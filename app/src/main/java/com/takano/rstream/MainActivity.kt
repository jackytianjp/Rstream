package com.takano.rstream

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat

/**
 * Rstream HUD 控制页：屏幕上只有最下面两行的符号（状态圆点/方块、延迟天线格、链路环），没有文字。
 *
 * 操作：
 *   触控板单击 = 开始/停止推流；双击 = 退出；长按 = 循环码率档位
 * adb 驱动：
 *   am start -n com.takano.rstream/.MainActivity --ez start true [--es host 127.0.0.1] [--ei port 8899]
 *            [--ei rot 0|90|180|270] [--ei fps 25] [--es codec h264] [--ei bitrate 2500]
 */
class MainActivity : Activity() {

    companion object {
        const val ACTION_SWIPE_FORWARD = "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"
        const val ACTION_SWIPE_BACK = "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"
    }

    private lateinit var hud: StatusHudView
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            hud.tick()
            handler.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        hud = StatusHudView(this)
        root.addView(
            hud,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM,
            ),
        )
        setContentView(root)

        // 眼镜 HUD 静止 5–10 秒就会自动熄屏（系统省电）。本页常亮，推流时画面不灭。
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
        // 调试：--ei focus 0|1|2 / --ei drop N 直接摆出某个 HUD 状态（触控板手势没法用 adb 模拟）
        intent?.let { i ->
            if (i.hasExtra("focus")) StreamStats.hudFocus = i.getIntExtra("focus", 0)
            if (i.hasExtra("drop")) StreamStats.hudDropIndex = i.getIntExtra("drop", 0)
        }
        if (intent?.getBooleanExtra("start", false) == true) {
            startStreaming(intent)
        }
        handler.post(ticker)
    }

    // 触控板双指前后滑 = 有序广播；用它切换 HUD 焦点（开关 ↔ 码率），展开后在档位间移动
    private val swipeFilter = IntentFilter().apply {
        addAction(ACTION_SWIPE_FORWARD)
        addAction(ACTION_SWIPE_BACK)
        priority = IntentFilter.SYSTEM_HIGH_PRIORITY
    }
    private val swipeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                ACTION_SWIPE_FORWARD -> {
                    moveFocus(1)
                    runCatching { abortBroadcast() }
                }
                ACTION_SWIPE_BACK -> {
                    moveFocus(-1)
                    runCatching { abortBroadcast() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        runCatching { registerReceiver(swipeReceiver, swipeFilter) }
    }

    override fun onPause() {
        runCatching { unregisterReceiver(swipeReceiver) }
        super.onPause()
    }

    private fun moveFocus(dir: Int) {
        if (StreamStats.hudFocus == 2) {
            val n = StreamConfig.LEVEL_LABELS.size
            StreamStats.hudDropIndex = ((StreamStats.hudDropIndex + dir) % n + n) % n
        } else {
            StreamStats.hudFocus = if (StreamStats.hudFocus == 0) 1 else 0
        }
        hud.invalidate()
    }

    /** 单击：开关 → 开始/停止；码率 → 展开下拉；下拉展开时 → 选中该档位并回到开关。 */
    private fun onEnter() {
        when (StreamStats.hudFocus) {
            0 -> toggle()
            1 -> {
                StreamStats.hudFocus = 2
                StreamStats.hudDropIndex = StreamStats.bitrateLevelSel
                hud.invalidate()
            }
            else -> {
                startService(
                    Intent(this, StreamService::class.java)
                        .setAction(StreamService.ACTION_SET_LEVEL)
                        .putExtra("level", StreamStats.hudDropIndex)
                )
                StreamStats.hudFocus = 0
                hud.invalidate()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("start", false) == true) {
            startStreaming(intent)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 息屏时的按键只用于唤醒，不触发动作（否则点一下唤醒会顺带把推流停掉）
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return super.dispatchKeyEvent(event)

        // 触控板单指轻点 = KEYCODE_ENTER；双击 = KEYCODE_BACK；长按 = KEYCODE_PROG_BLUE
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER -> {
                    onEnter()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    exitApp()
                    return true
                }
                // 长按 = 循环码率档位（自动 / 6.0M / 2.5M / 800k / 256k）
                KeyEvent.KEYCODE_PROG_BLUE -> {
                    startService(
                        Intent(this, StreamService::class.java).setAction(StreamService.ACTION_CYCLE)
                    )
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toggle() {
        if (StreamStats.running) stopStreaming() else startStreaming(null)
    }

    /** 退出：停服务（释放相机 + 去掉通知）并关闭本页。双击触控板 / 长按即此。 */
    private fun exitApp() {
        stopService(Intent(this, StreamService::class.java))
        StreamStats.running = false
        StreamStats.cameraState = "已退出"
        finishAffinity()
    }

    private fun startStreaming(src: Intent?) {
        val intent = Intent(this, StreamService::class.java).setAction(StreamService.ACTION_START)
        src?.extras?.let { intent.putExtras(it) }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopStreaming() {
        val intent = Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP)
        startService(intent)
    }
}
