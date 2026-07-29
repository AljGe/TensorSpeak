package com.github.aljge.tensorspeak

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DeepgramTtsRequestTest {

    private val selection = CloudVoiceSelection.Deepgram(
        apiKey = "dg-test",
        voice = DeepgramVoice.ORION,
    )

    @Test
    fun `builds the expected url and headers`() {
        val request = DeepgramTtsRequest.build("Hello.", selection)
        assertEquals(
            "https://api.deepgram.com/v1/speak?model=aura-2-orion-en&encoding=linear16&sample_rate=24000&container=wav",
            request.url,
        )
        assertEquals("Token dg-test", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])
    }

    @Test
    fun `body carries just the text`() {
        val body = JSONObject(DeepgramTtsRequest.build("Hi there.", selection).jsonBody)
        assertEquals("Hi there.", body.getString("text"))
        assertFalse(body.has("model"))
    }

    @Test
    fun `parses the error body message`() {
        val body = """{"err_code": "INVALID_AUTH", "err_msg": "invalid api key", "request_id": "abc"}"""
        assertEquals("invalid api key", DeepgramTtsRequest.errorMessage(body))
    }

    @Test
    fun `falls back to raw text when unparsable`() {
        assertFalse(DeepgramTtsRequest.errorMessage("oops").isEmpty())
    }
}
