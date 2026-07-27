package com.fastt.inflect

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * Exposes the engine to the whole system, so any app that speaks through `TextToSpeech` can
 * use this voice once it is selected in Settings > Accessibility > Text-to-speech.
 *
 * The framework calls [onSynthesizeText] on a dedicated synthesis thread and expects the call
 * to block until the audio has been handed over, which is why this uses `runBlocking` rather
 * than a scope of its own.
 */
class InflectTtsService : TextToSpeechService() {

    private var engine: OnnxTts? = null

    @Volatile
    private var stopRequested = false

    override fun onDestroy() {
        engine?.close()
        engine = null
        EspeakNative.release()
        super.onDestroy()
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
            // Warm the graphs and the eSpeak data copy so the first utterance is not slow.
            runCatching { requireEngine() }
                .onFailure { Log.e(TAG, "failed to load the engine", it) }
        }
        return availability
    }

    override fun onStop() {
        stopRequested = true
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

        try {
            val engine = requireEngine()
            // speechRate and pitch both arrive as percentages with 100 = normal. The graphs
            // take a length scale but have no pitch control, so pitch is ignored.
            val speed = (request.speechRate / 100.0f).coerceIn(MIN_SPEED, MAX_SPEED)
            val waveform = runBlocking { engine.synthesize(text, speed = speed) }

            callback.start(OnnxTts.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
            if (!streamPcm(waveform, callback)) {
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

    /**
     * Feeds the waveform out in `maxBufferSize` pieces, converting float PCM to the 16-bit
     * PCM the framework asked for.
     *
     * @return false if [onStop] arrived partway through.
     */
    private fun streamPcm(waveform: FloatArray, callback: SynthesisCallback): Boolean {
        val maxSamples = (callback.maxBufferSize / BYTES_PER_SAMPLE).coerceAtLeast(1)
        var offset = 0
        while (offset < waveform.size) {
            if (stopRequested) return false
            val count = minOf(maxSamples, waveform.size - offset)
            val bytes = ByteArray(count * BYTES_PER_SAMPLE)
            for (index in 0 until count) {
                val clamped = waveform[offset + index].coerceIn(-1.0f, 1.0f)
                val sample = (clamped * Short.MAX_VALUE).toInt()
                // Little-endian, as ENCODING_PCM_16BIT expects.
                bytes[index * 2] = (sample and 0xFF).toByte()
                bytes[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }
            if (callback.audioAvailable(bytes, 0, bytes.size) != TextToSpeech.SUCCESS) {
                return false
            }
            offset += count
        }
        return true
    }

    @Synchronized
    private fun requireEngine(): OnnxTts {
        engine?.let { return it }
        // fromAssets is suspend only because it does file I/O; we are already off the main
        // thread here, so blocking is fine.
        val created = runBlocking { OnnxTts.fromAssets(applicationContext) }
        engine = created
        return created
    }

    private fun isEnglish(lang: String?): Boolean =
        lang.equals("eng", ignoreCase = true) || lang.equals("en", ignoreCase = true)

    private companion object {
        const val TAG = "InflectTtsService"
        const val BYTES_PER_SAMPLE = 2
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
    }
}
