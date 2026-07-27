package com.github.aljge.tensorspeak

/**
 * Fused float-PCM helpers used by the system TTS path.
 *
 * Keeps fade, clip, and little-endian PCM16 conversion in one pass so the service does not
 * walk the waveform three times or allocate a fresh byte array for every callback block.
 */
internal object PcmConverter {
    const val BYTES_PER_SAMPLE = 2

    /**
     * Apply a linear edge fade and clamp to [-1, 1] in place.
     *
     * @return [waveform] for chaining.
     */
    fun edgeFadeAndClip(
        waveform: FloatArray,
        sampleRate: Int = OnnxTts.SAMPLE_RATE,
        milliseconds: Float = 5.0f,
    ): FloatArray {
        val frames = minOf(
            Math.round(sampleRate * milliseconds / 1000.0f),
            waveform.size / 2,
        )
        for (i in waveform.indices) {
            var sample = waveform[i]
            if (frames > 0 && i < frames) {
                sample *= i.toFloat() / (frames - 1).coerceAtLeast(1)
            } else if (frames > 0 && i >= waveform.size - frames) {
                val rampIndex = waveform.size - 1 - i
                sample *= rampIndex.toFloat() / (frames - 1).coerceAtLeast(1)
            }
            waveform[i] = sample.coerceIn(-1.0f, 1.0f)
        }
        return waveform
    }

    /**
     * Convert `[offset, offset + count)` float samples into little-endian PCM16 at the
     * start of [out]. [out] must hold at least `count * 2` bytes.
     */
    fun floatToPcm16(waveform: FloatArray, offset: Int, count: Int, out: ByteArray) {
        require(offset >= 0 && count >= 0 && offset + count <= waveform.size)
        require(out.size >= count * BYTES_PER_SAMPLE)
        for (index in 0 until count) {
            val clamped = waveform[offset + index].coerceIn(-1.0f, 1.0f)
            val sample = (clamped * Short.MAX_VALUE).toInt()
            out[index * 2] = (sample and 0xFF).toByte()
            out[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
    }
}
