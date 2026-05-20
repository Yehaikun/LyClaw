# 02 -- 差距分析：钩子、流水线、压缩、上下文管理

## 概述

本文档对 LyClaw 当前的 agent 钩子系统、流水线架构、压缩、上下文修剪、上下文限制、agent 终审门控以及重试策略，与 OpenClaw 的实现进行详细的逐项对比。每一行标识 LyClaw 的当前状态、对应的 OpenClaw 能力、差距严重性（P0=阻塞/必须修复，P1=关键/高优先级，P2=重要/中优先级，P3=增强/锦上添花），以及预估的实现复杂度。

---

## 1. 钩子系统

| 序号 | 类别 | LyClaw现状 | OpenClaw实现 | 差距严重性 | 复杂度 |
|---|----------|---------------|------------------------|-------------|------------|
| 1.1 | **钩子点总数** | 单个 `AgentHook` SPI 接口上的 5 个方法：`beforeRequest`、`beforeModel`、`afterModel`、`wrapToolCall`、`wrapToolExecutor`、`afterResult`。外加返回 int 类型的 `getOrder()`（默认 100）。 | 整个插件系统中有 36 个命名钩子点。每个钩子通过字符串名称标识（例如 `"before_model_resolve"`、`"agent_turn_prepare"`、`"before_compaction"`、`"subagent_spawning"`）。插件通过 `PluginHookRegistration` 为每个钩子名称注册处理器。 | **P1** -- LyClaw 的 5 个粗粒度生命周期事件仅覆盖了 OpenClaw 钩子覆盖面的约 14%。虽然并非全部 36 个都需要立即实现，但缺少独立的钩子名称使得插件无法选择性地订阅细粒度的生命周期事件。 | 中 |
| 1.2 | **钩子注册模型** | 钩子作为实现 `AgentHook` 的 Spring bean 被注入。`AgentInvocationHandler` 通过构造函数注入接收 `List<AgentHook>`。所有钩子在每次调用时都按顺序执行——没有按钩子名称过滤，没有条件注册。 | `PluginHookRegistration { pluginId, hookName, handler, priority, timeoutMs, source }`。插件为特定钩子名称注册独立的处理器。插件宿主解析哪些处理器对哪个钩子触发。支持基于超时的处理器中止（timeoutMs）和用于调试的来源归属。 | **P1** -- LyClaw 的"所有钩子始终触发"模型迫使每个钩子实现在每个方法的顶部自行执行空操作检查。这浪费 CPU、使钩子代码复杂化，并使第三方插件无法选择性地仅钩入相关的生命周期时刻。缺少 `timeoutMs` 意味着行为异常的钩子可能无限期地阻塞整个 agent 流水线。 | 中 |
| 1.3 | **钩子优先级系统** | 单个整数 `getOrder()`（最低优先）。所有钩子共享同一排序维度。没有"生命周期阶段内的优先级"与"跨阶段排序"的区分。 | 每次注册的 `priority`（数字）。由于钩子是按钩子名称注册的，插件可以为不同的钩子名称设置不同的优先级，从而实现细粒度控制（例如，安全插件可以在 `before_model_resolve` 中为高优先级，而在 `after_tool_call` 中为低优先级）。 | **P2** -- LyClaw 的扁平排序对当前 5 个内置钩子有效，但当 20+ 个来自多个插件的钩子时就会出问题。在多插件场景下需要按钩子名称的优先级（或至少是阶段+排序模型）。 | 低 |
| 1.4 | **钩子上下文数据丰富度** | `AgentContext` 携带：`sessionId`、`userMessage`、`systemPrompt`、`ChatRequest`、`ToolRegistry`、`Method`（反射）、`Object[]` args（反射）、`SandboxLevel`、`Lifecycle` 枚举、`TraceContext`、流水线状态计数器（`successCount`、`failCount`）、`TaskNode` 列表、`reflectScoreRef`、`pipelineOk`、`terminated`、`currentStage`，以及一个通用的 `Map<String,Object> attributes`。**缺失**：runId、jobId、modelProviderId、modelId、messageProvider、触发类型、channelId、contextTokenBudget、contextWindowSource、contextWindowReferenceTokens。 | `PluginHookAgentContext` 携带：`runId`、`jobId`、`trace`、`agentId`、`sessionKey`、`sessionId`、`workspaceDir`、`modelProviderId`、`modelId`、`messageProvider`、`trigger`、`channelId`、`contextTokenBudget`、`contextWindowSource`、`contextWindowReferenceTokens`。所有字段都是一级类型化字段，而非通用属性包。 | **P1** -- 缺少 `contextTokenBudget`、`contextWindowSource` 和 `contextWindowReferenceTokens` 使得钩子无法做出压缩感知的决策。缺少 `trigger` 和 `channelId` 阻止钩子区分用户发起、cron 触发或子 agent 衍生的调用。缺少 `modelProviderId`/`modelId` 阻止钩子按模型调整行为。 | 低-中 |
| 1.5 | **钩子决策/门控能力** | 钩子只能通过抛出异常来阻止（例如 `SecurityCheckHook.beforeRequest` 中的 `SecurityException`）。没有结构化的决策返回类型——阻止是全有或全无的命题。唯一的结构化结果是 `ctx.setTerminated(true)`，这是临时的、基于约定的，不受钩子契约的强制约束。 | `InputGateDecision = pass | block(带 reason, message, category, metadata)`。`GateHookResult` 携带 `decision` + `pluginId`。钩子返回结构化决策，使框架能够：(a) 聚合多个门控决策，(b) 记录为何阻止并附带元数据，(c) 向用户展示阻止类别，(d) 实现"警告但允许"（降级通过）语义。 | **P1** -- LyClaw 基于异常的阻止是脆弱的。异常代价高昂，丢失结构化元数据，且无法表达微妙的决策，如"带警告通过"或"阻止并给出建议的补救方案"。流水线中的 `SecurityCheckStage` 重复了 `SecurityCheckHook` 中相同的阻止逻辑，表明钩子层和流水线层在冗余实现相同的关注点。 | 中 |
| 1.6 | **钩子超时/保护** | 没有超时机制。阻塞或无限循环的钩子会冻结整个 agent 调用。唯一的超时在工具审批级别（`ApprovalHook` 中的 `approvalTimeoutSeconds`，默认 30 秒）。 | 每个 `PluginHookRegistration` 上的 `timeoutMs`。插件宿主强制执行每个处理器的超时。如果处理器超时，它会被取消，框架根据配置要么跳过它，要么使调用失败。 | **P2** -- 目前由于所有 5 个内置钩子都是简单微小的（无网络调用、无 LLM 调用）而得以缓解。一旦添加第三方或依赖网络的钩子，这将变得关键。 | 中 |
| 1.7 | **生命周期覆盖：请求前** | `beforeRequest(AgentContext)` -- 在调用开始时触发一次。涵盖：内容过滤、安全审批、沙箱级别分配。没有对应会话级初始化、模型解析或提示准备作为独立阶段。 | `before_model_resolve`（选择使用哪个模型）、`agent_turn_prepare`（准备一轮回合）、`before_prompt_build`（即将构建系统提示）、`before_agent_start`（已弃用）、`before_agent_reply`（即将生成回复）、`before_agent_run`（agent 即将执行）。每个阶段都是一个独立的钩子，允许插件在正确的粒度上进行干预。 | **P1** -- LyClaw 的单个 `beforeRequest` 将模型选择、提示构建、安全和会话设置混为一个模糊的阶段。这使得不可能在安全检查之后但在提示构建之前更改模型，或在模型解析之后注入会话级数据。 | 中 |
| 1.8 | **生命周期覆盖：模型交互** | `beforeModel(List<Message>, AgentContext)` -- 在每次 LLM 调用前触发，可以修改消息。`afterModel(String, AgentContext)` -- 在每次 LLM 响应后触发，可以修改响应文本。 | `model_call_started`（LLM API 调用已发起）、`model_call_ended`（LLM API 调用已完成）、`llm_input`（发送给 LLM 的确切提示/消息）、`llm_output`（来自 LLM 的确切原始响应）。这些是观察性钩子（不能修改，只能观察/记录），与修改性钩子分开。 | **P2** -- LyClaw 的 `beforeModel`/`afterModel` 很好地覆盖了修改用例。缺少的是保证不修改数据的观察性钩子（`llm_input`/`llm_output`），这些对审计日志、成本跟踪和调试至关重要。还缺少 `model_call_started`/`model_call_ended`，这些用于在 LLM API 边界进行延迟跟踪。 | 低 |
| 1.9 | **生命周期覆盖：工具执行** | `wrapToolCall(ToolCall, AgentContext)` -- 每次工具调用包装（步骤级别）。`wrapToolExecutor(ToolExecutor, AgentContext)` -- 以装饰器链方式包装执行器（请求级别）。两者都是修改性钩子。没有观察性工具钩子。 | `before_tool_call`（即将执行）、`after_tool_call`（已执行，附带结果）、`tool_result_persist`（结果即将持久化到会话）。还有 `before_message_write`（在将工具结果消息写入对话记录之前）。 | **P2** -- LyClaw 的装饰器模式（`wrapToolExecutor`）对沙箱/审批用例很优雅，但混淆了"修改执行行为"和"观察执行"。没有干净的方法添加一个在不干扰装饰器链的情况下观察工具调用的指标收集器。将 `before_tool_call`/`after_tool_call` 添加为独立的钩子名称可以解决这个问题。 | 低 |
| 1.10 | **生命周期覆盖：agent 结束/终审** | `afterResult(String, AgentContext)` -- 在流水线完成后触发。钩子按逆序执行（降序）。可以修改最终结果字符串。无法触发修订、重试或用结构化反馈拒绝最终结果。 | `before_agent_finalize` -- 钩子可以返回 `{action:"continue"}`（继续处理结果）、`{action:"revise", reason}`（发回修订并附带指令），或 `{action:"finalize", reason}`（无论质量如何强制结束）。`agent_end` -- 在所有终审完成后触发。`before_agent_reply` -- 与终审不同，专门针对发送给用户的回复。 | **P1** -- LyClaw 的 `afterResult` 是简单的文本转换传递。它不能触发修订（将结果送回 ReAct 并附带新指令），不能强制提前结束，也不能提供结构化重试指令。当前的重试逻辑硬编码在 `AgentInvocationHandler` 中，使用魔数（0.6 阈值，最多 2 次重试），而非由钩子驱动。 | 中 |
| 1.11 | **生命周期覆盖：会话** | 没有会话级钩子。`AgentContext.Lifecycle` 枚举存在（`TRANSIENT`、`SESSION`、`PERSISTENT`），但仅用于信息目的——在会话边界上没有钩子触发。 | `session_start`、`session_end`、`before_reset`（会话重置）。这些允许插件初始化每个会话的状态，在结束时持久化会话摘要，以及拦截/阻止会话重置。 | **P2** -- 当 LyClaw 支持带压缩和记忆的长时间运行会话时，会话生命周期钩子变得重要。没有它们，插件无法在会话结束时清理资源或在会话开始时预热缓存。 | 低-中 |
| 1.12 | **生命周期覆盖：消息路由** | 不适用——LyClaw 当前面向单一渠道/终端。所有调用经过相同的流水线。 | `inbound_claim`（声明对入站消息的责任）、`message_received`（消息到达）、`message_sending`（即将发送）、`message_sent`（已发送）、`before_dispatch`（路由决策）、`reply_dispatch`。还有 `gateway_start`/`gateway_stop` 用于网关生命周期。 | **P3** -- 仅当 LyClaw 支持多渠道（Webchat、API、Slack 等）分发时才相关。钩子架构应设计为将来能容纳这些。 | 高 |
| 1.13 | **生命周期覆盖：子 agent** | 没有子 agent 概念。`TaskNode` DAG 在单个 agent 调用内执行。 | `subagent_spawning`（即将衍生）、`subagent_delivery_target`（将子 agent 结果发送到哪里）、`subagent_spawned`（衍生完成）、`subagent_ended`（子 agent 完成）。这些构成了用于分层 agent 架构的完整子 agent 生命周期。 | **P3** -- 基于 DAG 的任务分解是 LyClaw 当前的模型，不需要子 agent 衍生钩子。这仅在 LyClaw 采用分层多 agent 架构时才会变得相关。 | 高 |
| 1.14 | **生命周期覆盖：cron/调度** | 没有 cron/调度系统。 | `cron_changed`（cron 调度已修改）、`heartbeat_prompt_contribution`（为定期心跳提示做贡献）。 | **P3** -- 仅当 LyClaw 添加自主调度 agent 执行时才相关。 | 中 |
| 1.15 | **生命周期覆盖：压缩** | 压缩系统不存在。 | `before_compaction`（即将压缩）、`after_compaction`（压缩完成）。这些允许插件影响压缩参数（为插件特定上下文预留 token、保留特定消息）并对压缩后状态做出反应。 | **P1** -- 依赖压缩本身的实现。一旦压缩存在，这些钩子对于将上下文注入对话记录的插件（例如记忆检索、RAG）来说至关重要，以确保它们注入的上下文在压缩后得以保留。 | 中 |
| 1.16 | **钩子链执行模型** | 在调用线程内顺序、同步执行。`beforeRequest` 钩子在 for-each 循环中按 `getOrder()` 升序执行。`afterResult` 钩子按降序执行。`wrapToolExecutor` 形成嵌套装饰器链（每个钩子包装前一个）。 | 插件宿主在可能的情况下并发执行处理器（同一钩子的独立处理器可以并行运行）。`InputGateDecision` 模型支持短路求值（第一个阻止获胜）。超时执行已内置到执行基础设施中。 | **P2** -- 顺序执行对当前 5 个钩子是合适的，但无法扩展到来自第三方插件的 20+ 个钩子。需要带短路门控的并行执行来保证性能。 | 中 |
| 1.17 | **内置钩子实现** | 5 个钩子：`SecurityCheckHook`（order=10，内容过滤 + 安全审批）、`SandboxHook`（order=20，用沙箱包装工具执行器）、`ApprovalHook`（order=30，对写入工具的用户审批）、`PlanningHook`（order=40，将计划 DAG 注入消息）、`OutputGuardHook`（order=90，基于正则的输出内容过滤）。 | 本身没有内置钩子——插件系统就是扩展机制。OpenClaw 等效的内置行为是通过相同的 PluginHookRegistration 机制注册的插件实现的，而非作为特殊的框架接口。 | **P0** -- LyClaw 的 SecurityCheckHook 和 SecurityCheckStage 相互重复了彼此的逻辑（两者都调用 securityManager.approve() 和 contentFilter.filter()）。这违反了 DRY 原则，并造成关于安全执行实际在哪一层的混淆。钩子和流水线阶段应该统一，或其中一个应委托给另一个。SandboxHook 的 wrapToolExecutor 与 RespondStage 的直接沙箱执行冲突，造成两条不同的沙箱代码路径。 | 中 |
| 1.18 | **可扩展性：第三方插件** | 第三方代码实现 `AgentHook`，将其声明为 Spring bean。钩子自动被 `AgentProxyFactory` / `AgentInvocationHandler` 拾取。没有隔离、没有版本控制、没有钩子之间的依赖解析。 | 带 `source` 归属的 `PluginHookRegistration`。插件宿主管理插件生命周期（安装、卸载、启用、禁用）。钩子限定在其所属插件范围内。插件依赖被解析。 | **P2** -- LyClaw 基于 Spring bean 的发现方式简单且功能正常，但不提供插件生命周期管理，没有热重载，也没有插件之间的隔离。这对插件市场来说很重要。 | 中-高 |

