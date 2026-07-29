package com.github.aljge.tensorspeak

/** Commercial TTS backends selectable as extra voices alongside the on-device models. */
enum class CloudProvider(val id: String, val label: String) {
    OPENAI("openai", "OpenAI"),
    ELEVENLABS("elevenlabs", "ElevenLabs"),
    DEEPGRAM("deepgram", "Deepgram"),
    CUSTOM("custom", "Custom endpoint");

    companion object {
        fun fromId(id: String?): CloudProvider? = entries.firstOrNull { it.id == id }
    }
}
