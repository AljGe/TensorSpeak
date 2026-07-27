package com.github.aljge.tensorspeak

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmConverterTest {

    @Test
    fun `edge fade ramps endpoints and clips`() {
        val waveform = FloatArray(48) { 2.0f }
        PcmConverter.edgeFadeAndClip(waveform, sampleRate = 1000, milliseconds = 5.0f)
        // 5 ms @ 1 kHz => 5 frames each end.
        assertEquals(0.0f, waveform.first(), 1e-6f)
        assertEquals(1.0f, waveform[4], 1e-6f)
        assertEquals(1.0f, waveform[waveform.lastIndex - 4], 1e-6f)
        assertEquals(0.0f, waveform.last(), 1e-6f)
        assertTrue(waveform.all { it in -1.0f..1.0f })
    }

    @Test
    fun `pcm16 is little endian and saturates`() {
        val waveform = floatArrayOf(-1.0f, 0.0f, 1.0f)
        val out = ByteArray(6)
        PcmConverter.floatToPcm16(waveform, 0, 3, out)
        // -1.0 * Short.MAX_VALUE => -32767 (0x8001 LE)
        assertEquals(0x01.toByte(), out[0])
        assertEquals(0x80.toByte(), out[1])
        // 0
        assertEquals(0x00.toByte(), out[2])
        assertEquals(0x00.toByte(), out[3])
        // 32767 (0x7FFF LE)
        assertEquals(0xFF.toByte(), out[4])
        assertEquals(0x7F.toByte(), out[5])
    }

    @Test
    fun `pcm16 converts a slice into the start of the buffer`() {
        val waveform = floatArrayOf(0.5f, -0.5f, 0.25f)
        val out = ByteArray(8)
        PcmConverter.floatToPcm16(waveform, 1, 1, out)
        val expected = ByteArray(8)
        val sample = (-0.5f * Short.MAX_VALUE).toInt()
        expected[0] = (sample and 0xFF).toByte()
        expected[1] = ((sample shr 8) and 0xFF).toByte()
        assertArrayEquals(expected, out)
    }
}
