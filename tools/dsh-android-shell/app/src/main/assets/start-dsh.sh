#!/system/bin/sh
# dsh starter for Android shell
# Usage: sh start-dsh.sh --port <port>

PORT=3080
while [ $# -gt 0 ]; do
  case "$1" in
    --port)
      PORT="$2"
      shift 2
      ;;
    *)
      shift
      ;;
  esac
done

LOG=/data/local/tmp/dsh_web.log
export PATH="$PATH:/data/data/com.termux/files/usr/bin"

DSH_BIN=""
if command -v dsh >/dev/null 2>&1; then
  DSH_BIN="dsh"
elif [ -x /data/data/com.termux/files/usr/bin/dsh ]; then
  DSH_BIN="/data/data/com.termux/files/usr/bin/dsh"
fi

if [ -z "$DSH_BIN" ]; then
  echo "ERR: dsh not found. Install Node.js and dsh in Termux first."
  exit 1
fi

# 记录旧进程 PID，方便排查端口占用
OLDPID=$(cat /data/local/tmp/dsh_web.pid 2>/dev/null)
if [ -n "$OLDPID" ] && kill -0 "$OLDPID" >/dev/null 2>&1; then
  kill "$OLDPID" 2>/dev/null
  sleep 1
fi

(
  "$DSH_BIN" web --host 127.0.0.1 --port "$PORT" > "$LOG" 2>&1 &
  echo $! > /data/local/tmp/dsh_web.pid
)

echo "OK: dsh web starting on 127.0.0.1:$PORT (pid file: /data/local/tmp/dsh_web.pid)"
