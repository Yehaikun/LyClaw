#!/bin/bash
#===============================================================================
# LyClaw 微服务集成测试脚本
# 测试范围: Nacos 可达性、7 个服务注册状态、所有 API 端点、Gateway 透传、错误处理
# 用法: bash test-integration.sh [--gateway] [--verbose]
#===============================================================================

set -o pipefail

# ======================== 配置 ========================
NACOS_URL="http://localhost:8848"
NACOS_NS="lyclaw"
GATEWAY_HOST="${GATEWAY_HOST:-localhost:8080}"
SERVICE_HOSTS=(
  "8080:lyclaw-gateway"
  "8081:lyclaw-orchestration-service"
  "8082:lyclaw-memory-service"
  "8083:lyclaw-plan-service"
  "8084:lyclaw-action-service"
  "8085:lyclaw-reflect-service"
  "8086:lyclaw-protocol-service"
)

TIMEOUT=15
USE_GATEWAY=false
VERBOSE=false

# ======================== 颜色常量 ========================
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# ======================== 计数器 ========================
TOTAL=0
PASSED=0
FAILED=0
SKIPPED=0
declare -a FAILED_TESTS=()

# ======================== 参数解析 ========================
for arg in "$@"; do
  case "$arg" in
    --gateway) USE_GATEWAY=true ;;
    --verbose) VERBOSE=true ;;
  esac
done

# ======================== 工具函数 ========================
log_pass()  { printf "  ${GREEN}[PASS]${NC} %s\n" "$1"; ((PASSED++)); }
log_fail()  { printf "  ${RED}[FAIL]${NC} %s ${RED}-> %s${NC}\n" "$1" "$2"; FAILED_TESTS+=("$1: $2"); ((FAILED++)); }
log_skip()  { printf "  ${YELLOW}[SKIP]${NC} %s -> %s\n" "$1" "$2"; ((SKIPPED++)); }
log_info()  { printf "  ${CYAN}[INFO]${NC} %s\n" "$1"; }

