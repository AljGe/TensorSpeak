package com.github.aljge.tensorspeak

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Synthesizes text against a commercial cloud TTS API. Unlike [OnnxTts], each provider call
 * is a batch HTTP request returning one audio blob — there is no incremental decode to stream
 * from, so this fetches, decodes, and returns a complete waveform. Long text is still split
 * with [TextChunker] to respect provider request-size limits and to keep natural pause
 * placement between sentences; each chunk costs its own round trip.
 */
class CloudTts {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeCall: Call? = null

    data class Result(val sampleRate: Int, val samples: FloatArray)

    class CloudTtsException(message: String) : IOException(message)

    suspend fun synthesize(
        text: String,
        speed: Float,
        selection: CloudVoiceSelection,
        shouldContinue: () -> Boolean = { true },
    ): Result = withContext(Dispatchers.IO) {
        val normalized = TextChunker.collapseWhitespace(text)
        val limit = maxCharsFor(selection)
        val chunks = if (normalized.length <= limit) {
            listOf(normalized)
        } else {
            TextChunker.split(normalized, limit = limit, firstChunkLimit = limit)
        }

        val pieces = mutableListOf<FloatArray>()
        var sampleRate = OnnxTts.SAMPLE_RATE
        for ((index, chunk) in chunks.withIndex()) {
            if (!shouldContinue()) break
            if (index > 0) {
                val pause = TextChunker.boundaryPauseSeconds(chunks[index - 1])
                pieces.add(FloatArray(Math.round(sampleRate * pause)))
            }
            val decoded = fetchAndDecode(chunk, speed, selection)
            sampleRate = decoded.sampleRate
            pieces.add(decoded.samples)
        }

        val total = pieces.sumOf { it.size }
        val waveform = FloatArray(total)
        var offset = 0
        for (piece in pieces) {
            piece.copyInto(waveform, offset)
            offset += piece.size
        }
        Result(sampleRate, waveform)
    }

    fun requestStop() {
        activeCall?.cancel()
    }

    private fun maxCharsFor(selection: CloudVoiceSelection): Int = when (selection) {
        is CloudVoiceSelection.OpenAi -> OPENAI_MAX_CHARS
        is CloudVoiceSelection.ElevenLabs -> ELEVENLABS_MAX_CHARS
        is CloudVoiceSelection.Deepgram -> DEEPGRAM_MAX_CHARS
        is CloudVoiceSelection.Custom -> CUSTOM_MAX_CHARS
    }

    private fun fetchAndDecode(
        chunk: String,
        speed: Float,
        selection: CloudVoiceSelection,
    ): DecodedAudio = when (selection) {
        is CloudVoiceSelection.OpenAi -> {
            val request = OpenAiTtsRequest.build(chunk, selection, speed)
            AudioBlobDecoder.decode(execute(request, OpenAiTtsRequest::errorMessage))
        }
        is CloudVoiceSelection.ElevenLabs -> fetchElevenLabs(chunk, selection)
        is CloudVoiceSelection.Deepgram -> {
            val request = DeepgramTtsRequest.build(chunk, selection)
            AudioBlobDecoder.decode(execute(request, DeepgramTtsRequest::errorMessage))
        }
        is CloudVoiceSelection.Custom -> {
            val request = CustomCloudTtsRequest.build(chunk, selection)
            AudioBlobDecoder.decode(execute(request) { it.take(200) })
        }
    }

    /**
     * PCM output is plan-gated on ElevenLabs, so this tries the raw `pcm_24000` format first
     * (no container to decode) and falls back to the account's default mp3 response if the
     * account rejects that query param.
     */
    private fun fetchElevenLabs(
        chunk: String,
        selection: CloudVoiceSelection.ElevenLabs,
    ): DecodedAudio {
        val pcmRequest = ElevenLabsTtsRequest.build(chunk, selection, requestPcm = true)
        val pcmBytes = runCatching {
            execute(pcmRequest, ElevenLabsTtsRequest::errorMessage)
        }
        if (pcmBytes.isSuccess) {
            return RawPcm16Decoder.decode(pcmBytes.getOrThrow(), sampleRate = 24_000)
        }
        val fallbackRequest = ElevenLabsTtsRequest.build(chunk, selection, requestPcm = false)
        return AudioBlobDecoder.decode(
            execute(fallbackRequest, ElevenLabsTtsRequest::errorMessage)
        )
    }

    private fun execute(
        request: CloudTtsHttpRequest,
        parseError: (String) -> String,
    ): ByteArray {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.addHeader(name, value) }
        builder.post(request.jsonBody.toRequestBody(JSON))
        val call = client.newCall(builder.build())
        activeCall = call
        try {
            call.execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (!response.isSuccessful) {
                    throw CloudTtsException(
                        "HTTP ${response.code}: ${parseError(String(bytes))}"
                    )
                }
                return bytes
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()

        // OpenAI's `input` field caps at 4096 chars; ElevenLabs' limit is plan-dependent
        // (commonly 2500-5000) so this stays conservative; Deepgram's `/v1/speak` caps at
        // 2000 bytes of `text`; the custom endpoint is assumed OpenAI-compatible unless the
        // user's server says otherwise.
        const val OPENAI_MAX_CHARS = 4096
        const val ELEVENLABS_MAX_CHARS = 2000
        const val DEEPGRAM_MAX_CHARS = 2000
        const val CUSTOM_MAX_CHARS = 4096
    }
}

/** Interprets raw, headerless 16-bit little-endian mono PCM (ElevenLabs' `pcm_*` formats). */
object RawPcm16Decoder {
    fun decode(bytes: ByteArray, sampleRate: Int): DecodedAudio {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(bytes.size / 2)
        for (index in samples.indices) {
            samples[index] = buffer.short / 32768f
        }
        return DecodedAudio(sampleRate, samples)
    }
}
