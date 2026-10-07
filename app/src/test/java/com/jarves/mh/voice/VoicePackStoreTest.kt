package com.jarves.mh.voice

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePackStoreTest {
    @Test
    fun verifiesListedFilesAndHashes() {
        val root = createTempDir(prefix = "voice-pack-")
        try {
            val model = File(root, "model.onnx").apply { writeText("model") }
            File(root, "manifest.json").writeText(manifest("test-pack", model))
            val verified = VoicePackVerifier.verify(root)
            assertEquals("test-pack", verified.manifest.id)
            assertEquals(VoicePackProvider.PIPER, verified.manifest.provider)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsChangedFile() {
        val root = createTempDir(prefix = "voice-pack-")
        try {
            val model = File(root, "model.onnx").apply { writeText("model") }
            File(root, "manifest.json").writeText(manifest("test-pack", model))
            model.writeText("tampered")
            assertThrows(VoicePackException::class.java) { VoicePackVerifier.verify(root) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnlistedFilesAndTraversalPaths() {
        val root = createTempDir(prefix = "voice-pack-")
        try {
            val model = File(root, "model.onnx").apply { writeText("model") }
            File(root, "extra.bin").writeText("not signed")
            File(root, "manifest.json").writeText(manifest("test-pack", model))
            assertThrows(VoicePackException::class.java) { VoicePackVerifier.verify(root) }
            assertTrue(!VoicePackVerifier.isSafeRelativePath("../escape"))
            assertTrue(!VoicePackVerifier.isSafeRelativePath("/absolute"))
            assertTrue(!VoicePackVerifier.isSafeRelativePath("C:/absolute"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun manifest(id: String, file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        return JSONObject()
            .put("id", id)
            .put("provider", "piper")
            .put("version", "1")
            .put("sampleRateHz", 22_050)
            .put("files", JSONArray().put(
                JSONObject()
                    .put("path", file.name)
                    .put("sha256", hash)
                    .put("sizeBytes", file.length()),
            ))
            .toString()
    }
}
