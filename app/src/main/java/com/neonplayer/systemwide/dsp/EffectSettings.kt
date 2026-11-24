package com.neonplayer.systemwide.dsp

/**
 * Shared effect settings for both in-app playback (Android AudioEffects) and
 * system-wide PCM processing (DspEngine).
 *
 * Mix levels are 0..100. Equalizer bands are in millibels (-1500..1500 typical).
 */
data class EffectSettings(
    val enabled: Boolean = true,
    val equalizerEnabled: Boolean = true,
    val equalizerBandsMb: IntArray = intArrayOf(0, 0, 0, 0, 0),
    val virtual3dEnabled: Boolean = true,
    val virtual3dMix: Int = 50,          // 0..100 rounded mix control
    val surround71Enabled: Boolean = true,
    val surround71Mix: Int = 50,          // 0..100 rounded mix control
    val bassBoostEnabled: Boolean = true,
    val bassBoostStrength: Int = 50,      // 0..100
    val trebleBoostEnabled: Boolean = true,
    val trebleBoostStrength: Int = 40,    // 0..100
    val pan: Int = 0                      // -100 (left) .. 0 .. 100 (right)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EffectSettings) return false
        return enabled == other.enabled &&
            equalizerEnabled == other.equalizerEnabled &&
            equalizerBandsMb.contentEquals(other.equalizerBandsMb) &&
            virtual3dEnabled == other.virtual3dEnabled &&
            virtual3dMix == other.virtual3dMix &&
            surround71Enabled == other.surround71Enabled &&
            surround71Mix == other.surround71Mix &&
            bassBoostEnabled == other.bassBoostEnabled &&
            bassBoostStrength == other.bassBoostStrength &&
            trebleBoostEnabled == other.trebleBoostEnabled &&
            trebleBoostStrength == other.trebleBoostStrength &&
            pan == other.pan
    }

    override fun hashCode(): Int {
        var result = enabled.hashCode()
        result = 31 * result + equalizerEnabled.hashCode()
        result = 31 * result + equalizerBandsMb.contentHashCode()
        result = 31 * result + virtual3dEnabled.hashCode()
        result = 31 * result + virtual3dMix
        result = 31 * result + surround71Enabled.hashCode()
        result = 31 * result + surround71Mix
        result = 31 * result + bassBoostEnabled.hashCode()
        result = 31 * result + bassBoostStrength
        result = 31 * result + trebleBoostEnabled.hashCode()
        result = 31 * result + trebleBoostStrength
        result = 31 * result + pan
        return result
    }
}
