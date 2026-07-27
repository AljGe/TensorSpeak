package com.github.aljge.tensorspeak

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grades the [TextChunker] port against `split_text` / `boundary_pause_seconds` from
 * `src/inflect_sandbox/frontend.py`, over goldens emitted by
 * `scripts/export_frontend_golden.py`.
 *
 * Chunking is what decides where the engine breathes and how soon the first sample can be
 * played, so a divergence here is audible rather than theoretical. The corpus hits every
 * branch: the sentence regex, the internal-mark break, the whitespace fallback, both
 * `limit // 2` guards and the mid-word cut.
 */
class TextChunkerTest {

    private fun corpus(): JSONArray = JSONArray(
        checkNotNull(javaClass.classLoader?.getResourceAsStream("chunking_golden.json")) {
            "missing chunking_golden.json - run scripts/export_frontend_golden.py"
        }.bufferedReader().use { it.readText() }
    )

    @Test
    fun `matches the python splitter across the golden corpus`() {
        val rows = corpus()
        assertTrue("corpus looks truncated", rows.length() >= 10)

        val failures = mutableListOf<String>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val text = row.getString("text")
            val expected = row.getJSONArray("chunks").let { array ->
                List(array.length()) { array.getString(it) }
            }
            val actual = TextChunker.split(text)
            if (actual != expected) {
                failures.add(
                    "  input:    ${text.take(60)}\n" +
                        "  expected: ${expected.map { it.length }} $expected\n" +
                        "  actual:   ${actual.map { it.length }} $actual"
                )
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError(
                "${failures.size}/${rows.length()} rows diverge from the Python splitter:\n" +
                    failures.joinToString("\n\n")
            )
        }
    }

    @Test
    fun `matches the python boundary pauses`() {
        val rows = corpus()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val chunks = TextChunker.split(row.getString("text"))
            val expected = row.getJSONArray("pauses")
            assertEquals("chunk count for row $index", expected.length(), chunks.size)
            for (position in chunks.indices) {
                assertEquals(
                    "pause after ${chunks[position]}",
                    expected.getDouble(position).toFloat(),
                    TextChunker.boundaryPauseSeconds(chunks[position]),
                    1e-6f,
                )
            }
        }
    }

    @Test
    fun `no chunk exceeds the limit`() {
        val rows = corpus()
        for (index in 0 until rows.length()) {
            for (chunk in TextChunker.split(rows.getJSONObject(index).getString("text"))) {
                assertTrue(
                    "chunk of ${chunk.length} chars exceeds ${TextChunker.LIMIT}",
                    chunk.length <= TextChunker.LIMIT,
                )
            }
        }
    }
}
