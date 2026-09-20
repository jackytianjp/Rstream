#!/system/bin/sh
# Start the relay manually (the app normally starts it for you).
# Values come from environment variables, or from /sdcard/rstream/config.txt.
DIR=/data/local/tmp/tsrelay
CFG=/sdcard/rstream/config.txt

[ -z "$TS_AUTHKEY" ] && [ -f "$CFG" ] && TS_AUTHKEY=$(sed -n 's/^tskey=//p' "$CFG")
[ -z "$PUBLISH" ] && [ -f "$CFG" ] && PUBLISH=$(sed -n 's/^publish=//p' "$CFG")
[ -z "$MAPS" ] && [ -f "$CFG" ] && MAPS=$(sed -n 's/^maps=//p' "$CFG")

[ -z "$TS_AUTHKEY" ] && { echo "missing Tailscale auth key (set TS_AUTHKEY or tskey= in $CFG)"; exit 1; }
[ -z "$PUBLISH" ] && { echo "missing PUBLISH (rtmp://your-server:1935/rokid)"; exit 1; }

echo -500 > /proc/self/oom_score_adj 2>/dev/null

while true; do
  echo "[supervisor] $(date) starting tsrelay"
  TS_AUTHKEY="$TS_AUTHKEY" \
  TS_STATE="$DIR/state" \
  TS_HOSTNAME="${TS_HOSTNAME:-glasses-relay}" \
  TS_LOGS_DIR="$DIR/logs" \
  MAPS="$MAPS" \
  INGEST="0.0.0.0:8899" \
  RAW_INGEST="0.0.0.0:8900" \
  PUBLISH="$PUBLISH" \
  "$DIR/tsrelay" >> "$DIR/relay.log" 2>&1
  echo "[supervisor] tsrelay exited ($?), restarting in 3s"
  sleep 3
done
