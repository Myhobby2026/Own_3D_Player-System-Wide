package com.neonplayer.systemwide.systemwide

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import com.neonplayer.systemwide.R
import com.neonplayer.systemwide.dsp.DspEngine
import com.neonplayer.systemwide.dsp.EffectState

/**
 * Rootless system-wide audio pipeline (same approach as reference):
 * MediaProjection playback capture -> DSP (DspEngine) -> non-capturable AudioTrack,
 * with DUMP-discovered source sessions muted to prevent double audio.
 */
class SystemWideEffectService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var sessionMuter: RootlessSessionMuter? = null
    private var captureThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var recreateRecorderRequested = false
    @Volatile private var reloadRequested = false
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                stopProcessing("Stopped by user")
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                reloadRequested = true
                updateNotification("Reloading DSP settings…")
                return START_NOT_STICKY
            }
        }
        if (running) return START_NOT_STICKY
        if (intent.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT < 29) {
            stopProcessing("System-wide effects require Android 10 or newer")
            return START_NOT_STICKY
        }
        if (!SystemWidePermissions.hasDump(this) || !SystemWidePermissions.hasRecordAudio(this)) {
            stopProcessing("Missing DUMP or microphone/capture permission")
            return START_NOT_STICKY
        }

        @Suppress("DEPRECATION")
        val projectionData = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
        if (projectionData == null) {
            stopProcessing("Missing audio capture consent")
            return START_NOT_STICKY
        }

        startForegroundCompat("Starting system-wide effects…")
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = manager.getMediaProjection(Activity.RESULT_OK, projectionData)
                ?: throw IllegalStateException("MediaProjection was rejected")
            projection = proj
            proj.registerCallback(projectionCallback, mainHandler)
        } catch (e: Throwable) {
            stopProcessing("Capture consent failed: ${e.message}")
            return START_NOT_STICKY
        }

        val muter = RootlessSessionMuter(this, object : RootlessSessionMuter.Listener {
            override fun onMuteControlLost(message: String) = stopProcessing(message)
            override fun onCapturePolicyChanged() { recreateRecorderRequested = true }
        })
        sessionMuter = muter
        muter.start()

        running = true
        val thread = Thread(captureRunnable, "NeonSystemWideCapture")
        captureThread = thread
        thread.start()
        // Single-use projection token on Android 14+; no redelivery.
        return START_NOT_STICKY
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (running) stopProcessing("Audio capture permission was revoked")
        }
    }

    private val captureRunnable = Runnable {
        var recorder: AudioRecord? = null
        var track: AudioTrack? = null
        var failure: String? = null
        val dsp = DspEngine(SAMPLE_RATE)
        try {
            val muter = sessionMuter ?: throw IllegalStateException("Session muter not initialized")
            muter.refreshPoliciesBlocking()
            recorder = buildRecorder(muter)
            track = buildTrack()
            muter.setProtectedSessionId(track.audioSessionId)

            dsp.settings = EffectState.current()
            recorder.startRecording()
            track.play()
            SystemWideState.isProcessing = true
            lastStatus = "Processing system-wide audio"
            updateNotification(lastStatus)

            val buffer = ShortArray(FRAMES_PER_READ * 2)
            while (running && !Thread.currentThread().isInterrupted) {
                if (reloadRequested) {
                    reloadRequested = false
                    dsp.settings = EffectState.current()
                    updateNotification("Processing system-wide audio")
                }
                if (recreateRecorderRequested) {
                    recreateRecorderRequested = false
                    try { recorder?.stop() } catch (_: Exception) {}
                    try { recorder?.release() } catch (_: Exception) {}
                    recorder = buildRecorder(muter)
                    recorder.startRecording()
                }

                val read = recorder!!.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read == 0) continue
                if (read < 0) throw IllegalStateException("AudioRecord.read failed: $read")

                val processed = dsp.process(buffer, read)
                val written = track.write(processed, 0, processed.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) throw IllegalStateException("AudioTrack.write failed: $written")
            }
        } catch (e: Throwable) {
            failure = e.message ?: e.javaClass.simpleName
        } finally {
            SystemWideState.isProcessing = false
            running = false
            releaseQuietly(recorder, track)
            if (failure != null) lastStatus = "Stopped: $failure"
            else if (lastStatus != "Stopped by user") lastStatus = "Stopped"
            mainHandler.post {
                updateNotification(lastStatus)
                stopSelf()
            }
        }
    }

    private fun buildRecorder(muter: RootlessSessionMuter): AudioRecord {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val configBuilder = AudioPlaybackCaptureConfiguration.Builder(projection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)

        // Do not exclude our own UID: local player audio is captured and its
        // in-app effects can be bypassed while system-wide processing is active.
        for (uid in muter.getRestrictedUids()) {
            if (uid != Process.myUid()) configBuilder.excludeUid(uid)
        }

        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(minBytes, FRAMES_PER_READ * 2 * 2 * 2)
        val recorder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(configBuilder.build())
            .build()
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("Audio playback capture could not be initialized")
        }
        return recorder
    }

    private fun buildTrack(): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(minBytes, FRAMES_PER_READ * 2 * 2 * 2)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("Processed output track could not be initialized")
        }
        return track
    }

    private fun stopProcessing(reason: String) {
        lastStatus = reason
        running = false
        SystemWideState.isProcessing = false
        mainHandler.post {
            updateNotification(lastStatus)
            stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        SystemWideState.isProcessing = false
        captureThread?.let { t ->
            t.interrupt()
            try { t.join(1000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            captureThread = null
        }
        sessionMuter?.destroy()
        sessionMuter = null
        projection?.let { p ->
            try { p.unregisterCallback(projectionCallback) } catch (_: Exception) {}
            try { p.stop() } catch (_: Exception) {}
        }
        projection = null
        if (foregroundStarted) {
            @Suppress("DEPRECATION")
            stopForeground(true)
            foregroundStarted = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun updateNotification(text: String) {
        if (!foregroundStarted) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, SystemWideEffectService::class.java).apply { action = ACTION_STOP }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val stopPending = PendingIntent.getService(this, 1, stopIntent, flags)
        val contentPending = PendingIntent.getActivity(this, 2, Intent(this, SystemWideSetupActivity::class.java), flags)

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId())
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("System-wide effects")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(channelId(), "System-wide effects", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Rootless audio capture and DSP processing"
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun channelId(): String = "$packageName.systemwide"

    private fun releaseQuietly(recorder: AudioRecord?, track: AudioTrack?) {
        if (recorder != null) {
            try { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
        }
        if (track != null) {
            try { if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
        }
    }

    companion object {
        const val ACTION_START = "com.neonplayer.systemwide.START"
        const val ACTION_STOP = "com.neonplayer.systemwide.STOP"
        const val ACTION_RELOAD = "com.neonplayer.systemwide.RELOAD"
        const val EXTRA_PROJECTION_DATA = "projectionData"

        private const val NOTIFICATION_ID = 30901
        private const val SAMPLE_RATE = 44100
        private const val FRAMES_PER_READ = 1024

        @Volatile
        var lastStatus: String = "Stopped"
            private set

        fun isProcessing(): Boolean = SystemWideState.isProcessing

        fun getStatus(): String = if (SystemWideState.isProcessing) "Processing system-wide audio" else lastStatus

        fun stop(context: Context) {
            context.startService(Intent(context, SystemWideEffectService::class.java).apply { action = ACTION_STOP })
        }

        fun reload(context: Context) {
            context.startService(Intent(context, SystemWideEffectService::class.java).apply { action = ACTION_RELOAD })
        }
    }
}
