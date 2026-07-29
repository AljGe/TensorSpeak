package com.github.aljge.tensorspeak

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a compressed audio blob (mp3, the common ElevenLabs default response) into mono
 * float PCM using the platform's [MediaExtractor]/[MediaCodec], so no third-party codec
 * dependency is needed. Not reachable from a plain JVM unit test — a real codec is required —
 * so this is exercised manually on-device.
 */
object Mp3PcmDecoder {

    fun decode(bytes: ByteArray): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(ByteArrayMediaDataSource(bytes))
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("no audio track in response")
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val output = mutableListOf<Float>()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idleLoops = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex, 0, sampleSize, extractor.sampleTime, 0,
                            )
                            extractor.advance()
                        }
                        idleLoops = 0
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        idleLoops = 0
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        idleLoops++
                        if (idleLoops > MAX_IDLE_LOOPS) {
                            error("mp3 decode stalled (not audio, or codec hung)")
                        }
                    }
                    else -> if (outputIndex >= 0) {
                        idleLoops = 0
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            appendPcm16(outputBuffer, bufferInfo, channelCount, output)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            return DecodedAudio(sampleRate, output.toFloatArray())
        } finally {
            codec?.stop()
            codec?.release()
            extractor.release()
        }
    }

    private fun appendPcm16(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channelCount: Int,
        out: MutableList<Float>,
    ) {
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(info.offset)
        view.limit(info.offset + info.size)
        val shortsPerFrame = channelCount.coerceAtLeast(1)
        while (view.remaining() >= shortsPerFrame * 2) {
            var sum = 0f
            for (channel in 0 until shortsPerFrame) {
                sum += view.short / 32768f
            }
            out.add(sum / shortsPerFrame)
        }
    }

    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE_LOOPS = 5_000

    private class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val remaining = (data.size - position).toInt()
            val count = minOf(size, remaining)
            System.arraycopy(data, position.toInt(), buffer, offset, count)
            return count
        }

        override fun getSize(): Long = data.size.toLong()
        override fun close() = Unit
    }
}