### 钩子系统总结

LyClaw 仅用 5 个庞大的钩子方法覆盖了 OpenClaw 钩子覆盖面的约 14%，仅涵盖了最基本的 agent 生命周期阶段（请求开始、LLM 调用、工具调用、响应后处理）。最关键的差距是：

1. **没有压缩生命周期钩子**（P1）——一旦实现压缩即需
2. **没有结构化决策/阻止语义**（P1）——基于异常的阻止是脆弱的
3. **没有基于钩子名称的选择性注册**（P1）——所有钩子始终触发
4. **没有终审/修订门控**（P1）——重试逻辑是硬编码的，非钩子驱动
5. **安全钩子/阶段重复**（P0）——同一关注点的两个冗余实现

### 详细钩子执行流程对比

**LyClaw 钩子执行（当前）**：

```
AgentInvocationHandler.invoke()
  |
  +-- hooks.sort(by order)                    // 按 getOrder() 排序所有钩子
  +-- for each hook: hook.beforeRequest(ctx)   // 所有钩子触发，无选择性
  +-- [流水线执行阶段 0..5]
  |     +-- ContextBuild.execute(ctx)
  |     +-- SecurityCheck.execute(ctx)          // 重复了 SecurityCheckHook 的逻辑！
  |     +-- PlanExecution.execute(ctx)
  |     +-- Respond.execute(ctx)
  |     |     +-- ReActEngine.executeStream()
  |     |           +-- for each LLM call:
  |     |           |     hook.beforeModel(msgs, ctx)   // 所有钩子触发
  |     |           |     [LLM API 调用]
  |     |           |     hook.afterModel(resp, ctx)    // 所有钩子触发
  |     |           +-- for each tool call:
  |     |                 hook.wrapToolCall(call, ctx)  // 所有钩子触发
  |     +-- Reflection.execute(ctx)
  |     +-- [重试块：如果 score<0.6 则重复 PlanExecution→Respond→Reflection]
  |     +-- Metrics.execute(ctx)
  +-- for each hook (reverse): hook.afterResult(result, ctx)  // 所有钩子触发
```

**OpenClaw 钩子执行（参考）**：

