package com.github.aljge.tensorspeak

/**
 * Raised when an on-device variant has neither an installed GitHub model pack nor APK
 * asset graphs. Callers should prompt a download via [ModelPackManager] rather than hang.
 */
class ModelPackMissingException(
    val variant: ModelVariant,
    message: String = "On-device model pack for ${variant.id} is not installed",
) : IllegalStateException(message)
