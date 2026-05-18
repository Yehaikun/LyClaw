#!/bin/bash
# LyClaw HTTP 接口测试脚本
# 用法: ./http-test.sh [base-url]  默认 http://localhost:28080
#
# 先启动 lyclaw-web:
#   mvn -pl lyclaw-web spring-boot:run
# 然后:
#   ./http-test.sh

BASE="${1:-http://localhost:28080}"
API="${BASE}/api"
PASS=0
FAIL=0
SESSION=""

green() { printf "\033[32m%s\033[0m" "$1"; }
red()   { printf "\033[31m%s\033[0m" "$1"; }

check() {
    local label="$1" expected="$2" actual="$3"
    if echo "$actual" | grep -q "$expected"; then
        green "  PASS"; echo "  $label"
        PASS=$((PASS + 1))
    else
        red "  FAIL"; echo "  $label (expected: $expected)"
        echo "  got: ${actual:0:200}"
        FAIL=$((FAIL + 1))
    fi
}

echo "=========================================="
echo "  LyClaw HTTP 接口测试"
echo "  Target: $API"
echo "=========================================="
echo ""

# ── 1. 创建会话 ──
echo "[1] POST /api/sessions — 创建会话"
RESP=$(curl -s -w "\n%{http_code}" -X POST "$API/sessions" \
    -H "Content-Type: application/json" \
    -d '{"messages":[{"role":"user","content":"init"}]}')
HTTP_CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
SESSION=$(echo "$BODY" | grep -o '"sessionId"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed 's/.*"\([^"]*\)"$/\1/')

if [ "$HTTP_CODE" = "200" ] && [ -n "$SESSION" ]; then
    green "  PASS"; echo "  会话创建成功: $SESSION"
    PASS=$((PASS + 1))
else
    red "  FAIL"; echo "  会话创建失败 HTTP=$HTTP_CODE"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── 2. 非流式聊天 ──
echo "[2] POST /api/chat — 非流式聊天"
RESP=$(curl -s -X POST "$API/chat" \
    -H "Content-Type: application/json" \
    -d "{\"sessionId\":\"$SESSION\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello, introduce yourself briefly\"}]}")
check "返回内容" "content\|assistant\|tool\|response" "$RESP"
echo "  Response preview: ${RESP:0:150}"
echo ""

# ── 3. 流式聊天 (SSE) ──
echo "[3] POST /api/chat/stream — 流式聊天"
SSE_OUT=$(curl -s -N --max-time 30 -X POST "$API/chat/stream" \
    -H "Content-Type: application/json" \
    -H "Accept: text/event-stream" \
    -d "{\"sessionId\":\"$SESSION\",\"messages\":[{\"role\":\"user\",\"content\":\"say hello in one sentence\"}]}" 2>&1)

if echo "$SSE_OUT" | grep -q "data:"; then
    green "  PASS"; echo "  SSE 流包含 data: 事件"
    PASS=$((PASS + 1))
else
    red "  FAIL"; echo "  SSE 流无 data: 事件"
    FAIL=$((FAIL + 1))
fi
echo "  SSE preview: ${SSE_OUT:0:200}"
echo ""

# ── 4. 计算查询 ──
echo "[4] POST /api/chat — 计算查询"
RESP=$(curl -s -X POST "$API/chat" \
    -H "Content-Type: application/json" \
    -d "{\"sessionId\":\"$SESSION\",\"messages\":[{\"role\":\"user\",\"content\":\"what is 2+2?\"}]}")
check "响应非空" "." "$RESP"
echo "  Response: ${RESP:0:150}"
echo ""

# ── 5. 获取会话 ──
echo "[5] GET /api/sessions/{sessionId} — 获取会话"
RESP=$(curl -s "$API/sessions/$SESSION")
check "返回会话信息" "sessionId\|messages" "$RESP"
echo "  Session: ${RESP:0:150}"
echo ""

# ── 6. 健康检查 (actuator) ──
echo "[6] GET /actuator/health — 健康检查"
RESP=$(curl -s "$BASE/actuator/health" 2>&1)
if echo "$RESP" | grep -q '"status"'; then
    green "  PASS"; echo "  健康检查正常"
    PASS=$((PASS + 1))
else
    echo "  INFO: actuator 未启用或不可达 (非关键)"
fi
echo ""

# ── 结果汇总 ──
echo "=========================================="
printf "  通过: "; green "$PASS"; echo ""
printf "  失败: "; red "$FAIL"; echo ""
echo "=========================================="

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
