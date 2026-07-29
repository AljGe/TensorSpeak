package com.github.aljge.tensorspeak

/** ElevenLabs `model_id` choices. */
enum class ElevenLabsModel(val id: String, val label: String) {
    MULTILINGUAL_V2("eleven_multilingual_v2", "Multilingual v2 · higher quality"),
    TURBO_V2_5("eleven_turbo_v2_5", "Turbo v2.5"),
    FLASH_V2_5("eleven_flash_v2_5", "Flash v2.5 · fastest");

    companion object {
        val DEFAULT = MULTILINGUAL_V2

        fun fromId(id: String?): ElevenLabsModel =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
