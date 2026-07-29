package com.github.aljge.tensorspeak

import android.content.Context

/**
 * Default Android TTS voice name (local `micro`/`nano` or a cloud id from
 * [CloudVoiceCatalog]). Kept separate from [ModelPreferences] so a cloud default can be
 * selected without implying an on-device graph swap for preview synthesis in the harness.
 */
object VoicePreferences {
    private const val PREFS = "tensorspeak_tts"
    private const val KEY_DEFAULT_VOICE = "default_voice_name"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun defaultVoiceName(context: Context): String? =
        prefs(context).getString(KEY_DEFAULT_VOICE, null)?.takeIf { it.isNotEmpty() }

    fun setDefaultVoiceName(context: Context, name: String?) {
        prefs(context).edit().apply {
            if (name.isNullOrEmpty()) {
                remove(KEY_DEFAULT_VOICE)
            } else {
                putString(KEY_DEFAULT_VOICE, name)
            }
        }.apply()
    }

    /**
     * Returns a voice name that [CloudVoiceCatalog.resolve] still accepts, falling back to
     * the on-device model from [ModelPreferences] when the stored default is missing or stale
     * (e.g. API key removed).
     */
    fun resolvedDefaultVoiceName(context: Context): String {
        val fallback = ModelPreferences.get(context).id
        val stored = defaultVoiceName(context)
        val coalesced = coalesceDefault(
            stored = stored,
            fallback = fallback,
            isValid = { CloudVoiceCatalog.resolve(context, it) != null },
        )
        if (stored != null && stored != coalesced) {
            setDefaultVoiceName(context, coalesced)
        }
        return coalesced
    }

    /**
     * Pure coalesce used by [resolvedDefaultVoiceName] (and unit tests): keep [stored] when
     * still valid, otherwise [fallback].
     */
    fun coalesceDefault(stored: String?, fallback: String, isValid: (String) -> Boolean): String {
        if (stored != null && isValid(stored)) return stored
        return fallback
    }

    /**
     * Persists [name] as the engine default. When it resolves to an on-device variant, also
     * updates [ModelPreferences] so the harness and service stay in sync.
     */
    fun setDefaultVoice(context: Context, name: String) {
        val target = CloudVoiceCatalog.resolve(context, name) ?: return
        setDefaultVoiceName(context, name)
        if (target is VoiceTarget.OnDevice) {
            ModelPreferences.set(context, target.variant)
        }
    }
}
