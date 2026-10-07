package com.jarves.mh.voice

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.json.JSONObject

/** A single file expected inside an installed voice pack. */
data class VoicePackFile(
    val path: String,
    val sha256: String,
    val sizeBytes: Long? = null,
) {
    init {
        require(VoicePackVerifier.isSafeRelativePath(path)) { "Unsafe voice-pack file path: $path" }
        require(SHA256.matches(sha256)) { "Invalid SHA-256 for $path" }
        require(sizeBytes == null || sizeBytes >= 0) { "Invalid size for $path" }
    }

    companion object {
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
    }
}

data class VoicePackManifest(
    val id: String,
    val provider: VoicePackProvider,
    val version: String,
    val sampleRateHz: Int,
    val files: List<VoicePackFile>,
    val entrypoint: String? = null,
) {
    init {
        require(ID.matches(id)) { "Invalid voice-pack id: $id" }
        require(version.isNotBlank() && version.length <= 128) { "Invalid voice-pack version" }
        require(sampleRateHz in 8_000..96_000) { "Invalid voice-pack sample rate" }
        require(files.isNotEmpty()) { "Voice-pack manifest has no files" }
        require(files.map { it.path }.distinct().size == files.size) { "Voice-pack manifest has duplicate files" }
        entrypoint?.let {
            require(VoicePackVerifier.isSafeRelativePath(it)) { "Unsafe voice-pack entrypoint: $it" }
            require(files.any { file -> file.path == it }) { "Entrypoint is not listed in files" }
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("provider", provider.id)
        put("version", version)
        put("sampleRateHz", sampleRateHz)
        entrypoint?.let { put("entrypoint", it) }
        put("files", org.json.JSONArray().apply {
            files.forEach { file ->
                put(JSONObject().apply {
                    put("path", file.path)
                    put("sha256", file.sha256.lowercase())
                    file.sizeBytes?.let { put("sizeBytes", it) }
                })
            }
        })
    }

    companion object {
        private val ID = Regex("[a-z0-9][a-z0-9._-]{0,95}")

        fun parse(json: JSONObject): VoicePackManifest {
            val filesJson = json.optJSONArray("files")
                ?: throw VoicePackException("Manifest field 'files' is required")
            if (filesJson.length() == 0) throw VoicePackException("Manifest contains no files")
            val files = (0 until filesJson.length()).map { index ->
                val item = filesJson.optJSONObject(index)
                    ?: throw VoicePackException("Manifest file $index is not an object")
                val path = item.optString("path").takeIf(String::isNotBlank)
                    ?: throw VoicePackException("Manifest file $index has no path")
                val hash = item.optString("sha256").takeIf(String::isNotBlank)
                    ?: throw VoicePackException("Manifest file $path has no sha256")
                val size = if (item.has("sizeBytes")) item.optLong("sizeBytes", -1L) else null
                if (size != null && size < 0) throw VoicePackException("Invalid size for $path")
                VoicePackFile(path, hash, size)
            }
            val providerId = json.optString("provider").takeIf(String::isNotBlank)
                ?: throw VoicePackException("Manifest field 'provider' is required")
            val provider = VoicePackProvider.fromId(providerId)
                ?: throw VoicePackException("Unsupported voice-pack provider: $providerId")
            val id = json.optString("id").takeIf(String::isNotBlank)
                ?: throw VoicePackException("Manifest field 'id' is required")
            val version = json.optString("version").takeIf(String::isNotBlank)
                ?: throw VoicePackException("Manifest field 'version' is required")
            val sampleRate = json.optInt("sampleRateHz", -1)
            val entrypoint = json.optString("entrypoint").takeIf(String::isNotBlank)
            return try {
                VoicePackManifest(id, provider, version, sampleRate, files, entrypoint)
            } catch (error: IllegalArgumentException) {
                throw VoicePackException(error.message ?: "Invalid voice-pack manifest", error)
            }
        }
    }
}

enum class VoicePackProvider(val id: String) {
    KITTEN("kitten"),
    PIPER("piper");

    companion object {
        fun fromId(id: String): VoicePackProvider? = entries.firstOrNull { it.id == id.lowercase() }
    }
}

class VoicePackException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class VerifiedVoicePack(
    val directory: File,
    val manifest: VoicePackManifest,
)

/** Pure file verifier; kept independent of Android Context for JVM testing. */
object VoicePackVerifier {
    private const val MANIFEST_NAME = "manifest.json"
    private const val MAX_MANIFEST_BYTES = 1L * 1024 * 1024
    private const val HASH_BUFFER_BYTES = 64 * 1024

    fun readManifest(directory: File): VoicePackManifest {
        val manifestFile = File(directory, MANIFEST_NAME)
        if (!manifestFile.isFile) throw VoicePackException("Missing $MANIFEST_NAME")
        if (manifestFile.length() > MAX_MANIFEST_BYTES) {
            throw VoicePackException("$MANIFEST_NAME is too large")
        }
        return try {
            VoicePackManifest.parse(JSONObject(manifestFile.readText(Charsets.UTF_8)))
        } catch (error: VoicePackException) {
            throw error
        } catch (error: Throwable) {
            throw VoicePackException("Invalid $MANIFEST_NAME: ${error.message ?: "invalid JSON"}", error)
        }
    }

    fun verify(directory: File): VerifiedVoicePack {
        if (!directory.isDirectory) throw VoicePackException("Voice-pack directory does not exist")
        val manifest = readManifest(directory)
        val canonicalRoot = directory.canonicalFile
        val listedPaths = manifest.files.map { it.path }.toSet()
        manifest.files.forEach { expected ->
            val file = safeResolve(canonicalRoot, expected.path)
            if (!file.isFile) throw VoicePackException("Missing voice-pack file: ${expected.path}")
            expected.sizeBytes?.let { size ->
                if (file.length() != size) {
                    throw VoicePackException("Size mismatch for ${expected.path}: expected $size, got ${file.length()}")
                }
            }
            val actual = sha256(file)
            if (!actual.equals(expected.sha256, ignoreCase = true)) {
                throw VoicePackException("SHA-256 mismatch for ${expected.path}")
            }
        }
        canonicalRoot.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relative = file.relativeTo(canonicalRoot).invariantSeparatorsPath
                if (relative != MANIFEST_NAME && relative !in listedPaths) {
                    throw VoicePackException("Unlisted voice-pack file: $relative")
                }
            }
        return VerifiedVoicePack(canonicalRoot, manifest)
    }

    fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.startsWith('\\')) return false
        if (path.contains('\u0000')) return false
        val normalized = path.replace('\\', '/')
        if (normalized.split('/').any { it.isEmpty() || it == "." || it == ".." }) return false
        return !Regex("^[A-Za-z]:").containsMatchIn(normalized)
    }

    internal fun safeResolve(root: File, relativePath: String): File {
        if (!isSafeRelativePath(relativePath)) throw VoicePackException("Unsafe voice-pack path: $relativePath")
        val rootPath = root.canonicalFile.toPath()
        val candidate = File(root, relativePath).canonicalFile
        if (!candidate.toPath().startsWith(rootPath)) {
            throw VoicePackException("Voice-pack path escapes its directory: $relativePath")
        }
        return candidate
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

/**
 * Stores verified voice packs below app-private storage and activates them
 * atomically. ZIP archives must contain a top-level manifest.json and paths
 * listed by that manifest; unlisted files are rejected.
 */
class VoicePackStore(private val context: Context) {
    private val root: File get() = File(context.filesDir, "voice-packs")
    private val stagingRoot: File get() = File(context.cacheDir, "voice-packs-staging")

    fun installed(id: String): VerifiedVoicePack? {
        require(PACK_ID.matches(id)) { "Invalid voice-pack id: $id" }
        val directory = File(root, id)
        if (!directory.isDirectory) return null
        return runCatching { VoicePackVerifier.verify(directory) }
            .getOrElse { throw VoicePackException("Installed voice pack '$id' is invalid: ${it.message}", it) }
    }

    fun listInstalled(): List<VerifiedVoicePack> {
        return root.listFiles { file -> file.isDirectory }
            .orEmpty()
            .mapNotNull { directory -> runCatching { VoicePackVerifier.verify(directory) }.getOrNull() }
            .sortedBy { it.manifest.id }
    }

    /** Installs a ZIP without trusting its filenames, symlinks, or archive layout. */
    fun installZip(input: InputStream, expectedId: String? = null): VerifiedVoicePack {
        val stage = File(stagingRoot, UUID.randomUUID().toString())
        stage.mkdirs()
        try {
            extractZip(input, stage)
            val verified = VoicePackVerifier.verify(stage)
            if (expectedId != null && verified.manifest.id != expectedId) {
                throw VoicePackException("Expected voice pack '$expectedId', got '${verified.manifest.id}'")
            }
            val destination = File(root, verified.manifest.id)
            root.mkdirs()
            val replacement = File(root, ".${verified.manifest.id}.${UUID.randomUUID()}.new")
            if (!stage.renameTo(replacement)) throw VoicePackException("Could not stage voice pack")
            val backup = File(root, ".${verified.manifest.id}.${UUID.randomUUID()}.old")
            val hadPrevious = destination.exists()
            if (hadPrevious && !destination.renameTo(backup)) {
                replacement.deleteRecursively()
                throw VoicePackException("Could not stage replacement for existing voice pack")
            }
            if (!replacement.renameTo(destination)) {
                if (hadPrevious) backup.renameTo(destination)
                replacement.deleteRecursively()
                throw VoicePackException("Could not activate voice pack")
            }
            if (hadPrevious) backup.deleteRecursively()
            return VoicePackVerifier.verify(destination)
        } finally {
            stage.deleteRecursively()
        }
    }

    fun installZip(file: File, expectedId: String? = null): VerifiedVoicePack =
        FileInputStream(file).use { installZip(BufferedInputStream(it), expectedId) }

    fun delete(id: String): Boolean {
        if (!PACK_ID.matches(id)) {
            throw VoicePackException("Invalid voice-pack id: $id")
        }
        return File(root, id).deleteRecursively()
    }

    private fun extractZip(input: InputStream, destination: File) {
        val seen = HashSet<String>()
        var totalBytes = 0L
        var fileCount = 0
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                if (!VoicePackVerifier.isSafeRelativePath(name)) {
                    throw VoicePackException("Unsafe ZIP entry: ${entry.name}")
                }
                if (!seen.add(name)) throw VoicePackException("Duplicate ZIP entry: $name")
                if (entry.isDirectory) {
                    VoicePackVerifier.safeResolve(destination, name).mkdirs()
                    continue
                }
                if (++fileCount > MAX_FILES) throw VoicePackException("Voice pack contains too many files")
                val output = VoicePackVerifier.safeResolve(destination, name)
                output.parentFile?.mkdirs()
                output.outputStream().use { sink ->
                    totalBytes = copyBounded(zip, sink, totalBytes)
                }
                zip.closeEntry()
            }
        }
        if (!seen.contains("manifest.json")) throw VoicePackException("ZIP is missing manifest.json")
    }

    private fun copyBounded(input: InputStream, output: OutputStream, current: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = current
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_UNCOMPRESSED_BYTES) throw VoicePackException("Voice pack is too large")
            output.write(buffer, 0, count)
        }
        return total
    }

    companion object {
        private val PACK_ID = Regex("[a-z0-9][a-z0-9._-]{0,95}")
        private const val MAX_FILES = 256
        private const val MAX_UNCOMPRESSED_BYTES = 512L * 1024 * 1024
    }
}
