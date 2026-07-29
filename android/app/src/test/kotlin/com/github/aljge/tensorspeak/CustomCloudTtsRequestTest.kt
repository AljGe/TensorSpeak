package com.github.aljge.tensorspeak

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomCloudTtsRequestTest {

    @Test
    fun `openai-compatible body joins base url without a double slash`() {
        val selection = CloudVoiceSelection.Custom(
            baseUrl = "https://tts.example.workers.dev/",
            apiKey = "",
            model = "",
            voiceName = "",
            useSimpleBody = false,
        )
        val request = CustomCloudTtsRequest.build("Hi.", selection)
        assertEquals("https://tts.example.workers.dev/audio/speech", request.url)
        assertFalse(request.headers.containsKey("Authorization"))

        val body = JSONObject(request.jsonBody)
        assertEquals("Hi.", body.getString("input"))
        assertEquals("wav", body.getString("response_format"))
        assertFalse(body.has("model"))
        assertFalse(body.has("voice"))
    }

    @Test
    fun `joins base url without a trailing slash the same way`() {
        val selection = CloudVoiceSelection.Custom(
            baseUrl = "https://tts.example.workers.dev",
            apiKey = "secret",
            model = "melotts",
            voiceName = "en",
            useSimpleBody = false,
        )
        val request = CustomCloudTtsRequest.build("Hi.", selection)
        assertEquals("https://tts.example.workers.dev/audio/speech", request.url)
        assertEquals("Bearer secret", request.headers["Authorization"])

        val body = JSONObject(request.jsonBody)
        assertEquals("melotts", body.getString("model"))
        assertEquals("en", body.getString("voice"))
    }

    @Test
    fun `simple body posts directly to the base url`() {
        val selection = CloudVoiceSelection.Custom(
            baseUrl = "https://tts.example.workers.dev",
            apiKey = "",
            model = "",
            voiceName = "",
            useSimpleBody = true,
        )
        val request = CustomCloudTtsRequest.build("Testing.", selection)
        assertEquals("https://tts.example.workers.dev", request.url)
        assertEquals("""{"input":"Testing."}""", request.jsonBody)
        assertTrue(request.headers.containsKey("Content-Type"))
    }
}
