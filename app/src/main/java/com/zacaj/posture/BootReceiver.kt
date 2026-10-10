package com.zacaj.posture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restarts tracking after a reboot or an app update, if it was running before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        WidgetProvider.refresh(ctx)
        if (!Settings(ctx).trackingEnabled) return
        Log.i(PostureService.TAG, "auto-start on ${intent.action}")
        runCatching { PostureService.send(ctx, PostureService.ACTION_START) }
            .onFailure { Log.w(PostureService.TAG, "auto-start failed", it) }
    }
}
