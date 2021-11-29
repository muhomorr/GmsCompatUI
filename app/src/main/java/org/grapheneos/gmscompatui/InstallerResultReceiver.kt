package org.grapheneos.gmscompatui

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageInstaller
import android.util.Log
import java.lang.RuntimeException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

private val lastId = AtomicLong()

class InstallerResultReceiver : BroadcastReceiver(), AutoCloseable {
    private var latch: CountDownLatch? = null
    private var result: Intent? = null

    fun getIntentSender(): IntentSender {
        assume(latch == null)
        latch = CountDownLatch(1)
        val action = javaClass.name + "." + lastId.getAndIncrement()
        appCtx.registerReceiver(this, IntentFilter(action))
        return PendingIntent.getBroadcast(
            appCtx, 0, Intent(action),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
        ).intentSender
    }

    override fun onReceive(brContext: Context?, intent: Intent?) {
        result = intent!!
        latch!!.countDown()
    }

    private fun awaitResult() {
        latch!!.await()

        while (true) {
            val res = result!!
            assume(res.hasExtra(PackageInstaller.EXTRA_STATUS))
            val status = res.getIntExtra(PackageInstaller.EXTRA_STATUS, 0)
            Log.d("status", res.extras.toString())

            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                assume(!BuildConfig.hasSystemPrivileges)
                val intent = res.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val latch2 = CountDownLatch(1)
                latch = latch2
                appCtx.startActivity(intent)
                latch2.await()
                continue
            }
            if (status != PackageInstaller.STATUS_SUCCESS) {
                throw RuntimeException(res.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            }
            return
        }
    }

    override fun close() {
        awaitResult()
        appCtx.unregisterReceiver(this)
    }
}
