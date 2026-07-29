package com.github.aljge.tensorspeak

import org.json.JSONObject

/** Builds the request for `POST https://api.openai.com/v1/audio/speech`. */
object OpenAiTtsRequest {
    private const val URL = "https://api.openai.com/v1/audio/speech"

    fun build(
        text: String,
        selection: CloudVoiceSelection.OpenAi,
        speed: Float = 1.0f,
    ): CloudTtsHttpRequest {
        val body = JSONObject()
            .put("model", selection.model.id)
            .put("input", text)
            .put("voice", selection.voice.id)
            .put("response_format", "wav")
        // OpenAI accepts 0.25..4.0; TensorSpeak's own range is narrower (0.5..2.0), so any
        // value it passes through is always in range.
        if (speed != 1.0f) body.put("speed", speed)
        return CloudTtsHttpRequest(
            url = URL,
            headers = mapOf(
                "Authorization" to "Bearer ${selection.apiKey}",
                "Content-Type" to "application/json",
            ),
            jsonBody = body.toString(),
        )
    }

    /** OpenAI error responses are `{"error": {"message": "...", ...}}`. */
    fun errorMessage(responseBody: String): String = runCatching {
        JSONObject(responseBody).getJSONObject("error").getString("message")
    }.getOrDefault(responseBody.take(200))
}
