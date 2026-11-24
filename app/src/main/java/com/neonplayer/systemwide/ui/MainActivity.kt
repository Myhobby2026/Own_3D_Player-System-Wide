package com.neonplayer.systemwide.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.neonplayer.systemwide.R
import com.neonplayer.systemwide.databinding.ActivityMainBinding
import com.neonplayer.systemwide.dsp.EffectSettings
import com.neonplayer.systemwide.dsp.EffectState
import com.neonplayer.systemwide.player.PcmPlayer
import com.neonplayer.systemwide.systemwide.SystemWideSetupActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pcmPlayer: PcmPlayer? = null
    private var currentUri: Uri? = null

    private val openAudioLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            loadTrack(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupKnobs()
        setupControls()
        restoreUiFromSettings(EffectState.current())
    }

    private fun setupKnobs() {
        binding.knob3dMix.label = "MIX"
        binding.knob3dMix.accentColor = ContextCompat.getColor(this, R.color.neon_cyan)
        binding.knob3dMix.value = 50
        binding.knob3dMix.onValueChangeListener = object : NeonKnobView.OnValueChangeListener {
            override fun onValueChanged(value: Int, fromUser: Boolean) {
                if (fromUser) updateSettings { it.copy(virtual3dMix = value) }
            }
        }

        binding.knobSurroundMix.label = "MIX"
        binding.knobSurroundMix.accentColor = ContextCompat.getColor(this, R.color.neon_magenta)
        binding.knobSurroundMix.value = 50
        binding.knobSurroundMix.onValueChangeListener = object : NeonKnobView.OnValueChangeListener {
            override fun onValueChanged(value: Int, fromUser: Boolean) {
                if (fromUser) updateSettings { it.copy(surround71Mix = value) }
            }
        }

        binding.knobBass.label = "LEVEL"
        binding.knobBass.accentColor = ContextCompat.getColor(this, R.color.neon_green)
        binding.knobBass.value = 50
        binding.knobBass.onValueChangeListener = object : NeonKnobView.OnValueChangeListener {
            override fun onValueChanged(value: Int, fromUser: Boolean) {
                if (fromUser) updateSettings { it.copy(bassBoostStrength = value) }
            }
        }

        binding.knobTreble.label = "LEVEL"
        binding.knobTreble.accentColor = ContextCompat.getColor(this, R.color.neon_amber)
        binding.knobTreble.value = 40
        binding.knobTreble.onValueChangeListener = object : NeonKnobView.OnValueChangeListener {
            override fun onValueChanged(value: Int, fromUser: Boolean) {
                if (fromUser) updateSettings { it.copy(trebleBoostStrength = value) }
            }
        }
    }

    private fun setupControls() {
        binding.btnOpen.setOnClickListener { openAudioLauncher.launch(arrayOf("audio/*")) }
        binding.btnPlayPause.setOnClickListener { togglePlayPause() }
        binding.btnStop.setOnClickListener { stopPlayback() }
        binding.btnSystemWide.setOnClickListener {
            startActivity(Intent(this, SystemWideSetupActivity::class.java))
        }

        binding.switchEq.setOnCheckedChangeListener { _, checked -> updateSettings { it.copy(equalizerEnabled = checked) } }
        binding.switch3d.setOnCheckedChangeListener { _, checked -> updateSettings { it.copy(virtual3dEnabled = checked) } }
        binding.switchSurround.setOnCheckedChangeListener { _, checked -> updateSettings { it.copy(surround71Enabled = checked) } }
        binding.switchBass.setOnCheckedChangeListener { _, checked -> updateSettings { it.copy(bassBoostEnabled = checked) } }
        binding.switchTreble.setOnCheckedChangeListener { _, checked -> updateSettings { it.copy(trebleBoostEnabled = checked) } }

        val eqBars = listOf(binding.eqBand0, binding.eqBand1, binding.eqBand2, binding.eqBand3, binding.eqBand4)
        eqBars.forEachIndexed { index, seekBar ->
            seekBar.setOnSeekBarChangeListener(object : SimpleSeekListener() {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val mb = (progress - 1500).coerceIn(-1500, 1500)
                    updateSettings { s ->
                        val bands = s.equalizerBandsMb.copyOf()
                        if (index < bands.size) bands[index] = mb
                        s.copy(equalizerBandsMb = bands)
                    }
                }
            })
        }

        binding.seekPan.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pan = (progress - 100).coerceIn(-100, 100)
                binding.panValue.text = when {
                    pan == 0 -> "Center"
                    pan < 0 -> "Left ${-pan}%"
                    else -> "Right ${pan}%"
                }
                if (fromUser) updateSettings { it.copy(pan = pan) }
            }
        })
    }

    private fun restoreUiFromSettings(s: EffectSettings) {
        binding.switchEq.isChecked = s.equalizerEnabled
        binding.switch3d.isChecked = s.virtual3dEnabled
        binding.switchSurround.isChecked = s.surround71Enabled
        binding.switchBass.isChecked = s.bassBoostEnabled
        binding.switchTreble.isChecked = s.trebleBoostEnabled
        binding.knob3dMix.value = s.virtual3dMix
        binding.knobSurroundMix.value = s.surround71Mix
        binding.knobBass.value = s.bassBoostStrength
        binding.knobTreble.value = s.trebleBoostStrength
        binding.seekPan.progress = s.pan + 100
        val eqBars = listOf(binding.eqBand0, binding.eqBand1, binding.eqBand2, binding.eqBand3, binding.eqBand4)
        eqBars.forEachIndexed { i, bar -> bar.progress = (s.equalizerBandsMb.getOrElse(i) { 0 } + 1500).coerceIn(0, 3000) }
    }

    private fun updateSettings(transform: (EffectSettings) -> EffectSettings) {
        EffectState.update(transform)
        pcmPlayer?.updateSettings()
    }

    private fun ensurePlayer(): PcmPlayer {
        val existing = pcmPlayer
        if (existing != null) return existing
        val player = PcmPlayer(applicationContext)
        player.listener = object : PcmPlayer.Listener {
            override fun onPrepared() {
                runOnUiThread {
                    player.updateSettings()
                    player.play()
                    binding.btnPlayPause.text = getString(R.string.pause)
                }
            }

            override fun onCompletion() {
                runOnUiThread { binding.btnPlayPause.text = getString(R.string.play) }
            }

            override fun onError(message: String) {
                runOnUiThread { toast("Playback error: $message") }
            }
        }
        pcmPlayer = player
        return player
    }

    private fun loadTrack(uri: Uri) {
        stopPlayback(releaseOnly = true)
        currentUri = uri
        binding.trackName.text = resolveDisplayName(uri)
        try {
            val player = ensurePlayer()
            player.updateSettings()
            player.setDataSource(uri)
        } catch (e: Exception) {
            toast("Unable to open audio: ${e.message}")
        }
    }

    private fun togglePlayPause() {
        val player = pcmPlayer
        if (player == null || !player.isPrepared) {
            currentUri?.let { loadTrack(it) } ?: toast("Open an audio file first")
            return
        }
        if (player.isPlaying) {
            player.pause()
            binding.btnPlayPause.text = getString(R.string.play)
        } else {
            player.play()
            binding.btnPlayPause.text = getString(R.string.pause)
        }
    }

    private fun stopPlayback(releaseOnly: Boolean = false) {
        try { pcmPlayer?.release() } catch (_: Exception) {}
        pcmPlayer = null
        if (!releaseOnly) {
            binding.btnPlayPause.text = getString(R.string.play)
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
            } ?: uri.lastPathSegment ?: getString(R.string.no_track)
        } catch (_: Exception) {
            uri.lastPathSegment ?: getString(R.string.no_track)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        stopPlayback(releaseOnly = true)
        super.onDestroy()
    }

    private abstract class SimpleSeekListener : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}
