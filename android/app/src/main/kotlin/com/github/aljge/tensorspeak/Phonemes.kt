package com.github.aljge.tensorspeak

import android.util.Log
import org.json.JSONObject

/**
 * Maps an IPA phoneme string to the token ids `duration.onnx` expects.
 *
 * The symbol table is generated from the Python sandbox by
 * `scripts/export_android_assets.py` into `assets/symbols.json`, so the two sides cannot
 * drift. `PhonemeTokenizerTest` pins this against golden fixtures produced by the same
 * script.
 */
class PhonemeTokenizer(private val symbols: List<String>) {

    private val symbolToId: Map<String, Int> =
        symbols.withIndex().associate { (index, symbol) -> symbol to index }

    /**
     * Drops phonemes the 178-symbol table cannot represent. Mirrors `sanitize_phonemes` in
     * [frontend.py](../../../../../../../src/inflect_sandbox/frontend.py).
     *
     * eSpeak-ng emits a few characters the VITS symbol table never had, and one of them is
     * common in ordinary English: U+0303 COMBINING TILDE, the nasal vowel in French
     * loanwords - "croissant", "blanc", "denouement", "Provence". The mark trails its base
     * vowel, so dropping it leaves `ɑ̃` as `ɑ`, which is what an en-US voice approximates.
     *
     * This used to throw, which failed the *whole* utterance over one character - a single
     * "croissant" silenced the paragraph around it.
     */
    fun sanitize(phonemeText: String): Sanitized {
        val kept = StringBuilder(phonemeText.length)
        val dropped = linkedSetOf<Char>()
        for (char in phonemeText) {
            if (symbolToId.containsKey(char.toString())) kept.append(char) else dropped.add(char)
        }
        return Sanitized(kept.toString(), dropped.toList())
    }

    data class Sanitized(val phonemeText: String, val dropped: List<Char>)

    /**
     * Interleaves the blank id 0 around every phoneme (`add_blank: true` in config.json),
     * producing `2 * n + 1` ids shaped for the `tokens` input.
     *
     * Unrepresentable characters are dropped rather than throwing; see [sanitize].
     */
    fun toTokens(phonemeText: String): LongArray {
        val (sanitized, dropped) = sanitize(phonemeText)
        if (dropped.isNotEmpty()) {
            Log.w(TAG, "dropped ${dropped.size} phoneme(s) outside the symbol table: $dropped")
        }
        require(sanitized.isNotEmpty()) { "The text frontend produced no speakable tokens." }

        val ids = sanitized.map { char -> symbolToId.getValue(char.toString()) }

        val withBlanks = LongArray(ids.size * 2 + 1)
        for ((index, id) in ids.withIndex()) {
            withBlanks[index * 2 + 1] = id.toLong()
        }
        return withBlanks
    }

    companion object {
        private const val TAG = "PhonemeTokenizer"

        fun fromJson(json: String): PhonemeTokenizer {
            val array = JSONObject(json).getJSONArray("symbols")
            return PhonemeTokenizer(List(array.length()) { array.getString(it) })
        }
    }
}

/**
 * Text -> IPA. The production implementation is [EspeakPhonemeSource], which runs the
 * normalizer and the vendored eSpeak-ng; [FixturePhonemeSource] survives for JVM unit tests,
 * where no native library is loadable.
 */
interface PhonemeSource {
    fun phonemize(text: String): String
}

/** Looks up pre-computed phonemes; throws on anything it has not seen. */
class FixturePhonemeSource(private val fixtures: Map<String, String>) : PhonemeSource {
    override fun phonemize(text: String): String =
        fixtures[text.trim()]
            ?: throw IllegalArgumentException(
                "no fixture for \"$text\" - use EspeakPhonemeSource for arbitrary text"
            )

    companion object {
        /** Phonemes for [MainActivity]'s demo sentence, from the Python frontend. */
        val DEMO = FixturePhonemeSource(
            mapOf(
                "A small voice can still have something meaningful to say." to
                    "ɐ smˈɔːl vˈɔɪs kæn stˈɪl hæv sˈʌmθɪŋ mˈiːnɪŋfəl tə sˈeɪ.",
                "Hello world." to "həlˈoʊ wˈɜːld.",
                "Is this working? It really should be!" to
                    "ɪz ðɪs wˈɜːkɪŋ? ɪt ɹˈiəli ʃˌʊd bˈiː!",
            )
        )
    }
}
