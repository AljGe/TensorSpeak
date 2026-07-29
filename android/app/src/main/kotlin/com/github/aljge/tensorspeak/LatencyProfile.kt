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
    FAST("fast", "Experimental · faster start", 64, 160),
    BALANCED("balanced", "Balanced (default)", 96, 280),
    CONTINUOUS("continuous", "Longer phrases", 280, 560);

    companion object {
        val DEFAULT = BALANCED

        fun fromId(id: String?): LatencyProfile =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
