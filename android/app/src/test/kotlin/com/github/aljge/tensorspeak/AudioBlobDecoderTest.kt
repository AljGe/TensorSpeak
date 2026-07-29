package com.github.aljge.tensorspeak

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Only the WAV branch is testable from plain JUnit — the mp3 branch needs a real
 * [android.media.MediaCodec], so it's covered manually on-device (see CLAUDE.md).
 */
class AudioBlobDecoderTest {

    private fun buildWav(): ByteArray {
        val samples = shortArrayOf(0, 1000, -1000)
        val out = ByteArrayOutputStream()
        fun writeInt(value: Int) {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        }
        fun writeShort(value: Int) {
            out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
        }
        out.write("RIFF".toByteArray())
        writeInt(36 + samples.size * 2)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        writeInt(16)
        writeShort(1)
        writeShort(1)
        writeInt(24_000)
        writeInt(24_000 * 2)
        writeShort(2)
        writeShort(16)
        out.write("data".toByteArray())
        writeInt(samples.size * 2)
        for (sample in samples) writeShort(sample.toInt())
        return out.toByteArray()
    }

    @Test
    fun `routes wav bytes to the wav decoder`() {
        val wav = buildWav()
        val viaDispatcher = AudioBlobDecoder.decode(wav)
        val direct = WavPcmDecoder.decode(wav)
        assertEquals(direct.sampleRate, viaDispatcher.sampleRate)
        assertArrayEquals(direct.samples, viaDispatcher.samples, 1e-6f)
    }
}
