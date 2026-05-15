# LyClaw Agent 功能实现状况审计报告 & TODO

---

## 一、Agent 主循环（Think → Act → Observe → Think）

### ✅ 已实现

**ReAct 循环** — `RespondStage.java:205-257`
真实可用的 ReAct 循环：调 LLM → 检测 tool_calls → 执行工具 → 结果注入消息列表 → 再次调 LLM，最多 10 轮。通过 ActionFeignClient 远程调用工具，端到端完整。

**流式优先策略** — `RespondStage.java:125-202`
3 状态 FSM：缓冲思考(0) → 流式转发(1) → 检测到工具(2)。先尝试 SSE 流式输出，检测到 tool_calls 后切到非流式 ReAct。JSON 感知的 tool_calls 分片合并。

**独立 ToolCallLoop** — `ToolCallLoop.java:80-176`
另一个完整的 ReAct 循环，含 ToolCallPolicy（重试/跳过/中止策略）、beforeLoop/afterLoop 钩子。真实可用。

### 🔴 断裂

| 问题 | 证据 |
|------|------|
| 两套 ReAct 循环零共享 | RespondStage 通过 Feign 调工具，ToolCallLoop 直接用 ToolRegistry。ToolCallLoop 从未被管线调用（全项目仅自身文件引用） |
| Plan/Reflection 产出未接入 ReAct | ReAct 循环自己做决策，不参考 Plan 的 TaskNode DAG，也不参考 Reflection 评分 |

---

## 二、规划（Planning）

### ✅ 已实现

- **规划器接口** `TaskPlanner.java` — 定义 `plan()` / `revise()` / `decompose()` / `optimize()`
- **DAGTaskPlanner** `DAGTaskPlanner.java:92-132` — 关键词匹配规则引擎，按复杂度(0-4)生成不同粒度的 DAG 计划节点
- **ReActPlanner** `ReActPlanner.java:81-132` — 规则引擎，按 `intent.length()/50` 决定循环次数，构建 Thought→Action→Observation 链
- **TaskGraphImpl** `TaskGraphImpl.java` — DAG 数据结构：加权边、关键路径(`getCriticalPath`)、并行度分析(`getMaxParallelism`)
- **PlanExecutionStage** `PlanExecutionStage.java:132` — 通过 `planFeignClient.plan()` 远程调用规划服务。真实 Feign 调用

### 🔴 断裂

| 问题 | 证据 |
|------|------|
| TaskNode DAG 无人消费 | `PlanExecutionStage:152` 写入 `pipelineCtx.addNode()`，但 `RespondStage` 走 `streamWithToolDetection`，完全不读 `getNodes()`。仅用于前端 SSE 展示 |
| 无 DAG 并行工具调度 | `RespondStage.runReActLoop()` 和 `ToolCallLoop.execute()` 全部是 `for` 循环串行执行。代码中不存在拓扑排序驱动并行执行 |
| `depends_on` 字段未被使用 | `TaskNode` 有 `dependencies` 字段，但无任何执行器代码读取它来决定执行顺序 |
| 规划器均为规则引擎 | `DAGTaskPlanner`（关键词匹配）和 `ReActPlanner`（`intent.length()/50`）都**不是 LLM 调用**，是纯硬编码规则 |

---

## 三、工具调用（Tool Calling）

### ✅ 已实现

- **5 个内置工具** — `calculator`（递归下降解析器）、`command`（`sh -c` 子进程）、`current_time`（`ZonedDateTime`）、`execute_script`（临时文件+解释器）、`web_search`（Tavily API）。全部真实可执行
- **工具注册表** `DefaultToolRegistry.java:35-41` — `ConcurrentHashMap`，自动收集所有 `Tool` bean
- **注解驱动注册** `ToolAnnotationProcessor.java:99-141` — `@Tool` 注解 → `ToolDefinition`（含 JSON Schema）→ `AnnotatedToolAdapter` 反射调用
- **端到端工具执行链路** — `RespondStage → ActionFeignClient → ActionController → ActionExecutorImpl → ToolSandboxImpl → Tool.execute()`。6 层调用链完整

