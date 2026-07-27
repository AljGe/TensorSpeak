package com.fastt.inflect

/**
 * Kotlin port of `normalize_text` from the model repo's `inflect_nano_v2_frontend.py`.
 *
 * This runs *before* eSpeak-ng, and it is the reason the Android output can match the Python
 * sandbox: eSpeak has its own opinions about "$1,234.50" or "3:05 PM", and they are not the
 * ones the model was trained on.
 *
 * The substitutions are order-dependent - each one consumes digits the next would otherwise
 * match differently - so the sequence in [normalize] mirrors the Python line for line. Change
 * one and `TextNormalizerTest` (which checks every row of the golden corpus) will tell you.
 *
 * Patterns are compiled with `(?U)` so `\d`, `\w` and `\s` carry Python's Unicode semantics
 * rather than Java's ASCII defaults.
 */
object TextNormalizer {

    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    private val WORD_OVERRIDES = linkedMapOf(
        "Qwen3" to "Qwen three",
        "Qwen" to "Qwen",
        "PyTorch" to "pie torch",
        "SQLite" to "ess cue lite",
        "USB-C" to "you ess bee see",
        "RTX 3060" to "ar tee ex thirty sixty",
        "RTX 3090" to "ar tee ex thirty ninety",
        "RTX 4090" to "ar tee ex forty ninety",
        "RTX 5080" to "ar tee ex fifty eighty",
        "RTX 5090" to "ar tee ex fifty ninety",
    )

    private val LETTER_NAMES = mapOf(
        'A' to "ay", 'B' to "bee", 'C' to "see", 'D' to "dee", 'E' to "ee", 'F' to "eff",
        'G' to "gee", 'H' to "aitch", 'I' to "eye", 'J' to "jay", 'K' to "kay", 'L' to "ell",
        'M' to "em", 'N' to "en", 'O' to "oh", 'P' to "pee", 'Q' to "cue", 'R' to "ar",
        'S' to "ess", 'T' to "tee", 'U' to "you", 'V' to "vee", 'W' to "double you",
        'X' to "ex", 'Y' to "why", 'Z' to "zee",
    )

    private val ABBREVIATIONS = linkedMapOf(
        "Dr." to "doctor",
        "Mr." to "mister",
        "Mrs." to "missus",
        "Ms." to "miss",
        "Prof." to "professor",
        "St." to "saint",
        "vs." to "versus",
        "etc." to "et cetera",
        "e.g." to "for example",
        "i.e." to "that is",
    )

    private val PUNCT_TRANSLATION = mapOf(
        '‘' to "'", '’' to "'", '“' to "\"", '”' to "\"",
        '–' to "-", '—' to ", ", '…' to "...",
        '(' to ", ", ')' to ", ", '[' to ", ", ']' to ", ", '{' to ", ", '}' to ", ",
    )

    private const val U = "(?U)"

