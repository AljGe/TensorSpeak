package com.github.aljge.tensorspeak.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class SynthesisBenchmarkRunnerTest {

    @Test
    fun percentile_p50_of_five_values() {
        val values = listOf(100.0, 200.0, 300.0, 400.0, 500.0)
        assertEquals(300.0, SynthesisBenchmarkRunner.percentile(values, 50), 0.001)
    }

    @Test
    fun percentile_empty_returns_zero() {
        assertEquals(0.0, SynthesisBenchmarkRunner.percentile(emptyList(), 50), 0.001)
    }

    @Test
    fun formatResultLine_matches_column_layout() {
        val line = SynthesisBenchmarkRunner.formatResultLine(
            caseLabel = "Short",
            chars = 12,
            ttfa50 = 400.0,
            total50 = 800.0,
            total90 = 900.0,
            audioSeconds = 1.5,
            svc50 = 420.0,
        )
        assert(line.contains("Short"))
        assert(line.contains("400 ms"))
        assert(line.contains("1.50 s"))
    }

    @Test
    fun formatStageLine_includes_chunk_count() {
        val sample = SynthesisBenchmarkRunner.BenchmarkSample(
            ttfaMs = 1.0,
            totalMs = 2.0,
            audioSeconds = 1.0,
            normalizeMs = 1.0,
            phonemeMs = 2.0,
            tokenCount = 10,
            durationMs = 3.0,
            melLen = 100,
            decodeMs = 50.0,
        )
        val line = SynthesisBenchmarkRunner.formatStageLine("Hello world.", sample)
        assert(line.startsWith("  stages"))
        assert(line.contains("tok=10"))
    }
}
