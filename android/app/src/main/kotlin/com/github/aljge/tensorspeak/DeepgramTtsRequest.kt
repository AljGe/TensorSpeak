package com.github.aljge.tensorspeak

import org.json.JSONObject

/**
 * Builds the request for `POST https://api.deepgram.com/v1/speak`. Asks for a WAV container
 * directly (`encoding=linear16&container=wav`) so the response goes through [WavPcmDecoder]
 * like OpenAI's, rather than needing the mp3/[Mp3PcmDecoder] path.
 */
object DeepgramTtsRequest {
    private const val BASE = "https://api.deepgram.com/v1/speak"

    fun build(text: String, selection: CloudVoiceSelection.Deepgram): CloudTtsHttpRequest {
        val url = "$BASE?model=${selection.voice.id}&encoding=linear16&sample_rate=24000&container=wav"
        return CloudTtsHttpRequest(
            url = url,
            headers = mapOf(
                "Authorization" to "Token ${selection.apiKey}",
                "Content-Type" to "application/json",
            ),
            jsonBody = JSONObject().put("text", text).toString(),
        )
    }

    /** Deepgram error responses are `{"err_code": "...", "err_msg": "...", ...}`. */
    fun errorMessage(responseBody: String): String = runCatching {
        JSONObject(responseBody).getString("err_msg")
    }.getOrDefault(responseBody.take(200))
}
