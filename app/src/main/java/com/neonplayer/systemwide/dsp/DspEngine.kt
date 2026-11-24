package com.neonplayer.systemwide.dsp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.PI

/**
 * Stereo PCM16 DSP engine used by the system-wide capture pipeline.
 *
 * Effects: 5-band EQ, Virtual 3D (stereo widen/spatialize), Surround 7.1
 * (virtual surround mix), Bass Boost (low shelf), Treble Boost (high shelf), PAN.
 *
 * Virtual 3D and Surround 7.1 expose mix levels (0..100) that control wet/dry blend.
 */
class DspEngine(private val sampleRate: Int = 44100) {

    @Volatile
    var settings: EffectSettings = EffectSettings()

    private val eqBandsHz = floatArrayOf(60f, 230f, 910f, 3600f, 14000f)
    private val eqFiltersL = Array(eqBandsHz.size) { Biquad() }
    private val eqFiltersR = Array(eqBandsHz.size) { Biquad() }
    private val bassL = Biquad()
    private val bassR = Biquad()
    private val trebleL = Biquad()
    private val trebleR = Biquad()

    // Simple delay lines for surround/3D spatial cues
    private val delaySize = (sampleRate * 0.030f).toInt().coerceAtLeast(1) // 30ms
    private val delayL = FloatArray(delaySize)
    private val delayR = FloatArray(delaySize)
    private var delayPos = 0

    private var lastSettings: EffectSettings? = null

    fun reset() {
        eqFiltersL.forEach { it.reset() }
        eqFiltersR.forEach { it.reset() }
        bassL.reset(); bassR.reset()
        trebleL.reset(); trebleR.reset()
        delayL.fill(0f); delayR.fill(0f)
        delayPos = 0
        lastSettings = null
    }

    fun process(input: ShortArray, length: Int): ShortArray {
        val s = settings
        val out = ShortArray(length)
        if (!s.enabled || length <= 0) {
            System.arraycopy(input, 0, out, 0, length)
            return out
        }

        updateFiltersIfNeeded(s)

        val v3dMix = if (s.virtual3dEnabled) s.virtual3dMix.coerceIn(0, 100) / 100f else 0f
        val s71Mix = if (s.surround71Enabled) s.surround71Mix.coerceIn(0, 100) / 100f else 0f
        val pan = s.pan.coerceIn(-100, 100) / 100f
        val panL = min(1f, 1f - pan)
        val panR = min(1f, 1f + pan)

        val frames = length / 2
        for (i in 0 until frames) {
            var l = input[i * 2].toFloat() / 32768f
            var r = input[i * 2 + 1].toFloat() / 32768f

            // EQ
            if (s.equalizerEnabled) {
                for (b in eqBandsHz.indices) {
                    l = eqFiltersL[b].process(l)
                    r = eqFiltersR[b].process(r)
                }
            }

            // Bass boost (low shelf)
            if (s.bassBoostEnabled && s.bassBoostStrength > 0) {
                l = bassL.process(l)
                r = bassR.process(r)
            }

            // Treble boost (high shelf)
            if (s.trebleBoostEnabled && s.trebleBoostStrength > 0) {
                l = trebleL.process(l)
                r = trebleR.process(r)
            }

            // Store dry signal for wet/dry mixes
            val dryL = l
            val dryR = r

            // Virtual 3D: mid-side widening + subtle crossfeed
            if (v3dMix > 0f) {
                val mid = (dryL + dryR) * 0.5f
                val side = (dryL - dryR) * 0.5f
                val widen = 1f + (v3dMix * 1.2f)
                val wL = mid + side * widen
                val wR = mid - side * widen
                // subtle crossfeed for spatial depth
                val cross = v3dMix * 0.08f
                l = lerp(l, wL + dryR * cross, v3dMix)
                r = lerp(r, wR + dryL * cross, v3dMix)
            }

            // Surround 7.1: virtual surround using short delays and wider crossfeed.
            // Mix level controls wet/dry blend as requested.
            if (s71Mix > 0f) {
                val dL = delayL[delayPos]
                val dR = delayR[delayPos]
                // feed delayed cross-mix
                delayL[delayPos] = dryL + dR * 0.25f
                delayR[delayPos] = dryR + dL * 0.25f
                delayPos = (delayPos + 1) % delaySize

                val surroundL = dryL + dR * 0.45f + (dryL - dryR) * 0.15f
                val surroundR = dryR + dL * 0.45f + (dryR - dryL) * 0.15f
                l = lerp(l, surroundL, s71Mix)
                r = lerp(r, surroundR, s71Mix)
            }

            // PAN
            l *= panL
            r *= panR

            // Soft clip / clamp
            out[i * 2] = floatToPcm16(softClip(l))
            out[i * 2 + 1] = floatToPcm16(softClip(r))
        }
        return out
    }

