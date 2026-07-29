package com.github.aljge.tensorspeak

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiTtsRequestTest {

    private val selection = CloudVoiceSelection.OpenAi(
        apiKey = "sk-test",
        model = OpenAiModel.TTS_1,
        voice = OpenAiVoice.NOVA,
    )

    @Test
    fun `builds the expected url, headers, and body`() {
        val request = OpenAiTtsRequest.build("Hello world.", selection)
        assertEquals("https://api.openai.com/v1/audio/speech", request.url)
        assertEquals("Bearer sk-test", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])

        val body = JSONObject(request.jsonBody)
        assertEquals("tts-1", body.getString("model"))
        assertEquals("Hello world.", body.getString("input"))
        assertEquals("nova", body.getString("voice"))
        assertEquals("wav", body.getString("response_format"))
        assertFalse(body.has("speed"))
    }

    @Test
    fun `omits speed when it is the default`() {
        val request = OpenAiTtsRequest.build("Hello.", selection, speed = 1.0f)
        assertFalse(JSONObject(request.jsonBody).has("speed"))
    }

    @Test
    fun `includes speed when non-default`() {
        val request = OpenAiTtsRequest.build("Hello.", selection, speed = 1.5f)
        assertEquals(1.5, JSONObject(request.jsonBody).getDouble("speed"), 1e-6)
    }

    @Test
    fun `parses the error body message`() {
        val body = """{"error": {"message": "invalid api key", "type": "invalid_request_error"}}"""
        assertEquals("invalid api key", OpenAiTtsRequest.errorMessage(body))
    }

    @Test
    fun `falls back to raw text when the error body is not the expected shape`() {
        assertTrue(OpenAiTtsRequest.errorMessage("not json").isNotEmpty())
    }
}