### 🔴 缺失/断裂

| 问题 | 证据 |
|------|------|
| 工具始终串行执行 | `RespondStage.java:259-309`、`ToolCallLoop.java:118-152`、`ActionExecutorImpl.java:276-358` 全部是 `for` 循环逐个执行 |
| CONTAINER/ISOLATED 沙箱未真正实现 | `ToolSandboxImpl.java:247-293`：CONTAINER 和 ISOLATED 代码几乎完全相同——对 `command` 工具用 `ProcessBuilder` 隔离，其他工具回退到 RESTRICTED（仅改 `user.dir`）。无 Docker/cgroup/namespace 隔离 |
| `ToolCallLoop` 未被管线使用 | 完整的独立 ReAct 循环带 `ToolCallPolicy`，但 `OrchestratorImpl` 和所有 `PipelineStage` 均不引用 |

---

## 四、反思（Reflection）

### ✅ 已实现

- **ReflectionStage** `ReflectionStage.java:87` — 调用 `reflectFeignClient.reflect()`，生成 `ReflectionReport` 和评分，写入 `pipelineCtx.reportRef` 和 `reflectScoreRef`。真实 Feign 调用
- **ReflectController** `ReflectController.java` — 三个端点：完整反思、独立评估、独立错误检测

### 🔴 断裂

| 问题 | 证据 |
|------|------|
| Reflection 在 Respond 之前执行 | `ReflectionStage.getOrder()=3` vs `RespondStage.getOrder()=4`。ReflectionStage 运行时 `toolResults` 永远为空——反思的对象是 Plan 文本本身，不是工具执行结果 |
| 反思结果不影响 LLM 决策 | `RespondStage` 读取 `report` 仅用于 `buildFinalResponse()`（降级/错误兜底）。正常路径 `streamWithToolDetection` 完全不读反思报告/评分 |
| pipelineOk 无条件 true | `ReflectionStage.java:113` 正常路径和 `:123` 异常路径均无条件设 `pipelineOk=true`。反思永远不阻止 RespondStage |
| 无 Plan→Execute→Reflect→Replan 闭环 | `OrchestratorImpl.java:108-112` 管线是严格线性串联（`concatWith`），无反馈回路。Reflection 评分不触发重新规划 |

---

## 五、会话持久化

### ✅ 已实现

- **会话创建/加载** `OrchestrationController.java:163-177` — `resolveSession()` 从 `StorageFacade` 按 sessionId 加载，不存在则新建并保存
- **ChatContext 深拷贝** `ChatContext.java:105` — 构造时 `new ArrayList<>(session.getMessages())` 拷贝历史消息

### 🔴 缺失

| 问题 | 证据 |
|------|------|
| 管道结束后消息不保存 | `OrchestratorImpl.execute()` 全流程无 `storageFacade.save("sessions", ...)`。ReAct 循环中新增的 assistant/tool 消息只存在于 `context.getRequest().getMessages()`——请求结束即丢失 |
| MetricsStage 只保存摘要 | `MetricsStage.java:107-126` 调 `memoryFeignClient.ingest()` 持久化的是 `PerceptionData` 摘要（任务计数、成功/失败），不是消息内容 |
| 无上下文窗口管理 | 消息无限追加，无 token 预算检查、无修剪、无摘要压缩。`validateRequest()` 只检查消息列表非空 |

---

## 六、记忆注入

### ✅ 已实现

- **记忆检索** `ContextBuildStage.java:85` — 调 `memoryFeignClient.retrieve()` 获取 `MemoryQueryResult`。真实 Feign 调用
- **记忆合并** `DefaultMemoryConsolidator.java` — 短期→长期记忆合并逻辑

### 🔴 断裂

| 问题 | 证据 |
|------|------|
| 检索结果丢弃 | `ContextBuildStage.java:85-95`：`MemoryQueryResult` 只打日志、发 SSE 事件。`ChatContext.memory` 是 `final` 字段（`ChatContext.java:72`），无 setter，构造时初始化为空的 `MemoryContent`，永远无法被填充 |
| 记忆 XML 构建器未被调用 | `FullWindowContextBuilder.java` 有 `buildContext()` 能把记忆包装成 `<memory>` XML，但无任何代码调用 |
| 无语义向量检索 | 当前"记忆"本质是全量加载同一 sessionId 的历史消息，换会话=完全失忆 |

