package com.github.aljge.tensorspeak

import android.os.Debug
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures what the engine actually costs on device across variants, providers, thread
 * counts, and the service PCM path.
 *
 * Not an assertion test - it prints tables to logcat under [TAG]. Key columns:
 *
 *   load / ttfa / total / audio / rtf / svc (service-path TTFA with PCM16)
 *   stages: normalize, phoneme, duration, decode, post for the first chunk
 *
 * Run with:
 *   ./gradlew :app:installDebug :app:installDebugAndroidTest
 *   adb shell am instrument -w -e class com.github.aljge.tensorspeak.SynthesisBenchmark \
 *       com.github.aljge.tensorspeak.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class SynthesisBenchmark {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val powerManager = context.getSystemService(PowerManager::class.java)

    private val cases = listOf(
        "Short" to "Hello world.",
        "Sentence" to
            "A small voice can still have something meaningful to say.",
        "Expanded" to
            "Dr. Chen paid $1,234.50 on 7/4/2026 at 3:05 PM.",
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
        header("providers")
        val configs = listOf(
            RuntimeConfig(provider = OnnxTts.Provider.CPU),
            RuntimeConfig(provider = OnnxTts.Provider.XNNPACK),
            RuntimeConfig(provider = OnnxTts.Provider.NNAPI),
        ).shuffled()
        for (variant in ModelVariant.entries.shuffled()) {
            for (config in configs) {
                runOne(variant, config, includeListening = config.provider == OnnxTts.Provider.CPU)
            }
        }
        runExperimentalGraphDir()
        Log.i(TAG, "=".repeat(78))
    }

    @Test
    fun benchmarkCpuThreading() {
        header("cpu-threading")
        val configs = buildList {
            for (threads in listOf(1, 2, 3, 4, 6, 0)) {
                add(RuntimeConfig(provider = OnnxTts.Provider.CPU, intraOpThreads = threads))
            }
            add(
                RuntimeConfig(
                    provider = OnnxTts.Provider.CPU,
                    intraOpThreads = OnnxTts.defaultCpuThreads(),
                    allowSpinning = false,
                )
            )
            add(
                RuntimeConfig(
                    provider = OnnxTts.Provider.CPU,
                    intraOpThreads = OnnxTts.defaultCpuThreads(),
                    useGlobalThreadPool = true,
                )
            )
        }.shuffled()

        // Nano only: enough to choose a default without a multi-hour Micro matrix.
        for (config in configs) {
            runOne(
                variant = ModelVariant.NANO,
                config = config,
                includeListening = false,
                caseFilter = setOf("Short", "Sentence", "Expanded", "Paragraph"),
                repeats = 3,
            )
        }
        Log.i(TAG, "=".repeat(78))
    }

    private fun header(title: String) {
        Log.i(TAG, "=".repeat(78))
        Log.i(
            TAG,
            "$title device=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT} " +
                "cores=${Runtime.getRuntime().availableProcessors()} " +
                "cpuThreadsDefault=${OnnxTts.defaultCpuThreads()} thermal=${thermalLabel()}",
        )
        Log.i(TAG, "=".repeat(78))
    }

    private fun runOne(
        variant: ModelVariant,
        config: RuntimeConfig,
        includeListening: Boolean,
        caseFilter: Set<String>? = null,
        repeats: Int = 5,
    ) {
        val label =
            "${variant.id}/${config.provider}/t${config.intraOpThreads}" +
                "/spin${config.allowSpinning}/g${config.useGlobalThreadPool}"
        val beforeNative = Debug.getNativeHeapAllocatedSize()
        val engine = try {
            val started = System.nanoTime()
            val created = runBlocking {
                OnnxTts.fromAssets(
                    context = context,
                    variant = variant,
                    phonemes = EspeakPhonemeSource(context),
                    config = config,
                )
            }
            val loadMs = (System.nanoTime() - started) / 1e6
            Log.i(TAG, "")
            Log.i(
                TAG,
                "--- $label (load ${"%.0f".format(loadMs)} ms, " +
                    "dNative=${(Debug.getNativeHeapAllocatedSize() - beforeNative) / 1_000_000}MB, " +
                    "thermal=${thermalLabel()})",
            )
            Log.i(
                TAG,
                "%-10s %6s %8s %8s %8s %8s %7s %8s".format(
                    "case", "chars", "ttfa50", "tot50", "tot90", "audio", "rtf50", "svc50"
                ),
            )
            created
        } catch (error: Exception) {
            Log.w(TAG, "--- $label: unavailable (${error.message})")
            return
        }

        engine.use {
            runBlocking { engine.synthesize("Warm up.") }

            for ((caseLabel, text) in cases) {
                if (caseFilter != null && caseLabel !in caseFilter) continue
                val samples = List(repeats) { measure(engine, text) }
                val serviceSamples = List(minOf(3, repeats)) { measureServicePath(engine, text) }
                val ttfa50 = percentile(samples.map { it.ttfaMs }, 50)
                val total50 = percentile(samples.map { it.totalMs }, 50)
                val total90 = percentile(samples.map { it.totalMs }, 90)
                val svc50 = percentile(serviceSamples.map { it.ttfaMs }, 50)
                val audioSeconds = samples.first().audioSeconds
                val first = samples.first()
                Log.i(
                    TAG,
                    "%-10s %6d %8s %8s %8s %8s %7s %8s".format(
                        caseLabel,
                        text.length,
                        "%.0f ms".format(ttfa50),
                        "%.0f ms".format(total50),
                        "%.0f ms".format(total90),
                        "%.2f s".format(audioSeconds),
                        "%.3f".format(total50 / 1000.0 / audioSeconds),
                        "%.0f ms".format(svc50),
                    ),
                )
                Log.i(
                    TAG,
                    (
                        "  stages norm=%.1f phon=%.1f tok=%d dur=%.0f mel=%d noise=%.1f " +
                            "dec=%.0f post=%.1f normLen=%d chunks=%d"
                        ).format(
                            first.normalizeMs,
                            first.phonemeMs,
                            first.tokenCount,
                            first.durationMs,
                            first.melLen,
                            first.noiseMs,
                            first.decodeMs,
                            first.postprocessMs,
                            TextNormalizer.normalize(text).length,
                            TextChunker.split(TextNormalizer.normalize(text)).size,
                        ),
                )
            }
            if (includeListening) runListeningMatrix(engine)
        }
    }

    private fun runExperimentalGraphDir() {
        val experimental = File(context.getExternalFilesDir(null), "experimental-ort")
        if (!experimental.isDirectory) {
            Log.i(TAG, "experimental ORT dir absent: $experimental")
            return
        }
        for (variant in ModelVariant.entries) {
            val dir = File(experimental, variant.id)
            if (!dir.isDirectory) continue
            try {
                val engine = runBlocking {
                    OnnxTts.fromAssets(
                        context = context,
                        variant = variant,
                        config = RuntimeConfig.DEFAULT,
                        graphDirectory = dir,
                    )
                }
                engine.use {
                    val sample = measure(engine, cases.first().second)
                    Log.i(
                        TAG,
                        "experimental ${variant.id} ttfa=${"%.0f".format(sample.ttfaMs)} " +
                            "total=${"%.0f".format(sample.totalMs)} from $dir",
                    )
                }
            } catch (error: Exception) {
                Log.w(TAG, "experimental ${variant.id} failed: ${error.message}")
            }
        }
    }

    private data class Sample(
        val ttfaMs: Double,
        val totalMs: Double,
        val audioSeconds: Double,
        val normalizeMs: Double = 0.0,
        val phonemeMs: Double = 0.0,
        val tokenCount: Int = 0,
        val durationMs: Double = 0.0,
        val melLen: Int = 0,
        val noiseMs: Double = 0.0,
        val decodeMs: Double = 0.0,
        val postprocessMs: Double = 0.0,
    )

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
        var firstStages: OnnxTts.StageTimings? = null
        runBlocking {
            engine.synthesizeStreaming(
                text,
                speed = speed,
                variation = variation,
                onChunkTiming = { stages ->
                    if (firstStages == null) firstStages = stages
                },
            ) { audio ->
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
            Log.i(TAG, "chunkSlack=${"%.0f".format(slackMs)}ms for $chunkCount chunks")
        }
        val stages = firstStages ?: OnnxTts.StageTimings()
        return Sample(
            ttfaMs = (firstAudioNs - started) / 1e6,
            totalMs = (endedNs - started) / 1e6,
            audioSeconds = samples.toDouble() / OnnxTts.SAMPLE_RATE,
            normalizeMs = stages.normalizeMs,
            phonemeMs = stages.phonemeMs,
            tokenCount = stages.tokenCount,
            durationMs = stages.durationMs,
            melLen = stages.melLen,
            noiseMs = stages.noiseMs,
            decodeMs = stages.decodeMs,
            postprocessMs = stages.postprocessMs,
        )
    }

    private fun measureServicePath(engine: OnnxTts, text: String): Sample {
        val started = System.nanoTime()
        var firstAudioNs = 0L
        var samples = 0L
        val scratch = ByteArray(8192)
        runBlocking {
            engine.synthesizeStreaming(text) { audio ->
                var offset = 0
                while (offset < audio.size) {
                    val count = minOf(audio.size - offset, scratch.size / 2)
                    PcmConverter.floatToPcm16(audio, offset, count, scratch)
                    if (scratch[0].toInt() == TextToSpeech.ERROR) {
                        Log.v(TAG, "unreachable")
                    }
                    offset += count
                }
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

    private fun runListeningMatrix(engine: OnnxTts) {
        val speeds = listOf(1.0f, 1.05f)
        val variations = listOf(
            engine.variant.defaultVariation - 0.07f,
            engine.variant.defaultVariation,
        )
        for (text in listeningCorpus) {
            for (speed in speeds) {
                for (variation in variations) {
                    val sample = measure(engine, text, speed = speed, variation = variation)
                    Log.i(
                        TAG,
                        "listen variant=${engine.variant.id} speed=$speed " +
                            "variation=${"%.2f".format(variation)} " +
                            "ttfa=${"%.0f".format(sample.ttfaMs)} " +
                            "total=${"%.0f".format(sample.totalMs)}",
                    )
                }
            }
        }
    }

    private fun thermalLabel(): String {
        val status = powerManager?.currentThermalStatus ?: return "n/a"
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown($status)"
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
