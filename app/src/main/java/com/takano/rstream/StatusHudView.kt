package com.takano.rstream

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

/**
 * HUD：屏幕最下面两行「符号 + 文字」，黑底不发光，戴在眼前不挡视线。
 *
 *   第 1 行： ● 直播中 / ■ 停止    ▂▄▆█ 延迟 好/差      （右侧）单击开关
 *   第 2 行： ⛓ 链路 已连接 / 断开                        （右侧）双击退出
 */
class StatusHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val live = Color.parseColor("#7CF6C0")
    private val warn = Color.parseColor("#FFC24D")
    private val bad = Color.parseColor("#FF4D5E")
    private val idle = Color.parseColor("#5A6069")
    private val hint = Color.parseColor("#7E868F")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = Typeface.MONOSPACE
        textSize = dp(12f)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private var blinkOn = true

    // 电量 / 充电
    private var battLevel = -1
    private var battCharging = false
    private var battAt = 0L

    private fun refreshBattery() {
        val now = SystemClock.elapsedRealtime()
        if (now - battAt < 5000) return
        battAt = now
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        battLevel = if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        battCharging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        // 喂给剩余时间预测（同一个 5 秒节拍）
        BatteryForecast.sample(context, battLevel, battCharging)
    }

    /** 电池符号：外壳 + 电量填充（绿/黄/红）+ 充电闪电。返回占用宽度。 */
    private fun drawBattery(canvas: Canvas, x0: Float, cy: Float): Float {
        refreshBattery()
        val w = dp(30f)
        val h = dp(14f)
        val top = cy - h / 2f
        stroke.color = if (battLevel in 0..20) bad else idle
        stroke.strokeWidth = dp(1.6f)
        canvas.drawRoundRect(RectF(x0, top, x0 + w, top + h), dp(3f), dp(3f), stroke)
        // 正极小凸起
        paint.color = if (battLevel in 0..20) bad else idle
        canvas.drawRect(x0 + w, cy - dp(3f), x0 + w + dp(2.5f), cy + dp(3f), paint)
        // 电量填充
        val fillW = (w - dp(4f)) * ((battLevel.coerceAtLeast(0)) / 100f)
        if (fillW > 0) {
            paint.color = when {
                battLevel <= 20 -> bad
                battLevel <= 40 -> warn
                else -> live
            }
            canvas.drawRoundRect(
                RectF(x0 + dp(2f), top + dp(2f), x0 + dp(2f) + fillW, top + h - dp(2f)),
                dp(1.5f), dp(1.5f), paint,
            )
        }
        // 充电：闪电
        if (battCharging) {
            val cx = x0 + w / 2f
            val path = android.graphics.Path().apply {
                moveTo(cx + dp(2f), cy - dp(6f))
                lineTo(cx - dp(3f), cy + dp(1f))
                lineTo(cx - dp(0.5f), cy + dp(1f))
                lineTo(cx - dp(2f), cy + dp(6f))
                lineTo(cx + dp(3f), cy - dp(1f))
                lineTo(cx + dp(0.5f), cy - dp(1f))
                close()
            }
            // 闪电要在绿色填充上看得见：电量高时用深色"挖空"，电量低时用亮色
            paint.color = if (battLevel >= 40) Color.parseColor("#0B0F12") else live
            canvas.drawPath(path, paint)
        }
        var x = x0 + w + dp(6f)
        if (battLevel >= 0) {
            paint.textAlign = Paint.Align.LEFT
            paint.color = when {
                battLevel <= 20 -> bad
                battLevel <= 40 -> warn
                else -> live
            }
            val pct = "$battLevel%"
            canvas.drawText(pct, x, baselineY(cy, paint), paint)
            x += paint.measureText(pct) + dp(6f)

            // 预测剩余时间：不充电、且已测出放电速率时显示「≈52分」
            val remain = BatteryForecast.remainingMin
            val est = when {
                battCharging -> null
                remain > 0.0 -> {
                    val total = remain.roundToInt()
                    if (total < 60) context.getString(R.string.hud_batt_min, total)
                    else context.getString(R.string.hud_batt_hm, total / 60, total % 60)
                }
                StreamStats.running -> context.getString(R.string.hud_batt_calc)
                else -> null
            }
            if (est != null) {
                paint.color = when {
                    remain <= 0.0 -> hint
                    remain < 8 -> bad
                    remain < 20 -> warn
                    else -> live
                }
                // 预测值比主字幕小一档，省地方
                paint.textSize = dp(11f)
                canvas.drawText(est, x, baselineY(cy, paint), paint)
                x += paint.measureText(est) + dp(7f)
                paint.textSize = dp(12f)
            }
        }
        return x - x0
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /** 由 Activity 每 400ms 调一次：切换闪烁相位并重绘。 */
    fun tick() {
        blinkOn = !blinkOn
        invalidate()
    }

    private fun baselineY(centerY: Float, p: Paint): Float =
        centerY - (p.descent() + p.ascent()) / 2f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val pad = dp(20f)
        val rowH = dp(36f)
        val cy2 = height - dp(20f) - rowH / 2f
        val cy1 = cy2 - rowH
        val right = width - pad

        val running = StreamStats.running

        // ================= 第 1 行：状态 + 延迟 =================
        var x = pad
        if (running) {
            paint.color = live
            paint.alpha = if (blinkOn) 255 else 70
            canvas.drawCircle(x + dp(8f), cy1, dp(8f), paint)
            paint.alpha = 255
        } else {
            paint.color = idle
            val s = dp(7f)
            canvas.drawRect(x, cy1 - s, x + s * 2, cy1 + s, paint)
        }
        x += dp(26f)
        paint.textAlign = Paint.Align.LEFT
        paint.color = if (running) live else idle
        canvas.drawText(
            context.getString(if (running) R.string.hud_live else R.string.hud_stopped),
            x, baselineY(cy1, paint), paint,
        )
        x += dp(58f)

        // 延迟：4 根天线格，越高越好
        val rtt = StreamStats.lastRttMs
        val level = when {
            !running -> 0
            rtt <= 0 -> 3
            rtt < 90 -> 4
            rtt < 170 -> 3
            rtt < 320 -> 2
            else -> 1
        }
        paint.color = when {
            !running -> idle
            level >= 3 -> live
            level == 2 -> warn
            else -> bad
        }
        val barW = dp(4f)
        val barGap = dp(3f)
        val baseY = cy1 + dp(10f)
        for (i in 0 until 4) {
            val h = dp(6f + i * 5f)
            val left = x + i * (barW + barGap)
            canvas.drawRect(left, baseY - h, left + barW, baseY, paint)
        }
        x += 4 * (barW + barGap) + dp(9f)
        canvas.drawText(
            when {
                !running -> context.getString(R.string.hud_latency) + " " + context.getString(R.string.hud_none)
                level >= 3 -> context.getString(R.string.hud_latency) + " " + context.getString(R.string.hud_good)
                else -> context.getString(R.string.hud_latency) + " " + context.getString(R.string.hud_bad)
            },
            x, baselineY(cy1, paint), paint,
        )

        paint.color = hint
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(context.getString(R.string.hud_tap_toggle), right, baselineY(cy1, paint), paint)

        // ================= 第 2 行：电量 + 链路 =================
        x = pad + drawBattery(canvas, pad, cy2) + dp(8f)
        val linkOk = running && StreamStats.lastError.isEmpty()
        paint.color = when {
            !running -> idle
            linkOk -> live
            else -> bad
        }
        stroke.color = paint.color
        stroke.strokeWidth = dp(2.2f)
        val r = dp(6.5f)
        canvas.drawArc(RectF(x, cy2 - r, x + r * 2, cy2 + r), 90f, 300f, false, stroke)
        canvas.drawArc(
            RectF(x + r * 1.15f, cy2 - r, x + r * 3.15f, cy2 + r), 270f, 300f, false, stroke
        )
        x += dp(26f)
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            when {
                !running -> context.getString(R.string.hud_link) + " " + context.getString(R.string.hud_none)
                linkOk -> context.getString(R.string.hud_link) + " " + context.getString(R.string.hud_connected)
                else -> context.getString(R.string.hud_link) + " " + context.getString(R.string.hud_disconnected)
            },
            x, baselineY(cy2, paint), paint,
        )

        paint.color = hint
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(context.getString(R.string.hud_double_exit), right, baselineY(cy2, paint), paint)

        drawControls(canvas)
    }

    // ---------------- 右上角：码率设置（可滑动选中 → 点击展开下拉） ----------------

    private val boxW = dp(112f)
    private val boxH = dp(32f)

    private fun drawControls(canvas: Canvas) {
        val pad = dp(18f)
        val bx = width - pad - boxW
        val by = pad

        // 码率框（右上）
        drawBox(
            canvas, bx, by, boxW, boxH,
            context.getString(R.string.ctl_bitrate, StreamStats.bitrateLabel),
            focused = StreamStats.hudFocus == HudControl.FOCUS_BITRATE ||
                StreamStats.hudFocus == HudControl.FOCUS_MENU,
        )

        // 看门狗框（左上）：关掉后 App 被系统杀掉也不会被自动拉起
        drawBox(
            canvas, pad, by, boxW * 0.86f, boxH,
            context.getString(
                if (StreamStats.watchdogOn) R.string.ctl_watchdog_on else R.string.ctl_watchdog_off
            ),
            focused = StreamStats.hudFocus == HudControl.FOCUS_WATCHDOG,
        )

        // 下拉菜单
        if (StreamStats.hudFocus == HudControl.FOCUS_MENU) {
            val rowH = dp(30f)
            var y = by + boxH + dp(6f)
            StreamConfig.LEVEL_LABELS.forEachIndexed { i, label ->
                val focused = i == StreamStats.hudDropIndex
                val current = i == StreamStats.bitrateLevelSel
                if (focused) {
                    paint.color = live
                    canvas.drawRoundRect(RectF(bx, y, bx + boxW, y + rowH), dp(5f), dp(5f), paint)
                } else {
                    paint.color = Color.parseColor("#141A1F")
                    canvas.drawRoundRect(RectF(bx, y, bx + boxW, y + rowH), dp(5f), dp(5f), paint)
                }
                paint.textAlign = Paint.Align.LEFT
                paint.color = if (focused) Color.parseColor("#0B0F12") else live
                val prefix = if (current) "● " else "   "
                canvas.drawText(prefix + label, bx + dp(10f), baselineY(y + rowH / 2f, paint), paint)
                y += rowH
            }
        }

        // 左下角：直播开关（HUD 两行上方）
        val rowH = dp(36f)
        val cy2 = height - dp(20f) - rowH / 2f
        val ax = pad
        val ay = cy2 - rowH - dp(20f) - boxH
        drawBox(
            canvas, ax, ay, boxW * 0.72f, boxH,
            context.getString(if (StreamStats.running) R.string.ctl_switch_on else R.string.ctl_switch_off),
            focused = StreamStats.hudFocus == HudControl.FOCUS_SWITCH,
        )
    }

    private fun drawBox(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, text: String, focused: Boolean) {
        if (focused) {
            paint.color = live
            canvas.drawRoundRect(RectF(x, y, x + w, y + h), dp(6f), dp(6f), paint)
            paint.color = Color.parseColor("#0B0F12")
        } else {
            paint.color = Color.parseColor("#141A1F")
            canvas.drawRoundRect(RectF(x, y, x + w, y + h), dp(6f), dp(6f), paint)
            stroke.color = idle
            stroke.strokeWidth = dp(1.2f)
            canvas.drawRoundRect(RectF(x, y, x + w, y + h), dp(6f), dp(6f), stroke)
            paint.color = live
        }
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(text, x + dp(10f), baselineY(y + h / 2f, paint), paint)
    }
}
