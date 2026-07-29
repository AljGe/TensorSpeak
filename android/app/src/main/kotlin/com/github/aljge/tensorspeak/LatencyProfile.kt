package com.github.aljge.tensorspeak

/**
 * User-facing chunking budgets. Smaller values lower TTFA (and insert more mid-sentence
 * breaths); larger values keep more of each phrase in one decode (smoother prosody, slower
 * start). Both limits keep the same smart sentence/punctuation splitter.
 */
enum class LatencyProfile(
    val id: String,
    val label: String,
    val firstChunkLimit: Int,
    val chunkLimit: Int,
) {
    FAST("fast", "Faster start · first 64 / later 160", 64, 160),
    BALANCED("balanced", "Balanced · first 96 / later 280 (default)", 96, 280),
    CONTINUOUS("continuous", "Longer phrases · first 280 / later 560", 280, 560);

    companion object {
        val DEFAULT = BALANCED

        fun fromId(id: String?): LatencyProfile =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
