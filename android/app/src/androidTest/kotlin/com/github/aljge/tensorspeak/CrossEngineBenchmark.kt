package com.github.aljge.tensorspeak

import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Measures wall-clock TTS synthesis time across engines installed on the device
 * using Android's [TextToSpeech] framework API (synthesizeToFile).
 *
 * Both TensorSpeak and Sherpa-ONNX (or any other installed TTS engine) are
 * driven through the same code path, so the comparison is apples-to-apples
 * at the framework level.
 *
 * Run with:
 *   ./gradlew :app:installDebug :app:installDebugAndroidTest
 *   adb shell am instrument -w -e class com.github.aljge.tensorspeak.CrossEngineBenchmark \
 *       com.github.aljge.tensorspeak.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class CrossEngineBenchmark {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val engines = listOf(
        "com.github.aljge.tensorspeak" to "TensorSpeak",
        "com.k2fsa.sherpa.onnx.tts.engine" to "Sherpa-ONNX",
    )

    private val cases = listOf(
        "Short" to "Hello world.",
        "Sentence" to "A small voice can still have something meaningful to say.",
        "Expanded" to "Dr. Chen paid one thousand two hundred thirty four dollars and fifty cents on July fourth, twenty twenty six at three oh five PM.",
        "Paragraph" to
            "The engine runs entirely on the device, with no network round trip at any " +
            "point. It loads two ONNX graphs, one predicting durations and one decoding " +
            "the waveform, and streams the result back sentence by sentence.",
    )

    private val repeats = 2
    private val warmupText = "Warm up synthesis."

    @Test
    fun compareEngines() {
        val outDir = File(context.cacheDir, "tts_benchmark")
        outDir.mkdirs()

        emit("=".repeat(78))
        emit("cross-engine benchmark  device=${Build.MODEL}  sdk=${Build.VERSION.SDK_INT}  " +
            "cores=${Runtime.getRuntime().availableProcessors()}")
        emit("=".repeat(78))

        for ((enginePkg, engineName) in engines) {
            emit("")
            emit("--- $engineName ($enginePkg)")
            val tts = initEngine(enginePkg)
            if (tts == null) {
                emit("    SKIPPED: engine not available or failed to init")
                continue
            }

            // Warmup
            val warmupFile = File(outDir, "warmup.wav")
            synthesizeToFile(tts, warmupText, warmupFile, "warmup")
            warmupFile.delete()

            emit(HEADER)

            for ((caseIdx, pair) in cases.withIndex()) {
                val (label, text) = pair
                if (caseIdx > 0) Thread.sleep(2000) // cool-down to avoid OPlus CPU kill
                val timings = mutableListOf<Long>()
                val fileSizes = mutableListOf<Long>()
                for (rep in 0 until repeats) {
                    val wavFile = File(outDir, "${engineName}_${label}_$rep.wav")
                    val ms = synthesizeToFile(tts, text, wavFile, "${label}_$rep")
                    if (ms >= 0) {
                        timings += ms
                        fileSizes += wavFile.length()
                    }
                    wavFile.delete()
                }
                if (timings.isEmpty()) {
                    emit("%-12s  FAILED".format(label))
                    continue
                }
                val sorted = timings.sorted()
                val p50 = sorted[sorted.size / 2]
                val p90 = sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.lastIndex)]
                val avgFileSize = fileSizes.average()
                // Estimate audio duration from WAV file size (16-bit mono 24kHz = 48000 bytes/s + 44 header)
                val audioDurS = (avgFileSize - 44.0).coerceAtLeast(0.0).toDouble() / 48000.0
                val rtf = if (audioDurS > 0.01) p50.toDouble() / 1000.0 / audioDurS else Double.NaN
                emit(
                    "%-12s %5d chars  p50=%5d ms  p90=%5d ms  audio=%.2f s  rtf=%.3f".format(
                        label, text.length, p50, p90, audioDurS, rtf,
                    )
                )
            }

            tts.shutdown()
        }

        emit("=".repeat(78))
        emit("done")

        // Clean up
        outDir.deleteRecursively()
    }

    private fun initEngine(enginePackage: String): TextToSpeech? {
        val latch = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val tts = TextToSpeech(context, { result ->
            status = result
            latch.countDown()
        }, enginePackage)

        if (!latch.await(30, TimeUnit.SECONDS)) {
            emit("    init timeout for $enginePackage")
            tts.shutdown()
            return null
        }
        if (status != TextToSpeech.SUCCESS) {
            emit("    init failed for $enginePackage: status=$status")
            tts.shutdown()
            return null
        }
        // Set language to English
        tts.language = java.util.Locale.US
        return tts
    }

    /**
     * Synthesizes [text] to [file] and returns elapsed wall-clock ms, or -1 on failure.
     */
    private fun synthesizeToFile(
        tts: TextToSpeech,
        text: String,
        file: File,
        utteranceId: String,
    ): Long {
        val latch = CountDownLatch(1)
        var success = false

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                success = true
                latch.countDown()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                latch.countDown()
            }
            override fun onError(id: String?, errorCode: Int) {
                latch.countDown()
            }
        })

        val params = Bundle()
        val started = System.nanoTime()
        val result = tts.synthesizeToFile(text, params, file, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            return -1
        }
        if (!latch.await(120, TimeUnit.SECONDS)) {
            emit("    timeout synthesizing: $utteranceId")
            return -1
        }
        if (!success) return -1
        return (System.nanoTime() - started) / 1_000_000
    }

    companion object {
        private const val TAG = "CrossEngine"
        private val HEADER = "%-12s %11s  %10s  %10s  %10s  %7s".format(
            "case", "length", "p50", "p90", "audio", "rtf",
        )

        private fun emit(line: String) {
            Log.i(TAG, line)
        }
    }
}
