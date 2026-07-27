package com.github.aljge.tensorspeak.benchmark

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import com.github.aljge.tensorspeak.EspeakPhonemeSource
import com.github.aljge.tensorspeak.ModelVariant
import com.github.aljge.tensorspeak.OnnxTts
import com.github.aljge.tensorspeak.PcmConverter
import com.github.aljge.tensorspeak.RuntimeConfig
import com.github.aljge.tensorspeak.TextChunker
import com.github.aljge.tensorspeak.TextNormalizer
import java.io.File
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** On-device synthesis timing; shared by the in-app benchmark and instrumented tests. */
class SynthesisBenchmarkRunner(private val context: Context) {

    data class DeviceInfo(
        val model: String,
        val sdk: Int,
        val cores: Int,
        val cpuThreadsDefault: Int,
        val thermal: String,
    ) {
        fun headerLine(): String =
            "device=$model sdk=$sdk cores=$cores cpuThreadsDefault=$cpuThreadsDefault thermal=$thermal"
    }

    data class BenchmarkSample(
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

    data class CaseResult(
        val caseLabel: String,
        val text: String,
        val ttfa50: Double,
        val total50: Double,
        val total90: Double,
        val svc50: Double,
        val audioSeconds: Double,
        val firstSample: BenchmarkSample,
    )

    data class ConfigResult(
        val label: String,
        val loadMs: Double,
        val nativeDeltaMb: Long,
        val thermal: String,
        val cases: List<CaseResult>,
        val unavailableReason: String? = null,
    )

    data class BenchmarkProgress(
        val phase: Phase,
        val configLabel: String = "",
        val caseLabel: String = "",
        val configIndex: Int = 0,
        val configCount: Int = 1,
        val caseIndex: Int = 0,
        val caseCount: Int = 0,
    ) {
        enum class Phase {
            STARTING,
            LOADING,
            WARMUP,
            MEASURING,
            DONE,
            CANCELLED,
        }
    }

    data class BenchmarkReport(
        val title: String,
        val deviceInfo: DeviceInfo,
        val lines: List<String>,
        val cancelled: Boolean,
    ) {
        fun asText(): String = lines.joinToString("\n")
    }

    sealed class BenchmarkSpec {
        abstract val title: String

        /** Current prefs: all standard cases, 3 repeats, service-path TTFA. */
        data class QuickCurrent(
            val variant: ModelVariant,
            val config: RuntimeConfig,
        ) : BenchmarkSpec() {
            override val title: String = "quick-current"
        }

        /** CPU / XNNPACK / NNAPI on one variant; subset of cases. */
        data class CompareBackends(
            val variant: ModelVariant,
            val configs: List<RuntimeConfig> = listOf(
                RuntimeConfig(provider = OnnxTts.Provider.CPU),
                RuntimeConfig(provider = OnnxTts.Provider.XNNPACK),
                RuntimeConfig(provider = OnnxTts.Provider.NNAPI),
            ),
        ) : BenchmarkSpec() {
            override val title: String = "compare-backends"
        }

        /** Full provider × variant matrix for adb instrumentation. */
        data class FullProviders(
            val shuffle: Boolean = true,
            val includeListening: (RuntimeConfig) -> Boolean = {
                it.provider == OnnxTts.Provider.CPU
            },
            val probeExperimentalGraphDir: Boolean = true,
        ) : BenchmarkSpec() {
            override val title: String = "providers"
        }

        /** CPU threading sweep (Nano) for adb instrumentation. */
        data class CpuThreading(
            val shuffle: Boolean = true,
        ) : BenchmarkSpec() {
            override val title: String = "cpu-threading"
        }

        /**
         * FP32 assets vs graphs under `files/experimental-ort/<variant>/`
         * (INT8 decode candidates from `scripts/quantize_decode_experiment.py`).
         */
        data class ExperimentalGraphs(
            val caseFilter: Set<String> = setOf("Short", "Sentence", "Expanded"),
            val repeats: Int = 3,
        ) : BenchmarkSpec() {
            override val title: String = "experimental-graphs"
        }
    }

    fun deviceInfo(): DeviceInfo = DeviceInfo(
        model = Build.MODEL,
        sdk = Build.VERSION.SDK_INT,
        cores = Runtime.getRuntime().availableProcessors(),
        cpuThreadsDefault = OnnxTts.defaultCpuThreads(),
        thermal = thermalLabel(),
    )

    suspend fun run(
        spec: BenchmarkSpec,
        onProgress: (BenchmarkProgress) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
        onLine: (String) -> Unit = { Log.i(TAG, it) },
        activeEngine: (OnnxTts?) -> Unit = {},
    ): BenchmarkReport = withContext(Dispatchers.Default) {
        val device = deviceInfo()
        val lines = mutableListOf<String>()
        fun emit(line: String) {
            lines += line
            onLine(line)
        }

        var cancelled = false
        fun cont(): Boolean {
            if (!shouldContinue()) cancelled = true
            return !cancelled
        }

        emit("=".repeat(78))
        emit("${spec.title} ${device.headerLine()}")
        emit("=".repeat(78))

        when (spec) {
            is BenchmarkSpec.QuickCurrent -> {
                if (!cont()) return@withContext report(spec, device, lines, cancelled)
                runOneConfig(
                    variant = spec.variant,
                    config = spec.config,
                    caseFilter = null,
                    repeats = 3,
                    serviceRepeats = 3,
                    includeListening = false,
                    onProgress = onProgress,
                    shouldContinue = ::cont,
                    emit = ::emit,
                    activeEngine = activeEngine,
                )
            }

            is BenchmarkSpec.CompareBackends -> {
                val caseFilter = setOf("Short", "Sentence", "Paragraph")
                spec.configs.forEachIndexed { index, config ->
                    if (!cont()) return@forEachIndexed
                    onProgress(
                        BenchmarkProgress(
                            phase = BenchmarkProgress.Phase.LOADING,
                            configLabel = configLabel(spec.variant, config),
                            configIndex = index,
                            configCount = spec.configs.size,
                        ),
                    )
                    runOneConfig(
                        variant = spec.variant,
                        config = config,
                        caseFilter = caseFilter,
                        repeats = 3,
                        serviceRepeats = 3,
                        includeListening = false,
                        onProgress = onProgress,
                        shouldContinue = ::cont,
                        emit = ::emit,
                        activeEngine = activeEngine,
                    )
                }
            }

            is BenchmarkSpec.FullProviders -> {
                val variants = ModelVariant.entries.let {
                    if (spec.shuffle) it.shuffled() else it
                }
                val configs = listOf(
                    RuntimeConfig(provider = OnnxTts.Provider.CPU),
                    RuntimeConfig(provider = OnnxTts.Provider.XNNPACK),
                    RuntimeConfig(provider = OnnxTts.Provider.NNAPI),
                ).let { if (spec.shuffle) it.shuffled() else it }
                for (variant in variants) {
                    for (config in configs) {
                        if (!cont()) break
                        runOneConfig(
                            variant = variant,
                            config = config,
                            caseFilter = null,
                            repeats = 5,
                            serviceRepeats = 3,
                            includeListening = spec.includeListening(config),
                            onProgress = onProgress,
                            shouldContinue = ::cont,
                            emit = ::emit,
                            activeEngine = activeEngine,
                        )
                    }
                }
                if (spec.probeExperimentalGraphDir && cont()) {
                    runExperimentalGraphDir(::emit, ::cont, activeEngine)
                }
            }

            is BenchmarkSpec.CpuThreading -> {
                val configs = buildList {
                    for (threads in listOf(1, 2, 3, 4, 6, 0)) {
                        add(RuntimeConfig(provider = OnnxTts.Provider.CPU, intraOpThreads = threads))
                    }
                    add(
                        RuntimeConfig(
                            provider = OnnxTts.Provider.CPU,
                            intraOpThreads = OnnxTts.defaultCpuThreads(),
                            allowSpinning = false,
                        ),
                    )
                    add(
                        RuntimeConfig(
                            provider = OnnxTts.Provider.CPU,
                            intraOpThreads = OnnxTts.defaultCpuThreads(),
                            useGlobalThreadPool = true,
                        ),
                    )
                }.let { if (spec.shuffle) it.shuffled() else it }
                val caseFilter = setOf("Short", "Sentence", "Expanded", "Paragraph")
                for (config in configs) {
                    if (!cont()) break
                    runOneConfig(
                        variant = ModelVariant.NANO,
                        config = config,
                        caseFilter = caseFilter,
                        repeats = 3,
                        serviceRepeats = 3,
                        includeListening = false,
                        onProgress = onProgress,
                        shouldContinue = ::cont,
                        emit = ::emit,
                        activeEngine = activeEngine,
                    )
                }
            }

            is BenchmarkSpec.ExperimentalGraphs -> {
                if (cont()) {
                    runExperimentalGraphCompare(
                        caseFilter = spec.caseFilter,
                        repeats = spec.repeats,
                        emit = ::emit,
                        shouldContinue = ::cont,
                        activeEngine = activeEngine,
                        onProgress = onProgress,
                    )
                }
            }
        }

        emit("=".repeat(78))
        onProgress(
            BenchmarkProgress(
                phase = if (cancelled) {
                    BenchmarkProgress.Phase.CANCELLED
                } else {
                    BenchmarkProgress.Phase.DONE
                },
            ),
        )
        report(spec, device, lines, cancelled)
    }

    private suspend fun runOneConfig(
        variant: ModelVariant,
        config: RuntimeConfig,
        caseFilter: Set<String>?,
        repeats: Int,
        serviceRepeats: Int,
        includeListening: Boolean,
        onProgress: (BenchmarkProgress) -> Unit,
        shouldContinue: () -> Boolean,
        emit: (String) -> Unit,
        activeEngine: (OnnxTts?) -> Unit,
    ) {
        val label = configLabel(variant, config)
        onProgress(
            BenchmarkProgress(
                phase = BenchmarkProgress.Phase.LOADING,
                configLabel = label,
            ),
        )
        if (!shouldContinue()) return

        val beforeNative = Debug.getNativeHeapAllocatedSize()
        val engine = try {
            val started = System.nanoTime()
            val created = OnnxTts.fromAssets(
                context = context,
                variant = variant,
                phonemes = EspeakPhonemeSource(context),
                config = config,
            )
            val loadMs = (System.nanoTime() - started) / 1e6
            emit("")
            emit(
                "--- $label (load ${"%.0f".format(loadMs)} ms, " +
                    "dNative=${(Debug.getNativeHeapAllocatedSize() - beforeNative) / 1_000_000}MB, " +
                    "thermal=${thermalLabel()})",
            )
            emit(TABLE_HEADER)
            created
        } catch (error: Exception) {
            emit("--- $label: unavailable (${error.message})")
            return
        }

        activeEngine(engine)
        try {
            engine.use {
                onProgress(
                    BenchmarkProgress(
                        phase = BenchmarkProgress.Phase.WARMUP,
                        configLabel = label,
                    ),
                )
                if (!shouldContinue()) {
                    engine.requestStop()
                    return
                }
                engine.synthesize("Warm up.")

                val cases = STANDARD_CASES.filter { caseFilter == null || it.first in caseFilter }
                cases.forEachIndexed { caseIndex, (caseLabel, text) ->
                    if (!shouldContinue()) {
                        engine.requestStop()
                        return@use
                    }
                    onProgress(
                        BenchmarkProgress(
                            phase = BenchmarkProgress.Phase.MEASURING,
                            configLabel = label,
                            caseLabel = caseLabel,
                            caseIndex = caseIndex,
                            caseCount = cases.size,
                        ),
                    )
                    val samples = List(repeats) {
                        if (!shouldContinue()) return@List BenchmarkSample(0.0, 0.0, 0.0)
                        measure(engine, text, shouldContinue)
                    }
                    if (!shouldContinue()) {
                        engine.requestStop()
                        return@use
                    }
                    val serviceSamples = List(minOf(serviceRepeats, repeats)) {
                        measureServicePath(engine, text, shouldContinue)
                    }
                    val ttfa50 = percentile(samples.map { it.ttfaMs }, 50)
                    val total50 = percentile(samples.map { it.totalMs }, 50)
                    val total90 = percentile(samples.map { it.totalMs }, 90)
                    val svc50 = percentile(serviceSamples.map { it.ttfaMs }, 50)
                    val audioSeconds = samples.first().audioSeconds
                    val first = samples.first()
                    emit(formatResultLine(caseLabel, text.length, ttfa50, total50, total90, audioSeconds, svc50))
                    emit(formatStageLine(text, first))
                }
                if (includeListening && shouldContinue()) {
                    runListeningMatrix(engine, emit, shouldContinue)
                }
            }
        } finally {
            activeEngine(null)
        }
    }

    private suspend fun runExperimentalGraphDir(
        emit: (String) -> Unit,
        shouldContinue: () -> Boolean,
        activeEngine: (OnnxTts?) -> Unit,
    ) {
        runExperimentalGraphCompare(
            caseFilter = setOf("Short"),
            repeats = 1,
            emit = emit,
            shouldContinue = shouldContinue,
            activeEngine = activeEngine,
            onProgress = {},
        )
    }

    private suspend fun runExperimentalGraphCompare(
        caseFilter: Set<String>,
        repeats: Int,
        emit: (String) -> Unit,
        shouldContinue: () -> Boolean,
        activeEngine: (OnnxTts?) -> Unit,
        onProgress: (BenchmarkProgress) -> Unit,
    ) {
        val experimental = File(context.getExternalFilesDir(null), "experimental-ort")
        if (!experimental.isDirectory) {
            emit("experimental ORT dir absent: $experimental")
            return
        }
        emit("")
        emit("--- experimental-ort compare (FP32 assets vs pushed graphs) @ $experimental")
        emit(TABLE_HEADER)
        val cases = STANDARD_CASES.filter { it.first in caseFilter }
        for (variant in ModelVariant.entries) {
            if (!shouldContinue()) return
            val dir = File(experimental, variant.id)
            if (!dir.isDirectory) {
                emit("experimental ${variant.id}: dir absent ($dir)")
                continue
            }
            for ((sourceLabel, graphDirectory) in listOf("fp32-assets" to null, "experimental" to dir)) {
                if (!shouldContinue()) return
                val label = "${variant.id}/$sourceLabel"
                onProgress(
                    BenchmarkProgress(
                        phase = BenchmarkProgress.Phase.LOADING,
                        configLabel = label,
                    ),
                )
                val beforeNative = Debug.getNativeHeapAllocatedSize()
                val engine = try {
                    val started = System.nanoTime()
                    val created = OnnxTts.fromAssets(
                        context = context,
                        variant = variant,
                        phonemes = EspeakPhonemeSource(context),
                        config = RuntimeConfig.DEFAULT,
                        graphDirectory = graphDirectory,
                    )
                    val loadMs = (System.nanoTime() - started) / 1e6
                    emit(
                        "--- $label (load ${"%.0f".format(loadMs)} ms, " +
                            "dNative=${(Debug.getNativeHeapAllocatedSize() - beforeNative) / 1_000_000}MB, " +
                            "thermal=${thermalLabel()}, dir=${graphDirectory ?: "assets"})",
                    )
                    created
                } catch (error: Exception) {
                    emit("--- $label: unavailable (${error.message})")
                    continue
                }
                activeEngine(engine)
                try {
                    engine.use {
                        engine.synthesize("Warm up.")
                        for ((caseLabel, text) in cases) {
                            if (!shouldContinue()) {
                                engine.requestStop()
                                return
                            }
                            onProgress(
                                BenchmarkProgress(
                                    phase = BenchmarkProgress.Phase.MEASURING,
                                    configLabel = label,
                                    caseLabel = caseLabel,
                                ),
                            )
                            val samples = List(repeats) {
                                if (!shouldContinue()) return@List BenchmarkSample(0.0, 0.0, 0.0)
                                measure(engine, text, shouldContinue)
                            }
                            val serviceSamples = List(minOf(repeats, 2)) {
                                measureServicePath(engine, text, shouldContinue)
                            }
                            val ttfa50 = percentile(samples.map { it.ttfaMs }, 50)
                            val total50 = percentile(samples.map { it.totalMs }, 50)
                            val total90 = percentile(samples.map { it.totalMs }, 90)
                            val svc50 = percentile(serviceSamples.map { it.ttfaMs }, 50)
                            val first = samples.first()
                            emit(
                                formatResultLine(
                                    caseLabel,
                                    text.length,
                                    ttfa50,
                                    total50,
                                    total90,
                                    first.audioSeconds,
                                    svc50,
                                ),
                            )
                            emit(formatStageLine(text, first))
                        }
                    }
                } finally {
                    activeEngine(null)
                }
            }
        }
    }

    private suspend fun runListeningMatrix(
        engine: OnnxTts,
        emit: (String) -> Unit,
        shouldContinue: () -> Boolean,
    ) {
        val speeds = listOf(1.0f, 1.05f)
        val variations = listOf(
            engine.variant.defaultVariation - 0.07f,
            engine.variant.defaultVariation,
        )
        for (text in LISTENING_CORPUS) {
            for (speed in speeds) {
                for (variation in variations) {
                    if (!shouldContinue()) return
                    val sample = measure(engine, text, shouldContinue, speed = speed, variation = variation)
                    emit(
                        "listen variant=${engine.variant.id} speed=$speed " +
                            "variation=${"%.2f".format(variation)} " +
                            "ttfa=${"%.0f".format(sample.ttfaMs)} " +
                            "total=${"%.0f".format(sample.totalMs)}",
                    )
                }
            }
        }
    }

    private suspend fun measure(
        engine: OnnxTts,
        text: String,
        shouldContinue: () -> Boolean,
        speed: Float = 1.0f,
        variation: Float = engine.variant.defaultVariation,
    ): BenchmarkSample {
        val started = System.nanoTime()
        var firstAudioNs = 0L
        var samples = 0L
        var chunkCount = 0
        var firstStages: OnnxTts.StageTimings? = null
        engine.synthesizeStreaming(
            text,
            speed = speed,
            variation = variation,
            shouldContinue = shouldContinue,
            onChunkTiming = { stages ->
                if (firstStages == null) firstStages = stages
            },
        ) { audio ->
            if (!shouldContinue()) return@synthesizeStreaming false
            if (firstAudioNs == 0L) firstAudioNs = System.nanoTime()
            samples += audio.size
            chunkCount += 1
            true
        }
        if (chunkCount > 1) {
            val slackMs = (samples.toDouble() / OnnxTts.SAMPLE_RATE) * 1000.0 -
                ((System.nanoTime() - firstAudioNs) / 1e6)
            Log.i(TAG, "chunkSlack=${"%.0f".format(slackMs)}ms for $chunkCount chunks")
        }
        val stages = firstStages ?: OnnxTts.StageTimings()
        val firstNs = if (firstAudioNs > 0L) firstAudioNs else System.nanoTime()
        return BenchmarkSample(
            ttfaMs = (firstNs - started) / 1e6,
            totalMs = (System.nanoTime() - started) / 1e6,
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

    private suspend fun measureServicePath(
        engine: OnnxTts,
        text: String,
        shouldContinue: () -> Boolean,
    ): BenchmarkSample {
        val started = System.nanoTime()
        var firstAudioNs = 0L
        var samples = 0L
        var checksum = 0
        val scratch = ByteArray(8192)
        engine.synthesizeStreaming(
            text,
            shouldContinue = shouldContinue,
        ) { audio ->
            if (!shouldContinue()) return@synthesizeStreaming false
            var offset = 0
            while (offset < audio.size) {
                val count = minOf(audio.size - offset, scratch.size / 2)
                PcmConverter.floatToPcm16(audio, offset, count, scratch)
                checksum = checksum xor scratch[0].toInt() xor scratch[1].toInt()
                offset += count
            }
            if (firstAudioNs == 0L) firstAudioNs = System.nanoTime()
            samples += audio.size
            true
        }
        if (checksum == Int.MIN_VALUE) Log.v(TAG, "unreachable")
        val firstNs = if (firstAudioNs > 0L) firstAudioNs else System.nanoTime()
        return BenchmarkSample(
            ttfaMs = (firstNs - started) / 1e6,
            totalMs = (System.nanoTime() - started) / 1e6,
            audioSeconds = samples.toDouble() / OnnxTts.SAMPLE_RATE,
        )
    }

    private fun thermalLabel(): String {
        val powerManager = context.getSystemService(PowerManager::class.java)
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

    private fun configLabel(variant: ModelVariant, config: RuntimeConfig): String =
        "${variant.id}/${config.provider}/t${config.intraOpThreads}" +
            "/spin${config.allowSpinning}/g${config.useGlobalThreadPool}"

    private fun report(
        spec: BenchmarkSpec,
        device: DeviceInfo,
        lines: List<String>,
        cancelled: Boolean,
    ): BenchmarkReport = BenchmarkReport(spec.title, device, lines, cancelled)

    companion object {
        const val TAG = "SynthBench"

        val TABLE_HEADER =
            "%-10s %6s %8s %8s %8s %8s %7s %8s".format(
                "case", "chars", "ttfa50", "tot50", "tot90", "audio", "rtf50", "svc50",
            )

        val STANDARD_CASES = listOf(
            "Short" to "Hello world.",
            "Sentence" to "A small voice can still have something meaningful to say.",
            "Expanded" to "Dr. Chen paid $1,234.50 on 7/4/2026 at 3:05 PM.",
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

        private val LISTENING_CORPUS = listOf(
            "Dr. Chen paid $1,234.50 on 7/4/2026 at 3:05 PM.",
            "Bring pens, paper, etc. tomorrow.",
            "He lives in the U.S.A. now.",
            "The fluorescent light flickered in Saskatchewan.",
            "A long sentence with many commas, pauses, and clauses should still start quickly.",
        )

        fun percentile(values: List<Double>, p: Int): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val rank = ceil((p / 100.0) * sorted.size).toInt().coerceIn(1, sorted.size) - 1
            return sorted[rank]
        }

        fun formatResultLine(
            caseLabel: String,
            chars: Int,
            ttfa50: Double,
            total50: Double,
            total90: Double,
            audioSeconds: Double,
            svc50: Double,
        ): String =
            "%-10s %6d %8s %8s %8s %8s %7s %8s".format(
                caseLabel,
                chars,
                "%.0f ms".format(ttfa50),
                "%.0f ms".format(total50),
                "%.0f ms".format(total90),
                "%.2f s".format(audioSeconds),
                "%.3f".format(total50 / 1000.0 / audioSeconds.coerceAtLeast(0.001)),
                "%.0f ms".format(svc50),
            )

        fun formatStageLine(text: String, sample: BenchmarkSample): String =
            (
                "  stages norm=%.1f phon=%.1f tok=%d dur=%.0f mel=%d noise=%.1f " +
                    "dec=%.0f post=%.1f normLen=%d chunks=%d"
                ).format(
                sample.normalizeMs,
                sample.phonemeMs,
                sample.tokenCount,
                sample.durationMs,
                sample.melLen,
                sample.noiseMs,
                sample.decodeMs,
                sample.postprocessMs,
                TextNormalizer.normalize(text).length,
                TextChunker.split(TextNormalizer.normalize(text)).size,
            )
    }
}