```
HarnessContextEngine.runTurn()
  |
  +-- fireHooks("before_model_resolve")       // 仅注册的处理器触发
  +-- resolveModel()
  +-- fireHooks("agent_turn_prepare")         // 仅注册的处理器触发
  +-- fireHooks("before_prompt_build")        // 仅注册的处理器触发
  +-- fireHooks("before_agent_reply")         // 仅注册的处理器触发
  +-- fireHooks("llm_input")                  // 观察性：记录输入
  +-- [LLM API 调用]
  +-- fireHooks("llm_output")                 // 观察性：记录输出
  +-- [for each tool call:]
  |     fireHooks("before_tool_call")          // 门控：可以阻止
  |     [执行工具]
  |     fireHooks("after_tool_call")           // 观察性：记录结果
  +-- fireHooks("before_agent_finalize")       // 门控：继续/修订/结束
  +-- [如果修订：注入指令，重试]
  +-- fireHooks("agent_end")                  // 清理
  +-- [轮次之间:]
  |     fireHooks("before_compaction")         // 仅在需要压缩时
  |     [压缩]
  |     fireHooks("after_compaction")          // 验证压缩后的上下文
```

这些流程中可见的关键差异：
- LyClaw 在每个点触发所有钩子；OpenClaw 仅触发为每个命名钩子注册的处理器
- LyClaw 缺少终审门控（`before_agent_finalize`），这是重试的关键决策点
- LyClaw 的 `afterResult` 是简单的文本传递；OpenClaw 的 `before_agent_finalize` 可以触发修订
- LyClaw 在钩子和阶段中重复安全执行；OpenClaw 仅在钩子层执行一次
- LyClaw 没有轮次间维护钩子；OpenClaw 在轮次之间有压缩钩子

---

## 2. 流水线架构

| 序号 | 类别 | LyClaw现状 | OpenClaw实现 | 差距严重性 | 复杂度 |
|---|----------|---------------|------------------------|-------------|------------|
| 2.1 | **架构模型** | 线性阶段流水线：6 个阶段，通过 `@PipelineStage` 注解 + `ReactivePipelineStage` 接口进行整数排序。阶段通过 `Flux.concat()` 执行，产生 `Flux<ServerSentEvent<String>>`。拓扑排序解析注解中的 `after`/`before` 约束。 | 上下文引擎生命周期模型：5 个阶段 -- `bootstrapHarnessContextEngine`、`assembleHarnessContextEngine`、`finalizeHarnessContextEngineTurn`、`runHarnessContextEngineMaintenance`、`isActiveHarnessContextEngine`。这不是线性流水线，而是有状态的、在轮次之间交错维护阶段的生命周期。 | **P0** -- 这些是根本不同的模型。LyClaw 的线性流水线对单轮请求-响应工作良好，但无法建模逐轮的跨轮次状态维护、上下文引擎预热或轮次间垃圾回收。"上下文引擎"模型是跨轮次存在的持久状态机，而 LyClaw 的流水线是每次调用实例化的。 | 高 |
| 2.2 | **阶段定义** | `ReactivePipelineStage` 接口，包含 `execute(AgentContext) -> Flux<SSE>`、`getOrder()`、`getStageName()`。阶段是带有 `@PipelineStage(name, after, before, group)` 注解的 Spring bean。`PipelineStageProcessor` 在启动时执行拓扑排序。 | 非基于阶段。上下文引擎的阶段是硬编码到引擎生命周期中的。自定义通过钩子（在特定生命周期点的插件钩子）和配置（压缩设置、上下文窗口设置）实现，而非通过可插拔的阶段。 | **P0** -- 这是根本性的架构分歧。LyClaw 基于阶段的方法提供了更大的可扩展性（添加/移除/重排序阶段），但对上下文管理核心职责的凝聚力较低。OpenClaw 整体但可钩入的上下文引擎提供了更大的凝聚力，但结构可扩展性较低。 | 高 |
| 2.3 | **流水线流程** | 固定顺序：`ContextBuild(0)` -> `SecurityCheck(1)` -> `PlanExecution(2)` -> `Respond(3)` -> `Reflection(4)` -> `Metrics(5)`。顺序由整数值和 `after` 约束硬编码。唯一的动态行为是围绕 `PlanExecution+Respond+Reflection` 的重试循环。 | 引导 -> 装配（从来源构建上下文：系统提示、记忆、工具、红线）-> 运行回合（模型调用 + 工具调用）-> 终审（持久化、压缩、维护）->（重复下一轮次）。维护运行可因以下原因触发：`"bootstrap"`、`"compaction"`、`"turn"`。 | **P1** -- LyClaw 的顺序将"每轮数据准备"（ContextBuild）与"每轮执行"（PlanExecution、Respond）与"每轮后处理"（Reflection、Metrics）混在一起。没有轮次间维护的概念。ContextBuild 阶段执行记忆检索但不处理 OpenClaw 所做的"装配最终上下文"步骤（将系统提示、记忆、工具模式、红线、用户消息组合成感知 token 预算的上下文）。 | 中-高 |
| 2.4 | **上下文窗口管理** | 没有上下文窗口管理。`ChatRequest.messages` 列表是无界的。没有 token 计数、没有 token 预算、没有截断、没有中间压缩。消息在会话中无限累积。 | `contextTokenBudget`（按终端/渠道管理）、`contextWindowSource`（哪个组件定义了窗口）、`contextWindowReferenceTokens`（参考 token 数）。上下文引擎主动管理适合上下文窗口的内容，在预算超出时使用压缩。 | **P0** -- 这是最大的单一架构差距。没有上下文窗口管理，LyClaw 将在长对话中默默超出模型上下文限制，导致 API 错误或默默截断上下文。每个生产级 agent 系统必须管理其上下文窗口。 | 高 |
| 2.5 | **阶段间数据传递** | 通过 `AgentContext`，它作为一个可变共享数据总线。各阶段读写上下文字段：`setUserMessage()`、`setSandboxLevel()`、`addNode()`、`addToolResult()`、`setAttribute()` 等。这是黑板模式。 | 通过 Harness Context Engine 的内部状态，这些状态不直接暴露给任意修改。插件通过钩子返回值和配置影响上下文引擎，而非通过直接修改共享状态包。 | **P2** -- 黑板模式灵活但会在阶段之间创建隐式耦合（例如，SecurityCheckStage 设置 sandboxLevel，RespondStage 读取它，但这个契约不受类型系统强制）。对于少量阶段来说可管理，但对于由插件注入的阶段来说变得脆弱。 | 中 |
| 2.6 | **流水线可观察性** | 每个阶段发出带有阶段名称标签的 SSE 事件。`LyClawPipelineEndpoint` actuator 暴露流水线拓扑和阶段状态。Trace span（`TraceContext.beginStage/endStage`）提供按阶段的持续时间跟踪。`MetricsCollector` 记录按阶段的持续时间。 | 上下文引擎阶段通过钩子调用（例如用于计时的 `model_call_started`/`ended`）和跟踪系统进行观察。没有显式的逐阶段 SSE 发射——引擎不会将其内部阶段边界暴露给前端。 | **P2** -- LyClaw 的逐阶段 SSE 事件有助于调试，但会给 SSE 流增加噪音。一个独立于用户端 SSE 流的专用可观察性通道（日志 + 指标 + 跟踪）会更干净。 | 低 |
| 2.7 | **流水线错误处理** | 每个阶段将其主体包装在 try-catch 中。出错时，阶段记录警告并：要么发出降级事件并继续（ContextBuild、SecurityCheck、PlanExecution、Reflection），要么通过 onErrorResume 提供回退响应（Respond）。阶段永远不会使流水线崩溃。 | 上下文引擎错误通过主题回复机制浮现。如果上下文装配失败，引擎可以优雅地使回合失败。压缩错误具有重试逻辑（带 maxRetries 的质量守卫）。 | **P1** -- LyClaw 的"永不崩溃"策略过于宽松。如果 ContextBuild 失败（记忆系统不可用），流水线默默地降级并在空的记忆上下文中继续。用户得到一个降级的响应，但没有迹象表明记忆不可用。对于某些故障模式，OpenClaw 优雅地使回合失败（对用户有清晰的错误提示）的方法更可取。 | 低 |
| 2.8 | **流水线与钩子的职责** | 显著重叠：SecurityCheckStage（流水线阶段）和 SecurityCheckHook（钩子）都执行内容过滤和安全审批。两者都访问 `securityManager.approve()` 和 `contentFilter.filter()`。钩子在 `AgentInvocationHandler.invoke()` 中甚至在流水线开始之前触发，然后流水线阶段在阶段执行期间再次触发。 | 钩子和上下文引擎有清晰的分离。钩子观察和门控；上下文引擎装配和执行。钩子层和引擎层之间没有重复的逻辑，因为它们是架构上不同的层，具有不同的职责。 | **P0** -- 这种重复是一个 bug。SecurityCheckHook 的 `beforeRequest` 已经过滤和审批，然后 SecurityCheckStage 再次执行。如果钩子允许但阶段阻止，用户会得到不一致的行为。修复方法是：(a) 移除 SecurityCheckStage 让钩子层处理安全，或 (b) 移除 SecurityCheckHook 让流水线阶段处理，或 (c) 让钩子委托给阶段的结果（从 AgentContext 读取）。 | 低 |
| 2.9 | **流水线动态重配置** | 不支持。阶段列表在处理器构造时计算，在处理器生命周期内不可变。无法按请求或会话添加/移除/重排序阶段。 | 也不直接支持，但基于钩子的自定义可以有效地更改上下文引擎在每次调用中的行为（例如 `before_compaction` 中的钩子可以更改压缩参数）。 | **P2** -- 按请求的阶段自定义将支持诸如"简单查询跳过规划"或"仅为复杂任务启用深度反思"等用例。当前固定流水线将每个请求视为完全相同。 | 中 |

