package com.github.aljge.tensorspeak

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * Exposes the engine to the whole system, so any app that speaks through `TextToSpeech` can
 * use this voice once it is selected in Settings > Accessibility > Text-to-speech.
 *
 * The framework calls [onSynthesizeText] on a dedicated synthesis thread and expects the call
 * to block until the audio has been handed over, which is why this uses `runBlocking` rather
 * than a scope of its own.
 *
 * Voices are either the on-device Micro/Nano models or a commercial cloud provider (OpenAI /
 * ElevenLabs / a custom endpoint) — see [CloudVoiceCatalog]. [onLoadVoice] resolves the chosen
 * voice into [loadedTarget]; when a caller never calls `setVoice()`, [loadedTarget] stays null
 * and synthesis falls back to the on-device model chosen in the app's settings screen.
 */
class TensorSpeakTtsService : TextToSpeechService() {

    private var engine: OnnxTts? = null
    private val cloudTts = CloudTts()

    @Volatile
    private var loadedTarget: VoiceTarget? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var warmedKey: String? = null

    private var pcmScratch = ByteArray(0)

    override fun onDestroy() {
        engine?.let { EngineRepository.releaseBlocking(it) }
        engine = null
        warmedKey = null
        EspeakNative.release()
        super.onDestroy()
    }

