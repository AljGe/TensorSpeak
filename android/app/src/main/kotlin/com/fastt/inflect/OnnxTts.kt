package com.fastt.inflect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
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
 *               -> m_p_exp float32[1, 192, mel_len], logs_p_exp [1, 192, mel_len],
 *                  y_mask float32[1, 1, mel_len]
 *   decode.onnx : m_p_exp, logs_p_exp, y_mask, zp_noise float32[1, 192, mel_len],
 *                 noise_scale float32[] -> waveform float32[1, 1, wav_len]
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
) : Closeable {

    /**
     * @param speed 0.5..2.0; sent to the graph as `length_scale = 1 / speed`.
     * @param variation 0.0..1.0, the `noise_scale` applied to the latent draw.
     */
    suspend fun synthesize(
        text: String,
        speed: Float = 1.0f,
        variation: Float = 0.667f,
        seed: Long = 0L,
    ): FloatArray = withContext(Dispatchers.Default) {
        require(speed in 0.5f..2.0f) { "speed must be between 0.5 and 2.0" }
        require(variation in 0.0f..1.0f) { "variation must be between 0.0 and 1.0" }

        val tokens = tokenizer.toTokens(phonemes.phonemize(text))
        synthesizeTokens(tokens, speed, variation, seed)
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

                val shape = mP.info.shape // [1, 192, mel_len]
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

    companion object {
        const val SAMPLE_RATE = 24_000

        /**
         * Reads both graphs and the symbol table out of assets. The graphs are stored
         * uncompressed (see `noCompress += "onnx"`), so this is a straight read.
         */
        suspend fun fromAssets(
            context: Context,
            phonemes: PhonemeSource = FixturePhonemeSource.DEMO,
        ): OnnxTts = withContext(Dispatchers.IO) {
            val assets = context.assets
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
            }

            val duration = env.createSession(assets.open("duration.onnx").readBytes(), options)
            val decode = env.createSession(assets.open("decode.onnx").readBytes(), options)
            val tokenizer = PhonemeTokenizer.fromJson(
                assets.open("symbols.json").bufferedReader().use { it.readText() }
            )
            OnnxTts(env, duration, decode, tokenizer, phonemes)
        }
    }
}
