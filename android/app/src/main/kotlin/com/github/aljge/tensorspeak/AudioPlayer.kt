package com.github.aljge.tensorspeak

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.Closeable

/** Plays float PCM mono audio. Sample rate defaults to the on-device model rate (24 kHz). */
class AudioPlayer : Closeable {

    private var track: AudioTrack? = null
    private var sampleRateHz: Int = OnnxTts.SAMPLE_RATE

    /** Blocking play of a complete waveform (tests / short clips). */
    fun play(waveform: FloatArray, sampleRate: Int = OnnxTts.SAMPLE_RATE) {
        stop()
        sampleRateHz = sampleRate
        val newTrack = buildTrack(
            bufferBytes = maxOf(minBufferBytes(), waveform.size * Float.SIZE_BYTES),
            mode = AudioTrack.MODE_STATIC,
        )
        newTrack.write(waveform, 0, waveform.size, AudioTrack.WRITE_BLOCKING)
        newTrack.play()
        track = newTrack
    }

    /**
     * Open a streaming track so the first decoded chunk can start before the rest of the
     * utterance is ready. Returns false from [write] if the track was stopped.
     */
    fun startStreaming(sampleRate: Int = OnnxTts.SAMPLE_RATE): Boolean {
        stop()
        sampleRateHz = sampleRate
        val newTrack = buildTrack(
            bufferBytes = minBufferBytes() * 2,
            mode = AudioTrack.MODE_STREAM,
        )
        newTrack.play()
        track = newTrack
        return true
    }

    fun write(waveform: FloatArray): Boolean {
        val current = track ?: return false
        if (current.playState != AudioTrack.PLAYSTATE_PLAYING &&
            current.playState != AudioTrack.PLAYSTATE_PAUSED
        ) {
            return false
        }
        var offset = 0
        while (offset < waveform.size) {
            val written = current.write(
                waveform,
                offset,
                waveform.size - offset,
                AudioTrack.WRITE_BLOCKING,
            )
            if (written < 0) return false
            offset += written
        }
        return true
    }

    fun stop() {
        track?.let {
            if (it.state == AudioTrack.STATE_INITIALIZED) {
                runCatching { it.pause() }
                runCatching { it.flush() }
                runCatching { it.stop() }
            }
            it.release()
        }
        track = null
    }

    override fun close() = stop()

    private fun minBufferBytes(): Int = AudioTrack.getMinBufferSize(
        sampleRateHz,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_FLOAT,
    ).coerceAtLeast(sampleRateHz / 10 * Float.SIZE_BYTES)

    private fun buildTrack(bufferBytes: Int, mode: Int): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(mode)
            .build()
}
