package com.github.aljge.tensorspeak

/**
 * Kotlin port of `split_text` and `boundary_pause_seconds` from
 * [frontend.py](../../../../../../../src/inflect_sandbox/frontend.py).
 *
 * The Python pipeline has always synthesized in chunks; the Android port used to hand the
 * whole utterance to `duration.onnx` in one pass, which cost both quality (no pause at a
 * sentence boundary, so paragraphs ran together) and latency (nothing could be played until
 * the last sample of the last sentence was decoded).
 *
 * Splitting is sentence-first, then on internal punctuation, then on whitespace, so a chunk
 * boundary lands where a speaker would breathe.
 *
 * Character classes are spelled out rather than using `\s`, for the ICU/OpenJDK reason
 * documented in [TextNormalizer].
 */
internal object TextChunker {

    /** Longest chunk handed to the graphs, in characters. `split_text(limit=280)`. */
    const val LIMIT = 280

    /** Pause inserted after a chunk, keyed by its final punctuation mark (seconds). */
    private val BOUNDARY_PAUSES = mapOf(
        '?' to 0.28f,
        '!' to 0.24f,
        '.' to 0.22f,
        ';' to 0.16f,
        ':' to 0.13f,
        ',' to 0.09f,
    )

    private const val DEFAULT_PAUSE = 0.08f

    /** Python's `\s`, spelled out; see [TextNormalizer]. */
    private const val WS = "[ \\t\\n\\u000B\\u000C\\r\\u001C-\\u001F\\u0085\\u00A0\\u1680" +
        "\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]"

    private val WHITESPACE_RUN = Regex("$WS+")

    /** `(?<=[.!?;:])\s+` - split after sentence-ending punctuation. */
    private val SENTENCE_SPLIT = Regex("(?<=[.!?;:])$WS+")

    /** The marks a too-long sentence may be broken on, in `split_text`'s order. */
    private val INTERNAL_MARKS = charArrayOf(',', ';', ':')

    /**
     * `" ".join(text.split())` - collapse every whitespace run to a single space and trim.
     */
    fun collapseWhitespace(text: String): String =
        WHITESPACE_RUN.replace(text, " ").trim()

    /**
     * Split into synthesis chunks: sentence-first, then on punctuation, then whitespace.
     *
     * Ported literally from `split_text`, including the `limit // 2` guards - a mark that
     * lands in the first half of the window is ignored, because breaking there would leave a
     * chunk too short to carry sensible prosody.
     */
    fun split(text: String, limit: Int = LIMIT): List<String> {
        val normalized = collapseWhitespace(text)
        if (normalized.isEmpty()) return emptyList()

        val sentences = SENTENCE_SPLIT.split(normalized)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val chunks = mutableListOf<String>()
        for (sentence in sentences.ifEmpty { listOf(normalized) }) {
            var remaining = sentence
            while (remaining.length > limit) {
                val search = remaining.substring(0, limit + 1)
                val punctuation = INTERNAL_MARKS.maxOf { search.lastIndexOf(it) }
                var splitAt = if (punctuation >= limit / 2) {
                    punctuation + 1
                } else {
                    // Python's `rfind(" ", 0, limit + 1)`: the space must sit inside the
                    // window, so search the same slice rather than the whole sentence.
                    search.lastIndexOf(' ')
                }
                // No usable break at all - cut mid-word rather than emit a runt chunk.
                if (splitAt < limit / 2) splitAt = limit
                chunks.add(remaining.substring(0, splitAt).trim())
                remaining = remaining.substring(splitAt).trim()
            }
            if (remaining.isNotEmpty()) chunks.add(remaining)
        }
        return chunks
    }

    /** Seconds of silence to insert after [chunk], from the mark it ends with. */
    fun boundaryPauseSeconds(chunk: String): Float {
        val ending = chunk.trimEnd().lastOrNull() ?: return DEFAULT_PAUSE
        return BOUNDARY_PAUSES[ending] ?: DEFAULT_PAUSE
    }
}
