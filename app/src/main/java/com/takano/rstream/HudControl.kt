package com.takano.rstream

/**
 * HUD 焦点状态机（Activity 与 Application 广播接收器共用）。
 *
 * 焦点：0 = 开关（左下）  1 = 码率（右上）  2 = 看门狗（左上）
 *      3 = 码率菜单展开中（此时滑动在 5 个档位间移动）
 */
object HudControl {

    const val FOCUS_SWITCH = 0
    const val FOCUS_BITRATE = 1
    const val FOCUS_WATCHDOG = 2
    const val FOCUS_MENU = 3
    const val FOCUS_COUNT = 3

    /**
     * 防抖：一次滑动动作系统会连发好几个广播，不加冷却就会"一滑跳好几格"。
     * 实测调成 380ms 比较跟手又不飘；要更钝/更灵改这个常数即可。
     */
    private const val COOLDOWN_MS = 380L
    private var lastMoveAt = 0L
    private var lastEventAt = 0L

    /** 滑动：菜单展开时移动档位高亮，否则在三个控件之间循环。 */
    fun moveFocus(dir: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        val gap = if (lastEventAt == 0L) -1L else now - lastEventAt
        lastEventAt = now
        if (now - lastMoveAt < COOLDOWN_MS) {
            android.util.Log.i("RstreamApp", "滑动防抖忽略（间隔 ${gap}ms，冷却 ${COOLDOWN_MS}ms）")
            return
        }
        lastMoveAt = now
        android.util.Log.i("RstreamApp", "焦点移动 dir=$dir（距上次事件 ${gap}ms）")

        if (StreamStats.hudFocus == FOCUS_MENU) {
            val n = StreamConfig.LEVEL_LABELS.size
            StreamStats.hudDropIndex = ((StreamStats.hudDropIndex + dir) % n + n) % n
        } else {
            StreamStats.hudFocus = ((StreamStats.hudFocus + dir) % FOCUS_COUNT + FOCUS_COUNT) % FOCUS_COUNT
        }
    }
}
