package org.grapheneos.gmscompatui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.ScrollView
import android.widget.TextView
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.Exception
import java.lang.StringBuilder
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

lateinit var appCtx: Context
val taskExecutor = Executors.newSingleThreadExecutor()
val mainThreadHandler = Handler(Looper.getMainLooper())

var taskRunnable: Runnable? = null
var taskFuture: FutureTask<Runnable>? = null
var lastTaskResult = ""

const val UPDATE_INTERVAL = 200L

class MainActivity : Activity() {
    lateinit var layout: LinearLayout
    lateinit var statusText: TextView

    fun executeTask(task: Runnable) {
        if (taskFuture != null) {
            return // in case user manages to rapidly double click the button
        }
        taskFuture = FutureTask(task, null)
        taskRunnable = task
        taskExecutor.submit(taskFuture)
        setup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appCtx = applicationContext

        layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        val pad = px(16f)
        layout.setPadding(pad, pad, pad, pad)
        val scroller = ScrollView(this)
        scroller.isScrollbarFadingEnabled = false
        scroller.addView(layout)
        setContentView(scroller)
    }

    @SuppressLint("BatteryLife", "SetTextI18n")
    fun setup() {
        layout.removeAllViews()

        text(R.string.header)
        button(R.string.learn_more, {
            val uri = Uri.parse("https://grapheneos.org/usage#sandboxed-play-services")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        })
        separator()

        val piGsf = Packages.info(GSF_PACKAGE_NAME)
        val piGms = Packages.info(GMS_PACKAGE_NAME)
        val piPlayStore = Packages.info(PLAY_STORE_PACKAGE_NAME)

        val installed = piGsf != null && piGms != null && piPlayStore != null

        if (!installed) {
            button(R.string.install, {
                executeTask(InstallTask())
            }).isEnabled = taskRunnable == null
        } else {
            val s = getString(R.string.are_installed) +
            """
                GSF: ${piGsf!!.versionName} (${piGsf.longVersionCode})
                GMS: ${piGms!!.versionName} (${piGms.longVersionCode})
                Play Store: ${piPlayStore!!.versionName} (${piPlayStore.longVersionCode})
            """.trimIndent()
            text(s, selectable = true)
        }
        separator()

        if (installed) {
            if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(GMS_PACKAGE_NAME)) {
                button(R.string.grant_battery_optimization_exemption, {
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.fromParts("package", GMS_PACKAGE_NAME, null))
                    startActivity(i)
                    setup()
                }).isEnabled = installed
                text(R.string.grant_battery_optimization_exemption_subtitle)
                separator()
            }

            button(R.string.settings, {
                val i = Intent().setClassName(GMS_PACKAGE_NAME, GMS_PACKAGE_NAME +
                ".app.settings.GoogleSettingsLink")
    //            ".app.settings.GoogleSettingsIALink")
    //            ".app.settings.GoogleSettingsActivity")
                startActivity(i)
            })
            separator()

            button(R.string.system_settings, {
                val uri = Uri.fromParts("package", GMS_PACKAGE_NAME, null)
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
                startActivity(i)
            })
            separator()

            button(R.string.uninstall, {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.uninstall) + "?")
                    .setMessage(R.string.uninstall_subtitle)
                    .setPositiveButton(R.string.yes, { _, _ -> executeTask(UninstallTask()) })
                    .setNegativeButton(R.string.no, null)
                    .show()
            }).isEnabled = installed
            separator()
        }

        statusText = text("", selectable = true)
        updateStatus()
    }

    var updateCounter = 0

    fun updateStatus() {
        val taskF = taskFuture
        if (taskF != null) {
            if (taskF.isDone) {
                try {
                    taskF.get()
                    lastTaskResult = ""
                } catch (e: Throwable) {
                    val cause = e.cause!!
                    val sw = StringWriter(1000)
                    cause.printStackTrace(PrintWriter(sw))
                    lastTaskResult = sw.toString()
                }
                taskFuture = null
                taskRunnable = null
                setup()
            }
        }
        val task = taskRunnable
        val text =
        when (task) {
            is InstallTask -> {
                val numInstalls = task.numInstalls.get()
                val txt = StringBuilder(100)
                if (!task.downloadsFinished) {
                    txt.append(getString(R.string.downloading,
                        task.downloadedBytes.get() / 1_000_000L))
                    if (numInstalls != 0) {
                        txt.append('\n')
                    }
                }
                if (numInstalls != 0) {
                    txt.append(getString(R.string.installing, task.processedInstalls.get(),
                        numInstalls))
                }
                repeat(updateCounter and 0b11, {
                    txt.append('.')
                })
                txt
            } is UninstallTask -> {
                getText(R.string.uninstalling)
            } else -> {
                lastTaskResult
            }
        }
        statusText.setText(text)
        ++ updateCounter

        if (taskFuture != null) {
            mainThreadHandler.postDelayed({
                if (resumed && taskRunnable != null) {
                    updateStatus()
                }
            }, UPDATE_INTERVAL)
        }
    }

    fun button(label: Int, onClickListener: View.OnClickListener?): Button {
        val v = Button(this)
        v.setText(label)
        v.textSize = 16f
        v.setAllCaps(false)
        v.setOnClickListener(onClickListener)
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        layout.addView(v, lp)
        return v
    }

    fun text(str: Int, size: Float = 16f, selectable: Boolean = false): TextView {
        return text(resources.getString(str), size, selectable)
    }

    fun text(str: String, size: Float = 16f, selectable: Boolean = false): TextView {
        val v = TextView(this)
        v.setText(str)
        val pad = px(4f)
        v.setPadding(pad, 0, pad, 0)
        v.textSize = size
        v.setTextIsSelectable(selectable)
        layout.addView(v)
        return v
    }

    fun separator() {
        val v = View(this)
        v.setBackgroundColor(0x80_808080.toInt())
        v.minimumHeight = px(2f)
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        val pad = px(12f)
        lp.setMargins(0, pad, 0, pad)
        layout.addView(v, lp)
    }

    var resumed = false

    override fun onResume() {
        super.onResume()
        resumed = true
        setup()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    fun px(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
    }

    /*
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu!!.add(R.string.allow_updates_to_unknown_versions)
        return true
    }

    override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean {
        return super.onMenuItemSelected(featureId, item)
    }
     */
}
