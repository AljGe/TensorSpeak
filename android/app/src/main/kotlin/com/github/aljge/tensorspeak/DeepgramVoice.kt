package com.github.aljge.tensorspeak

/**
 * Deepgram Aura-2 English voices. Unlike OpenAI, Deepgram has no separate model/voice split —
 * the `model` query param *is* the voice selection, so each entry here doubles as both.
 *
 * This list is a snapshot of Deepgram's documented Aura-2 English catalog and may drift as
 * they add voices; check https://developers.deepgram.com/docs/tts-models before relying on an
 * id not listed here. Non-English Aura-2 voices are omitted because this engine reports en-US.
 */
enum class DeepgramVoice(val id: String, val label: String) {
    AMALTHEA("aura-2-amalthea-en", "Amalthea"),
    ANDROMEDA("aura-2-andromeda-en", "Andromeda"),
    APOLLO("aura-2-apollo-en", "Apollo"),
    ARCAS("aura-2-arcas-en", "Arcas"),
    ARIES("aura-2-aries-en", "Aries"),
    ASTERIA("aura-2-asteria-en", "Asteria"),
    ATHENA("aura-2-athena-en", "Athena"),
    ATLAS("aura-2-atlas-en", "Atlas"),
    AURORA("aura-2-aurora-en", "Aurora"),
    CALLISTA("aura-2-callista-en", "Callista"),
    CORA("aura-2-cora-en", "Cora"),
    CORDELIA("aura-2-cordelia-en", "Cordelia"),
    DELIA("aura-2-delia-en", "Delia"),
    DRACO("aura-2-draco-en", "Draco"),
    ELECTRA("aura-2-electra-en", "Electra"),
    HARMONIA("aura-2-harmonia-en", "Harmonia"),
    HELENA("aura-2-helena-en", "Helena"),
    HERA("aura-2-hera-en", "Hera"),
    HERMES("aura-2-hermes-en", "Hermes"),
    HYPERION("aura-2-hyperion-en", "Hyperion"),
    IRIS("aura-2-iris-en", "Iris"),
    JANUS("aura-2-janus-en", "Janus"),
    JUNO("aura-2-juno-en", "Juno"),
    JUPITER("aura-2-jupiter-en", "Jupiter"),
    LUNA("aura-2-luna-en", "Luna"),
    MARS("aura-2-mars-en", "Mars"),
    MINERVA("aura-2-minerva-en", "Minerva"),
    NEPTUNE("aura-2-neptune-en", "Neptune"),
    ODYSSEUS("aura-2-odysseus-en", "Odysseus"),
    OPHELIA("aura-2-ophelia-en", "Ophelia"),
    ORION("aura-2-orion-en", "Orion"),
    ORPHEUS("aura-2-orpheus-en", "Orpheus"),
    PANDORA("aura-2-pandora-en", "Pandora"),
    PHOEBE("aura-2-phoebe-en", "Phoebe"),
    PLUTO("aura-2-pluto-en", "Pluto"),
    SATURN("aura-2-saturn-en", "Saturn"),
    SELENE("aura-2-selene-en", "Selene"),
    THALIA("aura-2-thalia-en", "Thalia"),
    THEIA("aura-2-theia-en", "Theia"),
    VESTA("aura-2-vesta-en", "Vesta"),
    ZEUS("aura-2-zeus-en", "Zeus");

    companion object {
        val DEFAULT = THALIA

        fun fromId(id: String?): DeepgramVoice =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
