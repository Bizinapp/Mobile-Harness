package com.jarves.mh.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import java.io.File
import java.security.SecureRandom

/**
 * ADB-free setup of the Artemis accessibility helper, once MH holds WRITE_SECURE_SETTINGS.
 * One-time grant from any computer (or wireless debugging):
 *     adb shell pm grant com.jarves.mh android.permission.WRITE_SECURE_SETTINGS
 * After that this class (1) enables the helper's accessibility service, (2) sends it a session token,
 * and (3) writes the token where the PRoot-side client reads it.
 */
object HelperProvisioner {
    const val HELPER_PKG = "com.artemis.helper"
    private const val SERVICE = "$HELPER_PKG/$HELPER_PKG.ArtemisAccessibilityService"

    sealed class Result {
        object Ready : Result()
        data class Needs(val what: String) : Result()
    }

    fun hasSecureSettings(ctx: Context) =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun helperInstalled(ctx: Context) = try { ctx.packageManager.getPackageInfo(HELPER_PKG, 0); true } catch (_: Exception) { false }

    fun provision(ctx: Context, tokenFileInPRoot: File): Result {
        if (!helperInstalled(ctx)) return Result.Needs("Install the Artemis helper APK.")
        if (!hasSecureSettings(ctx)) return Result.Needs("Run once: adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS")
        enableService(ctx)
        val token = newToken()
        ctx.sendBroadcast(Intent("$HELPER_PKG.SET_TOKEN").setComponent(ComponentName(HELPER_PKG, "$HELPER_PKG.TokenReceiver")).putExtra("token", token))
        tokenFileInPRoot.parentFile?.mkdirs()
        tokenFileInPRoot.writeText(token)
        return Result.Ready
    }

    private fun enableService(ctx: Context) {
        val cr = ctx.contentResolver
        val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        if (cur.split(':').none { it.equals(SERVICE, ignoreCase = true) }) {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (cur.isEmpty()) SERVICE else "$cur:$SERVICE")
        }
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    /** Resend on every session start: the helper keeps the token in memory only. */
    fun newToken(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}
