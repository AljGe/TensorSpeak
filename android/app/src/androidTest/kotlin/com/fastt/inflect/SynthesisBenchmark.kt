package com.fastt.inflect

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures what the engine actually costs on device, across both graph variants and both
 * execution providers.
 *
 * Not an assertion test - it prints a table to logcat under the tag [TAG] and passes as long
 * as nothing throws. The numbers that matter:
 *
 *   load      cold session construction (both graphs)
 *   ttfa      time to first audio: what the user waits before hearing anything
 *   total     wall clock for the whole utterance
 *   audio     duration of the produced waveform
 *   rtf       total / audio; below 1.0 means faster than real time
 *
 * `ttfa` is the point of the streaming rewrite, so it is reported separately from `total`.
 *
 * Run with:
 *   ./gradlew :app:installDebug :app:installDebugAndroidTest
 *   adb shell am instrument -w -e class com.fastt.inflect.SynthesisBenchmark \
 *       com.fastt.inflect.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class SynthesisBenchmark {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Lengths chosen to bracket what the TTS log showed in the field (7..332 chars). */
    private val cases = listOf(
        "Short" to "Hello world.",
        "Sentence" to
            "A small voice can still have something meaningful to say.",
        "Paragraph" to
            "The engine runs entirely on the device, with no network round trip at any " +
            "point. It loads two ONNX graphs, one predicting durations and one decoding " +
            "the waveform, and streams the result back sentence by sentence.",
        "Long" to
            "Text to speech on a phone is a latency problem before it is a quality " +
            "problem, because the listener notices the wait before they notice the voice. " +
            "The decoder dominates the compute budget, and its cost grows with the length " +
            "of the utterance, so synthesizing a whole paragraph before playing a single " +
            "sample is the worst possible arrangement. Splitting on sentence boundaries " +
            "fixes both halves of that: the first sentence starts playing while the rest " +
            "is still being decoded, and the pauses land where a speaker would breathe.",
    )

    @Test
    fun benchmarkVariantsAndProviders() {
        Log.i(TAG, "=".repeat(78))
        Log.i(TAG, "device=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT} " +
            "cores=${Runtime.getRuntime().availableProcessors()}")
        Log.i(TAG, "=".repeat(78))

        for (variant in ModelVariant.entries) {
            for (provider in listOf(OnnxTts.Provider.XNNPACK, OnnxTts.Provider.CPU)) {
                runOne(variant, provider)
            }
        }
        Log.i(TAG, "=".repeat(78))
    }

    private fun runOne(variant: ModelVariant, provider: OnnxTts.Provider) {
        val engine = try {
            val started = System.nanoTime()
            val created = runBlocking {
                OnnxTts.fromAssets(context, variant, EspeakPhonemeSource(context), provider)
            }
            val loadMs = (System.nanoTime() - started) / 1e6
            Log.i(TAG, "")
            Log.i(TAG, "--- ${variant.id} / ${provider.name}  (load ${"%.0f".format(loadMs)} ms)")
            Log.i(
                TAG,
                "%-10s %6s %8s %8s %8s %7s".format("case", "chars", "ttfa", "total", "audio", "rtf")
            )
            created
        } catch (error: Exception) {
            Log.w(TAG, "--- ${variant.id} / ${provider.name}: unavailable (${error.message})")
            return
        }

        engine.use {
            // One discarded pass: the first run pays for lazy espeak data install and JIT.
            runBlocking { engine.synthesize("Warm up.") }

            for ((label, text) in cases) {
                // Three passes, report the best - the phone's governor makes single runs noisy.
                var best: Sample? = null
                repeat(3) {
                    val sample = measure(engine, text)
                    if (best == null || sample.totalMs < best!!.totalMs) best = sample
                }
                val s = best!!
                Log.i(
                    TAG,
                    "%-10s %6d %8s %8s %8s %7s".format(
                        label,
                        text.length,
                        "%.0f ms".format(s.ttfaMs),
                        "%.0f ms".format(s.totalMs),
                        "%.2f s".format(s.audioSeconds),
                        "%.3f".format(s.totalMs / 1000.0 / s.audioSeconds),
                    )
                )
            }
        }
    }

    private data class Sample(val ttfaMs: Double, val totalMs: Double, val audioSeconds: Double)

    private fun measure(engine: OnnxTts, text: String): Sample {
        val started = System.nanoTime()
        var firstAudioNs = 0L
        var samples = 0L
        runBlocking {
            engine.synthesizeStreaming(text) { audio ->
                // The leading piece of a multi-chunk utterance is real audio, not the pause,
                // because pauses are only emitted *between* chunks.
                if (firstAudioNs == 0L) firstAudioNs = System.nanoTime()
                samples += audio.size
                true
            }
        }
        val endedNs = System.nanoTime()
        return Sample(
            ttfaMs = (firstAudioNs - started) / 1e6,
            totalMs = (endedNs - started) / 1e6,
            audioSeconds = samples.toDouble() / OnnxTts.SAMPLE_RATE,
        )
    }

    private companion object {
        const val TAG = "SynthBench"
    }
}
