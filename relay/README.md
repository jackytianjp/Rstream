# tsrelay

Small Go relay that runs on the glasses:

1. **raw TCP ingest** (default `0.0.0.0:8900`) — 13-byte frame header
   (`4B length | 1B flags (bit0 = keyframe, 0x02 = client bye) | 8B timestamp µs`) + Annex-B
   H.264 access unit. A persistent socket; no per-frame HTTP.
2. **HTTP ingest** (`0.0.0.0:8899`, `POST /h264`) — same payload with `X-Key` / `X-Frame-Ts`
   headers, kept for convenience.
3. **TCP forwards** (`MAPS="listen=target,..."`) through the tailnet.
4. **RTMP publish** — joins your tailnet with `tsnet` (userspace Tailscale: no VpnService, no
   root, and the firmware's per-package cleanup cannot kill a shell process) and publishes to
   `PUBLISH` using `gortmplib`.
5. **Watchdog** — if the app dies while streaming (no frames for 20 s, no `bye`), it runs
   `am start` to bring it back.

Environment: `TS_AUTHKEY`, `TS_STATE`, `TS_HOSTNAME`, `TS_LOGS_DIR`, `MAPS`, `INGEST`,
`RAW_INGEST`, `PUBLISH`. Build for the glasses with:

```bash
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o libtsrelay.so .
```
