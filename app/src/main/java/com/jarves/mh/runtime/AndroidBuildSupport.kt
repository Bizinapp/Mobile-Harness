package com.jarves.mh.runtime

import org.json.JSONObject
import java.io.File

enum class AndroidBuildPhase { IDLE, PREPARING, BUILDING, CANCELLING, SUCCEEDED, FAILED, CANCELLED }

data class AndroidBuildRecord(
    val phase: AndroidBuildPhase = AndroidBuildPhase.IDLE,
    val message: String? = null,
    val log: String = "",
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val apkPath: String? = null,
    val apkSizeBytes: Long? = null,
)

internal fun androidGradleCommand(root: File, task: String): String {
    val launcher = File(root, "gradlew").takeIf(File::isFile)?.let { "bash ./gradlew" } ?: "gradle"
    return "$launcher -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 " +
        "--init-script /root/.gradle/init.d/pocketdev-android.gradle " +
        "-Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 " +
        // Gradle daemons can remain attached to deleted PRoot paths after an
        // interrupted build. A bounded, no-daemon invocation is more reliable
        // on Android and avoids leaving a second busy daemon behind.
        "$task --console=plain --stacktrace"
}

internal fun findAndroidProjectRoot(workspace: File): File? {
    val settingsNames = setOf("settings.gradle", "settings.gradle.kts", "settings.gradle.dcl")
    return workspace.walkTopDown().maxDepth(4)
        .filter { it.isFile && it.name in settingsNames }
        .mapNotNull(File::getParentFile)
        .sortedBy { it.absolutePath.length }
        .firstOrNull { root ->
            root.walkTopDown().maxDepth(5)
                .any { it.isFile && it.invariantSeparatorsPath.endsWith("src/main/AndroidManifest.xml") }
        }
}

internal fun findDebugApk(projectRoot: File): File? {
    val metadata = projectRoot.walkTopDown().maxDepth(8)
        .filter { it.isFile && it.name == "output-metadata.json" && "/outputs/apk/" in it.invariantSeparatorsPath }
        .maxByOrNull(File::lastModified)
    if (metadata != null) {
        runCatching {
            val json = JSONObject(metadata.readText())
            val elements = json.optJSONArray("elements") ?: return@runCatching null
            (0 until elements.length()).asSequence()
                .mapNotNull { elements.optJSONObject(it)?.optString("outputFile") }
                .map { File(metadata.parentFile, it) }
                .firstOrNull { it.isFile && it.length() > 0L }
        }.getOrNull()?.let { return it }
    }
    return projectRoot.walkTopDown().maxDepth(10)
        .filter { it.isFile && it.extension.equals("apk", true) && "/outputs/apk/debug/" in it.invariantSeparatorsPath }
        .maxByOrNull(File::lastModified)
}

/** Returns the last debug APK only when every build input is older than it. */
internal fun findReusableDebugApk(projectRoot: File): File? {
    val apk = findDebugApk(projectRoot) ?: return null
    val ignoredDirectories = setOf("build", ".gradle", ".idea", ".git")
    val buildInputExtensions = setOf(
        "kt", "java", "xml", "gradle", "kts", "properties", "toml", "pro", "json",
    )
    val changedAfterApk = projectRoot.walkTopDown()
        .onEnter { directory -> directory == projectRoot || directory.name !in ignoredDirectories }
        .filter { file ->
            file.isFile && (
                file.extension.lowercase() in buildInputExtensions ||
                    file.name in setOf("gradlew", "gradlew.bat")
                )
        }
        .any { it.lastModified() > apk.lastModified() }
    return apk.takeUnless { changedAfterApk }
}

internal fun diagnoseAndroidBuildFailure(output: String, exitCode: Int): String {
    val text = output.lowercase()
    return when {
        "no space left on device" in text -> "The device does not have enough free storage for this build."
        "outofmemoryerror" in text || "java heap space" in text -> "Gradle ran out of memory. Close other apps and retry."
        "could not resolve" in text || "could not get resource" in text -> "A project dependency is unavailable. Connect to the internet or use an offline-cached version."
        "manifest merger failed" in text -> "Android manifest merge failed. Open the build log for the conflicting declaration."
        "aapt2" in text || "android resource linking failed" in text -> "Android resources could not be compiled. Open the build log for the file and line."
        "compilation error" in text || "compilation failed" in text || "unresolved reference" in text -> "Kotlin or Java compilation failed. Open the build log for source errors."
        "minimum supported gradle version" in text || "maximum supported gradle" in text -> "This project's Gradle and Android plugin versions are incompatible."
        else -> "Gradle build failed (exit code $exitCode)."
    }
}
