package com.github.aljge.tensorspeak

/**
 * Deepgram Aura-2 English voices. Unlike OpenAI, Deepgram has no separate model/voice split —
 * the `model` query param *is* the voice selection, so each entry here doubles as both.
 *
 * This list is a snapshot of Deepgram's documented Aura-2 English catalog and may drift as
 * they add voices; check https://developers.deepgram.com/docs/tts-models before relying on an
 * id not listed here.
 */
enum class DeepgramVoice(val id: String, val label: String) {
    THALIA("aura-2-thalia-en", "Thalia"),
    ODYSSEUS("aura-2-odysseus-en", "Odysseus"),
    AMALTHEA("aura-2-amalthea-en", "Amalthea"),
    ARCAS("aura-2-arcas-en", "Arcas"),
    APOLLO("aura-2-apollo-en", "Apollo"),
    ANDROMEDA("aura-2-andromeda-en", "Andromeda")
    LARA("aura-2-lara-de", "Lara")
    JULIUS("aura-2-julius-de", "Julius")

    companion object {
        val DEFAULT = THALIA

        fun fromId(id: String?): DeepgramVoice =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
