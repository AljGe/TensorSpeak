package com.github.aljge.tensorspeak

/**
 * User-selectable ONNX Runtime backends. Defaults stay on the measured CPU path; the others
 * are opt-in experiments that can be faster or slower depending on the device.
 */
enum class ExecutionBackend(
    val id: String,
    val label: String,
    val provider: OnnxTts.Provider,
) {
    CPU("cpu", "CPU (default)", OnnxTts.Provider.CPU),
    XNNPACK("xnnpack", "Experimental · XNNPACK", OnnxTts.Provider.XNNPACK),
    NNAPI("nnapi", "Experimental · NNAPI", OnnxTts.Provider.NNAPI);

    companion object {
        val DEFAULT = CPU

        fun fromId(id: String?): ExecutionBackend =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
