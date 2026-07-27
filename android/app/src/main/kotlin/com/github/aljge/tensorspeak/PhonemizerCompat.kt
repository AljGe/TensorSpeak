package com.github.aljge.tensorspeak

/**
 * Kotlin port of the parts of `phonemizer` that sit around eSpeak-ng.
 *
 * `phonemize(..., backend="espeak", strip=True, preserve_punctuation=True, with_stress=True)`
 * is not a thin wrapper over `espeak_TextToPhonemes`: it strips punctuation out of the text
 * before eSpeak sees it, phonemizes each punctuation-free chunk separately, then stitches the
 * marks back in. Calling eSpeak on the raw sentence produces visibly different output, so this
 * layer is required for parity with the Python sandbox, not an optimization.
 *
 * Sources: `phonemizer/punctuation.py` (preserve/restore) and
 * `phonemizer/backend/espeak/espeak.py` (`_postprocess_line`), phonemizer 3.x.
 *
 * The configuration is fixed to what the sandbox uses:
 *   separator = Separator(word=" ", syllable="", phone="")   strip = True
 *   with_stress = True (stress marks are kept)               language_switch = "keep-flags"
 */
internal object PhonemizerCompat {

    /** `Punctuation._DEFAULT_MARKS`. */
    private const val DEFAULT_MARKS = ";:,.!?¡¿—…\"«»“”(){}[]"

    /**
     * Python's `\s`, spelled out. Android's ICU regex and desktop OpenJDK disagree about what
     * `\s` covers, and ICU rejects `(?U)`; see the note in [TextNormalizer].
     */
    private const val WS = "[ \\t\\n\\u000B\\u000C\\r\\u001C-\\u001F\\u0085\\u00A0\\u1680" +
        "\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]"

    /** `(\s*[marks]+\s*)+` - a mark run plus any whitespace hugging it. */
    private val MARKS_RE = Regex("($WS*[${Regex.escape(DEFAULT_MARKS)}]+$WS*)+")

    private const val WORD_SEPARATOR = " "

    private val UNDERSCORE_RUN = Regex("_+")
    private val UNDERSCORE_SPACE = Regex("_ ")
    private val WHITESPACE = Regex("$WS+")

    /** Where a mark sat relative to the text it was pulled out of. */
    private enum class Position { BEGIN, END, MIDDLE, ALONE }

    private data class Mark(val index: Int, val text: String, val position: Position)

    private data class Preserved(val chunks: List<String>, val marks: List<Mark>)

    /**
     * `Punctuation.preserve`, for a single line.
     *
     * `"hello, my world!"` -> chunks `["hello", "my world"]`, marks `[", ", "!"]`.
     */
    private fun preserve(line: String): Preserved {
        val matches = MARKS_RE.findAll(line).toList()
        if (matches.isEmpty()) return Preserved(listOf(line), emptyList())

        // A line that is nothing but punctuation.
        if (matches.size == 1 && matches[0].value == line) {
            return Preserved(emptyList(), listOf(Mark(0, line, Position.ALONE)))
        }

        val marks = matches.mapIndexed { index, match ->
            val position = when {
                index == 0 && line.startsWith(match.value) -> Position.BEGIN
                index == matches.lastIndex && line.endsWith(match.value) -> Position.END
                else -> Position.MIDDLE
            }
            Mark(0, match.value, position)
        }

        // Peel the line apart at each mark in turn. Python splits on the mark *text*, which
        // can re-match an earlier identical mark; keep that behaviour rather than using the
        // match offsets, or the chunking diverges on repeated punctuation.
        var remainder = line
        val chunks = mutableListOf<String>()
        for (mark in marks) {
            val split = remainder.split(mark.text)
            chunks.add(split.first())
            remainder = split.drop(1).joinToString(mark.text)
        }
        chunks.add(remainder)
        return Preserved(chunks.filter { it.isNotEmpty() }, marks)
    }

