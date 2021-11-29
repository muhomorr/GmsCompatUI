package org.grapheneos.gmscompatui

import android.annotation.SuppressLint
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import androidx.annotation.Keep
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.lang.IllegalStateException
import java.lang.Long.parseUnsignedLong
import java.net.URL
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

const val BASE_URL = "https://apps.grapheneos.org/"
const val SIGNIFY_PUBLIC_KEY = "RWQtZwEu1br1lMh911L3yPOs97cQb9LOks/ALBbqGl21ul695ocWR/ir";

class InstallTask : Runnable {
    val destDir = File(appCtx.cacheDir!!, "repo")

    lateinit var downloadExecutor: ExecutorService

    val processedInstalls = AtomicInteger()
    val numInstalls = AtomicInteger()
    val downloadedBytes = AtomicLong()
    @Volatile var downloadsFinished = false

    @SuppressLint("MissingPermission")
    override fun run() {
        assume(destDir.deleteRecursively())
        downloadExecutor = Executors.newSingleThreadExecutor()

        val metadataSig = enqueueDownload("metadata.json.0.sig")
        val metadata = enqueueDownload("metadata.json")

        val installer = appCtx.packageManager.packageInstaller
        val multiSession = createMultiSession(installer)
        try {
            val repo = obtainRepo(metadataSig, metadata)

            val appInstalls = ArrayList<AppInstall>(3)
            Packages.NAMES.forEach { pkgName ->
                val appRelease = repo.apps[pkgName]!!.get("stable")!!

                if (Packages.isVersionCodeAtLeast(pkgName, appRelease.versionCode)) {
                    return@forEach
                }
                if (BuildConfig.hasSystemPrivileges) {
                    if (Packages.isVersionCodeAtLeast(pkgName, appRelease.versionCode, matchAnyUser = true)) {
                        InstallerResultReceiver().use {
                            installer.installExistingPackage(pkgName,
                                PackageManager.INSTALL_REASON_USER, it.getIntentSender())
                        }
                        return@forEach
                    }
                }
                val apks = appRelease.packages.mapIndexed { index, apkName ->
                    val apk = Apk(appRelease, pkgName, apkName, appRelease.hashes[index])
                    apk.fileFuture = enqueueDownload(apk.path(), apk.hash)
                    apk
                }
                appInstalls.add(AppInstall(pkgName, apks))
            }
            if (appInstalls.size == 0) {
                return
            }
            numInstalls.set(appInstalls.size)
            downloadExecutor.submit {
                downloadsFinished = true
            }

            // as of Android 12, PackageInstaller returns an instance of FileBridgeOutputStream,
            // which uses 8K buffer under the hood. But bigger buffer will be copied to directly
            // by FileInputStream (buffers larger than 12K are allocated immovably in large object space)
            // https://android.googlesource.com/platform/art/+/refs/tags/android-12.0.0_r15/runtime/gc/heap.h#148
            val streamBuffer = ByteArray(64 * 1024)
            try {
                appInstalls.forEach { appInstall ->
                    val session = createSession(installer, multiSession, appInstall.packageName)
                    appInstall.session = session

                    appInstall.apks.forEach { apk ->
                        val file = apk.fileFuture.get()
                        file.inputStream().use { inputStream ->
                        session.openWrite(apk.splitName, 0, file.length()).use { outputStream ->
                            while (true) {
                                val bufLen = inputStream.read(streamBuffer)
                                if (bufLen < 0) {
                                    break
                                }
                                outputStream.write(streamBuffer, 0, bufLen)
                            }
                        }}
                    }
                    if (!BuildConfig.hasSystemPrivileges) {
                        commitSession(session)
                    }
                    processedInstalls.incrementAndGet()
                }
            } catch (e: Throwable) {
                appInstalls.forEach {
                    it.session?.abandon()
                }
                throw e
            }
            if (BuildConfig.hasSystemPrivileges) {
                commitSession(multiSession!!)
            }
        } catch (e: Throwable) {
            if (BuildConfig.hasSystemPrivileges) {
                multiSession!!.abandon()
            }
            throw e
        } finally {
            downloadExecutor.shutdownNow()
            destDir.deleteRecursively()
        }
    }

