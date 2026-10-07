package com.jarves.mh.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Loopback HTTP bridge so code running inside PRoot can ask the Android host to start activities.
 * (Without ADB, only a real app can launch apps; the accessibility service cannot.)
 * Binds 127.0.0.1 only and requires X-VH-Token. Endpoints (POST, JSON): /launch {app}, /open_url {url}, /apps.
 */
class IntentBridgeServer(private val ctx: Context, private val token: String, private val port: Int = 18889) {
    @Volatile private var running = false
    private var server: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        server = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        thread(name = "vh-intent-bridge", isDaemon = true) {
            while (running) { try { val s = server!!.accept(); thread { serve(s) } } catch (_: Exception) { if (!running) break } }
        }
    }
    fun stop() { running = false; server?.close() }

    private fun serve(s: Socket) = s.use {
        val input = it.getInputStream().bufferedReader()
        val request = input.readLine() ?: return
        val path = request.split(" ").getOrNull(1) ?: "/"
        var len = 0; var tok = ""
        while (true) {
            val h = input.readLine() ?: break
            if (h.isEmpty()) break
            val (k, v) = h.split(":", limit = 2).let { p -> p[0].trim().lowercase() to p.getOrElse(1) { "" }.trim() }
            if (k == "content-length") len = v.toIntOrNull() ?: 0
            if (k == "x-vh-token") tok = v
        }
        val body = if (len in 1..65536) CharArray(len).also { b -> input.read(b, 0, len) }.concatToString() else "{}"
        val out = if (!MessageDigest.isEqual(tok.toByteArray(), token.toByteArray())) fail("bad token") else handle(path, JSONObject(body))
        val bytes = out.toString().toByteArray()
        it.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush() }
    }

    private fun fail(msg: String) = JSONObject().put("ok", false).put("error", msg)

    private fun handle(path: String, j: JSONObject): JSONObject = try {
        when (path) {
            "/launch" -> launch(j.optString("app"))
            "/open_url" -> {
                val uri = Uri.parse(j.optString("url"))
                if (uri.scheme != "http" && uri.scheme != "https") fail("only http(s)")
                else { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); JSONObject().put("ok", true) }
            }
            "/apps" -> JSONObject().put("ok", true).put("apps", JSONArray(launchables().map { (n, p) -> JSONObject().put("name", n).put("package", p) }))
            else -> fail("unknown endpoint")
        }
    } catch (e: Exception) { fail(e.message ?: "error") }

    private fun launchables(): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(q, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
    }

    private fun launch(app: String): JSONObject {
        val pm = ctx.packageManager
        val pkg = launchables().firstOrNull { it.second.equals(app, true) }?.second
            ?: launchables().firstOrNull { it.first.equals(app, true) }?.second
            ?: launchables().firstOrNull { it.first.contains(app, true) }?.second
            ?: return fail("no installed app matches '$app'")
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return fail("cannot launch $pkg")
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return JSONObject().put("ok", true).put("package", pkg)
    }
}