    /**
     * `Punctuation.restore` with `strip=True` and a single-space word separator.
     *
     * Ported literally, including the `pos` bookkeeping: marks carry the index of the input
     * line they came from, and a mark whose index no longer matches `pos` falls through to
     * emitting the next chunk untouched.
     */
    private fun restore(chunks: List<String>, marks: List<Mark>): List<String> {
        val text = ArrayDeque(chunks)
        val pending = ArrayDeque(marks)
        val output = mutableListOf<String>()
        var pos = 0

        while (text.isNotEmpty() || pending.isNotEmpty()) {
            if (pending.isEmpty()) {
                // strip=True, so no trailing word separator is added.
                output.addAll(text)
                text.clear()
            } else if (text.isEmpty()) {
                // Nothing was phonemized: emit the marks alone.
                output.add(pending.joinToString("") { it.text }.replace(" ", WORD_SEPARATOR))
                pending.clear()
            } else {
                val current = pending.first()
                if (current.index != pos) {
                    output.add(text.removeFirst())
                    pos += 1
                    continue
                }
                pending.removeFirst()
                val mark = current.text.replace(" ", WORD_SEPARATOR)

                // Drop the word separator the chunk ends with, so the mark hugs the word.
                if (text.first().endsWith(WORD_SEPARATOR)) {
                    text[0] = text.first().dropLast(WORD_SEPARATOR.length)
                }

                when (current.position) {
                    Position.BEGIN -> text[0] = mark + text.first()
                    Position.END -> {
                        output.add(text.removeFirst() + mark)
                        pos += 1
                    }
                    Position.ALONE -> {
                        output.add(mark)
                        pos += 1
                    }
                    Position.MIDDLE -> {
                        if (text.size == 1) {
                            // The tail after an intermediate mark was never phonemized.
                            text[0] = text.first() + mark
                        } else {
                            val first = text.removeFirst()
                            text[0] = first + mark + text.first()
                        }
                    }
                }
            }
        }
        return output
    }

    /**
     * `EspeakBackend._postprocess_line` with `strip=True`, `with_stress=True` and an empty
     * phone separator - which is what collapses eSpeak's `_` phoneme separators away.
     */
    private fun postprocessLine(raw: String): String {
        // espeak splits an utterance across lines at punctuation; merge them back.
        var line = raw.trim().replace("\n", " ").replace("  ", " ")
        // Works around espeak-ng#694: stray separators at the end of a word.
        line = UNDERSCORE_RUN.replace(line, "_")
        line = UNDERSCORE_SPACE.replace(line, " ")
        if (line.isEmpty()) return ""

        val builder = StringBuilder()
        for (word in line.split(" ")) {
            // with_stress=True keeps ˈ and ˌ; strip=True adds no trailing separator; the
            // empty phone separator deletes the '_' marks.
            builder.append(word.trim().replace("_", "")).append(WORD_SEPARATOR)
        }
        return builder.dropLast(WORD_SEPARATOR.length).toString()
    }

    /**
     * The whole `phonemize()` call for one line: punctuation out, [phonemizeChunk] per chunk,
     * punctuation back in.
     *
     * [phonemizeChunk] is `EspeakNative.textToPhonemes`; it is a parameter so the algorithm
     * can be unit-tested on the JVM, where there is no native library.
     */
    fun phonemizeLine(line: String, phonemizeChunk: (String) -> String): String {
        val preserved = preserve(line)
        val phonemized = preserved.chunks.map { chunk -> postprocessLine(phonemizeChunk(chunk)) }
        // `restore` returns a list of lines. phonemizer hands that straight back and the
        // upstream frontend indexes `[0]`, so a line that somehow restored into more than one
        // piece keeps only the first - matching the reference rather than "fixing" it.
        return restore(phonemized, preserved.marks).firstOrNull() ?: ""
    }

    /** `_apply_phoneme_overrides` from `inflect_vits_frontend.py`. */
    private val PHONEME_OVERRIDES = linkedMapOf(
        "sˈæskɐtʃˌuːən" to "sɐskˈætʃəwən",
        "flʊɹɹˈɛsənt" to "flʊˈɹɛsənt",
    )

    fun applyPhonemeOverrides(phonemeText: String): String {
        var result = phonemeText
        for ((source, replacement) in PHONEME_OVERRIDES) {
            result = result.replace(source, replacement)
        }
        return WHITESPACE.replace(result, " ").trim()
    }
}