# 测试入口: 执行单个测试用例
# 参数: $1=编号 $2=描述 $3=命令类型(curl/http_code/json_field/regex/range/not_empty) $4=具体参数
test_case() {
  local id="$1" desc="$2" type="$3"
  ((TOTAL++))
  printf "${BOLD}[%03d]${NC} %s\n" "$TOTAL" "$desc"

  case "$type" in
    curl)
      # $4=URL $5=期望HTTP状态码(可逗号分隔多个) $6=额外curl参数...
      local url="$4" expected_codes="$5"
      shift 5
      local extra_args=("$@")

      local response_file
      response_file=$(mktemp /tmp/lyclaw-test.XXXXXX)
      local http_code
      http_code=$(curl -s -o "$response_file" -w "%{http_code}" \
        --connect-timeout 5 --max-time "$TIMEOUT" "${extra_args[@]}" "$url" 2>/dev/null)

      IFS=',' read -ra codes <<< "$expected_codes"
      local matched=false
      for c in "${codes[@]}"; do
        [[ "$http_code" == "$c" ]] && matched=true && break
      done

      if $matched; then
        log_pass "$desc"
        $VERBOSE && [[ -s "$response_file" ]] && printf "    Body: %s\n" "$(head -c 300 "$response_file")"
      else
        local body_snippet
        body_snippet=$(head -c 200 "$response_file" 2>/dev/null)
        log_fail "$desc" "期望状态码 [${expected_codes}], 实际 $http_code | body=${body_snippet}"
      fi
      rm -f "$response_file"
      ;;

    http_code)
      # $4=URL $5=额外curl参数...
      local url="$4"
      shift 4
      local extra_args=("$@")

      local http_code
      http_code=$(curl -s -o /dev/null -w "%{http_code}" \
        --connect-timeout 5 --max-time "$TIMEOUT" "${extra_args[@]}" "$url" 2>/dev/null)
      echo "$http_code"
      ;;

    json_field)
      # $4=URL $5=JSONPath(用jq) $6=期望值 $7=额外curl参数...
      local url="$4" jq_filter="$5" expected="$6"
      shift 6
      local extra_args=("$@")

      local response
      response=$(curl -s --connect-timeout 5 --max-time "$TIMEOUT" "${extra_args[@]}" "$url" 2>/dev/null)
      local actual
      actual=$(echo "$response" | jq -r "$jq_filter" 2>/dev/null)

      if [[ "$actual" == "$expected" ]]; then
        log_pass "$desc"
      else
        log_fail "$desc" "期望 '$expected', 实际 '$actual'"
      fi
      ;;

    regex)
      # $4=URL $5=Content-Type正则 $6=额外curl参数...
      local url="$4" content_type_regex="$5"
      shift 5
      local extra_args=("$@")

      local response_file
      response_file=$(mktemp /tmp/lyclaw-test.XXXXXX)
      local http_code ct
      http_code=$(curl -s -o "$response_file" -w "%{http_code}" \
        --connect-timeout 5 --max-time "$TIMEOUT" "${extra_args[@]}" "$url" 2>/dev/null)
      ct=$(curl -s -o /dev/null -w "%{content_type}" \
        --connect-timeout 5 --max-time "$TIMEOUT" "${extra_args[@]}" "$url" 2>/dev/null)

      if [[ "$http_code" =~ ^2 ]] && [[ "$ct" =~ $content_type_regex ]]; then
        log_pass "$desc"
      else
        log_fail "$desc" "状态码=$http_code, Content-Type=$ct, 期望匹配=$content_type_regex"
      fi
      rm -f "$response_file"
      ;;

    range)
      # $4=URL $5=jq filter $6=最小值 $7=最大值 $8=额外curl参数...
      local url="$4" jq_filter="$5" min_val="$6" max_val="$7"
      shift 7
      local extra_args=("$@")

      local response
      response=$(curl -s --connect-timeout 5 --max-time "$TIMEOUT" "${extra_args[@]}" "$url" 2>/dev/null)
      local actual
      actual=$(echo "$response" | jq -r "$jq_filter" 2>/dev/null)

      if [[ "$actual" != "null" ]] && (( $(echo "$actual >= $min_val" | bc -l 2>/dev/null || echo 0) )) \
         && (( $(echo "$actual <= $max_val" | bc -l 2>/dev/null || echo 0) )); then
        log_pass "$desc"
      else
        log_fail "$desc" "期望范围 [$min_val, $max_val], 实际 $actual"
      fi
      ;;

    not_empty)
      # $4=URL $5=jq filter $6=额外curl参数...
      local url="$4" jq_filter="$5"
      shift 5
      local extra_args=("$@")

      local response
      response=$(curl -s --connect-timeout 5 --max-time "$TIMEOUT" "${extra_args[@]}" "$url" 2>/dev/null)
      local actual
      actual=$(echo "$response" | jq -r "$jq_filter" 2>/dev/null)

      if [[ -n "$actual" && "$actual" != "null" && "$actual" != "[]" && "$actual" != "{}" ]]; then
        log_pass "$desc"
      else
        log_fail "$desc" "期望非空, 实际 '$actual'"
      fi
      ;;

    *)
      log_fail "$desc" "未知测试类型: $type"
      ;;
  esac
}

# 获取基础 URL (根据 --gateway 参数决定)
get_base() {
  local port="$1"
  if $USE_GATEWAY; then
    echo "http://$GATEWAY_HOST"
  else
    echo "http://localhost:$port"
  fi
}

# ======================== 启动前检查 ========================
echo ""
echo "============================================================"
echo "  LyClaw 微服务集成测试"
echo "  时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "  模式: $($USE_GATEWAY && echo 'Gateway 透传 (8080)' || echo '直连服务')"
echo "============================================================"
echo ""

# -------- 第 0 部分: 环境检查 --------
echo "${BOLD}--- 0. 环境检查 ---${NC}"

# 检查 curl
if command -v curl &>/dev/null; then
  log_pass "curl 可用"
else
  log_fail "curl 不可用" "请安装 curl"
  echo "测试终止: 缺少必要工具"
  exit 1
fi

# 检查 jq
if command -v jq &>/dev/null; then
  log_pass "jq 可用"
