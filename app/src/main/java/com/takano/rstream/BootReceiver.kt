package com.takano.rstream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** 开机后把内置中继拉起来（不自动开始推流），这样不用 adb 也能随时一键开播。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, StreamService::class.java).setAction(StreamService.ACTION_RELAY_ONLY),
            )
        }
    }
}
