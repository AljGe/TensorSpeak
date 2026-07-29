package com.github.aljge.tensorspeak

import org.json.JSONObject

/**
 * Builds the request for a user-configured endpoint. Defaults to the OpenAI-compatible
 * `{base_url}/audio/speech` shape (what the bundled `cloudflare-worker/` also accepts); when
 * [CloudVoiceSelection.Custom.useSimpleBody] is set, posts directly to `base_url` with just
 * `{"input": text}`, for simple servers that don't speak the OpenAI schema.
 */
object CustomCloudTtsRequest {
    fun build(text: String, selection: CloudVoiceSelection.Custom): CloudTtsHttpRequest {
        val headers = buildMap {
            put("Content-Type", "application/json")
            if (selection.apiKey.isNotEmpty()) put("Authorization", "Bearer ${selection.apiKey}")
        }
        if (selection.useSimpleBody) {
            return CloudTtsHttpRequest(
                url = selection.baseUrl,
                headers = headers,
                jsonBody = JSONObject().put("input", text).toString(),
            )
        }
        val body = JSONObject().put("input", text).put("response_format", "wav")
        if (selection.model.isNotEmpty()) body.put("model", selection.model)
        if (selection.voiceName.isNotEmpty()) body.put("voice", selection.voiceName)
        return CloudTtsHttpRequest(
            url = joinUrl(selection.baseUrl, "audio/speech"),
            headers = headers,
            jsonBody = body.toString(),
        )
    }
}
