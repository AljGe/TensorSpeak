package com.github.aljge.tensorspeak

/**
 * Tunables for ONNX Runtime session construction.
 *
 * Defaults match the measured Pixel 9a winner (CPU EP, four intra-op threads). The
 * [SynthesisBenchmark] sweeps alternatives before changing production values.
 */
data class RuntimeConfig(
    val provider: OnnxTts.Provider = OnnxTts.Provider.CPU,
    /** Intra-op worker count. `0` leaves ORT's default. Capped for energy on phones. */
    val intraOpThreads: Int = OnnxTts.defaultCpuThreads(),
    /**
     * When true, both sessions disable per-session pools and share the process-wide ORT
     * pool created with [OrtEnvironment] threading options. Only takes effect if the
     * environment has not already been constructed another way.
     */
    val useGlobalThreadPool: Boolean = false,
    /**
     * ORT worker spin waiting. `null` keeps the runtime default; `false` saves power
     * between chunks at a small scheduling cost.
     */
    val allowSpinning: Boolean? = null,
    /** Write an ORT chrome-trace profile under the app's cache directory. */
    val enableProfiling: Boolean = false,
    val profileFilePrefix: String? = null,
) {
    companion object {
        val DEFAULT = RuntimeConfig()
    }
}
