package com.github.aljge.tensorspeak

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.aljge.tensorspeak.benchmark.SynthesisBenchmarkRunner
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures what the engine actually costs on device across variants, providers, thread
 * counts, and the service PCM path.
 *
 * Not an assertion test - it prints tables to logcat under [SynthesisBenchmarkRunner.TAG].
 *
 * Run with:
 *   ./gradlew :app:installDebug :app:installDebugAndroidTest
 *   adb shell am instrument -w -e class com.github.aljge.tensorspeak.SynthesisBenchmark \
 *       com.github.aljge.tensorspeak.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class SynthesisBenchmark {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val runner = SynthesisBenchmarkRunner(context)

    @Test
    fun benchmarkVariantsAndProviders() {
        runBlockingCompat {
            runner.run(SynthesisBenchmarkRunner.BenchmarkSpec.FullProviders())
        }
    }

    @Test
    fun benchmarkCpuThreading() {
        runBlockingCompat {
            runner.run(SynthesisBenchmarkRunner.BenchmarkSpec.CpuThreading())
        }
    }

    private fun runBlockingCompat(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}
