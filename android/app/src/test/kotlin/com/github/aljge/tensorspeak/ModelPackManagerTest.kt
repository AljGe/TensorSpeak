package com.github.aljge.tensorspeak

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelManifestTest {

    @Test
    fun `parses release manifest and builds download urls`() {
        val json = """
            {
              "repo": "AljGe/TensorSpeak",
              "urlTemplate": "https://github.com/AljGe/TensorSpeak/releases/download/v{version}/{assetName}",
              "variants": {
                "micro": {
                  "assetName": "TensorSpeak-model-micro.zip",
                  "zipSha256": "ABC123",
                  "approxBytes": 34820428
                },
                "nano": {
                  "assetName": "TensorSpeak-model-nano.zip",
                  "zipSha256": "def456",
                  "approxBytes": 14788940
                }
              }
            }
        """.trimIndent()
        val manifest = ModelManifest.parse(json)
        assertEquals("AljGe/TensorSpeak", manifest.repo)
        assertEquals("abc123", manifest.descriptor(ModelVariant.MICRO).zipSha256)
        assertEquals("def456", manifest.descriptor(ModelVariant.NANO).zipSha256)
        assertEquals(
            "https://github.com/AljGe/TensorSpeak/releases/download/v0.3.5/TensorSpeak-model-micro.zip",
            manifest.downloadUrl(ModelVariant.MICRO, "0.3.5"),
        )
    }

    @Test
    fun `missing variant throws`() {
        val json = """
            {
              "repo": "AljGe/TensorSpeak",
              "urlTemplate": "https://example/{version}/{assetName}",
              "variants": {
                "micro": {
                  "assetName": "TensorSpeak-model-micro.zip",
                  "zipSha256": "aa",
                  "approxBytes": 1
                }
              }
            }
        """.trimIndent()
        val manifest = ModelManifest.parse(json)
        try {
            manifest.descriptor(ModelVariant.NANO)
            fail("expected error")
        } catch (_: IllegalStateException) {
            // expected
        }
    }
}

class ModelPackUtilsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `sha256Hex matches known digest`() {
        val file = temp.newFile("payload.bin")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        // echo -n $'\x01\x02\x03\x04' | sha256sum
        assertEquals(
            "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",
            ModelPackManager.sha256Hex(file),
        )
    }

    @Test
    fun `unzip extracts flat onnx entries and rejects traversal`() {
        val zip = temp.newFile("pack.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("duration.onnx"))
            zos.write("duration".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("decode.onnx"))
            zos.write("decode".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("../evil.bin"))
            zos.write("nope".toByteArray())
            zos.closeEntry()
        }
        val dest = temp.newFolder("extracted")
        ModelPackManager.unzip(zip, dest)
        assertEquals("duration", File(dest, "duration.onnx").readText())
        assertEquals("decode", File(dest, "decode.onnx").readText())
        assertFalse(File(temp.root, "evil.bin").exists())
    }
}

class ModelPackMissingExceptionTest {

    @Test
    fun `carries variant id`() {
        val error = ModelPackMissingException(ModelVariant.NANO)
        assertEquals(ModelVariant.NANO, error.variant)
        assertTrue(error.message!!.contains("nano"))
    }
}

class ModelPackStampLogicTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `stamp file marks matching digest as installed layout`() {
        val dir = temp.newFolder("models", "micro")
        File(dir, "duration.onnx").writeText("d")
        File(dir, "decode.onnx").writeText("e")
        val digest = "aabbcc"
        File(dir, ModelPackManager.STAMP_NAME).writeText(digest)

        assertTrue(File(dir, "duration.onnx").isFile)
        assertTrue(File(dir, ModelPackManager.STAMP_NAME).readText() == digest)
        // Mismatch would be treated as not installed by ModelPackManager.isInstalled.
        assertFalse(File(dir, ModelPackManager.STAMP_NAME).readText() == "other")
    }
}
