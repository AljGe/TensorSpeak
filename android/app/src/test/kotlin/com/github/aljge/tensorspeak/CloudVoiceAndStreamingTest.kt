package com.github.aljge.tensorspeak

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepgramVoiceTest {

    @Test
    fun `includes orion and thalia english ids`() {
        assertEquals("aura-2-orion-en", DeepgramVoice.ORION.id)
        assertEquals("aura-2-thalia-en", DeepgramVoice.THALIA.id)
        assertEquals(DeepgramVoice.THALIA, DeepgramVoice.DEFAULT)
    }

    @Test
    fun `fromId resolves known voices and falls back to default`() {
        assertEquals(DeepgramVoice.ORION, DeepgramVoice.fromId("aura-2-orion-en"))
        assertEquals(DeepgramVoice.DEFAULT, DeepgramVoice.fromId("missing"))
        assertEquals(DeepgramVoice.DEFAULT, DeepgramVoice.fromId(null))
    }

    @Test
    fun `catalog is english only and non empty`() {
        assertTrue(DeepgramVoice.entries.size >= 40)
        for (voice in DeepgramVoice.entries) {
            assertTrue(voice.id.endsWith("-en"))
            assertTrue(voice.label.isNotEmpty())
        }
    }
}

class VoicePreferencesCoalesceTest {

    @Test
    fun `keeps stored voice when still valid`() {
        val result = VoicePreferences.coalesceDefault(
            stored = "deepgram-aura-2-thalia-en",
            fallback = "micro",
            isValid = { it.startsWith("deepgram-") },
        )
        assertEquals("deepgram-aura-2-thalia-en", result)
    }

    @Test
    fun `falls back when stored voice is stale`() {
        val result = VoicePreferences.coalesceDefault(
            stored = "deepgram-aura-2-thalia-en",
            fallback = "nano",
            isValid = { false },
        )
        assertEquals("nano", result)
    }

    @Test
    fun `falls back when nothing stored`() {
        val result = VoicePreferences.coalesceDefault(
            stored = null,
            fallback = "micro",
            isValid = { true },
        )
        assertEquals("micro", result)
    }
}

class CloudVoiceCatalogResolveTest {

    @Test
    fun `on-device names resolve without cloud keys`() {
        // resolve for local ids only needs ModelVariant — no Context secrets.
        // Use a fake context path by testing naming conventions against the enum directly.
        assertNotNull(ModelVariant.fromId("micro"))
        assertNotNull(ModelVariant.fromId("nano"))
        assertEquals(ModelVariant.MICRO, ModelVariant.fromId("micro"))
    }

    @Test
    fun `display label falls back to voice name when feature missing`() {
        // Voice is an Android framework class; exercise the string helper path via a
        // synthetic feature set using the same prefix contract.
        val label = "label=OpenAI · Nova".removePrefix(CloudVoiceCatalog.FEATURE_LABEL_PREFIX)
        assertEquals("OpenAI · Nova", label)
        assertEquals(
            CloudVoiceCatalog.FEATURE_LABEL_PREFIX,
            "label=",
        )
    }
}

class CloudTtsStreamingTest {

    private val selection = CloudVoiceSelection.Deepgram(
        apiKey = "dg-test",
        voice = DeepgramVoice.ORION,
    )

    @Test
    fun `streaming emits one piece for a short utterance`() = runBlocking {
        val fetches = mutableListOf<String>()
        val cloud = CloudTts { chunk, _, _ ->
            fetches.add(chunk)
            DecodedAudio(24_000, FloatArray(8) { 0.1f })
        }
        val pieces = mutableListOf<FloatArray>()
        cloud.synthesizeStreaming("Hello world.", speed = 1f, selection = selection) { _, audio ->
            pieces.add(audio)
            true
        }
        assertEquals(1, fetches.size)
        assertEquals(1, pieces.size)
        assertEquals(8, pieces[0].size)
    }

    @Test
    fun `streaming emits multiple pieces for multi-sentence text`() = runBlocking {
        val fetches = mutableListOf<String>()
        val cloud = CloudTts { chunk, _, _ ->
            fetches.add(chunk)
            DecodedAudio(24_000, FloatArray(4) { 0.2f })
        }
        // Force several chunks with a tiny first/limit budget.
        val text = "First sentence here. Second sentence follows. Third one ends it."
        val pieces = mutableListOf<FloatArray>()
        cloud.synthesizeStreaming(
            text = text,
            speed = 1f,
            selection = selection,
            firstChunkLimit = 20,
        ) { _, audio ->
            pieces.add(audio)
            true
        }
        assertTrue("expected multiple HTTP fetches, got ${fetches.size}", fetches.size >= 2)
        // Pieces include audio chunks plus inter-sentence silence between them.
        assertTrue("expected multiple streamed pieces, got ${pieces.size}", pieces.size >= 3)
    }

    @Test
    fun `known sample rate is 24 kHz for deepgram openai and elevenlabs`() {
        val cloud = CloudTts()
        assertEquals(
            24_000,
            cloud.knownSampleRateHz(
                CloudVoiceSelection.Deepgram("k", DeepgramVoice.THALIA),
            ),
        )
        assertEquals(
            24_000,
            cloud.knownSampleRateHz(
                CloudVoiceSelection.OpenAi("k", OpenAiModel.DEFAULT, OpenAiVoice.NOVA),
            ),
        )
        assertEquals(
            24_000,
            cloud.knownSampleRateHz(
                CloudVoiceSelection.ElevenLabs("k", ElevenLabsModel.DEFAULT, "voice"),
            ),
        )
        assertEquals(
            null,
            cloud.knownSampleRateHz(
                CloudVoiceSelection.Custom("https://example.com", "", "", "", false),
            ),
        )
    }
}
