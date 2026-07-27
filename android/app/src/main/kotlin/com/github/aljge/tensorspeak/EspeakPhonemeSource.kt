package com.github.aljge.tensorspeak

import android.content.Context
import java.io.File

/**
 * The real [PhonemeSource]: normalize, then phonemize with the vendored eSpeak-ng, mirroring
 * `run_vits_frontend` in the model repo.
 *
 * The three layers are split across [TextNormalizer], [PhonemizerCompat] and [EspeakNative] so
 * that the first two are testable on the JVM without a device; only the eSpeak call itself
 * needs hardware. `EspeakParityTest` checks the whole chain against the Python sandbox.
 */
class EspeakPhonemeSource(context: Context) : PhonemeSource {

    private val appContext = context.applicationContext

    // espeak-ng opens its dictionaries with stdio, so the data has to exist as real files.
    // Assets live inside the APK, hence the one-time copy into filesDir.
    private val dataDirectory: File by lazy { installData() }

    override fun phonemize(text: String): String {
        EspeakNative.ensureInitialized(dataDirectory.absolutePath)
        val normalized = TextNormalizer.normalize(text)
        if (normalized.isEmpty()) return ""
        val phonemes = PhonemizerCompat.phonemizeLine(normalized) { chunk ->
            EspeakNative.textToPhonemes(chunk)
        }
        return PhonemizerCompat.applyPhonemeOverrides(phonemes)
    }

    /** The normalized text, exposed so tests and the TTS service can log what was spoken. */
    fun normalize(text: String): String = TextNormalizer.normalize(text)

    private fun installData(): File {
        val target = File(appContext.filesDir, DATA_DIRECTORY)
        val stamp = File(target, ".installed")
        // The stamp holds the app version, so an upgrade that ships new data reinstalls it.
        val expected = appContext.packageManager
            .getPackageInfo(appContext.packageName, 0)
            .let { "${it.versionName}/${it.longVersionCode}" }
        if (stamp.isFile && stamp.readText() == expected) return target

        target.deleteRecursively()
        copyAssetTree(DATA_DIRECTORY, target)
        stamp.writeText(expected)
        return target
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val entries = appContext.assets.list(assetPath).orEmpty()
        if (entries.isEmpty()) {
            // A leaf: `list()` returns nothing for files.
            target.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        for (entry in entries) {
            copyAssetTree("$assetPath/$entry", File(target, entry))
        }
    }

    private companion object {
        /**
         * Produced by `scripts/export_android_assets.py --espeak-data`, trimmed to the files
         * en-us needs (~0.9 MB of the 19 MB upstream tree).
         */
        const val DATA_DIRECTORY = "espeak-ng-data"
    }
}
