package com.jarves.mh.phone

import android.content.Context
import java.io.File

/** One idempotent call that gets the phone side ready: starts the intent bridge, enables the helper, shares tokens with PRoot. */
object PhoneSetup {
    private var bridge: IntentBridgeServer? = null
    private var bridgeToken: String? = null

    fun start(ctx: Context, rootfs: File): HelperProvisioner.Result {
        val vh = File(rootfs, "root/vh").apply { mkdirs() }
        if (bridge == null) {
            val t = HelperProvisioner.newToken()
            bridgeToken = t
            bridge = IntentBridgeServer(ctx.applicationContext, t).also { it.start() }
        }
        File(vh, "bridge.token").writeText(bridgeToken!!)          // read by artemis_lite.IntentBridge inside PRoot
        return HelperProvisioner.provision(ctx, File(vh, "helper.token"))
    }
}
