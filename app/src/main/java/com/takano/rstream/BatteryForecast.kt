package com.takano.rstream

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 电池剩余时间预测（HUD 第 2 行电量后面显示「≈52分」这种）。
 *
 * 主路（实测）：对「时间 vs 电量%」样本做最小二乘拟合，得到 %/分钟放电速率，
 *   剩余分钟 = 当前电量 / 速率。需要约 1.5~3 分钟样本收敛，之后随流自更新。
 * 辅路（瞬时电流）：BATTERY_PROPERTY_CURRENT_NOW(µA，放电为负) +
 *   BATTERY_PROPERTY_CHARGE_COUNTER(µAh) 反推容量，暖机期先给个粗略值；
 *   机芯不实现这两项（返回 0/正数）时自动忽略。
 * 记忆：每个码率档位记下最后一次实测速率，切档后立刻能出估计，不必重新暖机。
 */
object BatteryForecast {
    private const val TAG = "RstreamBatt"

    private const val MAX_SAMPLES = 400      // HUD 每 5s 喂一次 → 约 33 分钟窗口
    private const val MIN_SPAN_MS = 90_000L  // 至少 90 秒样本才认
    private const val MAX_RATE = 25.0        // %/分钟，超过当异常丢弃
    private const val MIN_RATE = 0.02        // %/分钟，低于当没在放电

    private data class Sample(val t: Long, val level: Float)

    private val samples = ArrayDeque<Sample>()
    private val rateByLabel = HashMap<String, Double>()

    /** 当前电量 0..100，-1 = 未知。 */
    @Volatile var levelPct = -1
        private set

    /** 正在充电。 */
    @Volatile var charging = false
        private set

    /** 实测放电速率 %/分钟（0 = 还没测出来）。 */
    @Volatile var ratePctPerMin = 0.0
        private set

    /** 瞬时电流法算出的速率 %/分钟（0 = 不可用）。 */
    @Volatile var instantRatePctPerMin = 0.0
        private set

    /** 预测剩余分钟，<0 = 还没有结论。 */
    @Volatile var remainingMin = -1.0
        private set

    /** 这次估计的来源：实测 / 电流 / 档位记忆。 */
    @Volatile var source = ""
        private set

    private var lastRunning = false
    private var lastLogAt = 0L

    /** HUD 每 5 秒调一次。 */
    fun sample(context: Context, level: Int, isCharging: Boolean) {
        levelPct = level
        charging = isCharging
        val now = SystemClock.elapsedRealtime()

        // 开流/停流 → 功耗档位变了，旧样本作废重新测
        if (StreamStats.running != lastRunning) {
            lastRunning = StreamStats.running
            samples.clear()
        }
        if (isCharging) {
            samples.clear()
            remainingMin = -1.0
            source = ""
            return
        }
        if (level < 0) return

        val last = samples.lastOrNull()
        // 电量回升（中途插过电）→ 前面的样本不能再用
        if (last != null && level > last.level) samples.clear()

        val tail = samples.lastOrNull()
        if (tail == null || tail.level != level.toFloat() || now - tail.t >= 60_000L) {
            samples.addLast(Sample(now, level.toFloat()))
        }
        while (samples.size > MAX_SAMPLES) samples.removeFirst()

        val measured = measureRate()
        val instant = measureInstant(context)
        decide(measured, instant, now)
    }

    /** 最小二乘拟合（时间秒 → 电量%）的斜率，取负号得放电速率 %/分钟。 */
    private fun measureRate(): Double {
        if (samples.size < 2) return 0.0
        val t0 = samples.first().t
        val spanMs = samples.last().t - t0
        if (spanMs < MIN_SPAN_MS) return 0.0

        var n = 0.0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (s in samples) {
            val x = (s.t - t0) / 1000.0
            val y = s.level
            n += 1.0
            sx += x
            sy += y
            sxx += x * x
            sxy += x * y
        }
        val den = n * sxx - sx * sx
        if (den <= 0.0) return 0.0
        val slopePerSec = (n * sxy - sx * sy) / den
        val rate = -slopePerSec * 60.0
        if (rate < MIN_RATE || rate > MAX_RATE) return 0.0
        return rate
    }

    /** 瞬时电流法：拿不到合理值就返回 0。 */
    private fun measureInstant(context: Context): Double {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return 0.0
        var nowUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val ccUah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (nowUa == 0 || ccUah <= 0) return 0.0
        // 有些机芯 CURRENT_NOW 直接给 mA：小到不可能是 µA 时按 mA 处理
        if (abs(nowUa) < 10_000) nowUa *= 1000
        // 充电时电流为正，不算
        if (nowUa >= 0) return 0.0
        val level = levelPct
        if (level <= 5) return 0.0
        val capacityUah = ccUah * 100.0 / level
        if (capacityUah <= 0.0) return 0.0
        val ratePerMin = abs(nowUa) * 100.0 / capacityUah / 60.0
        if (ratePerMin < MIN_RATE || ratePerMin > MAX_RATE) return 0.0
        return ratePerMin
    }

    private fun decide(measured: Double, instant: Double, now: Long) {
        instantRatePctPerMin = instant
        ratePctPerMin = measured

        val label = StreamStats.bitrateLabel
        if (measured > 0.0) {
            rateByLabel[label] = measured
            remainingMin = levelPct / measured
            source = "实测"
        } else {
            val remembered = rateByLabel[label] ?: 0.0
            // 实测还没收敛：优先用本档位的历史速率，其次用瞬时电流
            val rate = if (remembered > 0.0 && instant <= 0.0) remembered
            else if (instant > 0.0) instant
            else remembered
            if (rate > 0.0) {
                remainingMin = levelPct / rate
                source = if (rate == remembered) "档位记忆" else "电流"
            } else {
                remainingMin = -1.0
                source = ""
            }
        }

        if (now - lastLogAt >= 60_000L) {
            lastLogAt = now
            Log.i(
                TAG,
                "剩余预测: %.3f 分钟（来源=%s）| 实测=%.3f%%/分 电流=%.3f%%/分 电量=%d%% 档位=%s 样本=%d"
                    .format(remainingMin, source.ifEmpty { "-" }, measured, instant, levelPct, label, samples.size),
            )
        }
    }
}
