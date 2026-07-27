package com.github.aljge.tensorspeak

/**
 * The slice of `num2words` that [TextNormalizer] actually reaches: English cardinals and
 * ordinals for non-negative integers.
 *
 * `num2words` defaults to the "GB" style, which is what the Python sandbox produces and
 * therefore what we must reproduce exactly:
 *
 *   1234    -> "one thousand, two hundred and thirty-four"
 *   1001    -> "one thousand and one"
 *   1100    -> "one thousand, one hundred"
 *   123456  -> "one hundred and twenty-three thousand, four hundred and fifty-six"
 *
 * The rules, derived from the reference implementation's output:
 *  - split into groups of three digits, drop zero groups;
 *  - within a group, `hundreds` then `and` then the remainder, when both are present;
 *  - join groups with `", "`, except that the *last* group joins with `" and "` when it is
 *    below one hundred.
 *
 * Ordinals are the cardinal with only its final word ordinalized ("one hundred and first",
 * "twenty-fourth", "one millionth").
 *
 * Callers go through [TextNormalizer.words], which then flattens `-` to a space and drops
 * commas, mirroring `_words()` in the Python frontend.
 */
internal object NumToWords {

    private val UNITS = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen",
    )

    private val TENS = listOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
    )

    /** Scale word per group of three digits, least significant first. */
    private val SCALES = listOf(
        "", "thousand", "million", "billion", "trillion", "quadrillion", "quintillion",
    )

    private val ORDINAL_EXCEPTIONS = mapOf(
        "one" to "first",
        "two" to "second",
        "three" to "third",
        "five" to "fifth",
        "eight" to "eighth",
        "nine" to "ninth",
        "twelve" to "twelfth",
    )

    fun cardinal(value: Long): String {
        require(value >= 0) { "only non-negative integers are reachable from the normalizer" }
        if (value == 0L) return "zero"

        // Least-significant group first.
        val groups = mutableListOf<Int>()
        var remaining = value
        while (remaining > 0) {
            groups.add((remaining % 1000).toInt())
            remaining /= 1000
        }
        require(groups.size <= SCALES.size) { "number too large to spell: $value" }

        // Rebuild most-significant first, skipping empty groups.
        val parts = mutableListOf<String>()
        for (index in groups.indices.reversed()) {
            val group = groups[index]
            if (group == 0) continue
            val scale = SCALES[index]
            parts.add(if (scale.isEmpty()) underThousand(group) else "${underThousand(group)} $scale")
        }

        val builder = StringBuilder(parts.first())
        for (index in 1 until parts.size) {
            // The trailing group attaches with "and" when it is a bare 1..99 ("one thousand
            // and one"); everything else is comma-separated ("one thousand, one hundred").
            val isLastGroupUnderHundred = index == parts.size - 1 && groups[0] in 1..99
            builder.append(if (isLastGroupUnderHundred) " and " else ", ").append(parts[index])
        }
        return builder.toString()
    }

    fun ordinal(value: Long): String {
        val text = cardinal(value)
        // Only the final word takes the ordinal suffix; it may be the tail of a hyphenated
        // compound ("twenty-four" -> "twenty-fourth").
        val split = maxOf(text.lastIndexOf(' '), text.lastIndexOf('-'))
        val head = text.substring(0, split + 1)
        return head + ordinalizeWord(text.substring(split + 1))
    }

    private fun ordinalizeWord(word: String): String {
        ORDINAL_EXCEPTIONS[word]?.let { return it }
        if (word.endsWith("y")) return word.dropLast(1) + "ieth"
        return word + "th"
    }

    private fun underThousand(value: Int): String {
        require(value in 1..999)
        val hundreds = value / 100
        val remainder = value % 100
        return when {
            hundreds == 0 -> underHundred(remainder)
            remainder == 0 -> "${UNITS[hundreds]} hundred"
            else -> "${UNITS[hundreds]} hundred and ${underHundred(remainder)}"
        }
    }

    private fun underHundred(value: Int): String {
        require(value in 1..99)
        if (value < 20) return UNITS[value]
        val tens = TENS[value / 10]
        val unit = value % 10
        return if (unit == 0) tens else "$tens-${UNITS[unit]}"
    }
}
