package com.fastt.inflect

import android.content.Context

/** Persists the selected [ModelVariant] for the harness and the system TTS service. */
object ModelPreferences {
    private const val PREFS = "inflect_tts"
    private const val KEY_VARIANT = "model_variant"

    fun get(context: Context): ModelVariant {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VARIANT, ModelVariant.DEFAULT.id)
        return ModelVariant.fromId(id)
    }

    fun set(context: Context, variant: ModelVariant) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VARIANT, variant.id)
            .apply()
    }
}
