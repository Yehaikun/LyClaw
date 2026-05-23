#!/bin/bash
# 启动/停止 Grafana
# Usage: ./start-grafana.sh [start|stop|status]

GRAFANA_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$GRAFANA_DIR/grafana.pid"

start() {
    if [ -f "$PID_FILE" ] && kill -0 $(cat "$PID_FILE") 2>/dev/null; then
        echo "[grafana] already running (pid $(cat $PID_FILE)) → http://localhost:3000"
        return
    fi
    echo -n "[grafana] starting... "
    nohup "$GRAFANA_DIR/bin/grafana" server \
        --config "$GRAFANA_DIR/conf/defaults.ini" \
        --homepath "$GRAFANA_DIR" \
        --configOverrides "paths.data=$GRAFANA_DIR/data;paths.logs=$GRAFANA_DIR/logs;server.http_port=3000;auth.anonymous.enabled=true" \
        > "$GRAFANA_DIR/grafana.log" 2>&1 &
    echo $! > "$PID_FILE"
    sleep 3
    if kill -0 $(cat "$PID_FILE") 2>/dev/null; then
        echo "ok (pid $(cat $PID_FILE), http://localhost:3000)"
    else
        echo "FAILED — check $GRAFANA_DIR/grafana.log"
    fi
}

stop() {
    if [ -f "$PID_FILE" ]; then
        pid=$(cat "$PID_FILE")
        if kill -0 $pid 2>/dev/null; then
            echo "[grafana] stopping pid $pid..."
            kill $pid 2>/dev/null
        fi
        rm -f "$PID_FILE"
    fi
}

status() {
    if [ -f "$PID_FILE" ] && kill -0 $(cat "$PID_FILE") 2>/dev/null; then
        echo "  Grafana: RUNNING (pid $(cat $PID_FILE), http://localhost:3000)"
    else
        echo "  Grafana: stopped"
    fi
}

case "${1:-start}" in
    start)  start ;;
    stop)   stop ;;
    status) status ;;
    *)      echo "Usage: $0 {start|stop|status}" ;;
esac