### 流水线架构总结

最关键的差距是**缺少上下文引擎**（P0）。LyClaw 的线性阶段流水线处理单次轮转，但没有上下文窗口管理、轮次间维护或 token 预算执行的概念。这意味着：

- LyClaw 无法安全地处理超过模型上下文窗口的长对话
- 没有机制来压缩或截断不断增长的消息历史
- 流水线将每次调用视为孤立事件，即使对于 SESSION/PERSISTENT 生命周期也是如此

次要的关键差距是钩子与流水线阶段之间的**安全执行重复**（P0）。

### 阶段级职责分析

以下是每个 LyClaw 阶段当前所做的与在上下文引擎感知架构中应该做的详细分解：

| 阶段 | 当前职责 | 缺失的上下文引擎职责 |
|-------|----------------------|--------------------------------------|
| **ContextBuild** (order=0) | 加载会话，通过 `memorySystem.retrieve()` 检索记忆，发出 `context_build_start`/`context_build_complete` SSE 事件 | 不进行 token 预算检查。不装配最终上下文（系统提示 + 记忆 + 工具 + 红线 + 用户消息）。不为模型响应预留空间。不以 token 感知的方式注入检索到的记忆（可能使上下文过载）。 |
| **SecurityCheck** (order=1) | 内容过滤 + 安全审批，设置沙箱级别，发出 `intercept_start`/`intercept_complete` SSE 事件 | 应该是一个钩子，而不是阶段。安全执行应该在上下文装配之前发生，而不是作为单独的流水线阶段。将其作为阶段意味着它在 ContextBuild 已经花费时间检索记忆之后运行，如果安全阻止，这些记忆将被丢弃。 |
| **PlanExecution** (order=2) | 通过 `taskPlanner.plan()` 将用户意图分解为 `TaskNode` DAG，通过 `planValidator.validate()` 验证，发出 `plan_start`/`plan_node`/`plan_complete` SSE 事件 | 计划本身消耗上下文 token（由 PlanningHook 注入）。没有机制检查计划上下文是否适合剩余的 token 预算。没有机制在上下文窗口几乎满时中止规划。 |
| **Respond** (order=3) | 执行带工具调用的 ReAct 循环，流式 LLM 输出，工具审批流程，发出 `respond_start`/`message`/`tool_call`/`tool_approval` SSE 事件 | 没有轮次中的上下文窗口限制预检查。工具结果无界存储。没有截断大型工具输出的机制。没有机制在轮次中超出上下文窗口时触发压缩。 |
| **Reflection** (order=4) | 通过 `reflectionEngine.reflect()` 评估响应质量，计算分数，确定 `needsRetry`，发出 `reflection_start`/`reflection_complete` SSE 事件 | 反思分数存储在 `AgentContext` 中，但重试决策硬编码在 `AgentInvocationHandler` 中。没有钩子可以影响重试阈值或提供修订指令。 |
| **Metrics** (order=5) | 通过 `memorySystem.ingestPerception()` 持久化到记忆，记录指标，发出 `respond_complete`/`metrics`/`done` SSE 事件 | 没有轮次后维护（压缩、修剪、记忆刷新）。没有轮次间垃圾回收。 |

### 上下文装配差距（详细）

OpenClaw 的 `assembleHarnessContextEngine` 阶段将上下文装配作为一个独立的、感知 token 预算的步骤执行：

```
assembleHarnessContextEngine():
  1. 从系统提示开始（强制，始终包含）
  2. 添加红线 / 安全指令（强制，始终包含）
  3. 计算可用 token：contextWindow - reserveTokens - systemPromptTokens - redLinesTokens
  4. 添加工具定义（如果空间允许，否则截断工具描述）
  5. 添加记忆检索结果（截断至 memoryGetMaxChars）
  6. 添加对话历史：
     a. 旧轮次的压缩摘要（来自先前的压缩）
     b. 最近轮次逐字保留（keepRecentTokens）
  7. 添加当前用户消息
  8. 为模型响应预留剩余 token（reserveTokens）
  9. 如果总计超出预算，在进行之前触发压缩
```

LyClaw 没有等效于这个流程的东西。ContextBuild 检索记忆并将其添加到 `AgentContext` 属性包中。PlanningHook 将计划作为系统消息注入。RespondStage 构建带有工具定义的 ChatRequest。但这三个操作是不协调的——没有一个单点知道总 token 消耗并能做出感知预算的决策。

---

## 3. 压缩

| 序号 | 类别 | LyClaw现状 | OpenClaw实现 | 差距严重性 | 复杂度 |
|---|----------|---------------|------------------------|-------------|------------|
| 3.1 | **压缩是否存在** | **无。** 完全没有压缩机制。消息历史（`ChatRequest.messages`）无界增长。没有 token 计数基础设施。 | 完全实现的压缩系统，有两种模式（`"default"` 和 `"safeguard"`）、广泛的配置、token 预算管理和质量守卫重试逻辑。 | **P0** -- 压缩是任何处理多轮对话的生产级 agent 的硬性要求。没有它，超出模型上下文窗口（例如 128K token）的对话将以 API 错误失败或从默默截断的上下文中产生降质结果。 | 极高 |
| 3.2 | **压缩模式** | 不适用 | `"default"` -- 标准压缩，总结对话历史，保留最近轮次同时将较旧的轮次压缩为摘要。`"safeguard"` -- 一个额外的安全导向压缩，确保关键上下文（红线、系统提示、身份）永远不会丢失。 | **P0** | 极高 |
| 3.3 | **Token 预算管理** | 代码库中任何地方都不存在 token 计数。没有分词器集成。 | `reserveTokens` -- 在上下文窗口末尾为模型响应预留的 token。`keepRecentTokens` -- 为最近对话轮次保留的 token（保持未压缩）。`reserveTokensFloor` -- 即使在压力下也保留的最小预留 token。`maxHistoryShare` -- 对话历史可以占用的上下文窗口的最大比例。 | **P0** -- Token 计数是压缩的前提条件。LyClaw 需要集成一个分词器（OpenAI 模型用 tiktoken / Java 用 JTokkit）并添加 token 跟踪到消息列表，然后才能考虑压缩。 | 高 |
| 3.4 | **压缩指令** | 不适用 | `customInstructions` -- 附加到压缩 LLM 调用的自定义提示指令，允许插件指定要保留什么、要强调什么以及如何结构化摘要。`recentTurnsPreserve` -- 逐字保留（不摘要化）的最近轮次数量。 | **P0** | 中 |
| 3.5 | **标识符策略** | 不适用 | `identifierPolicy`：`"strict"`（保留所有标识符，如姓名、ID、URL）、`"off"`（激进压缩可能丢失标识符）、`"custom"`（带有针对特定领域标识符规则的 `identifierInstructions`）。 | **P1** -- 对企业用例很重要，其中在压缩中丢失订单 ID、客户名称或参考编号将是灾难性的。 | 中 |
| 3.6 | **质量守卫** | 不适用 | `qualityGuard: { enabled: boolean, maxRetries: number }`。压缩后，系统评估压缩后的上下文是否连贯和完整。如果不，它使用调整后的参数重试压缩，最多 maxRetries 次。 | **P1** -- 压缩质量问题可能破坏整个对话状态。带重试的质量守卫对可靠性至关重要。 | 中-高 |
| 3.7 | **轮次中预检查** | 不适用 | `midTurnPrecheck: { enabled: boolean }`。在轮次中进行 LLM 调用之前，检查上下文窗口是否接近限制，如果需要则触发主动压缩。 | **P1** -- 防止尴尬的情况：在添加工具结果后因上下文超出而在对话中途模型调用失败。 | 中 |
| 3.8 | **后压缩索引同步** | 不适用 | `postIndexSync: "off" | "async" | "await"`。压缩后，可选地将新摘要同步到记忆/向量索引，以便未来的记忆检索包含压缩后的历史。 | **P2** -- 跨会话记忆连续性的锦上添花功能。 | 中 |
| 3.9 | **记忆刷新** | 不适用 | `memoryFlush: { enabled, model, softThresholdTokens, forceFlushTranscriptBytes, prompt, systemPrompt }`。当对话记录达到软阈值时，系统主动将对话内容刷新到长期记忆（摘要化 + 向量嵌入），减少对上下文内历史的需求。 | **P1** -- 这是压缩和记忆之间的桥梁。没有记忆刷新，压缩后的历史就丢失了。有记忆刷新，压缩后的历史保留在记忆系统中供未来检索。 | 高 |
| 3.10 | **压缩后段落** | 不适用 | `postCompactionSections` -- 压缩后始终注入的段落列表（默认：`["Session Startup", "Red Lines"]`）。这些确保关键系统级上下文在压缩后得以保留。 | **P1** | 低 |
| 3.11 | **压缩模型** | 不适用 | `model` -- 压缩 LLM 调用的可选模型覆盖。允许使用更便宜/更快的模型进行压缩（例如 GPT-4o-mini），同时使用更强大的模型进行主对话（例如 Claude Opus）。`timeoutSeconds`（默认 900）。 | **P1** -- 成本优化：压缩调用不应消耗昂贵的模型容量。 | 低 |
| 3.12 | **截断** | 不适用 | `truncateAfterCompaction` -- 如果压缩失败或不够充分，回退到简单截断（丢弃最旧的消息）。`maxActiveTranscriptBytes` -- 对话记录总大小的硬上限（以字节为单位）。 | **P1** -- 当压缩无法充分减少上下文时的安全网。 | 低 |
| 3.13 | **用户通知** | 不适用 | `notifyUser` -- 是否通知用户发生了压缩（例如 "我已总结了我们之前的对话以保持在上下文限制内"）。 | **P3** -- 良好的用户体验但不是关键。 | 低 |
| 3.14 | **压缩钩子** | 没有压缩钩子（压缩本身不存在）。 | `before_compaction` 和 `after_compaction` 钩子允许插件：(a) 在其被摘要化之前保存插件特定状态，(b) 修改压缩参数，(c) 压缩后恢复插件状态，(d) 验证关键上下文是否存活。 | **P1** -- 依赖压缩的实现。一旦压缩存在，这些钩子对记忆插件、RAG 插件以及任何向对话记录注入上下文的插件至关重要。 | 中 |

