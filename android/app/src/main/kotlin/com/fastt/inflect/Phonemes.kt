package com.fastt.inflect

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
     * Interleaves the blank id 0 around every phoneme (`add_blank: true` in config.json),
     * producing `2 * n + 1` ids shaped for the `tokens` input.
     */
    fun toTokens(phonemeText: String): LongArray {
        require(phonemeText.isNotEmpty()) { "The text frontend produced no speakable tokens." }

        val ids = phonemeText.map { char ->
            symbolToId[char.toString()]
                ?: throw IllegalArgumentException("phoneme outside the symbol table: '$char'")
        }

        val withBlanks = LongArray(ids.size * 2 + 1)
        for ((index, id) in ids.withIndex()) {
            withBlanks[index * 2 + 1] = id.toLong()
        }
        return withBlanks
    }

    companion object {
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
