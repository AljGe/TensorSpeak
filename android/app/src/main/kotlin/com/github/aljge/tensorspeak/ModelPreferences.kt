package com.github.aljge.tensorspeak

import android.content.Context

/** Persists the selected [ModelVariant] for the harness and the system TTS service. */
object ModelPreferences {
    private const val PREFS = "tensorspeak_tts"
    private const val KEY_VARIANT = "model_variant"
    private const val KEY_QUALITY_PROFILE = "quality_profile"

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

    fun qualityProfile(context: Context): QualityProfile =
        QualityProfile.fromId(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_QUALITY_PROFILE, QualityProfile.DEFAULT.id)
        )

    fun setQualityProfile(context: Context, profile: QualityProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUALITY_PROFILE, profile.id)
            .apply()
    }

    fun variation(context: Context, variant: ModelVariant): Float =
        qualityProfile(context).variationFor(variant)
}