---

## 七、Agent 生命周期与多 Agent

### ✅ 已实现（框架层，真实状态机/算法）

- **AgentState** `AgentState.java` — 6 状态：IDLE/RUNNING/WAITING/COMPLETED/FAILED/CANCELLED
- **AgentLifecycleManager** `AgentLifecycleManager.java:87-257` — CAS 原子状态转换、CachedThreadPool 异步执行、前置条件检查
- **DefaultAgentRegistry** `DefaultAgentRegistry.java:64-225` — `ConcurrentHashMap` 存储，按 capability/state/accuracy 查询
- **StarAgentChannel** `StarAgentChannel.java:50-236` — `BlockingQueue` 消息传递、星型拓扑
- **ConsensusEngineImpl** `ConsensusEngineImpl.java:43-262` — Jaccard 相似度、加权投票（能力40%+准确率35%+置信度25%）

### 🔴 全部与管线隔离 / 模拟

| 问题 | 证据 |
|------|------|
| Agent 生命周期与管线完全隔离 | `OrchestratorImpl` 不注入 `AgentLifecycleManager`、`AgentRegistry`、`AgentCoordinator`、`CollaborationHub` |
| `@Agent` 注解无处理器 | 元注解 `@Component`，但无 `BeanPostProcessor` 消费。`@Agent` 全项目零使用 |
| `executeAgentTask()` 是模拟 | `OrchestratorImpl.java:141-219`：生成硬编码的 `agent-0`、`agent-1` 事件流，不调用任何真实 Agent 组件 |
| 4 种协作模式内容生成为模拟 | `collab/` 目录：Market/Network/Pipeline/SupervisorWorker 的交互逻辑（拍卖、共识）真实，但 LLM 调用输出是随机置信度+硬编码字符串 |
| A2A 协议完全模拟 | `A2aGatewayImpl.java:22` 注释"当前为模拟实现"；`sendTask()` 用 `Thread.sleep()` |
| A2A 服务发现模拟 | `A2aDiscovery.java:22` 注释"当前为模拟实现" |
| ExternalAgentAdapter 模拟 | `ExternalAgentAdapterImpl.java:21` 注释"当前实现为模拟版本" |

---

## 八、MCP 协议

| 组件 | 状态 | 详情 |
|------|------|------|
| MCP Client STDIO | ✅ 真实 | `McpClientImpl.java:72-91`：`ProcessBuilder` 子进程，JSON-RPC 2.0 |
| MCP Client SSE | ❌ 模拟 | `McpClientImpl.java:107-114`：不建立 HTTP 连接，返回硬编码响应 |
| MCP Server STDIO 协议分发 | ✅ 真实 | `McpServerImpl.java:169-186`：System.in 读 JSON-RPC，分发到 handler |
| MCP Server 工具执行 | ❌ 模拟 | `McpServerImpl.java:137-161`：`executeTool()` 只返回字符串 "Tool [name] executed" |
| MCP Server SSE | ❌ 占位 | 只打日志 "SSE transport initialized" |
| MCP Server WebSocket | ❌ 未实现 | 注释 "reserved for future use" |
| McpToolAdapter | ⚠️ 脆弱 | HTTP POST 到远程 MCP 端点，但用 `indexOf("\"content\"")` 手动解析 JSON |

---

## 九、流式输出

| 功能 | 状态 | 位置 |
|------|------|------|
| LLM 流式调用 | ✅ | `OpenAiProtocolChatModel.java`：WebClient + SSE 逐行解析 |
| 3 状态 FSM 流式检测 | ✅ | `RespondStage.java:125-202`：状态 0 缓冲→状态 1 转发→状态 2 工具检测 |
| 非流式兜底 | ✅ | `RespondStage.java:97`：流式异常回退到 `chat()` |
| Plan/Reflection 进度推送 | ⚠️ 仅 SSE | `PlanExecutionStage.java:167-174` 推 `plan_node` 事件，但中间状态推送不完整 |
| 工具执行进度推送 | ❌ | 不存在 |

