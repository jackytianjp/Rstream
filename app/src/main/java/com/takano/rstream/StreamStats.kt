package com.takano.rstream

/** 推流状态（同进程内 MainActivity 直接读，供 HUD 显示与 adb 日志核对）。 */
object StreamStats {
    @Volatile var running = false
    @Volatile var url = ""
    @Volatile var resolution = ""
    @Volatile var cameraState = "未启动"
    @Volatile var rotation = 0
    @Volatile var recoveries = 0
    @Volatile var sent = 0
    @Volatile var dropped = 0
    @Volatile var skipped = 0
    @Volatile var errors = 0
    @Volatile var bytesSent = 0L
    @Volatile var lastRttMs = 0L
    @Volatile var lastError = ""
    @Volatile var lastFrameAt = 0L
    /** HUD 上显示的码率档位（如 自动(6.0M) / 256k）。 */
    @Volatile var bitrateLabel = "自动"
    /** HUD 焦点：0 = 开关，1 = 码率（未展开），2 = 码率下拉展开。 */
    @Volatile var hudFocus = 0
    /** 下拉菜单里高亮到第几档。 */
    @Volatile var hudDropIndex = 0
    /** 当前码率档位（0..4，与 StreamConfig.LEVEL_* 对应）。 */
    @Volatile var bitrateLevelSel = 0

    private val window = ArrayDeque<Long>()

    @Synchronized
    fun onSent(size: Int, rttMs: Long) {
        sent += 1
        bytesSent += size
        lastRttMs = rttMs
        lastFrameAt = System.currentTimeMillis()
        window.addLast(lastFrameAt)
        while (window.size > 30) window.removeFirst()
    }

    @Synchronized
    fun fps(): Double {
        if (window.size < 2) return 0.0
        val span = (window.last() - window.first()) / 1000.0
        if (span <= 0) return 0.0
        return Math.round((window.size - 1) / span * 10.0) / 10.0
    }

    @Synchronized
    fun reset() {
        sent = 0
        dropped = 0
        skipped = 0
        errors = 0
        recoveries = 0
        bytesSent = 0
        lastRttMs = 0
        lastError = ""
        window.clear()
    }

    fun resetKeepRunning() {
        reset()
    }
}
