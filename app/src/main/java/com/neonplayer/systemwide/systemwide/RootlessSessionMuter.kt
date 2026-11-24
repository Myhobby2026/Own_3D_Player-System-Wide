package com.neonplayer.systemwide.systemwide

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.util.UUID

/**
 * Suppresses original (unprocessed) playback sessions so the listener hears
 * only the processed AudioTrack. Session discovery and mute-effect fallback
 * follow the RootlessJamesDSP rootless approach.
 */
class RootlessSessionMuter(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onMuteControlLost(message: String)
        fun onCapturePolicyChanged()
    }

    private val appContext = context.applicationContext
    private val dumpReader = ServiceDumpReader(appContext)
    private val resolver = Resolver(appContext)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val muted = linkedMapOf<Int, MuteEffect>()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var disposed = false
    @Volatile private var protectedSessionId: Int = -1
    @Volatile private var restrictedUids: IntArray = intArrayOf()
    @Volatile private var lastPolicy: AudioSessionDumpParser.PolicyDump = AudioSessionDumpParser.PolicyDump()
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (disposed) return
            try {
                pollOnce()
            } catch (e: Throwable) {
                listener.onMuteControlLost("Session dump failed: ${e.message}")
            }
            if (!disposed) handler?.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() {
        if (disposed || thread != null) return
        val t = HandlerThread("NeonSystemWideSessions")
        t.start()
        thread = t
        val h = Handler(t.looper)
        handler = h

        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                if (!disposed) {
                    h.removeCallbacks(pollRunnable)
                    h.post(pollRunnable)
                }
            }
        }
        playbackCallback = cb
        audioManager?.registerAudioPlaybackCallback(cb, h)
        h.post(pollRunnable)
    }

    fun refreshPoliciesBlocking(): IntArray {
        val policyDump = dumpReader.read("media.audio_policy")
        if (policyDump.isNullOrBlank()) {
            throw IllegalStateException("Audio policy dump unavailable; check the ADB DUMP grant")
        }
        val policy = AudioSessionDumpParser.parseAudioPolicy(policyDump, resolver)
        lastPolicy = policy
        val uids = AudioSessionDumpParser.restrictedUids(policy, resolver)
        val ownUid = Process.myUid()
        val filtered = uids.filter { it > 0 && it != ownUid }.distinct().toIntArray()
        val previous = restrictedUids
        restrictedUids = filtered
        if (!previous.contentEquals(filtered)) listener.onCapturePolicyChanged()
        return filtered
    }

    fun getRestrictedUids(): IntArray = restrictedUids

    fun setProtectedSessionId(sessionId: Int) {
        protectedSessionId = sessionId
    }

    fun destroy() {
        disposed = true
        audioManager?.let { am ->
            playbackCallback?.let { cb ->
                try { am.unregisterAudioPlaybackCallback(cb) } catch (_: Exception) {}
            }
        }
        handler?.removeCallbacksAndMessages(null)
        synchronized(muted) {
            muted.values.forEach { it.release() }
            muted.clear()
        }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun pollOnce() {
        if (disposed) return
        refreshPoliciesBlocking()
        val audioDump = dumpReader.read("audio")
        if (audioDump.isNullOrBlank()) {
            listener.onMuteControlLost(
                "Audio service dump unavailable; check the ADB DUMP grant. Stopped to avoid double audio."
            )
            return
        }
        val flingerDump = dumpReader.read("media.audio_flinger")
        if (Build.VERSION.SDK_INT < 31 && flingerDump.isNullOrBlank()) {
            listener.onMuteControlLost(
                "AudioFlinger dump unavailable on Android 10/11; check the ADB DUMP grant. Stopped to avoid double audio."
            )
            return
        }

        var sessions = AudioSessionDumpParser.parseAudioService(audioDump, flingerDump, resolver)
        if (sessions.isEmpty()) sessions = lastPolicy.sessions

        val restricted = restrictedUids.toHashSet()
        val outputSession = protectedSessionId
        val wanted = linkedMapOf<Int, AudioSessionDumpParser.Session>()
        for ((sid, session) in sessions) {
            if (session.sessionId <= 0 || session.sessionId == outputSession || !session.isRecordableUsage()) continue
            if (session.uid in restricted) continue
            val allowed = lastPolicy.captureAllowedByPackage[session.packageName]
            if (allowed == false) continue
            wanted[sid] = session
        }

        synchronized(muted) {
            val it = muted.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (!wanted.containsKey(entry.key)) {
                    entry.value.release()
                    it.remove()
                }
            }
            for ((sid, session) in wanted) {
                if (disposed) break
                if (muted.containsKey(sid)) continue
                val effect = createMuteEffect(session.sessionId)
                if (effect == null) {
                    listener.onMuteControlLost(
                        "This device rejected the mute effect for audio session ${session.sessionId}. Stopped to avoid double audio."
                    )
                    return
                }
                muted[sid] = effect
            }
        }
    }

    private fun createMuteEffect(sessionId: Int): MuteEffect? {
        if (Build.VERSION.SDK_INT >= 28 && isEffectTypeAvailable(AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING)) {
            try {
                val processing = DynamicsProcessing(Int.MAX_VALUE, sessionId, null)
                processing.setInputGainAllChannelsTo(-200f)
                return MuteEffect(processing, true, sessionId)
            } catch (_: Throwable) {
            }
        }
        return try {
            val ctor = AudioEffect::class.java.getDeclaredConstructor(
                UUID::class.java, UUID::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            ctor.isAccessible = true
            val volume = ctor.newInstance(VOLUME_TYPE, null, Int.MAX_VALUE, sessionId) as AudioEffect
            val setParameter = AudioEffect::class.java.getMethod("setParameter", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            setParameter.invoke(volume, 2, 1) // mute
            setParameter.invoke(volume, 0, -96) // level
            MuteEffect(volume, false, sessionId)
        } catch (_: Throwable) {
            null
        }
    }

    private fun isEffectTypeAvailable(type: UUID): Boolean {
        return try {
            AudioEffect.queryEffects()?.any { it?.type == type } == true
        } catch (_: Throwable) {
            false
        }
    }

    private inner class MuteEffect(
        private val effect: AudioEffect,
        private val dynamics: Boolean,
        private val sessionId: Int
    ) {
        private var releasing = false

        init {
            try {
                effect.setEnableStatusListener { _, enabled ->
                    if (releasing || disposed) return@setEnableStatusListener
                    if (!enabled) {
                        try {
                            if (dynamics && effect is DynamicsProcessing) effect.setInputGainAllChannelsTo(-200f)
                            effect.enabled = true
                        } catch (_: Throwable) {
                            listener.onMuteControlLost(
                                "Another app disabled the mute effect for session $sessionId. Stopped to avoid double audio."
                            )
                        }
                    }
                }
                effect.setControlStatusListener { _, controlGranted ->
                    if (releasing || disposed) return@setControlStatusListener
                    if (!controlGranted) {
                        listener.onMuteControlLost(
                            "Another app took audio effect control for session $sessionId. Stopped to avoid double audio."
                        )
                    } else {
                        try {
                            if (dynamics && effect is DynamicsProcessing) effect.setInputGainAllChannelsTo(-200f)
                            effect.enabled = true
                        } catch (_: Throwable) {}
                    }
                }
                effect.enabled = true
            } catch (e: Throwable) {
                release()
                throw IllegalStateException(e)
            }
        }

        fun release() {
            releasing = true
            try { effect.enabled = false } catch (_: Throwable) {}
            try { effect.release() } catch (_: Throwable) {}
        }
    }

    private class Resolver(context: Context) : AudioSessionDumpParser.ReversePackageResolver {
        private val pm: PackageManager = context.packageManager
        override fun packageForUid(uid: Int): String = try {
            pm.getNameForUid(uid) ?: uid.toString()
        } catch (_: Throwable) { uid.toString() }

        override fun uidForPackage(packageName: String): Int = try {
            pm.getApplicationInfo(packageName, 0).uid
        } catch (_: Throwable) { -1 }
    }

    companion object {
        private val VOLUME_TYPE: UUID = UUID.fromString("09e8ede0-ddde-11db-b4f6-0002a5d5c51b")
        private const val POLL_INTERVAL_MS = 1000L
    }
}
