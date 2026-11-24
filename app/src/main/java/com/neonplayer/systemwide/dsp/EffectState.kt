package com.neonplayer.systemwide.dsp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide effect settings shared by the in-app player UI and the
 * system-wide capture service.
 */
object EffectState {
    private val _settings = MutableStateFlow(EffectSettings())
    val settings: StateFlow<EffectSettings> = _settings

    fun update(transform: (EffectSettings) -> EffectSettings) {
        _settings.value = transform(_settings.value)
    }

    fun set(settings: EffectSettings) {
        _settings.value = settings
    }

    fun current(): EffectSettings = _settings.value
}
