package com.takano.rstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 前台服务：绑定相机 → 抓 JPEG 帧 → POST 到 See-Bridge。
 * 无 Preview（单色 HUD 无意义），背压策略 KEEP_ONLY_LATEST，发送端单槽丢帧。
 */
class StreamService : Service(), LifecycleOwner {

    companion object {
        const val TAG = "SeeStream"
        const val CHANNEL_ID = "seestream"
        const val NOTIF_ID = 4101
        const val ACTION_START = "com.takano.rstream.START"
        /** 只把内置中继拉起来，不开始推流（开机广播用）。 */
        const val ACTION_RELAY_ONLY = "com.takano.rstream.RELAY_ONLY"
        /** 循环码率档位（触控板长按）。 */
        const val ACTION_CYCLE = "com.takano.rstream.CYCLE"
        /** 指定码率档位（HUD 下拉菜单选中）。 */
        const val ACTION_SET_LEVEL = "com.takano.rstream.SET_LEVEL"
        /** 切换看门狗监控开关。 */
        const val ACTION_TOGGLE_WATCHDOG = "com.takano.rstream.TOGGLE_WATCHDOG"
        const val ACTION_STOP = "com.takano.rstream.STOP"
        const val PREFS = "seestream"
    }

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var sender: Sender? = null
    private var preview: Preview? = null
    private var codec: MediaCodec? = null
    private var glRotator: GlRotator? = null
    private var csd: ByteArray? = null
    @Volatile private var draining = false
    /** H.264 模式下发给桥端的旋转角（由 SENSOR_ORIENTATION 推出，见 bindH264Camera）。 */
    private var h264Rotation = 270
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var quality = StreamConfig.DEFAULT_QUALITY
    private val jpegBuffer = ByteArrayOutputStream(512 * 1024)
    private var nv21Buf: ByteArray? = null
    private var uArr: ByteArray? = null
    private var vArr: ByteArray? = null
    private var cfg: StreamConfig? = null
    private var analysis: ImageAnalysis? = null
    /** AE 帧率上限是否生效；相机不支持时置 false 并回退（见 bindCamera）。 */
    private var fpsCapActive = true
    @Volatile private var lastAnalyzerAt = 0L
    private var recoveries = 0
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            if (StreamStats.running) {
                val now = SystemClock.elapsedRealtime()
                // 相机被系统/其它 App 抢走时 CameraX 会静默关闭客户端（实测发生过），这里自愈重绑
                if (lastAnalyzerAt > 0 && now - lastAnalyzerAt > 10_000) {
                    recoveries += 1
                    StreamStats.recoveries = recoveries
                    Log.w(TAG, "no frame for ${(now - lastAnalyzerAt) / 1000}s → rebind camera (#$recoveries)")
                    provider?.unbindAll()
                    cfg?.let { if (it.isH264) bindH264Camera(it) else bindCamera(it) }
                    lastAnalyzerAt = now
                }
            }
            watchdogHandler.postDelayed(this, 5_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            sendBye()
            stopStreaming()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TOGGLE_WATCHDOG) {
            val on = !getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("watchdog", true)
            setWatchdog(on)
            return START_STICKY
        }
        if (intent?.action == ACTION_SET_LEVEL) {
            setBitrateLevel(intent.getIntExtra("level", 0))
            return START_STICKY
        }
        if (intent?.action == ACTION_CYCLE) {
            cycleBitrate()
            return START_STICKY
        }
        if (intent?.action == ACTION_RELAY_ONLY) {
            startForegroundWithNotification(StreamConfig.load(null, getSharedPreferences(PREFS, MODE_PRIVATE)))
            ensureRelayRunning()
            return START_STICKY
        }
        var cfg = StreamConfig.load(intent, getSharedPreferences(PREFS, MODE_PRIVATE))
        // 码率档位：0=自动（在家 6.0M / 出门 2.5M），1..4=手动档（长按触控板循环）
        val lvl = resolveLevel(cfg.bitrateLevel)
        cfg = cfg.copy(bitrateKbps = StreamConfig.LEVEL_KBPS[lvl], bitrateLevel = cfg.bitrateLevel)
        StreamStats.watchdogOn = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("watchdog", true)
        notifyRelayWatchdog(StreamStats.watchdogOn)
        StreamStats.bitrateLevelSel = cfg.bitrateLevel
        StreamStats.bitrateLabel = StreamConfig.LEVEL_LABELS[cfg.bitrateLevel] +
            (if (cfg.bitrateLevel == 0) "(${StreamConfig.LEVEL_LABELS[lvl]})" else "")
        Log.i(TAG, "码率档位: 设置=${StreamConfig.LEVEL_LABELS[cfg.bitrateLevel]} 实际=${StreamConfig.LEVEL_KBPS[lvl]}kbps 高度=${StreamConfig.LEVEL_HEIGHT[lvl]}")
        cfg.save(getSharedPreferences(PREFS, MODE_PRIVATE))
        startForegroundWithNotification(cfg)
        StreamStats.reset()
        if (!StreamStats.running) startStreaming(cfg)
        statsHandler.removeCallbacks(statsTicker)
        statsHandler.post(statsTicker)
        ensureRelayRunning()
        relayHandler.removeCallbacks(relayTicker)
        relayHandler.post(relayTicker)
        // 自愈验证钩子：--ez killCamera true 会在相机绑定后主动解绑，看 watchdog 是否 ~15s 内自动重绑
        if (intent?.getBooleanExtra("killCamera", false) == true) {
            watchdogHandler.postDelayed({
                provider?.unbindAll()
                Log.w(TAG, "debug: camera unbound on purpose (killCamera)")
            }, 12_000)
        }
        return START_STICKY
    }

    private val statsHandler = Handler(Looper.getMainLooper())
    private var lastStatsAt = 0L
    private var lastStatsBytes = 0L
    private val statsTicker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val bytes = StreamStats.bytesSent
            if (lastStatsAt > 0) {
                val dt = (now - lastStatsAt) / 1000.0
                val kbps = ((bytes - lastStatsBytes) * 8 / 1000.0) / dt
                Log.i(TAG, "stats fps=${StreamStats.fps()} kbps=${"%.0f".format(kbps)} sent=${StreamStats.sent} dropped=${StreamStats.dropped} errors=${StreamStats.errors} rtt=${StreamStats.lastRttMs}ms q=${StreamStats.resolution}")
            }
            lastStatsAt = now
            lastStatsBytes = bytes
            statsHandler.postDelayed(this, 5000)
        }
    }

    override fun onDestroy() {
        stopStreaming()
        statsHandler.removeCallbacks(statsTicker)
        super.onDestroy()
    }

    // ---------------- 推流生命周期 ----------------

    // ---------------- 内置中继（把 Go 编译的 tsrelay 当 libtsrelay.so 打包，从 nativeLibraryDir 执行）----------------

    private val relayHandler = Handler(Looper.getMainLooper())
    private val relayTicker = object : Runnable {
        override fun run() {
            if (StreamStats.running) ensureRelayRunning()
            relayHandler.postDelayed(this, 10_000)
        }
    }

    /** 中继端口是否已在监听（可能是我们自己起的，也可能是 adb 起的 shell 进程）。 */
    private fun relayPortOpen(port: Int = 8900): Boolean = try {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 300) }
        true
    } catch (e: Exception) {
        false
    }

    private var relayStartedAt = 0L

    /** 确保中继在跑：已在跑就什么都不做，否则从 App 自带的二进制拉起来。 */
    private fun ensureRelayRunning() {
        if (relayPortOpen()) return
        // 刚拉起过就给 tsnet 一点时间（登录 + 起监听要几秒），别连开好几个
        if (SystemClock.elapsedRealtime() - relayStartedAt < 20_000) return
        relayStartedAt = SystemClock.elapsedRealtime()
        try {
            val bin = java.io.File(applicationInfo.nativeLibraryDir, "libtsrelay.so")
            if (!bin.exists()) {
                Log.w(TAG, "内置中继不存在: ${bin.absolutePath}")
                return
            }
            val devCfg = StreamConfig.deviceConfig()
            val key = devCfg["tskey"]
                ?: java.io.File("/sdcard/rstream/tskey.txt").takeIf { it.exists() }?.readText()?.trim()
                ?: java.io.File(filesDir, "tskey.txt").takeIf { it.exists() }?.readText()?.trim()
                ?: runCatching { assets.open("tskey.txt").use { it.bufferedReader().readText().trim() } }.getOrNull()
                ?: runCatching { assets.open("tskey.example.txt").use { it.bufferedReader().readText().trim() } }.getOrNull()
                ?: ""
            val publish = devCfg["publish"] ?: "rtmp://YOUR-SERVER:1935/rokid"
            val maps = devCfg["maps"] ?: ""   // 例如 "0.0.0.0:1935=YOUR-SERVER:1935,127.0.0.1:9997=YOUR-SERVER:9997"
            if (key.isEmpty() || key.contains("xxxx")) {
                Log.w(TAG, "没配 Tailscale auth key：把 key 放到 /sdcard/rstream/config.txt 或 assets/tskey.txt")
            }
            val state = java.io.File(filesDir, "tsstate").apply { mkdirs() }
            // tsnet 会去找「安全的地方存日志状态」，App 沙箱里得显式指定，否则 panic: no safe place found to store log state
            val logs = java.io.File(filesDir, "tslogs").apply { mkdirs() }
            val log = java.io.File(filesDir, "relay.log")
            val pb = ProcessBuilder(bin.absolutePath)
            pb.environment().apply {
                put("TS_AUTHKEY", key)
                put("TS_STATE", state.absolutePath)
                put("TS_LOGS_DIR", logs.absolutePath)
                put("HOME", filesDir.absolutePath)
                put("TS_HOSTNAME", "glasses-relay")
                put("MAPS", maps)
                put("INGEST", "0.0.0.0:8899")
                put("RAW_INGEST", "0.0.0.0:8900")
                put("PUBLISH", publish)
            }
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            pb.start()
            Log.i(TAG, "已拉起内置中继: ${bin.absolutePath} → 日志 ${log.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "拉起内置中继失败: ${e.message}", e)
        }
    }

    /** 本机第一个非回环 IPv4。 */
    private fun localIpv4(): String = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress ?: ""
    } catch (e: Exception) {
        ""
    }

    /** 解析档位：0 = 自动（按本机 IP 判断在家/出门），其余按表。 */
    private fun resolveLevel(level: Int): Int {
        if (level in 1 until StreamConfig.LEVEL_KBPS.size) return level
        val ip = localIpv4()
        val home = ip.startsWith("10.10.10.")
        Log.i(TAG, "码率自动: 本机 IP=$ip 在家=$home")
        return if (home) StreamConfig.AUTO_HOME_LEVEL else StreamConfig.AUTO_AWAY_LEVEL
    }

    /** 编码输出尺寸：按档位高度算（宽度 = 高度*9/16 取偶），相机输入尺寸不变。 */
    private fun levelOutSize(level: Int, rot: Int, camW: Int, camH: Int): Pair<Int, Int> {
        val h = StreamConfig.LEVEL_HEIGHT[resolveLevel(level)]
        val swap = (rot == 0 || rot == 180)
        return if (h <= 0) {
            if (swap) camH to camW else camW to camH
        } else {
            val short = (h * 9 / 16) / 2 * 2
            if (swap) short to h else h to short
        }
    }

    /** 看门狗开关：存设置 + 通知中继（中继按这个决定要不要把被杀掉的 App 拉回来）。 */
    private fun setWatchdog(enabled: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("watchdog", enabled).apply()
        StreamStats.watchdogOn = enabled
        notifyRelayWatchdog(enabled)
        Log.i(TAG, "看门狗 = $enabled")
    }

    private fun notifyRelayWatchdog(enabled: Boolean) {
        Thread({ notifyRelayWatchdogBlocking(enabled) }, "wd").start()
    }

    private fun notifyRelayWatchdogBlocking(enabled: Boolean) {
        try {
            val conn = (java.net.URL("http://127.0.0.1:8899/watchdog/" + if (enabled) "on" else "off")
                .openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 800
                readTimeout = 800
            }
            conn.outputStream.use { }
            conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "通知中继看门狗状态失败: ${e.message}")
        }
    }

    /** HUD 下拉菜单选中某个档位：存下来，正在推流就重启生效。 */
    fun setBitrateLevel(level: Int) {
        val l = level.coerceIn(0, StreamConfig.LEVEL_LABELS.size - 1)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("bitrateLevel", l).apply()
        Log.i(TAG, "选中码率档位 → ${StreamConfig.LEVEL_LABELS[l]}")
        if (StreamStats.running) {
            stopStreaming()
            statsHandler.postDelayed({
                androidx.core.content.ContextCompat.startForegroundService(
                    this, Intent(this, StreamService::class.java).setAction(ACTION_START)
                )
            }, 1200)
        }
    }

    /** 长按触控板：循环码率档位（自动 → 6.0M → 2.5M → 800k → 256k → 自动），重启推流生效。 */
    fun cycleBitrate() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val next = (prefs.getInt("bitrateLevel", 0) + 1) % StreamConfig.LEVEL_LABELS.size
        prefs.edit().putInt("bitrateLevel", next).apply()
        Log.i(TAG, "切换码率档位 → ${StreamConfig.LEVEL_LABELS[next]}")
        if (StreamStats.running) {
            // 编码器码率/尺寸是建编码器时定的，必须重启推流才生效
            stopStreaming()
            statsHandler.postDelayed({
                androidx.core.content.ContextCompat.startForegroundService(
                    this, Intent(this, StreamService::class.java).setAction(ACTION_START)
                )
            }, 1200)
        }
    }

    private fun startStreaming(cfg: StreamConfig) {
        StreamStats.running = true
        StreamStats.url = cfg.postUrl
        StreamStats.cameraState = "相机启动中"
        quality = cfg.quality
        this.cfg = cfg
        acquireLocks()
        registry.currentState = Lifecycle.State.RESUMED
        lastAnalyzerAt = SystemClock.elapsedRealtime()
        watchdogHandler.removeCallbacks(watchdog)
        watchdogHandler.postDelayed(watchdog, 5_000)
        sender = Sender(cfg).also { it.start() }
        if (cfg.isH264) bindH264Camera(cfg) else bindCamera(cfg)
        Log.i(TAG, "streaming to ${cfg.postUrl} @${cfg.fps}fps ${cfg.width}x${cfg.height} codec=${cfg.codec}" +
            (if (cfg.isH264) " ${cfg.bitrateKbps}kbps" else " q${cfg.quality}"))
    }

    /** 通知中继：这是用户主动停止，别自动拉起（13 字节帧头，长度 0 + 标志 2）。 */
    private fun sendBye() {
        // 网络调用必须离开主线程，否则抛 NetworkOnMainThreadException 被吞掉（踩过一次）
        val t = Thread({ sendByeBlocking() }, "bye")
        t.start()
        runCatching { t.join(900) }
    }

    private fun sendByeBlocking() {
        try {
            java.net.Socket().use { sk ->
                sk.connect(java.net.InetSocketAddress("127.0.0.1", 8900), 500)
                val hdr = java.nio.ByteBuffer.allocate(13)
                hdr.putInt(0)
                hdr.put(2)
                hdr.putLong(0)
                sk.getOutputStream().write(hdr.array())
                sk.getOutputStream().flush()
            }
        } catch (e: Exception) {
            // 中继没在跑就算了
        }
    }

    private fun stopStreaming() {
        StreamStats.running = false
        watchdogHandler.removeCallbacks(watchdog)
        sender?.shutdown()
        sender = null
        stopH264()
        provider?.unbindAll()
        provider = null
        registry.currentState = Lifecycle.State.CREATED
        StreamStats.cameraState = "已停止"
        releaseLocks()
        Log.i(TAG, "streaming stopped (sent=${StreamStats.sent}, dropped=${StreamStats.dropped}, errors=${StreamStats.errors})")
    }

    private fun bindCamera(cfg: StreamConfig) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val camProvider = future.get()
                provider = camProvider
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(cfg.width, cfg.height),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
                val analysisBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_NV21)
                // 给相机（AE 目标帧率）设下限：目标只有 cfg.fps，没必要让 sensor/ISP 一直 30fps 跑。
                // 实测本机支持的是固定档 15/24/30/60（没有 5fps 这种区间），所以从
                // CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES 里挑「上限 ≥ 目标 fps 且上限最小」的那档。
                val analysis = if (fpsCapActive) {
                    try {
                        val info = camProvider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
                        val ranges = Camera2CameraInfo.from(info)
                            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        val pick = ranges
                            ?.filter { it.upper >= cfg.fps }
                            ?.minByOrNull { it.upper }
                            ?: ranges?.minByOrNull { it.upper }
                        if (pick != null) {
                            Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, pick
                            )
                            Log.i(TAG, "AE 帧率区间锁定为 $pick（目标 ${cfg.fps}fps，设备支持 ${ranges?.joinToString()}）")
                        }
                        analysisBuilder.build()
                    } catch (e: Exception) {
                        Log.w(TAG, "AE 帧率上限不支持，回退默认: ${e.message}")
                        fpsCapActive = false
                        ImageAnalysis.Builder()
                            .setResolutionSelector(selector)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_NV21)
                            .build()
                    }
                } else {
                    analysisBuilder.build()
                }
                this.analysis = analysis
                analysis.setAnalyzer(cameraExecutor) { proxy -> onFrame(proxy) }
                camProvider.unbindAll()
                camProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                StreamStats.cameraState = "相机已绑定"
                lastAnalyzerAt = SystemClock.elapsedRealtime()
                Log.i(TAG, "camera bound")
            } catch (e: Exception) {
                if (fpsCapActive) {
                    // 有些 HAL 只接受自己支持的 AE 帧率区间，被拒就去掉上限重绑一次
                    fpsCapActive = false
                    Log.w(TAG, "camera bind failed with AE fps cap, retry without: ${e.message}")
                    StreamStats.cameraState = "重试中（已去掉帧率上限）"
                    bindCamera(cfg)
                } else {
                    StreamStats.cameraState = "相机失败: ${e.message}"
                    StreamStats.lastError = "camera: ${e.javaClass.simpleName} ${e.message}"
                    Log.e(TAG, "camera bind failed", e)
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(proxy: ImageProxy) {
        try {
            if (proxy.planes.isEmpty()) return
            // 关键：先判断这一帧要不要发，不要先做转换/编码再丢（那样白烧 80% CPU 变热）
            val tx = sender ?: return
            if (!tx.wantsFrame()) {
                StreamStats.skipped += 1
                return
            }
            val width = proxy.width
            val height = proxy.height
            val buffer = proxy.planes[0].buffer
            if (StreamStats.sent + StreamStats.dropped < 3) {
                Log.i(TAG, "frame: ${width}x$height fmt=${proxy.format} planes=${proxy.planes.size} " +
                    "buf[pos=${buffer.position()} lim=${buffer.limit()} cap=${buffer.capacity()}] " +
                    "expectNv21=${width * height * 3 / 2} rowStride=${proxy.planes[0].rowStride} pixStride=${proxy.planes[0].pixelStride} " +
                    "u(row=${proxy.planes[1].rowStride},pix=${proxy.planes[1].pixelStride}) v(row=${proxy.planes[2].rowStride},pix=${proxy.planes[2].pixelStride})")
            }
            val nv21 = toNv21(proxy)
            val jpeg = toJpeg(nv21, width, height)
            lastAnalyzerAt = SystemClock.elapsedRealtime()
            StreamStats.resolution = "${width}x$height"
            StreamStats.rotation = proxy.imageInfo.rotationDegrees
            val frame = Frame(jpeg, uprightRotation(proxy.imageInfo.rotationDegrees), proxy.imageInfo.timestamp)
            if (!tx.offer(frame)) StreamStats.dropped += 1
        } catch (e: Exception) {
            StreamStats.lastError = "frame: ${e.message}"
        } finally {
            proxy.close()
        }
    }

    /**
     * YUV_420_888 → NV21。
     * 本机实测 ImageAnalysis 的 NV21 输出请求不生效（仍是 planes=3 的 YUV_420_888），
     * 直接拿 plane[0] 当 NV21 会丢掉色度（画面全绿），所以必须按 rowStride/pixelStride 自己拼。
     */
    private fun toNv21(proxy: ImageProxy): ByteArray {
        val w = proxy.width
        val h = proxy.height
        val need = w * h * 3 / 2
        if (nv21Buf == null || nv21Buf!!.size < need) nv21Buf = ByteArray(need)
        val out = nv21Buf!!
        val yPlane = proxy.planes[0]
        val uPlane = proxy.planes[1]
        val vPlane = proxy.planes[2]
        val yBuf = yPlane.buffer
        val yRow = yPlane.rowStride
        val yStart = yBuf.position()
        val yLimit = yBuf.limit()
        if (yRow == w && yStart == 0 && yLimit >= w * h) {
            yBuf.get(out, 0, w * h)
        } else {
            // 注意：yBuf.position() 会被上一行的 get() 推进，起点必须**先固定**，
            // 否则 base 逐行累加（row*rowStride + 已读字节）→ 很快越界，
            // 表现为 "Bad position xxx/yyy"，整帧丢弃、一帧都发不出去。
            for (row in 0 until h) {
                val src = yStart + row * yRow
                val available = yLimit - src
                if (available <= 0) break
                val n = minOf(w, available)
                yBuf.position(src)
                yBuf.get(out, row * w, n)
                if (n < w) {
                    // 末行可能没有行填充（实测 limit = rowStride*(h-1) + w），用行尾像素补齐
                    val tail = out[row * w + n - 1]
                    for (i in row * w + n until row * w + w) out[i] = tail
                }
            }
        }
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRow = uPlane.rowStride
        val vRow = vPlane.rowStride
        val uPix = uPlane.pixelStride
        val vPix = vPlane.pixelStride
        val chromaH = h / 2
        val chromaW = w / 2
        var pos = w * h
        // 色度：先整块 plane 批量拷进数组，再在数组上交错。
        // 逐像素 uBuf.get(index)/vBuf.get(index) 每帧要几十万次带边界检查的调用
        // （480p ≈ 20 万次），实测这是转换里最大的一块 CPU；改成数组索引后同样结果但快得多。
        val uSize = uBuf.remaining()
        val vSize = vBuf.remaining()
        if (uArr == null || uArr!!.size < uSize) uArr = ByteArray(uSize)
        if (vArr == null || vArr!!.size < vSize) vArr = ByteArray(vSize)
        val ua = uArr!!
        val va = vArr!!
        val uBase = uBuf.position()
        val vBase = vBuf.position()
        runCatching {
            uBuf.position(uBase); uBuf.get(ua, 0, uSize)
            vBuf.position(vBase); vBuf.get(va, 0, vSize)
        }.onFailure {
            StreamStats.lastError = "chroma copy: ${it.message}"
            return out
        }
        if (uPix == 2 && vPix == 2) {
            // 半平面（交织）：pixelStride=2，取 VU 顺序（NV21）
            for (row in 0 until chromaH) {
                val vOff = row * vRow
                val uOff = row * uRow
                if (vOff + chromaW * 2 > vSize || uOff + chromaW * 2 > uSize) break
                var p = pos
                var c = 0
                while (c < chromaW) {
                    out[p] = va[vOff + c * 2]
                    out[p + 1] = ua[uOff + c * 2]
                    p += 2
                    c++
                }
                pos += chromaW * 2
            }
        } else {
            for (row in 0 until chromaH) {
                val vOff = row * vRow
                val uOff = row * uRow
                var p = pos
                var c = 0
                while (c < chromaW) {
                    out[p] = va[vOff + c * vPix]
                    out[p + 1] = ua[uOff + c * uPix]
                    p += 2
                    c++
                }
                pos += chromaW * 2
            }
        }
        return out
    }

    /**
     * 本机实测（RG-glasses）：CameraX 的 rotationDegrees 报 270，画面需要
     * 顺时针 270°（等价逆时针 90°）才正立 —— 也就是**直接用** rotationDegrees，
     * 不要取反（2026-09-20 用四档旋转 + 时钟数字可读性复核，90° 是倒的）。
     * 这里换算成「顺时针旋转多少度能得到正立画面」，发给 See-Bridge 的 X-Rotation。
     */
    private fun uprightRotation(rotationDegrees: Int): Int = ((rotationDegrees % 360) + 360) % 360

    /**
     * H.264 模式：相机 → MediaCodec 输入 Surface（零拷贝），编码器输出 Annex-B 码流直接 POST。
     *
     * 与 MJPEG 模式的区别：CPU 完全不碰像素（不做 NV21 拼装、不做 JPEG），
     * 编码走 `c2.qti.avc.encoder` 硬件块；代价是 Mac 端要用 ffmpeg 解码。
     * 分辨率/帧率由 VideoCapture 自己协商，SurfaceRequest 给什么尺寸就按什么配编码器。
     */
    private fun bindH264Camera(cfg: StreamConfig) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val camProvider = future.get()
                provider = camProvider
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(cfg.width, cfg.height),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
                val builder = Preview.Builder()
                    .setResolutionSelector(selector)
                // VideoCapture 明确支持 setTargetFrameRate（ImageAnalysis 不支持），
                // 用设备真正支持的档（RG-glasses: 15/24/30/60）
                try {
                    val info = camProvider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
                    val ranges = Camera2CameraInfo.from(info)
                        .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    val pick = ranges?.filter { it.upper >= cfg.fps }?.minByOrNull { it.upper }
                        ?: ranges?.minByOrNull { it.upper }
                    if (pick != null) {
                        builder.setTargetFrameRate(pick)
                        Log.i(TAG, "h264 目标帧率区间 $pick")
                    }
                    val sensor = Camera2CameraInfo.from(info)
                        .getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION)
                    if (sensor != null) {
                        val auto = if (sensor != null && uprightRotation(sensor) != 0) {
                            StreamConfig.DEFAULT_UPRIGHT_ROTATION
                        } else {
                            uprightRotation(sensor)
                        }
                        h264Rotation = if (cfg.rotOverride >= 0) cfg.rotOverride else auto
                        StreamStats.rotation = h264Rotation
                        Log.i(TAG, "h264 旋转: SENSOR_ORIENTATION=$sensor 自动=$auto 实际=$h264Rotation")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "h264 帧率区间设置失败: ${e.message}")
                }
                val preview = Preview.Builder()
                    .setResolutionSelector(selector)
                    .build()
                // Preview 不需要 MediaSpec（VideoCapture 需要，自定义 VideoOutput 会报
                // "MediaSpec can't be null"），只要给它一个 Surface 就能零拷贝渲染。
                preview.setSurfaceProvider { request -> provideEncoderSurface(request) }
                this.preview = preview
                camProvider.unbindAll()
                camProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                StreamStats.cameraState = "H.264 相机已绑定"
                lastAnalyzerAt = SystemClock.elapsedRealtime()
                Log.i(TAG, "h264 camera bound")
            } catch (e: Exception) {
                StreamStats.cameraState = "H.264 相机失败: ${e.message}"
                StreamStats.lastError = "h264 camera: ${e.javaClass.simpleName} ${e.message}"
                Log.e(TAG, "h264 camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun provideEncoderSurface(request: SurfaceRequest) {
        val size = request.resolution
        val cfg = this@StreamService.cfg
        try {
            // 相机 buffer 是横向 1280x720，但 SurfaceTexture 的变换矩阵是 transpose（u/v 互换），
            // 所以「采样出来的画面」本身是竖的 720x1280。于是：
            //   rot 0/180 → 净变换还是 transpose，编码器要用竖屏尺寸，否则横向拉宽
            //   rot 90/270 → 净变换变成水平/垂直翻转，编码器用横屏尺寸
            val rot = ((h264Rotation % 360) + 360) % 360
            val swap = (rot == 0 || rot == 180)
            val (outW, outH) = levelOutSize(cfg?.bitrateLevel ?: 0, rot, size.width, size.height)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, (cfg?.bitrateKbps ?: 800) * 1000)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, cfg?.fps ?: 15)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)   // 1 秒一个关键帧，丢了能恢复
            format.setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR   // 实时链路要恒定码率
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            // 选设备支持的最好 profile（High > Main > Baseline）：同码率下画质更好，快速运动时块效应更少
            runCatching {
                val pls = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).profileLevels
                Log.i(TAG, "AVC 支持 profile/level: " + pls.joinToString(",") { "${it.profile}/${it.level}" })
                val want = listOf(
                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                ).firstOrNull { prof -> pls.any { it.profile == prof } }
                if (want != null) {
                    val lvl = pls.filter { it.profile == want }.maxOf { it.level }
                    format.setInteger(MediaFormat.KEY_PROFILE, want)
                    format.setInteger(MediaFormat.KEY_LEVEL, lvl)
                    Log.i(TAG, "AVC 使用 profile=$want level=$lvl")
                }
            }.onFailure { Log.w(TAG, "profile 设置失败: ${it.message}") }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            this@StreamService.codec = codec
            draining = true
            startDrain(codec)
            // 相机 → SurfaceTexture → GL 旋转 → 编码器输入 Surface
            val rotator = GlRotator(surface, size.width, size.height, outW, outH, rot)
            this@StreamService.glRotator = rotator
            val camSurface = Surface(rotator.start())
            request.provideSurface(camSurface, cameraExecutor) { result ->
                Log.i(TAG, "h264 input surface released: ${result.resultCode}")
            }
            StreamStats.resolution = "${outW}x$outH"
            Log.i(TAG, "h264 encoder started ${outW}x$outH（相机 ${size.width}x${size.height} 旋转 $rot°）@${cfg?.fps}fps ${cfg?.bitrateKbps}kbps")
        } catch (e: Exception) {
            StreamStats.lastError = "h264 encoder: ${e.message}"
            Log.e(TAG, "h264 encoder init failed", e)
            request.willNotProvideSurface()
        }
    }

    private fun startDrain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        Thread({
            while (draining) {
                val idx = try {
                    codec.dequeueOutputBuffer(info, 20_000)
                } catch (e: Exception) {
                    break
                }
                if (idx < 0) continue
                try {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        val out = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(out)
                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        if (isConfig) {
                            csd = out                     // SPS/PPS 单独存，贴到下一个关键帧前面
                        } else {
                            var payload = out
                            val needCsd = isKey && csd != null && !(out.size > 4 && out[0] == 0.toByte() &&
                                out[1] == 0.toByte() && out[2] == 0.toByte() && out[3] == 1.toByte() &&
                                (out[4].toInt() and 0x1F) == 7)
                            if (needCsd) payload = csd!! + out
                            if (sender?.offerH264(payload, isKey, info.presentationTimeUs) != true) {
                                StreamStats.dropped += 1
                            }
                        }
                        lastAnalyzerAt = SystemClock.elapsedRealtime()
                    }
                } catch (e: Exception) {
                    StreamStats.lastError = "h264 drain: ${e.message}"
                } finally {
                    runCatching { codec.releaseOutputBuffer(idx, false) }
                }
            }
        }, "seestream-drain").start()
    }

    private fun stopH264() {
        draining = false
        glRotator?.let { runCatching { it.release() } }
        glRotator = null
        codec?.let { c ->
            runCatching {
                c.stop()
                c.release()
            }
        }
        codec = null
        csd = null
        preview = null
    }

    /** NV21 → JPEG（YuvImage，720p@q75 约 15–25ms）。 */
    private fun toJpeg(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val out = jpegBuffer
        out.reset()
        YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), quality, out)
        return out.toByteArray()
    }

    // ---------------- 帧发送 ----------------

    private class Frame(
        val jpeg: ByteArray,
        val rotation: Int,
        val timestampUs: Long,
        /** H.264 模式：这一片是不是关键帧（桥端据此重置解码器）。 */
        val key: Boolean = false,
    )

    private inner class Sender(private val cfg: StreamConfig) {
        private val queue = ArrayBlockingQueue<Frame>(8)  // 稍微留点缓冲，避免抖动就丢帧（丢帧会打断参考帧链 → 花屏）
        private val thread = Thread({ run() }, "seestream-sender")
        @Volatile private var stopped = false
        private var lastSentAt = 0L

        fun start() = thread.start()

        /**
         * 是否值得为这一帧做 NV21 转换 + JPEG 编码。
         *
         * 相机以 ~30fps 回调，但目标只有 cfg.fps 帧/秒；不先判断就在这里编码，
         * 等于每秒白做 25 次全分辨率编码（实测把 CPU 顶到 86–98%，5fps 都推不满）。
         * 调用方在转换**之前**用它挡掉绝大多数帧，只透传真正要发的那几帧。
         */
        fun wantsFrame(): Boolean {
            if (stopped) return false
            if (SystemClock.elapsedRealtime() - lastSentAt < cfg.minIntervalMs) return false
            return queue.remainingCapacity() > 0
        }

        fun offer(frame: Frame): Boolean {
            if (!wantsFrame()) return false
            return queue.offer(frame)
        }

        /** H.264 模式：编码器按目标帧率出片，不额外节流，只保证不积压。 */
        fun offerH264(data: ByteArray, key: Boolean, tsUs: Long): Boolean {
            if (stopped) return false
            if (queue.remainingCapacity() <= 0) return false
            return queue.offer(Frame(data, h264Rotation, tsUs, key))
        }

        fun shutdown() {
            stopped = true
            thread.interrupt()
            closeSocket()
        }

        private fun run() {
            while (!stopped) {
                val frame = try {
                    queue.poll(500, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                } ?: continue
                send(frame)
            }
        }

        private var sock: java.net.Socket? = null
        private var out: java.io.OutputStream? = null

        /** H.264 走常连 TCP：每帧只写「13 字节帧头 + 数据」，不等响应，避免 40ms 级卡顿。 */
        private fun ensureSocket(): java.io.OutputStream? {
            out?.let { return it }
            return try {
                val s = java.net.Socket()
                s.tcpNoDelay = true
                s.connect(java.net.InetSocketAddress(cfg.host, cfg.rawPort), 2000)
                sock = s
                out = s.getOutputStream()
                Log.i(TAG, "原始收流已连接 ${cfg.host}:${cfg.rawPort}")
                out
            } catch (e: Exception) {
                runCatching { sock?.close() }
                sock = null
                out = null
                null
            }
        }

        private fun closeSocket() {
            runCatching { sock?.close() }
            sock = null
            out = null
        }

        private fun sendRaw(frame: Frame): Boolean {
            val o = ensureSocket() ?: return false
            val hdr = java.nio.ByteBuffer.allocate(13)
            hdr.putInt(frame.jpeg.size)
            hdr.put(if (frame.key) 1 else 0)
            hdr.putLong(frame.timestampUs)
            o.write(hdr.array())
            o.write(frame.jpeg)
            o.flush()
            return true
        }

        private fun send(frame: Frame) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val h264 = cfg.isH264
                if (h264) {
                    if (!sendRaw(frame)) throw IOException("原始收流未连接")
                    lastSentAt = SystemClock.elapsedRealtime()
                    StreamStats.onSent(frame.jpeg.size, lastSentAt - t0)
                    if (StreamStats.lastError.isNotEmpty()) StreamStats.lastError = ""
                    return
                }
                val conn = (URL(cfg.postUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    useCaches = false
                    connectTimeout = 3000
                    readTimeout = 5000
                    setRequestProperty("Content-Type", if (h264) "application/octet-stream" else "image/jpeg")
                    if (h264) {
                        setRequestProperty("X-Key", if (frame.key) "1" else "0")
                        setRequestProperty("X-Rotation", frame.rotation.toString())
                        setRequestProperty("X-Frame-Ts", frame.timestampUs.toString())
                    } else {
                        setRequestProperty("X-Rotation", frame.rotation.toString())
                        setRequestProperty("X-Frame-Ts", frame.timestampUs.toString())
                    }
                    setFixedLengthStreamingMode(frame.jpeg.size)
                }
                conn.outputStream.use { it.write(frame.jpeg) }
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    val body = try {
                        conn.errorStream?.bufferedReader()?.use { it.readText().take(200) }
                    } catch (e: Exception) {
                        null
                    }
                    conn.disconnect()
                    throw IOException("HTTP $code ${body ?: ""}")
                }
                // 读完响应体，让连接回到 keep-alive 池复用
                conn.inputStream.use { it.readBytes() }
                lastSentAt = SystemClock.elapsedRealtime()
                StreamStats.onSent(frame.jpeg.size, lastSentAt - t0)
                if (StreamStats.lastError.isNotEmpty()) StreamStats.lastError = ""
            } catch (e: Exception) {
                closeSocket()
                lastSentAt = SystemClock.elapsedRealtime() // 失败也节流，别每帧猛重试
                StreamStats.errors += 1
                StreamStats.lastError = "${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "send failed: ${e.message}")
            }
        }
    }

    // ---------------- 电源 / Wi-Fi 锁 ----------------

    private fun acquireLocks() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "seestream:camera").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "wakelock failed: ${e.message}")
        }
        try {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "seestream:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "wifilock failed: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            // ignore
        }
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            // ignore
        }
        wakeLock = null
        wifiLock = null
    }

    // ---------------- 前台通知 ----------------

    private fun startForegroundWithNotification(cfg: StreamConfig) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SeeStream 推流", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "相机画面推送到 See-Bridge"
                    setShowBadge(false)
                }
            )
        }
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("SeeStream 推流中")
            .setContentText(cfg.url)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(tapIntent)
            .build()
        startForegroundWithType(notification)
    }

    private fun startForegroundWithType(notification: Notification) {
        startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
    }
}
