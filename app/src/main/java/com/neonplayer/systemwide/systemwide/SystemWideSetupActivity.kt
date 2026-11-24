package com.neonplayer.systemwide.systemwide

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.neonplayer.systemwide.R

class SystemWideSetupActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var checklistView: TextView

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply {
            setBackgroundResource(R.drawable.bg_app_gradient)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(root, ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT
        ))

        root.addView(titleText("SYSTEM-WIDE EFFECTS"))
        root.addView(bodyText(getString(R.string.system_wide_desc)))

        statusView = bodyText("").apply {
            setTextColor(ContextCompat.getColor(this@SystemWideSetupActivity, R.color.neon_green))
            typeface = Typeface.MONOSPACE
        }
        checklistView = bodyText("").apply {
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(this@SystemWideSetupActivity, R.color.text_secondary))
        }
        root.addView(sectionLabel("Status"))
        root.addView(statusView)
        root.addView(sectionLabel("Checklist"))
        root.addView(checklistView)

        root.addView(neonButton("Grant Microphone / Notification") { requestRuntimePermissions() })
        root.addView(neonButton("Copy ADB Setup Commands") { copyAdbCommands() })
        root.addView(neonButton("Open Developer Options") { openDeveloperOptions() })
        root.addView(neonButton("Start System-Wide Effects") { startEffects() })
        root.addView(neonButton("Reload Current DSP Settings") {
            if (!SystemWideEffectService.isProcessing()) {
                toast("Start system-wide effects first")
            } else {
                SystemWideEffectService.reload(this)
                toast("DSP reload requested")
            }
        })
        root.addView(neonButton("Stop System-Wide Effects") {
            SystemWideEffectService.stop(this)
            refreshStatus()
        })

        root.addView(sectionLabel("Limitations"))
        root.addView(bodyText(getString(R.string.limitations)))

        setContentView(scroll)

        if (Build.VERSION.SDK_INT < 29) {
            AlertDialog.Builder(this)
                .setTitle("Unsupported Android version")
                .setMessage("System-wide rootless effects require Android 10 (API 29) or newer.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun refreshStatus() {
        statusView.text = "Service: ${SystemWideEffectService.getStatus()}"
        checklistView.text = SystemWidePermissions.checklist(this)
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 23) return
        val missing = mutableListOf<String>()
        if (!SystemWidePermissions.hasRecordAudio(this)) missing.add(android.Manifest.permission.RECORD_AUDIO)
        if (!SystemWidePermissions.hasNotifications(this)) missing.add(android.Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isEmpty()) {
            toast("Runtime permissions already granted")
            return
        }
        requestPermissions(missing.toTypedArray(), REQUEST_RUNTIME_PERMISSIONS)
    }

    private fun startEffects() {
        if (Build.VERSION.SDK_INT < 29) { toast("Requires Android 10 or newer"); return }
        if (SystemWideEffectService.isProcessing()) { toast("Already processing"); return }
        if (!SystemWidePermissions.hasDump(this) || !SystemWidePermissions.hasProjectMediaAppOp(this)) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.adb_required_title))
                .setMessage(getString(R.string.adb_required_msg, SystemWidePermissions.adbCommands(this)))
                .setPositiveButton("Copy commands") { _, _ -> copyAdbCommands() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        if (!SystemWidePermissions.hasRecordAudio(this)) {
            requestRuntimePermissions()
            toast("Grant microphone/capture permission, then press Start again")
            return
        }
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
        } catch (e: Exception) {
            toast("Could not open capture consent: ${e.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            toast("Audio capture consent was cancelled")
            return
        }
        val service = Intent(this, SystemWideEffectService::class.java).apply {
            action = SystemWideEffectService.ACTION_START
            putExtra(SystemWideEffectService.EXTRA_PROJECTION_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        refreshStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun copyAdbCommands() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("ADB setup", SystemWidePermissions.adbCommands(this)))
        toast("ADB commands copied")
    }

    private fun openDeveloperOptions() {
        try {
            startActivity(Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun titleText(text: String) = TextView(this).apply {
        this.text = text
        textSize = 24f
        setTextColor(ContextCompat.getColor(context, R.color.neon_cyan))
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        letterSpacing = 0.08f
        gravity = Gravity.CENTER
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 12f
        setTextColor(ContextCompat.getColor(context, R.color.neon_magenta))
        letterSpacing = 0.12f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun bodyText(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun neonButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(ContextCompat.getColor(context, R.color.neon_cyan))
        setBackgroundResource(R.drawable.bg_neon_button)
        setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(10)
        layoutParams = lp
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_CAPTURE = 30902
        private const val REQUEST_RUNTIME_PERMISSIONS = 30903
    }
}
