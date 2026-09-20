# 【开源】Rstream：把 Rokid 眼镜变成你自己的直播摄像头（含绕开"系统杀第三方 App"的完整方案）

## 先说我踩到的坑

用官方手机 App 的「RTMP 自定义直播」把眼镜画面推到自己服务器，一切配好后发现：

**每次开播 10 秒左右推流就自己停了。**

查眼镜 logcat 找到真凶：

```
ThirdAppScene -> forceStopPackage success: com.tailscale.ipn
ThirdAppScene -> forceStopPackage success: com.takano.tshud
```

也就是说：**手机 App 一发"开始直播"指令，眼镜的 assistserver 会把所有第三方 App 强制杀掉**（腾资源给相机+编码）。官方 Tailscale 客户端是靠 `VpnService` 提供系统 VPN 的，被杀了 VPN 就断，推往 tailnet 地址的 RTMP 必然失败。

顺带一提，眼镜只有 2GB 内存，Tailscale 常驻 130MB，lowmemorykiller 平时也会挑它。

## 我的方案

眼镜上装一个自己的 App（Rstream），整条链路都在眼镜里完成：

```
相机(CameraX) → MediaCodec H.264 硬编(High profile) → GL 旋转
      → 常连 TCP(13 字节帧头) → 内置中继(Go, tsnet 用户态 Tailscale)
            → RTMP 经 tailnet → MediaMTX（自己的服务器）→ OBS/录制/随便
```

几个关键点：

1. **中继用 `tsnet`（Tailscale 用户态实现），并且以 shell 进程身份运行**。
   系统"清第三方 App"是按包名杀的，抓不到 shell 进程；而且不需要 VpnService、不需要 root。
   App 把中继二进制当 `libtsrelay.so` 打包进 APK，开机广播拉起，**完全不用 adb**。
2. **看门狗**：App 被 lowmemorykiller 干掉时（2GB 机器上会），中继发现"连接断了且没收到主动停止信号"，就 `am start` 把 App 拉回来。实测 22 秒自动恢复。
3. **别用"每帧一次 HTTP POST"送流**。实测 `HttpURLConnection` 每帧要等响应、47ms/帧，加上队列只有 1 帧，20fps 里每秒丢 8 帧；H.264 参考帧链一断，表现就是**"静止清晰、一动就花、停下来又变清楚"**。改成常连 TCP（13 字节帧头）后：**30fps / 0 丢帧 / 往返 1ms**。
4. **画面是躺着的**：`SurfaceTexture.getTransformMatrix()` 在这台机器上返回的是 **transpose**，而 CameraX 交给 SurfaceProvider 的是**未旋转**的 buffer。所以要在 GL 里自己转，并且编码器的宽高要按"净变换"来定，否则会被横向拉宽。
5. **码率五档**：自动（在家 6M / 出门 2.5M）/ 6.0M / 2.5M / 800k / 256k，低档位同时降分辨率（256k 配 360×640）。触控板双指前后滑切焦点、单击进入菜单选择。

## 实测数据（RG-glasses，Android 12 / API 32）

| 档位 | 编码 | 实测 | 流量 |
|---|---|---|---|
| 自动（局域网） | 720×1280 @6Mbps | 30fps / 0 丢帧 / ~5.8Mbps / 1ms | ~2.7GB/时 |
| 2.5M | 720×1280 @2.5Mbps | 30fps / 0 丢帧 | ~1.1GB/时 |
| 800k | 540×960 @800kbps | 30fps / 0 丢帧 / ~570kbps | ~360MB/时 |
| 256k | 360×640 @256kbps | 30fps / 0 丢帧 / ~240kbps | ~115MB/时 |

HUD 只有屏幕最下面两行：状态圆点/方块、延迟天线格、链路环、电量+充电标识；右上角是码率框（可滑动选中、点击展开五档）。

## 开源地址

**https://github.com/jackytianjp/Rstream** （MIT）

包含 Android App（Kotlin）+ Go 中继源码 + 详细的踩坑清单。需要你自己准备：
- 一个 tailnet（生成 reusable auth key）
- 一台能连上的服务器跑 MediaMTX（RTMP :1935）

配置放在眼镜上 `/sdcard/rstream/config.txt`（服务器地址 + auth key），不用重新编译。

## 求反馈

- 有没有更省流量的编码参数组合？（低码率下 720p 我试过，糊得没法看，所以降了分辨率）
- 触控板手势还有没有更好的交互方式？
- 有人试过在眼镜上直接跑 MediaMTX（省掉中继）吗？

欢迎 fork / issue / 拍砖。