    fun commitSession(session: PackageInstaller.Session) {
        InstallerResultReceiver().use {
            session.commit(it.getIntentSender())
        }
    }

    fun obtainRepo(metadataSigF: FutureTask<File>, metadataF: FutureTask<File>): Repo {
        val metadataSig = metadataSigF.get().readLines(Charsets.UTF_8).filter {
            !it.startsWith("untrusted comment")
        }.first()
        val metadata = metadataF.get().readBytes()

        assume(FileVerifier(SIGNIFY_PUBLIC_KEY).verifySignature(metadata, metadataSig),
            "signature verification failed")

        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val jsonAdapter = moshi.adapter(Repo::class.java)
        return jsonAdapter.fromJson(String(metadata, Charsets.UTF_8))!!
    }

    @Keep
    class Repo(
        val time: Long,
        val apps: Map<String, Map<String, App>>
    )

    @Keep
    class App(
        val packages: Array<String>,
        val hashes: Array<String>,
        val versionCode: Long
    )

    class Apk(
        val app: App,
        val packageName: String,
        val splitName: String,
        val hash: String,
    ) {
        lateinit var fileFuture: FutureTask<File>
        fun path() = "packages/${packageName}/${app.versionCode}/${splitName}"
    }

    class AppInstall(
        val packageName: String,
        val apks: List<Apk>,
        var session: PackageInstaller.Session? = null
    )

    // used only by the single-threaded download executor
    val downloadBuf = ByteArray(DEFAULT_BUFFER_SIZE)
    val msgDigest = MessageDigest.getInstance("SHA-256")

    fun enqueueDownload(path: String, expectedSha256: String? = null): FutureTask<File> {
        val task = FutureTask {
            val file = File(destDir, path)
            val dir = file.parentFile!!
            assume(dir.isDirectory || dir.mkdirs())

            val buf = downloadBuf

            URL(BASE_URL + path).openStream().use { input ->
            file.outputStream().use { output ->
                while (true) {
                    val bufLen = input.read(buf)
                    if (bufLen < 0) {
                        break
                    }
                    downloadedBytes.addAndGet(bufLen.toLong())

                    if (expectedSha256 != null) {
                        msgDigest.update(buf, 0, bufLen)
                    }
                    output.write(buf, 0, bufLen)
                }
            }}
            if (expectedSha256 != null) {
                val hash = msgDigest.digest()

                val bbuf = ByteBuffer.allocate(256 / 8)
                assume(expectedSha256.length == 64)
                for (i in 0..48 step 16) {
                    bbuf.putLong(parseUnsignedLong(expectedSha256.substring(i, i + 16), 16))
                }
                assume(bbuf.position() == bbuf.capacity())

                val expectedHash = bbuf.array()
                if (!expectedHash.contentEquals(hash)) {
                    throw IllegalStateException("corrupted file $path")
                }
            }
            file
        }
        downloadExecutor.submit(task)
        return task
    }

    fun createMultiSession(installer: PackageInstaller): PackageInstaller.Session? {
        if (!BuildConfig.hasSystemPrivileges) {
            return null
        }
        val sp = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        sp.setMultiPackage()
        val id = installer.createSession(sp)
        return installer.openSession(id)
    }

    fun createSession(installer: PackageInstaller, multiSession: PackageInstaller.Session?, packageName: String): PackageInstaller.Session {
        val sp = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        sp.setAppPackageName(packageName)
        val id = installer.createSession(sp)
        if (BuildConfig.hasSystemPrivileges) {
            multiSession!!.addChildSessionId(id)
        }
        val session = installer.openSession(id)
        return session
    }
}
