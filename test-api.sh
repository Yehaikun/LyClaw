#!/bin/bash
# LyClaw API 集成测试脚本 — 打印每个接口的完整响应
# 用法: ./test-api.sh [base_url]
# 默认 base_url=http://localhost:8082

BASE="${1:-http://localhost:8082}"
PASS=0
FAIL=0
AGENT="chat"
SESSION_ID=""

red()   { echo -e "\033[31m$1\033[0m"; }
green() { echo -e "\033[32m$1\033[0m"; }
cyan()  { echo -e "\033[36m$1\033[0m"; }
bold()  { echo -e "\033[1m$1\033[0m"; }

divider() {
    echo ""
    echo "──────────────────────────────────────────────"
    echo ""
}

print_json() {
    local body="$1"
    if [ -z "$body" ]; then
        echo "  (空响应)"
    elif echo "$body" | python3 -m json.tool 2>/dev/null; then
        : # python3 already printed
    else
        echo "  $body"
    fi
}

assert_status() {
    local desc="$1" expected="$2" actual="$3"
    if [ "$actual" = "$expected" ]; then
        green "  PASS: $desc (HTTP $actual)"
        PASS=$((PASS + 1))
    else
        red "  FAIL: $desc — expected HTTP $expected, got $actual"
        FAIL=$((FAIL + 1))
    fi
}

echo ""
bold  "══════════════════════════════════════════════"
bold  "  LyClaw API 集成测试"
echo "  Base URL: $BASE"
echo "  Agent:    $AGENT"
bold  "══════════════════════════════════════════════"

# ════════════════════════════════════════════════════
# 1. 获取 Agent 列表
# ════════════════════════════════════════════════════
divider
cyan "1. GET /api/agents"
RESP=$(curl -s -w "\n%{http_code}" "$BASE/api/agents")
HTTP=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
bold "  响应:"
print_json "$BODY"
assert_status "HTTP 200" 200 "$HTTP"

# ════════════════════════════════════════════════════
# 2. 获取会话列表
# ════════════════════════════════════════════════════
divider
cyan "2. GET /api/agents/$AGENT/sessions"
RESP=$(curl -s -w "\n%{http_code}" "$BASE/api/agents/$AGENT/sessions")
HTTP=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
bold "  响应:"
print_json "$BODY"
assert_status "HTTP 200" 200 "$HTTP"

# ════════════════════════════════════════════════════
# 3. 创建会话
# ════════════════════════════════════════════════════
divider
cyan "3. POST /api/agents/$AGENT/sessions"
RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/agents/$AGENT/sessions" \
    -H 'Content-Type: application/json' \
    -d '{"model":"deepseek-4-pro"}')
HTTP=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
bold "  响应:"
print_json "$BODY"
assert_status "HTTP 200" 200 "$HTTP"
SESSION_ID=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['sessionId'])" 2>/dev/null || echo "")
if [ -n "$SESSION_ID" ]; then
    green "  sessionId = $SESSION_ID"
else
    red "  未能提取 sessionId"
    FAIL=$((FAIL + 1))
fi

# ════════════════════════════════════════════════════
# 4. 获取单个会话
# ════════════════════════════════════════════════════
if [ -n "$SESSION_ID" ]; then
    divider
    cyan "4. GET /api/agents/$AGENT/sessions/$SESSION_ID"
    RESP=$(curl -s -w "\n%{http_code}" "$BASE/api/agents/$AGENT/sessions/$SESSION_ID")
    HTTP=$(echo "$RESP" | tail -1)
    BODY=$(echo "$RESP" | sed '$d')
    bold "  响应:"
    print_json "$BODY"
    assert_status "HTTP 200" 200 "$HTTP"
fi

# ════════════════════════════════════════════════════
# 5. 非流式聊天
# ════════════════════════════════════════════════════
divider
cyan "5. POST /api/chat?agentId=$AGENT  (非流式)"
bold "  请求体: {\"sessionId\":\"$SESSION_ID\",\"messages\":[{\"role\":\"user\",\"content\":\"1+1=?\"}]}"
RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/chat?agentId=$AGENT" \
    -H 'Content-Type: application/json' \
    -d "{\"sessionId\":\"$SESSION_ID\",\"messages\":[{\"role\":\"user\",\"content\":\"1+1=? answer in one word\"}],\"stream\":false}")
