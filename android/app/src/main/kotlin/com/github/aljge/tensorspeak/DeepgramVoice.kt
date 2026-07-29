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
    LUNA("aura-2-luna-en", "Luna"),
    STELLA("aura-2-stella-en", "Stella"),
    ATHENA("aura-2-athena-en", "Athena"),
    HERA("aura-2-hera-en", "Hera"),
    ORION("aura-2-orion-en", "Orion"),
    ARCAS("aura-2-arcas-en", "Arcas"),
    PERSEUS("aura-2-perseus-en", "Perseus"),
    ANGUS("aura-2-angus-en", "Angus"),
    ORPHEUS("aura-2-orpheus-en", "Orpheus"),
    HELIOS("aura-2-helios-en", "Helios"),
    ZEUS("aura-2-zeus-en", "Zeus");

    companion object {
        val DEFAULT = THALIA

        fun fromId(id: String?): DeepgramVoice =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
