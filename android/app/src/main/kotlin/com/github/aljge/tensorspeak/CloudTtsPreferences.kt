package com.github.aljge.tensorspeak

import android.content.Context

/**
 * Non-secret cloud TTS configuration: which models to use and the user's named voice slots.
 * API keys live separately in [CloudTtsSecrets], which needs Keystore-backed encryption.
 */
object CloudTtsPreferences {
    private const val PREFS = "tensorspeak_cloud"
    private const val KEY_OPENAI_MODEL = "openai_model"
    private const val KEY_ELEVENLABS_MODEL = "elevenlabs_model"
    private const val KEY_ELEVENLABS_VOICE_SLOTS = "elevenlabs_voice_slots"
    private const val KEY_CUSTOM_BASE_URL = "custom_base_url"
    private const val KEY_CUSTOM_MODEL = "custom_model"
    private const val KEY_CUSTOM_VOICE_SLOTS = "custom_voice_slots"
    private const val KEY_CUSTOM_SIMPLE_BODY = "custom_use_simple_body"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun openAiModel(context: Context): OpenAiModel =
        OpenAiModel.fromId(prefs(context).getString(KEY_OPENAI_MODEL, OpenAiModel.DEFAULT.id))

    fun setOpenAiModel(context: Context, model: OpenAiModel) {
        prefs(context).edit().putString(KEY_OPENAI_MODEL, model.id).apply()
    }

    fun elevenLabsModel(context: Context): ElevenLabsModel =
        ElevenLabsModel.fromId(
            prefs(context).getString(KEY_ELEVENLABS_MODEL, ElevenLabsModel.DEFAULT.id)
        )

    fun setElevenLabsModel(context: Context, model: ElevenLabsModel) {
        prefs(context).edit().putString(KEY_ELEVENLABS_MODEL, model.id).apply()
    }

    fun elevenLabsVoiceSlotsText(context: Context): String =
        prefs(context).getString(KEY_ELEVENLABS_VOICE_SLOTS, "").orEmpty()

    fun setElevenLabsVoiceSlotsText(context: Context, text: String) {
        prefs(context).edit().putString(KEY_ELEVENLABS_VOICE_SLOTS, text).apply()
    }

    fun elevenLabsVoiceSlots(context: Context): List<VoiceSlot> =
        parseVoiceSlots(elevenLabsVoiceSlotsText(context))

    fun customBaseUrl(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_BASE_URL, "").orEmpty().trim()

    fun setCustomBaseUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_CUSTOM_BASE_URL, url.trim()).apply()
    }

    fun customModel(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_MODEL, "").orEmpty().trim()

    fun setCustomModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_CUSTOM_MODEL, model.trim()).apply()
    }

    fun customVoiceSlotsText(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_VOICE_SLOTS, "").orEmpty()

    fun setCustomVoiceSlotsText(context: Context, text: String) {
        prefs(context).edit().putString(KEY_CUSTOM_VOICE_SLOTS, text).apply()
    }

    fun customVoiceSlots(context: Context): List<VoiceSlot> =
        parseVoiceSlots(customVoiceSlotsText(context))

    /** Whether the custom endpoint expects `{"input": text}` instead of the OpenAI-style body. */
    fun customUsesSimpleBody(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CUSTOM_SIMPLE_BODY, false)

    fun setCustomUsesSimpleBody(context: Context, simple: Boolean) {
        prefs(context).edit().putBoolean(KEY_CUSTOM_SIMPLE_BODY, simple).apply()
    }

    /** One user-named voice, e.g. `"Rachel|21m00Tcm4TlvDq8ikWAM"`. */
    data class VoiceSlot(val label: String, val id: String, val slug: String)

    /**
     * Parses `label|id` lines (blank lines and lines without a `|` are skipped) into slots
     * with a stable, URL/voice-name-safe slug, disambiguating collisions with a numeric suffix.
     */
    fun parseVoiceSlots(text: String): List<VoiceSlot> {
        val seenSlugs = mutableSetOf<String>()
        val slots = mutableListOf<VoiceSlot>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val separator = line.indexOf('|')
            if (separator < 0) continue
            val label = line.substring(0, separator).trim()
            val id = line.substring(separator + 1).trim()
            if (label.isEmpty() || id.isEmpty()) continue
            var slug = slugify(label)
            var suffix = 2
            while (!seenSlugs.add(slug)) {
                slug = "${slugify(label)}-$suffix"
                suffix++
            }
            slots.add(VoiceSlot(label, id, slug))
        }
        return slots
    }

    private fun slugify(label: String): String {
        val lowered = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return lowered.ifEmpty { "voice" }
    }
}
