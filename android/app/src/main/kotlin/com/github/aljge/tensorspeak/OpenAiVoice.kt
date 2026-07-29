package com.github.aljge.tensorspeak

/** OpenAI's fixed `/v1/audio/speech` voice names. */
enum class OpenAiVoice(val id: String, val label: String) {
    ALLOY("alloy", "Alloy"),
    ASH("ash", "Ash"),
    BALLAD("ballad", "Ballad"),
    CORAL("coral", "Coral"),
    ECHO("echo", "Echo"),
    FABLE("fable", "Fable"),
    ONYX("onyx", "Onyx"),
    NOVA("nova", "Nova"),
    SAGE("sage", "Sage"),
    SHIMMER("shimmer", "Shimmer"),
    VERSE("verse", "Verse");

    companion object {
        val DEFAULT = ALLOY

        fun fromId(id: String?): OpenAiVoice =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
