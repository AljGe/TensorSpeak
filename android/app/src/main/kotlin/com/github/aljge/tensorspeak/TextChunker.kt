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
 * boundary lands where a speaker would breathe. The first emitted chunk additionally obeys
 * [FIRST_CHUNK_LIMIT] so time-to-first-audio stays bounded even when a long sentence or a
 * normalization-expanded money/date line would otherwise dominate decode time.
 *
 * Character classes are spelled out rather than using `\s`, for the ICU/OpenJDK reason
 * documented in [TextNormalizer].
 */
internal object TextChunker {

    /** Longest subsequent chunk handed to the graphs, in characters. `split_text(limit=280)`. */
    const val LIMIT = 280

    /**
     * Cap on the first synthesis chunk. Decode cost scales with utterance length, and the
     * first chunk gates TTFA, so it stays shorter than [LIMIT]. Later chunks keep the larger
     * limit because playback slack is already ample once audio has started.
     */
    const val FIRST_CHUNK_LIMIT = 96

    /** Pause inserted after a chunk, keyed by its final punctuation mark (seconds). */
    private val BOUNDARY_PAUSES = mapOf(
        '?' to 0.20f,
        '!' to 0.18f,
        '.' to 0.15f,
        ';' to 0.12f,
        ':' to 0.10f,
        ',' to 0.07f,
    )

    private const val DEFAULT_PAUSE = 0.06f
    private const val PROTECTED_DOT = '\uF000'

    /** Python's `\s`, spelled out; see [TextNormalizer]. */
    private const val WS = "[ \\t\\n\\u000B\\u000C\\r\\u001C-\\u001F\\u0085\\u00A0\\u1680" +
        "\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]"

    private val WHITESPACE_RUN = Regex("$WS+")

    /** `(?<=[.!?;:])\s+` - split after sentence-ending punctuation. */
    private val SENTENCE_SPLIT = Regex("(?<=[.!?;:])$WS+")
    private val ABBREVIATION_DOT = Regex(
        "\\b(?:dr|mr|mrs|ms|prof|st|vs|etc|e\\.g|i\\.e)\\.",
        RegexOption.IGNORE_CASE,
    )
    private val INITIALISM_DOT = Regex("\\b(?:[A-Za-z]\\.){2,}")

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
     * chunk too short to carry sensible prosody. The first emitted chunk uses
     * [firstChunkLimit]; later chunks use [limit].
     */
    fun split(
        text: String,
        limit: Int = LIMIT,
        firstChunkLimit: Int = FIRST_CHUNK_LIMIT,
    ): List<String> {
        val normalized = collapseWhitespace(text)
        if (normalized.isEmpty()) return emptyList()

        val protected = protectSentenceDots(normalized)
        val sentences = SENTENCE_SPLIT.split(protected)
            .map { restoreSentenceDots(it) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val chunks = mutableListOf<String>()
        for (sentence in sentences.ifEmpty { listOf(normalized) }) {
            var remaining = sentence
            while (remaining.isNotEmpty()) {
                val currentLimit = if (chunks.isEmpty()) firstChunkLimit else limit
                if (remaining.length <= currentLimit) {
                    chunks.add(remaining)
                    break
                }
                val search = remaining.substring(0, currentLimit + 1)
                val punctuation = INTERNAL_MARKS.maxOf { search.lastIndexOf(it) }
                var splitAt = if (punctuation >= currentLimit / 2) {
                    punctuation + 1
                } else {
                    // Python's `rfind(" ", 0, limit + 1)`: the space must sit inside the
                    // window, so search the same slice rather than the whole sentence.
                    search.lastIndexOf(' ')
                }
                // No usable break at all - cut mid-word rather than emit a runt chunk.
                if (splitAt < currentLimit / 2) splitAt = currentLimit
                chunks.add(remaining.substring(0, splitAt).trim())
                remaining = remaining.substring(splitAt).trim()
            }
        }
        return chunks
    }

    private fun protectSentenceDots(text: String): String {
        var protected = ABBREVIATION_DOT.replace(text) { match ->
            match.value.replace('.', PROTECTED_DOT)
        }
        protected = INITIALISM_DOT.replace(protected) { match ->
            match.value.replace('.', PROTECTED_DOT)
        }
        return protected
    }

    private fun restoreSentenceDots(text: String): String =
        text.replace(PROTECTED_DOT, '.')

    /** Seconds of silence to insert after [chunk], from the mark it ends with. */
    fun boundaryPauseSeconds(chunk: String): Float {
        val ending = chunk.trimEnd().lastOrNull() ?: return DEFAULT_PAUSE
        return BOUNDARY_PAUSES[ending] ?: DEFAULT_PAUSE
    }
}