HTTP=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
bold "  响应:"
print_json "$BODY"
assert_status "HTTP 200" 200 "$HTTP"

# ════════════════════════════════════════════════════
# 6. 流式聊天 (SSE)
# ════════════════════════════════════════════════════
divider
cyan "6. POST /api/chat/stream?agentId=$AGENT  (SSE流式)"
bold "  请求体: {\"sessionId\":\"$SESSION_ID\",\"messages\":[{\"role\":\"user\",\"content\":\"say hi\"}]}"
echo ""
SSE_OUT=$(curl -s -N -X POST "$BASE/api/chat/stream?agentId=$AGENT" \
    -H 'Content-Type: application/json' \
    -H 'Accept: text/event-stream' \
    -d "{\"sessionId\":\"$SESSION_ID\",\"messages\":[{\"role\":\"user\",\"content\":\"say hi\"}],\"stream\":true}" 2>&1 || true)

# 给SSE事件着色打印
while IFS= read -r line; do
    if [[ "$line" == event:* ]]; then
        cyan "  $line"
    elif [[ "$line" == data:* ]]; then
        echo "  $line" | sed 's/^data:/  📦 data:/'
    elif [[ "$line" == "" ]]; then
        echo ""
    else
        echo "  $line"
    fi
done <<< "$SSE_OUT"

if echo "$SSE_OUT" | grep -q "session_created"; then
    green "  PASS: session_created 事件已收到"
    PASS=$((PASS + 1))
else
    red "  FAIL: 缺少 session_created 事件"
    FAIL=$((FAIL + 1))
fi

# ════════════════════════════════════════════════════
# 7. 获取消息历史
# ════════════════════════════════════════════════════
if [ -n "$SESSION_ID" ]; then
    divider
    cyan "7. GET /api/agents/$AGENT/sessions/$SESSION_ID/messages"
    RESP=$(curl -s -w "\n%{http_code}" "$BASE/api/agents/$AGENT/sessions/$SESSION_ID/messages")
    HTTP=$(echo "$RESP" | tail -1)
    BODY=$(echo "$RESP" | sed '$d')
    bold "  响应 (最近消息):"
    print_json "$BODY"
    assert_status "HTTP 200" 200 "$HTTP"
fi

# ════════════════════════════════════════════════════
# 8. 删除会话
# ════════════════════════════════════════════════════
if [ -n "$SESSION_ID" ]; then
    divider
    cyan "8. DELETE /api/agents/$AGENT/sessions/$SESSION_ID"
    RESP=$(curl -s -w "\n%{http_code}" -X DELETE "$BASE/api/agents/$AGENT/sessions/$SESSION_ID")
    HTTP=$(echo "$RESP" | tail -1)
    BODY=$(echo "$RESP" | sed '$d')
    bold "  响应:"
    print_json "$BODY"
    assert_status "HTTP 200" 200 "$HTTP"

    # 验证删除
    divider
    cyan "9. 验证删除: GET /api/agents/$AGENT/sessions/$SESSION_ID"
    RESP2=$(curl -s -w "\n%{http_code}" "$BASE/api/agents/$AGENT/sessions/$SESSION_ID")
    HTTP2=$(echo "$RESP2" | tail -1)
    BODY2=$(echo "$RESP2" | sed '$d')
    bold "  响应:"
    print_json "$BODY2"
    if [ "$HTTP2" = "404" ] || [ "$BODY2" = "null" ] || [ -z "$BODY2" ]; then
        green "  PASS: 会话已删除 (HTTP $HTTP2)"
        PASS=$((PASS + 1))
    else
        red "  FAIL: 删除后会话仍可获取 (HTTP $HTTP2)"
        FAIL=$((FAIL + 1))
    fi
fi

# ════════════════════════════════════════════════════
# 汇总
# ════════════════════════════════════════════════════
divider
bold  "══════════════════════════════════════════════"
echo "  通过: $PASS     失败: $FAIL"
bold  "══════════════════════════════════════════════"
echo ""

if [ "$FAIL" -gt 0 ]; then
    red "存在 $FAIL 个失败用例，请检查后端日志"
    exit 1
else
    green "全部 $PASS 个用例通过"
    exit 0
fi
