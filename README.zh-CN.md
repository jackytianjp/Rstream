# Rstream

把 **Rokid 眼镜**变成你自己的直播摄像头：眼镜负责采集和编码，经 **Tailscale** 把 H.264 推到你自己的
RTMP 服务器 —— 不用系统 VPN、不用手机、不经过厂商云。

[English](README.md) | **中文**

```
Rokid 眼镜（本 App）
  相机 CameraX ─▶ MediaCodec H.264 硬编（High profile）─▶ GL 旋转（传感器差 90°）
        └─▶ 常连 TCP（13 字节帧头）─▶ tsrelay（内置，用户态 Tailscale）
                 └─▶ RTMP 经你的 tailnet ─▶ MediaMTX ─▶ OBS / 录制 / 随便
```

## 为什么做这个

1. **固件会在开播时杀掉所有第三方 App。** 在 YodaOS-Sprite 上，只要系统直播场景一开，
   assistserver 就会 `ThirdAppScene -> forceStopPackage success: com.tailscale.ipn`。
   官方 Tailscale 客户端是 `VpnService`，被杀后 VPN 就断，推往 tailnet 地址的流几秒后必然失败。
2. **官方手机 App 离不开手机**，眼镜上没有状态显示，直播链路也没法控制码率、分辨率、接收端。

Rstream 两个问题一起解决：tailnet 那一跳由 **`tsnet`**（Tailscale 的用户态实现）在
一个小中继里完成，而中继**以 shell 进程身份运行** —— 固件的"按包名清杀"抓不到它；
App 本身就是一个普通的前台服务，带两行 HUD 和触控板操作。

## 特性

- 硬件 H.264（MediaCodec，High profile），720×1280 / 25–30fps，CBR 最高 6Mbps
- GPU 旋转（CameraX 给的是**未旋转**的 buffer，而这台眼镜的传感器是转 90° 装的）
- 常连 TCP 传输（每帧一次 HTTP POST 在这台机器上要 47ms/帧并大量丢帧，表现就是
  "静止清晰、一动就花"）
- 内置中继：用 auth key 加入你的 tailnet 并推 RTMP；开机自动拉起，**不需要 adb**
- 看门狗：App 被 lowmemorykiller 杀掉时，中继会把它重新拉起来
- 码率五档 **自动 / 6.0M / 2.5M / 800k / 256k**（低档位同时降分辨率），
  在眼镜上用"双指滑动 + 单击"选择
- 极简两行 HUD：状态圆点、延迟天线格、链路状态、电量 + 充电标识

## 在 RG-glasses（Android 12 / API 32）上的实测

| 档位 | 编码 | 实测 | 流量 |
|---|---|---|---|
| 自动（局域网） | 720×1280 @ 6Mbps | 30fps、0 丢帧、~5.8Mbps、往返 1ms | ~2.7GB/时 |
| 2.5M | 720×1280 @ 2.5Mbps | 30fps、0 丢帧 | ~1.1GB/时 |
| 800k | 540×960 @ 800kbps | 30fps、0 丢帧、~570kbps | ~360MB/时 |
| 256k | 360×640 @ 256kbps | 30fps、0 丢帧、~240kbps | ~115MB/时 |

## 需要准备

- Rokid 眼镜（RG-glasses）。其它 Android 12 带摄像头的设备理论上也行。
- tailnet 里有一台跑 [MediaMTX](https://github.com/bluenviron/mediamtx) 的服务器，
  开启 RTMP（`rtmp: yes`，端口 1935）—— 或者任何从 tailnet 能连到的 RTMP 服务器。
- 一个 **Reusable** 的 Tailscale auth key（`login.tailscale.com/admin/settings/keys`）。

## 构建

```bash
# 1) 中继（Go）—— 交叉编译进 App 的 jniLibs
cd relay && GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" \
    -o ../app/src/main/jniLibs/arm64-v8a/libtsrelay.so .

# 2) App
cd ..
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # JDK 17 即可
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.takano.rstream android.permission.CAMERA
```

> 必须开 `jniLibs { useLegacyPackaging = true }`：AGP 默认 `extractNativeLibs=false`，
> `.so` 会留在 APK 内部，没法执行。

## 配置

把设置放到眼镜上（不用重新编译）：

```bash
adb shell mkdir -p /sdcard/rstream
cat > config.txt <<'EOF'
# 中继要推的 RTMP 目标
publish=rtmp://your-server:1935/rokid
# 可选：额外经 tailnet 转发的端口
maps=0.0.0.0:1935=your-server:1935,127.0.0.1:9997=your-server:9997
# Reusable 的 Tailscale auth key（也可以单独放 /sdcard/rstream/tskey.txt）
tskey=tskey-auth-xxxxxxxxxxxxxxxxxxxx-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
EOF
adb push config.txt /sdcard/rstream/config.txt
```

App 把帧发到 `127.0.0.1:8900`（内置中继的原始 TCP 收流口）—— 在中继按 `publish=` 推出去之前，
数据不出设备。

## 操作（眼镜触控板）

| 手势 | 作用 |
|---|---|
| 单指单击 | 执行当前焦点上的操作（开始/停止、展开菜单、选档） |
| 单指双击 | 退出 |
| 双指前滑/后滑 | 在左下角 **开关** 和右上角 **码率** 之间移动焦点；菜单展开时在五档之间移动 |

HUD 只有最下面两行：`● 直播中 / ■ 停止`、延迟天线格 + `延迟 好/差`、`链路 已连接/断开`、
电量 + 充电闪电；右侧 `单击开关` / `双击退出`。

## 踩坑清单（花了我们几天的东西）

- **"开播 10 秒就断"** → 固件的 `ThirdAppScene` 把 VPN App 杀了。要用 shell 进程里的中继，
  别用 `VpnService`。
- **"静止清晰、一动就花"** → 丢帧打断了 H.264 参考帧链。别每帧发一次 HTTP（47ms/帧 + 队列只有 1
  ⇒ 丢 40%）。用常连 socket + 小队列。
- **画面躺着 / 被拉宽** → 这台机器上 `SurfaceTexture.getTransformMatrix()` 返回的是 **transpose**，
  而 CameraX 给的是未旋转的 buffer；要在 GL 里转，并且编码器的宽高要按"净变换"定，不能只看传感器方向。
- **中继被 App 拉起时 panic `no safe place found to store log state`** → 要给子进程设
  `TS_LOGS_DIR`（和 `HOME`）到 App 可写目录。
- **内存压力下 App 被杀**（2GB 机器，~107MB RSS）→ 中继保持 shell 进程身份并负责把 App 拉回来；
  顺便把 App 加进电池白名单。

## 目录结构

```
app/                      Android App（Kotlin）
  src/main/java/com/takano/rstream/
    MainActivity.kt       HUD、触控板手势（双指滑动是有序广播）
    StatusHudView.kt      两行符号 HUD + 码率菜单
    StreamService.kt      前台服务：相机 → 编码 → socket，中继保活
    GlRotator.kt          SurfaceTexture → GL 旋转 → 编码器输入 Surface
    StreamConfig.kt       配置（intent extra、prefs、/sdcard/rstream/config.txt）
relay/                    Go 中继（tsnet + gortmplib 推 RTMP）
```

## 致谢

- [MediaMTX](https://github.com/bluenviron/mediamtx) 与 `gortmplib` / `mediacommon`（bluenviron）—— RTMP 侧
- [Tailscale](https://tailscale.com) `tsnet` —— 用户态组网
- Rokid 的 `GlassesBareDevSample` —— 触控板/手势语义

## 许可

MIT，见 [LICENSE](LICENSE)。
