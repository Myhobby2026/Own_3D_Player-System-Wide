package com.neonplayer.systemwide.systemwide

/**
 * Dependency-free processing flag shared between in-app DSP bypass and the
 * system-wide service. Kept Android-API free so it can be safely referenced
 * from audio hot paths.
 */
object SystemWideState {
    @Volatile
    var isProcessing: Boolean = false
}
