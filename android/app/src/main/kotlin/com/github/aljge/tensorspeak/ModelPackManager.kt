package com.github.aljge.tensorspeak

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads Inflect ONNX packs from the matching GitHub Release, verifies SHA-256, and
 * installs them under `filesDir/models/<variant>/` for [OnnxTts] file-backed sessions.
 *
 * Stamp file content is the expected zip SHA-256 from [ModelManifest], so an app upgrade
 * that ships a new manifest re-downloads when the user installs again (or when
 * [isInstalled] fails the stamp check).
 */
class ModelPackManager(
    context: Context,
    private val client: OkHttpClient = defaultClient(),
    private val fetcher: PackFetcher? = null,
) {
    private val appContext = context.applicationContext

    fun modelsRoot(): File = File(appContext.filesDir, MODELS_DIR)

    fun packDirectory(variant: ModelVariant): File = File(modelsRoot(), variant.id)

    fun loadManifest(): ModelManifest =
        ModelManifest.parse(
            appContext.assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() },
        )

    fun appVersionName(): String =
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
            ?: "0"

    /** True when both graphs exist and the stamp matches the current manifest digest. */
    fun isInstalled(variant: ModelVariant): Boolean {
        val dir = packDirectory(variant)
        val stamp = File(dir, STAMP_NAME)
        if (!stamp.isFile) return false
        val expected = runCatching { loadManifest().descriptor(variant).zipSha256 }.getOrNull()
            ?: return false
        if (stamp.readText().trim().lowercase() != expected) return false
        return File(dir, "duration.onnx").isFile && File(dir, "decode.onnx").isFile
    }

    /**
     * Directory to pass as [OnnxTts] `graphDirectory` when a pack is installed; null if the
     * caller should try APK assets or raise [ModelPackMissingException].
     */
    fun installedDirectory(variant: ModelVariant): File? =
        packDirectory(variant).takeIf { isInstalled(variant) }

    /** Dev/debug fallback: graphs still present under `assets/<variant>/`. */
    fun hasAssetGraphs(variant: ModelVariant): Boolean =
        runCatching {
            appContext.assets.open("${variant.id}/duration.onnx").close()
            true
        }.getOrDefault(false)

    fun delete(variant: ModelVariant) {
        packDirectory(variant).deleteRecursively()
    }

    /**
     * Download, verify, and extract [variant]. [onProgress] receives 0f..1f for the HTTP
     * transfer (extraction is unmetered after that).
     */
    suspend fun install(
        variant: ModelVariant,
        onProgress: (Float) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val manifest = loadManifest()
        val descriptor = manifest.descriptor(variant)
        val url = manifest.downloadUrl(variant, appVersionName())
        val root = modelsRoot()
        root.mkdirs()
        val tempZip = File(root, "${variant.id}.download.zip")
        val staging = File(root, "${variant.id}.staging")
        try {
            if (tempZip.exists()) tempZip.delete()
            if (staging.exists()) staging.deleteRecursively()

            downloadToFile(url, tempZip, onProgress)
            val actual = sha256Hex(tempZip)
            if (actual != descriptor.zipSha256) {
                throw IOException(
                    "checksum mismatch for ${descriptor.assetName}: expected " +
                        "${descriptor.zipSha256}, got $actual",
                )
            }

            staging.mkdirs()
            unzip(tempZip, staging)
            require(File(staging, "duration.onnx").isFile) { "zip missing duration.onnx" }
            require(File(staging, "decode.onnx").isFile) { "zip missing decode.onnx" }

            val finalDir = packDirectory(variant)
            if (finalDir.exists()) finalDir.deleteRecursively()
            if (!staging.renameTo(finalDir)) {
                staging.copyRecursively(finalDir, overwrite = true)
                staging.deleteRecursively()
            }
            File(finalDir, STAMP_NAME).writeText(descriptor.zipSha256)
            Log.i(TAG, "installed ${variant.id} from $url")
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        } finally {
            tempZip.delete()
        }
    }

    private fun downloadToFile(url: String, dest: File, onProgress: (Float) -> Unit) {
        if (fetcher != null) {
            fetcher.fetch(url, dest, onProgress)
            return
        }
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("download failed HTTP ${response.code} for $url")
            }
            val body = response.body ?: throw IOException("empty body for $url")
            val total = body.contentLength().takeIf { it > 0 }
                ?: loadManifest().variants.values
                    .firstOrNull { url.endsWith(it.assetName) }
                    ?.approxBytes
                ?: -1L
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var readTotal = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        readTotal += n
                        if (total > 0L) {
                            onProgress((readTotal.toDouble() / total).toFloat().coerceIn(0f, 1f))
                        }
                    }
                }
            }
            onProgress(1f)
        }
    }

    companion object {
        private const val TAG = "ModelPack"
        const val MANIFEST_ASSET = "model_manifest.json"
        const val MODELS_DIR = "models"
        const val STAMP_NAME = ".pack_sha256"

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { b -> "%02x".format(b) }
        }

        fun unzip(zipFile: File, destination: File) {
            destination.mkdirs()
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name.trimStart('/').replace('\\', '/')
                    if (name.isEmpty() || name.contains("..")) {
                        zis.closeEntry()
                        continue
                    }
                    val out = File(destination, name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                }
            }
        }
    }
}

/** Injectable download for JVM unit tests (no OkHttp / network). */
fun interface PackFetcher {
    fun fetch(url: String, dest: File, onProgress: (Float) -> Unit)
}
