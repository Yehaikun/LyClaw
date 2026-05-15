# LyClaw 规划-执行断裂修复方案

---

## 目录

1. [架构总览](#一架构总览)
2. [改动清单](#二改动清单)
3. [新增文件详细设计](#三新增文件详细设计)
4. [修改文件详细设计](#四修改文件详细设计)
5. [完整数据流](#五完整数据流)
6. [兜底降级链路](#六兜底降级链路)
7. [验证方法](#七验证方法)

---

## 一、架构总览

### 当前状态（断裂）

```
PlanExecutionStage ──── 生成 DAG ────→ pipelineCtx.nodes ────→ 仅前端SSE展示，无人消费
                                                                    │
RespondStage ──── actionFeignClient.listTools() ────→ LLM自由发挥  │
                                                                    │
                             两条路径完全平行，DAG形同虚设            │
```

### 目标状态（打通）

```
前端 ChatRequest { planMode, planner, plan?, messages, sessionId }
  │
  ▼
PlanExecutionStage (策略路由)
  ├─ 前端传了 plan → 校验 + 转换 → pipelineCtx.nodes
  ├─ planMode="dag" → PlanRequest.strategy=planner → Feign → pipelineCtx.nodes
  └─ planMode="react" → skip
  │
  ▼
RespondStage (执行分支)
  ├─ nodes 非空 → executeDAGPlan()   ← DAG注入提示词 + 分层并行
  └─ nodes 为空 → streamWithToolDetection()  ← 原有ReAct(不改)
```

### 核心设计原则

1. **Strategy 模式**：LLM 规划 = 新的 `TaskPlanner` 实现，不修改现有 Planner
2. **开关控制**：前端通过 flag 选择执行模式，向后零兼容风险
3. **三层兜底**：LLM 规划失败 → 规则引擎 → ReAct 自由模式

---

## 二、改动清单

### 新增文件

| 文件 | 模块 | 职责 |
|------|------|------|
| `LLMTaskPlanner.java` | lyclaw-plan | 实现 TaskPlanner，用 ChatFacade 调 LLM 生成 DAG |
| `PlanNodeDTO.java` | lyclaw-framework | 前端友好的计划节点 DTO |
| `PlanNodeConverter.java` | lyclaw-framework | PlanNodeDTO ↔ TaskNode 双向转换 |

### 修改文件

| 文件 | 模块 | 改动 |
|------|------|------|
| `PlanController.java` | lyclaw-plan | `selectPlanner()` 加 `"llm"` case；注入 LLMTaskPlanner；plan() 增加 null 检查 |
| `ChatRequest.java` (DTO) | lyclaw-orchestration | 加 `planMode`、`planner`、`plan` 字段 |
| `PlanExecutionStage.java` | lyclaw-orchestration | 读前端 flag，透传到 PlanRequest；处理前端直传 plan；SSE 推送用 PlanNodeDTO 格式 |
| `RespondStage.java` | lyclaw-orchestration | 读 `pipelineCtx.nodes`，非空走 DAG 执行路径（新增 5 个方法）；DAG 执行时每节点深拷贝消息列表并行执行 |
| `ReflectionStage.java` | lyclaw-orchestration | `@PipelineStage` 注解 `after` 从 PlanExecutionStage 改为 RespondStage；`getOrder()` 从 3 改为 4；`setPipelineOk(true)` 逻辑不变但不再阻塞 RespondStage |
| `OrchestrationController.java` | lyclaw-orchestration | `buildChatContext()` 方法签名加 `ChatRequest dto` 参数，提取 flag 写入 `context.attributes`；`chatStream()` 和 `chat()` 两个调用点都需更新 |

### 不改的文件

| 文件 | 原因 |
|------|------|
| `TaskPlanner.java` | 接口不变，LLMTaskPlanner 直接实现 |
| `DAGTaskPlanner.java` | 规则引擎保持独立，作为降级兜底 |
| `TaskNode.java` | 内部领域对象不变，DTO 在其上层做转换 |
| `PlanRequest.java` | `strategy` 字段已有，直接复用 |
| `FullWindowContextBuilder.java` | 死代码，不理它 |
| `PipelineContext.java` | 现有字段（nodes, toolResults, successCount, failCount, pipelineOk, terminated）已足够，不需要新增 |

---

## 三、新增文件详细设计

### 3.1 LLMTaskPlanner.java

**位置**: `lyclaw-plan/src/main/java/lyjew/com/lyclaw/plan/impl/LLMTaskPlanner.java`

**设计模式**: Strategy（实现 TaskPlanner 接口）、Template Method（继承 AbstractTaskPlanner）、Chain of Responsibility（内部降级链）

**为什么注入 DAGTaskPlanner 作为 fallback？**

降级需要判断"LLM 调用失败"还是"JSON 不可解析"还是"返回空内容"，这些判断逻辑属于 Planner 内部不应泄露到 Controller。LLMTaskPlanner 内部完成降级，对外永远返回有效 TaskPlan。

---

**完整实现**:

```java
package lyjew.com.lyclaw.plan.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;
import lyjew.com.lyclaw.task.*;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LLM驱动的任务规划器 —— 将自然语言意图交由大模型拆解为结构化 DAG。
 *
 * <p><b>设计定位</b>：作为 {@link TaskPlanner} 接口的 LLM 实现，与
 * {@link DAGTaskPlanner} 平级。通过调用 ChatFacade 让 LLM 自主分析任务、
 * 识别依赖、评估复杂度、推荐工具，生成包含节点类型、依赖关系和超时时间的
 * 完整 DAG 计划。</p>
 *
 * <p><b>核心流程</b>：
 * <ol>
 *   <li>组装规划专用 System Prompt（含角色设定、JSON Schema、分解原则、示例）</li>
 *   <li>构造低温度（0.3）ChatRequest，调用 LLM 获取 JSON 响应</li>
 *   <li>从响应中提取 JSON（处理 markdown 代码块包裹），解析为 TaskNode 列表</li>
 *   <li>校验节点合法性（类型白名单、数量上限、依赖一致性）</li>
 *   <li>封装为 {@link SimpleTaskPlan} 返回</li>
 *   <li>任何步骤失败 → 内部降级到 {@link DAGTaskPlanner}</li>
 * </ol>
 *
 * <p><b>为什么不包含工具清单？</b>
 * 规划器的职责是结构分解（拆成什么步骤、依赖关系），而非工具选择。
 * 工具匹配在 RespondStage 的 ReAct 循环中完成——每个 DAG 节点执行时，
 * RespondStage 通过 {@code ActionFeignClient.listTools()} 获取实际可用工具列表，
 * 传入 LLM，由 LLM 自主决定调用哪个工具。规划阶段预设工具名反而限制了灵活性。</p>
 *
 * <p><b>降级策略</b>：ChatFacade 为 null、LLM 调用超时/异常、
 * 返回不可解析 JSON、节点列表为空等场景全部降级到 DAGTaskPlanner.plan()，
 * 确保调用方永远收到有效的 TaskPlan。</p>
 *
 * <p><b>线程安全</b>：无状态设计，ObjectMapper 线程安全。
 * 单例作用域下安全。</p>
 *
 * @see TaskPlanner
 * @see DAGTaskPlanner
 * @see SimpleTaskPlan
 */
@Slf4j
@Service("llmTaskPlanner")
public class LLMTaskPlanner extends AbstractTaskPlanner {

    // ──────────────────────────── 依赖注入 ────────────────────────────

    /** LLM 对话门面，为 null 时降级到规则引擎 */
    private final ChatFacade chatFacade;

    /** 降级规划器，LLM 规划失败时自动切入 */
    private final DAGTaskPlanner fallbackPlanner;

    /** JSON 解析器，线程安全 */
    private final ObjectMapper objectMapper;

    // ──────────────────────────── 配置常量 ────────────────────────────

    /** 规划用采样温度（低温度保证输出稳定、格式规范） */
    static final double PLANNER_TEMPERATURE = 0.3;

    /** 规划响应最大 Token 数 */
    static final int PLANNER_MAX_TOKENS = 4096;

    /** 默认节点超时（毫秒），LLM 未指定时使用 */
    static final long DEFAULT_TIMEOUT_MS = 30_000L;

    /** LLM 计划允许的最大节点数，防止异常输出 */
    static final int MAX_NODES = 20;

    /** 合法的节点类型白名单，用于校验 LLM 输出 */
    static final Set<String> VALID_NODE_TYPES = Set.of(
            "ANALYZE", "RESEARCH", "DESIGN", "PREPARE",
            "EXECUTE", "INTEGRATE", "VERIFY"
    );

    /** 从 markdown 代码块中提取 JSON 的正则 */
    private static final Pattern JSON_BLOCK = Pattern.compile(
            "```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    /** 纯 JSON 对象检测（首非空字符为 { 或 [） */
    private static final Pattern JSON_BARE = Pattern.compile("^\\s*[\\{\\[]");

    // ──────────────────────────── 构造函数 ────────────────────────────

    /**
     * 构造 LLM 任务规划器。
     *
     * @param chatFacade      LLM 调用门面，标记为 {@link Nullable}，
     *                        当 lyclaw-chat 模块未加载时为 null
     * @param fallbackPlanner LLM 规划失败时的降级规则引擎规划器
     */
    public LLMTaskPlanner(
            @Nullable ChatFacade chatFacade,
            DAGTaskPlanner fallbackPlanner) {
        this.chatFacade = chatFacade;
        this.fallbackPlanner = fallbackPlanner;
        this.objectMapper = new ObjectMapper();
    }

    // ──────────────────────── TaskPlanner 实现 ────────────────────────

    /**
     * 根据用户意图生成 DAG 任务计划。
     *
     * <p>降级链路：ChatFacade=null → LLM调用失败 → JSON不可解析 → 全部走
     * {@code fallbackPlanner.plan(context, userIntent)}。</p>
     *
     * @param context    对话上下文，包含会话信息
     * @param userIntent 用户意图文本
     * @return 任务计划，永远非 null
     */
    @Override
    public TaskPlan plan(ChatContext context, String userIntent) {
        if (userIntent == null || userIntent.isBlank()) {
            log.debug("LLM规划: userIntent为空，直接降级到规则引擎");
            return fallbackPlanner.plan(context, userIntent);
        }

        TaskPlan llmPlan = tryLLMPlan(userIntent);
        if (llmPlan != null) {
            return llmPlan;
        }

        log.info("LLM规划失败或不可用，降级到 DAGTaskPlanner");
        return fallbackPlanner.plan(context, userIntent);
    }

    /**
     * 根据反思反馈修订计划。
     *
     * <p>当前委托给 DAGTaskPlanner 的修订逻辑，后续可扩展为 LLM 修订。
     */
    @Override
    public TaskPlan revise(TaskPlan original, ReflectionFeedback feedback) {
        return fallbackPlanner.revise(original, feedback);
    }

    /**
     * 使用指定策略分解根任务。
     *
     * <p>委托给 DAGTaskPlanner，复用其已实现的六种分解策略。
     */
    @Override
    public PlanGraph decompose(TaskNode rootTask, DecompositionStrategy strategy) {
        return fallbackPlanner.decompose(rootTask, strategy);
    }

    // ──────────────────────── LLM 规划核心 ────────────────────────────

    /**
     * 尝试使用 LLM 生成任务计划。
     *
     * <p>核心链路：组装系统提示词 → 构造请求 → 调 LLM → 提取 JSON → 解析节点。
     * 任何步骤失败返回 null，由调用方降级处理。</p>
     *
     * @param userIntent 用户意图文本
     * @return 解析成功的 TaskPlan，失败返回 null
     */
    private TaskPlan tryLLMPlan(String userIntent) {
        if (chatFacade == null) {
            log.debug("ChatFacade 未注入，跳过 LLM 规划");
            return null;
        }

        try {
            // 1. 动态生成系统提示词（结构分解，不包含工具清单）
            String systemPrompt = buildSystemPrompt();

            // 2. 构造低温度 ChatRequest
            ChatRequest request = ChatRequest.builder()
                    .systemPrompt(systemPrompt)
                    .messages(List.of(Message.user(userIntent)))
                    .temperature(PLANNER_TEMPERATURE)
                    .maxTokens(PLANNER_MAX_TOKENS)
                    .stream(false)
                    .build();

            log.info("LLM规划: 发起请求, intentLength={}", userIntent.length());

            // 3. 同步调用 LLM
            ModelResponse response = chatFacade.chat(request);
            String content = response.getContent();
            if (content == null || content.isBlank()) {
                log.warn("LLM规划: 返回空内容");
                return null;
            }

            log.debug("LLM规划: 原始响应长度={}", content.length());

            // 4. 从响应中提取 JSON
            String json = extractJSON(content);
            if (json == null) {
                log.warn("LLM规划: 无法从响应中提取JSON, raw={}", content.substring(0,
                        Math.min(200, content.length())));
                return null;
            }

            // 5. 解析 JSON 为 TaskNode 列表
            List<TaskNode> nodes = parseNodes(json);
            if (nodes.isEmpty()) {
                log.warn("LLM规划: 解析结果为空");
                return null;
            }

            log.info("LLM规划: 成功, 生成{}个节点", nodes.size());
            return new SimpleTaskPlan(nodes);

        } catch (Exception e) {
            log.warn("LLM规划异常: {}, 将降级到规则引擎", e.getMessage());
            return null;
        }
    }

    // ──────────────────────── 系统提示词 ──────────────────────────────

    /**
     * 动态构建规划专用系统提示词。
     *
     * <p>提示词由三部分组成：
     * <ol>
     *   <li><b>角色设定</b> — 任务规划专家，输出严格 JSON</li>
     *   <li><b>JSON Schema</b> — 详细的输出格式规范和字段说明</li>
     *   <li><b>规划原则 + 示例</b> — 依赖分析、并行识别、复杂度评估</li>
     * </ol>
     *
     * <p>注意：提示词中<b>不包含工具清单</b>。工具选择是 RespondStage
     * 中 ReAct 循环的职责，规划阶段只做结构分解。</p>
     */
    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder(4096);

        // ── 第1部分：角色设定 ──
        sb.append("""
                You are a task planning expert. Your job is to analyze a user's request
                and decompose it into a structured Directed Acyclic Graph (DAG) of steps.

                Focus on WHAT needs to be done and the DEPENDENCIES between steps.
                Do NOT prescribe specific tools — tool selection happens at execution time.

                Always respond with a valid JSON object. No markdown, no explanations,
                just the JSON.

                """);

        // ── 第2部分：JSON Schema ──
        sb.append("""
                ## Output Format

                ```json
                {
                  "complexity": "simple|medium|complex",
                  "summary": "one-line description of the plan",
                  "nodes": [
                    {
                      "nodeId": "step-0",
                      "type": "ANALYZE|RESEARCH|DESIGN|PREPARE|EXECUTE|INTEGRATE|VERIFY",
                      "description": "what this step does in detail",
                      "requiredTools": ["exact_tool_name"],
                      "dependencies": ["step-0"],
                      "timeoutMs": 30000
                    }
                  ]
                }
                ```

                ### Field rules:

                - **nodeId**: Unique within the plan. Use "step-N" format (step-0, step-1, ...).
                - **type**: Must be one of ANALYZE, RESEARCH, DESIGN, PREPARE, EXECUTE, INTEGRATE, VERIFY.
                - **description**: Clear, actionable description in the user's language.
                  Include what needs to be done, not how.
                - **requiredTools**: Array of tool names from the Available Tools table above.
                  Use empty array [] if no tools are needed.
                - **dependencies**: Array of nodeIds that must complete before this node can start.
                  Use empty array [] for the first node(s).
                - **timeoutMs**: Estimated timeout in milliseconds. Use 30000 for quick tasks,
                  60000 for normal tasks, 120000 for complex tasks.

                """);

        // ── 第3部分：规划原则 + 示例 ──
        sb.append("""
                ## Planning Principles

                1. **Dependency analysis**: Only add dependencies when truly sequential.
                   Maximize parallelism by keeping independent steps separate.
                2. **Complexity assessment**: "simple" (1-2 nodes, single task),
                   "medium" (3-5 nodes, multi-phase), "complex" (6+ nodes,
                   multiple parallel branches).
                3. **Granularity**: Each node should be a coherent unit of work.
                   Not too fine (single trivial action) and not too coarse
                   (mixing unrelated concerns).
                4. **Language**: Use the same language as the user's request
                   for node descriptions.
                5. **requiredTools**: Leave as empty array [] in most cases.
                   Tool selection happens at execution time by the ReAct loop,
                   not at planning time. Only specify a tool if the step absolutely
                   cannot proceed without it.

                ## Example

                User request: "帮我写一个Spring Boot REST API，包括单元测试和Docker部署配置"

                ```json
                {
                  "complexity": "medium",
                  "summary": "构建包含测试和部署的Spring Boot REST API",
                  "nodes": [
                    {
                      "nodeId": "step-0",
                      "type": "DESIGN",
                      "description": "设计REST API接口：定义端点路径、HTTP方法、请求/响应格式",
                      "requiredTools": [],
                      "dependencies": [],
                      "timeoutMs": 30000
                    },
                    {
                      "nodeId": "step-1",
                      "type": "EXECUTE",
                      "description": "实现核心Controller和Service层代码",
                      "requiredTools": [],
                      "dependencies": ["step-0"],
                      "timeoutMs": 60000
                    },
                    {
                      "nodeId": "step-2",
                      "type": "VERIFY",
                      "description": "编写并运行单元测试，确保API逻辑正确",
                      "requiredTools": [],
                      "dependencies": ["step-1"],
                      "timeoutMs": 60000
                    },
                    {
                      "nodeId": "step-3",
                      "type": "PREPARE",
                      "description": "创建Dockerfile和docker-compose.yml部署配置",
                      "requiredTools": [],
                      "dependencies": ["step-1"],
                      "timeoutMs": 30000
                    },
                    {
                      "nodeId": "step-4",
                      "type": "VERIFY",
                      "description": "验证Docker构建是否成功，确认API可正常访问",
                      "requiredTools": [],
                      "dependencies": ["step-2", "step-3"],
                      "timeoutMs": 60000
                    }
                  ]
                }
                ```

                Note: step-2 and step-3 both depend on step-1 but NOT on each other —
                they can execute in parallel. step-4 depends on both, forming a
                proper fork-join pattern. All requiredTools are empty because
                tool selection is deferred to execution time.

                Now analyze the user's request and output ONLY the JSON.
                """);

        return sb.toString();
    }

    // ──────────────────────── JSON 提取 ───────────────────────────────

    /**
     * 从 LLM 原始输出中提取纯 JSON 字符串。
     *
     * <p>处理三种常见格式：
     * <ol>
     *   <li>```json\n{...}\n``` — 标准 markdown 代码块（优先匹配）</li>
     *   <li>```\n{...}\n``` — 无语言标记的代码块</li>
     *   <li>{...} — 纯 JSON 裸文本（直接返回）</li>
     * </ol>
     *
     * <p>多个代码块时，返回第一个包含合法 JSON 对象的块。
     *
     * @param llmOutput LLM 返回的完整文本
     * @return 提取的纯 JSON 字符串，失败返回 null
     */
    String extractJSON(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return null;
        }

        // 优先匹配 markdown 代码块
        Matcher matcher = JSON_BLOCK.matcher(llmOutput);
        while (matcher.find()) {
            String block = matcher.group(1).trim();
            if (block.startsWith("{") || block.startsWith("[")) {
                return block;
            }
        }

        // 回退：检查是否为裸 JSON
        String trimmed = llmOutput.trim();
        if (JSON_BARE.matcher(trimmed).find()) {
            // 截取第一个完整 JSON 对象（处理末尾有多余文本的情况）
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (inString) {
                    if (escaped) { escaped = false; continue; }
                    if (c == '\\') { escaped = true; continue; }
                    if (c == '"') { inString = false; }
                    continue;
                }
                if (c == '"') { inString = true; continue; }
                if (c == '{' || c == '[') { depth++; continue; }
                if (c == '}' || c == ']') {
                    depth--;
                    if (depth == 0) {
                        return trimmed.substring(0, i + 1);
                    }
                }
            }
            // 整个文本就是一个 JSON，括号深度没归零也直接返回
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return trimmed;
            }
        }

        return null;
    }

    // ──────────────────────── JSON 解析 ───────────────────────────────

    /**
     * 将 JSON 字符串解析为 TaskNode 列表。
     *
     * <p>处理流程：解析顶层 Map → 提取 nodes 数组 → 逐节点转换 → 校验。
     * 单个节点解析失败不影响其他节点（跳过失败节点并记录警告）。
     *
     * @param json 纯 JSON 字符串
     * @return 解析出的 TaskNode 列表，失败返回空列表
     */
    private List<TaskNode> parseNodes(String json) {
        try {
            Map<String, Object> root = objectMapper.readValue(
                    json, new TypeReference<Map<String, Object>>() {});

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawNodes =
                    (List<Map<String, Object>>) root.getOrDefault("nodes", List.of());

            if (rawNodes.isEmpty()) {
                log.warn("LLM JSON 中 nodes 数组为空");
                return List.of();
            }

            String complexity = (String) root.getOrDefault("complexity", "unknown");
            String summary = (String) root.getOrDefault("summary", "");
            log.debug("LLM规划: complexity={}, summary={}, nodeCount={}",
                    complexity, summary, rawNodes.size());

            List<TaskNode> nodes = new ArrayList<>();
            int nodeCount = Math.min(rawNodes.size(), MAX_NODES);

            for (int i = 0; i < nodeCount; i++) {
                try {
                    TaskNode node = parseNode(rawNodes.get(i), i);
                    if (node != null) {
                        nodes.add(node);
                    }
                } catch (Exception e) {
                    log.warn("跳过解析失败的节点[{}]: {}", i, e.getMessage());
                }
            }

            // 输出规划摘要日志
            if (!nodes.isEmpty()) {
                log.info("LLM规划结果: complexity={}, nodes={}, deps={}",
                        complexity,
                        nodes.stream().map(n -> n.getType() + ":" + n.getNodeId())
                                .collect(Collectors.joining(", ")),
                        nodes.stream().filter(n -> !n.getDependencies().isEmpty()).count());
            }

            return nodes;

        } catch (Exception e) {
            log.warn("JSON解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析单个节点 —— 安全地从 Map 中提取字段并构造 TaskNode。
     *
     * <p>所有字段均有默认值保护，LLM 漏填字段不会导致空指针：
     * <ul>
     *   <li>nodeId 缺失 → 根据索引自动生成</li>
     *   <li>type 缺失或不在白名单 → 默认 "EXECUTE"</li>
     *   <li>description 缺失 → 使用占位描述</li>
     *   <li>timeoutMs 缺失或为 null → {@link #DEFAULT_TIMEOUT_MS}</li>
     * </ul>
     *
     * @param raw   原始 JSON 节点 Map
     * @param index 节点在列表中的位置
     * @return 构造好的 TaskNode，不会为 null
     */
    private TaskNode parseNode(Map<String, Object> raw, int index) {
        // 节点 ID：优先用 LLM 给的，缺失时自动生成
        String nodeId = raw.get("nodeId") instanceof String s && !s.isBlank()
                ? s : generateNodeId(index);

        // 节点类型：白名单校验，不合法则回退为 EXECUTE
        String type = raw.get("type") instanceof String s
                && VALID_NODE_TYPES.contains(s.toUpperCase())
                ? s.toUpperCase() : "EXECUTE";

        // 节点描述
        String description = raw.get("description") instanceof String s && !s.isBlank()
                ? s : "Step " + (index + 1);

        // 所需工具：安全提取字符串列表
        @SuppressWarnings("unchecked")
        List<String> requiredTools = raw.get("requiredTools") instanceof List<?> list
                ? list.stream()
                        .filter(Objects::nonNull)
                        .map(Object::toString)
                        .filter(s -> !s.isBlank())
                        .toList()
                : List.of();

        // 依赖节点：安全提取字符串列表
        @SuppressWarnings("unchecked")
        List<String> dependencies = raw.get("dependencies") instanceof List<?> list
                ? list.stream()
                        .filter(Objects::nonNull)
                        .map(Object::toString)
                        .filter(s -> !s.isBlank())
                        .toList()
                : List.of();

        // 超时时间：Number 类型兼容 Integer/Long/Double
        long timeoutMs = raw.get("timeoutMs") instanceof Number n
                ? n.longValue() : DEFAULT_TIMEOUT_MS;
        // 超时时间合理化：最小5秒，最大5分钟
        timeoutMs = Math.clamp(timeoutMs, 5_000L, 300_000L);

        return new TaskNode(nodeId, type, description, requiredTools, dependencies, timeoutMs);
    }

    // ──────────────────────── 辅助方法 ────────────────────────────────

    /** 根据索引生成唯一节点 ID。使用 "llm-N" 格式区分于规则引擎生成的节点。 */
    private String generateNodeId(int index) {
        return "llm-" + index;
    }
}
```

**关键设计决策说明**:

1. **规划器不做工具选择** — 工具匹配是 RespondStage ReAct 循环的职责。规划阶段只输出结构分解（节点类型、描述、依赖关系），`requiredTools` 在 JSON Schema 中标记为可选，LLM 示例中全部设为空数组。

2. **`parseNode()` 所有字段都有默认值** — LLM 可能漏填某个字段，不能让一个字段的缺失导致整个节点解析失败。

3. **`VALID_NODE_TYPES` 白名单** — LLM 可能编造节点类型，白名单确保只有合理类型进入后续流程。

4. **`timeoutMs` 使用 `Math.clamp`** — 防止 LLM 给出极端值（1ms 或 999999999ms）。

5. **`extractJSON()` 是 package-private** — 方便测试。不需要 mock LLM 时单独测试 JSON 提取逻辑。

6. **所有日志通过 SLF4J `@Slf4j` 输出** — 无 `System.out.println`。

7. **`revise()` 和 `decompose()` 委托给 `fallbackPlanner`** — 单一职责，LLMTaskPlanner 只负责 `plan()`。修订和分解逻辑已在 DAGTaskPlanner 中成熟实现。

---

### 3.2 PlanNodeDTO.java

**位置**: `lyclaw-framework/src/main/java/lyjew/com/lyclaw/task/PlanNodeDTO.java`

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanNodeDTO {

    /** 节点标识，如 "step-1" */
    private String id;

    /** 短标题，前端展示用，如 "分析需求" */
    private String title;

    /** 节点类型: ANALYZE | RESEARCH | DESIGN | PREPARE | EXECUTE | INTEGRATE | VERIFY */
    private String type;

    /** 详细描述 */
    private String description;

    /** 建议使用的工具名列表 */
    private List<String> tools = List.of();

    /** 前置节点的 id 列表（相互依赖关系） */
    private List<String> dependsOn = List.of();

    /** 预估耗时（秒），前端展示用 */
    private int estimatedSeconds;
}
```

**与 TaskNode 的对应关系**:

| PlanNodeDTO | TaskNode | 转换 |
|-------------|----------|------|
| id | nodeId | 直接映射 |
| title | — | 前端独有，从 description 中截取/生成 |
| type | type | 直接映射 |
| description | description | 直接映射 |
| tools | requiredTools | 直接映射 |
| dependsOn | dependencies | 直接映射 |
| estimatedSeconds | timeoutMs | seconds × 1000 ↔ ms / 1000 取整 |

`title` 字段是前端特有的——当用户拖拽工作流编辑器时，每个卡片需要短标题。如果不传 `title`，Converter 从 `description` 截取前 30 个字符作为默认标题。

---

### 3.3 PlanNodeConverter.java

**位置**: `lyclaw-framework/src/main/java/lyjew/com/lyclaw/task/PlanNodeConverter.java`

```java
public class PlanNodeConverter {

    /** 前端 DTO → 内部 TaskNode */
    public static TaskNode toTaskNode(PlanNodeDTO dto) {
        return new TaskNode(
            dto.getId(),
            dto.getType() != null ? dto.getType() : "EXECUTE",
            dto.getDescription() != null ? dto.getDescription() : "",
            dto.getTools() != null ? dto.getTools() : List.of(),
            dto.getDependsOn() != null ? dto.getDependsOn() : List.of(),
            dto.getEstimatedSeconds() > 0 ? dto.getEstimatedSeconds() * 1000L : 30000L
        );
    }

    /** 内部 TaskNode → 前端 DTO（SSE推送用） */
    public static PlanNodeDTO fromTaskNode(TaskNode node) {
        var dto = new PlanNodeDTO();
        dto.setId(node.getNodeId());
        dto.setTitle(truncate(node.getDescription(), 30));
        dto.setType(node.getType());
        dto.setDescription(node.getDescription());
        dto.setTools(node.getRequiredTools());
        dto.setDependsOn(node.getDependencies());
        dto.setEstimatedSeconds((int)(node.getTimeoutMs() / 1000));
        return dto;
    }

    /** 批量转换 */
    public static List<TaskNode> toTaskNodes(List<PlanNodeDTO> dtos) {
        return dtos.stream().map(PlanNodeConverter::toTaskNode).toList();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.isEmpty()) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
```

**为什么是静态工具类而不是 Spring Bean？**

这个转换是纯函数——无状态、无依赖、无副作用。不需要注入任何东西。做成静态方法可以让 `PlanExecutionStage`、`PlanController`、测试代码等任何地方直接调用，不会产生循环依赖。

---

## 四、修改文件详细设计

### 4.1 PlanController.java — selectPlanner() 加 case + plan() 加 null 检查

**改动点**: `selectPlanner()` 方法 + `plan()` 方法

```java
private TaskPlanner selectPlanner(String strategyName) {
    if (strategyName == null || strategyName.isBlank()) return defaultPlanner;
    return switch (strategyName.toLowerCase()) {
        case "cot"        -> cotPlanner != null ? cotPlanner : defaultPlanner;
        case "react"      -> reActPlanner != null ? reActPlanner : defaultPlanner;
        case "hierarchical" -> hierarchicalPlanner != null ? hierarchicalPlanner : defaultPlanner;
        case "llm"        -> llmTaskPlanner != null ? llmTaskPlanner : defaultPlanner;  // ← 新增
        default           -> defaultPlanner;
    };
}
```

**为什么把 "llm" 也放进 fallback 逻辑？**

`llmTaskPlanner` 被注入为 `@Nullable`（当 lyclaw-plan 服务没有 ChatFacade 时它是 null）。如果为 null，自动降级到 `defaultPlanner`（DAGTaskPlanner）。这个降级对调用方完全透明。

**plan() 方法增加双保险**：

虽然 LLMTaskPlanner 内部已做降级（永远不返回 null），但 PlanController 增加一层防御：

```java
@PostMapping("/plan")
public ResponseEntity<Map<String, Object>> plan(@RequestBody PlanRequest request) {
    ChatContext context = buildContext(request);
    TaskPlanner planner = selectPlanner(request.getStrategy());

    TaskPlan plan;
    try {
        plan = planner.plan(context, request.getUserIntent());
    } catch (Exception e) {
        log.warn("Planner {} failed, falling back to defaultPlanner", planner.getClass().getSimpleName(), e);
        plan = defaultPlanner.plan(context, request.getUserIntent());
    }

    // 双保险：如果 plan 仍然是 null，返回单节点兜底计划
    if (plan == null) {
        plan = SimpleTaskPlan.singleNode("EXECUTE", request.getUserIntent());
    }

    PlanValidator.ValidationResult validation = planValidator.validate(plan);
    // ... 后续不变 ...
}
```

**构造函数改动**: 加一个参数

```java
public PlanController(
    @Qualifier("DAGTaskPlanner") TaskPlanner defaultPlanner,
    // ... 现有参数 ...
    @Qualifier("llmTaskPlanner") @Nullable TaskPlanner llmTaskPlanner  // ← 新增
) {
    // ...
    this.llmTaskPlanner = llmTaskPlanner;
}
```

---

### 4.2 ChatRequest.java (orchestration DTO) — 加字段

**位置**: `lyclaw-orchestration/src/main/java/lyjew/com/lyclaw/orchestration/dto/ChatRequest.java`

```java
@Data
public class ChatRequest {
    private String sessionId;
    private List<Map<String, String>> messages;
    private boolean stream;

    // === 以下新增 ===

    /** 执行模式: "dag" 按DAG分层执行 | "react" 自由ReAct循环 (默认) */
    private String planMode;

    /** 规划器选择: "llm" LLM驱动 | "rule" 规则引擎 (默认) */
    private String planner;

    /** 可选，前端直传的执行计划。非空时跳过Planner直接消费 */
    private List<PlanNodeDTO> plan;
}
```

**字段默认值处理（在 PlanExecutionStage 中）**:

```java
String planMode = request.getPlanMode() != null ? request.getPlanMode() : "react";
String planner  = request.getPlanner()  != null ? request.getPlanner()  : "rule";
```

默认 `react` + `rule` = 完全保持现有行为不变。

---

### 4.3 PlanExecutionStage.java — 核心改造

**改动位置**: `execute()` 方法，约第 110-203 行

**改造后的流程**:

```java
@Override
public Flux<ServerSentEvent<String>> execute(ChatContext context, PipelineContext pipelineCtx) {
    if (pipelineCtx.isTerminated()) return Flux.empty();

    return Flux.create(sink -> {
        String traceId = context.getTracing().getTraceId();
        try {
            String sessionId = context.getRequest().getSessionId();
            String userMessage = context.getRequest().getLastUserMessage();

            // === 从上下文读取 flag（由 OrchestrationController 写入） ===
            String planMode = (String) context.getAttribute("planMode");
            if (planMode == null) planMode = "react";

            String planner = (String) context.getAttribute("planner");
            if (planner == null) planner = "rule";

            List<PlanNodeDTO> frontendPlan = (List<PlanNodeDTO>) context.getAttribute("frontendPlan");

            // ==================== 分支1：前端直传计划 ====================
            if (frontendPlan != null && !frontendPlan.isEmpty()) {
                log.info(logJson("INFO", "plan_source", "PLAN", traceId,
                        "Using frontend-supplied plan: " + frontendPlan.size() + " nodes", null));
                sink.next(sseEvent("plan_status", "使用前端提供的执行计划"));

                List<TaskNode> nodes = PlanNodeConverter.toTaskNodes(frontendPlan);
                for (TaskNode node : nodes) {
                    pipelineCtx.addNode(node);
                    PlanNodeDTO dto = PlanNodeConverter.fromTaskNode(node);
                    sink.next(sseEvent("plan_node", toJSON(dto)));
                }
                sink.next(sseEvent("plan_complete", "已加载 " + nodes.size() + " 个步骤"));
                sink.complete();
                return;
            }

            // ==================== 分支2：react 模式，跳过规划 ====================
            if ("react".equals(planMode)) {
                log.info(logJson("INFO", "plan_skip", "PLAN", traceId,
                        "planMode=react, skipping planning, will use free-form ReAct", null));
                pipelineCtx.getCurrentStage().set("PLAN");
                sink.next(sseEvent("plan_status", "自由推理模式"));
                sink.complete();
                return;
            }

            // ==================== 分支3：dag 模式，调规划服务 ====================
            pipelineCtx.getCurrentStage().set("PLAN");
            context.getTracing().beginStage("PLAN");
            long t3 = System.currentTimeMillis();

            log.info("\n\n══════════════════════════════════");
            log.info("  [阶段 2/6] 任务规划 - planMode=dag, planner={}", planner);
            log.info("══════════════════════════════════");

            sink.next(sseEvent("plan_status", planner.equals("llm") ? "AI正在分析任务..." : "正在生成执行计划..."));

            PlanRequest planReq = PlanRequest.builder()
                    .sessionId(sessionId)
                    .userIntent(userMessage)
                    .strategy(planner)     // ← 复用 strategy 字段传递 planner flag
                    .context(Map.of("sessionId", sessionId, "timestamp", System.currentTimeMillis()))
                    .build();

            long planCallStart = System.currentTimeMillis();
            Map<String, Object> planResult = planFeignClient.plan(planReq);
            long planCallDuration = System.currentTimeMillis() - planCallStart;
            log.info(logJson("INFO", "feign_call", "PLAN", traceId,
                    "planFeignClient.plan completed, planner=" + planner, planCallDuration));

            // 解析返回的节点（与现有代码相同）
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawNodes = planResult != null && planResult.get("nodes") instanceof List
                    ? (List<Map<String, Object>>) planResult.get("nodes")
                    : Collections.emptyList();
            for (Map<String, Object> raw : rawNodes) {
                // ... 与现有代码相同的解析逻辑 ...
                pipelineCtx.addNode(new TaskNode(...));
            }

            log.info(logJson("INFO", "plan_result", "PLAN", traceId,
                    "Plan generated: " + pipelineCtx.getNodes().size() + " task(s)", null));

            sink.next(sseEvent("plan_complete", "已生成 " + pipelineCtx.getNodes().size() + " 个步骤"));

            // 逐个推送节点详情（使用 DTO 格式，前端友好）
            List<TaskNode> nodes = pipelineCtx.getNodes();
            for (int i = 0; i < nodes.size(); i++) {
                PlanNodeDTO dto = PlanNodeConverter.fromTaskNode(nodes.get(i));
                sink.next(sseEvent("plan_node", toJSON(dto)));
            }

            long stage3Duration = System.currentTimeMillis() - t3;
            context.getTracing().endStage("PLAN");
            if (metricsCollector != null) {
                metricsCollector.recordPipelineStage("PLAN", stage3Duration);
            }

            pipelineCtx.getCurrentStage().set("EXECUTE");
            sink.complete();

        } catch (Exception e) {
            // 降级：规划服务不可用时，回退到 react 模式
            log.error(logJson("ERROR", "stage_error", "PLAN", traceId,
                    "Planning failed, falling back to ReAct: " + e.getMessage(), null));
            pipelineCtx.getCurrentStage().set("EXECUTE");
            sink.next(sseEvent("plan_status", "规划服务不可用，切换到自由推理模式"));
            // nodes 保持空，RespondStage 会走 ReAct 路径
            sink.complete();
        }
    });
}
```

**关键设计决策**:

1. **flag 从哪里取？** 从 `ChatContext.attributes` 中取，而非直接在 PlanExecutionStage 里读 ChatRequest。因为 OrchestrationController 在构造 ChatContext 时把 flag 写入 attributes，这样所有 Stage 都能读，不依赖具体的 DTO 类型。

2. **分支1（前端直传 plan）优先于分支3（后端生成）**。因为前端可能拖拽了一个精心设计的工作流，后端不应该覆盖它。

3. **catch 里不设 pipelineOk=true，而是让 nodes 保持空。** 这样 RespondStage 会自然走到 ReAct 路径——这是一条更安全的降级。

---

### 4.4 RespondStage.java — 加 DAG 执行路径

**改动位置**: `execute()` 方法，约第 95-98 行

**改造后的分流逻辑**:

```java
@Override
public Flux<ServerSentEvent<String>> execute(ChatContext context, PipelineContext pipelineCtx) {
    if (pipelineCtx.isTerminated() || !pipelineCtx.isPipelineOk()) {
        return Flux.empty();
    }

    return Flux.defer(() -> {
        String traceId = context.getTracing().getTraceId();
        // ...

        List<TaskNode> planNodes = pipelineCtx.getNodes();  // ← 读 DAG

        List<ToolDefinition> toolDefs;
        try {
            toolDefs = actionFeignClient.listTools();
        } catch (Exception e) {
            toolDefs = Collections.emptyList();
        }

        Flux<ServerSentEvent<String>> bodyFlux;
        if (!planNodes.isEmpty()) {
            // ========== DAG执行路径 ==========
            bodyFlux = executeDAGPlan(context, traceId, toolDefs, planNodes);
        } else if (chatFacade != null && !toolDefs.isEmpty()) {
            // ========== 原有ReAct流式路径(不改) ==========
            bodyFlux = streamWithToolDetection(context, traceId, toolDefs);
        } else if (chatFacade != null) {
            bodyFlux = simpleChatStream(context, traceId);
        } else {
            // 降级兜底
            bodyFlux = Flux.just(sseEvent("message", buildFinalResponse(...)));
        }

        return Flux.just(sseEvent("respond_start", "Generating AI response"))
                .concatWith(bodyFlux)
                .onErrorResume(...);
    });
}
```

**新增方法**:

#### 4.4.1 buildDAGPrompt() — DAG → 提示词文本

```java
/**
 * 将 TaskNode DAG 转换为 LLM 可理解的结构化提示词。
 *
 * 输出包含：
 * 1. 步骤总览 — 编号列表
 * 2. 依赖关系图 — from → to 链
 * 3. 可并行节点识别
 * 4. 每步骤推荐工具（仅供参考）
 */
private String buildDAGPrompt(List<TaskNode> nodes) {
    // ... 见前次讨论的完整实现 ...
}
```

#### 4.4.2 topologicalSort() — Kahn 分层

```java
/**
 * Kahn 算法分层拓扑排序。
 *
 * 返回: List<List<TaskNode>>
 *   - 外层list按执行顺序
 *   - 内层list是同一层内可并行执行的节点
 *
 * 复杂度: O(V+E)
 */
private List<List<TaskNode>> topologicalSort(List<TaskNode> nodes) {
    // ... 见前次讨论的完整实现 ...
}
```

#### 4.4.3 executeDAGPlan() — 分层执行入口（修复竞态条件）

```java
/**
 * 按DAG分层执行所有节点。
 *
 * 核心设计——消息隔离:
 *  同一层内的节点并行执行，但它们共享 context.getRequest().getMessages()
 *  会导致竞态条件。因此每层采用"快照-分发-合并"模式:
 *    1. 层开始前: 快照当前 messages
 *    2. 每个节点: 深拷贝快照 + 自己的指令 → 独立执行 → 返回 (结果, 自己的消息历史)
 *    3. 层结束后: 合并所有节点的消息历史 → 写回 messages → 下一层基于合并结果继续
 *
 * 每个节点内部仍然是完整的 ReAct 循环（见 executeDAGNode）。
 */
private Flux<ServerSentEvent<String>> executeDAGPlan(
        ChatContext context, String traceId,
        List<ToolDefinition> toolDefs, List<TaskNode> planNodes) {

    List<List<TaskNode>> layers = topologicalSort(planNodes);

    // 先注入 DAG 总览提示词（一次性，所有节点都能看到）
    String dagPrompt = buildDAGPrompt(planNodes);
    if (dagPrompt != null && !dagPrompt.isEmpty()) {
        context.getRequest().getMessages().add(
                Message.builder().role("system").content(dagPrompt).build());
    }

    // 逐层执行
    Flux<ServerSentEvent<String>> resultFlux = Flux.empty();
    for (int i = 0; i < layers.size(); i++) {
        List<TaskNode> layer = layers.get(i);
        resultFlux = resultFlux.concatWith(
                executeLayer(context, traceId, toolDefs, layer, i + 1, layers.size())
        );
    }
    return resultFlux;
}
```

#### 4.4.4 executeLayer() — 单层内并行（消息隔离版）

```java
/**
 * 并行执行一层中的所有节点，每个节点操作自己的消息副本。
 *
 * 流程:
 *  1. 快照当前全局 messages (不可变)
 *  2. flatMap 并行订阅所有节点（每个节点独立消息副本，内部 subscribeOn boundedElastic）
 *  3. collectList 等待所有节点完成 → 保证后续写回是单线程的
 *  4. 写回: 所有节点的 assistant 摘要追加到全局消息列表（安全，因为 collectList 后只有一条路径）
 *  5. 推送 SSE 事件
 *
 * 为什么用 collectList 而不是 mergeSequential 内联写回?
 *  executeDAGNode 内部 subscribeOn(Schedulers.boundedElastic())，
 *  如果 flatMapMany 回调直接在 mergeSequential 的源中执行，
 *  多个节点可能在 boundedElastic 的不同线程上同时完成，
 *  导致对 context.getRequest().getMessages() 的并发写入。
 *  collectList 确保"所有节点完成 → 单线程写回"，消除了竞态。
 */
private Flux<ServerSentEvent<String>> executeLayer(
        ChatContext context, String traceId,
        List<ToolDefinition> toolDefs, List<TaskNode> layer,
        int layerNum, int totalLayers) {

    log.info(logJson("INFO", "dag_layer_start", "RESPOND", traceId,
            "Layer " + layerNum + "/" + totalLayers + ", " + layer.size() + " node(s)", null));

    // 1. 快照当前全局消息列表（不可变，所有节点共享读）
    List<Message> snapshot = List.copyOf(context.getRequest().getMessages());

    // 2+3. 并行执行所有节点 → 等待全部完成 → 收集结果
    return Flux.fromIterable(layer)
            .flatMap(node -> executeDAGNode(context, traceId, toolDefs, node, snapshot))
            .collectList()
            .flatMapMany(results -> {
                // 4. 所有节点已完成，单线程安全地写回全局消息列表
                List<ServerSentEvent<String>> events = new ArrayList<>();
                for (NodeResult result : results) {
                    context.getRequest().getMessages().add(Message.builder()
                            .role("assistant")
                            .content("[步骤完成 Layer" + layerNum + ": " + result.nodeType + "] "
                                    + result.summary)
                            .build());
                    events.add(sseEvent("dag_node_complete",
                            "{\"layer\":" + layerNum + ",\"nodeType\":\"" + result.nodeType
                                    + "\",\"summary\":\"" + escapeJson(result.summary) + "\"}"));
                    if (result.output != null && !result.output.isEmpty()) {
                        events.add(sseEvent("message", result.output));
                    }
                }
                log.info(logJson("INFO", "dag_layer_complete", "RESPOND", traceId,
                        "Layer " + layerNum + "/" + totalLayers + " complete, "
                                + results.size() + " node(s) done", null));
                return Flux.fromIterable(events);
            });
}

/** 单节点执行结果 */
private record NodeResult(String nodeType, String summary, String output) {}
```

#### 4.4.5 executeDAGNode() — 单节点独立执行（消息副本版）

```java
/**
 * 在独立的消息副本上执行一个 DAG 节点。
 *
 * 每个节点拿到的是层开始前的全局消息快照 + 本节点的执行指令。
 * 在副本上自由进行 ReAct 循环（多轮推理 + 工具调用），
 * 不会污染全局消息列表或其他并行节点的副本。
 *
 * @param snapshot 层开始前的全局消息快照（不可变）
 * @return NodeResult 包含节点类型、摘要和完整输出
 */
private Mono<NodeResult> executeDAGNode(
        ChatContext context, String traceId,
        List<ToolDefinition> toolDefs, TaskNode node,
        List<Message> snapshot) {

    return Mono.fromCallable(() -> {
        // 深拷贝消息列表 → 本节点独享，其他并行节点不可见
        List<Message> localMessages = new ArrayList<>(snapshot);

        // 为本节点构造专用指令
        localMessages.add(Message.builder()
                .role("user")
                .content("请执行步骤: [" + node.getType() + "] " + node.getDescription()
                        + (node.getRequiredTools() != null && !node.getRequiredTools().isEmpty()
                                ? "\n建议工具: " + String.join(", ", node.getRequiredTools()) : ""))
                .build());

        // 构造本节点的独立请求上下文
        lyjew.com.lyclaw.model.ChatRequest localRequest =
                context.getRequest().toBuilder()
                        .messages(localMessages)
                        .tools(toolDefs)
                        .toolChoice("auto")
                        .stream(false)
                        .build();

        try {
            // 调 LLM，如果返回 tool_calls 则进入 ReAct 循环
            ModelResponse response = chatFacade.chat(localRequest);

            String finalOutput;
            if (response.hasToolCalls()) {
                // 本节点的独立 ReAct 循环（操作 localMessages，不影响全局）
                finalOutput = runReActLoopWithMessages(localRequest, traceId, response, toolDefs);
            } else {
                finalOutput = response.getContent() != null ? response.getContent() : "完成";
            }

            // 摘要取 finalOutput 前 200 字符，避免消息列表膨胀
            String summary = finalOutput.length() > 200
                    ? finalOutput.substring(0, 200) + "..."
                    : finalOutput;

            return new NodeResult(node.getType(), summary, finalOutput);

        } catch (Exception e) {
            log.error(logJson("ERROR", "dag_node_error", "RESPOND", traceId,
                    "Node execution failed: " + node.getType() + " - " + e.getMessage(), null));
            return new NodeResult(node.getType(),
                    "[失败: " + e.getMessage() + "]", null);
        }
    }).subscribeOn(Schedulers.boundedElastic());
}
```

**为什么 executeDAGNode 不用保存/恢复全局消息列表了？**

旧方案在 `finally` 块做 `clear()` + `addAll(saved)`，在并行场景下会互相覆盖。
新方案改为**快照-分发-合并**：每个节点拿到层开始前的不可变快照，深拷贝后在本地自由操作。
执行完成后，只有最终摘要写回全局消息列表（在 executeLayer 的 flatMapMany 中单线程追加），
全局列表的读写不再有竞态。

**下游节点能看到上游的完整交互记录吗？**

不能——下游只看到上游的摘要。如果 INTEGRATE 节点需要上游 RESEARCH 节点的完整工具调用结果，
应该通过 `pipelineCtx.getToolResults()` 获取，而不是从消息列表中解析。
如果确实需要完整交互记录，把 `NodeResult` 的 `output` 存入 `pipelineCtx` 即可。

**runReActLoopWithMessages 与现有 runReActLoop 的区别？**

`runReActLoopWithMessages` 接收独立的 `localRequest`（含自己的 messages 副本），
循环逻辑与现有 `runReActLoop` 完全一致，只是操作的是 localRequest 的 messages 而非
context.getRequest().getMessages()。这是对现有方法的一个简单重载，不重复代码。

#### 4.4.6 节点失败处理

```java
/**
 * DAG 节点失败策略。
 *
 * 关键取舍：
 *  - 单节点失败不应中断整个 DAG（一个 RESEARCH 失败不代表 DESIGN 的结果不能用）
 *  - 但下游依赖失败节点的步骤需要知道上游挂了
 *
 * 实现：
 *  1. executeDAGNode 内部 catch 所有异常 → 返回 NodeResult(summary="[失败: ...]")
 *     不抛异常 = 不中断当前层的其他并行节点
 *  2. 失败节点在全局消息列表中追加 assistant "上游步骤 [X] 执行失败，请酌情处理"
 *  3. 下游节点从 pipelineCtx 中读取上游状态，自行决定是否继续
 *  4. pipelineCtx.failCount 累加，MetricsStage 统一上报
 */

// executeDAGNode 中已在 catch 块实现（见上文）：
// } catch (Exception e) {
//     return new NodeResult(node.getType(), "[失败: " + e.getMessage() + "]", null);
// }
```

**设计决策：为什么不直接终止整个 DAG？**

DAG 中不同分支是独立子任务。如果 "搜索资料" 失败但 "分析需求" 成功，
后续 "整合" 节点可以基于成功的分支继续，降级处理失败的路径。
这比"一个节点挂了全体重来"更贴合实际工作场景，也避免了 Token 浪费。

---

### 4.5 OrchestrationController.java — flag 提取 + 写入 attributes

**改动点**: `buildChatContext()` 方法，约第 219-231 行

```java
private ChatContext buildChatContext(
        lyjew.com.lyclaw.model.ChatRequest modelRequest,
        Session session, String traceId, ChatRequest dto) {  // ← 加 dto 参数

    MemoryContent memory = new MemoryContent("", "", true, Collections.emptyList(), 0.0);
    ChatContext context = new ChatContext(
            modelRequest, session, memory,
            Collections.emptyList(), interceptorChain, chatFacade, traceId
    );

    // === 新增：将前端 flag 写入 attributes，供管线阶段读取 ===
    context.setAttribute("planMode", dto.getPlanMode() != null ? dto.getPlanMode() : "react");
    context.setAttribute("planner", dto.getPlanner() != null ? dto.getPlanner() : "rule");
    if (dto.getPlan() != null && !dto.getPlan().isEmpty()) {
        context.setAttribute("frontendPlan", dto.getPlan());
    }

    return context;
}
```

**为什么用 attributes 而不是 ChatContext 的成员变量？**

`ChatContext.memory` 是 `final` 且无 setter，教训在前。`attributes` 是一个 `Map<String, Object>`，专门为"临时扩展数据"设计——不污染 ChatContext 的类型定义。flag 类数据是"请求级别的元数据"，放 attributes 里正合适。

**两个调用点都需要更新**：

`chatStream()` 和 `chat()` 都调用了 `buildChatContext()`，参数从 3 个变 4 个：

```java
// chatStream() (line 79 原代码)
ChatContext context = buildChatContext(modelRequest, session, traceId, request);  // ← 加第4参

// chat() (line 100 原代码)
ChatContext context = buildChatContext(modelRequest, session, traceId, request);  // ← 加第4参
```

`chat()` 端点虽然不流式推送，但也需要让 flag 生效——用户可能通过同步端点使用 DAG 规划。

---

### 4.6 PipelineContext.java — 不需要改

确认现有字段够用：

| 字段 | DAG执行路径是否使用 | 说明 |
|------|--------------------|------|
| `nodes` | ✅ 使用 | PlanExecutionStage写入，RespondStage读取 |
| `toolResults` | ✅ 使用 | executeDAGNode执行时追加结果 |
| `successCount` | ✅ 使用 | 跟踪成功/失败节点数 |
| `failCount` | ✅ 使用 | 同上 |
| `pipelineOk` | ✅ 使用 | ReflectionStage设置，RespondStage检查 |
| `terminated` | ✅ 使用 | 控制流 |

不需要新增字段。

---

### 4.7 ReflectionStage.java — 调整执行顺序（RespondStage 之后）

**当前状态**：

```java
@PipelineStage(name = "Reflection", after = PlanExecutionStage.class, group = "CORE")
public class ReflectionStage extends PipelineStageBase {
    // getOrder() = 3
    // ...
    pipelineCtx.setPipelineOk(true);  // ← 放行 RespondStage
}
```

```java
@PipelineStage(name = "Respond", after = ReflectionStage.class, group = "POSTPROCESSING")
public class RespondStage extends PipelineStageBase {
    // getOrder() = 4
    // execute() 第一行: if (!pipelineCtx.isPipelineOk()) return Flux.empty();
}
```

**问题**：ReflectionStage 在 PlanExecutionStage 之后、RespondStage 之前执行。但 DAG 模式下的工具执行结果在 RespondStage 中产生，ReflectionStage 需要这些结果才能评估质量。

**改动**：

```java
// ReflectionStage: after 改为 RespondStage.class, order 从 3 改为 4
@PipelineStage(name = "Reflection", after = RespondStage.class, group = "POSTPROCESSING")
public class ReflectionStage extends PipelineStageBase {
    @Override
    public int getOrder() { return 4; }
    // ...
    // execute() 不再需要 setPipelineOk(true) —— 管线执行由 OrchestratorImpl 的 concatWith 链自然控制
    // 如果改成在 RespondStage 之后执行，setPipelineOk() 调用可保留（兼容 RespondStage 的检查逻辑）
}
```

**注意**：交换后管线顺序变为：
```
ContextBuild(0) → SecurityCheck(1) → PlanExecution(2) → Respond(3) → Reflection(4) → Metrics(5)
```

ReflectionStage 的 `setPipelineOk(true)` 原本是放行 RespondStage 的门控。交换后 Reflection 在 Respond 后面，这个门控不再需要。但为了保持代码兼容性（今后可能有其他 stage 检查 pipelineOk），可保留调用。

**RespondStage 原先的 `pipelineOk` 检查**：

现在 RespondStage 是先于 ReflectionStage 运行了。在 DAG 模式下，需要确保 RespondStage 自身的 `pipelineOk` 检查不会拒绝自己（PlanExecutionStage 不应该提前置 false）。这个检查在新顺序下变为：**前置阶段（PlanExecution）失败时才跳过 Respond**，逻辑仍然成立。

---

## 五、完整数据流

```
用户发送: { planMode: "dag", planner: "llm", messages: [...] }

OrchestrationController.chatStream()
  → resolveSession()
  → buildModelRequest(dto)               ← messages 转成 List<Message>
  → buildChatContext(modelReq, session, traceId, dto)
      context.setAttribute("planMode", "dag")
      context.setAttribute("planner", "llm")
  → orchestrator.execute(context)
     │
     ├─ ContextBuildStage (order=0)
     │     → 检索记忆 → 打日志 → 发SSE（不改）
     │
     ├─ SecurityCheckStage (order=1)
     │     → 安全检查（不改）
     │
     ├─ PlanExecutionStage (order=2)
     │     → planMode="dag", frontendPlan=null
     │     → 分支3: 调远程规划
     │     → PlanRequest.strategy = "llm"
     │     → planFeignClient.plan(planReq)
     │         │
     │         ▼  HTTP到lyclaw-plan服务
     │     PlanController.plan()
     │         → selectPlanner("llm") → llmTaskPlanner
     │         → llmTaskPlanner.plan(context, userIntent)
     │             → buildPlannerSystemPrompt()
     │             → chatFacade.chat(planRequest)
     │             → extractJSON() → parsePlanJSON()
     │             → return SimpleTaskPlan(nodes)
     │         → planValidator.validate()
     │         → return { plan, validation, ... }
     │     ← HTTP响应
     │     → 解析rawNodes → pipelineCtx.addNode(...)  [7个TaskNode]
     │     → 逐个推送 plan_node SSE事件
     │     → 推送 plan_complete
     │
     ├─ RespondStage (order=3)
     │     → pipelineCtx.getNodes() 非空 → 走DAG路径
     │     → 快照全局消息列表
     │     → buildDAGPrompt(nodes) → 结构化文本注入 system 消息
     │     → topologicalSort(nodes) → 4层
     │     → executeDAGPlan()
     │         第0层: [ANALYZE] ────────── 1个节点，独立消息副本
     │         第1层: [RESEARCH, DESIGN, PREPARE] ─── 3节点并行，各自独立副本
     │               每节点独立ReAct循环（推理+工具调用）
     │               层结束后合并摘要到全局消息列表
     │         第2层: [INTEGRATE] ──────── 等第1层全部完成，基于合并后的全局消息
     │         第3层: [EXECUTE, VERIFY] ── 2节点并行
     │     → Flux<SSE> 流式推送给前端
     │
     ├─ ReflectionStage (order=4)
     │     → 评估工具执行结果 → 评分 → 报告存入 pipelineCtx.reportRef
     │     → 注意：此时 toolResults、successCount、failCount 已由 RespondStage 填充
     │
     └─ MetricsStage (order=5)
           → 统计 → 持久化摘要 → done事件
```

---

## 六、兜底降级链路

三层兜底，确保 Agent 永远不会"卡死"：

```
第1层: LLM规划 (LLMTaskPlanner 内部)
  planner="llm" → LLMTaskPlanner.plan()
    → LLM 响应正常且 JSON 可解析 → 返回 DAG ✅
    → LLM 返回不可解析 JSON → catch → log.warn → 内部调 fallbackPlanner.plan() → 降级到第2层 ⬇
    → LLM 调用超时/异常 → catch → log.warn → 内部调 fallbackPlanner.plan() → 降级到第2层 ⬇
    → ChatFacade 为 null (构造时 @Nullable) → LLMTaskPlanner.plan() 直接调 fallbackPlanner.plan() ⬇

第2层: 规则引擎规划 (DAGTaskPlanner / PlanController 双保险)
  LLMTaskPlanner 内部降级 → DAGTaskPlanner.plan()
    → 关键词匹配正常 → 返回 DAG ✅
    → 无法匹配任何关键词 → 返回单节点计划 ✅ (规则引擎永远不会返回 null)
  
  PlanController 兜底（双保险）:
    → planner.plan() 抛未捕获异常 → catch → defaultPlanner.plan() → 返回计划 ✅
    → plan 仍为 null → SimpleTaskPlan.singleNode("EXECUTE", userIntent) ✅

第3层: 自由ReAct (PlanExecutionStage catch)
  planFeignClient 完全不可达 → Feign 调用抛异常
    → PlanExecutionStage catch → nodes 保持空
    → RespondStage 检查 nodes.isEmpty() → 走原有 streamWithToolDetection() ReAct ✅
    → 即使 RespondStage 也失败 → onErrorResume → buildFinalResponse() 兜底 ✅

关键保障:
  - 任何一层失败都不会导致用户得不到回复
  - 最坏情况：LLM规划失败 → 规则引擎失败 → Feign不可达 → ReAct自由模式 → 兜底文本
  - 每一层都有明确的日志（WARN级别），方便排查降级原因
```

---

## 七、验证方法

### 7.1 单元测试

| 测试对象 | 测试内容 |
|---------|---------|
| `LLMTaskPlanner` | mock ChatFacade 返回合法JSON → 验证解析正确；返回非法JSON → 验证返回null；返回空content → 验证返回null |
| `PlanNodeConverter` | DTO→TaskNode 往返测试；空字段处理；seconds↔ms 转换正确性 |
| `topologicalSort()` | 简单链(3节点) → 3层各1节点；复杂扇出(根→3并行→合并) → 验证第1层1节点第2层3节点第3层1节点；空列表 → 空结果；单节点 → 单层 |
| `buildDAGPrompt()` | 有依赖节点的DAG → 验证输出含依赖关系描述；无依赖节点 → 验证输出含并行提示 |

### 7.2 集成测试（启动全部服务）

**测试1: react 模式（默认，验证向后兼容）**
```
curl -X POST /api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"sessionId": "test-1", "planMode": "react", "messages": [{"role":"user","content":"今天天气怎么样"}]}'
```
期望: 日志中出现 `planMode=react, skipping planning` → 走原有ReAct流式路径

**测试2: dag模式 + 规则引擎**
```
curl -d '{"sessionId":"test-2", "planMode":"dag", "planner":"rule",
  "messages":[{"role":"user","content":"帮我写一个Spring Boot的Hello World并测试启动"}]}'
```
期望: PlanExecutionStage调Feign → DAGTaskPlanner生成中等计划(4节点) → RespondStage走DAG路径 → 前端收到 plan_node ×4

**测试3: dag模式 + LLM规划（重点）**
```
curl -d '{"sessionId":"test-3", "planMode":"dag", "planner":"llm",
  "messages":[{"role":"user","content":"设计一个电商系统的数据库，包括用户表商品表订单表，考虑高并发缓存策略"}]}'
```
期望: PlanExecutionStage → Feign → PlanController → LLMTaskPlanner → LLM返回DAG JSON → 7+节点 → 前端收到 plan_status "AI正在分析任务..." → plan_node 逐个推送

**测试4: 前端直传计划**
```
curl -d '{"sessionId":"test-4", "planMode":"dag",
  "plan":[
    {"id":"s1","title":"搜索资料","type":"RESEARCH","description":"搜索Spring Boot最佳实践","tools":["web_search"],"dependsOn":[],"estimatedSeconds":30},
    {"id":"s2","title":"写代码","type":"EXECUTE","description":"编写Hello World","tools":["command"],"dependsOn":["s1"],"estimatedSeconds":60}
  ],
  "messages":[{"role":"user","content":"帮我做Spring Boot研究"}]}'
```
期望: 跳过规划服务 → 直接转换前端plan为TaskNode → 走DAG执行路径 → plan_node 推 s1 和 s2

**测试5: LLM规划失败降级**
```
# 停掉ChatFacade或故意让LLM返回垃圾数据
curl -d '{"sessionId":"test-5", "planMode":"dag", "planner":"llm", ...}'
```
期望: LLM规划失败 → 日志出现 "LLM planning failed, falling back to rule-based" → DAGTaskPlanner接手 → 正常执行

**测试6: 规划服务完全不可用降级**
```
# 停掉lyclaw-plan服务
curl -d '{"sessionId":"test-6", "planMode":"dag", "planner":"llm", ...}'
```
期望: PlanExecutionStage catch → nodes保持空 → RespondStage走ReAct → 用户仍能得到回复

**测试7: DAG节点失败不中断整体执行**
```
# 模拟场景：LLM 生成的计划包含 web_search 步骤，但工具服务不可用
curl -d '{"sessionId":"test-7", "planMode":"dag", "planner":"llm",
  "messages":[{"role":"user","content":"搜索最新AI新闻然后总结"}]}'
```
期望: web_search 节点返回 `NodeResult("[失败: ...]")` → 不中断同层其他节点 → 后续总结节点收到上游失败标记 → 降级输出

### 7.3 观察要点

- `plan_node` SSE 事件是否使用了 `PlanNodeDTO` 格式（`estimatedSeconds` 而非 `timeoutMs`）
- DAG 路径中同一层的节点是否真正并行执行（通过日志时间戳判断。并行节点的 start 时间戳应接近）
- 同层并行节点是否各自有独立的消息副本（日志中不应出现 `clear()` 调用引发的消息丢失）
- LLM 规划器的 prompt 是否正确生成（在 LLM 调用日志中检查）
- 降级链路是否平滑（任何一层失败都不影响用户最终获得回复）
- 节点失败时 `dag_node_complete` SSE 事件是否携带正确的失败标记
- 下游节点是否能看到上游节点的摘要信息（而非完整交互记录）
