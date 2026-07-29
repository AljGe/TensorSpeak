package com.github.aljge.tensorspeak

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A decoded, mono, [-1, 1]-normalized waveform plus the sample rate it was recorded at. */
data class DecodedAudio(val sampleRate: Int, val samples: FloatArray)

/**
 * Parses a RIFF/WAVE byte stream (`fmt `/`data` chunks) into mono float PCM, downmixing
 * multi-channel audio by averaging channels per frame. Supports 8/16/24/32-bit integer PCM
 * and 32-bit IEEE float, which covers what OpenAI's `response_format=wav` and the bundled
 * Cloudflare Worker (MeloTTS) emit.
 */
object WavPcmDecoder {
    private const val FORMAT_PCM = 1
    private const val FORMAT_IEEE_FLOAT = 3

    fun decode(bytes: ByteArray): DecodedAudio {
        require(bytes.size >= 12) { "wav data too short" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4).also { buffer.get(it) }
        require(String(riff) == "RIFF") { "not a RIFF file" }
        buffer.int // overall chunk size, unused
        val wave = ByteArray(4).also { buffer.get(it) }
        require(String(wave) == "WAVE") { "not a WAVE file" }

        var audioFormat = FORMAT_PCM
        var channels = 1
        var sampleRate = 0
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0

        while (buffer.remaining() >= 8) {
            val idBytes = ByteArray(4).also { buffer.get(it) }
            val id = String(idBytes)
            val size = buffer.int
            when (id) {
                "fmt " -> {
                    val fmtStart = buffer.position()
                    audioFormat = buffer.short.toInt()
                    channels = buffer.short.toInt()
                    sampleRate = buffer.int
                    buffer.int // byte rate, unused
                    buffer.short // block align, unused
                    bitsPerSample = buffer.short.toInt()
                    buffer.position(fmtStart + size + (size and 1))
                }
                "data" -> {
                    dataOffset = buffer.position()
                    dataSize = size
                    buffer.position((buffer.position() + size + (size and 1)).coerceAtMost(bytes.size))
                }
                else -> {
                    val next = buffer.position() + size + (size and 1)
                    if (next > bytes.size || next < buffer.position()) break
                    buffer.position(next)
                }
            }
        }

        require(sampleRate > 0) { "wav missing fmt chunk" }
        require(dataOffset >= 0) { "wav missing data chunk" }
        require(channels >= 1) { "wav has no channels" }

        val bytesPerSample = bitsPerSample / 8
        val frameSize = bytesPerSample * channels
        val frameCount = if (frameSize > 0) dataSize / frameSize else 0
        val data = ByteBuffer.wrap(bytes, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN)

        val samples = FloatArray(frameCount)
        for (frame in 0 until frameCount) {
            var sum = 0f
            for (channel in 0 until channels) {
                sum += readSample(data, audioFormat, bitsPerSample)
            }
            samples[frame] = sum / channels
        }
        return DecodedAudio(sampleRate, samples)
    }

    private fun readSample(data: ByteBuffer, audioFormat: Int, bitsPerSample: Int): Float =
        when {
            audioFormat == FORMAT_IEEE_FLOAT && bitsPerSample == 32 -> data.float
            bitsPerSample == 8 -> ((data.get().toInt() and 0xFF) - 128) / 128f
            bitsPerSample == 16 -> data.short / 32768f
            bitsPerSample == 24 -> {
                val b0 = data.get().toInt() and 0xFF
                val b1 = data.get().toInt() and 0xFF
                val b2 = data.get().toInt()
                val value = (b2 shl 16) or (b1 shl 8) or b0
                value / 8388608f
            }
            bitsPerSample == 32 -> data.int / 2147483648f
            else -> error("unsupported wav bit depth: $bitsPerSample")
        }

    /** True if [bytes] starts with a RIFF/WAVE header. */
    fun looksLikeWav(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() &&
            bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte()
}
