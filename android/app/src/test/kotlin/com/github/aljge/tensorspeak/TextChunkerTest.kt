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
    fun `no chunk exceeds its limit`() {
        val rows = corpus()
        for (index in 0 until rows.length()) {
            val chunks = TextChunker.split(rows.getJSONObject(index).getString("text"))
            chunks.forEachIndexed { position, chunk ->
                val limit = if (position == 0) TextChunker.FIRST_CHUNK_LIMIT else TextChunker.LIMIT
                assertTrue(
                    "chunk $position of ${chunk.length} chars exceeds $limit",
                    chunk.length <= limit,
                )
            }
        }
    }

    @Test
    fun `first chunk stays under the TTFA budget`() {
        val long =
            "A very long sentence that keeps going and going without stopping, " +
                "and then continues past the first-chunk limit with another clause, " +
                "and still more text after that clause so the splitter has to cut."
        val chunks = TextChunker.split(long)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.first().length <= TextChunker.FIRST_CHUNK_LIMIT)
        assertTrue(chunks.drop(1).all { it.length <= TextChunker.LIMIT })
    }

    @Test
    fun `latency profiles respect both first and subsequent limits`() {
        val long =
            "A very long sentence that keeps going and going without stopping, " +
                "and then continues past any first-chunk limit with another clause, " +
                "and still more text after that clause so the splitter has to cut again, " +
                "plus yet another stretch of filler words that push past balanced and continuous " +
                "subsequent budgets so every profile must emit more than one continuation piece."
        for (profile in LatencyProfile.entries) {
            val chunks = TextChunker.split(
                long,
                limit = profile.chunkLimit,
                firstChunkLimit = profile.firstChunkLimit,
            )
            assertTrue("${profile.id} should split", chunks.size >= 2)
            // Punctuation break at the end of the limit+1 search window can yield limit+1.
            assertTrue(
                "${profile.id} first chunk ${chunks.first().length} exceeds ${profile.firstChunkLimit}",
                chunks.first().length <= profile.firstChunkLimit + 1,
            )
            assertTrue(
                "${profile.id} later chunk exceeds ${profile.chunkLimit}",
                chunks.drop(1).all { it.length <= profile.chunkLimit + 1 },
            )
        }
        val fast = TextChunker.split(
            long,
            limit = LatencyProfile.FAST.chunkLimit,
            firstChunkLimit = LatencyProfile.FAST.firstChunkLimit,
        )
        val continuous = TextChunker.split(
            long,
            limit = LatencyProfile.CONTINUOUS.chunkLimit,
            firstChunkLimit = LatencyProfile.CONTINUOUS.firstChunkLimit,
        )
        assertTrue(
            "fast should emit more pieces than continuous",
            fast.size > continuous.size,
        )
    }

    @Test
    fun `does not split common abbreviations into micro sentences`() {
        assertEquals(
            listOf("Bring pens, paper, etc. tomorrow before 9:00."),
            TextChunker.split("Bring pens, paper, etc. tomorrow before 9:00."),
        )
        assertEquals(
            listOf("The package is bound for the U.S.A. and should arrive tomorrow."),
            TextChunker.split("The package is bound for the U.S.A. and should arrive tomorrow."),
        )
        assertEquals(
            listOf("Dr. Chen met Prof. Adler on St. Vincent Street."),
            TextChunker.split("Dr. Chen met Prof. Adler on St. Vincent Street."),
        )
    }
}