### 压缩总结

压缩是一个 **P0** 差距。它是 LyClaw agent 系统中最重要的缺失特性。没有压缩：

- 长对话将超出模型上下文限制
- 会话范围的 agent 将在约 100-200 条消息后默默降级或失败
- 记忆检索无法正常运行，因为不断增长的对话记录挤占了检索到的记忆
- 没有办法实现 OpenClaw 执行的感知 token 预算的上下文装配

实现复杂度是**极高**，因为压缩触及每一层：token 计数（需要分词器）、LLM 调用（需要压缩模型）、上下文装配（需要将上下文分成可压缩 vs 保留段落）、记忆集成（后压缩同步）和钩子系统（压缩前/后钩子）。

### 压缩决策流程（应该怎样运作）

作为参考，以下是 LyClaw 需要实现的压缩决策流程：

```
每次 LLM 调用之前（midTurnPrecheck）或轮次开始时：
  1. 计算消息列表中的总 token 数
  2. 计算：remainingBudget = contextWindow - totalTokens - reserveTokens
  3. 如果 remainingBudget < softThreshold：
     a. 确定要压缩什么：
        - 系统提示：绝不压缩
        - 红线 / 安全：绝不压缩（postCompactionSections）
        - 工具定义：压缩（摘要化描述）
        - 最近轮次（最后 N 个，keepRecentTokens）：逐字保留
        - 旧轮次：通过 LLM 摘要化压缩
        - 记忆注入：截断至 memoryGetMaxChars
        - 工具结果：按修剪策略修剪旧的/大的
     b. 调用压缩 LLM：
        - 模型：compactionModelOverride（更便宜的模型）或主模型
        - 提示：customInstructions + "请总结以下对话"
        - 输入：待压缩的旧轮次
        - 超时：timeoutSeconds（默认 900）
     c. 质量守卫：
        - 验证压缩输出的连贯性
        - 如果质量检查失败，用调整后的参数重试（maxRetries）
     d. 后压缩：
        - 在上下文顶部注入 postCompactionSections
        - 如果仍超出预算则截断（truncateAfterCompaction）
        - 同步到记忆索引（postIndexSync：async 或 await）
        - 如果 notifyUser=true 则通知用户
  4. 如果 remainingBudget >= softThreshold：
     不进行压缩，继续
```

### Token 计数前提条件

在实现压缩之前，LyClaw 需要：

1. **分词器集成**：集成一个 token 计数库。选项：
   - `tikoken`（Java 用 JTokkit）用于 OpenAI 模型
   - Anthropic 的 token 计数用于 Claude 模型
   - 一个通用 token 计数器（基于字符的近似值，以 4 字符/token 作为回退）
2. **带计数的消息包装器**：扩展 `Message` 以跟踪每条消息的 `tokenCount`。
3. **累积 token 跟踪**：向 `AgentContext` 或一个新的 `ContextBudget` 类添加 `AtomicLong totalTokens`。
4. **模型特定的上下文窗口配置**：modelId -> maxContextTokens 的映射（例如 `{"gpt-4o": 128000, "claude-sonnet-4-20250514": 200000, "deepseek-v3": 65536}`）。
5. **按终端的预算配置**：允许不同的终端（渠道）拥有不同的 token 预算（例如 Slack 机器人获得 32K，Web 应用获得 128K）。

---

## 4. 上下文修剪

| 序号 | 类别 | LyClaw现状 | OpenClaw实现 | 差距严重性 | 复杂度 |
|---|----------|---------------|------------------------|-------------|------------|
| 4.1 | **修剪是否存在** | **无。** 没有修剪机制。工具结果完整存储在 `toolResults` 列表和消息历史中。 | 可配置的修剪系统，模式为 `"off"` 或 `"cache-ttl"`。在 TTL 后从上下文中修剪工具结果以释放上下文窗口空间。 | **P1** -- 不如压缩关键，因为它解决的是一个更具体的问题（过期的工具结果消耗上下文），但对于每次轮次执行许多工具的 agent 来说很重要。 | 中 |
| 4.2 | **修剪模式** | 不适用 | `"off"`（禁用）或 `"cache-ttl"`（基于生存时间的修剪）。 | **P1** | 低 |
| 4.3 | **TTL 配置** | 不适用 | `ttl` -- 工具结果在此持续时间后有资格被修剪。`keepLastAssistants` -- 保持未修剪的最近助手消息数量。 | **P1** | 低 |
| 4.4 | **修剪阈值** | 不适用 | `softTrimRatio` -- 在软阈值下要修剪的工具结果字符比例。`hardClearRatio` -- 在此比例下完全用占位符替换结果。`minPrunableToolChars` -- 工具结果必须有资格被修剪的最小字符数。 | **P2** | 低 |
| 4.5 | **工具级控制** | 不适用 | 带有 `allow`/`deny` 列表的 `tools` -- 其结果可以或不能被修剪的特定工具。例如，`read_file` 结果可能可修剪（内容在文件中），但 `get_user_profile` 结果可能不可修剪（用户信息仅在工具结果中）。 | **P1** -- 对正确性很重要：某些工具结果是不可替代的，绝不能修剪。 | 低 |
| 4.6 | **软裁剪** | 不适用 | `softTrim: { maxChars, headChars, tailChars }`。裁剪时，保留结果的前 `headChars` 和后 `tailChars`，用省略号替换中间部分。裁剪后的总结果 <= `maxChars`。 | **P2** | 低 |
| 4.7 | **硬清除** | 不适用 | `hardClear: { enabled, placeholder }`。当工具结果超过硬清除阈值时，用占位符消息完全替换它，如"[先前的工具结果已被清除以节省上下文空间]"。 | **P2** | 低 |
| 4.8 | **每终端上下文限制** | 上下文限制不存在。 | `memoryGetMaxChars`（默认 12000）、`memoryGetDefaultLines`（默认 120）、`toolResultMaxChars`（默认 16000）、`postCompactionMaxChars`（默认 1800）。每种操作类型的不同限制。 | **P1** -- 没有这些限制，单个大型工具结果可能消耗整个上下文窗口，挤占对话历史和系统指令。 | 低-中 |

### 上下文修剪总结

上下文修剪是一个 **P1** 差距。虽然不如压缩关键（压缩是 P0），但修剪是一个重要的配套功能。压缩处理对话历史，而修剪处理工具结果。它们共同构成完整的上下文管理策略。没有修剪：

- 调用产生大型输出的工具（文件读取、数据库查询、API 响应）的 agent 将看到工具结果主导上下文窗口
- 来自较早轮次的过期工具结果将浪费上下文空间
- 没有机制来限制各工具结果的大小

### 修剪 vs 压缩：何时使用哪个