    override fun onGetVoices(): List<Voice> = CloudVoiceCatalog.voices(applicationContext)

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (CloudVoiceCatalog.resolve(applicationContext, voiceName) != null) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }

    override fun onLoadVoice(voiceName: String?): Int {
        val target = CloudVoiceCatalog.resolve(applicationContext, voiceName)
            ?: return TextToSpeech.ERROR
        loadedTarget = target
        return TextToSpeech.SUCCESS
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? =
        if (onIsLanguageAvailable(lang, country, variant) != TextToSpeech.LANG_NOT_SUPPORTED) {
            ModelPreferences.get(applicationContext).id
        } else {
            null
        }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        when {
            !isEnglish(lang) -> TextToSpeech.LANG_NOT_SUPPORTED
            country.equals("USA", ignoreCase = true) && variant.isNullOrEmpty() ->
                TextToSpeech.LANG_COUNTRY_AVAILABLE
            // The model is en-US only; other English locales are served, just not natively.
            else -> TextToSpeech.LANG_AVAILABLE
        }

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val availability = onIsLanguageAvailable(lang, country, variant)
        if (availability != TextToSpeech.LANG_NOT_SUPPORTED) {
            // Warm newly created graphs once so the first real utterance is not cold.
            runCatching {
                val warmed = requireEngine()
                val key = engineKey(warmed)
                if (warmedKey != key) {
                    runBlocking {
                        val variation = ModelPreferences.variation(applicationContext, warmed.variant)
                        warmed.synthesizeStreaming(
                            text = "Warm up.",
                            speed = 1.0f,
                            variation = variation,
                            seed = 1L,
                            firstChunkLimit = ModelPreferences.latencyProfile(applicationContext)
                                .firstChunkLimit,
                            shouldContinue = { true },
                        ) { true }
                    }
                    warmedKey = key
                }
            }
                .onFailure { Log.e(TAG, "failed to load the engine", it) }
        }
        return availability
    }

    override fun onStop() {
        stopRequested = true
        engine?.requestStop()
        cloudTts.requestStop()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        stopRequested = false

        val text = request.charSequenceText?.toString().orEmpty()
        if (text.isBlank()) {
            callback.start(OnnxTts.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        // speechRate and pitch both arrive as percentages with 100 = normal. The graphs take
        // a length scale but have no pitch control, so pitch is ignored on-device; OpenAI's
        // API accepts a speed multiplier directly, ElevenLabs/custom endpoints ignore it.
        val speed = (request.speechRate / 100.0f).coerceIn(MIN_SPEED, MAX_SPEED)

        when (val target = loadedTarget) {
            is VoiceTarget.Cloud -> synthesizeCloud(text, speed, target.selection, callback)
            else -> synthesizeOnDevice(text, speed, (target as? VoiceTarget.OnDevice)?.variant, callback)
        }
    }

    private fun synthesizeOnDevice(
        text: String,
        speed: Float,
        variantOverride: ModelVariant?,
        callback: SynthesisCallback,
    ) {
        try {
            val engine = requireEngine(variantOverride)

            // start() before synthesis, so the framework opens the audio path while the
            // first chunk is still decoding rather than after the last one finishes.
            callback.start(OnnxTts.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)

            var delivered = true
            val variation = ModelPreferences.variation(applicationContext, engine.variant)
            val firstChunkLimit = ModelPreferences.latencyProfile(applicationContext).firstChunkLimit
            runBlocking {
                engine.synthesizeStreaming(
                    text = text,
                    speed = speed,
                    variation = variation,
                    firstChunkLimit = firstChunkLimit,
                    shouldContinue = { !stopRequested },
                ) { audio ->
                    streamPcm(audio, callback).also { delivered = it }
                }
            }
            if (!delivered) {
                // A cancelled utterance is reported as an error; `done()` would tell the
                // framework the whole request was spoken.
                callback.error()
                return
            }
            callback.done()
        } catch (error: Exception) {
            Log.e(TAG, "synthesis failed for: $text", error)
            callback.error()
        }
    }

    private fun synthesizeCloud(
        text: String,
        speed: Float,
        selection: CloudVoiceSelection,
        callback: SynthesisCallback,
    ) {
        try {
            val result = runBlocking {
                cloudTts.synthesize(text, speed, selection, shouldContinue = { !stopRequested })
            }
            // Sample rate is only known once the response is decoded, so start() waits for it
            // (unlike the on-device path, which knows SAMPLE_RATE statically up front).
            callback.start(result.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            if (streamPcm(result.samples, callback)) {
                callback.done()
            } else {
                callback.error()
            }
        } catch (error: Exception) {
            Log.e(TAG, "cloud synthesis failed for: $text", error)
            callback.error()
        }
    }

    /**
     * Feeds the waveform out in `maxBufferSize` pieces, converting float PCM to the 16-bit
     * PCM the framework asked for.
     *
     * @return false if [onStop] arrived partway through.
     */
    private fun streamPcm(waveform: FloatArray, callback: SynthesisCallback): Boolean {
        val maxSamples = (callback.maxBufferSize / PcmConverter.BYTES_PER_SAMPLE).coerceAtLeast(1)
        val needed = maxSamples * PcmConverter.BYTES_PER_SAMPLE
        if (pcmScratch.size < needed) {
            pcmScratch = ByteArray(needed)
        }
        var offset = 0
        while (offset < waveform.size) {
            if (stopRequested) return false
            val count = minOf(maxSamples, waveform.size - offset)
            PcmConverter.floatToPcm16(waveform, offset, count, pcmScratch)
            if (callback.audioAvailable(pcmScratch, 0, count * PcmConverter.BYTES_PER_SAMPLE)
                != TextToSpeech.SUCCESS
            ) {
                return false
            }
            offset += count
        }
        return true
    }

    @Synchronized
    private fun requireEngine(variantOverride: ModelVariant? = null): OnnxTts {
        val preferred = variantOverride ?: ModelPreferences.get(applicationContext)
        val config = ModelPreferences.runtimeConfig(applicationContext)
        engine?.let { current ->
            if (current.variant == preferred && current.runtimeConfig == config) return current
            EngineRepository.releaseBlocking(current)
            engine = null
            warmedKey = null
        }
        val created = try {
            EngineRepository.acquireBlocking(applicationContext, preferred, config)
        } catch (error: Exception) {
            if (config.provider == OnnxTts.Provider.CPU) throw error
            Log.w(TAG, "${config.provider} unavailable, falling back to CPU", error)
            ModelPreferences.setExecutionBackend(applicationContext, ExecutionBackend.CPU)
            EngineRepository.acquireBlocking(
                applicationContext,
                preferred,
                ModelPreferences.runtimeConfig(applicationContext),
            )
        }
        engine = created
        return created
    }

    private fun engineKey(engine: OnnxTts): String =
        "${engine.variant.id}/${engine.runtimeConfig}"

    private fun isEnglish(lang: String?): Boolean =
        lang.equals("eng", ignoreCase = true) || lang.equals("en", ignoreCase = true)

    private companion object {
        const val TAG = "TensorSpeakTtsService"
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
    }
}