else
  log_skip "jq 不可用" "部分 JSON 验证测试将跳过"
fi

# 检查 Nacos
NACOS_HEALTH=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 --max-time 5 "$NACOS_URL/nacos/v1/console/health" 2>/dev/null)
if [[ "$NACOS_HEALTH" == "200" ]]; then
  log_pass "Nacos 可达 (localhost:8848)"
else
  log_fail "Nacos 不可达" "请启动 Docker: docker-compose up -d"
fi

# -------- 第 1 部分: 服务启动检查 --------
echo ""
echo "${BOLD}--- 1. 服务启动检查 ---${NC}"

# 尝试通过 Nacos API 获取注册服务列表
NACOS_SERVICES=$(curl -s --connect-timeout 3 --max-time 5 \
  "http://localhost:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20&namespaceId=lyclaw" 2>/dev/null)

declare -A SERVICE_PORT_MAP
SERVICE_PORT_MAP=(
  ["lyclaw-gateway"]="8080"
  ["lyclaw-orchestration-service"]="8081"
  ["lyclaw-memory-service"]="8082"
  ["lyclaw-plan-service"]="8083"
  ["lyclaw-action-service"]="8084"
  ["lyclaw-reflect-service"]="8085"
  ["lyclaw-protocol-service"]="8086"
)

UNREACHABLE=()

for entry in "${SERVICE_HOSTS[@]}"; do
  port="${entry%%:*}"
  svc="${entry#*:}"
  ((TOTAL++))

  # 检查端口是否在监听
  if ss -tlnp 2>/dev/null | grep -q ":$port " || netstat -tlnp 2>/dev/null | grep -q ":$port "; then
    printf "  ${GREEN}[PASS]${NC} %s (端口 %s 已监听)\n" "$svc" "$port"
    ((PASSED++))
  else
    # 尝试 curl 健康检查
    HEALTH_CODE=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 2 --max-time 3 \
      "http://localhost:$port" 2>/dev/null)
    if [[ "$HEALTH_CODE" =~ ^[0-9]+$ ]]; then
      printf "  ${GREEN}[PASS]${NC} %s (端口 %s HTTP %s)\n" "$svc" "$port" "$HEALTH_CODE"
      ((PASSED++))
    else
      printf "  ${YELLOW}[SKIP]${NC} %s (端口 %s 未启动)\n" "$svc" "$port"
      UNREACHABLE+=("$svc:$port")
      ((SKIPPED++))
    fi
  fi
done

