package com.neonplayer.systemwide.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.neonplayer.systemwide.dsp.DspEngine
import com.neonplayer.systemwide.dsp.EffectState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * In-app player that decodes audio to PCM and processes it through DspEngine,
 * ensuring Virtual 3D / Surround 7.1 mix controls and other DSP effects work reliably.
 */
class PcmPlayer(private val context: Context) {

    interface Listener {
        fun onPrepared()
        fun onCompletion()
        fun onError(message: String)
    }

    var listener: Listener? = null

    @Volatile private var playing = false
    @Volatile private var released = false
    @Volatile private var prepared = false
    private var decodeThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private var engine: DspEngine = DspEngine(44100)
    private val mainHandler = Handler(Looper.getMainLooper())

    val isPlaying: Boolean get() = playing
    val isPrepared: Boolean get() = prepared

    fun setDataSource(uri: Uri) {
        stopInternal(releaseTrack = true)
        released = false
        prepared = false
        decodeThread = thread(name = "NeonPcmPlayer") { decodeLoop(uri) }
    }

    fun play() {
        playing = true
        try { audioTrack?.play() } catch (_: Exception) {}
    }

    fun pause() {
        playing = false
        try { audioTrack?.pause() } catch (_: Exception) {}
    }

    fun stop() {
        stopInternal(releaseTrack = true)
    }

    fun release() {
        released = true
        playing = false
        stopInternal(releaseTrack = true)
    }

    fun updateSettings() {
        engine.settings = EffectState.current()
    }

    private fun stopInternal(releaseTrack: Boolean) {
        playing = false
        prepared = false
        val t = decodeThread
        decodeThread = null
        t?.interrupt()
        try { t?.join(800) } catch (_: Exception) {}
        if (releaseTrack) {
            try { audioTrack?.pause() } catch (_: Exception) {}
            try { audioTrack?.flush() } catch (_: Exception) {}
            try { audioTrack?.release() } catch (_: Exception) {}
            audioTrack = null
        }
    }

    private fun decodeLoop(uri: Uri) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            val trackIndex = selectAudioTrack(extractor)
            if (trackIndex < 0) throw IllegalArgumentException("No audio track found")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalArgumentException("Missing MIME")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            engine = DspEngine(sampleRate)
            engine.settings = EffectState.current()

            audioTrack = buildTrack(sampleRate)
            prepared = true
            mainHandler.post { if (!released) listener?.onPrepared() }

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            while (!released && !Thread.currentThread().isInterrupted && !sawOutputEos) {
                if (!playing) {
                    try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                    continue
                }

                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            val size = extractor.readSampleData(inputBuffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                when {
                    outIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val pcm = toStereoPcm16(outputBuffer, bufferInfo.offset, bufferInfo.size, channelCount)
                            if (pcm.isNotEmpty()) {
                                engine.settings = EffectState.current()
                                val processed = engine.process(pcm, pcm.size)
                                writeToTrack(processed)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEos = true
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else channelCount
                        engine = DspEngine(sampleRate)
                        engine.settings = EffectState.current()
                        try { audioTrack?.pause() } catch (_: Exception) {}
                        try { audioTrack?.release() } catch (_: Exception) {}
                        audioTrack = buildTrack(sampleRate)
                        if (playing) try { audioTrack?.play() } catch (_: Exception) {}
                    }
                }
            }

            if (!released && sawOutputEos) {
                drainTrack()
                mainHandler.post { if (!released) listener?.onCompletion() }
            }
        } catch (e: Exception) {
            if (!released) {
                mainHandler.post { listener?.onError(e.message ?: "Playback failed") }
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    private fun buildTrack(sampleRate: Int): AudioTrack {
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = max(minBytes, 4096 * 4)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
    }

    private fun toStereoPcm16(buffer: ByteBuffer, offset: Int, size: Int, channelCount: Int): ShortArray {
        val dup = buffer.duplicate()
        dup.position(offset)
        dup.limit(offset + size)
        dup.order(ByteOrder.LITTLE_ENDIAN)
        val shortCount = size / 2
        val src = ShortArray(shortCount)
        dup.asShortBuffer().get(src)
        return when {
            channelCount <= 1 -> {
                val out = ShortArray(src.size * 2)
                for (i in src.indices) {
                    out[i * 2] = src[i]
                    out[i * 2 + 1] = src[i]
                }
                out
            }
            channelCount == 2 -> src
            else -> {
                // Downmix to stereo by taking first two channels per frame
                val frames = src.size / channelCount
                val out = ShortArray(frames * 2)
                for (i in 0 until frames) {
                    out[i * 2] = src[i * channelCount]
                    out[i * 2 + 1] = src[i * channelCount + 1]
                }
                out
            }
        }
    }

    private fun writeToTrack(samples: ShortArray) {
        var offset = 0
        while (offset < samples.size && !released) {
            val track = audioTrack ?: return
            val written = try {
                track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            } catch (_: Exception) { -1 }
            if (written < 0) break
            offset += written
        }
    }

    private fun drainTrack() {
        // Give the AudioTrack time to play buffered audio
        try {
            val track = audioTrack ?: return
            val frames = track.playbackHeadPosition
            Thread.sleep(100)
        } catch (_: Exception) {}
    }
}
