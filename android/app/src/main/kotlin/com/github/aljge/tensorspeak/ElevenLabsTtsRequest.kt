package com.github.aljge.tensorspeak

import org.json.JSONObject

/**
 * Builds the request for `POST https://api.elevenlabs.io/v1/text-to-speech/{voice_id}`.
 *
 * PCM output (`output_format=pcm_24000`) is gated to paid ElevenLabs tiers, so callers should
 * try [requestPcm] = true first and, if the account rejects it, retry with false to fall back
 * to the account's default (mp3) response — [CloudTts] owns that retry, this only builds the
 * request for a given attempt.
 */
object ElevenLabsTtsRequest {
    private const val BASE = "https://api.elevenlabs.io/v1/text-to-speech"

    fun build(
        text: String,
        selection: CloudVoiceSelection.ElevenLabs,
        requestPcm: Boolean = true,
    ): CloudTtsHttpRequest {
        val url = "$BASE/${selection.voiceId}" + if (requestPcm) "?output_format=pcm_24000" else ""
        val body = JSONObject()
            .put("text", text)
            .put("model_id", selection.model.id)
            .put(
                "voice_settings",
                JSONObject().put("stability", 0.5).put("similarity_boost", 0.75),
            )
        return CloudTtsHttpRequest(
            url = url,
            headers = mapOf(
                "xi-api-key" to selection.apiKey,
                "Content-Type" to "application/json",
            ),
            jsonBody = body.toString(),
        )
    }

    /** ElevenLabs error responses are `{"detail": {"message": "...", ...}}` (or a plain string). */
    fun errorMessage(responseBody: String): String = runCatching {
        val detail = JSONObject(responseBody).get("detail")
        if (detail is JSONObject) detail.getString("message") else detail.toString()
    }.getOrDefault(responseBody.take(200))
}
