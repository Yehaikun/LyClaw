# LyClaw Agent 封装思路计划

> 目标：将 Stage 管线嵌入 Agent 代理内部，每个 @Agent 接口调用走完整的管线流程。
> 13 个 SPI 全覆盖，@Agent 配置可扩展不硬编码。对标 2026 年行业主流 Agent 框架的能力全景，
> 在沙箱隔离、工具审批、双层拦截上做差异化创新。

---

## 目录

1. [行业 Agent 框架能力全景对标](#一行业-agent-框架能力全景对标)
2. [架构总览](#二架构总览)
3. [Stage 管线](#三stage-管线)
4. [Hook 体系：步骤级拦截](#四hook-体系步骤级拦截)
5. [AgentContext：统一上下文](#五agentcontext统一上下文)
6. [工具执行管线](#六工具执行管线)
7. [13 个框架 SPI](#七13-个框架-spi)
8. [@Agent 配置可扩展机制](#八agent-配置可扩展机制)
9. [Web 层精简](#九web-层精简)
10. [差异化创新总结](#十差异化创新总结)
11. [实施阶段与优先级](#十一实施阶段与优先级)

---

## 一、行业 Agent 框架能力全景对标

### 1.1 2026 年主流 Agent 框架通用功能栈

所有主流框架都在做同一件事——把 LLM 从"一次问答"升级为"自主完成任务"。它们共同封装了 8 层能力：

```
┌─────────────────────────────────────────────────────────────────┐
│                     Agent 框架通用功能栈                         │
├─────────────────────────────────────────────────────────────────┤
│  ① Agent 定义层      角色、目标、系统提示词、状态变量              │
│  ② 多Agent编排层     手递手 | 顺序 | 并行 | 层级 | 投票           │
│  ③ 任务规划层        ReAct | Plan-First | 图式 | 动态重规划        │
│  ④ 工具调用层        Function Call | MCP | 动态筛选 | 沙箱        │
│  ⑤ 记忆系统层        工作记忆 | 语义/情景记忆 | 程序记忆           │
│  ⑥ 安全护栏层        输入校验 | 输出审核 | 人机介入 | 审批         │
│  ⑦ 可观测性层        链路追踪 | 调试 | 指标 | 检查点回放           │
│  ⑧ 跨框架互通层      MCP | A2A | UTCP                             │
└─────────────────────────────────────────────────────────────────┘
```

### 1.2 各层能力详解与 LyClaw 对标

#### ① Agent 定义与生命周期

| 能力 | 行业标杆 | LyClaw 现状 | 差距 |
|------|---------|-----------|------|
| 角色/目标/系统提示词绑定 | CrewAI 的 `Agent(role, goal, backstory)` 最简洁 | `@Agent` + `@SystemMessage` / `@UserMessage` | 已有 |
| 状态变量管理 | LangGraph 的 State + checkpoint | AgentContext.attributes | 缺类型安全的状态 Schema |
| 会话持久化 | Google ADK 的 Session + PostgreSQL | StorageFacade（file 模式） | 缺关系型 DB 后端 |
| Agent 即工具 | AutoGen 的嵌套 Agent 对话 | 无 | **缺失**（预留 @SupervisorAgent） |

#### ② 多 Agent 编排模式

| 模式 | 代表框架 | 机制 | LyClaw 现状 |
|------|---------|------|-----------|
| Handoff（手递手） | OpenAI Agents SDK | Agent A 完成→控制权交给 Agent B | **缺失** |
| Role-based（角色协作） | CrewAI, MetaGPT | 预定义角色，按流程分工 | **缺失** |
| Conversation-driven（对话式） | AutoGen | Agent 间消息通信，无中心调度 | **缺失** |
| Hierarchical（层级树） | Google ADK, LangGraph | 父 Agent 分派→子 Agent 可再分派 | **缺失** |
| Graph-based（图式） | LangGraph | 节点+边定义状态机 | 当前 Stage 链 ≈ 简化版 |
| Voting/Debate（投票辩论） | AutoGen, CAMEL | 多输出→投票→最佳结果 | **缺失** |

当前 `OrchestratorImpl.executeAgentTask()` 只是模拟实现，发送固定事件序列。

#### ③ 任务规划

| 策略 | 框架 | LyClaw 现状 |
|------|------|-----------|
| ReAct 循环 | 几乎所有框架 | ✅ `DefaultReActEngine` |
| Plan-First（先生成计划，人工审核后执行） | Osprey, Alpha Berkeley | ✅ `TaskPlanner.plan()` + DAG |
| Plan+Execute（规划→执行→反思） | 你的 LyClaw 当前模式 | ✅ 4 种策略（DAG/CoT/ReAct/Hierarchical） |
| Reflexion（失败后自我批评→修正→重试） | AgenticAI, LangGraph | ❌ 缺失 |
| CoT + Tree Search | 学术框架 | ❌ 缺失 |

#### ④ 工具调用

| 能力 | 行业现状 | LyClaw 现状 |
|------|---------|-----------|
| Function Calling | 所有框架标配 | ✅ `@Tool` + ToolRegistry |
| MCP 协议 | 2025-2026 标配，Claude SDK 支持最深 | ❌ **缺失** |
| 动态工具筛选 | 根据上下文自动过滤工具 | ❌ 缺失（当前全部工具传给 LLM） |
| 沙箱执行 | OpenAI SDK v0.14+、AutoGen、Claude SDK | ✅ 3 级沙箱（DIRECT/RESTRICTED/ISOLATED） |
| 审批流 | CrewAI 的 human_input，LangGraph 的 interrupt | ✅ ApprovalStore + SSE 审批流 |

#### ⑤ 记忆系统

| 层次 | 内容 | 技术 | LyClaw 现状 |
|------|------|------|-----------|
| 工作记忆 | 当前对话上下文、中间结果 | 会话缓冲区 | ✅ ChatRequest.messages |
| 语义/情景记忆 | 历史对话摘要、用户偏好 | Vector DB + RAG | ⚠️ MemorySystem 有基础，缺向量检索 |
| 程序性记忆 | Agent 学到的策略、启发式规则 | 动态调整系统提示词 | ❌ 缺失 |

#### ⑥ 安全护栏

| 类型 | 行业标杆 | LyClaw 现状 |
|------|---------|-----------|
| Input Guardrail | OpenAI SDK 三层 Guardrail | ✅ SecurityCheckStage（ContentFilter + SecurityManager） |
| Output Guardrail | 输出审核 | ❌ **缺失**（ReflectionStage 被移除后没有输出审核） |
| Tool Guardrail | 工具执行前权限检查 | ✅ ApprovalStore + ToolCallPolicy |
| Human-in-the-Loop | CrewAI human_input, LangGraph interrupt | ✅ ApprovalHook（SSE 审批流） |

#### ⑦ 可观测性

| 能力 | 行业标杆 | LyClaw 现状 |
|------|---------|-----------|
| 链路追踪 | LangSmith, OpenAI SDK 内置 | ✅ Tracing（traceId + stage span） |
| 检查点/时间旅行 | LangGraph checkpoint 可回退到任意状态 | ❌ 缺失（PERSISTENT 模式预留） |
| Debug 模式 | Google ADK `adk web` CLI | ❌ 缺失 |
| 指标采集 | — | ✅ MetricsCollector + MetricsStage |

#### ⑧ 跨框架互通

| 协议 | 职责 | 状态 | LyClaw 现状 |
|------|------|------|-----------|
| MCP (Model Context Protocol) | Agent ↔ 工具/资源标准化接口 | 2025 事实标准，200+ Server | ❌ **缺失** |
| A2A (Agent-to-Agent) | 不同框架 Agent 相互发现和调用 | Google 主导，已并入 Linux 基金会 | ❌ **缺失** |
| UTCP (Universal Tool Calling) | 跨语言工具调用协议 | Lattice 提出 | ❌ 缺失 |

### 1.3 LyClaw 对标结论

```
LyClaw 已具备（行业对齐）:
  ✅ ReAct 循环          ✅ 任务规划（4 种策略）   ✅ 沙箱隔离（行业领先）
  ✅ 工具审批             ✅ 输入安全护栏           ✅ 链路追踪 + 指标
  ✅ 记忆系统（基础）     ✅ @Agent 注解 + 模板    ✅ Stage 管线编排

LyClaw 缺失（需要补齐）:
  ❌ MCP 协议 — 2025-2026 行业标配，必须对齐
  ❌ 多 Agent 协作 — 当前只是模拟，需真正的 Handoff/Hierarchical
  ❌ 输出护栏 — 安全审核只做了输入，输出也需要
  ❌ 动态工具筛选 — 工具多了 prompt 爆炸
  ❌ 语义记忆 — 向量检索
  ❌ 检查点回放 — 长任务崩溃恢复
  ❌ A2A 协议 — 跨框架互通

补的优先级:
  P0: 沙箱 + 审批（已有，巩固）
  P1: MCP 协议 + 输出护栏 + 双层拦截 + 上下文增强
  P2: 动态工具筛选 + 语义记忆 + 检查点回放 + 混合 Planner
  P3: 多 Agent + A2A + Reflexion
```

---

## 二、架构总览

### 2.1 请求路径（单一路径）

```
HTTP POST /api/chat/stream
  → ChatController (直接调用 Agent 代理)
    → @Agent 接口 (JDK 动态代理)
      → AgentInvocationHandler.invoke()
        → 解析 @SystemMessage / @UserMessage 模板
        → 创建 AgentContext
        → 合并配置源 (注解 + yml + Builder + DB, 优先级叠加)
        → 启动 Stage 管线 ───────────────────────────────┐
          │                                               │
          │  Stage 0: ContextBuild    加载记忆             │
          │  Stage 1: SecurityCheck   安全审核 (输入护栏)   │
          │  Stage 2: PlanExecution   生成任务 DAG         │
          │  Stage 3: Respond         ReAct 循环 ──────┐  │
          │  Stage 4: Metrics         指标 + 持久化      │  │
          │                                               │  │
          │  Respond 内部 (步骤级 Hook + 工具执行管线):    │  │
          │    每轮: beforeModel → LLM → afterModel        │  │
          │          → 若有 tool_calls:                    │  │
          │            ToolExecutionPipeline ──────────┐  │  │
          │              → ToolResolver → ToolCallPolicy│  │  │
          │              → ToolHook.beforeExecution     │  │  │
          │              → ParameterBinder → execute    │  │  │
          │              → ToolHook.afterExecution      │  │  │
          │              → ResultFormatter               │  │  │
          │            └────────────────────────────────┘  │  │
          │          → 循环或退出                          │  │
          └───────────────────────────────────────────────┘  │
        → 返回结果 (String / Mono / Flux<SSE>)              │
```

---

## 三、Stage 管线

### 3.1 5 个 Stage

```
order=0  ContextBuild    → MemorySystem.retrieve() → AgentContext.memoryEntries
order=1  SecurityCheck   → SecurityManager.approve() + ContentFilter.filter()
                            → AgentContext.sandboxLevel
                            可终止管线（拒绝不安全请求）
order=2  PlanExecution   → TaskPlanner.plan() + PlanValidator
                            → AgentContext.taskNodes
order=3  Respond         → ReActEngine + Hook链 + ToolExecutionPipeline
                            → AgentContext.finalResponse + toolResults
order=4  Metrics         → MemorySystem.ingestPerception() + MetricsCollector
                            → SSE done 事件
```

ReflectionStage 已移除。计划校验由 PlanExecution 内部的 PlanValidator 负责。

### 3.2 Stage 接口

```java
public interface ReactivePipelineStage {
    Flux<ServerSentEvent<String>> execute(AgentContext ctx);
    int getOrder();
    String getName();
    default Class<? extends ReactivePipelineStage>[] after() { return new Class[0]; }
}
```

用户自定义 Stage 只需实现此接口 + `@PipelineStage`，框架自动排序插入管线。

---

## 四、Hook 体系：步骤级拦截

### 4.1 定位

Hook 只做 AOP 做不到的事——ReAct 循环内部每一轮 LLM 调用和工具调用的拦截。方法级横切用 AOP。

```
AOP 边界: ReActEngine.executeStream()  ← AOP 只能拦截这一次方法调用
  ├── 第 1 轮: beforeModel → LLM → afterModel → 工具执行管线 → 循环
  ├── 第 2 轮: beforeModel → LLM → afterModel → 工具执行管线 → 循环
  └── ...（最多 30 轮）
       ↑ Hook 在循环内部拦截，AOP 看不到这里
```

### 4.2 AgentHook 接口

```java
public interface AgentHook {
    default void beforeRequest(AgentContext ctx) {}
    default void beforeModel(ChatRequest request, int roundNum, AgentContext ctx) {}
    default void afterModel(ModelResponse response, int roundNum, AgentContext ctx) {}
    default ToolCall wrapToolCall(ToolCall toolCall, AgentContext ctx) { return toolCall; }
    default String afterResult(String result, AgentContext ctx) { return result; }
    default int getOrder() { return 100; }
}
```

### 4.3 五个内置 Hook

| Hook | Order | 拦截点 | 职责 |
|------|-------|--------|------|
| **SecurityCheckHook** | 10 | `beforeRequest` | 内容过滤 + 权限校验 |
| **PlanningHook** | 20 | `beforeModel` | 注入 TaskNode DAG 到 system prompt；跟踪进度 |
| **SandboxHook** | 30 | `wrapToolCall` | 沙箱隔离（DIRECT / RESTRICTED / ISOLATED） |
| **ApprovalHook** | 40 | `wrapToolCall` | 非只读工具 → SSR tool_approval → 阻塞等确认 |
| **OutputGuardHook** | 50 | `afterModel` | 输出护栏：检测敏感信息、注入、有害内容 |

---

## 五、AgentContext：统一上下文

合并 PipelineContext + AgentContext，参考 LangGraph State 和 LangChain4j AgenticScope。

```
AgentContext
├── 基础信息
│   ├── sessionId, traceId, method, args, chatRequest
│
├── Stage 产出（下游自动继承上游）
│   ├── memoryEntries         ← Stage 0: ContextBuild
│   ├── sandboxLevel          ← Stage 1: SecurityCheck
│   ├── taskNodes             ← Stage 2: PlanExecution
│   ├── toolResults           ← Stage 3: Respond
│   ├── finalResponse         ← Stage 3: Respond
│   └── successCount / failCount
│
├── 管线控制
│   ├── terminated, pipelineOk, currentStage
│
├── 生命周期
│   ├── TRANSIENT   — 一次调用
│   ├── SESSION     — 绑定会话，跨调用缓存
│   └── PERSISTENT  — 持久化，崩溃恢复（参考 LangGraph checkpoint）
│
└── 扩展属性
    └── attributes: ConcurrentHashMap（参考 AgenticScope.state）
```

---

## 六、工具执行管线

### 6.1 完整链路

当前工具执行散落在两个地方（AgentInvocationHandler 和 RespondStage），代码不一致。统一为一条可拦截、可替换的管线：

```
LLM 返回 ToolCall
  │
  ▼
┌──────────────────────────────────────────────────────────┐
│              ToolExecutionPipeline                        │
│                                                           │
│  ① ToolResolver.resolve(name, ctx)                        │
│     找到工具实例                                            │
│                                                           │
│  ② ToolCallPolicy.check(name, ctx)                        │
│     策略检查（次数限制 / 白名单 / 黑名单）                  │
│                                                           │
│  ③ ToolHook.beforeExecution(toolCall, ctx)                │
│     SandboxHook 分级 + ApprovalHook 审批                   │
│     + 用户自定义 ToolHook（日志、审计、限流）              │
│                                                           │
│  ④ ParameterBinder.bind(tool, argsJson)                   │
│     统一参数绑定（不区分 @Param 注解 / Tool 接口）          │
│     注入参数（sandboxLevel、sessionId）对 LLM 不可见       │
│                                                           │
│  ⑤ tool.invoke(boundArgs)                                 │
│     实际执行                                                │
│                                                           │
│  ⑥ ToolHook.afterExecution(result, ctx)                   │
│     结果校验 + 审计日志                                    │
│                                                           │
│  ⑦ ResultFormatter.format(rawResult)                      │
│     格式化为 ToolExecutionResult                            │
│                                                           │
└──────────────────────────────────────────────────────────┘
  │
  ▼
ToolExecutionResult → 追加到 messages → 继续 ReAct
```

### 6.2 AgentHook 与 ToolHook 的分工

```
ReAct 循环内部:

  AgentHook.beforeModel    → LLM 调用前（规划进度注入）
  LLM 推理
  AgentHook.afterModel     → LLM 调用后（异常检测、输出护栏）

  若有 tool_calls:
    ToolHook.beforeExecution  → 工具执行前（沙箱、审批、动态筛选）
    工具执行
    ToolHook.afterExecution   → 工具执行后（结果校验、审计）
    ToolHook.onError          → 工具异常（重试/跳过/终止）
```

分开的理由：AgentHook 管 LLM 推理，ToolHook 管工具执行。用户可以只替换工具拦截逻辑，不影响 LLM 拦截逻辑。

### 6.3 注入参数（参考 LangChain InjectedToolArg）

```java
// 对 LLM 可见的参数
@Param(name = "command", description = "要执行的 Shell 命令", required = true)
String command,

// 对 LLM 不可见，框架运行时注入
@ParamInjected SandboxLevel sandboxLevel,   // from AgentContext
@ParamInjected String sessionId,            // from AgentContext
@ParamInjected ChatContext ctx              // from AgentContext
```

`@ParamInjected` 参数不出现在 ToolDefinition 的 JSON Schema 中，LLM 不知道它们的存在。框架在 `ParameterBinder.bind()` 阶段自动注入。

---

## 七、13 个框架 SPI

### 7.1 SPI 全景

```
┌──────────────────────────────────────────────────────────────────┐
│                     LyClaw Framework SPI (13 个)                   │
│                                                                    │
│  Agent 级 SPI (8 个):                                              │
│  SPI-1 : AgentFactory            Agent 代理创建方式                │
│  SPI-2 : ReactivePipelineStage   Stage 注册和排序                  │
│  SPI-3 : AgentHook               ReAct 循环步骤级拦截              │
│  SPI-4 : ReActEngine             推理-行动循环引擎                 │
│  SPI-5 : TaskPlanner             任务规划策略                      │
│  SPI-6 : ChatModelProvider       LLM 模型接入                     │
│  SPI-7 : AgentCommProtocol       Agent 间通信（含 A2A）           │
│  SPI-8 : AgentConfigSource       配置源优先级叠加                  │
│                                                                    │
│  Tool 级 SPI (5 个):                                               │
│  SPI-9 : ToolResolver            工具发现 + 动态筛选                │
│  SPI-10: ToolExecutionPipeline   工具执行管线编排                  │
│  SPI-11: ToolHook                工具执行拦截（before/after/onError）│
│  SPI-12: ParameterBinder         参数绑定 + 注入参数                │
│  SPI-13: MCPConnector            MCP 协议接入                      │
│                                                                    │
│  默认实现全部内置，用户可选替换。对默认行为满意则零配置。            │
└──────────────────────────────────────────────────────────────────┘
```

### 7.2 SPI-1: AgentFactory

```java
public interface AgentFactory {
    boolean supports(Class<?> agentInterface);
    Object create(Class<?> agentInterface, AgentConfig config);
}
```

默认：`JdkProxyAgentFactory`（`Proxy.newProxyInstance()` + `AgentInvocationHandler`）

可替换为：CGLIB 子类化、字节码生成、远程 RPC 代理

### 7.3 SPI-2: ReactivePipelineStage

见第三章 `Stage 接口`。用户实现接口 + `@PipelineStage` 即可插入自定义 Stage。

### 7.4 SPI-3: AgentHook

见第四章 `AgentHook 接口`。用户实现接口即可注册自定义 Hook。

### 7.5 SPI-4: ReActEngine

```java
public interface ReActEngine {
    String execute(ChatFacade chatFacade, ChatRequest request, ToolExecutor toolExecutor);
    Flux<ServerSentEvent<String>> executeStream(ChatFacade chatFacade, ChatRequest request,
                                                 ToolExecutor toolExecutor);
    void setApprovalRequired(Set<String> toolNames);
}
```

默认：`DefaultReActEngine`（30 轮，流式探测，SSE 审批流）

可替换为：Tree-of-Thought、基于状态图引擎、预算感知轮次控制

### 7.6 SPI-5: TaskPlanner

```java
public interface TaskPlanner {
    TaskPlan plan(ChatContext context, String userIntent);
    /** 可选：执行期动态决策 */
    default TaskNode nextAction(AgentContext ctx) { return null; }
}
```

默认：`DAGTaskPlanner`（静态 DAG 生成）。可选：`SupervisorPlanner`（LLM 动态决策）。

### 7.7 SPI-6: ChatModelProvider

```java
public interface ChatModelProvider {
    ChatModel resolve(ChatRequest request, AgentContext ctx);
    List<String> supportedModels();
}
```

默认：`RoutingChatModelProvider`（配置驱动路由）

### 7.8 SPI-7: AgentCommProtocol（含 A2A）

```java
public interface AgentCommProtocol {
    Flux<AgentMessage> send(String targetAgentName, AgentMessage message);
    void registerReceiver(String agentName, Consumer<AgentMessage> receiver);
    /** 是否支持 A2A 协议发现 */
    default boolean supportsA2A() { return false; }
    /** 发现远程 Agent（A2A） */
    default List<AgentDefinition> discoverAgents() { return List.of(); }
}
```

默认：`InJvmProtocol`（同 JVM 直接调用）

可替换为：RabbitMQ、Kafka、gRPC、Redis pub/sub。A2A 协议通过 `discoverAgents()` 扩展。

### 7.9 SPI-8: AgentConfigSource

```java
public interface AgentConfigSource {
    Map<String, String> loadConfig(String agentName);
    default int getPriority() { return 0; }
}
```

优先级叠加：`application.yml (10) → @Agent 注解 (50) → AgentBuilder (100) → DB (60) → 配置中心 (70)`

### 7.10 SPI-9: ToolResolver

```java
public interface ToolResolver {
    /** 获取可用工具定义（支持动态筛选） */
    List<ToolDefinition> resolveTools(AgentContext ctx);
    /** 按名称查找工具实例 */
    Tool resolve(String toolName, AgentContext ctx);
}
```

默认：`AnnotationToolResolver`（扫描 `@Tool` 注解 + ToolProvider 动态工具）

动态筛选：`resolveTools()` 可根据 `ctx` 中的上下文自动过滤无关工具，避免 prompt 爆炸。

### 7.11 SPI-10: ToolExecutionPipeline

```java
public interface ToolExecutionPipeline {
    ToolExecutionResult execute(ToolCall toolCall, AgentContext ctx);
}
```

默认实现按第七章的顺序编排：resolve → policy → beforeHook → bind → invoke → afterHook → format。

### 7.12 SPI-11: ToolHook

```java
public interface ToolHook {
    /** 工具执行前（可修改参数、拒绝执行） */
    default ToolCall beforeExecution(ToolCall toolCall, AgentContext ctx) { return toolCall; }
    /** 工具执行后（可修改结果） */
    default ToolExecutionResult afterExecution(ToolExecutionResult result, AgentContext ctx) { return result; }
    /** 工具异常时（决定重试/跳过/终止） */
    default ToolErrorAction onError(Exception e, ToolCall toolCall, AgentContext ctx) { return ToolErrorAction.ABORT; }
    default int getOrder() { return 100; }
}
```

内置 SandboxHook + ApprovalHook 同时实现 AgentHook 和 ToolHook（兼容过渡），未来逐步迁移到纯 ToolHook。

### 7.13 SPI-12: ParameterBinder

```java
public interface ParameterBinder {
    /** 将 JSON args 绑定到工具参数，注入框架级参数 */
    Map<String, Object> bind(Tool tool, String argsJson, AgentContext ctx);
    /** 类型转换 */
    Object coerce(Object value, Class<?> targetType);
}
```

默认：`ReflectionParameterBinder`（`@Param` 注解反射 + `@ParamInjected` 注入参数）

### 7.14 SPI-13: MCPConnector

```java
public interface MCPConnector {
    /** 连接 MCP Server */
    void connect(String serverUrl);
    /** 从 MCP Server 获取工具列表 */
    List<ToolDefinition> listTools();
    /** 通过 MCP 协议执行工具 */
    ToolExecutionResult callTool(String toolName, Map<String, Object> args);
    /** 关闭连接 */
    void disconnect();
}
```

MCP（Model Context Protocol）是 2025-2026 行业标准。实现后 LyClaw 可直接接入 200+ 现有的 MCP Server（文件系统、数据库、API 网关等），同时 MCP Server 可复用 LyClaw 的工具。

---

## 八、@Agent 配置可扩展机制

### 8.1 注解定义

```java
@Retention(RUNTIME)
@Target(TYPE)
public @interface Agent {

    // ── 核心属性（框架级，类型安全）──
    String name();
    String description() default "";
    String model() default "";
    String provider() default "";
    SandboxLevel sandbox() default SandboxLevel.RESTRICTED;
    int maxToolRounds() default 30;
    int approvalTimeout() default 60;

    // ── 扩展属性（可无限扩展，不改注解定义）──
    Extension[] extensions() default {};
}

@Retention(RUNTIME)
public @interface Extension {
    String key();    // 配置键，如 "planning.strategy"
    String value();  // 配置值，如 "sequential"
}
```

### 8.2 使用示例

```java
@Agent(
    name = "data-analysis-agent",
    sandbox = SandboxLevel.RESTRICTED,
    maxToolRounds = 20,

    extensions = {
        @Extension(key = "planning.enabled", value = "true"),
        @Extension(key = "planning.strategy", value = "sequential"),
        @Extension(key = "memory.topK", value = "10"),
        @Extension(key = "tool.dynamicFiltering", value = "true"),
        @Extension(key = "mcp.servers", value = "http://localhost:9000,http://localhost:9001"),
        @Extension(key = "outputGuard.enabled", value = "true"),
        @Extension(key = "communication.protocol", value = "in-jvm"),
    }
)
public interface DataAnalysisAgent {
    Flux<ServerSentEvent<String>> analyze(String userRequest);
}
```

### 8.3 未来扩展

新功能只需：1. 组件从 AgentConfig 读新 key → 2. 用户在 @Extension 加一行。注解定义不动。

---

## 九、Web 层精简

### 9.1 ChatController

```java
@RestController
@RequestMapping("/api")
public class ChatController {
    private final ChatAgent chatAgent;  // @Agent 代理 Bean，直接注入

    @PostMapping(value = "/chat/stream", produces = TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request, ...) {
        return chatAgent.chatStream(request.getMessages(), request.getSessionId());
    }
}
```

### 9.2 可删除的组件

| 删除项 | 原因 |
|--------|------|
| Orchestrator / OrchestratorImpl | Stage 管线嵌入 AgentInvocationHandler |
| OrchestrationService | Controller 直接调 Agent 代理 |
| PipelineContext / ChatContext | 被 AgentContext 取代 |
| ReflectionStage | 已移除。OutputGuardHook 负责输出护栏 |
| InteractionModeProcessor | @InteractionMode 废弃，ReAct 为唯一模式 |
| lyclaw-orchestration 模块 | 降为空壳或删除 |

---

## 十、差异化创新总结

### 10.1 LyClaw vs 全行业

| 能力维度 | LangChain4j | OpenAI SDK | Google ADK | CrewAI | LyClaw v2.0 |
|---------|-----------|-----------|-----------|--------|------------|
| 沙箱隔离 | ❌ | ✅ | ❌ | ❌ | ✅ 3级（**行业领先**） |
| 工具审批（SSE） | ❌ | ❌ | ❌ | human_input | ✅ SSE双向（**独有**） |
| 双层拦截 | ❌ | ❌ | ❌ | ❌ | ✅ Stage+步骤级（**独有**） |
| MCP 协议 | ✅ | ✅ | ✅ | ❌ | ✅ SPI-13 |
| A2A 协议 | ❌ | ❌ | ✅ | ❌ | ✅ SPI-7 预留 |
| 输出护栏 | ❌ | ✅ | ❌ | ❌ | ✅ OutputGuardHook |
| 动态工具筛选 | ✅ | ❌ | ❌ | ❌ | ✅ SPI-9 |
| 配置可扩展 | ❌ | ❌ | ❌ | ❌ | ✅ @Extension key-value（**独有**） |
| 上下文逐步增强 | ❌ | ❌ | ❌ | ❌ | ✅ Stage 链（**独有**） |
| 多 Agent | SupervisorAgent | Handoff | Hierarchical | Role-based | P3 预留 |
| 检查点回放 | ❌ | ❌ | ✅ | ❌ | P2 AgentContext PERSISTENT |
| 13 个 SPI | 0 | 0 | 0 | 0 | ✅ **全可替换** |

### 10.2 优先级

```
P0 — 安全底线（已有，巩固）:
    沙箱隔离（3 级）+ 工具审批（SSE 双向）+ 输入护栏
    → 这是 LyClaw 最硬核的差异化，行业没有第二家同时做这三件事

P1 — 行业对齐 + 架构创新:
    MCP 协议（行业标配，必须对齐）
    工具执行管线（统一两条路径的工具执行）
    双层拦截（Stage 级 + 步骤级，架构独创）
    上下文逐步增强（Stage 链，下游自动继承上游）
    输出护栏（OutputGuardHook，补安全漏洞）

P2 — 优化增强:
    动态工具筛选（工具多了不爆炸）
    语义记忆（向量检索）
    检查点回放（长任务崩溃恢复）
    混合 Planner（简单任务零 LLM 成本）
    @Extension 配置机制（不改注解加配置）

P3 — 多 Agent（预留）:
    真正多 Agent 协作（Handoff / Hierarchical）
    A2A 协议（跨框架互通）
    Reflexion（失败后自我修正）
```

---

## 十一、实施阶段与优先级

### 11.1 总体路线

```
阶段 1 (P0): 安全底线          阶段 2 (P1): 行业对齐+架构核心
══════════════════════        ═══════════════════════════════
AgentContext 统一              Stage 管线嵌入 Agent
Hook 接口扩展（步骤级）        ToolExecutionPipeline 实现
SandboxHook 完善               5 个 Stage 迁移到 framework
ApprovalHook 完善              MCP 协议（SPI-13）
SecurityCheckHook 完善         OutputGuardHook 新建
                               PlanningHook 新建
                               Web 精简 + 旧代码删除

阶段 3 (P2): 优化增强          阶段 4 (P3): 多 Agent
══════════════════════        ═══════════════════════
13 个 SPI 全部实现             @SupervisorAgent
动态工具筛选                   Handoff / Hierarchical
语义记忆（向量检索）           A2A 协议发现
检查点回放                     Reflexion
混合 Planner                   工作流拓扑
@Extension 配置机制
```

### 11.2 阶段 1：安全底线（P0，2-3 天）

| 任务 | 说明 |
|------|------|
| 合并 PipelineContext → AgentContext | 保留全部字段，新增 Lifecycle 枚举 |
| Hook 接口扩展 | 新增 beforeModel / afterModel / wrapToolCall |
| SandboxHook 完善 | 适配新 AgentContext，从 ctx 读取 sandboxLevel |
| ApprovalHook 完善 | 适配 wrapToolCall 签名，SSE 审批流可用 |
| SecurityCheckHook 完善 | beforeRequest 注入 ContentFilter + SecurityManager |
| 编译验证 | mvn compile 通过 |

### 11.3 阶段 2：行业对齐 + 架构核心（P1，4-5 天）

| 任务 | 说明 |
|------|------|
| AgentInvocationHandler 嵌入 Stage 管线 | invoke() 中注入 PipelineStageProcessor，Stage 按序执行 |
| 5 个 Stage 迁移到 lyclaw-framework | 修改签名为 execute(AgentContext) |
| ToolExecutionPipeline 实现 | 默认 7 步管线：resolve→policy→beforeHook→bind→invoke→afterHook→format |
| SPI-13 MCPConnector | 默认实现：JSON-RPC over HTTP，连接 MCP Server 获取工具 |
| OutputGuardHook 新建 | afterModel 检测敏感信息、注入、有害内容 |
| PlanningHook 新建 | beforeModel 注入 TaskNode DAG + 进度追踪 |
| ChatController 精简 | 注入 ChatAgent 代理 Bean |
| 删除旧代码 | Orchestrator / OrchestratorImpl / OrchestrationService / PipelineContext / ChatContext / ReflectionStage / InteractionModeProcessor |
| lyclaw-orchestration 降级 | 或直接删除模块 |
| 全量编译 + curl SSE 测试 | 确保前端零改动 |

### 11.4 阶段 3：优化增强（P2，3-4 天）

| 任务 | 说明 |
|------|------|
| SPI-1 ~ SPI-8 全部实现 | AgentFactory、Stage、Hook、ReActEngine、TaskPlanner、ChatModelProvider、AgentCommProtocol、AgentConfigSource |
| SPI-9 ~ SPI-13 完善 | ToolResolver 动态筛选、ToolHook 用户扩展、ParameterBinder 注入参数 |
| @Extension 配置机制 | AgentConfig 类 + AgentConfigResolver + 多来源优先级叠加 |
| 混合 Planner | PlanExecutionStage 中规则优先，LLM 可选 |
| 语义记忆 | MemorySystem 接入向量检索 |
| AgentContext SESSION / PERSISTENT | SESSION 跨调用缓存，PERSISTENT checkpoint 持久化 |

### 11.5 阶段 4：多 Agent 协作（P3，预留）

| 任务 | 说明 |
|------|------|
| @SupervisorAgent 注解 | 多 Agent 编排入口 |
| Handoff / Hierarchical | 手递手 + 层级树两种模式 |
| A2A 协议发现 | AgentCommProtocol.discoverAgents() |
| Reflexion | 失败后自我批评→修正→重试 |
| AgentContext PERSISTENT 崩溃恢复 | checkpoint / restore |

### 11.6 模块变更汇总

```
v1.0 模块结构:                        v2.0 模块结构:
═══════════════════                  ═══════════════════
lyclaw-framework                     lyclaw-framework (+5 Stage, +AgentContext, +13 SPI接口)
lyclaw-autoconfigure                 lyclaw-autoconfigure (+SPI默认实现自动配置)
lyclaw-infra                         lyclaw-infra
lyclaw-protocol                      lyclaw-protocol (+MCPConnector)
lyclaw-plan                          lyclaw-plan
lyclaw-memory                        lyclaw-memory (+语义记忆检索)
lyclaw-action                        lyclaw-action (+ToolExecutionPipeline)
lyclaw-reflect                       lyclaw-reflect
lyclaw-orchestration  ← 删除        lyclaw-web (唯一部署单元)
lyclaw-web

                                    13 个 SPI 接口分布在 framework/action/protocol 中
                                    删除:
                                      Orchestrator / OrchestratorImpl
                                      OrchestrationService
                                      PipelineContext / ChatContext
                                      ReflectionStage / InteractionModeProcessor
                                      lyclaw-orchestration 模块
```
