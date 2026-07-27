package com.github.aljge.tensorspeak

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

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
 *   adb shell am instrument -w -e class com.github.aljge.tensorspeak.SynthesisBenchmark \
 *       com.github.aljge.tensorspeak.test/androidx.test.runner.AndroidJUnitRunner
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

    private val listeningCorpus = listOf(
        "Dr. Chen paid $1,234.50 on 7/4/2026 at 3:05 PM.",
        "Bring pens, paper, etc. tomorrow.",
        "He lives in the U.S.A. now.",
        "The fluorescent light flickered in Saskatchewan.",
        "A long sentence with many commas, pauses, and clauses should still start quickly.",
    )

    @Test
    fun benchmarkVariantsAndProviders() {
        Log.i(TAG, "=".repeat(78))
        Log.i(TAG, "device=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT} " +
            "cores=${Runtime.getRuntime().availableProcessors()}")
        Log.i(TAG, "=".repeat(78))

        Log.i(TAG, "cpuThreadsDefault=${OnnxTts.defaultCpuThreads()}")
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
            Log.i(TAG, "--- ${variant.id} / ${provider.name} (load ${"%.0f".format(loadMs)} ms)")
            Log.i(
                TAG,
                "%-10s %6s %8s %8s %8s %8s %7s".format(
                    "case", "chars", "ttfa50", "tot50", "tot90", "audio", "rtf50"
                )
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
                val samples = List(5) { measure(engine, text) }
                val ttfa50 = percentile(samples.map { it.ttfaMs }, 50)
                val total50 = percentile(samples.map { it.totalMs }, 50)
                val total90 = percentile(samples.map { it.totalMs }, 90)
                val audioSeconds = samples.first().audioSeconds
                Log.i(
                    TAG,
                    "%-10s %6d %8s %8s %8s %8s %7s".format(
                        label,
                        text.length,
                        "%.0f ms".format(ttfa50),
                        "%.0f ms".format(total50),
                        "%.0f ms".format(total90),
                        "%.2f s".format(audioSeconds),
                        "%.3f".format(total50 / 1000.0 / audioSeconds),
                    )
                )
            }
            runListeningMatrix(engine)
        }
    }

    private data class Sample(val ttfaMs: Double, val totalMs: Double, val audioSeconds: Double)

    private fun measure(
        engine: OnnxTts,
        text: String,
        speed: Float = 1.0f,
        variation: Float = engine.variant.defaultVariation,
    ): Sample {
        val started = System.nanoTime()
        var firstAudioNs = 0L
        var samples = 0L
        var chunkCount = 0
        runBlocking {
            engine.synthesizeStreaming(text, speed = speed, variation = variation) { audio ->
                // The leading piece of a multi-chunk utterance is real audio, not the pause,
                // because pauses are only emitted *between* chunks.
                if (firstAudioNs == 0L) firstAudioNs = System.nanoTime()
                samples += audio.size
                chunkCount += 1
                true
            }
        }
        val endedNs = System.nanoTime()
        if (chunkCount > 1) {
            val slackMs = (samples.toDouble() / OnnxTts.SAMPLE_RATE) * 1000.0 -
                ((endedNs - firstAudioNs) / 1e6)
            Log.i(TAG, "chunkSlack=${"%.0f".format(slackMs)}ms for ${chunkCount} chunks")
        }
        return Sample(
            ttfaMs = (firstAudioNs - started) / 1e6,
            totalMs = (endedNs - started) / 1e6,
            audioSeconds = samples.toDouble() / OnnxTts.SAMPLE_RATE,
        )
    }

    private fun runListeningMatrix(engine: OnnxTts) {
        val speeds = listOf(1.0f, 1.05f)
        val variations = listOf(engine.variant.defaultVariation - 0.07f, engine.variant.defaultVariation)
        for (text in listeningCorpus) {
            for (speed in speeds) {
                for (variation in variations) {
                    val sample = measure(engine, text, speed = speed, variation = variation)
                    Log.i(
                        TAG,
                        "listen variant=${engine.variant.id} speed=$speed variation=${"%.2f".format(variation)} " +
                            "ttfa=${"%.0f".format(sample.ttfaMs)} total=${"%.0f".format(sample.totalMs)}"
                    )
                }
            }
        }
    }

    private fun percentile(values: List<Double>, p: Int): Double {
        val sorted = values.sorted()
        val rank = ceil((p / 100.0) * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        return sorted[rank]
    }

    private companion object {
        const val TAG = "SynthBench"
    }
}
