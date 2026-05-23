#!/bin/bash
# Start Prometheus for LyClaw metrics scraping
# Prometheus scrapes /actuator/prometheus from the LyClaw backend

PROM_DIR="$(dirname "$(readlink -f "$0")")"
DATA_DIR="/home/lyjew/tools/prometheus/data"
CONFIG="$PROM_DIR/prometheus.yml"

mkdir -p "$DATA_DIR"

# Kill existing
pkill -f "prometheus.*prometheus.yml" 2>/dev/null || true
sleep 1

echo "[prometheus] Starting Prometheus..."
nohup "$PROM_DIR/prometheus" \
    --config.file="$CONFIG" \
    --storage.tsdb.path="$DATA_DIR" \
    --web.listen-address=":9090" \
    > "$PROM_DIR/prometheus.log" 2>&1 &

sleep 2
if pgrep -f "prometheus.*prometheus.yml" > /dev/null; then
    echo "[prometheus] Prometheus started successfully on :9090"
    echo "[prometheus] Scraping http://localhost:8082/actuator/prometheus every 15s"
else
    echo "[prometheus] WARNING: Prometheus may have failed to start. Check $PROM_DIR/prometheus.log"
fi
