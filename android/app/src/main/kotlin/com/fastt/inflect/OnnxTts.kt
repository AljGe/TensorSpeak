package com.fastt.inflect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random
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
) : Closeable {

    /**
     * Synthesize chunk by chunk, handing each piece of audio to [onAudio] as soon as it is
     * decoded. Mirrors `InflectPipeline.synthesize` in
     * [pipeline.py](../../../../../../../src/inflect_sandbox/pipeline.py), including the
     * boundary pauses and the per-chunk seed advance.
     *
     * This is the streaming entry point, and it is what makes the engine usable on long
     * text: decoding is ~97% of the work and scales with utterance length, so waiting for a
     * whole paragraph before emitting a sample put 8-14 s between the tap and the first
     * sound. Emitting per chunk makes time-to-first-audio the cost of one sentence instead.
     *
     * @param onAudio receives each piece in order; return false to abandon the utterance.
     * @param speed 0.5..2.0; sent to the graph as `length_scale = 1 / speed`.
     * @param variation 0.0..1.0, the `noise_scale` applied to the latent draw.
     */
    suspend fun synthesizeStreaming(
        text: String,
        speed: Float = 1.0f,
        variation: Float = 0.667f,
        seed: Long = 0L,
        onAudio: (FloatArray) -> Boolean,
    ): Unit = withContext(Dispatchers.Default) {
        require(speed in 0.5f..2.0f) { "speed must be between 0.5 and 2.0" }
        require(variation in 0.0f..1.0f) { "variation must be between 0.0 and 1.0" }

        val chunks = TextChunker.split(text)
        for ((index, chunk) in chunks.withIndex()) {
            if (index > 0) {
                val pause = TextChunker.boundaryPauseSeconds(chunks[index - 1])
                val silence = FloatArray(Math.round(SAMPLE_RATE * pause))
                if (!onAudio(silence)) return@withContext
            }

            val phonemeText = phonemes.phonemize(chunk)
            // A chunk can phonemize to nothing (punctuation the normalizer left behind).
            // The reference raises here; an engine driving the system voice must not die on
            // one unspeakable fragment, so it is skipped - the pause around it still plays.
            if (phonemeText.isBlank()) {
                Log.w(TAG, "chunk produced no phonemes, skipping: $chunk")
                continue
            }

            // seed advances per chunk so adjacent chunks don't share a noise draw
            val tokens = tokenizer.toTokens(phonemeText)
            val audio = synthesizeTokens(tokens, speed, variation, seed + index)
            for (i in audio.indices) audio[i] = audio[i].coerceIn(-1.0f, 1.0f)
            if (!onAudio(audio)) return@withContext
        }
    }

    /**
     * The whole utterance as one array. Convenience over [synthesizeStreaming] for callers
     * that want a complete waveform (the demo activity, the parity test); the TTS service
     * uses the streaming form.
     */
    suspend fun synthesize(
        text: String,
        speed: Float = 1.0f,
        variation: Float = 0.667f,
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

    fun synthesizeTokens(
        tokens: LongArray,
        speed: Float = 1.0f,
        variation: Float = 0.667f,
        seed: Long = 0L,
    ): FloatArray {
        val closeables = mutableListOf<OnnxTensor>()
        try {
            val tokenTensor = OnnxTensor.createTensor(
                env, LongBuffer.wrap(tokens), longArrayOf(1, tokens.size.toLong())
            ).also(closeables::add)
            val lengthTensor = OnnxTensor.createTensor(
                env, LongBuffer.wrap(longArrayOf(tokens.size.toLong())), longArrayOf(1)
            ).also(closeables::add)
            val lengthScale = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(floatArrayOf(1.0f / speed)), longArrayOf()
            ).also(closeables::add)

            duration.run(
                mapOf(
                    "tokens" to tokenTensor,
                    "lengths" to lengthTensor,
                    "length_scale" to lengthScale,
                )
            ).use { durationResult ->
                val mP = durationResult.get(0) as OnnxTensor
                val logsP = durationResult.get(1) as OnnxTensor
                val yMask = durationResult.get(2) as OnnxTensor

                val shape = mP.info.shape // [1, C, mel_len]
                val elements = shape.fold(1L) { acc, dim -> acc * dim }.toInt()
                val noise = standardNormal(elements, seed)

                val noiseTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(noise), shape)
                    .also(closeables::add)
                val noiseScale = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(floatArrayOf(variation)), longArrayOf()
                ).also(closeables::add)

                decode.run(
                    mapOf(
                        "m_p_exp" to mP,
                        "logs_p_exp" to logsP,
                        "y_mask" to yMask,
                        "zp_noise" to noiseTensor,
                        "noise_scale" to noiseScale,
                    )
                ).use { decodeResult ->
                    val waveform = decodeResult.get(0) as OnnxTensor
                    val flat = FloatArray(waveform.floatBuffer.remaining())
                    waveform.floatBuffer.get(flat)
                    return edgeFade(flat)
                }
            }
        } finally {
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

    /** Ramp the first/last 5 ms so concatenated utterances do not click. */
    private fun edgeFade(waveform: FloatArray, milliseconds: Float = 5.0f): FloatArray {
        val frames = minOf(
            Math.round(SAMPLE_RATE * milliseconds / 1000.0f), waveform.size / 2
        )
        if (frames <= 0) return waveform
        for (i in 0 until frames) {
            val ramp = i.toFloat() / (frames - 1).coerceAtLeast(1)
            waveform[i] *= ramp
            waveform[waveform.size - 1 - i] *= ramp
        }
        return waveform
    }

    override fun close() {
        duration.close()
        decode.close()
    }

    /**
     * Which ONNX Runtime execution provider to build the sessions on.
     *
     * [AUTO] is what production uses. The explicit values exist so `SynthesisBenchmark` can
     * measure one against the other instead of assuming XNNPACK is the faster choice.
     */
    enum class Provider { AUTO, XNNPACK, CPU }

    companion object {
        const val SAMPLE_RATE = 24_000

        private const val TAG = "OnnxTts"

        /**
         * Session options for one graph.
         *
         * With XNNPACK the session's own intra-op pool is pinned to a single thread and the
         * thread budget handed to XNNPACK instead - ORT's guidance, and running both pools
         * at full width just oversubscribes the little cores.
         */
        private fun sessionOptions(xnnpack: Boolean): OrtSession.SessionOptions {
            val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
            return OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                if (xnnpack) {
                    addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                    setIntraOpNumThreads(1)
                } else {
                    setIntraOpNumThreads(threads)
                }
            }
        }

        /**
         * Reads both graphs for [variant] and the shared symbol table out of assets.
         * The graphs are stored uncompressed (see `noCompress += "onnx"`), so this is a
         * straight read.
         */
        suspend fun fromAssets(
            context: Context,
            variant: ModelVariant = ModelPreferences.get(context),
            phonemes: PhonemeSource = EspeakPhonemeSource(context),
            provider: Provider = Provider.AUTO,
        ): OnnxTts = withContext(Dispatchers.IO) {
            val assets = context.assets
            val env = OrtEnvironment.getEnvironment()

            val prefix = variant.id
            val durationBytes = assets.open("$prefix/duration.onnx").readBytes()
            val decodeBytes = assets.open("$prefix/decode.onnx").readBytes()

            // decode.onnx is ~97% of synthesis time and is a convolution stack, which is
            // exactly XNNPACK's strength. It is compiled into the ORT AAR but has to be
            // asked for; unsupported nodes fall back to the CPU provider per-node.
            var duration: OrtSession
            var decode: OrtSession
            try {
                val xnnpack = provider != Provider.CPU
                duration = env.createSession(durationBytes, sessionOptions(xnnpack))
                decode = env.createSession(decodeBytes, sessionOptions(xnnpack))
            } catch (error: Exception) {
                // Not fatal: an ORT build or device without XNNPACK still runs on CPU.
                if (provider == Provider.XNNPACK) throw error
                Log.w(TAG, "XNNPACK unavailable, falling back to the CPU provider", error)
                duration = env.createSession(durationBytes, sessionOptions(xnnpack = false))
                decode = env.createSession(decodeBytes, sessionOptions(xnnpack = false))
            }
            val tokenizer = PhonemeTokenizer.fromJson(
                assets.open("symbols.json").bufferedReader().use { it.readText() }
            )
            OnnxTts(env, duration, decode, tokenizer, phonemes, variant)
        }
    }
}
