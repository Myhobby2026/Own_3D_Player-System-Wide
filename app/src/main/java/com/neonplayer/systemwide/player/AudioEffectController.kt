package com.neonplayer.systemwide.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.os.Build
import com.neonplayer.systemwide.dsp.EffectSettings
import kotlin.math.roundToInt

/**
 * Applies effect settings to Android AudioEffects for in-app playback.
 *
 * Note: Virtual 3D and Surround 7.1 mix levels are fully honored in the
 * system-wide PCM DSP engine. For in-app playback we map them to Android's
 * Virtualizer / strength controls where possible.
 */
class AudioEffectController {

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var sessionId: Int = 0

    fun attachToSession(audioSessionId: Int) {
        if (audioSessionId == 0 || audioSessionId == sessionId) return
        release()
        sessionId = audioSessionId
        try { equalizer = Equalizer(0, audioSessionId).apply { enabled = true } } catch (_: Throwable) {}
        try { bassBoost = BassBoost(0, audioSessionId).apply { enabled = true } } catch (_: Throwable) {}
        try { virtualizer = Virtualizer(0, audioSessionId).apply { enabled = true } } catch (_: Throwable) {}
        try { loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply { enabled = true } } catch (_: Throwable) {}
    }

    fun apply(settings: EffectSettings) {
        // Equalizer
        equalizer?.let { eq ->
            try {
                eq.enabled = settings.enabled && settings.equalizerEnabled
                val bands = eq.numberOfBands.toInt()
                val minMb = eq.bandLevelRange[0].toInt()
                val maxMb = eq.bandLevelRange[1].toInt()
                for (i in 0 until bands) {
                    val src = settings.equalizerBandsMb.getOrElse(i) { 0 }
                    // Also blend in treble boost on highest bands
                    val trebleAdd = if (settings.trebleBoostEnabled && i >= bands - 2) {
                        (settings.trebleBoostStrength * 6) // up to +600mB
                    } else 0
                    val target = (src + trebleAdd).coerceIn(minMb, maxMb)
                    eq.setBandLevel(i.toShort(), target.toShort())
                }
            } catch (_: Throwable) {}
        }

        // Bass boost
        bassBoost?.let { bb ->
            try {
                val strength = if (settings.enabled && settings.bassBoostEnabled) {
                    (settings.bassBoostStrength.coerceIn(0, 100) * 10).toShort()
                } else 0.toShort()
                bb.setStrength(strength)
                bb.enabled = strength > 0
            } catch (_: Throwable) {}
        }

        // Virtual 3D + Surround 7.1 mapped to Virtualizer strength.
        // The full per-effect mix is applied in system-wide DspEngine.
        virtualizer?.let { virt ->
            try {
                val v3d = if (settings.enabled && settings.virtual3dEnabled) settings.virtual3dMix else 0
                val s71 = if (settings.enabled && settings.surround71Enabled) settings.surround71Mix else 0
                val combined = ((v3d.coerceIn(0, 100) * 0.6f) + (s71.coerceIn(0, 100) * 0.4f))
                    .roundToInt().coerceIn(0, 100)
                val strength = (combined * 10).coerceIn(0, 1000).toShort()
                if (virt.strengthSupported) virt.setStrength(strength)
                virt.enabled = strength > 0
            } catch (_: Throwable) {}
        }

        // Loudness/treble presence assist
        loudnessEnhancer?.let { le ->
            try {
                val gainMb = if (settings.enabled && settings.trebleBoostEnabled) {
                    (settings.trebleBoostStrength.coerceIn(0, 100) * 5) // 0..500mB
                } else 0
                le.setTargetGain(gainMb)
                le.enabled = gainMb > 0
            } catch (_: Throwable) {}
        }
    }

    fun release() {
        try { equalizer?.release() } catch (_: Throwable) {}
        try { bassBoost?.release() } catch (_: Throwable) {}
        try { virtualizer?.release() } catch (_: Throwable) {}
        try { loudnessEnhancer?.release() } catch (_: Throwable) {}
        equalizer = null; bassBoost = null; virtualizer = null; loudnessEnhancer = null
        sessionId = 0
    }
}
