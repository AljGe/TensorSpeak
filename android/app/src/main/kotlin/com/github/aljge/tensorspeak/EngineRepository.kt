package com.github.aljge.tensorspeak

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide, reference-counted [OnnxTts] cache so the settings activity and the system
 * TTS service do not both open the same ~16-38 MB graph pair.
 */
object EngineRepository {
    private val mutex = Mutex()
    private var engine: OnnxTts? = null
    private var holders = 0
    private var runtimeConfig: RuntimeConfig = RuntimeConfig.DEFAULT

    suspend fun acquire(
        context: Context,
        variant: ModelVariant = ModelPreferences.get(context),
        config: RuntimeConfig = ModelPreferences.runtimeConfig(context),
    ): OnnxTts = mutex.withLock {
        val current = engine
        if (current != null &&
            current.variant == variant &&
            runtimeConfig == config
        ) {
            holders += 1
            return current
        }
        current?.close()
        engine = null
        holders = 0
        runtimeConfig = config
        val created = OnnxTts.fromAssets(
            context = context.applicationContext,
            variant = variant,
            phonemes = EspeakPhonemeSource(context.applicationContext),
            config = config,
        )
        engine = created
        holders = 1
        created
    }

    fun acquireBlocking(
        context: Context,
        variant: ModelVariant = ModelPreferences.get(context),
        config: RuntimeConfig = ModelPreferences.runtimeConfig(context),
    ): OnnxTts = runBlocking { acquire(context, variant, config) }

    suspend fun release(instance: OnnxTts) = mutex.withLock {
        if (engine !== instance) {
            instance.close()
            return
        }
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) {
            engine?.close()
            engine = null
        }
    }

    fun releaseBlocking(instance: OnnxTts) = runBlocking { release(instance) }
}
