package com.github.aljge.tensorspeak

/**
 * User-facing first-chunk budget. Smaller values lower TTFA; larger values keep more of the
 * opening sentence in one decode (smoother prosody, slower start).
 */
enum class LatencyProfile(
    val id: String,
    val label: String,
    val firstChunkLimit: Int,
) {
    FAST("fast", "Experimental · faster start", 64),
    BALANCED("balanced", "Balanced start (default)", 96),
    CONTINUOUS("continuous", "Longer first phrase", 280);

    companion object {
        val DEFAULT = BALANCED

        fun fromId(id: String?): LatencyProfile =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