---

## 总表

```
模块                  │ 实现状况      │ 关键断裂
──────────────────────┼──────────────┼──────────────────────────────────────────
Agent 主循环 (ReAct)  │ ✅ 真实可用   │ 两套并行实现；Plan/Reflection 产出未接入
规划 (Planning)       │ ⚠️ 规则引擎   │ TaskNode DAG 无人消费；规划器无 LLM 调用
工具调用              │ ✅ 链路完整   │ 始终串行；高级沙箱未实现
反思 (Reflection)     │ ⚠️ 顺序错误   │ 在 Respond 前执行；评分不影响决策
会话持久化            │ 🔴 不保存     │ 消息请求结束即丢失
记忆注入              │ 🔴 检索后丢弃 │ ChatContext.memory 永远为空
上下文窗口管理        │ 🔴 不存在     │ 消息无限增长，无 token 预算
Agent 生命周期        │ ⚠️ 状态机真实 │ 与管线完全隔离；@Agent 注解无处理器
多 Agent 协作         │ ❌ 全部模拟   │ 交互逻辑真实，内容生成为随机假数据
A2A 协议              │ ❌ 全部模拟   │ Thread.sleep() + 硬编码
MCP Client STDIO      │ ✅ 真实       │ 需外部 MCP 服务端进程
MCP Client SSE        │ ❌ 模拟       │ 硬编码响应
MCP Server 协议分发   │ ✅ 真实       │ executeTool() 是假的
MCP Server 工具执行   │ ❌ 模拟       │ 只返回描述字符串
流式输出              │ ✅ 真实       │ 工具执行进度不推送
```

**核心结论**：Agent 的 ReAct 循环（`RespondStage.runReActLoop`）是真实能跑的，LLM 适配层完整，工具调用链路完整。但规划做了没人看，反思评了没人用，会话消息不保存，记忆检索了不注入。三段关键产出（Plan→DAG、Reflect→评分、Memory→检索结果）全部断裂——Agent 实际等效于一个没有规划引导、没有反思纠错、没有跨会话记忆的裸 ReAct 循环。多 Agent 和 A2A 层是完整但不工作的骨架。

---

# TODO：成熟 Agent 待实现清单

> 以下排除记忆检索与会话持久化（独立设计），聚焦 Agent 核心能力。

---

## 已经有的（不需重做）

- **ReAct 循环**：`RespondStage.runReActLoop` — 真实 LLM 调用 + 工具执行，端到端通
- **工具调用链路**：`RespondStage → ActionFeignClient → ActionController → ActionExecutorImpl → ToolSandboxImpl → Tool.execute()` — 6 层完整
- **流式输出**：3 状态 FSM，SSE 逐 token 推送，工具检测自动切换
- **Agent 状态机/注册表/协调器/通道/共识引擎**：代码真实，算法真实，只是没接入管线

---

## P0：让 Plan 驱动执行（打通规划→执行断裂）

**当前状态**：`PlanExecutionStage` 生成 TaskNode DAG（含依赖关系），但 `RespondStage` 完全无视它，LLM 自己决定调什么工具。

**需要做**：
- [ ] `RespondStage` 的 ReAct 循环读取 `pipelineCtx.getNodes()`，按 TaskNode 的结构引导 LLM
- [ ] 把 TaskNode 的 `type` + `description` + `requiredTools` 注入 LLM prompt，让 LLM 在规划好的框架内执行
- [ ] `depends_on` 字段被读取，决定节点执行顺序

---

## P0：DAG 拓扑排序 + 并行工具执行

**当前状态**：3 处工具执行全是 `for` 循环串行。

**需要做**：
- [ ] 读取 TaskNode 的 `dependencies` 字段，做拓扑排序（Kahn 算法），识别无依赖的节点组
- [ ] 同组内节点并行执行（`CompletableFuture.allOf` 或 `Flux.merge`）
- [ ] 有依赖的节点等待上游完成后再执行
- [ ] 复用 `TaskGraphImpl` 已有的 `getCriticalPath()` 和 `getMaxParallelism()`

