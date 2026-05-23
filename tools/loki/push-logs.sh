#!/bin/bash
# 轻量日志推送器 — tail JSONL 日志文件并逐行推送到 Loki
# 开发环境替代 Promtail，无需额外依赖
# Usage: ./push-logs.sh [log_dir]
#   日志文件名格式: YYYY-MM-DD.jsonl，跨天自动切换

LOKI_URL="${LOKI_URL:-http://localhost:3100}"
LOG_DIR="${1:-logs}"
JOB_NAME="${JOB_NAME:-lyclaw}"
BATCH_SIZE="${BATCH_SIZE:-50}"
FLUSH_SECONDS="${FLUSH_SECONDS:-5}"

batch=''
count=0
last_flush=$(date +%s)

push_batch() {
    if [ -z "$batch" ]; then return; fi
    payload="{\"streams\":[{\"stream\":{\"job\":\"$JOB_NAME\"},\"values\":[$batch]}]}"
    curl -s --max-time 5 -X POST "$LOKI_URL/loki/api/v1/push" \
        -H "Content-Type: application/json" \
        -d "$payload" -o /dev/null
    batch=''
    count=0
}

while true; do
    today="$(date +%Y-%m-%d)"
    logfile="$LOG_DIR/$today.jsonl"

    # 等待日志文件出现
    waited=0
    while [ ! -f "$logfile" ] && [ $waited -lt 30 ]; do
        sleep 2; waited=$((waited + 2))
    done

    if [ -f "$logfile" ]; then
        echo "[push-logs] $(date '+%Y-%m-%d %H:%M:%S') watching $logfile → $LOKI_URL"
    else
        echo "[push-logs] $(date '+%Y-%m-%d %H:%M:%S') waiting for $logfile ..."
        sleep 10
        continue
    fi

    # 用进程替换避免管道子 shell，跨天时 break 退出内层循环
    while IFS= read -r line; do
        [ -z "$line" ] && continue

        now_ns="$(date +%s)000000000"
        escaped=$(printf '%s' "$line" | jq -Rs '.' 2>/dev/null)
        [ -z "$escaped" ] && continue

        if [ -z "$batch" ]; then
            batch="[\"$now_ns\",$escaped]"
        else
            batch="$batch,[\"$now_ns\",$escaped]"
        fi
        count=$((count + 1))

        now=$(date +%s)
        if [ $count -ge "$BATCH_SIZE" ] || [ $((now - last_flush)) -ge "$FLUSH_SECONDS" ]; then
            push_batch
            last_flush=$now
        fi

        # 跨天了：推掉余量，退出内层让外层重新计算文件名
        if [ "$(date +%Y-%m-%d)" != "$today" ]; then
            push_batch
            last_flush=$(date +%s)
            break
        fi
    done < <(tail -n0 -F "$logfile" 2>/dev/null)

    # tail 进程可能因文件轮转而退出，推掉余量然后重连
    push_batch
    last_flush=$(date +%s)
    sleep 1
done
