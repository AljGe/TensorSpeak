package com.github.aljge.tensorspeak

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The acceptance test for the on-device frontend: every row of the golden corpus must
 * phonemize to exactly the IPA the Python sandbox produced, and tokenize to the same ids.
 *
 * This needs real hardware (it loads `libtensorspeak_espeak.so`), so it lives in `androidTest`.
 * The normalizer half is separately covered on the JVM by `TextNormalizerTest`.
 *
 * Run with: `./gradlew :app:connectedDebugAndroidTest` (a device or emulator must be attached).
 */
@RunWith(AndroidJUnit4::class)
class EspeakParityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val phonemes = EspeakPhonemeSource(context)

    private fun corpus(): JSONArray {
        val json = InstrumentationRegistry.getInstrumentation().context.assets
            .open("frontend_golden.json").bufferedReader().use { it.readText() }
        return JSONArray(json)
    }

    private fun tokenizer(): PhonemeTokenizer = PhonemeTokenizer.fromJson(
        context.assets.open("symbols.json").bufferedReader().use { it.readText() }
    )

    @Test
    fun matchesPythonPhonemesAcrossTheGoldenCorpus() {
        val rows = corpus()
        assertTrue("corpus looks truncated", rows.length() > 100)

        val failures = mutableListOf<String>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val text = row.getString("text")
            val expected = row.getString("phonemes")
            val actual = phonemes.phonemize(text)
            if (actual != expected) {
                failures.add("  input:    $text\n  expected: $expected\n  actual:   $actual")
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError(
                "${failures.size}/${rows.length()} rows diverge from the Python frontend:\n" +
                    failures.joinToString("\n\n")
            )
        }
    }

    @Test
    fun matchesPythonTokensAcrossTheGoldenCorpus() {
        val rows = corpus()
        val tokenizer = tokenizer()

        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            if (row.isNull("tokens")) continue
            val expectedJson = row.getJSONArray("tokens")
            val expected = LongArray(expectedJson.length()) { expectedJson.getLong(it) }

            val actual = tokenizer.toTokens(phonemes.phonemize(row.getString("text")))

            assertEquals(row.getString("text"), expected.toList(), actual.toList())
        }
    }

    @Test
    fun endToEndSynthesisProducesAudio() = kotlinx.coroutines.runBlocking {
        OnnxTts.fromAssets(context, phonemes = phonemes).use { tts ->
            val waveform = tts.synthesize("Hello world.", seed = 0L)
            assertTrue("expected some audio", waveform.size > OnnxTts.SAMPLE_RATE / 10)
            assertTrue("expected non-silent audio", waveform.any { kotlin.math.abs(it) > 0.01f })
        }
    }
}
