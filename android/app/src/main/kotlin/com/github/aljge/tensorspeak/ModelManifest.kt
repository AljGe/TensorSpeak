package com.github.aljge.tensorspeak

import org.json.JSONObject

/** Per-variant entry in [assets/model_manifest.json]. */
data class ModelPackDescriptor(
    val assetName: String,
    val zipSha256: String,
    val approxBytes: Long,
)

/**
 * Release metadata for on-device ONNX packs hosted on GitHub Releases.
 *
 * URL template placeholders: `{version}` (app [versionName]) and `{assetName}`.
 */
data class ModelManifest(
    val repo: String,
    val urlTemplate: String,
    val variants: Map<String, ModelPackDescriptor>,
) {
    fun descriptor(variant: ModelVariant): ModelPackDescriptor =
        variants[variant.id]
            ?: error("model_manifest.json has no entry for ${variant.id}")

    fun downloadUrl(variant: ModelVariant, versionName: String): String {
        val desc = descriptor(variant)
        return urlTemplate
            .replace("{version}", versionName)
            .replace("{assetName}", desc.assetName)
    }

    companion object {
        fun parse(json: String): ModelManifest {
            val root = JSONObject(json)
            val variantsObj = root.getJSONObject("variants")
            val variants = mutableMapOf<String, ModelPackDescriptor>()
            val keys = variantsObj.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val entry = variantsObj.getJSONObject(id)
                variants[id] = ModelPackDescriptor(
                    assetName = entry.getString("assetName"),
                    zipSha256 = entry.getString("zipSha256").lowercase(),
                    approxBytes = entry.getLong("approxBytes"),
                )
            }
            return ModelManifest(
                repo = root.getString("repo"),
                urlTemplate = root.getString("urlTemplate"),
                variants = variants,
            )
        }
    }
}
