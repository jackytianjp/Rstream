package com.takano.rstream

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 触控板双指前后滑 = 系统有序广播。放在 Application 里注册（跟 Rokid 官方 Sample 一样），
 * 这样不管 Activity 在不在前台都能收到；放在 Activity 里注册时实测收不到。
 */
class RstreamApp : Application() {

    companion object {
        const val TAG = "RstreamApp"
        const val ACTION_SWIPE_FORWARD = "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"
        const val ACTION_SWIPE_BACK = "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"
    }

    private val swipeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.i(TAG, "手势广播: $action")
            when (action) {
                ACTION_SWIPE_FORWARD -> {
                    HudControl.moveFocus(1)
                    runCatching { abortBroadcast() }
                }
                ACTION_SWIPE_BACK -> {
                    HudControl.moveFocus(-1)
                    runCatching { abortBroadcast() }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            addAction(ACTION_SWIPE_FORWARD)
            addAction(ACTION_SWIPE_BACK)
        }
        runCatching {
            ContextCompat.registerReceiver(this, swipeReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            Log.i(TAG, "已注册双指滑动广播接收器")
        }.onFailure { Log.e(TAG, "注册失败: ${it.message}") }
    }
}