| 场景 | 使用修剪 | 使用压缩 |
|----------|------------|----------------|
| 来自 3 轮前的大型工具结果（100K 文件读取），不再被引用 | 是 -- 修剪/用占位符替换 | 否 -- 工具结果不是对话历史 |
| 50 轮对话，带有冗长的模型响应 | 否 -- 修剪会丢弃单独的消息 | 是 -- 将旧轮次压缩为摘要 |
| 包含不应持久化的敏感数据的工具结果 | 是 -- TTL 过期后修剪 | 否 -- 压缩摘要化可能泄露数据 |
| 系统提示 + 红线 | 绝不修剪 | 绝不压缩（在 postCompactionSections 中） |
| 最近对话（最近 5 轮） | 绝不修剪 | 绝不压缩（在 keepRecentTokens 中） |
| 记忆注入结果 | 如果太大则修剪到 maxChars | 否 -- 记忆是被注入的，不是累积的 |

### 上下文修剪实现说明

修剪系统应在两个层面上运作：

1. **基于大小的修剪**：当工具结果超过可用上下文的 `softTrimRatio` 时，将其裁剪到 `maxChars`（保留开头的 `headChars` 和末尾的 `tailChars`，用 `"[...]"` 替换中间部分）。当超过 `hardClearRatio` 时，用 `placeholder` 文本完全替换。
2. **基于 TTL 的修剪**：在 `ttl` 持续时间后，来自旧轮次的工具结果有资格被修剪。`keepLastAssistants` 参数保护最近的上下文。

通过 `tools.allow`/`tools.deny` 列表的每工具控制至关重要——某些工具（例如 `get_user_profile`）返回的关键信息绝不能修剪，而其他工具（例如 `search_web`）返回的临时信息可以安全修剪。

---

## 5. 上下文限制

| 序号 | 类别 | LyClaw现状 | OpenClaw实现 | 差距严重性 | 复杂度 |
|---|----------|---------------|------------------------|-------------|------------|
| 5.1 | **每终端上下文限制** | **无。** 任何地方都没有强制执行 token 限制、字符限制、字节限制。唯一的限制是 `maxToolRounds`（来自 `AgentProperties` 的默认值），它限制 ReAct 循环迭代次数但不限制上下文大小。 | `memoryGetMaxChars`（12000）、`memoryGetDefaultLines`（120）、`toolResultMaxChars`（16000）、`postCompactionMaxChars`（1800）。每个限制是按操作类型和每终端的。 | **P0** -- 没有任何上下文限制，单个操作可能默默地消耗整个可用上下文窗口，导致后续操作失败或产生降质输出。这是生产可靠性的要求。 | 中 |
| 5.2 | **工具结果大小限制** | 无界。`RespondStage` 通过 `ctx.addToolResult(result.getResult())` 存储完整的工具结果，不进行截断。完整结果也添加到 `ChatRequest.messages` 中作为工具消息，没有大小检查。 | `toolResultMaxChars`（默认 16000）-- 任何超过此值的工具结果被截断。这防止单个 `read_file` 或 `web_search` 调用消耗整个上下文窗口。 | **P0** -- 大型文件的 `read_file`（或返回大型页面的 `web_search`）可能默默消耗 100K+ 字符的上下文。模型可能仍然响应，但拥挤的上下文将降低后续轮次的质量。 | 低 |
| 5.3 | **记忆检索限制** | ContextBuildStage 使用 `MemoryQuery.builder().topK(10).build()` -- 限制记忆条目数量但不限制检索记忆的总字符数。单个记忆条目可能任意大。 | `memoryGetMaxChars`（12000）限制检索到的记忆内容的总字符数。`memoryGetDefaultLines`（120）限制行数。 | **P1** -- 如果记忆条目包含大型文档块，它可能挤占其他检索到的记忆和用户消息。 | 低 |
| 5.4 | **压缩后限制** | 不适用（无压缩） | `postCompactionMaxChars`（1800）-- 注入到上下文中的压缩后摘要的最大大小。防止摘要本身消耗太多空间。 | **P1** -- 依赖压缩的实现。 | 低 |
| 5.5 | **上下文预算感知** | LyClaw 中没有任何组件知道模型的上下文窗口大小。没有 `maxContextTokens` 的配置，没有分词器，没有预算跟踪。 | `contextTokenBudget` 是 `PluginHookAgentContext` 中的一级字段，使其对每个钩子可用。上下文引擎主动跟踪剩余预算。 | **P0** -- 上下文预算感知是压缩、修剪和上下文限制正确工作的前提条件。不知道有多少预算可用，系统无法决定何时压缩、何时修剪或截断多少。 | 中-高 |
| 5.6 | **上下文窗口来源/参考** | 没有来源跟踪。 | `contextWindowSource`（哪个配置定义了窗口大小）和 `contextWindowReferenceTokens`（模型的参考 token 数）。这些允许系统根据使用的模型动态调整。 | **P1** -- 不同模型有不同的上下文窗口（GPT-4o：128K，Claude 3.5 Sonnet：200K，DeepSeek-V3：64K）。LyClaw 硬编码的方法无法适应每个模型的上下文限制。 | 低 |

### 上下文限制总结

上下文限制是一个 **P0** 差距。三个最紧迫的需求是：

1. **工具结果大小限制** -- 防止单个工具调用消耗上下文窗口（低复杂度，P0）
2. **上下文预算感知** -- 跟踪剩余 token 以便压缩/修剪知道何时触发（中-高复杂度，P0）
3. **每模型上下文窗口配置** -- 根据活跃的模型调整限制（低复杂度，P1）

---

## 6. Agent 终审 / 修订门控

| 序号 | 类别 | LyClaw现状 | OpenClaw实现 | 差距严重性 | 复杂度 |
|---|----------|---------------|------------------------|-------------|------------|
| 6.1 | **终审门控是否存在** | **作为结构化概念不存在。** `afterResult` 钩子在流水线完成后触发，但仅提供文本转换。无法触发修订、强制终审或提供结构化重试指令。 | `AgentHarnessBeforeAgentFinalizeOutcome = {action:"continue"} | {action:"revise", reason} | {action:"finalize", reason}`。`PluginHookBeforeAgentFinalizeResult = {action, reason, retry?: {instruction, idempotencyKey, maxAttempts}}`。 | **P0** -- 终审门控是钩子系统最强大的控制点。它允许插件检查 agent 的输出，并决定是接受、送回修订（附带特定指令），还是强制终止。 | 中 |
| 6.2 | **带指令的修订** | `AgentInvocationHandler` 有硬编码的重试：如果 `reflectionScore < 0.6 && failCount > 0`，最多重试 PlanExecution+Respond+Reflection 2 次。重试是盲目的——它重新执行相同的阶段，而不向 LLM 提供任何关于出了什么问题的反馈。 | `retry: { instruction, idempotencyKey, maxAttempts }`。`instruction` 字段被注入下一轮的提示中，告诉模型具体要修复什么。`idempotencyKey` 防止重复重试。`maxAttempts` 允许每修订尝试的限制。 | **P0** -- 盲目重试是无效的。如果模型犯了错误（幻觉、错误的工具选择、不完整的答案），重新运行相同的提示会产生相同的错误。模型需要关于要修复什么的具体反馈。 | 中 |
| 6.3 | **强制终审** | `ctx.setTerminated(true)` 被 SecurityCheckStage 用于在内容被阻止时中止流水线。这是一个二进制的开关，而不是优雅的终审。 | `{action:"finalize", reason}` -- 尽管存在质量担忧，但强制终审，附带有记录的原因。在以下情况下有用：(a) 达到最大重试次数，(b) 用户明确要求终审，(c) 时间预算超出。 | **P1** -- 目前流水线要么正常完成，要么通过异常终止。没有"因为约束要求而接受这个次优结果"的中间地带。 | 低 |
| 6.4 | **修订历史跟踪** | 未跟踪。先前尝试的输出被丢弃；只有最终结果字符串可用。如果重试 2 产生比重试 1 更差的结果，没有机制回退到最佳尝试。 | 重试系统保留幂等键，允许系统检测和去重重试尝试。可以从历史中恢复最佳尝试。 | **P1** -- 没有修订历史，系统无法选择最佳尝试，无法从失败的尝试中学习，也无法提供调试信息。 | 中 |
| 6.5 | **重试的幂等性** | 不保证。`executeStages()` 中的当前重试使用带条件的 `Flux.repeat()`。如果 SSE 连接断开且客户端重新连接，重试计数器重置，相同的重试可能再次执行。 | 重试指令上的 `idempotencyKey` 确保即使在重新连接之间，相同的修订也不会被应用两次。 | **P2** -- 仅对连接断开可能发生的 SSE/流式场景相关。 | 低-中 |
| 6.6 | **钩子驱动的重试 vs 硬编码的重试** | 硬编码在 `AgentInvocationHandler` 中：`MAX_REFLECTION_RETRIES = 2`、`REFLECTION_RETRY_THRESHOLD = 0.6`。没有钩子可以影响这些值或重试决策。 | 终审钩子结果（由插件返回）驱动重试。不同插件可以设置不同阈值、不同最大尝试次数和不同修订指令。编排层聚合插件决策。 | **P0** -- 硬编码的魔数是反可扩展的。安全插件可能想要最多 1 次重试；质量插件可能想要最多 5 次重试，并随温度递增。当前架构无法支持这个。 | 中 |

