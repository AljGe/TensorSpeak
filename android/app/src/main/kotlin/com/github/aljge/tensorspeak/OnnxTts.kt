package com.github.aljge.tensorspeak

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.os.Debug
import android.util.Log
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.EnumSet
import java.util.Random
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The two-stage Inflect pipeline on ONNX Runtime.
 *
 * Contract mirrored from `docs/TENSOR_CONTRACT.md`:
 *
 *   duration.onnx: tokens int64[1, text_len], lengths int64[1], length_scale float32[]
 *               -> m_p_exp float32[1, C, mel_len], logs_p_exp [1, C, mel_len],
 *                  y_mask float32[1, 1, mel_len]
 *   decode.onnx : m_p_exp, logs_p_exp, y_mask, zp_noise float32[1, C, mel_len],
 *                 noise_scale float32[] -> waveform float32[1, 1, wav_len]
 *
 * `C` is inter_channels (192 Micro / 128 Nano). Noise shape is taken from the duration
 * outputs so both variants share this class.
 *
 * Duration expansion happens *inside* duration.onnx - its outputs are already at `mel_len`,
 * so there is no length-regulation step to implement here. The only thing this class adds
 * between the graphs is the `zp_noise` draw.
 */
class OnnxTts private constructor(
    private val env: OrtEnvironment,
    private val duration: OrtSession,
    private val decode: OrtSession,
    private val tokenizer: PhonemeTokenizer,
    private val phonemes: PhonemeSource,
    val variant: ModelVariant,
    val runtimeConfig: RuntimeConfig,
) : Closeable {

    private val activeRunOptions = AtomicReference<OrtSession.RunOptions?>(null)

    data class StageTimings(
        val normalizeMs: Double = 0.0,
        val phonemeMs: Double = 0.0,
        val tokenCount: Int = 0,
        val durationMs: Double = 0.0,
        val melLen: Int = 0,
        val noiseMs: Double = 0.0,
        val decodeMs: Double = 0.0,
        val postprocessMs: Double = 0.0,
        val wavLen: Int = 0,
    )

    /**
     * Synthesize chunk by chunk, handing each piece of audio to [onAudio] as soon as it is
     * decoded. Mirrors `InflectPipeline.synthesize` in
     * [pipeline.py](../../../../../../../src/inflect_sandbox/pipeline.py), including the
     * boundary pauses and the per-chunk seed advance.
     *
     * This is the streaming entry point, and it is what makes the engine usable on long
     * text: decoding is ~97% of the work and scales with utterance length, so waiting for a
     * whole paragraph before emitting a sample put 8-14 s between the tap and the first
     * sound. Emitting per chunk makes time-to-first-audio the cost of one (bounded) sentence
     * piece instead.
     *
     * @param onAudio receives each piece in order; return false to abandon the utterance.
     * @param speed 0.5..2.0; sent to the graph as `length_scale = 1 / speed`.
     * @param variation 0.0..1.0, the `noise_scale` applied to the latent draw.
     */
    suspend fun synthesizeStreaming(
        text: String,
        speed: Float = 1.0f,
        variation: Float? = null,
        seed: Long = 0L,
        firstChunkLimit: Int = TextChunker.FIRST_CHUNK_LIMIT,
        shouldContinue: () -> Boolean = { true },
        onChunkTiming: ((StageTimings) -> Unit)? = null,
        onAudio: (FloatArray) -> Boolean,
    ): Unit = withContext(Dispatchers.Default) {
        val selectedVariation = variation ?: variant.defaultVariation
        require(speed in 0.5f..2.0f) { "speed must be between 0.5 and 2.0" }
        require(selectedVariation in 0.0f..1.0f) { "variation must be between 0.0 and 1.0" }

        // Expand money/dates before splitting so the first-chunk budget bounds real decode cost.
        val normalizeStarted = System.nanoTime()
        val forChunking = TextNormalizer.normalize(text).ifBlank {
            TextChunker.collapseWhitespace(text)
        }
        val normalizeMs = (System.nanoTime() - normalizeStarted) / 1e6
        val chunks = TextChunker.split(forChunking, firstChunkLimit = firstChunkLimit)
        for ((index, chunk) in chunks.withIndex()) {
            if (!shouldContinue()) return@withContext
            if (index > 0) {
                val pause = TextChunker.boundaryPauseSeconds(chunks[index - 1])
                val silence = FloatArray(Math.round(SAMPLE_RATE * pause))
                if (!onAudio(silence)) return@withContext
            }

            val phonemeStarted = System.nanoTime()
            val phonemeText = phonemes.phonemize(chunk)
            val phonemeMs = (System.nanoTime() - phonemeStarted) / 1e6
            // A chunk can phonemize to nothing (punctuation the normalizer left behind).
            // The reference raises here; an engine driving the system voice must not die on
            // one unspeakable fragment, so it is skipped - the pause around it still plays.
            if (phonemeText.isBlank()) {
                Log.w(TAG, "chunk produced no phonemes, skipping: $chunk")
                continue
            }
            if (!shouldContinue()) return@withContext

            // seed advances per chunk so adjacent chunks don't share a noise draw
            val tokens = tokenizer.toTokens(phonemeText)
            val (audio, stages) = synthesizeTokensTimed(
                tokens,
                speed,
                selectedVariation,
                seed + index,
                shouldContinue,
            )
            onChunkTiming?.invoke(
                stages.copy(
                    normalizeMs = if (index == 0) normalizeMs else 0.0,
                    phonemeMs = phonemeMs,
                    tokenCount = tokens.size,
                    wavLen = audio.size,
                )
            )
            if (audio.isEmpty()) return@withContext
            if (!onAudio(audio)) return@withContext
        }
    }

    /**
     * The whole utterance as one array. Convenience over [synthesizeStreaming] for callers
     * that want a complete waveform (parity tests); the TTS service and the demo harness
     * use the streaming form.
     */
    suspend fun synthesize(
        text: String,
        speed: Float = 1.0f,
        variation: Float? = null,
        seed: Long = 0L,
    ): FloatArray {
        val pieces = mutableListOf<FloatArray>()
        synthesizeStreaming(text, speed, variation, seed) { pieces.add(it); true }

        val total = pieces.sumOf { it.size }
        val waveform = FloatArray(total)
        var offset = 0
        for (piece in pieces) {
            piece.copyInto(waveform, offset)
            offset += piece.size
        }
        return waveform
    }

    /** Ask any in-flight ORT `run` to abort; checked again between chunks. */
    fun requestStop() {
        activeRunOptions.get()?.let { options ->
            runCatching { options.setTerminate(true) }
                .onFailure { Log.w(TAG, "failed to terminate ORT run", it) }
        }
    }

    fun synthesizeTokens(
        tokens: LongArray,
        speed: Float = 1.0f,
        variation: Float? = null,
        seed: Long = 0L,
        shouldContinue: () -> Boolean = { true },
    ): FloatArray = synthesizeTokensTimed(tokens, speed, variation, seed, shouldContinue).first

    private fun synthesizeTokensTimed(
        tokens: LongArray,
        speed: Float = 1.0f,
        variation: Float? = null,
        seed: Long = 0L,
        shouldContinue: () -> Boolean = { true },
    ): Pair<FloatArray, StageTimings> {
        val selectedVariation = variation ?: variant.defaultVariation
        val closeables = mutableListOf<OnnxTensor>()
        val runOptions = OrtSession.RunOptions()
        activeRunOptions.set(runOptions)
        try {
            if (!shouldContinue()) return FloatArray(0) to StageTimings()
            val tokenTensor = OnnxTensor.createTensor(
                env, LongBuffer.wrap(tokens), longArrayOf(1, tokens.size.toLong())
            ).also(closeables::add)
            val lengthTensor = OnnxTensor.createTensor(
                env, LongBuffer.wrap(longArrayOf(tokens.size.toLong())), longArrayOf(1)
            ).also(closeables::add)
            val lengthScale = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(floatArrayOf(1.0f / speed)), longArrayOf()
            ).also(closeables::add)

            val durationStarted = System.nanoTime()
            duration.run(
                mapOf(
                    "tokens" to tokenTensor,
                    "lengths" to lengthTensor,
                    "length_scale" to lengthScale,
                ),
                runOptions,
            ).use { durationResult ->
                val durationMs = (System.nanoTime() - durationStarted) / 1e6
                if (!shouldContinue()) return FloatArray(0) to StageTimings(durationMs = durationMs)
                val mP = durationResult.get(0) as OnnxTensor
                val logsP = durationResult.get(1) as OnnxTensor
                val yMask = durationResult.get(2) as OnnxTensor

                val shape = mP.info.shape // [1, C, mel_len]
                val melLen = shape[2].toInt()
                val elements = shape.fold(1L) { acc, dim -> acc * dim }.toInt()

                val noiseStarted = System.nanoTime()
                val noise = standardNormal(elements, seed)
                val noiseMs = (System.nanoTime() - noiseStarted) / 1e6

                val noiseTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(noise), shape)
                    .also(closeables::add)
                val noiseScale = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(floatArrayOf(selectedVariation)), longArrayOf()
                ).also(closeables::add)

                val decodeStarted = System.nanoTime()
                decode.run(
                    mapOf(
                        "m_p_exp" to mP,
                        "logs_p_exp" to logsP,
                        "y_mask" to yMask,
                        "zp_noise" to noiseTensor,
                        "noise_scale" to noiseScale,
                    ),
                    runOptions,
                ).use { decodeResult ->
                    val decodeMs = (System.nanoTime() - decodeStarted) / 1e6
                    if (!shouldContinue()) {
                        return FloatArray(0) to StageTimings(
                            tokenCount = tokens.size,
                            durationMs = durationMs,
                            melLen = melLen,
                            noiseMs = noiseMs,
                            decodeMs = decodeMs,
                        )
                    }
                    val waveform = decodeResult.get(0) as OnnxTensor
                    val postStarted = System.nanoTime()
                    val flat = FloatArray(waveform.floatBuffer.remaining())
                    waveform.floatBuffer.get(flat)
                    PcmConverter.edgeFadeAndClip(flat)
                    val postprocessMs = (System.nanoTime() - postStarted) / 1e6
                    return flat to StageTimings(
                        tokenCount = tokens.size,
                        durationMs = durationMs,
                        melLen = melLen,
                        noiseMs = noiseMs,
                        decodeMs = decodeMs,
                        postprocessMs = postprocessMs,
                        wavLen = flat.size,
                    )
                }
            }
        } finally {
            activeRunOptions.compareAndSet(runOptions, null)
            runCatching { runOptions.close() }
            closeables.forEach(OnnxTensor::close)
        }
    }

    /**
     * NOTE: this is *not* bit-identical to NumPy's `default_rng(seed).standard_normal` used
     * by the Python sandbox - only the graphs are shared, not the RNG. Audio is
     * perceptually equivalent but will not match sample-for-sample across the two.
     */
    private fun standardNormal(count: Int, seed: Long): FloatArray {
        val random = Random(seed)
        return FloatArray(count) { random.nextGaussian().toFloat() }
    }

    override fun close() {
        requestStop()
        runCatching {
            if (runtimeConfig.enableProfiling) {
                Log.i(TAG, "duration profile: ${duration.endProfiling()}")
                Log.i(TAG, "decode profile: ${decode.endProfiling()}")
            }
        }
        duration.close()
        decode.close()
    }

    /**
     * Which ONNX Runtime execution provider to build the sessions on.
     *
     * [CPU] is the default because it measured *faster* than XNNPACK on every case of
     * `SynthesisBenchmark` - see the note on [sessionOptions]. [XNNPACK] and [NNAPI] stay
     * selectable so the comparison can be re-run when the runtime or the hardware changes.
     */
    enum class Provider { CPU, XNNPACK, NNAPI }

    companion object {
        const val SAMPLE_RATE = 24_000

        private const val TAG = "OnnxTts"

        @Volatile
        private var globalPoolConfigured = false

        /**
         * Session options for one graph.
         *
         * The default CPU provider is not a placeholder - it is the measured winner.
         * `decode.onnx` is a convolution stack, which reads like XNNPACK's home ground, but
         * on a Pixel 9a (8 cores, ORT 1.27) XNNPACK was slower on every case:
         *
         *   micro  short 1194 vs 603 ms   sentence 3344 vs 1853 ms   long 25899 vs 18354 ms
         *   nano   short  534 vs 364 ms   sentence 1708 vs 1154 ms   long 18917 vs 10574 ms
         *
         * The likely reason is that the ORT CPU provider already parallelizes these convs
         * across all four threads, whereas XNNPACK takes a large share of the graph into a
         * partition that ORT then cannot thread as well - plus per-partition transfer cost.
         * Re-run `SynthesisBenchmark` before changing this back.
         *
         * With XNNPACK the session's own intra-op pool is pinned to a single thread and the
         * thread budget handed to XNNPACK instead, per ORT's guidance.
         *
         * NNAPI is experimental: VITS 1-D conv / ConvTranspose often fragment onto CPU and
         * can lose. Keep it behind the benchmark gate.
         */
        private fun sessionOptions(
            config: RuntimeConfig,
            profilePath: String?,
        ): OrtSession.SessionOptions {
            val threads = config.intraOpThreads
            return OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                when (config.provider) {
                    Provider.XNNPACK -> {
                        val xnnThreads = if (threads <= 0) defaultCpuThreads() else threads
                        addXnnpack(mapOf("intra_op_num_threads" to xnnThreads.toString()))
                        setIntraOpNumThreads(1)
                    }
                    Provider.NNAPI -> {
                        // Prefer accelerator partitions; fall back to ORT CPU for the rest.
                        addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
                        if (threads > 0) setIntraOpNumThreads(threads)
                    }
                    Provider.CPU -> {
                        if (threads > 0) setIntraOpNumThreads(threads)
                    }
                }
                if (config.useGlobalThreadPool && globalPoolConfigured) {
                    disablePerSessionThreads()
                }
                config.allowSpinning?.let { spinning ->
                    addConfigEntry("session.intra_op.allow_spinning", if (spinning) "1" else "0")
                }
                if (config.enableProfiling && profilePath != null) {
                    enableProfiling(profilePath)
                }
            }
        }

        fun defaultCpuThreads(): Int = Runtime.getRuntime().availableProcessors().coerceAtMost(4)

        private fun environmentFor(config: RuntimeConfig): OrtEnvironment {
            if (config.useGlobalThreadPool && !globalPoolConfigured) {
                val threads = if (config.intraOpThreads > 0) {
                    config.intraOpThreads
                } else {
                    defaultCpuThreads()
                }
                return try {
                    OrtEnvironment.ThreadingOptions().use { threading ->
                        threading.setGlobalIntraOpNumThreads(threads)
                        threading.setGlobalInterOpNumThreads(1)
                        config.allowSpinning?.let { threading.setGlobalSpinControl(it) }
                        globalPoolConfigured = true
                        OrtEnvironment.getEnvironment(
                            OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING,
                            "tensorspeak",
                            threading,
                        )
                    }
                } catch (error: Exception) {
                    // Environment already exists without a global pool - fall back.
                    Log.w(TAG, "global ORT thread pool unavailable: ${error.message}")
                    OrtEnvironment.getEnvironment()
                }
            }
            return OrtEnvironment.getEnvironment()
        }

        /**
         * Loads both graphs for [variant] and the shared symbol table.
         *
         * Resolution order when [graphDirectory] is null:
         * 1. Installed GitHub model pack under `filesDir/models/<variant>/`
         * 2. APK assets (dev/debug fallback when graphs were exported locally)
         * 3. [ModelPackMissingException]
         *
         * Optional [graphDirectory] loads pre-optimized / ORT-format files from an absolute
         * directory instead (benchmark / experimental track).
         */
        suspend fun fromAssets(
            context: Context,
            variant: ModelVariant = ModelPreferences.get(context),
            phonemes: PhonemeSource = EspeakPhonemeSource(context),
            provider: Provider = Provider.CPU,
            useFileBackedSessions: Boolean = true,
            config: RuntimeConfig = RuntimeConfig(provider = provider),
            graphDirectory: File? = null,
        ): OnnxTts = withContext(Dispatchers.IO) {
            val assets = context.assets
            val env = environmentFor(config)

            val packManager = ModelPackManager(context)
            val resolvedDirectory = graphDirectory ?: packManager.installedDirectory(variant)
            if (resolvedDirectory == null && !packManager.hasAssetGraphs(variant)) {
                throw ModelPackMissingException(variant)
            }

            val prefix = variant.id
            val profileDir = if (config.enableProfiling) {
                File(context.cacheDir, "ort-profile").also { it.mkdirs() }
            } else {
                null
            }

            fun openSession(name: String): OrtSession {
                val profilePath = profileDir?.let {
                    File(it, "${config.profileFilePrefix ?: variant.id}-$name").absolutePath
                }
                val options = sessionOptions(config, profilePath)
                try {
                    if (resolvedDirectory != null) {
                        val onnx = File(resolvedDirectory, name)
                        val ort = File(resolvedDirectory, name.removeSuffix(".onnx") + ".ort")
                        val path = when {
                            ort.exists() -> ort.absolutePath
                            onnx.exists() -> onnx.absolutePath
                            else -> error("missing $name under $resolvedDirectory")
                        }
                        return env.createSession(path, options)
                    }
                    if (useFileBackedSessions) {
                        val path = materializeAsset(context, "$prefix/$name")
                        return env.createSession(path.absolutePath, options)
                    }
                    val bytes = assets.open("$prefix/$name").readBytes()
                    return env.createSession(bytes, options)
                } finally {
                    options.close()
                }
            }

            val durationSession = openSession("duration.onnx")
            val decodeSession = try {
                openSession("decode.onnx")
            } catch (error: Exception) {
                durationSession.close()
                throw error
            }
            val tokenizer = PhonemeTokenizer.fromJson(
                assets.open("symbols.json").bufferedReader().use { it.readText() }
            )
            Log.i(
                TAG,
                "loaded ${variant.id}/${config.provider} threads=${config.intraOpThreads} " +
                    "spin=${config.allowSpinning} globalPool=${config.useGlobalThreadPool} " +
                    "nativeHeap=${Debug.getNativeHeapAllocatedSize() / 1_000_000}MB",
            )
            OnnxTts(env, durationSession, decodeSession, tokenizer, phonemes, variant, config)
        }

        private fun materializeAsset(context: Context, assetPath: String): File {
            val output = File(context.filesDir, "onnx/$assetPath")
            if (output.exists()) return output
            output.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                output.outputStream().use { stream -> input.copyTo(stream) }
            }
            return output
        }
    }
}