    private fun updateFiltersIfNeeded(s: EffectSettings) {
        if (s == lastSettings) return
        // EQ peaking filters
        for (i in eqBandsHz.indices) {
            val gainDb = (s.equalizerBandsMb.getOrElse(i) { 0 } / 100f).coerceIn(-15f, 15f)
            eqFiltersL[i].setPeaking(sampleRate.toFloat(), eqBandsHz[i], 1.1f, gainDb)
            eqFiltersR[i].setPeaking(sampleRate.toFloat(), eqBandsHz[i], 1.1f, gainDb)
        }
        // Bass shelf
        val bassDb = (s.bassBoostStrength.coerceIn(0, 100) / 100f) * 12f
        bassL.setLowShelf(sampleRate.toFloat(), 90f, 0.8f, bassDb)
        bassR.setLowShelf(sampleRate.toFloat(), 90f, 0.8f, bassDb)
        // Treble shelf
        val trebleDb = (s.trebleBoostStrength.coerceIn(0, 100) / 100f) * 10f
        trebleL.setHighShelf(sampleRate.toFloat(), 8000f, 0.8f, trebleDb)
        trebleR.setHighShelf(sampleRate.toFloat(), 8000f, 0.8f, trebleDb)
        lastSettings = s.copy()
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun softClip(x: Float): Float {
        // Simple soft clip to avoid harsh digital clipping
        return when {
            x > 1f -> 1f - (1f / (x + 1f)) * 0.5f
            x < -1f -> -1f + (1f / (-x + 1f)) * 0.5f
            else -> x
        }.coerceIn(-1f, 1f)
    }

    private fun floatToPcm16(v: Float): Short =
        (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

    private class Biquad {
        private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
        private var a1 = 0f; private var a2 = 0f
        private var x1 = 0f; private var x2 = 0f
        private var y1 = 0f; private var y2 = 0f

        fun reset() { x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            // Guard against denormals/NaN
            if (!y.isFinite()) {
                reset()
                return 0f
            }
            return y
        }

        fun setPeaking(fs: Float, freq: Float, q: Float, gainDb: Float) {
            val a = 10f.pow(gainDb / 40f)
            val w0 = (2f * PI.toFloat() * freq / fs)
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2f * q)
            val b0n = 1f + alpha * a
            val b1n = -2f * cosW0
            val b2n = 1f - alpha * a
            val a0n = 1f + alpha / a
            val a1n = -2f * cosW0
            val a2n = 1f - alpha / a
            setNormalized(b0n, b1n, b2n, a0n, a1n, a2n)
        }

        fun setLowShelf(fs: Float, freq: Float, q: Float, gainDb: Float) {
            val a = 10f.pow(gainDb / 40f)
            val w0 = (2f * PI.toFloat() * freq / fs)
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2f * q)
            val sqrtA = kotlin.math.sqrt(a)
            val b0n = a * ((a + 1f) - (a - 1f) * cosW0 + 2f * sqrtA * alpha)
            val b1n = 2f * a * ((a - 1f) - (a + 1f) * cosW0)
            val b2n = a * ((a + 1f) - (a - 1f) * cosW0 - 2f * sqrtA * alpha)
            val a0n = (a + 1f) + (a - 1f) * cosW0 + 2f * sqrtA * alpha
            val a1n = -2f * ((a - 1f) + (a + 1f) * cosW0)
            val a2n = (a + 1f) + (a - 1f) * cosW0 - 2f * sqrtA * alpha
            setNormalized(b0n, b1n, b2n, a0n, a1n, a2n)
        }

        fun setHighShelf(fs: Float, freq: Float, q: Float, gainDb: Float) {
            val a = 10f.pow(gainDb / 40f)
            val w0 = (2f * PI.toFloat() * freq / fs)
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2f * q)
            val sqrtA = kotlin.math.sqrt(a)
            val b0n = a * ((a + 1f) + (a - 1f) * cosW0 + 2f * sqrtA * alpha)
            val b1n = -2f * a * ((a - 1f) + (a + 1f) * cosW0)
            val b2n = a * ((a + 1f) + (a - 1f) * cosW0 - 2f * sqrtA * alpha)
            val a0n = (a + 1f) - (a - 1f) * cosW0 + 2f * sqrtA * alpha
            val a1n = 2f * ((a - 1f) - (a + 1f) * cosW0)
            val a2n = (a + 1f) - (a - 1f) * cosW0 - 2f * sqrtA * alpha
            setNormalized(b0n, b1n, b2n, a0n, a1n, a2n)
        }

        private fun setNormalized(b0n: Float, b1n: Float, b2n: Float, a0n: Float, a1n: Float, a2n: Float) {
            b0 = b0n / a0n; b1 = b1n / a0n; b2 = b2n / a0n
            a1 = a1n / a0n; a2 = a2n / a0n
        }
    }
}
