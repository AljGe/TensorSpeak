package com.fastt.inflect

/**
 * Which Inflect ONNX graph pair to load from assets.
 *
 * Graphs live under `assets/<id>/duration.onnx` and `assets/<id>/decode.onnx`.
 * The text frontend (`symbols.json`, eSpeak) is shared.
 */
enum class ModelVariant(val id: String, val label: String) {
    MICRO("micro", "Micro · higher quality"),
    NANO("nano", "Nano · smaller / faster");

    companion object {
        val DEFAULT = MICRO

        fun fromId(id: String?): ModelVariant =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