### Agent 终审 / 修订门控总结

终审/修订门控是一个 **P0** 差距，因为它代表了 agent 系统决定是否向用户交付响应的控制点。没有结构化门控：

- 重试是盲目的（没有给模型的修订指令）
- 重试阈值是硬编码的（不可插拔）
- 没有机制在时间/资源压力下强制终审
- 没有修订历史来选择最佳尝试

---

## 7. 重试策略

| 序号 | 类别 | LyClaw现状 | OpenClaw实现 | 差距严重性 | 复杂度 |
|---|----------|---------------|------------------------|-------------|------------|
| 7.1 | **重试触发机制** | 两个独立机制：(a) `AgentInvocationHandler.executeStages()` -- 如果 `reflectionScore < 0.6` 且 `failCount > 0`，通过 `Flux.repeat()` 重试 PlanExecution+Respond+Reflection，最多 2 次重试。(b) `ReflexionLoop`（独立类）-- 执行 -> 反思 -> 修订 -> 重试循环，带可配置的 `maxRetries` 和 `qualityThreshold`。ReflexionLoop 未被集成到主流水线中；它作为独立工具存在。 | 重试通过终审门控触发：插件返回 `{action:"revise", reason, retry: {instruction, maxAttempts}}`。harness 通过将修订指令注入下一轮并重新运行模型来处理重试。压缩也有自己的质量守卫重试（maxRetries）。 | **P0** -- LyClaw 有两个不连接的重试机制。流水线内置的重试（`executeStages`）不能按插件配置。`ReflexionLoop` 根本没有接入流水线。 | 中 |
| 7.2 | **带反馈的重试** | 流水线重试（`executeStages`）完全相同地重新执行相同阶段——没有向 LLM 反馈出了什么问题。`ReflexionLoop` 确实通过 `TaskPlanner.revise(plan, feedback)` 提供反馈，但这仅修订任务计划，而非 LLM 提示。 | 重试包含 `instruction`（注入下一个提示的具体反馈），通常由质量/反思系统生成。模型看到："你之前的响应有 X 问题。请用 Y 更正重试。" | **P0** -- 没有反馈的盲目重试是浪费的，经常适得其反。模型需要知道要修复什么，而不仅仅是需要修复某件事。 | 中 |
| 7.3 | **重试范围** | 硬编码为重试块 `PlanExecution -> Respond -> Reflection`。不能重试单个阶段，不能在重试时跳过 PlanExecution（浪费——计划通常正确，只是执行有误）。 | 修订将整个轮次送回模型，附带新指令。范围是"整个模型轮次"，更粗粒度但更简单。由于 OpenClaw 没有 LyClaw 的阶段分解，重试单元自然是一个轮次。 | **P2** -- LyClaw 的阶段粒度量试如果正确实现可能是一个优势（跳过重规划，仅重新执行）。目前它是部分实现的，但缺点是总是重新规划。 | 中 |
| 7.4 | **最大重试配置** | `AgentInvocationHandler` 中硬编码常量 `MAX_REFLECTION_RETRIES = 2`。不可配置。独立的 `ReflexionLoop` 将 `maxRetries` 作为构造函数参数，但未接入流水线。 | 重试指令中每次尝试的 `maxAttempts`，允许插件为不同故障模式指定不同限制。还通过 `timeoutMs` 有每插件超时。 | **P0** -- 硬编码的最大重试次数阻止了针对不同场景的调优（复杂编码任务可能需要 5 次重试，简单问答应该是 0 次）。 | 低 |
| 7.5 | **带修订计划的重试** | `ReflexionLoop` 调用 `taskPlanner.revise(currentPlan, feedback)` 生成带有调整后任务分解的新计划。这是正确的方法，但：(a) 未集成到流水线中，(b) 反馈不包括具体的 LLM 提示修订指令，仅任务级反馈。 | 不适用 -- OpenClaw 不使用基于 DAG 的任务规划，因此计划修订在那里不是一个概念。 | **P2** -- 这是 LyClaw 架构可能更优越的领域，但实现不完整（ReflexionLoop 是一个孤立的工具）。 | 中 |
| 7.6 | **重试退避 / 速率限制** | 无。重试通过 `Flux.repeat()` 立即发生，不带延迟。 | 重试系统尊重速率限制，并可以在重试尝试之间纳入退避（通过 harness 轮次调度隐式实现）。 | **P2** -- 立即重试可能冲击 LLM API。在重试之间增加小延迟（1-2 秒）是速率限制合规的良好实践，也能给模型一个"新鲜"的上下文。 | 低 |
| 7.7 | **重试指标 / 可观察性** | 反思分数被记录，但没有发出结构化的重试指标。SSE 流不指示正在发生重试（没有 `retry_start`/`retry_attempt` 事件）。 | 重试通过 harness 的 run/job/trace 系统跟踪。每次重试尝试是一个独立的模型调用，有自己的 trace span 和指标。 | **P2** -- 重试可观察性对调试 agent 循环和成本跟踪（重试消耗额外的 LLM 调用）很重要。 | 低-中 |
| 7.8 | **重试失败的回退** | 如果所有重试耗尽，最后一个结果按原样返回（没有回退策略）。在阻塞路径（`executeStagesBlocking`）中，来自最后一次尝试的 `finalResponse` 在不管质量的情况下被返回。 | 终审门控的 `{action:"finalize", reason}` 强制终止。harness 可以配置当质量无法达到时的回退消息。 | **P1** -- 在耗尽重试后，系统应该：(a) 从历史中返回最佳尝试，(b) 返回明确的错误，指示 agent 无法产生满意的响应，或 (c) 升级给人类。默默返回低质量结果是最差的选择。 | 低 |

### 重试策略总结

重试策略存在 **P0** 差距，因为当前重试机制：

1. **是盲目的** -- 重试时没有给模型反馈/指令
2. **是硬编码的** -- 阈值和最大尝试次数是编译时常量
3. **有两个不连接的实现** -- 流水线处理器有一个重试循环，`ReflexionLoop` 有另一个，它们不共享逻辑
4. **不能被钩子影响** -- 插件不能触发、阻止或配置重试行为

前进的道路是将重试集成到终审门控中：钩子返回 `{action:"revise", reason, retry:{instruction, maxAttempts}}`，流水线编排器用适当的反馈注入处理重试。

### 当前重试流程 vs 目标重试流程

**当前（LyClaw）**：
```
流水线执行：PlanExecution → Respond → Reflection
  ↓
ReflectionStage 在 AgentContext 中设置 reflectScoreRef
  ↓
AgentInvocationHandler 检查：score < 0.6 && failCount > 0？
  ↓ 是（盲目重试）
流水线重新执行：PlanExecution → Respond → Reflection
  [没有向 LLM 反馈出了什么问题]
  [最多重复 2 次，然后无论如何返回最后结果]
  ↓ 否
继续 Metrics → done
```

**目标（受 OpenClaw 启发）**：
```
流水线执行：PlanExecution → Respond → Reflection
  ↓
ReflectionStage 产生 ReflectionReport，包含：
  - overallScore、errors[]、suggestion
  ↓
触发钩子："before_agent_finalize"
  ↓
钩子返回结构化决策：
  ├── {action: "continue"}                    → 继续 Metrics → done
  ├── {action: "finalize", reason}            → 尽管有质量担忧，强制 done
  └── {action: "revise", reason,
        retry: {instruction, maxAttempts}}    → 注入指令，重试
           ↓
流水线使用修订指令重新执行：
  - PlanningHook 注入："先前尝试有问题：{errors}。请修复：{instruction}"
  - LLM 看到关于要纠正什么的具体反馈
  - ReflectionStage 重新评估
  - 最佳尝试通过 idempotencyKey 跟踪
  ↓
在达到 maxAttempts 或满足质量阈值后：
  从历史中选择最佳尝试 → 继续 Metrics → done
```

### 修订指令格式（建议）

当钩子触发修订时，修订指令应该是结构化的：

```json
{
  "action": "revise",
  "reason": "响应包含幻觉的 API 参数",
  "retry": {
    "instruction": "先前的响应引用了一个不存在的参数 'user_email'。正确的参数是 'email'。请用正确的参数名称重新生成 API 调用。",
    "idempotencyKey": "revise-hallucination-abc123",
    "maxAttempts": 3,
    "temperatureOverride": 0.3
  }
}
```

`instruction` 在重试前作为系统消息注入，因此模型看到：
```
[系统] 请求修订：先前的响应引用了一个不存在的参数 'user_email'。
正确的参数是 'email'。请用正确的参数名称重新生成 API 调用。
```

这与盲目重试有根本不同——模型接收关于出了什么问题的具体的、可操作的反馈。

---

## 8. 总体差距总结

