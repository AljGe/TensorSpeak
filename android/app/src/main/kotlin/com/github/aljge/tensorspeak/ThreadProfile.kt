package com.github.aljge.tensorspeak

/**
 * Optional CPU intra-op thread override. [AUTO] keeps [OnnxTts.defaultCpuThreads].
 */
enum class ThreadProfile(
    val id: String,
    val label: String,
    /** `null` means use [OnnxTts.defaultCpuThreads]; `0` means ORT default. */
    val intraOpThreads: Int?,
) {
    AUTO("auto", "Threads · auto (default)", null),
    T2("t2", "Experimental · 2 threads", 2),
    T4("t4", "Experimental · 4 threads", 4),
    T6("t6", "Experimental · 6 threads", 6);

    fun resolve(): Int = intraOpThreads ?: OnnxTts.defaultCpuThreads()

    companion object {
        val DEFAULT = AUTO

        fun fromId(id: String?): ThreadProfile =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
