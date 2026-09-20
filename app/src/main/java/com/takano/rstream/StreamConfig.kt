package com.takano.rstream

import android.content.Intent
import android.content.SharedPreferences

/**
 * 推流参数：命令行（adb am start 的 extra）优先，其次 SharedPreferences，最后内置默认值。
 *
 * codec = "mjpeg"（默认：逐帧软件 JPEG 走 /frame，AI 抓帧最省事）
 *       | "h264"（相机直接写进 MediaCodec 输入 Surface，零拷贝硬编，码率低 CPU 低；
 *                 Mac 端 See-Bridge 用 ffmpeg 解码后照样出 JPEG / 直播 / 切片）
 */
data class StreamConfig(
    val host: String,
    val port: Int,
    val fps: Int,
    val width: Int,
    val height: Int,
    val quality: Int = 75,
    val codec: String = "mjpeg",
    val bitrateKbps: Int = 800,
    /** 调试用：覆盖由 SENSOR_ORIENTATION 推出的旋转角（-1 = 用自动值）。 */
    val rotOverride: Int = -1,
    /** 中继的原始 TCP 收流口（13 字节帧头 + Annex-B，比每帧一次 HTTP 快得多）。 */
    val rawPort: Int = 8900,
    /** 码率档位：0 = 自动，1..4 = 手动（见 LEVEL_*）。 */
    val bitrateLevel: Int = 0,
) {
    val url: String get() = "http://$host:$port/frame"
    val h264Url: String get() = "http://$host:$port/h264"
    val postUrl: String get() = if (isH264) h264Url else url
    val minIntervalMs: Long get() = (1000L / fps.coerceAtLeast(1))

    val isH264: Boolean get() = codec.equals("h264", ignoreCase = true)

    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putString("host", host)
            .putInt("port", port)
            .putInt("fps", fps)
            .putInt("width", width)
            .putInt("height", height)
            .putInt("quality", quality)
            .putString("codec", codec)
            .putInt("bitrateKbps", bitrateKbps)
            .putInt("rawPort", rawPort)
            .putInt("bitrateLevel", bitrateLevel)
            .apply()
    }

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8899
        const val DEFAULT_RAW_PORT = 8900
        const val DEFAULT_FPS = 25
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
        const val DEFAULT_QUALITY = 75
        const val DEFAULT_CODEC = "h264"
        const val DEFAULT_BITRATE_KBPS = 6000
        /**
         * 默认画面旋转（顺时针多少度才正立）。
         * RG-glasses 的 SENSOR_ORIENTATION 报 270，但实测要配合 GL 的 ST 变换才等效，
         * 用 --ei rot N 可以不改代码试 0/90/180/270。
         */
        const val DEFAULT_UPRIGHT_ROTATION = 0

        /**
         * 码率档位：index 0 = 自动（按网络选），1..N 手动档。
         * 档位 = (标签, kbps, 编码高度)；宽度按 9:16 自动算，低了同时降分辨率，
         * 因为 720p 塞进 256kbps 会糊成一片，降分辨率才划算。
         */
        val LEVEL_LABELS = arrayOf("自动", "6.0M", "2.5M", "800k", "256k")
        val LEVEL_KBPS = intArrayOf(0, 6000, 2500, 800, 256)
        val LEVEL_HEIGHT = intArrayOf(0, 1280, 1280, 960, 640)
        /** 自动档：在家（10.10.10.x）用 6.0M，出门用 2.5M。 */
        const val AUTO_HOME_LEVEL = 1
        const val AUTO_AWAY_LEVEL = 2

        /** 设备上的配置文件：/sdcard/rstream/config.txt（key=value，一行一条，可用 # 注释）。 */
        fun deviceConfig(): Map<String, String> {
            val f = java.io.File("/sdcard/rstream/config.txt")
            if (!f.exists()) return emptyMap()
            return try {
                f.readLines().mapNotNull { line ->
                    val t = line.trim()
                    if (t.isEmpty() || t.startsWith("#") || !t.contains('=')) null
                    else t.substringBefore('=').trim() to t.substringAfter('=').trim()
                }.toMap()
            } catch (e: Exception) {
                emptyMap()
            }
        }

        fun load(intent: Intent?, prefs: SharedPreferences): StreamConfig = StreamConfig(
            host = intent?.getStringExtra("host") ?: prefs.getString("host", DEFAULT_HOST)!!,
            port = intent?.getIntExtra("port", 0)?.takeIf { it > 0 } ?: prefs.getInt("port", DEFAULT_PORT),
            fps = intent?.getIntExtra("fps", 0)?.takeIf { it > 0 } ?: prefs.getInt("fps", DEFAULT_FPS),
            width = intent?.getIntExtra("width", 0)?.takeIf { it > 0 } ?: prefs.getInt("width", DEFAULT_WIDTH),
            height = intent?.getIntExtra("height", 0)?.takeIf { it > 0 } ?: prefs.getInt("height", DEFAULT_HEIGHT),
            quality = intent?.getIntExtra("quality", 0)?.takeIf { it > 0 } ?: prefs.getInt("quality", DEFAULT_QUALITY),
            codec = intent?.getStringExtra("codec") ?: prefs.getString("codec", DEFAULT_CODEC)!!,
            bitrateKbps = intent?.getIntExtra("bitrate", 0)?.takeIf { it > 0 }
                ?: prefs.getInt("bitrateKbps", DEFAULT_BITRATE_KBPS),
            rotOverride = intent?.getIntExtra("rot", -1) ?: -1,
            rawPort = intent?.getIntExtra("rawport", 0)?.takeIf { it > 0 } ?: prefs.getInt("rawPort", DEFAULT_RAW_PORT),
            bitrateLevel = intent?.getIntExtra("level", -1)?.takeIf { it >= 0 }
                ?: prefs.getInt("bitrateLevel", 0),
        )
    }
}
