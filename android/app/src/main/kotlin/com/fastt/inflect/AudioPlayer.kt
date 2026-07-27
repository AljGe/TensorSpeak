package com.fastt.inflect

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.Closeable

/** Plays the pipeline's float PCM at the model's native 24 kHz, mono. */
class AudioPlayer : Closeable {

    private var track: AudioTrack? = null

    fun play(waveform: FloatArray) {
        stop()

        val minBuffer = AudioTrack.getMinBufferSize(
            OnnxTts.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bufferBytes = maxOf(minBuffer, waveform.size * Float.SIZE_BYTES)

        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(OnnxTts.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        newTrack.write(waveform, 0, waveform.size, AudioTrack.WRITE_BLOCKING)
        newTrack.play()
        track = newTrack
    }

    fun stop() {
        track?.let {
            if (it.state == AudioTrack.STATE_INITIALIZED) it.stop()
            it.release()
        }
        track = null
    }

    override fun close() = stop()
}
