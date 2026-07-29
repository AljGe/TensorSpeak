package com.github.aljge.tensorspeak

/**
 * Which Inflect ONNX graph pair to load.
 *
 * Production loads from an installed GitHub model pack under
 * `filesDir/models/<id>/`. Debug builds may still ship graphs under
 * `assets/<id>/duration.onnx` and `assets/<id>/decode.onnx`.
 * The text frontend (`symbols.json`, eSpeak) is shared.
 */
enum class ModelVariant(
    val id: String,
    val label: String,
    val defaultVariation: Float,
) {
    MICRO("micro", "Micro · higher quality", 0.62f),
    NANO("nano", "Nano · smaller / faster", 0.58f);

    companion object {
        val DEFAULT = MICRO

        fun fromId(id: String?): ModelVariant =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