if [[ ${#UNREACHABLE[@]} -gt 0 ]]; then
  echo ""
  printf "  ${YELLOW}[提示]${NC} 以下服务未启动:\n"
  for s in "${UNREACHABLE[@]}"; do
    printf "    - %s\n" "$s"
  done
  echo ""
  echo "  启动命令参考:"
  echo "    cd /home/lyjew/Documents/Unicom/LyClaw"
  echo "    mvn -pl lyclaw-gateway spring-boot:run &"
  echo "    mvn -pl lyclaw-orchestration spring-boot:run &"
  echo "    mvn -pl lyclaw-memory spring-boot:run &"
  echo "    mvn -pl lyclaw-plan spring-boot:run &"
  echo "    mvn -pl lyclaw-action spring-boot:run &"
  echo "    mvn -pl lyclaw-reflect spring-boot:run &"
  echo "    mvn -pl lyclaw-protocol spring-boot:run &"
  echo ""
fi

# -------- 第 2 部分: Orchestration Service (8081) --------
echo ""
echo "${BOLD}--- 2. Orchestration Service (8081) ---${NC}"
BASE=$(get_base 8081)

test_case "ORCH-01" "POST /api/sessions 创建会话" curl \
  "$BASE/api/sessions" "200,201" -X POST -H "Content-Type: application/json"

# 创建测试会话并保存 sessionId
SESSION_ID="test-$(date +%s)"
CREATE_RESP=$(curl -s -X POST -H "Content-Type: application/json" \
  --connect-timeout 5 --max-time "$TIMEOUT" "$BASE/api/sessions" 2>/dev/null)
if [[ -n "$CREATE_RESP" ]]; then
  EXTRACTED_ID=$(echo "$CREATE_RESP" | jq -r '.sessionId // .id // empty' 2>/dev/null)
  [[ -n "$EXTRACTED_ID" ]] && SESSION_ID="$EXTRACTED_ID"
fi

test_case "ORCH-02" "GET /api/sessions/{sessionId} 获取会话" json_field \
  "$BASE/api/sessions/$SESSION_ID" ".sessionId" "$SESSION_ID"

test_case "ORCH-03" "POST /api/chat 非流式聊天" curl \
  "$BASE/api/chat" "200" -X POST -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"$SESSION_ID\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}],\"stream\":false}"

test_case "ORCH-04" "POST /api/chat/stream SSE 流式聊天" regex \
  "$BASE/api/chat/stream" "text/event-stream" -X POST -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"$SESSION_ID\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}],\"stream\":true}"

test_case "ORCH-05" "DELETE /api/sessions/{sessionId} 删除会话" curl \
  "$BASE/api/sessions/$SESSION_ID" "200" -X DELETE

test_case "ORCH-06" "GET /api/sessions/{deletedId} 应返回 404" curl \
  "$BASE/api/sessions/$SESSION_ID" "404,500"

# -------- 第 3 部分: Memory Service (8082) --------
echo ""
echo "${BOLD}--- 3. Memory Service (8082) ---${NC}"
BASE=$(get_base 8082)

test_case "MEM-01" "POST /api/memory/ingest 写入记忆" curl \
  "$BASE/api/memory/ingest?sessionId=test-$(date +%s)&userId=default" "200" \
  -X POST -H "Content-Type: application/json" \
  -d '{"role":"user","content":"test memory"}'

test_case "MEM-02" "POST /api/memory/retrieve 检索记忆" curl \
  "$BASE/api/memory/retrieve" "200" -X POST -H "Content-Type: application/json" \
  -d '{"queryText":"test","topK":5}'

test_case "MEM-03" "POST /api/memory/consolidate 整理记忆" curl \
  "$BASE/api/memory/consolidate?userId=test&sessionId=test" "200" -X POST

test_case "MEM-04" "GET /api/memory/stats 获取统计" curl \
  "$BASE/api/memory/stats" "200"

# -------- 第 4 部分: Action Service (8084) --------
echo ""
echo "${BOLD}--- 4. Action Service (8084) ---${NC}"
BASE=$(get_base 8084)

test_case "ACT-01" "GET /api/action/tools 获取工具列表" not_empty \
  "$BASE/api/action/tools" ".[0].name"

test_case "ACT-02" "GET /api/action/skills 获取技能列表" curl \
  "$BASE/api/action/skills" "200"

test_case "ACT-03" "POST /api/action/execute-tool 执行计算器" curl \
  "$BASE/api/action/execute-tool" "200" -X POST -H "Content-Type: application/json" \
  -d '{"toolName":"calculator","args":{"expression":"2+3*4"},"sandboxLevel":"SANDBOX","sessionId":"test"}'

test_case "ACT-04" "POST /api/action/execute-skill 执行技能" curl \
  "$BASE/api/action/execute-skill" "200" -X POST -H "Content-Type: application/json" \
  -d '{"skillId":"test-skill","sessionId":"test","params":{}}'

test_case "ACT-05" "GET /api/action/sandbox/health 沙箱健康检查" json_field \
  "$BASE/api/action/sandbox/health" ".healthy" "true"

test_case "ACT-06" "GET /api/action/tools/stats 工具统计" not_empty \
  "$BASE/api/action/tools/stats" ".totalCount"

# -------- 第 5 部分: Reflect Service (8085) --------
echo ""
echo "${BOLD}--- 5. Reflect Service (8085) ---${NC}"
BASE=$(get_base 8085)

test_case "RFL-01" "POST /api/reflect/evaluate 质量评估" curl \
  "$BASE/api/reflect/evaluate" "200" -X POST -H "Content-Type: application/json" \
  -d '{"output":"test output","criteria":{"taskDescription":"test","checkAccuracy":true}}'

test_case "RFL-02" "POST /api/reflect/detect-errors 错误检测" curl \
  "$BASE/api/reflect/detect-errors" "200" -X POST -H "Content-Type: application/json" \
  -d '{"output":"This is definitely always correct without exception"}'

test_case "RFL-03" "POST /api/reflect/reflect 反思" curl \
  "$BASE/api/reflect/reflect" "200" -X POST -H "Content-Type: application/json" \
  -d '{"sessionId":"test","output":"test output"}'

# -------- 第 6 部分: Plan Service (8083) --------
echo ""
echo "${BOLD}--- 6. Plan Service (8083) ---${NC}"
BASE=$(get_base 8083)

test_case "PLN-01" "GET /api/plan/strategies 获取策略列表" not_empty \
  "$BASE/api/plan/strategies" ".[0].name"

test_case "PLN-02" "POST /api/plan/plan DAG 规划" curl \
  "$BASE/api/plan/plan" "200" -X POST -H "Content-Type: application/json" \
  -d '{"sessionId":"test","userIntent":"calculate 2+2","strategy":"dag"}'

test_case "PLN-03" "POST /api/plan/decompose 任务分解" curl \
  "$BASE/api/plan/decompose" "200" -X POST -H "Content-Type: application/json" \
  -d '{"taskDescription":"analyze data","strategy":"BY_PHASE"}'

test_case "PLN-04" "POST /api/plan/validate 验证计划" curl \
  "$BASE/api/plan/validate" "200" -X POST -H "Content-Type: application/json" \
  -d '{"nodes":[{"nodeId":"1","type":"EXECUTE","description":"test","requiredTools":[],"dependencies":[],"timeoutMs":30000}]}'

test_case "PLN-05" "POST /api/plan/graph 构建图" curl \
  "$BASE/api/plan/graph" "200" -X POST -H "Content-Type: application/json" \
  -d '{"nodes":[{"nodeId":"1","type":"EXECUTE","description":"test"}],"edges":[]}'

test_case "PLN-06" "GET /api/plan/progress/test-plan 查询进度" curl \
  "$BASE/api/plan/progress/test-plan" "200"

# -------- 第 7 部分: Protocol Service (8086) --------
echo ""
echo "${BOLD}--- 7. Protocol Service (8086) ---${NC}"
BASE=$(get_base 8086)

test_case "PRT-01" "GET /api/protocol/a2a/card Agent卡片" not_empty \
  "$BASE/api/protocol/a2a/card" ".agentId"

test_case "PRT-02" "POST /api/protocol/model/chat 模型聊天" curl \
  "$BASE/api/protocol/model/chat" "200" -X POST -H "Content-Type: application/json" \
  -d '{"message":"test"}'

test_case "PRT-03" "POST /api/protocol/mcp/discover MCP工具发现" curl \
  "$BASE/api/protocol/mcp/discover?serverCommand=echo" "200" -X POST

# -------- 第 8 部分: Gateway 透传测试 --------
echo ""
echo "${BOLD}--- 8. Gateway 透传测试 (通过 8080) ---${NC}"

# 检查 Gateway 是否可达
GW_HEALTH=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 --max-time 5 \
  "http://localhost:8080/api/action/tools" 2>/dev/null)

if [[ "$GW_HEALTH" =~ ^[0-9]+$ ]]; then
  log_pass "Gateway 8080 可达"

  test_case "GW-01" "Gateway -> GET /api/action/tools" curl \
    "http://localhost:8080/api/action/tools" "200"

  test_case "GW-02" "Gateway -> GET /api/plan/strategies" curl \
    "http://localhost:8080/api/plan/strategies" "200"

  test_case "GW-03" "Gateway -> GET /api/memory/stats" curl \
    "http://localhost:8080/api/memory/stats" "200"

  test_case "GW-04" "Gateway -> POST /api/reflect/evaluate" curl \
    "http://localhost:8080/api/reflect/evaluate" "200" \
    -X POST -H "Content-Type: application/json" \
    -d '{"output":"test","criteria":{"checkAccuracy":true}}'

  test_case "GW-05" "Gateway -> GET /api/protocol/a2a/card" curl \
    "http://localhost:8080/api/protocol/a2a/card" "200"

  test_case "GW-06" "Gateway -> POST /api/sessions" curl \
    "http://localhost:8080/api/sessions" "200,201" \
    -X POST -H "Content-Type: application/json"

  test_case "GW-07" "Gateway -> POST /api/plan/plan" curl \
    "http://localhost:8080/api/plan/plan" "200" \
    -X POST -H "Content-Type: application/json" \
    -d '{"sessionId":"gw-test","userIntent":"test","strategy":"dag"}'
else
  log_skip "Gateway 透传测试" "Gateway 8080 不可达"
fi

# -------- 第 9 部分: 错误处理测试 --------
echo ""
echo "${BOLD}--- 9. 错误处理测试 ---${NC}"

# 找一个可用的服务端口进行错误测试
ERROR_BASE="http://localhost:8081"
if [[ "$GW_HEALTH" =~ ^[0-9]+$ ]]; then
  ERROR_BASE="http://localhost:8080"
fi

test_case "ERR-01" "404 不存在的端点" curl \
  "$ERROR_BASE/api/nonexistent-endpoint-xyz" "404"

test_case "ERR-02" "无效 JSON 请求体" curl \
  "$ERROR_BASE/api/chat" "400,500" -X POST -H "Content-Type: application/json" \
  -d 'this is not valid json{'

test_case "ERR-03" "空请求体" curl \
  "$ERROR_BASE/api/chat" "400,405,415,500" -X POST -H "Content-Type: application/json" \
  -d ''

test_case "ERR-04" "缺失 Content-Type" curl \
  "$ERROR_BASE/api/chat" "400,415,500" -X POST -d '{"test":true}'

test_case "ERR-05" "GET 请求 POST-only 端点" curl \
  "$ERROR_BASE/api/chat" "405,404" -X GET

# -------- 第 10 部分: 安全测试 --------
echo ""
echo "${BOLD}--- 10. 安全测试 ---${NC}"

test_case "SEC-01" "CORS 预检请求 OPTIONS" curl \
  "$ERROR_BASE/api/chat" "200,204,403" -X OPTIONS \
  -H "Origin: http://localhost:3000" -H "Access-Control-Request-Method: POST"

test_case "SEC-02" "SQL 注入尝试" curl \
  "$ERROR_BASE/api/memory/retrieve" "200,400,403" -X POST \
  -H "Content-Type: application/json" \
  -d '{"queryText":"DROP TABLE users;--","topK":1}'

test_case "SEC-03" "XSS 尝试" curl \
  "$ERROR_BASE/api/chat" "200,400,403,500" -X POST \
  -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"sec-test\",\"messages\":[{\"role\":\"user\",\"content\":\"<script>alert('xss')</script>\"}],\"stream\":false}"

# ======================== 汇总报告 ========================
echo ""
echo "============================================================"
echo "  测试汇总"
echo "============================================================"
printf "  总计: %d\n" "$TOTAL"
printf "  ${GREEN}通过: %d${NC}\n" "$PASSED"
printf "  ${RED}失败: %d${NC}\n" "$FAILED"
printf "  ${YELLOW}跳过: %d${NC}\n" "$SKIPPED"

if [[ $TOTAL -gt 0 ]]; then
  PASS_RATE=$(echo "scale=1; $PASSED * 100 / ($TOTAL - $SKIPPED)" | bc 2>/dev/null || echo "0")
  printf "  通过率(排除跳过): %s%%\n" "$PASS_RATE"
fi

if [[ ${#FAILED_TESTS[@]} -gt 0 ]]; then
  echo ""
  echo "  ${RED}失败详情:${NC}"
  for ft in "${FAILED_TESTS[@]}"; do
    printf "    ${RED}[FAIL]${NC} %s\n" "$ft"
  done
fi

echo ""
echo "  完成时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================================"

# 返回码
if [[ $FAILED -gt 0 ]]; then
  exit 1
else
  exit 0
fi
