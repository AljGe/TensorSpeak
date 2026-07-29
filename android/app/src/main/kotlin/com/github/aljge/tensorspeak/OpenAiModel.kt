package com.github.aljge.tensorspeak

/** OpenAI `/v1/audio/speech` model choices. */
enum class OpenAiModel(val id: String, val label: String) {
    TTS_1("tts-1", "tts-1 · fast"),
    TTS_1_HD("tts-1-hd", "tts-1-hd · higher quality"),
    GPT_4O_MINI_TTS("gpt-4o-mini-tts", "gpt-4o-mini-tts");

    companion object {
        val DEFAULT = TTS_1

        fun fromId(id: String?): OpenAiModel =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
