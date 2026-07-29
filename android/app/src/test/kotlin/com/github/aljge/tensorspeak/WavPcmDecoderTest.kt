package com.github.aljge.tensorspeak

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WavPcmDecoderTest {

    private fun buildWav(sampleRate: Int, channels: Int, samples: ShortArray): ByteArray {
        val bitsPerSample = 16
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream()
        fun writeInt(value: Int) {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        }
        fun writeShort(value: Int) {
            out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
        }
        out.write("RIFF".toByteArray())
        writeInt(36 + dataSize)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        writeInt(16)
        writeShort(1) // PCM
        writeShort(channels)
        writeInt(sampleRate)
        writeInt(sampleRate * blockAlign)
        writeShort(blockAlign)
        writeShort(bitsPerSample)
        out.write("data".toByteArray())
        writeInt(dataSize)
        for (sample in samples) writeShort(sample.toInt())
        return out.toByteArray()
    }

    @Test
    fun `decodes mono 16-bit pcm`() {
        val samples = shortArrayOf(0, 16384, -16384, 32767)
        val wav = buildWav(sampleRate = 24_000, channels = 1, samples = samples)
        val decoded = WavPcmDecoder.decode(wav)

        assertEquals(24_000, decoded.sampleRate)
        assertEquals(samples.size, decoded.samples.size)
        assertEquals(0f, decoded.samples[0], 1e-6f)
        assertEquals(0.5f, decoded.samples[1], 1e-3f)
        assertEquals(-0.5f, decoded.samples[2], 1e-3f)
    }

    @Test
    fun `downmixes stereo to mono by averaging channels`() {
        // Frame 0: left=32767, right=-32768 -> average ~ 0.
        val wav = buildWav(sampleRate = 22_050, channels = 2, samples = shortArrayOf(32767, -32768))
        val decoded = WavPcmDecoder.decode(wav)

        assertEquals(22_050, decoded.sampleRate)
        assertEquals(1, decoded.samples.size)
        assertEquals(0f, decoded.samples[0], 1e-3f)
    }

    @Test
    fun `looksLikeWav recognizes a RIFF WAVE header`() {
        val wav = buildWav(sampleRate = 24_000, channels = 1, samples = shortArrayOf(0))
        assertTrue(WavPcmDecoder.looksLikeWav(wav))
        assertFalse(WavPcmDecoder.looksLikeWav("ID3".toByteArray()))
        assertFalse(WavPcmDecoder.looksLikeWav(ByteArray(4)))
    }

    @Test
    fun `clamps Deepgram-style sentinel data chunk sizes`() {
        // Deepgram writes data size 0x7FFF0024 (or similar) instead of the real length.
        val samples = shortArrayOf(0, 1000, -1000, 2000)
        val real = buildWav(sampleRate = 24_000, channels = 1, samples = samples)
        val corrupt = real.copyOf()
        // Overwrite the 4-byte data-chunk size at offset 40 (RIFF/WAVE/fmt(24)/data-id).
        val sentinel = 0x7FFF0024
        corrupt[40] = (sentinel and 0xFF).toByte()
        corrupt[41] = ((sentinel shr 8) and 0xFF).toByte()
        corrupt[42] = ((sentinel shr 16) and 0xFF).toByte()
        corrupt[43] = ((sentinel shr 24) and 0xFF).toByte()

        val decoded = WavPcmDecoder.decode(corrupt)
        assertEquals(24_000, decoded.sampleRate)
        assertEquals(samples.size, decoded.samples.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a truncated header`() {
        WavPcmDecoder.decode(byteArrayOf(1, 2, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-RIFF file`() {
        WavPcmDecoder.decode(ByteArray(20))
    }
}
