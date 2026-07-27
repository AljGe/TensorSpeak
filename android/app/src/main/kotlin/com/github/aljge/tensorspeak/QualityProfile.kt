package com.github.aljge.tensorspeak

enum class QualityProfile(val id: String, val label: String) {
    BALANCED("balanced", "Balanced (default)"),
    STABLE("stable", "Stable pronunciation");

    fun variationFor(variant: ModelVariant): Float =
        when (this) {
            BALANCED -> variant.defaultVariation
            STABLE -> (variant.defaultVariation - 0.07f).coerceAtLeast(0.0f)
        }

    companion object {
        val DEFAULT = BALANCED

        fun fromId(id: String?): QualityProfile =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