    private val WHITESPACE = Regex("$U\\s+")
    private val INITIALS = Regex("$U\\b([A-Z])(?:\\.([A-Z]))+\\.")
    private val UPPERCASE_RUN = Regex("[A-Z]")
    private val LABELED_IDENTIFIER = Regex(
        "$U\\b(apartment|apt\\.?|suite|unit|room|flight|extension|order|invoice|locker|aisle|gate)" +
            "\\s+([A-Za-z]?\\d{1,4}[A-Za-z]?)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val STREET_NUMBER =
        Regex("$U\\b(\\d{3})(?=\\s+(?:North|South|East|West)\\b)", RegexOption.IGNORE_CASE)
    private val MONEY = Regex("$U\\$(\\d[\\d,]*(?:\\.\\d{1,2})?)")
    private val DATE_SLASH =
        Regex("$U\\b(0?[1-9]|1[0-2])/(0?[1-9]|[12]\\d|3[01])/(20\\d{2}|19\\d{2})\\b")
    private val TIME = Regex("$U\\b(\\d{1,2}):(\\d{2})\\s*([AaPp]\\.?\\s*[Mm]\\.?)?\\b")
    private val BARE_HOUR_TIME = Regex("$U\\b(\\d{1,2})\\s*([AaPp]\\.?\\s*[Mm]\\.?)\\b")
    private val PHONE = Regex("$U\\b(\\d{3})-(\\d{4})\\b")
    private val VERSION = Regex("$U\\b\\d+(?:\\.\\d+){2,}\\b")
    private val DECIMAL = Regex("$U\\b(\\d+)\\.(\\d+)\\b")
    private val ORDINAL = Regex("$U\\b(\\d+)(st|nd|rd|th)\\b", RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("$U\\b\\d[\\d,]*\\b")
    private val ACRONYM = Regex("$U\\b[A-Z]{2,}\\b")
    private val REPEATED_COMMA = Regex("$U,(?:\\s*,)+")
    private val COMMA_BEFORE_STOP = Regex("$U,\\s*([.!?])")
    private val SPACE_BEFORE_PUNCT = Regex("$U\\s+([,;:.!?])")
    private val PUNCT_NEEDS_SPACE = Regex("$U([,;:.!?])(?=\\S)")
    private val IDENTIFIER_TOKEN = Regex("$U([A-Za-z]?)(\\d+)([A-Za-z]?)")
    private val NON_LETTER = Regex("[^A-Za-z]")

    /** `_words()`: spell a number, then flatten hyphens and drop the group commas. */
    private fun words(value: Long, ordinal: Boolean = false): String {
        val text = if (ordinal) NumToWords.ordinal(value) else NumToWords.cardinal(value)
        return text.replace("-", " ").replace(",", "")
    }

    /** `_digit_words()`: every digit spelled out individually. */
    private fun digitWords(text: String): String =
        text.filter { it.isDigit() }.toList().joinToString(" ") { words(it.digitToInt().toLong()) }

    /** `_identifier_digits()`: like [digitWords], but a non-leading zero is "oh". */
    private fun identifierDigits(text: String): String {
        val pieces = mutableListOf<String>()
        for ((index, character) in text.withIndex()) {
            if (!character.isDigit()) continue
            pieces.add(
                if (character == '0' && index > 0) "oh" else words(character.digitToInt().toLong())
            )
        }
        return pieces.joinToString(" ")
    }

    private fun expandIdentifierToken(token: String): String {
        val match = IDENTIFIER_TOKEN.matchEntire(token) ?: return token
        val (prefix, digits, suffix) = match.destructured
        val pieces = mutableListOf<String>()
        if (prefix.isNotEmpty()) pieces.add(LETTER_NAMES.getValue(prefix[0].uppercaseChar()))
        // Three digits or a leading zero reads as an identifier ("gate 103" -> "one oh three");
        // anything else is a plain number ("room 12" -> "twelve").
        if (digits.length == 3 || digits.startsWith("0")) {
            pieces.add(identifierDigits(digits))
        } else {
            pieces.add(words(digits.toLong()))
        }
        if (suffix.isNotEmpty()) pieces.add(LETTER_NAMES.getValue(suffix[0].uppercaseChar()))
        return pieces.joinToString(" ")
    }

    private fun expandMoney(amount: String): String {
        val raw = amount.replace(",", "")
        val dollars = raw.substringBefore('.')
        val cents = if (raw.contains('.')) raw.substringAfter('.') else ""
        val dollarCount = dollars.toLong()
        val parts = mutableListOf(words(dollarCount), if (dollarCount == 1L) "dollar" else "dollars")
        if (cents.isNotEmpty()) {
            val centCount = cents.take(2).padEnd(2, '0').toLong()
            if (centCount != 0L) {
                parts.add("and")
                parts.add(words(centCount))
                parts.add(if (centCount == 1L) "cent" else "cents")
            }
        }
        return parts.joinToString(" ")
    }

    private fun isValidDate(year: Int, month: Int, day: Int): Boolean {
        if (month !in 1..12) return false
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val lengths = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        return day in 1..lengths[month - 1]
    }

    private fun expandTimeSuffix(suffix: String): List<String> =
        // "P.M." -> "p m", then each character becomes its own token. The stray spaces this
        // leaves are swept up by the final whitespace collapse, exactly as in Python.
        suffix.lowercase().replace(".", "").map { it.toString() }

    private fun expandNumber(value: String): String {
        val digits = value.replace(",", "")
        // Long digit runs that are not years read as digit sequences, not as quantities.
        return if (digits.length >= 5 && !digits.startsWith("20")) {
            digitWords(digits)
        } else {
            words(digits.toLong())
        }
    }

    fun normalize(text: String): String {
        var result = buildString {
            for (character in text) append(PUNCT_TRANSLATION[character] ?: character.toString())
        }
        result = WHITESPACE.replace(result, " ").trim()

        for ((source, replacement) in WORD_OVERRIDES) {
            result = Regex("$U\\b${Regex.escape(source)}\\b").replace(result, replacement)
        }
        for ((source, replacement) in ABBREVIATIONS) {
            result = Regex("$U\\b${Regex.escape(source)}", RegexOption.IGNORE_CASE)
                .replace(result, replacement)
        }

        // "U.S.A." -> "U S A"; the acronym pass below then reads the letters out.
        result = INITIALS.replace(result) { match ->
            UPPERCASE_RUN.findAll(match.value).joinToString(" ") { it.value }
        }
        result = LABELED_IDENTIFIER.replace(result) { match ->
            "${match.groupValues[1]} ${expandIdentifierToken(match.groupValues[2])}"
        }
        result = STREET_NUMBER.replace(result) { match -> identifierDigits(match.groupValues[1]) }
        result = MONEY.replace(result) { match -> expandMoney(match.groupValues[1]) }
        result = DATE_SLASH.replace(result) { match ->
            val month = match.groupValues[1].toInt()
            val day = match.groupValues[2].toInt()
            val year = match.groupValues[3].toInt()
            if (!isValidDate(year, month, day)) {
                match.value
            } else {
                "${MONTHS[month - 1]} ${words(day.toLong(), ordinal = true)} ${words(year.toLong())}"
            }
        }
        result = TIME.replace(result) { match ->
            val hour = match.groupValues[1].toLong()
            val minute = match.groupValues[2].toInt()
            val pieces = mutableListOf(words(hour))
            when {
                minute == 0 -> pieces.add("o clock")
                minute < 10 -> { pieces.add("oh"); pieces.add(words(minute.toLong())) }
                else -> pieces.add(words(minute.toLong()))
            }
            val suffix = match.groupValues[3]
            if (suffix.isNotEmpty()) pieces.addAll(expandTimeSuffix(suffix))
            pieces.joinToString(" ")
        }
        result = BARE_HOUR_TIME.replace(result) { match ->
            val hour = match.groupValues[1].toLong()
            val suffix = NON_LETTER.replace(match.groupValues[2], "").lowercase()
            "${words(hour)} ${suffix.toList().joinToString(" ")}"
        }
        result = PHONE.replace(result) { match ->
            "${digitWords(match.groupValues[1])}, ${digitWords(match.groupValues[2])}"
        }
        result = VERSION.replace(result) { match ->
            match.value.split(".").joinToString(" point ") { words(it.toLong()) }
        }
        result = DECIMAL.replace(result) { match ->
            "${words(match.groupValues[1].toLong())} point ${digitWords(match.groupValues[2])}"
        }
        result = ORDINAL.replace(result) { match ->
            words(match.groupValues[1].toLong(), ordinal = true)
        }
        result = NUMBER.replace(result) { match -> expandNumber(match.value) }
        result = ACRONYM.replace(result) { match ->
            val acronym = match.value
            if (acronym.length <= 1) {
                acronym
            } else {
                acronym.map { LETTER_NAMES[it] ?: it.toString() }.joinToString(" ")
            }
        }

        result = REPEATED_COMMA.replace(result, ",")
        result = COMMA_BEFORE_STOP.replace(result) { match -> match.groupValues[1] }
        result = SPACE_BEFORE_PUNCT.replace(result) { match -> match.groupValues[1] }
        result = PUNCT_NEEDS_SPACE.replace(result) { match -> "${match.groupValues[1]} " }
        return WHITESPACE.replace(result, " ").trim()
    }
}
