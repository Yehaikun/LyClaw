#!/bin/bash
# 启动 Loki 日志平台 + 日志推送器
# Usage: ./start-loki.sh [start|stop|status]
# 环境变量:
#   LOKI_BIN       Loki 二进制路径（默认 /home/lyjew/tools/loki/loki-linux-amd64）
#   PROJECT_DIR    项目根目录（默认脚本所在目录的 ../..）
#   DATA_DIR       数据/日志存放目录（默认 /home/lyjew/tools/loki）

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="${PROJECT_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
LOKI_BIN="${LOKI_BIN:-/home/lyjew/tools/loki/loki-linux-amd64}"
DATA_DIR="${DATA_DIR:-/home/lyjew/tools/loki}"
LOKI_CONFIG="$SCRIPT_DIR/loki-config.yml"
LOKI_PID_FILE="$DATA_DIR/loki.pid"
PUSHER_PID_FILE="$DATA_DIR/pusher.pid"
LOG_DIR="$PROJECT_DIR/logs"

start_loki() {
    if [ -f "$LOKI_PID_FILE" ] && kill -0 $(cat "$LOKI_PID_FILE") 2>/dev/null; then
        echo "[loki] already running (pid $(cat $LOKI_PID_FILE))"
        return
    fi
    echo -n "[loki] starting... "
    nohup "$LOKI_BIN" -config.file="$LOKI_CONFIG" > "$DATA_DIR/loki.log" 2>&1 &
    echo $! > "$LOKI_PID_FILE"
    sleep 2
    if kill -0 $(cat "$LOKI_PID_FILE") 2>/dev/null; then
        echo "ok (pid $(cat $LOKI_PID_FILE), http://localhost:3100)"
    else
        echo "FAILED — check $DATA_DIR/loki.log"
    fi
}

start_pusher() {
    if [ -f "$PUSHER_PID_FILE" ] && kill -0 $(cat "$PUSHER_PID_FILE") 2>/dev/null; then
        echo "[pusher] already running (pid $(cat $PUSHER_PID_FILE))"
        return
    fi
    echo -n "[pusher] starting... "
    nohup "$SCRIPT_DIR/push-logs.sh" "$LOG_DIR" > "$DATA_DIR/pusher.log" 2>&1 &
    echo $! > "$PUSHER_PID_FILE"
    sleep 1
    if kill -0 $(cat "$PUSHER_PID_FILE") 2>/dev/null; then
        echo "ok (pid $(cat $PUSHER_PID_FILE), watching $LOG_FILE)"
    else
        echo "FAILED — check $DATA_DIR/pusher.log"
    fi
}

stop_all() {
    for f in "$LOKI_PID_FILE" "$PUSHER_PID_FILE"; do
        if [ -f "$f" ]; then
            pid=$(cat "$f")
            if kill -0 $pid 2>/dev/null; then
                echo "stopping pid $pid..."
                kill $pid 2>/dev/null
            fi
            rm -f "$f"
        fi
    done
}

status() {
    echo "=== Loki Platform Status ==="
    for s in "Loki:$LOKI_PID_FILE" "Pusher:$PUSHER_PID_FILE"; do
        name="${s%%:*}" file="${s##*:}"
        if [ -f "$file" ] && kill -0 $(cat "$file") 2>/dev/null; then
            echo "  $name: RUNNING (pid $(cat $file))"
        else
            echo "  $name: stopped"
        fi
    done
    echo ""
    if command -v curl >/dev/null 2>&1; then
        echo -n "  Loki ready: "
        curl -s --max-time 2 http://localhost:3100/ready 2>/dev/null || echo "no"
    fi
}

case "${1:-start}" in
    start)   start_loki; start_pusher; status ;;
    stop)    stop_all ;;
    status)  status ;;
    *)       echo "Usage: $0 {start|stop|status}" ;;
esac