### P0（阻塞 -- 生产前必须修复）

| 差距 | 复杂度 | 描述 |
|-----|-----------|-------------|
| **压缩** | 极高 | 没有管理不断增长的上下文的机制。超出上下文窗口的对话将失败。 |
| **上下文窗口管理 / token 预算** | 高 | 没有 token 计数、没有预算跟踪、没有上下文窗口感知。是压缩的前提条件。 |
| **上下文限制（工具结果、记忆、每终端）** | 中 | 工具结果和记忆条目大小无界。可能挤占对话上下文。 |
| **安全钩子/阶段重复** | 低 | SecurityCheckHook 和 SecurityCheckStage 冗余实现相同的关注点。 |
| **终审/修订门控** | 中 | 没有结构化机制让钩子触发带指令的修订、强制终审或提供重试参数。 |
| **带反馈的重试** | 中 | 重试是盲目的（没有给模型的修订指令）且硬编码（魔数）。 |
| **流水线与钩子的架构一致性** | 高 | 线性阶段流水线与上下文引擎生命周期模型根本不同。需要决定 LyClaw 向哪个方向演进。 |

### P1（高优先级 / 关键）

| 差距 | 复杂度 | 描述 |
|-----|-----------|-------------|
| **基于钩子名称的选择性注册** | 中 | 所有钩子始终触发；需要按钩子名称注册和选择性执行。 |
| **结构化决策/阻止语义** | 中 | 基于异常的阻止；需要带 reason/message/category 的 `InputGateDecision`。 |
| **钩子上下文数据丰富度** | 低-中 | AgentContext 中缺少 token 预算、模型信息、触发类型、渠道信息。 |
| **生命周期覆盖扩展** | 中 | 缺少压缩钩子、会话钩子、模型解析钩子、agent 终审钩子。 |
| **轮次间维护** | 中-高 | 没有轮次之间维护的概念（压缩、记忆刷新、垃圾回收）。 |
| **流水线错误处理策略** | 低 | "永不崩溃"策略过于宽松；需要可配置的错误升级。 |
| **记忆刷新** | 高 | 没有将压缩后的对话持久化到长期记忆的机制。 |
| **压缩质量守卫** | 中-高 | 没有压缩输出质量的重试/重验证。 |
| **压缩轮次中预检查** | 中 | 轮次中 LLM 调用前没有主动压缩。 |
| **压缩后段落** | 低 | 没有确保关键系统上下文在压缩后存活的保证。 |
| **上下文修剪（工具结果）** | 中 | 没有基于 TTL 或大小的工具结果修剪。 |
| **工具级修剪控制** | 低 | 没有修剪资格的每工具 allow/deny。 |

### P2（中优先级 / 重要）

| 差距 | 复杂度 | 描述 |
|-----|-----------|-------------|
| **钩子每名称优先级** | 低 | 跨所有钩子的扁平排序共享；需要按阶段或按名称的优先级。 |
| **钩子超时/保护** | 中 | 钩子上没有超时执行。 |
| **观察性钩子** | 低 | 没有用于审计日志的 `llm_input`/`llm_output`；没有用于延迟的 `model_call_started`/`ended`。 |
| **钩子链并发** | 中 | 仅顺序执行；需要并行+短路以扩展。 |
| **插件生命周期管理** | 中-高 | 没有第三方插件的安装/卸载/启用/禁用。 |
| **会话生命周期钩子** | 低-中 | 没有 `session_start`/`session_end`/`before_reset`。 |
| **每请求阶段自定义** | 中 | 不能按请求跳过/修改阶段（例如简单查询跳过规划）。 |
| **重试退避** | 低 | 重试之间没有延迟；可能冲击 LLM API。 |
| **重试可观察性** | 低-中 | 没有用于重试进度的结构化 SSE 事件。 |
| **修订历史 / 最佳尝试恢复** | 中 | 如果后续重试产生更差结果，无法恢复最佳尝试。 |

### P3（锦上添花 / 未来）

| 差距 | 复杂度 | 描述 |
|-----|-----------|-------------|
| **消息路由钩子** | 高 | 多渠道分发（gateway、inbound_claim 等） |
| **子 agent 生命周期钩子** | 高 | 分层多 agent 衍生/结束钩子。 |
| **Cron/调度钩子** | 中 | 心跳和 cron-changed 钩子。 |
| **压缩用户通知** | 低 | 在对话被压缩时通知用户。 |

---

## 9. 建议的实现顺序

基于依赖分析，推荐的实现顺序是：

### 阶段 1：基础（第 1-3 周）
1. **修复安全钩子/阶段重复**（P0，低）-- 统一为单个执行点
2. **添加上下文限制**（P0，低-中）-- 工具结果最大字符数、记忆检索最大字符数
3. **添加 token 计数基础设施**（P0，中-高）-- 集成分词器，向 AgentContext 添加预算跟踪

### 阶段 2：压缩（第 4-8 周）
4. **实现压缩系统**（P0，极高）-- 默认模式、token 预算管理、模型覆盖
5. **添加压缩钩子**（P1，中）-- before_compaction、after_compaction
6. **实现记忆刷新**（P1，高）-- 将压缩后的历史持久化到记忆
7. **添加压缩后段落**（P1，低）-- 保留系统提示、红线

### 阶段 3：上下文引擎（第 9-12 周）
8. **过渡到上下文引擎生命周期**（P0，高）-- 引导、装配、终审、维护
9. **实现轮次间维护**（P1，中-高）-- 按"turn"原因压缩，记忆 GC
10. **添加上下文修剪**（P1，中）-- 带工具级控制的 cache-ttl 模式

### 阶段 4：钩子系统改造（第 13-16 周）
11. **命名钩子点**（P1，中）-- 从 5 个方法扩展到 15-20 个命名钩子点
12. **结构化决策模型**（P1，中）-- InputGateDecision，带元数据的阻止
13. **终审/修订门控**（P0，中）-- 带 revise/continue/finalize 的 AgentHarnessBeforeAgentFinalizeOutcome
14. **带反馈的重试**（P0，中）-- 将修订指令注入重试提示

### 阶段 5：生产加固（第 17-20 周）
15. **钩子超时执行**（P2，中）
16. **重试退避和可观察性**（P2，低-中）
17. **插件生命周期管理**（P2，中-高）
18. **会话生命周期钩子**（P2，低-中）
19. **子 agent/cron 钩子**（P3，高）-- 推迟到未来版本

---

## 10. 关键设计决策

### D-1：线性流水线 vs 上下文引擎

LyClaw 当前使用线性阶段流水线。OpenClaw 使用上下文引擎生命周期。根本问题是：LyClaw 应该向上下文引擎模型演进，还是用上下文管理能力增强阶段流水线？

**建议**：向混合模型演进。保留阶段流水线用于每轮处理（它提供了出色的可扩展性），但添加一个持久化的 ContextEngine，在跨轮次间管理上下文窗口。ContextEngine 将是一个新的单例服务（不是阶段），它：
- 跨轮次跟踪 token 预算
- 在预算超出时触发压缩
- 提供由流水线阶段消费的上下文装配服务
- 运行轮次间维护（记忆刷新、修剪）

流水线阶段（ContextBuild、SecurityCheck 等）将向 ContextEngine 查询预算信息并委托压缩决策给它，而不是自己实现压缩逻辑。

### D-2：钩子系统演进

两条路径：
1. **增量式**：向 `AgentHook` 添加更多方法（例如 `beforeCompaction`、`afterCompaction`、`beforeFinalize`）。优点：熟悉，迁移成本低。缺点：接口庞大臃肿，所有钩子必须实现所有方法。
2. **命名钩子**：围绕 `PluginHookRegistration { hookName, handler, priority }` 重新设计。优点：可扩展，清晰的关注点分离。缺点：迁移成本，新概念。

**建议**：路径 2（命名钩子）。当前 5 方法接口已经显露不足（安全重复）。命名钩子是行业标准（OpenClaw、LangChain 回调、Vercel AI SDK 中间件）。实现一个适配器层，以便现有的 `AgentHook` 实现在迁移期间继续工作。

### D-3：压缩模型选择

OpenClaw 的压缩使用 LLM 调用来摘要化对话历史。替代方法：
- **LLM 摘要化**（OpenClaw 的方法）：最灵活，处理任意对话内容，但消耗一次 LLM 调用。
- **滑动窗口**（最简单）：超出预算时丢弃最旧的消息。无 LLM 成本但丢失历史。
- **混合**：最近消息使用滑动窗口 + 较旧消息使用 LLM 摘要化。

**建议**：混合方式，匹配 OpenClaw 的方法。`keepRecentTokens` 参数逐字保留最后 N 轮，而较旧的轮次被摘要化。添加 `truncateAfterCompaction` 回退，以应对基于 LLM 的压缩失败的情况。

---

_本差距分析涵盖 7 个主要类别，共 65+ 行对比。本系列的下一个文档（03）将涵盖记忆系统和工具系统的差距。_
