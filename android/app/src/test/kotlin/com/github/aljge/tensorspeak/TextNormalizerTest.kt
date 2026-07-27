package com.github.aljge.tensorspeak

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Grades the [TextNormalizer] / [NumToWords] port against `normalize_text` from the model
 * repo, row by row over the golden corpus (`scripts/export_frontend_golden.py`).
 *
 * This is the half of the frontend that can be checked without a device: the corpus is built
 * to hit every branch of the normalizer, so a regex that ported wrong shows up here rather
 * than as subtly wrong audio. The eSpeak half is covered by `EspeakParityTest`, which needs
 * hardware.
 */
class TextNormalizerTest {

    private fun corpus(): JSONArray = JSONArray(
        checkNotNull(javaClass.classLoader?.getResourceAsStream("frontend_golden.json")) {
            "missing frontend_golden.json - run scripts/export_frontend_golden.py"
        }.bufferedReader().use { it.readText() }
    )

    @Test
    fun `matches the python normalizer across the golden corpus`() {
        val rows = corpus()
        assertEquals("corpus looks truncated", true, rows.length() > 100)

        val failures = mutableListOf<String>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val text = row.getString("text")
            val expected = row.getString("normalized")
            val actual = TextNormalizer.normalize(text)
            if (actual != expected) {
                failures.add("  input:    $text\n  expected: $expected\n  actual:   $actual")
            }
        }
        // Report every mismatch at once - fixing these one assertion failure per run is
        // needlessly slow when a single regex can break a dozen rows.
        if (failures.isNotEmpty()) {
            throw AssertionError(
                "${failures.size}/${rows.length()} rows diverge from the Python normalizer:\n" +
                    failures.joinToString("\n\n")
            )
        }
    }

    @Test
    fun `spells cardinals the way num2words does`() {
        val expected = mapOf(
            0L to "zero",
            5L to "five",
            13L to "thirteen",
            21L to "twenty-one",
            100L to "one hundred",
            101L to "one hundred and one",
            999L to "nine hundred and ninety-nine",
            1000L to "one thousand",
            1001L to "one thousand and one",
            1100L to "one thousand, one hundred",
            1234L to "one thousand, two hundred and thirty-four",
            2024L to "two thousand and twenty-four",
            123456L to "one hundred and twenty-three thousand, four hundred and fifty-six",
            1000001L to "one million and one",
            2024000000L to "two billion, twenty-four million",
        )
        for ((value, words) in expected) {
            assertEquals("cardinal($value)", words, NumToWords.cardinal(value))
        }
    }

    @Test
    fun `spells ordinals the way num2words does`() {
        val expected = mapOf(
            0L to "zeroth",
            1L to "first",
            2L to "second",
            3L to "third",
            5L to "fifth",
            8L to "eighth",
            9L to "ninth",
            12L to "twelfth",
            20L to "twentieth",
            42L to "forty-second",
            101L to "one hundred and first",
            1000L to "one thousandth",
            1234L to "one thousand, two hundred and thirty-fourth",
            1000000L to "one millionth",
        )
        for ((value, words) in expected) {
            assertEquals("ordinal($value)", words, NumToWords.ordinal(value))
        }
    }
}
