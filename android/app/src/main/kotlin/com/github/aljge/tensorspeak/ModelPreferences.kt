package com.github.aljge.tensorspeak

import android.content.Context

/** Persists model / quality / experimental runtime choices for the harness and system TTS. */
object ModelPreferences {
    private const val PREFS = "tensorspeak_tts"
    private const val KEY_VARIANT = "model_variant"
    private const val KEY_QUALITY_PROFILE = "quality_profile"
    private const val KEY_EXECUTION_BACKEND = "execution_backend"
    private const val KEY_LATENCY_PROFILE = "latency_profile"
    private const val KEY_THREAD_PROFILE = "thread_profile"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): ModelVariant =
        ModelVariant.fromId(prefs(context).getString(KEY_VARIANT, ModelVariant.DEFAULT.id))

    fun set(context: Context, variant: ModelVariant) {
        prefs(context).edit().putString(KEY_VARIANT, variant.id).apply()
    }

    fun qualityProfile(context: Context): QualityProfile =
        QualityProfile.fromId(
            prefs(context).getString(KEY_QUALITY_PROFILE, QualityProfile.DEFAULT.id)
        )

    fun setQualityProfile(context: Context, profile: QualityProfile) {
        prefs(context).edit().putString(KEY_QUALITY_PROFILE, profile.id).apply()
    }

    fun variation(context: Context, variant: ModelVariant): Float =
        qualityProfile(context).variationFor(variant)

    fun executionBackend(context: Context): ExecutionBackend =
        ExecutionBackend.fromId(
            prefs(context).getString(KEY_EXECUTION_BACKEND, ExecutionBackend.DEFAULT.id)
        )

    fun setExecutionBackend(context: Context, backend: ExecutionBackend) {
        prefs(context).edit().putString(KEY_EXECUTION_BACKEND, backend.id).apply()
    }

    fun latencyProfile(context: Context): LatencyProfile =
        LatencyProfile.fromId(
            prefs(context).getString(KEY_LATENCY_PROFILE, LatencyProfile.DEFAULT.id)
        )

    fun setLatencyProfile(context: Context, profile: LatencyProfile) {
        prefs(context).edit().putString(KEY_LATENCY_PROFILE, profile.id).apply()
    }

    fun threadProfile(context: Context): ThreadProfile =
        ThreadProfile.fromId(
            prefs(context).getString(KEY_THREAD_PROFILE, ThreadProfile.DEFAULT.id)
        )

    fun setThreadProfile(context: Context, profile: ThreadProfile) {
        prefs(context).edit().putString(KEY_THREAD_PROFILE, profile.id).apply()
    }

    /** Session construction options implied by the experimental prefs. */
    fun runtimeConfig(context: Context): RuntimeConfig =
        RuntimeConfig(
            provider = executionBackend(context).provider,
            intraOpThreads = threadProfile(context).resolve(),
        )
}
