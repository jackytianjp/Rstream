# Rstream

**English** | [中文](README.zh-CN.md)

Turn **Rokid Glasses** into a self-hosted live camera: the glasses capture, encode and push
H.264 to *your own* RTMP server over Tailscale — no system VPN, no phone, no vendor cloud.

```
Rokid Glasses (this app)
  CameraX ─▶ MediaCodec H.264 (High profile) ─▶ GL rotation (sensor is 90° off)
        └─▶ persistent TCP (13-byte frame header) ─▶ tsrelay (bundled, userspace Tailscale)
                 └─▶ RTMP over your tailnet ─▶ MediaMTX ─▶ OBS / recordings / anything
```

## Why this exists

1. **The firmware kills third-party apps when a live broadcast starts.** On YodaOS-Sprite the
   assist server does `ThirdAppScene -> forceStopPackage success: com.tailscale.ipn` the moment
   the system live scene opens. The official Tailscale client is a `VpnService`, so the VPN dies
   and a stream pushed to a tailnet address stops after a few seconds.
2. **The official phone app needs the phone**, has no status display on the glasses, and the
   firmware's live pipeline gives you no control over bitrate, resolution or the receiving end.

Rstream solves both: the tailnet hop is done by **`tsnet`** (Tailscale's userspace
implementation) inside a tiny relay that runs **as a shell process**, so the firmware's
per-package cleanup cannot see it — and the app itself is a normal foreground service with a
2-line HUD and touchpad control.

## Features

- Hardware H.264 (MediaCodec, High profile) at 720×1280 / 25–30 fps, CBR up to 6 Mbps
- GPU rotation (CameraX hands out *unrotated* buffers; the glasses' sensor is rotated 90°)
- Persistent TCP transport (a per-frame HTTP POST costs ~47 ms/frame on this device and drops
  frames, which shows up as "clean when still, garbage when moving")
- Bundled relay: joins your tailnet with an auth key, publishes RTMP; starts automatically on
  boot, no adb needed
- Watchdog: if the app is killed by the low-memory killer, the relay relaunches it
- Bitrate ladder **AUTO / 6.0M / 2.5M / 800k / 256k** (lower levels also lower the resolution),
  selectable on the glasses with two-finger swipes + tap
- Minimal 2-line HUD: status dot, latency bars, link state, battery + charging

## Verified on RG-glasses (Android 12 / API 32)

| Mode | Encoder | Measured | Data |
|---|---|---|---|
| AUTO (home LAN) | 720×1280 @ 6 Mbps | 30 fps, 0 dropped, ~5.8 Mbps, 1 ms RTT | ~2.7 GB/h |
| 2.5M | 720×1280 @ 2.5 Mbps | 30 fps, 0 dropped | ~1.1 GB/h |
| 800k | 540×960 @ 800 kbps | 30 fps, 0 dropped, ~570 kbps | ~360 MB/h |
| 256k | 360×640 @ 256 kbps | 30 fps, 0 dropped, ~240 kbps | ~115 MB/h |

## Requirements

- Rokid Glasses (RG-glasses). Other Android 12 devices with a camera should work too.
- A server in your tailnet running [MediaMTX](https://github.com/bluenviron/mediamtx) with RTMP
  enabled (`rtmp: yes`, port 1935) — or any RTMP server you can reach from the tailnet.
- A **reusable** Tailscale auth key (`login.tailscale.com/admin/settings/keys`).

## Build

```bash
# 1) relay (Go) — cross-compile into the app's jniLibs
cd relay && GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" \
    -o ../app/src/main/jniLibs/arm64-v8a/libtsrelay.so .

# 2) app
cd ..
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # any JDK 17
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.takano.rstream android.permission.CAMERA
```

> `jniLibs { useLegacyPackaging = true }` is required: with the AGP default
> (`extractNativeLibs=false`) the `.so` stays inside the APK and cannot be executed.

## Configure

Put your settings on the device (no rebuild needed):

```bash
adb shell mkdir -p /sdcard/rstream
cat > config.txt <<'EOF'
# RTMP target the relay publishes to
publish=rtmp://your-server:1935/rokid
# optional: extra TCP forwards through the tailnet
maps=0.0.0.0:1935=your-server:1935,127.0.0.1:9997=your-server:9997
# reusable Tailscale auth key (or put it in /sdcard/rstream/tskey.txt)
tskey=tskey-auth-xxxxxxxxxxxxxxxxxxxx-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
EOF
adb push config.txt /sdcard/rstream/config.txt
```

The app POSTs frames to `127.0.0.1:8900` (raw TCP ingest of the bundled relay) — nothing
leaves the device until the relay publishes to `publish=`.

## Use (glasses touchpad)

| Gesture | Action |
|---|---|
| single tap | activate the focused control (start/stop, open menu, pick level) |
| double tap | exit |
| two-finger swipe | move the focus between the **SWITCH** box (bottom-left) and the **BITRATE** box (top-right); while the menu is open, move through the 5 levels |

HUD (bottom two lines): `● LIVE / ■ STOP`, latency bars + `LAT GOOD/BAD`, `LINK CONNECTED/DOWN`,
battery + charging bolt. `TAP: TOGGLE` / `2xTAP: EXIT` on the right.

## Troubleshooting (things that cost us days)

- **Stream dies ~10 s after "start live"** → the firmware's `ThirdAppScene` force-stopped the
  VPN app. Use the relay (shell process), not a `VpnService`.
- **Clean when still, blocky when moving** → dropped frames break the H.264 reference chain.
  Do not POST one HTTP request per frame (47 ms/frame + a 1-deep queue ⇒ ~40 % loss). Use a
  persistent socket with a small queue.
- **Picture is sideways / stretched** → `SurfaceTexture.getTransformMatrix()` returns a
  *transpose* on this device, and CameraX gives unrotated buffers; rotate in GL and set the
  encoder's aspect from the net transform, not from the sensor orientation alone.
- **Relay panics `no safe place found to store log state`** when spawned by the app → set
  `TS_LOGS_DIR` (and `HOME`) to a writable app directory.
- **App gets killed under memory pressure** (2 GB device, ~107 MB RSS) → keep the relay as a
  shell process and let it relaunch the app; also whitelist the app for battery.

## Layout

```
app/                      Android app (Kotlin)
  src/main/java/com/takano/rstream/
    MainActivity.kt       HUD, touchpad gestures (ordered broadcasts for two-finger swipes)
    StatusHudView.kt      the 2-line symbol HUD + bitrate menu
    StreamService.kt      foreground service: camera → encoder → socket, relay supervision
    GlRotator.kt          SurfaceTexture → GL rotation → encoder surface
    StreamConfig.kt       config (intent extras, prefs, /sdcard/rstream/config.txt)
relay/                    Go relay (tsnet + RTMP publish via gortmplib)
```

## Credits

- [MediaMTX](https://github.com/bluenviron/mediamtx) and `gortmplib` / `mediacommon`
  (bluenviron) for the RTMP side
- [Tailscale](https://tailscale.com) `tsnet` for userspace networking
- Rokid's `GlassesBareDevSample` for the touchpad/gesture semantics

## License

MIT — see [LICENSE](LICENSE).
