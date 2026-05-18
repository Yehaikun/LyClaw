# LyClaw Agent 封装思路计划

> 目标：废除编排管线路径（路径1），将 Web 层精简为直接调用 Agent 代理（路径2），同时吸收 LangChain4j 和 LangChain V1 的成熟设计模式。

---

## 目录

1. [现状：两条独立路径](#一现状两条独立路径)
2. [LangChain4j 的设计启示](#二langchain4j-的设计启示)
3. [LangChain V1 中间件模型](#三langchain-v1-中间件模型)
4. [目标架构](#四目标架构)
5. [Agent 内部执行流程](#五agent-内部执行流程)
6. [Hook 体系扩展：从 3 个到 6 个](#六hook-体系扩展从-3-个到-6-个)
7. [Planner 集成：计划能力下沉](#七planner-集成计划能力下沉)
8. [Reflection 集成：反思能力下沉](#八reflection-集成反思能力下沉)
9. [Web 层精简](#九web-层精简)
10. [多 Agent 协作：Supervisor 模式](#十多-agent-协作supervisor-模式)
11. [与 LangChain4j 的对比](#十一与-langchain4j-的对比)
12. [实施阶段](#十二实施阶段)

---

## 一、现状：两条独立路径

当前 LyClaw 存在两条并行的请求处理路径：

```
路径1（编排管线）— 6 阶段 Pipeline，功能完整但重量级
═══════════════════════════════════════════════════════════
  HTTP Controller → OrchestrationService → Orchestrator
      → ContextBuild → SecurityCheck → PlanExecution
      → Reflection → Respond(ReAct) → Metrics
      → SSE 流返回前端

路径2（Agent 代理）— JDK 动态代理，轻量但功能不完整
═══════════════════════════════════════════════════════════
  用户接口(@Agent) → AgentInvocationHandler
      → Hook.beforeRequest (安全/沙箱)
      → Hook.wrapToolExecutor (审批)
      → ReActEngine.execute/executeStream
      → Hook.afterResult
      → 返回结果
```

| 对比维度 | 编排管线（路径1） | Agent 代理（路径2） |
|---------|------------------|-------------------|
| 入口方式 | HTTP Controller + OrchestrationService | @Agent 注解接口 |
| 安全审核 | SecurityCheckStage | SecurityCheckHook（已实现） |
| 沙箱隔离 | RespondStage → ActionExecutor → ToolSandbox | SandboxHook（已实现） |
| 计划生成 | PlanExecutionStage → TaskPlanner | **缺失** |
| 反思评估 | ReflectionStage → ReflectionEngine | **缺失** |
| 工具审批 | RespondStage → DefaultReActEngine | ApprovalHook（已实现） |
| 指标采集 | MetricsStage → MemorySystem | **缺失** |
| 多 Agent 协作 | 无（仅单 Agent 循环） | **缺失** |
| 代码量 | Controller + Service + 6 Stage ≈ 1500 行 | Handler + 3 Hook ≈ 400 行 |

**核心问题**：路径1 是完整的，但太重（Spring MVC 注解、stage 依赖、PipelineContext 传递）。路径2 轻量，但缺少计划、反思、多 Agent 协作能力。未来的方向是让路径2 吞并路径1 的全部能力。

---

## 二、LangChain4j 的设计启示

### 2.1 AiServices：JDK 动态代理 + 15 步执行管线

LangChain4j 的 `AiServices` 是 Agent 代理的参考标杆。它同样使用 `Proxy.newProxyInstance()` 生成用户接口的代理实现，内部有一条 15 步的执行管线：

```
1. 获取 InvocationContext（方法、参数、注解）
2. 解析 @SystemMessage 模板 → 渲染系统消息
3. 解析 @UserMessage 模板 → 渲染用户消息
4. 应用 systemMessageTransformer
5. 构建 ChatRequest（含 tools、toolChoice）
6. 应用 chatRequestTransformer
7. 发射 AiServiceStartedEvent
8. 调用 ToolService.executeInferenceAndToolsLoop()
9. 发射 AiServiceCompletedEvent
10. 解析返回值类型（String/List/自定义类型）
11. 应用 returnValueTransformer
12. 若返回 TokenStream → 适配为 Publisher/Flux
13. 若返回 ChatResponse → 直接返回
14. 若返回自定义类型 → JSON 反序列化
15. 返回结果
```

**启示**：LyClaw 的 `AgentInvocationHandler.invoke()` 已经实现了步骤 2-3（`resolveSystemMessage` / `resolveUserMessage`）和步骤 8（委托 `ReActEngine`）。第 4、6、11 步的 "Transformer" 本质上是 **Hook 的另一种叫法**。

### 2.2 PlannerBasedInvocationHandler：状态机编排

LangChain4j 的 Agentic 模块在 AiServices 之上叠加了一层 **Planner 状态机**：

```
Agent 接口方法调用
  → PlannerBasedInvocationHandler.invoke()
    → Planner.firstAction(PlanningContext)
    → PlannerLoop.loop()
        WHILE not done:
          → 执行 Agent（同步/并行）
          → 子 Agent 完成后回调 onSubagentInvoked()
          → Planner.nextAction(PlanningContext)
          → 持久化执行状态（崩溃恢复）
    → 返回结果
```

`Planner` 是一个策略接口，有 6 种实现：顺序、并行、条件路由、循环、监督者（LLM 驱动）、并行映射（MapReduce）。每种实现的核心差异仅在于 `nextAction()` 的决策逻辑。

**启示**：LyClaw 当前的 `TaskPlanner` 只生成静态任务列表（DAG 图），缺少执行期的动态编排能力。引入 Planner 模式可以让 Agent 在执行过程中根据中间结果动态调整后续步骤。

### 2.3 AgenticScope：黑板模式

`AgenticScope` 是 Agent 间的共享状态空间：

```
AgenticScope
  ├── state: ConcurrentHashMap<String, Object>   // 共享状态
  ├── agentInvocations: List<AgentInvocation>     // 调用历史
  ├── context: List<AgentMessage>                 // 对话上下文
  └── Kind: EPHEMERAL / REGISTERED / PERSISTENT   // 生命周期
```

**启示**：LyClaw 当前的 `ChatContext` 和 `PipelineContext` 承载了类似职责，但耦合了管线特定的字段。在 Agent 路径中需要一个新的轻量级 `AgentScope` 替代它们。

---

## 三、LangChain V1 中间件模型

LangChain V1 使用 **StateGraph + 洋葱模型（Onion Model）** 实现中间件链：

```
START → [before_agent] → [before_model] → model → [after_model]
                                                    ↓
                                               [tools 节点]
                                                    ↓
                                         [loop back to before_model]
                                                    ↓
                                          [after_agent] → END
```

核心抽象 `AgentMiddleware` 暴露 6 个钩子点：

| 钩子 | 触发时机 | LyClaw 对应 |
|------|---------|------------|
| `before_agent` | Agent 调用开始前 | `Hook.beforeRequest` |
| `before_model` | 每次 LLM 调用前 | **缺失**（ReAct 循环内部不可拦截） |
| `after_model` | 每次 LLM 调用后 | **缺失** |
| `wrap_model_call` | 包装 LLM 调用（可修改请求/响应） | **缺失** |
| `wrap_tool_call` | 包装工具调用（可修改参数/结果） | `Hook.wrapToolExecutor` |
| `after_agent` | Agent 调用结束后 | `Hook.afterResult` |

**核心差异**：LangChain V1 的中间件是 **循环内拦截**（每次 LLM 调用和工具调用都可以被拦截），而 LyClaw 当前的 Hook 是 **循环外拦截**（仅在 ReAct 循环前后拦截）。这意味着 LyClaw 的 Hook 看不到 ReAct 第二轮及之后的 LLM 调用细节。

**启示**：LyClaw 的 Hook 体系需要从 "循环级" 扩展到 "步骤级"，允许中间件在 ReAct 的每一次迭代中介入。

---

## 四、目标架构

废除管线路径后，所有请求统一走 Agent 代理路径：

```
                          ┌──────────────────────────┐
                          │     lyclaw-web (HTTP)     │
                          │  ChatController (精简)    │
                          │  ApprovalController       │
                          │  HealthController         │
                          └──────────┬───────────────┘
                                     │ 直接调用
                                     ▼
┌─────────────────────────────────────────────────────────────┐
│                   AgentProxyFactory                          │
│  为 @Agent 接口创建 JDK 动态代理                              │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                AgentInvocationHandler                        │
│                                                              │
│  ① Hook.beforeRequest (安全审核、内容过滤)                    │
│  ② 检查是否需要 Planning（简单对话跳过）                      │
│  ③ Hook.beforeModel (计划注入、上下文准备)                   │
│  ④ ReActEngine.executeStream() ─────────────────┐            │
│     │  每次 LLM 调用:                             │            │
│     │    ├── Hook.beforeModel 拦截               │ 循环内     │
│     │    ├── LLM 推理                             │ Hook       │
│     │    ├── Hook.afterModel 拦截                │ 拦截       │
│     │    ├── Hook.wrapToolCall 拦截              │            │
│     │    └── 工具执行                             │            │
│     │  每次循环结束:                              │            │
│     │    └── Reflection 检查（可选）              │            │
│     └────────────────────────────────────────────┘            │
│  ⑤ Hook.afterResult (指标采集、结果校验)                      │
│                                                              │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    Hook 链 (6 个标准 Hook)                    │
│                                                              │
│  order=10  SecurityCheckHook   安全审核 + 内容过滤            │
│  order=20  SandboxHook         工具执行隔离                   │
│  order=30  PlanningHook        计划生成 → 注入 system prompt │
│  order=40  ApprovalHook        工具审批（人机协同）           │
│  order=50  ReflectionHook      每轮反思 + 最终质量评估        │
│  order=60  MetricsHook         指标采集 + 持久化              │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

**关键变化**：

1. **ChatController 不再注入 OrchestrationService**，改为注入 AgentProxyFactory 或直接注入 Agent 接口的代理 Bean
2. **PlanningHook** 替代 `PlanExecutionStage`：在 `beforeModel` 中检查是否需要生成计划，将计划文本注入 system prompt
3. **ReflectionHook** 替代 `ReflectionStage`：在每轮 ReAct 循环的 `afterModel` 中做轻量校验，在循环结束后做质量评估
4. **MetricsHook** 替代 `MetricsStage`：在 `afterResult` 中采集指标
5. **Orchestrator、PipelineContext、6 个 Stage 类全部废弃**

---

## 五、Agent 内部执行流程

### 5.1 循环级 Hook（控制 Agent 的启动和终止）

```
AgentInvocationHandler.invoke()
│
├─ ① SecurityCheckHook.beforeRequest()
│     ├─ ContentFilter.filter(userMessage) → 提示注入检测
│     └─ SecurityManager.approve(sessionId, "EXECUTE_CHAT") → 获取沙箱级别
│
├─ ② 构建 ChatRequest（system prompt + user message + tools）
│
├─ ③ 检查是否启用 Planning：
│     若禁用 → 跳过
│     若启用 → PlanningHook.beforeModel():
│       ├─ TaskPlanner.plan(userIntent) → 生成 TaskNode DAG
│       ├─ 将 "请按以下步骤执行：1. xx 2. xx" 注入 system prompt
│       └─ 将 TaskNode 列表存入 AgentScope（供 ReflectionHook 对照）
│
├─ ④ 进入 ReAct 循环（最多 30 轮）
│   │
│   │ 每轮开始:
│   │   ├─ Hook.beforeModel(request, roundNum)
│   │   │    ├─ PlanningHook: 检查是否完成当前子任务，推进到下一步
│   │   │    └─ 其他 beforeModel 逻辑
│   │   │
│   │   ├─ LLM 调用 (stream=true 探测 / 非流式推理)
│   │   │
│   │   ├─ Hook.afterModel(response, roundNum)
│   │   │    ├─ ReflectionHook: 轻量级异常检测
│   │   │    │    ├─ 空响应检测
│   │   │    │    ├─ 重复内容检测（连续 3 轮相同输出）
│   │   │    │    ├─ 幻觉标记检测（tool_call 引用不存在的工具）
│   │   │    │    └─ 若检测到异常 → 注入纠正提示到下一轮 messages
│   │   │    └─ 其他 afterModel 逻辑
│   │   │
│   │   ├─ 若无 tool_calls → 退出循环，返回纯文本
│   │   │
│   │   ├─ Hook.wrapToolCall(toolCall)
│   │   │    ├─ SandboxHook: 沙箱级别检查 → 隔离执行
│   │   │    └─ ApprovalHook: 若工具非只读 → 发射 tool_approval SSE → 等待用户确认
│   │   │
│   │   └─ 工具执行 → 结果追加到 messages
│   │
│   └─ 循环结束
│
├─ ⑤ ReflectionHook.afterResult(finalResult)
│     ├─ ReflectionEngine.assessQuality(output, criteria) → 质量评分
│     ├─ ErrorDetector.detect(output) → 事实错误检查
│     └─ 若评分低于阈值 → 可选地追加一轮纠正 LLM 调用
│
├─ ⑥ MetricsHook.afterResult()
│     ├─ 采集: 轮次数、工具调用数、耗时、token 消耗
│     └─ 持久化到 MemorySystem
│
└─ ⑦ 返回结果
```

### 5.2 步骤级 vs 循环级对比

| 拦截粒度 | 当前（v1.0） | 目标（v2.0） |
|---------|-------------|-------------|
| Agent 启动 | `beforeRequest` | `beforeRequest` |
| 每次 LLM 调用前 | **不可拦截** | `beforeModel` |
| 每次 LLM 调用后 | **不可拦截** | `afterModel` |
| 每次工具调用 | `wrapToolExecutor` | `wrapToolCall` |
| Agent 结束 | `afterResult` | `afterResult` |

v2.0 新增的 `beforeModel` 和 `afterModel` 是步骤级拦截，它们让 Planning 和 Reflection 可以深入到 ReAct 循环内部。

---

## 六、Hook 体系扩展：从 3 个到 6 个

### 6.1 AgentHook 接口扩展

当前接口有 3 个方法，需要扩展到 5 个：

```
当前 (v1.0):
  beforeRequest(AgentContext)
  wrapToolExecutor(ToolExecutor, AgentContext) → ToolExecutor
  afterResult(String, AgentContext) → String

扩展 (v2.0):
  beforeRequest(AgentContext)
  beforeModel(ChatRequest, int roundNum, AgentContext)          ← 新增
  afterModel(ModelResponse, int roundNum, AgentContext)         ← 新增
  wrapToolCall(ToolCall, AgentContext) → ToolCall               ← 取代 wrapToolExecutor
  afterResult(String, AgentContext) → String
```

### 6.2 六个标准 Hook 的职责

#### SecurityCheckHook（order=10）

- **`beforeRequest`**：调用 `ContentFilter.filter()` 检测提示注入/PII；调用 `SecurityManager.approve()` 确定沙箱级别
- **运行阶段**：Agent 启动前，一次性
- **替代**：`SecurityCheckStage`

#### SandboxHook（order=20）

- **`wrapToolCall`**：将工具调用包装在沙箱中执行，根据沙箱级别（DIRECT / RESTRICTED / ISOLATED）控制文件系统和网络访问
- **运行阶段**：每次工具调用时
- **替代**：`RespondStage` 中的 `ActionExecutor.executeTool()` 调用链

#### PlanningHook（order=30）

- **`beforeModel`（首次）**：调用 `TaskPlanner.plan(userIntent)` 生成任务 DAG，将计划文本注入 system prompt
- **`beforeModel`（后续轮次）**：检查当前轮次的工具调用结果是否完成了当前子任务，推进游标到下一子任务
- **运行阶段**：Agent 启动时 + 每轮 LLM 调用前
- **替代**：`PlanExecutionStage`
- **可配置**：简单对话（"今天天气怎么样"）自动跳过，由 LLM 快速分类决定是否启用

#### ApprovalHook（order=40）

- **`wrapToolCall`**：对非只读工具（如文件写入、命令执行、网络请求），发射 `tool_approval` SSE 事件，阻塞等待用户确认（60s 超时）
- **运行阶段**：每次工具调用时
- **替代**：`DefaultReActEngine` 内置的审批逻辑

#### ReflectionHook（order=50）

- **`afterModel`（每轮）**：轻量级异常检测（空响应、重复输出、幻觉标记），若检测到异常则注入纠正提示
- **`afterResult`（循环结束）**：调用 `ReflectionEngine.assessQuality()` 评估输出质量，包括准确性、完整性、安全性、用户体验四个维度
- **运行阶段**：每轮 LLM 调用后 + Agent 结束后
- **替代**：`ReflectionStage`

#### MetricsHook（order=60）

- **`beforeModel`**：记录 LLM 调用开始时间
- **`afterModel`**：计算 token 消耗、响应延迟
- **`afterResult`**：汇总全部指标 → 持久化到 `MemorySystem`
- **运行阶段**：贯穿全流程
- **替代**：`MetricsStage`

---

## 七、Planner 集成：计划能力下沉

### 7.1 设计思路

LangChain4j 的 `Planner` 核心思想是 **"计划即策略"** ——计划不是一次生成的静态 DAG，而是每步根据上一步结果动态决定下一步。但 LangChain4j 的 Planner 也有代价：每次决策需要 LLM 调用（SupervisorAgent），增加了 2-5 秒延迟。

LyClaw 的 `TaskPlanner` 当前只生成静态 DAG 图（`TaskNode` 列表），不参与执行期决策。对于 LyClaw 的定位（单体 Agent 而非多 Agent 系统），**完全动态的 Planner 是过度设计**。

### 7.2 LyClaw 的折中方案：计划注入

保持 `TaskPlanner` 的静态 DAG 生成，但通过 `PlanningHook` 将计划注入到 ReAct 循环：

```
PlanningHook 工作流程:

1. [beforeModel(首次)] 判断是否需要计划:
   - 调用 LLM（低成本模型，如 deepseek-v4-flash）做意图分类: "simple" / "complex"
   - "simple" → 不生成计划，跳过
   - "complex" → TaskPlanner.plan(userIntent) → 生成 TaskNode DAG

2. [beforeModel(首次)] 注入计划到 system prompt:
   原始: "你是一个有用的 AI 助手..."
   注入后: "你是一个有用的 AI 助手...
            
            ## 执行计划
            1. 检索相关数据
            2. 分析数据模式
            3. 生成可视化建议
            4. 撰写分析报告
            
            ## 当前进度
            开始执行第 1 步。"

3. [beforeModel(第N轮)] 根据工具调用结果推进进度:
   - 检查 tool result 是否符合当前子任务完成条件
   - 推进游标 → 更新 "## 当前进度" 为 "已完成第1步，开始第2步"
```

**不采用 LangChain4j Planner 的原因**：

- LyClaw 的定位是单 Agent 系统，LangChain4j Planner 是为多 Agent 编排设计的
- LangChain4j Planner 的每次动态决策都需要 LLM 调用（SupervisorAgent），成本高
- LyClaw 已有的 `TaskPlanner` 生成的 DAG 图对单 Agent 够用
- 简单的 "意图分类 + 计划注入" 方案在 90% 场景下足够，且不增加 LLM 调用次数

---

## 八、Reflection 集成：反思能力下沉

### 8.1 两层反思

将反思拆分为两个层次：

**层次 1：循环内轻量校验（afterModel，每轮）**

不增加 LLM 调用，纯规则检测：
- 空响应 → 追加 "Please continue your response." 到 messages
- 连续 3 轮相同输出 → 追加 "Your last 3 responses were identical. Please provide new information or conclude."
- tool_call 引用不存在的工具 → 追加错误提示，列出可用工具
- 响应内容截断（最后一句不完整）→ 追加 "continue"

**层次 2：循环后质量评估（afterResult，仅最终）**

调用 LLM 评估最终输出质量：
- 准确性：是否包含事实错误
- 完整性：是否回应了用户的所有问题
- 安全性：是否包含敏感内容
- 用户体验：格式、语气、可读性

若评分低于阈值（如 60 分），可自动发起一次纠正 LLM 调用。

### 8.2 与 ReflectionStage 的对比

| 维度 | ReflectionStage（旧） | ReflectionHook（新） |
|------|----------------------|---------------------|
| 执行时机 | ReAct 循环结束后，作为独立 Stage | 每轮循环中有轻量校验，循环后有质量评估 |
| LLM 调用 | 1 次（质量评估） | 1 次（仅最终评估） |
| 循环内纠正 | **不支持** | 支持（规则检测 → 注入提示） |
| 代码复杂度 | 独立 Stage + QualityCriteria + ErrorDetector + StrategyAdjuster | 1 个 Hook，约 200 行 |
| 纠正机制 | 通过 PipelineContext 传递 | 直接修改 ReAct 循环中的 messages 列表 |

---

## 九、Web 层精简

### 9.1 当前 ChatController

```java
// 当前：依赖 OrchestrationService
@PostMapping(value = "/chat/stream", produces = TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request, ...) {
    Session session = orchestrationService.resolveSession(request.getSessionId());
    return orchestrationService.chatStream(request, traceId, session);
}
```

### 9.2 精简后 ChatController

```java
// 精简后：直接注入 Agent 代理 Bean
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatAgent chatAgent;  // @Agent 接口的 JDK 代理

    @PostMapping(value = "/chat/stream", produces = TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request, ...) {
        return chatAgent.chatStream(
            request.getMessages(),
            request.getSessionId()
        );
    }
}
```

### 9.3 ChatAgent 接口定义

```java
@Agent(
    name = "lyclaw-chat",
    description = "LyClaw 通用聊天 Agent",
    planning = true,       // 启用计划（复杂任务自动触发）
    reflection = true      // 启用反思
)
public interface ChatAgent {

    @SystemMessage("你是 LyClaw AI 助手，今天是 {{current_date}}。")
    Flux<ServerSentEvent<String>> chatStream(
        @UserMessage List<Message> messages,
        @V("sessionId") String sessionId
    );
}
```

### 9.4 可删除的组件

| 删除项 | 原因 |
|--------|------|
| `Orchestrator` / `OrchestratorImpl` | 不再需要管线编排 |
| `PipelineContext` | 不再需要管线上下文传递 |
| 6 个 Pipeline Stage 类 | 全部由 Hook 替代 |
| `OrchestrationService` | 不再需要业务逻辑中间层 |
| `InteractionModeProcessor` | 不再需要解析 @InteractionMode（ReAct 引擎成为唯一模式） |
| `lyclaw-orchestration` 模块 | 整个模块可废弃（或重建为轻量级多 Agent 编排库） |

### 9.5 保留的组件

| 保留项 | 原因 |
|--------|------|
| `AgentInvocationHandler` | Agent 代理核心执行引擎 |
| `DefaultReActEngine` | LLM 推理-行动循环 |
| `AgentHook` 接口 + 6 个实现 | 安全/沙箱/计划/审批/反思/指标 |
| `AgentProxyFactory` | 创建代理实例 |
| `AgentInterfaceProcessor` | 扫描 @Agent 接口并注册 Bean |
| `ToolRegistry` + `ActionExecutor` | 工具注册和执行 |
| `ChatFacade` | LLM 路由和调用 |
| `MemorySystem` | 记忆持久化 |
| `StorageFacade` | 会话和实体存储 |
| `TaskPlanner` | 计划生成（被 PlanningHook 使用） |
| `ReflectionEngine` | 反思评估（被 ReflectionHook 使用） |
| 3 个 Controller | HTTP 入口（精简后） |

---

## 十、多 Agent 协作：Supervisor 模式

### 10.1 定位

废除编排管线后，多 Agent 协作仍是需求（例如：数据分析场景中，一个 Agent 负责数据检索，一个 Agent 负责统计分析，一个 Agent 负责可视化建议）。LangChain4j 的 `SupervisorAgent` 提供了参考方案。

### 10.2 设计思路

不立即实现，但预留扩展点：

```
预留: @SupervisorAgent 注解
═══════════════════════════════════

@Agent(name = "data-analysis-supervisor")
public interface DataAnalysisSupervisor {

    @SystemMessage("""
        你是数据分析主管。你可以调用以下子 Agent:
        - data_retriever: 从数据库中检索原始数据
        - statistical_analyzer: 执行统计分析
        - visualization_advisor: 提供可视化建议
        
        根据用户需求，决定调用哪个子 Agent，综合分析结果后给出最终回答。
        """)
    Flux<ServerSentEvent<String>> analyze(String userRequest);
}
```

关键差异：Supervisor Agent 的 `ChatRequest.tools` 列表中包含的是**子 Agent 的调用入口**（而非普通工具），子 Agent 的调用也经过完整的 Hook 链（安全、沙箱、审批）。

### 10.3 与 LangChain4j SupervisorAgent 的对比

| 维度 | LangChain4j SupervisorAgent | LyClaw 预留给 Supervisor |
|------|---------------------------|------------------------|
| 实现方式 | `AiServices` 创建的 LLM Agent + `SupervisorAgentServiceImpl` | @Agent 接口 + Hook 链 |
| 子 Agent 调用 | PlannerBasedInvocationHandler → Planner → PlannerLoop | AgentInvocationHandler 递归（子 Agent 也是代理对象） |
| 状态管理 | AgenticScope（黑板） | AgentScope（ConcurrentHashMap） |
| 持久化 | checkpoint 到 AgenticScopeStore | MemorySystem |
| 崩溃恢复 | saveState / restoreState | 暂不支持（延后到 v3.0） |

---

## 十一、与 LangChain4j 的对比

| 设计要素 | LangChain4j | LyClaw（目标架构） |
|---------|------------|-------------------|
| **代理机制** | `Proxy.newProxyInstance()` + `AiServices` | `Proxy.newProxyInstance()` + `AgentInvocationHandler` |
| **执行管线** | 15 步静态管线（`DefaultAiServices`） | Hook 链（6 个标准 Hook，可插拔） |
| **编排模型** | Planner 状态机 + PlannerLoop（8 种拓扑） | PlanningHook（计划注入）+ 单 Agent ReAct |
| **拦截粒度** | 循环级（仅在 AiServices 入口/出口） | 循环级 + 步骤级（beforeModel/afterModel） |
| **状态管理** | AgenticScope（黑板）+ checkpoint 持久化 | AgentScope（轻量版，无崩溃恢复） |
| **多 Agent** | SupervisorAgent + PlannerBasedInvocationHandler | @SupervisorAgent 注解（预留），子 Agent = 工具 |
| **模板系统** | `@SystemMessage` + `@UserMessage` + `@V` + PromptTemplateFactory | `@SystemMessage` + `@UserMessage` + `@V`（已对齐） |
| **Hook/中间件** | 无内置中间件（通过 Transformer 和 Listener 扩展） | AgentHook SPI（6 个标准 Hook） |
| **流式支持** | TokenStream + TokenStreamAdapter | Flux<ServerSentEvent<String>>（Reactor） |
| **工具审批** | 无内置支持 | ApprovalHook（SSE → 前端 → 回调） |

**LyClaw 的差异化优势**：

1. **步骤级 Hook 拦截** — LangChain4j 的 Transformer 只在入口/出口生效，LyClaw 的 Hook 可以介入 ReAct 循环的每一轮 LLM 调用
2. **工具审批（人机协同）** — LangChain4j 没有内置的工具审批机制，LyClaw 有完整的 SSE → ApprovalStore → 回调流程
3. **沙箱隔离** — LangChain4j 无沙箱概念，LyClaw 有三级沙箱（DIRECT / RESTRICTED / ISOLATED）
4. **Spring Boot 原生集成** — Bean 自动发现、自动配置、`@Agent` 接口自动代理

**LangChain4j 的优势**（LyClaw 暂不追赶）：

1. **多 Agent 编排** — 8 种拓扑（顺序/并行/条件/循环/监督者/MapReduce），LyClaw 只有单 Agent
2. **崩溃恢复** — PlannerLoop 的 saveState/restoreState + checkpoint 持久化
3. **社区生态** — 30+ 模型集成、文档、示例

---

## 十二、实施阶段

### 阶段 1：Hook 接口扩展（1-2 天）

- 扩展 `AgentHook` 接口，新增 `beforeModel`、`afterModel` 方法
- 修改 `AgentInvocationHandler`，在 ReAct 循环中调用步骤级 Hook
- 修改 `DefaultReActEngine`，暴露内部 LLM 调用点供 Hook 拦截
- 为现有 3 个 Hook 添加空实现（`beforeModel` / `afterModel` 默认为 no-op）
- 编译验证

### 阶段 2：新建 PlanningHook（1-2 天）

- 在 `AgentInvocationHandler` 中新增 `beforeModel`（首次）逻辑：调用 `TaskPlanner.plan()`，注入计划文本
- 实现 "意图分类"：低成本 LLM 判断用户请求是 simple 还是 complex
- 实现 "进度追踪"：在后续 `beforeModel` 中根据工具调用结果推进计划游标
- 将 `@Agent` 注解扩展 `planning` 属性（默认 false）

### 阶段 3：新建 ReflectionHook（1-2 天）

- 实现循环内轻量校验（空响应/重复/幻觉/截断检测，纯规则，无 LLM 调用）
- 实现循环后质量评估（调用 LLM，4 维度评分）
- 实现纠正注入逻辑（检测到异常时追加提示到 messages）
- 将 `@Agent` 注解扩展 `reflection` 属性（默认 false）

### 阶段 4：新建 MetricsHook（0.5 天）

- 采集轮次数、工具调用数、耗时、token 消耗
- 在 `afterResult` 中汇总并持久化到 `MemorySystem`

### 阶段 5：Web 层精简 + 旧代码删除（1-2 天）

- 修改 `ChatController`，注入 `ChatAgent` 代理 Bean 替代 `OrchestrationService`
- 修改 `ApprovalController` 和 `HealthController`（基本不变）
- 删除 `OrchestrationService`、`Orchestrator`、`OrchestratorImpl`
- 删除 `PipelineContext`、6 个 Stage 类
- 删除 `lyclaw-orchestration` 模块（或降级为空壳保留向后兼容）
- 全量编译 + 启动 + curl 测试 SSE 流

### 阶段 6：多 Agent 协作（预留，v3.0）

- 设计 `@SupervisorAgent` 注解
- 实现子 Agent 作为工具的注册机制
- 实现 AgenticScope 状态共享
- 工作流拓扑（至少支持顺序和并行）

### 实施优先级总结

```
必须做（v2.0）:
  ✅ 阶段 1: Hook 接口扩展（步骤级拦截）
  ✅ 阶段 2: PlanningHook（替代 PlanExecutionStage）
  ✅ 阶段 3: ReflectionHook（替代 ReflectionStage）
  ✅ 阶段 4: MetricsHook（替代 MetricsStage）
  ✅ 阶段 5: Web 层精简 + 管线代码删除

预留（v3.0）:
  ⏳ 阶段 6: 多 Agent 协作（Supervisor 模式）
  ⏳ 崩溃恢复（checkpoint / saveState / restoreState）
  ⏳ 工作流可视化（DAG 图编辑）
```

---

## 附录：架构演进对比

```
v1.0（当前）                         v2.0（目标）
════════════════════════════════     ════════════════════════════════

HTTP 入口                            HTTP 入口
  ↓                                    ↓
OrchestrationService                 ChatAgent（@Agent 接口代理）
  ↓                                    ↓
Orchestrator                         AgentInvocationHandler
  ↓                                    ↓
6 个 Pipeline Stage                  Hook 链（6 个 Hook）
  ├─ ContextBuild                    ├─ SecurityCheckHook
  ├─ SecurityCheck   → Feign         ├─ SandboxHook
  ├─ PlanExecution   → Feign         ├─ PlanningHook  ← 替代 PlanExecutionStage
  ├─ Reflection      → Feign         ├─ ApprovalHook
  ├─ Respond (ReAct)                 ├─ ReflectionHook ← 替代 ReflectionStage
  └─ Metrics         → Feign         └─ MetricsHook    ← 替代 MetricsStage
  ↓                                    ↓
ReActEngine                          ReActEngine（扩展步骤级拦截）
  ↓                                    ↓
SSE → 前端                           SSE → 前端

代码量: ~2500 行                      代码量: ~1200 行
模块数: 9 个（含 orchestration）       模块数: 8 个（无 orchestration）
请求路径: 2 条（管线 + 代理）          请求路径: 1 条（代理）
Hook 拦截: 循环级                      Hook 拦截: 循环级 + 步骤级
多 Agent: 不支持                       多 Agent: 预留
```