---

## P1：Reflection 移到 Respond 之后 + 评分接入 ReAct

**当前状态**：Reflection(order=3) 在 Respond(order=4) 之前执行，toolResults 为空，评分只在降级兜底用。

**需要做**：
- [ ] 调整阶段顺序：Respond 先执行（产出 toolResults + 最终回复），Reflection 后执行（评估工具执行结果）
- [ ] Reflection 评分低（< 0.6）时触发 ReAct 重新执行（增加一轮或换策略）
- [ ] 评分写入 prompt 上下文，让 LLM 知道上一轮质量如何

---

## P1：Plan→Execute→Reflect→Replan 闭环

**当前状态**：管线是严格线性 `concatWith`，无反馈回路。

**需要做**：
- [ ] 在 `OrchestratorImpl` 中实现条件循环：Reflection 评分低于阈值 → 重新 Plan → 重新 Execute
- [ ] 或更轻量：Reflection 检测到具体错误（幻觉/逻辑矛盾）→ 把错误描述注入 prompt → 再跑一轮 ReAct
- [ ] 最大重试次数限制（如 3 次）

---

## P2：封装 Agent 类 + @Agent 注解生效

**当前状态**：Agent 相关组件（LifecycleManager、Registry、Coordinator）真实但游离；`@Agent` 注解零使用。

**需要做**：
- [ ] 定义一个 `Agent` 接口/抽象类，封装：`plan()` + `execute()`（含 ReAct 循环）+ `reflect()` + `revise()`
- [ ] 写一个 `AgentAnnotationProcessor`（`BeanPostProcessor`），扫描 `@Agent` 注解的类，自动注册到 `AgentRegistry`
- [ ] `@Agent` 注解加属性：`name`、`capabilities`、`model`、`maxRetries` 等
- [ ] Agent 实例化后即可通过 `AgentRegistry` 查询、通过 `AgentLifecycleManager` 调度

---

## P2：把 Agent 生命周期接入管线

**当前状态**：`OrchestratorImpl` 不注入任何 Agent 组件，`executeAgentTask()` 是硬编码假数据。

**需要做**：
- [ ] `OrchestratorImpl` 注入 `AgentRegistry` + `AgentLifecycleManager` + `AgentCoordinator`
- [ ] 管线执行时：从 Registry 按 capability 匹配 Agent → LifecycleManager 管理状态转换（IDLE→RUNNING→COMPLETED）→ 执行完成后更新状态
- [ ] 替换 `executeAgentTask()` 的硬编码为真实 Agent 调度

---

## P3：ToolCallPolicy 接入主流程

**当前状态**：`ToolCallLoop` 有完整的重试/跳过/中止策略，但主流程 `RespondStage` 没用。

**需要做**：
- [ ] `RespondStage.runReActLoop()` 集成 `ToolCallPolicy`
- [ ] 工具调用失败时按策略决定：重试（同参数）、跳过（继续下一个）、中止（终止循环）
- [ ] 或直接复用 `ToolCallLoop`，让 `RespondStage` 调用它而不是自己再写一套

---

## 优先级汇总

```
P0（阻塞 — Agent 没有规划引导就是瞎跑）
├── Plan→Execution 打通：TaskNode DAG 驱动工具执行
└── DAG 拓扑排序 + 并行执行

P1（核心能力缺失）
├── Reflection 移到 Respond 之后 + 评分驱动重试
└── Plan→Execute→Reflect→Replan 闭环

P2（Agent 封装与注册）
├── Agent 接口/抽象类封装
├── @Agent 注解处理器 + 自动注册
└── Agent 生命周期接入管线

P3（锦上添花）
└── ToolCallPolicy 接入主 ReAct 流程
```

P0 做完，Agent 就是一个有规划引导、能并行执行的多步推理体。
P1 做完，Agent 就有了自我纠错能力。
P2 做完，`@Agent` 注解一行注册，多 Agent 协作就有了基础。
