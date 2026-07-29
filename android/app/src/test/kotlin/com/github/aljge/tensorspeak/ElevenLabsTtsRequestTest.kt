package com.github.aljge.tensorspeak

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevenLabsTtsRequestTest {

    private val selection = CloudVoiceSelection.ElevenLabs(
        apiKey = "xi-test",
        model = ElevenLabsModel.TURBO_V2_5,
        voiceId = "21m00Tcm4TlvDq8ikWAM",
    )

    @Test
    fun `pcm request adds the output_format query param`() {
        val request = ElevenLabsTtsRequest.build("Hi.", selection, requestPcm = true)
        assertEquals(
            "https://api.elevenlabs.io/v1/text-to-speech/21m00Tcm4TlvDq8ikWAM?output_format=pcm_24000",
            request.url,
        )
        assertEquals("xi-test", request.headers["xi-api-key"])
    }

    @Test
    fun `fallback request omits the query param`() {
        val request = ElevenLabsTtsRequest.build("Hi.", selection, requestPcm = false)
        assertEquals(
            "https://api.elevenlabs.io/v1/text-to-speech/21m00Tcm4TlvDq8ikWAM",
            request.url,
        )
    }

    @Test
    fun `body carries text and model`() {
        val body = JSONObject(ElevenLabsTtsRequest.build("Hi there.", selection).jsonBody)
        assertEquals("Hi there.", body.getString("text"))
        assertEquals("eleven_turbo_v2_5", body.getString("model_id"))
        assertTrue(body.has("voice_settings"))
    }

    @Test
    fun `parses a nested error message`() {
        val body = """{"detail": {"status": "invalid", "message": "bad voice id"}}"""
        assertEquals("bad voice id", ElevenLabsTtsRequest.errorMessage(body))
    }

    @Test
    fun `parses a plain string detail`() {
        val body = """{"detail": "unauthorized"}"""
        assertEquals("unauthorized", ElevenLabsTtsRequest.errorMessage(body))
    }

    @Test
    fun `falls back to raw text when unparsable`() {
        assertFalse(ElevenLabsTtsRequest.errorMessage("oops").isEmpty())
    }
}
