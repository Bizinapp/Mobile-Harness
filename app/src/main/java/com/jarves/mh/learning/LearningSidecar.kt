package com.jarves.mh.learning

import android.content.Context
import com.jarves.mh.runtime.RuntimeInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Calls the Python learning brain (vhbrain.sidecar) inside PRoot. Requests and answers are plain JSON files in
 * the guest's /root/vh/io, which the host can read directly because the rootfs is an ordinary app directory.
 * Every call is best-effort: on any failure the agent simply runs without memory (learning must never block a turn).
 */
class LearningSidecar(private val context: Context, private val installer: RuntimeInstaller) {
    data class Prepared(val addendum: String, val skill: String?, val needsConfirmation: Boolean)

    private fun guestRoot(): File = File(installer.installedRuntime().rootfs, "root/vh")
    val addendumFile get() = File(guestRoot(), "state/addendum.md")

    private suspend fun run(cmd: String, request: JSONObject?, timeoutMs: Long = 25_000): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val io = File(guestRoot(), "io").apply { mkdirs() }
            val id = System.nanoTime()
            val req = File(io, "req-$id.json"); val res = File(io, "res-$id.json")
            request?.let { req.writeText(it.toString()) }
            val rt = installer.installedRuntime()
            val args = buildString {
                append("/root/vh/bin/vh $cmd --out /root/vh/io/res-$id.json")
                if (request != null) append(" --in /root/vh/io/req-$id.json")
            }
            val proc = installer.process(rt.proot, rt.rootfs, File(rt.rootfs, "root"), emptyMap(),
                listOf("/usr/bin/env", "bash", "-lc", args))
            val done = withTimeoutOrNull(timeoutMs) { proc.waitFor(); true } ?: false
            if (!done) proc.destroyForcibly()
            val out = if (res.exists()) JSONObject(res.readText()) else null
            req.delete(); res.delete()
            out?.takeIf { !it.has("error") }
        } catch (_: Exception) { null }
    }

    /** Before a run: get memory + matching skill. Also stages the addendum for the Claude bridge (see patch). */
    suspend fun prepare(utterance: String): Prepared? {
        val r = run("prepare", JSONObject().put("utterance", utterance)) ?: return null
        val p = Prepared(r.optString("addendum"), r.optString("skill").ifBlank { null }, r.optBoolean("needs_confirmation"))
        withContext(Dispatchers.IO) { addendumFile.apply { parentFile?.mkdirs() }.writeText(p.addendum) }
        return p
    }

    /** After a run: record the task and let the brain learn from it. */
    suspend fun finish(utterance: String, reply: String, steps: List<Pair<String, String>>, success: Boolean, seconds: Double, skill: String?) {
        val arr = JSONArray(); steps.forEach { (t, d) -> arr.put(JSONObject().put("tool", t).put("args", d).put("ok", true)) }
        run("finish", JSONObject().put("utterance", utterance).put("reply", reply).put("steps", arr)
            .put("success", success).put("seconds", seconds).apply { skill?.let { put("skill", it) } }, timeoutMs = 90_000)
    }

    suspend fun correct(text: String) { run("correct", JSONObject().put("text", text), timeoutMs = 90_000) }
    suspend fun curate(): String? = run("curate", null, timeoutMs = 120_000)?.optString("summary")

    /** Hand the guest side what it needs: helper token path is written by HelperProvisioner into the same folder. */
    fun helperTokenFile(): File = File(guestRoot(), "helper.token")
}
