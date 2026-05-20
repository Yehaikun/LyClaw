# LyClaw vs OpenClaw：Agent 核心差距分析（第一部分）

> **范围**：Agent 配置、运行时模式、子代理委托、配置层次与解析、Agent 作用域解析
>
> **日期**：2026-05-20
>
> **方法**：基于 LyClaw 源码（lyclaw-framework + lyclaw-autoconfigure）与 OpenClaw 2026.5.18 发行版代码，进行逐字段对比。

---

## 目录

1. [类别 1：Agent 配置（注解/接口 vs 配置对象）](#类别-1agent-配置)
2. [类别 2：Agent 运行时模式](#类别-2agent-运行时模式)
3. [类别 3：子代理委托](#类别-3子代理委托)
4. [类别 4：配置层次与解析](#类别-4配置层次与解析)
5. [类别 5：Agent 作用域解析](#类别-5agent-作用域解析)
6. [总结：优先级矩阵](#总结优先级矩阵)

---

## 架构概览：LyClaw Agent 系统（当前状态）

在深入差距分析之前，这里先简要总结 LyClaw 的 agent 系统当前的工作方式：

```
接口上的 @Agent 注解
        │
        ▼
AgentProxyFactory.create(Class<T>)
        │  读取 @Agent(name, description, version, model, provider, extensions[])
        │  创建 AgentInvocationHandler(chatFacade, reActEngine, toolRegistry, ...)
        │
        ▼
JDK Proxy.newProxyInstance() ──► AgentInvocationHandler.invoke()
        │
        ├── 1. 从方法注解解析 @SystemMessage / @UserMessage
        ├── 2. 构建 AgentContext (sessionId, userMessage, systemPrompt, chatRequest, toolRegistry, method, args)
        ├── 3. beforeRequest 钩子（按 getOrder() 排序执行）
        ├── 4. 阶段流水线 (ContextBuild→SecurityCheck→PlanExecution→Respond→Reflection→Metrics)
        │       │
        │       └── ReAct 循环（嵌入 RespondStage 中）
        │           ├── 通过 ChatFacade → ModelRouter → ChatModel 进行 LLM 调用
        │           ├── 工具检测（3 状态流式：缓冲/中继/工具）
        │           ├── 通过 ToolExecutor 链执行工具（由 SandboxHook + ApprovalHook 包装）
        │           └── 多轮循环直到文本响应或达到 maxToolRounds
        │
        ├── 5. afterResult 钩子（逆序执行）
        └── 6. 返回 String / Flux<SSE> / Mono<String>
```

关键观察：**整个 agent 生命周期由 `@Agent` 注解和 JDK 代理调用处理器驱动**。在此流程中，没有配置对象、没有默认值层、没有运行时模式选择，也没有子代理生成。

---

## 类别 1：Agent 配置

### 1.1 标识符与基本信息

| 子类别 | LyClaw 当前状态 | OpenClaw 实现 | 差距严重程度 | 复杂度 |
|---|---|---|---|---|
| **Agent 身份** | `@Agent(name="chat")` — 单个字符串名称。没有独立于 name 的 `id` 字段。name 同时承担注册键和显示标签的双重功能。 | `AgentConfig.id`（必填，唯一字符串）+ `AgentConfig.name`（可选，人类可读）。ID 是路由绑定、子代理引用、日志的标准标识；name 仅用于显示。 | P1 | 低 |
| **默认 agent 标记** | 没有"默认"agent 的概念。路由绑定不依赖 agent 身份；`ModelRouter` 按内容路由，而非按 agent 路由。 | `AgentConfig.default: boolean` — 将某个 agent 标记为全局兜底的默认 agent。当没有路由绑定匹配到传入消息时，由 `default=true` 的 agent 处理。 | P1 | 低 |
| **描述** | `@Agent(description="...")` — 纯文本字符串，在 `AgentProxyFactory.create()` 中用作回退系统提示。 | `AgentConfig.description: string` — 仅用于 UI/管理面板的描述性文本；系统提示是单独的 `systemPromptOverride` 字段。 | P2 | 低 |
| **版本** | `@Agent(version="1.0.0")` — 存储在注解中的语义化版本字符串，用于跟踪迭代历史。 | `AgentConfig` 上没有显式的版本字段。版本管理在部署/配置文件层面处理。 | P3 | N/A |
| **工作空间目录** | 不存在。没有每个 agent 独立工作目录的概念。文件系统工具相对于全局进程当前工作目录操作。 | `AgentConfig.workspace: string` — 从默认值继承。agent 用于所有文件操作和引导文件的工作目录。 | P1 | 中 |
| **Agent 私有目录** | 不存在。 | `AgentConfig.agentDir: string` — 与工作空间分离，存储 agent 私有数据（会话归档、技能数据）。 | P2 | 中 |

### 1.2 系统提示与引导上下文

| 子类别 | LyClaw 当前状态 | OpenClaw 实现 | 差距严重程度 | 复杂度 |
|---|---|---|---|---|
| **系统提示覆盖** | 系统提示来自方法上的 `@SystemMessage` 注解或 `AgentProxyFactory` 中的 `defaultSystemPrompt`。没有独立于方法注解的 agent 级别覆盖。 | `AgentConfig.systemPromptOverride: string` — **完全替换**整个系统提示。与方法级别或内联提示分开。 | P1 | 中 |
| **上下文注入模式** | 不存在。引导/上下文注入不能按 agent 配置。 | `AgentConfig.contextInjection: "always" \| "continuation-skip" \| "never"` — 控制 AGENTS.md/引导文件何时注入到上下文中。 | P2 | 低 |
| **引导文件限制** | 不存在。 | `AgentConfig.bootstrapMaxChars: number`（默认 20000）每文件；`bootstrapTotalMaxChars: number`（默认 150000）所有文件总计。 | P2 | 低 |
| **技能白名单** | 不存在于 agent 级别。技能在 `SkillRegistry` 中全局注册；没有按 agent 过滤技能。 | `AgentConfig.skills: string[]` — 该 agent 可用技能 ID 的白名单。**显式设置完全替换默认值**（不合并）。 | P1 | 中 |

### 1.3 模型配置

| 子类别 | LyClaw 当前状态 | OpenClaw 实现 | 差距严重程度 | 复杂度 |
|---|---|---|---|---|
| **主模型** | `@Agent(model="deepseek-v4-flash")` — 单个字符串模型名。`@Agent(provider="deepseek")` — 单个提供商字符串。通过 `ModelRouter` + `ChatFacade.route()` 解析。 | `AgentConfig.model: string \| { primary, fallbacks[] }` — 可以是简单的 "provider/model" 字符串，也可以是包含 `primary` + 有序 `fallbacks` 列表的对象。 | P1 | 中 |
| **模型回退链** | `FallbackChatModel` 装饰器作为通用装饰器模式存在，不针对特定 agent。没有按 agent 配置回退。 | `AgentModelConfig.fallbacks: string[]` — 有序回退模型列表。自动回退探测：如果主模型失败，依次尝试 fallback[0]，fallback[1] 等。 | P1 | 中 |
| **按模型元数据目录** | 不存在。只有 YAML 中的 `ChatProperties.ModelProperties`（provider, model, baseUrl, apiKey）。 | `AgentConfig.models: Record<string, AgentModelEntryConfig>` — 按模型 ID 的元数据，包括 `alias`、`params`（提供商特定的 API 参数）、`agentRuntime` 覆盖、`streaming` 开关。 | P2 | 高 |
| **多模型支持（图像/视频/音乐/PDF）** | 不存在。每个 agent 只有一个文本模型。 | `AgentDefaultsConfig` 有 `imageModel`、`imageGenerationModel`、`videoGenerationModel`、`musicGenerationModel`、`pdfModel` — 每个都有 primary+fallbacks。还有 `pdfMaxBytesMb`、`pdfMaxPages`。 | P2 | 高 |
| **模型路由** | `ModelRouter` 接口 + `FirstAvailableRouter`、`RegexKeywordRouter`、`LlmBasedRouter`。按请求内容分析路由。 | 基于 Agent 绑定：通过 channel + accountId + peer + guildId + teamId 匹配路由。模型选择通过配置层次完成，而非运行时路由器。 | P2 | 中 |

### 1.4 思考 / 推理 / 详细度 / 提权控制

| 子类别 | LyClaw 当前状态 | OpenClaw 实现 | 差距严重程度 | 复杂度 |
|---|---|---|---|---|
| **思考预算** | 不存在。任何级别都没有思考/推理预算控制。 | `AgentConfig.thinkingDefault: "off" \| "minimal" \| "low" \| "medium" \| "high" \| "xhigh" \| "adaptive" \| "max"` — 8 级思考预算（从默认值继承）。 | P1 | 中 |
| **详细模式** | 不存在。 | `AgentConfig.verboseDefault: "off" \| "on" \| "full"` — 控制 agent 响应的详细程度。 | P2 | 低 |
| **推理可见性** | 不存在。 | `AgentConfig.reasoningDefault: "off" \| "on" \| "stream"` — 控制推理跟踪是否对用户可见以及是否流式输出。 | P1 | 中 |
| **快速模式** | 不存在。 | `AgentConfig.fastModeDefault: boolean` — 跳过某些处理步骤以提升速度。 | P2 | 低 |
| **提权模式** | 不存在。没有权限提升的概念。 | `AgentConfig.elevatedDefault: boolean`（可继承）— 允许 agent 请求提升权限。 | P2 | 中 |
| **阻塞流式** | 不存在。流式由方法返回类型（`Flux` vs `String`）控制，而非 agent 配置。 | `AgentConfig.blockStreamingDefault: boolean` — 强制非流式模式，忽略其他设置。 | P3 | 低 |
| **工具进度详情** | 不存在。SSE 事件包含带状态（executing/done）的 `tool_call`，但没有可配置的详情级别。 | `AgentConfig.toolProgressDetail: "explain" \| "raw"` — 控制工具执行进度如何呈现给用户。 | P2 | 低 |

### 1.5 运行时行为控制

| 子类别 | LyClaw 当前状态 | OpenClaw 实现 | 差距严重程度 | 复杂度 |
|---|---|---|---|---|
| **人类延迟模拟** | 不存在。 | `AgentConfig.humanDelay: number \| [number, number]` — 模拟打字延迟（毫秒），实现更自然的交互节奏。 | P3 | 低 |
| **TTS 配置** | 不存在。 | `AgentConfig.tts: AgentTtsConfig` — 文本转语音配置，包含提供商、语音、语言、速度。深度合并到 `messages.tts` 中。 | P3 | 中 |
| **上下文限制框架** | 不存在于 agent 级别配置。没有按 agent 的 `contextTokens` 或工具结果截断限制。 | `AgentConfig.contextLimits` 包含 5 个字段：`memoryGetMaxChars`、`memoryGetDefaultLines`、`toolResultMaxChars`、`postCompactionMaxChars`、`contextTokens`。 | P1 | 中 |
| **心跳** | 不存在。 | `AgentConfig.heartbeat` 包含 10 个字段：`every`、`activeHours`（start/end/timezone）、`model`、`session`、`prompt`、`lightContext`、`isolatedSession`、`skipWhenBusy`、`includeReasoning`。 | P2 | 中 |
| **身份配置** | 不存在。 | `AgentConfig.identity: AgentIdentityConfig` — agent 角色/身份设置。 | P3 | 低 |
| **群聊设置** | 不存在于 agent 配置级别。群聊由 `CollaborationHub` + `ConsensusEngine` 单独处理。 | `AgentConfig.groupChat: GroupChatConfig` — agent 在群聊场景中的行为。 | P2 | 中 |
| **运行重试** | 不存在。重试逻辑仅存在于流水线级别（ReflectionStage 重试块，MAX_REFLECTION_RETRIES=2）。 | `AgentConfig.runRetries: number` — agent 整个运行失败时的重试次数。 | P2 | 低 |
| **嵌入式 PI** | 不存在。 | `AgentConfig.embeddedPi: boolean` — 是否在 agent 内使用嵌入式 PI（进程智能）运行时。 | P2 | 高 |
| **沙箱配置** | 基础：`SandboxLevel` 枚举（DIRECT、SANDBOX、DISABLED）由 `SecurityCheckHook` 设置。`ToolSandbox` 接口委托执行。 | `AgentConfig.sandbox: AgentSandboxConfig`，包含 10 多个字段：`mode`（off/non-main/all）、`backend`（docker）、`workspaceAccess`（none/ro/rw）、`sessionToolsVisibility`、`scope`、`workspaceRoot`，以及嵌套的 `docker`/`ssh`/`browser`/`prune` 子配置。Docker 设置包括 `image`、`network`、`memory`、`cpus`、`gpus`、`seccomp`（20 多个子字段）。 | P1 | 高 |
| **参数** | `@Agent(extensions={@Extension(key="...", value="...")})` — 扁平的键值对。没有结构化的参数对象。 | `AgentConfig.params: Record<string, unknown>` — 任意结构化参数，支持完全类 JSON 嵌套。 | P2 | 中 |
| **运行时** | 不作为配置存在。Agent 始终通过 JDK 动态代理在进程内运行。 | 参见[类别 2：Agent 运行时模式](#类别-2agent-运行时模式)。 | P0 | 极高 |

### 1.6 工具配置

| 子类别 | LyClaw 当前状态 | OpenClaw 实现 | 差距严重程度 | 复杂度 |
|---|---|---|---|---|
| **工具配置文件/预设** | 不存在。工具在 `ToolRegistry` 中全局注册。通过 `ToolCallPolicy` + `ToolDefinition.getAllDefinitions(ChatRequest)` 按请求过滤工具。 | `AgentConfig.tools.profile: "minimal" \| "coding" \| "messaging" \| "full"` — 四种预设工具配置文件。 | P1 | 中 |
| **工具允许/拒绝列表** | 不存在于 agent 配置级别。工具过滤仅通过运行时的 `ToolCallPolicy` 实现。 | `AgentConfig.tools.allow: string[]`（白名单）、`tools.alsoAllow: string[]`（附加）、`tools.deny: string[]`（黑名单，最高优先级）。 | P1 | 中 |
| **按提供商的工具覆盖** | 不存在。 | `AgentConfig.tools.byProvider: Record<string, ToolPolicyConfig>` — 按模型提供商覆盖的工具策略。 | P2 | 中 |
| **按发送者的工具** | 不存在。 | `AgentConfig.tools.toolsBySender: GroupToolPolicyBySenderConfig` — 根据消息发送者使用不同的工具策略。 | P3 | 中 |
| **代码模式** | 不存在。 | `AgentConfig.tools.codeMode: CodeModeConfig` — 用于代码执行的 QuickJS WASI 沙箱。 | P2 | 高 |
| **提权工具** | 不存在。 | `AgentConfig.tools.elevated: { enabled, allowFrom }` — 需要提权权限的工具。 | P2 | 中 |
| **执行/文件系统工具配置** | 不存在于 agent 级别配置。Shell 执行由 `ToolSandbox` + `SandboxLevel` 处理。 | `AgentConfig.tools.exec: ExecToolConfig`、`tools.fs: FsToolsConfig` — 针对 shell 执行和文件系统工具的详细按 agent 配置。 | P2 | 中 |
| **循环检测** | 基础：`AgentProperties` 中的 `maxToolRounds=30`。 | `AgentConfig.tools.loopDetection: ToolLoopDetectionConfig` — 可配置的循环检测，包含阈值、模式、动作。 | P2 | 中 |
| **消息工具配置** | 不存在。 | `AgentConfig.tools.message: MessageToolsConfig` — 消息相关工具的配置。 | P3 | 低 |
| **沙箱工具** | 不存在。 | `AgentConfig.tools.sandbox: { tools: { allow, alsoAllow, deny } }` — 沙箱特定的工具允许/拒绝列表。 | P2 | 中 |

### 1.7 详细对比：@Agent 注解 vs AgentConfig 对象

以下是每个 `@Agent` 注解字段及其在 OpenClaw 的 `AgentConfig` 中最接近的对应项（或缺失）的并排映射：

```
LyClaw @Agent 字段             OpenClaw AgentConfig 对应项
─────────────────────────────────────────────────────────────
name: String                 id: String（必填，唯一）
                             name: String（可选，仅用于显示）
                             → 名称承担不同的角色；OpenClaw 将身份与显示分离
description: String          description: String
                             → 用途相似，但 OpenClaw 仅用于 UI（不作为回退提示）
version: String              （无）
                             → LyClaw 有版本管理；OpenClaw 在部署层面处理
model: String                model: AgentModelConfig (string | {primary, fallbacks[]})
                             → LyClaw：单个字符串，提供商在单独字段中
                             → OpenClaw：组合的 "provider/model" 格式，带回退链
provider: String             （嵌入在 model 字符串 "provider/model" 中）
                             → LyClaw 将提供商与模型名称分离
extensions: Extension[]      params: Record<string, unknown>
                             tools: AgentToolsConfig
                             sandbox: AgentSandboxConfig
                             contextLimits: AgentContextLimits
                             heartbeat: AgentHeartbeat
                             thinkingDefault, verboseDefault, reasoningDefault 等
                             → LyClaw 的扁平键值对扩展是 OpenClaw
                               结构化子配置的前身；OpenClaw 的每个子配置字段都对应
                               LyClaw 中的一个潜在扩展键
```

### 1.8 实现说明：AgentConfig 的预期变更

鉴于差距严重程度，LyClaw 的 `AgentConfig`（目前 6 个核心字段）需要大幅扩展。以下结构性变更预期如下：

1. **添加与 `name` 分离的 `id` 字段**。`id` 成为所有内部查找的标准引用（路由、子代理引用、会话绑定、日志记录）。
2. **添加嵌套配置对象**：`modelConfig`（primary+fallbacks）、`toolsConfig`（profile/allow/deny/byProvider）、`sandboxConfig`（mode/backend/docker）、`contextLimits`（memoryGetMaxChars/toolResultMaxChars/contextTokens）。
3. **添加行为控制**：`thinkingDefault`、`verboseDefault`、`reasoningDefault`、`fastModeDefault`、`elevatedDefault`。
4. **添加目录字段**：`workspace`、`agentDir`。
5. **添加运行时字段**：`runtime`（embedded/acp 鉴别器）。
6. **添加子代理字段**：`subagents`（delegationMode/allowAgents/model）。
7. **保持向后兼容**：`@Agent` 注解的 `extensions` 数组应继续工作，当两者同时设置时，结构化配置字段优先于等效的扩展键。

---

## 类别 2：Agent 运行时模式

### 2.1 LyClaw 当前架构：始终嵌入式

LyClaw agent 目前**始终是嵌入式的**。执行路径如下：

```
用户请求
    │
    ▼
Spring MVC 控制器（例如 ChatController）
    │
    ▼
ChatAgent.chat(message)   ← JDK 动态代理
    │
    ▼
AgentInvocationHandler.invoke()
    │
    ▼
ReActEngine.execute(chatFacade, request, toolExecutor)
    │
    ▼
ChatFacade → ModelRouter → ChatModel（例如 DeepSeekChatModel）
    │
    ▼
对 LLM API 的 HTTP 调用（例如 api.deepseek.com）
    │
    ▼
在进程内返回响应
```

没有机制可以表示"此 agent 应在单独进程中运行"或"此 agent 实际上是远程 ACP 服务"。`A2aGateway` 提供了基本的 agent 到 agent 任务分发，但运行在不同的层面——它向外部分派任务到外部 agent，而不是远程运行 LyClaw agent。

### 2.2 OpenClaw 的鉴别联合运行时模型

| 子类别 | LyClaw 当前状态 | OpenClaw 实现 | 差距严重程度 | 复杂度 |
|---|---|---|---|---|
| **嵌入式运行时** | 仅隐式。所有 agent 通过 JDK 的 `Proxy.newProxyInstance()` 经 `AgentInvocationHandler` 在进程内运行。没有运行时模式概念——始终是嵌入式的。 | `AgentRuntimeConfig = { type: "embedded" }` — 显式声明 agent 在进程内运行。agent 的代码在同一个 Node.js 进程内执行。 | P1 | 低 |
| **ACP 运行时（远程 agent）** | 没有 ACP 协议支持。`A2aGateway` 提供基本的 Agent 到 Agent 通信（getAgentCard、sendTask、getArtifact、cancelTask），但 agent 本身始终是本地的。 | `AgentRuntimeConfig = { type: "acp", acp: { agent, backend, mode, cwd } }` — ACP（Agent Communication Protocol）运行时。Agent 作为远程进程运行，通过标准协议通信。支持 `mode: "persistent" \| "oneshot"`、`backend` 覆盖、`cwd` 覆盖。 | P0 | 极高 |
| **运行时策略配置** | 不存在。 | `AgentRuntimePolicyConfig = { id?: string }` — 运行时标识符。`id="pi"` 用于内置 PI 运行时，`id="auto"` 用于自动选择插件。可在 `AgentModelEntryConfig.agentRuntime` 中按模型设置。 | P1 | 中 |
| **运行时模式选择** | 不存在。没有按 agent 选择嵌入式与远程执行的机制。 | 按 agent：每个 `AgentConfig` 可以指定 `runtime.type`。按绑定：`AgentAcpBinding.acp` 可以额外覆盖运行时参数。按模型：`AgentModelEntryConfig.agentRuntime` 覆盖特定模型选择的运行时。 | P0 | 极高 |
| **运行时绑定** | Agent 作为 Spring Bean 注册，通过 `@Agent` 注解扫描发现。在 Spring DI 之外没有绑定概念。 | `AgentBinding = AgentRouteBinding \| AgentAcpBinding`。路由绑定将 channels/accounts 映射到 agent；ACP 绑定将 ACP 端点映射到 agent。每种绑定类型携带自己的运行时配置。 | P1 | 高 |
| **按运行时的 CWD（工作目录）** | 不存在。 | `AgentRuntimeConfig.acp.cwd` — ACP 运行时的工作目录覆盖。`AgentConfig.workspace` — 嵌入式运行时的工作空间目录。两个独立的目录概念。 | P1 | 中 |
| **持久 vs 一次性会话** | `AgentContext.Lifecycle` 枚举（TRANSIENT / SESSION / PERSISTENT）存在，但是上下文级别的概念，而非运行时模式。通过 `toSnapshot()` / `restoreFromSnapshot()` 实现检查点/恢复。 | ACP 模式支持 `mode: "persistent"`（长期存在的 agent 进程）vs `mode: "oneshot"`（每次任务生成）。不同的生命周期管理。 | P1 | 中 |

### 2.3 多运行时支持的架构影响

为 LyClaw 添加 ACP 运行时支持将需要重大的架构变更：

**当前流程（全部嵌入式）：**
```
AgentInvocationHandler.invoke()
    → ReActEngine.execute(chatFacade, request, toolExecutor)
    → ChatFacade → ChatModel → 对 LLM API 的 HTTP 调用
    → 返回结果
```

**目标流程（带 ACP 运行时支持）：**
```
AgentInvocationHandler.invoke()
    → resolveAgentExecutionContract(agentId)
    → if (runtime.type == "embedded"):
          ReActEngine.execute(chatFacade, request, toolExecutor)  [当前路径]
      else if (runtime.type == "acp"):
          AcpClient.sendTask(acpConfig.agent, taskSpec)
          → 等待远程 agent 完成（轮询或 SSE）
          → 收集远程工具调用和结果
          → 返回最终响应
```

关键设计决策：
1. **工具执行在哪里发生？** 在 ACP 模式下，远程 agent 有自己的工具注册表。LyClaw 的 `ToolExecutor`（函数接口）不适用。本地代理变成一个薄存根。
2. **流水线如何受影响？** 6 阶段流水线（ContextBuild→SecurityCheck→...→Metrics）当前在本地运行。对于 ACP agent，某些阶段（SecurityCheck、Sandbox、Approval）必须仍在本地运行（把关），而其他阶段（PlanExecution、Respond、Reflection）委托给远程运行时。
3. **钩子怎么办？** `wrapToolCall` 和 `wrapToolExecutor` 钩子对 ACP agent 无意义（工具执行在远程）。`beforeRequest` 和 `afterResult` 钩子仍然适用。
4. **会话和上下文序列化**：对于 ACP 模式，`AgentContext` 必须是可序列化的（或部分可序列化）才能发送到远程运行时。当前的 `AgentContext` 持有无法序列化的活动引用（`ToolRegistry`、`Method`、`Object[] args`）。

---

## 类别 3：子代理委托

### 3.1 LyClaw 当前架构：没有子代理概念

LyClaw 有两个独立的系统与子代理功能部分重叠，但两者都没有在 **agent 执行循环内**提供真正的子代理委托：

1. **AgentCoordinator.dispatch(ChatContext, AgentTask)** — 顶层任务分发。将任务分派给 agent 并返回 `CompletableFuture<AgentResult>`。这是编排层操作：控制器或服务决定调用哪个 agent，而非 agent 自身决定。

2. **AutoScaler** — 根据 `AgentPoolSnapshot` 管理 agent 池大小（扩容/缩容）。这是基础设施级别，而非任务级委托。

两者都不允许 Agent A 在其 ReAct 循环中说："我需要帮助处理这个子任务，让我生成 Agent B 来处理它，等待结果，并将其纳入我的响应中。"

### 3.2 OpenClaw 子代理架构

OpenClaw 的子代理系统直接集成到 agent 回合循环中。LLM 被赋予一个 `sessions_spawn` 工具。当 LLM 决定某个任务应被委托时，它会调用此工具。框架随后：

1. 根据 `SubagentConfig` 验证请求（白名单、并发、深度限制）
2. 解析子代理的配置（模型、思考级别、超时）
3. 生成子代理（嵌入式或通过 ACP）
4. 等待完成（带超时）
5. 将结果作为工具结果注入回父代理的对话中

这意味着子代理委托对 LLM 是透明的——它看起来就像另一个工具。生成、监控和收集的复杂性由框架处理。

| 子类别 | LyClaw 当前状态 | OpenClaw 实现 | 差距严重程度 | 复杂度 |
|---|---|---|---|---|
| **子代理概念** | **不存在。** agent 执行循环内没有子代理生成或委托。`AgentCoordinator.dispatch()` 在编排层（与核心 ReAct 循环分离）将任务分发给 agent，但 agent 在自身执行期间无法生成子 agent。 | 完整的子代理系统集成到 agent 回合循环中。Agent 可以在执行期间使用 `sessions_spawn` 工具生成子 agent。父 agent 监控子 agent、收集结果并继续。 | P0 | 极高 |
| **委托模式** | 不存在。 | `SubagentConfig.delegationMode: "suggest" \| "prefer"` — `"suggest"` 表示模型可以选择是否委托；`"prefer"` 表示系统强烈鼓励对适当的任务进行委托。 | P0 | 高 |
| **Agent 白名单** | 不存在。 | `SubagentConfig.allowAgents: string[]` — 可作为子代理生成的 agent ID 的显式白名单。`"*"` 表示允许所有 agent。 | P0 | 中 |
| **最大并发子代理数** | 不存在。 | `SubagentConfig.maxConcurrent: number`（默认 1）— 同时运行的子 agent 的最大数量。只能在默认值级别设置（安全边界）。 | P0 | 中 |
| **最大生成深度** | 不存在。 | `SubagentConfig.maxSpawnDepth: number`（默认 1，表示无嵌套）— agent → 子代理 → 孙代理 链的最大深度。只能在默认值级别设置。 | P0 | 低 |
| **每个 agent 最大子代理数** | 不存在。 | `SubagentConfig.maxChildrenPerAgent: number`（默认 5）— 单个父 agent 可以生成的子 agent 的最大数量。只能在默认值级别设置。 | P0 | 低 |
| **子代理归档** | 不存在。 | `SubagentConfig.archiveAfterMinutes: number`（默认 60）— N 分钟后自动归档已完成的子代理会话。 | P1 | 中 |
| **子代理模型** | 不存在。 | `SubagentConfig.model: AgentModelConfig` — 子代理会话的默认模型。可以与父 agent 的模型不同。 | P1 | 低 |
| **子代理思考级别** | 不存在。 | `SubagentConfig.thinking: string` — 专门用于子代理会话的思考预算级别。 | P2 | 低 |
| **子代理超时** | 不存在。 | `SubagentConfig.runTimeoutSeconds: number`（默认 0 = 无超时）— 子代理在被强制终止前的最大运行时间。 | P0 | 中 |
| **通告超时** | 不存在。 | `SubagentConfig.announceTimeoutMs: number`（默认 120000）— 向子代理传递通告的超时时间。 | P2 | 低 |
| **要求 Agent ID** | 不存在。 | `SubagentConfig.requireAgentId: boolean`（默认 false）— 当为 true 时，`sessions_spawn` 必须显式指定 `agentId`，而不是使用默认值。 | P2 | 低 |
| **子代理结果收集** | `AgentCoordinator.dispatch()` 返回 `CompletableFuture<AgentResult>`，但这未集成到 agent 的 ReAct 循环中。 | 子代理结果自动收集并作为 `sessions_spawn` 的工具结果注入到父 agent 的对话上下文中。父 agent 可以看到每个子代理做了什么。 | P0 | 高 |
| **父子生命周期** | 不存在。`AutoScaler` 管理层池扩容，但不管理父子关系。 | 完整生命周期：父代理生成 → 子代理运行 → 子代理完成 → 父代理接收结果 → 子代理在 `archiveAfterMinutes` 后归档。父代理也可以终止子代理。 | P0 | 高 |

### 3.3 添加子代理支持的架构影响

添加子代理委托将是 LyClaw 最大的一项架构变更。以下是必须发生的事情：

**当前 ReAct 循环（简化）：**
```
for round in 0..maxToolRounds:
    response = chatFacade.chat(request)
    if response.hasToolCalls():
        for toolCall in response.getToolCalls():
            result = toolExecutor.execute(toolCall.name, toolCall.id, toolCall.arguments)
            messages.add(Message.tool(toolCall.id, result))
    else:
        return response.getContent()
```

**带子代理支持的目标 ReAct 循环：**
```
for round in 0..maxToolRounds:
    response = chatFacade.chat(request)
    if response.hasToolCalls():
        for toolCall in response.getToolCalls():
            if toolCall.name == "sessions_spawn":         ← 新增：子代理生成工具
                subagentId = parseSubagentId(toolCall.arguments)
                // 1. 验证：subagentId 是否在 allowAgents 中？
                // 2. 检查限制：maxConcurrent、maxSpawnDepth、maxChildrenPerAgent
                // 3. 解析子代理配置（model、thinking、timeout）
                // 4. 生成子代理会话（嵌入式或 ACP）
                // 5. 等待完成（带超时）
                // 6. 收集结果 → 作为工具结果注入
                result = spawnAndWait(subagentId, toolCall.arguments)
            else:
                result = toolExecutor.execute(toolCall.name, toolCall.id, toolCall.arguments)
            messages.add(Message.tool(toolCall.id, result))
    else:
        return response.getContent()
```

关键设计决策：
1. **子代理作为特殊工具**：子代理生成以工具形式（`sessions_spawn`）呈现给 LLM。LLM 决定何时生成。这种方式很干净——无需更改 ReAct 算法本身。
2. **并发模型**：子代理可以并发运行（`maxConcurrent > 1`）。ReAct 循环必须支持 `CompletableFuture.allOf()` 实现并行子代理执行，在完成时收集结果。
3. **嵌套**：如果 `maxSpawnDepth > 1`，子代理可以生成孙代理。这需要在 `AgentContext` 中进行深度跟踪（添加 `spawnDepth` 字段）。
4. **资源限制执行**：`maxConcurrent`、`maxSpawnDepth`、`maxChildrenPerAgent` 必须在框架级别执行，不可按 agent 配置。这些是在 `AgentDefaultsConfig` 中设置的安全边界。
5. **结果注入格式**：子代理结果以结构化格式（子代理 ID、状态、输出摘要）作为工具结果注入，以便父代理 LLM 能够理解发生了什么。
6. **与运行时模式的集成**：子代理可以具有与其父代理不同的运行时（父代理嵌入式，子代理 ACP）。`resolveAgentExecutionContract()` 函数处理此问题。

### 3.4 对比：AgentCoordinator vs 子代理系统

| 方面 | LyClaw AgentCoordinator | OpenClaw 子代理系统 |
|---|---|---|
| **谁触发** | 外部调用者（控制器/服务） | 父 agent 在 ReAct 循环中（通过 `sessions_spawn` 工具） |
| **决策者** | 编排层（代码决定） | LLM 决定（呈现为工具） |
| **集成** | 与 agent 执行分离 | 作为工具结果集成到对话上下文中 |
| **并发** | 每次调用单次分发 | 每个父代理回合可多个并发子代理 |
| **生命周期** | 即发即忘 或 基于 Future | 完整生命周期：生成 → 监控 → 收集 → 归档 |
| **嵌套** | 不支持 | 支持，带深度限制 |
| **资源控制** | 通过 AutoScaler（层池级别） | 通过 SubagentConfig（按 agent 限制） |

---

## 类别 4：配置层次与解析

### 4.1 LyClaw 当前配置解析流程

```
AgentConfigResolver.resolve("chat")
    │
    ├── YamlAgentConfigSource.loadConfig("chat")     priority=10
    │       lyclaw.chat.models.chat.model
    │       lyclaw.chat.models.chat.provider
    │       lyclaw.chat.default-model（回退）
    │
    ├── AnnotationAgentConfigSource.loadConfig("chat")  priority=50
    │       @Agent(name="chat", model="...", provider="...",
    │              extensions={@Extension(key="planning.enabled", value="true")})
    │
    ├── [数据库配置源 — 预留槽位，priority=60，未实现]
    ├── [配置中心源 — 预留槽位，priority=70，未实现]
    │
    └── BuilderAgentConfigSource.loadConfig("chat")   priority=100
            LyClawAgent.builder(ChatAgent.class).model("...").provider("...")

    结果：扁平的 AgentConfig，每个键取最高优先级的值
```

**关键限制**：没有 AgentDefaultsConfig。每个字段必须在每个 agent 上显式设置，否则回退到硬编码的框架默认值。没有"所有 agent 继承此值，除非显式覆盖"的机制。

### 4.2 OpenClaw 配置解析流程

```
resolveAgentConfig("chat")
    │
    ├── 加载 AgentDefaultsConfig（全局默认值，约 50 个字段）
    │       model: "deepseek/deepseek-v4-flash"
    │       thinkingDefault: "off"
    │       tools.profile: "full"
    │       contextLimits.toolResultMaxChars: 16000
    │       subagents.maxConcurrent: 1       ← 仅默认值（不可按 agent 覆盖）
    │       subagents.maxSpawnDepth: 1       ← 仅默认值（安全边界）
    │       imageModel: { primary: "openai/gpt-4o", fallbacks: [...] }
    │       compaction.mode: "default"
    │       compaction.reserveTokens: 20000
    │       ...
    │
    ├── 加载 "chat" 的 AgentConfig（约 40 个字段，仅显式设置的部分）
    │       id: "chat"
    │       default: true
    │       description: "通用聊天 agent"
    │       systemPromptOverride: "你是一个有帮助的助手..."
    │       model: "deepseek/deepseek-v4-pro"  ← 覆盖默认值
    │       thinkingDefault: "medium"           ← 覆盖默认值
    │       skills: ["web-search", "code-interpreter"]  ← 替换默认值（不合并）
    │       tools: { profile: "coding", deny: ["shell_exec"] }
    │       ...
    │
    ├── 深度合并：默认值 → agent
    │       对于 AgentConfig 中的每个字段：
    │         如果 AgentConfig 中设置了该字段（非 undefined/null）→ 使用它
    │         如果 AgentConfig 中未设置该字段 → 从 AgentDefaultsConfig 继承
    │       特殊合并语义：
    │         - skills：显式设置会替换（而非合并）默认值
    │         - tts：递归深度合并（嵌套字段合并）
    │         - subagents.maxConcurrent：仅在默认值中（AgentConfig 中设置会被忽略）
    │         - tools.allow/deny：分层（profile → allow → alsoAllow → deny）
    │       → 标准配置
    │
    └── 应用运行时覆盖
            会话级别的有效模型（用户在会话中途切换了模型）
            绑定级别的运行时配置（ACP 绑定 cwd 覆盖）
            → 有效配置（用于此特定执行回合）
```

### 4.3 配置层次表

| 子类别 | LyClaw 当前状态 | OpenClaw 实现 | 差距严重程度 | 复杂度 |
|---|---|---|---|---|
| **配置层** | **3 层（已存在）：**（1）`application.yml` 通过 `YamlAgentConfigSource`（优先级 10），（2）`@Agent` 注解通过 `AnnotationAgentConfigSource`（优先级 50），（3）`LyClawAgent.builder()` 通过构建器（优先级 100）。还有数据库（60）和配置中心（70）的预留槽位——但这些未实现。**没有默认值层。** | **2 层带深度合并：**（1）`AgentDefaultsConfig`（约 50 个字段）— 应用于所有 agent 的全局默认值，（2）`AgentConfig`（约 40 个字段）— 按 agent 覆盖。深度合并：`默认值 → agent → 解析结果`。未设置的 agent 级字段从默认值继承。 | P0 | 高 |
| **默认值配置** | **不存在。** `AgentProperties`（6 个字段：defaultMode、maxToolRounds、approvalTimeoutSeconds、approvalStoreTimeoutSeconds、timeoutMs）是唯一的全局配置，且是扁平的/行为性的，而非 agent 的配置模板。 | `AgentDefaultsConfig` — 完整镜像 `AgentConfig` 结构（约 50 个字段）。为每个 agent 级字段定义回退值。包含仅存在于默认值中（不在 AgentConfig 中）的字段：多模型配置、上下文修剪、启动上下文、压缩（27 个字段）、子代理资源限制。 | P0 | 极高 |
| **配置合并策略** | 基于优先级的覆盖（最高优先级胜出）。无深度合并 — 通过 `AgentConfigResolver.resolve()` 进行扁平键值覆盖。`AgentConfig` 上的 `extensions` 映射具有键级覆盖语义。 | 深度合并：嵌套对象（如 `tts`、`sandbox`、`tools`、`contextLimits`）被递归合并。某些字段使用**替换**语义（例如 `skills` — 显式设置的技能完全替换默认值，而非合并）。某些字段是**仅默认值**（例如 `subagents.maxConcurrent`）。 | P0 | 高 |
| **解析后的配置** | `AgentConfigResolver.resolve(agentName)` 返回一个带有合并扁平属性 + extensions 映射的 `AgentConfig`。解析后的配置没有"标准"或"有效"配置的概念。 | 三层解析：（1）来自配置文件的原始 `AgentConfig`，（2）与 `AgentDefaultsConfig` 合并 → 标准配置，（3）运行时覆盖（会话级别、绑定级别、模型级别）→ 有效配置。 | P0 | 高 |

### 4.4 配置范围与覆盖

| 子类别 | LyClaw 当前状态 | OpenClaw 实现 | 差距严重程度 | 复杂度 |
|---|---|---|---|---|
| **可配置字段总数** | `@Agent`（6 个：name、description、version、model、provider、extensions[]）+ `AgentProperties`（6 个）+ `ChatProperties`（少量），约 15 个字段。Extensions 支持临时键值对（文档记录的键：planning.enabled、planning.strategy、memory.topK、tool.dynamicFiltering、mcp.servers、outputGuard.enabled、communication.protocol、maxToolRounds、sandbox）。 | `AgentConfig`（37 个字段）+ `AgentDefaultsConfig`（额外约 15 个仅默认值字段）+ 嵌套子配置：`AgentModelConfig`、`AgentModelEntryConfig`、`AgentRuntimeConfig`、`AgentRuntimePolicyConfig`、`AgentToolsConfig`、`AgentSandboxConfig`、`AgentContextLimits`、`AgentHeartbeat`、`AgentIdentity`、`GroupChatConfig`、`SubagentConfig`、`ContextPruningConfig`、`StartupContext`、`CompactionConfig`（27 个字段）、`ModelDefinitionConfig`、`AgentBinding`，总共约 90 多个字段。 | P0 | 极高 |
| **思考控制** | 不存在。 | 4 个独立的控制：`thinkingDefault`（8 个级别）、`verboseDefault`（3 个级别）、`reasoningDefault`（3 个级别）、`fastModeDefault`（布尔值）。 | P1 | 中 |
| **上下文 Token 限制** | 不存在。 | `AgentDefaultsConfig.contextLimits.contextTokens` — 上下文窗口 token 的硬限制。 | P1 | 低 |
| **上下文修剪** | 不存在。 | `AgentDefaultsConfig` 有专门的 `ContextPruningConfig`，包含 `mode`、`ttl`、`keepLastAssistants`、`softTrimRatio`、`hardClearRatio`、`minPrunableToolChars`、工具 allow/deny、`softTrim`（maxChars、headChars、tailChars）、`hardClear`（enabled、placeholder）。 | P1 | 高 |
| **压缩配置** | 不存在。存在基本的消息截断，但没有可配置的压缩策略。 | `AgentDefaultsConfig` 有完整的 `CompactionConfig`，包含 27 个字段：`mode`、`reserveTokens`、`keepRecentTokens`、`reserveTokensFloor`、`maxHistoryShare`、`customInstructions`、`recentTurnsPreserve`、`identifierPolicy`、`qualityGuard`（enabled、maxRetries）、`midTurnPrecheck`、`postIndexSync`、`memoryFlush`（enabled、model）、`postCompactionSections`、`model`、`timeoutSeconds`、`notifyUser`。 | P1 | 极高 |
| **启动上下文** | 不存在。 | `AgentDefaultsConfig` 有 `StartupContext`，包含 `enabled`、`applyOn`（new/reset）、`dailyMemoryDays`、`maxFileBytes`、`maxFileChars`、`maxTotalChars`。 | P2 | 中 |

### 4.5 模型解析

| 子类别 | LyClaw 当前状态 | OpenClaw 实现 | 差距严重程度 | 复杂度 |
|---|---|---|---|---|
| **模型解析流程** | `ChatFacade.route(request, context)` → `ModelRouter.route()` → `RoutingDecision`（provider + model + tier + reason）。然后 `ChatFacade.resolveModel(decision)` → `ChatModel`。模型是每次请求通过路由选择的，而非来自 agent 配置。 | `resolveAgentExplicitModelPrimary(agentId)` → 读取 `AgentConfig.model`（字符串或 {primary, fallbacks}）。`resolveAgentEffectiveModelPrimary(agentId)` → 遍历解析链：显式 → 默认值 → 系统回退。`resolveAgentModelFallbacksOverride(agentId)` → 返回有序回退列表。主模型失败时运行时自动回退探测。 | P0 | 高 |
| **有效模型** | 不作为概念存在。使用的模型是 `ModelRouter` 在运行时决定的任何内容，加上 `AgentProxyFactory` 应用的 `modelOverride`。 | `setAgentEffectiveModelPrimary(agentId, model)` — 运行时覆盖有效主模型（例如用户会话中途切换模型）。将"已配置"与"有效"分离。 | P1 | 中 |
| **子代理模型选择** | 不适用（没有子代理）。 | `resolveSubagentModelConfigSelection(parentAgentId, subagentConfig)` — 根据父配置、子代理配置和默认值确定子代理应使用哪个模型。 | P0 | 中 |
| **模型目录** | `ChatProperties.models` — 模型名 → `ModelProperties`（provider、model、baseUrl、apiKey）的扁平映射。 | 完整的 `ModelDefinitionConfig` 目录，包含 `id`、`name`、`api`（9 种 API 类型）、`reasoning`（布尔值）、`input`（模态：text/image/video/audio）、`cost`（input/output/cacheRead/cacheWrite 每百万 token）、`contextWindow`、`maxTokens`、`compat`（23 个兼容性字段）。 | P2 | 高 |
| **提供商发现** | `ChatModelProvider` 接口 — 提供商作为 Spring Bean 注册。 | 从模型目录条目自动发现提供商。模型 `id` 格式 "provider/model" 被解析以识别提供商。提供商特定的 API 参数存储在 `AgentModelEntryConfig.params` 中。 | P2 | 中 |

### 4.6 工具解析

| 子类别 | LyClaw 当前状态 | OpenClaw 实现 | 差距严重程度 | 复杂度 |
|---|---|---|---|---|
| **工具可用性** | `ToolRegistry.getAllDefinitions(request)` 中的所有工具对所有 agent 可用。通过 `ToolCallPolicy` 在运行时过滤。 | 通过 `AgentConfig.tools` 进行按 agent 的工具配置，采用分层策略：`profile`（预设）→ `allow`（白名单）→ `alsoAllow`（附加）→ `deny`（黑名单，最高优先级）→ `byProvider` → `toolsBySender` → `sandbox.tools`。 | P0 | 高 |
| **工具配置文件预设** | 不存在。 | 4 种预设：`minimal`（仅 session_status）、`coding`（files+runtime+network+memory+session+plans+media）、`messaging`（仅消息工具）、`full`（所有工具，通过 `["*"]`）。 | P1 | 中 |
| **代码执行模式** | `ToolSandbox` 提供基本沙箱隔离，但没有代码模式概念。 | `AgentConfig.tools.codeMode: CodeModeConfig` — 专用的 QuickJS WASI 沙箱，用于在 agent 上下文中执行代码。 | P2 | 高 |
| **提权工具** | `ApprovalHook` + `ApprovalStore` 提供带用户确认的工具审批流程。但没有"提权"与普通权限的概念区别。 | `AgentConfig.tools.elevated: { enabled, allowFrom }` — 仅在 agent 处于提权模式时可使用的工具。`elevatedDefault` 控制 agent 是否以提权模式启动。 | P2 | 中 |

---

## 类别 5：Agent 作用域解析

### 5.1 什么是 Agent 作用域解析？

Agent 作用域解析是一组回答以下问题的函数：
- "系统中存在哪些 agent？"
- "哪个 agent 应处理此传入消息？"
- "agent X 当前的有效配置是什么？"
- "agent X 应使用哪个模型（考虑默认值、覆盖和会话状态）？"
- "agent X 可以使用哪些工具？"
- "agent X 使用哪个工作空间目录？"

在 LyClaw 中，这些问题大多是临时回答的：Spring 的 `ApplicationContext` 提供 Bean 扫描，`AgentConfigResolver` 提供扁平配置合并，`ModelRouter` 提供请求时的模型选择。没有统一的"作用域解析"子系统。

在 OpenClaw 中，有一个专门的作用域解析层（约 30 个函数），为所有 agent 相关查询提供单一可信源。该层是 agent 运行器、路由、子代理生成和管理 UI 都依赖的基础。

### 5.2 对比表

| 子类别 | LyClaw 当前状态 | OpenClaw 实现 | 差距严重程度 | 复杂度 |
|---|---|---|---|---|
| **列出 agent 条目** | `ApplicationContext.getBeansWithAnnotation(Agent.class)` — 扫描 Spring 上下文中带有 `@Agent` 注解的 Bean。`AnnotationAgentConfigSource.loadConfig()` 遍历这些 Bean 以按名称查找配置。临时的，没有专用的注册表查询方法。 | `listAgentEntries(): AgentEntry[]` — 专用函数，返回所有 agent 条目及完整元数据。用于管理 UI、路由、子代理选择。 | P1 | 中 |
| **列出 agent ID** | 没有专用函数。名称从注解扫描或配置解析中临时提取。 | `listAgentIds(): string[]` — 返回所有已注册的 agent ID。用于验证、白名单和 UI 下拉菜单。 | P1 | 低 |
| **解析默认 agent ID** | 不存在。没有默认/回退 agent 的概念。 | `resolveDefaultAgentId(): string` — 返回带有 `default: true` 的 agent 的 `id`。当没有路由绑定匹配时使用。 | P1 | 低 |
| **解析 agent 配置** | `AgentConfigResolver.resolve(agentName)` — 从注册的 `AgentConfigSource` 实例按优先级合并。返回带有扁平属性和 extensions 映射的 `AgentConfig`。没有默认值层。 | `resolveAgentConfig(agentId): AgentConfig` — 返回完全合并的配置（默认值 → agent）。首次解析后缓存。 | P0 | 高 |
| **解析 agent 上下文限制** | 不存在。 | `resolveAgentContextLimits(agentId): AgentContextLimits` — 解析 agent 的有效上下文限制（记忆获取最大字符数、工具结果最大字符数、上下文 token 等）。 | P1 | 低 |
| **解析 agent 工作空间目录** | 不存在。没有按 agent 的工作空间概念。 | `resolveAgentWorkspaceDir(agentId): string` — 从配置中解析 agent 的工作空间目录，进行路径规范化和存在性检查。 | P1 | 中 |
| **解析 agent 私有目录** | 不存在。 | `resolveAgentDir(agentId): string` — 解析 agent 的私有数据目录。 | P2 | 中 |
| **解析会话 agent ID** | 不存在。会话有 `model` 字段，但没有哪个 agent 正在处理会话的概念。 | `resolveSessionAgentIds(sessionKey): string[]` — 返回与给定会话关联的 agent ID。会话可以由多个 agent 处理。 | P1 | 中 |
| **解析 agent 执行合约** | 不存在。没有正式的执行合约概念。 | `resolveAgentExecutionContract(agentId): AgentExecutionContract` — 返回 agent 的已解析运行时类型、模式、后端、cwd。 | P0 | 高 |
| **解析 agent 技能过滤器** | 不存在。技能是全局的。 | `resolveAgentSkillsFilter(agentId): string[]` — 返回 agent 的有效技能白名单（agent 级别覆盖默认值，显式设置替换而非合并）。 | P1 | 中 |
| **按工作空间路径解析 agent ID** | 不存在。 | `resolveAgentIdsByWorkspacePath(workspacePath: string): string[]` — 查找工作空间匹配给定路径的所有 agent。用于多租户部署。 | P3 | 中 |
| **解析回退 agent ID** | 不存在。 | `resolveFallbackAgentId(): string` — 当没有配置默认值时返回硬编码的系统回退 agent ID。 | P1 | 低 |
| **解析 agent 主模型（显式）** | 不存在。`AgentProxyFactory` 有 `modelOverride` 和 `providerOverride` 字段，在代理创建期间从 `@Agent` 注解设置。没有运行时查询"agent X 使用什么模型"。 | `resolveAgentExplicitModelPrimary(agentId): string \| undefined` — 返回 agent 显式配置的主模型（不应用默认值）。 | P0 | 中 |
| **解析 agent 主模型（有效）** | 不存在。 | `resolveAgentEffectiveModelPrimary(agentId): string` — 返回应用默认值和运行时覆盖后的有效主模型。这是 agent 实际使用的内容。 | P0 | 中 |
| **设置 agent 有效主模型** | 不存在。没有按 agent 的运行时模型切换。 | `setAgentEffectiveModelPrimary(agentId, model)` — 在运行时覆盖有效主模型（例如用户在会话中途切换模型）。 | P1 | 中 |
| **解析 agent 模型回退覆盖** | 不存在。 | `resolveAgentModelFallbacksOverride(agentId): string[] \| undefined` — 返回 agent 的回退模型列表覆盖。 | P1 | 低 |
| **解析子代理模型配置选择** | 不适用。 | `resolveSubagentModelConfigSelection(parentId, subagentConfig): AgentModelConfig` — 在考虑父配置、子代理配置和默认值的情况下确定子代理会话的模型。 | P0 | 中 |
| **Agent 条目元数据** | `AgentConfig`（6 个核心字段 + extensions 映射）。没有结构化的条目类型。 | `AgentEntry` — 结构化元数据对象，包含 id、name、description、default 标志、workspace、agentDir、运行时配置、绑定信息以及完整的已解析配置引用。 | P1 | 中 |
| **Agent 路由查找** | 不存在。`ModelRouter` 做基于内容的路由，而非 agent 路由。`AgentCoordinator.dispatch()` 分发任务但路由逻辑与 agent 注册分离。 | `AgentRouteBinding.match` — channel + accountId + peer（kind、id）+ guildId + teamId + roles → agentId。在执行前进行基于路由的 agent 选择。 | P1 | 高 |
| **配置缓存** | 不存在。`AgentConfigResolver.resolve()` 每次调用都重新计算（遍历所有源、合并）。 | 解析后的配置在首次计算后缓存。配置重载时缓存失效。 | P2 | 低 |
| **配置热重载** | 不存在。配置源在启动时初始化。 | 配置文件监视器：agent 配置文件的更改触发重新解析和缓存失效。正在运行的 agent 不会被中断，但新的回合使用更新后的配置。 | P3 | 中 |
| **按会话的配置覆盖** | 不存在。 | 会话级别覆盖：`effectiveModel` 可以按会话设置（用户切换模型）。某些配置字段可以被会话元数据覆盖。 | P1 | 中 |

### 5.3 Agent 注册与发现：LyClaw vs OpenClaw

**LyClaw 方式（基于 Spring）：**
```
ApplicationContext.getBeansWithAnnotation(Agent.class)
    │
    └── 对于每个带有 @Agent 的 Bean：
            提取 name、description、version、model、provider、extensions
            → 此扫描在 AnnotationAgentConfigSource 中进行
            → 仅用于配置加载，不用于运行时 agent 查找
```

限制：
- Agent 仅作为 Spring Bean 发现。不支持纯粹在配置文件中定义（没有对应 Java 类）的 agent。
- 没有带元数据的集中式 agent 注册表。每个消费者（配置解析器、代理工厂、协调器）都自己扫描。
- 没有 agent 到工作空间的映射。工作空间不是概念。
- 没有路由到 agent 的绑定。模型路由（基于内容）和 agent 路由（基于身份）没有关联。

**OpenClaw 方式（专用作用域函数）：**
```
listAgentEntries()
    → 返回 AgentEntry[] { id, name, description, default, workspace, agentDir, runtime, bindings }

listAgentIds()
    → 返回 string[]（仅 ID，用于白名单和下拉菜单）

resolveDefaultAgentId()
    → 找到 default: true 的 agent

resolveAgentConfig(agentId)
    → 深度合并：AgentDefaultsConfig → AgentConfig → 缓存结果

resolveAgentExecutionContract(agentId)
    → 返回 { type: "embedded" | "acp", mode, backend, cwd }

resolveSessionAgentIds(sessionKey)
    → 哪些 agent 正在处理此会话？

resolveAgentIdsByWorkspacePath(path)
    → 多租户：哪些 agent 属于此工作空间？
```

### 5.4 关键缺失的解析函数

以下作用域解析函数在架构上至关重要，必须在其他 agent 功能（子代理、多运行时）能够工作之前实现：

1. **`resolveAgentEffectiveModelPrimary(agentId)`** — 子代理生成需要知道子代理使用哪个模型。运行时调度需要知道远程 agent 应加载哪个模型。
2. **`resolveAgentExecutionContract(agentId)`** — `AgentInvocationHandler` 必须知道是本地执行（ReActEngine）还是远程执行（AcpClient）。
3. **`resolveAgentConfig(agentId)`** — 所有其他解析函数都依赖于拥有完全合并的配置。
4. **`resolveSubagentModelConfigSelection(parentId, config)`** — 是 `sessions_spawn` 工具实现所必需的。
5. **`listAgentIds()`** — 是子代理白名单验证所必需的（"subagentId 是否在 allowAgents 中？"）。

---

## 总结：优先级矩阵

### P0（阻塞 — 必须在生产环境对齐前实现）

| # | 差距 | 类别 | 复杂度 |
|---|---|---|---|
| P0-1 | **AgentDefaultsConfig** — 没有默认值层；每个字段必须按 agent 设置或是硬编码 | 配置层次 | 极高 |
| P0-2 | **子代理委托** — agent 在执行期间无法生成子 agent；没有委托模式、白名单、并发限制、深度限制、超时 | 子代理委托 | 极高 |
| P0-3 | **ACP/远程运行时** — 没有远程 agent 执行模式；所有 agent 是嵌入式的进程内运行 | 运行时模式 | 极高 |
| P0-4 | **AgentConfig 完整性** — OpenClaw 约 90 个字段 vs LyClaw 约 15 个字段；缺少运行时、子代理、沙箱、工具、上下文限制配置 | Agent 配置 | 极高 |
| P0-5 | **配置深度合并** — 扁平键值覆盖 vs 递归深度合并，替换/合并/附加语义 | 配置层次 | 高 |
| P0-6 | **配置解析（默认值 → agent → 有效）** — 没有三层解析链 | 配置层次 | 高 |
| P0-7 | **从 agent 配置解析模型** — 没有 `resolveAgentEffectiveModelPrimary()`，模型在运行时由路由器选择，而非来自 agent 配置 | 作用域解析 | 高 |
| P0-8 | **Agent 执行合约解析** — 没有 `resolveAgentExecutionContract()` 来确定运行时类型/模式/后端 | 作用域解析 | 高 |
| P0-9 | **Agent 级别的工具策略** — 没有按 agent 的工具 allow/deny/profile；工具是全局的 | Agent 配置 | 高 |
| P0-10 | **子代理模型/结果集成** — 没有子代理结果收集到父代理对话上下文中 | 子代理委托 | 高 |

### P1（关键 — 重大功能差距）

| # | 差距 | 类别 | 复杂度 |
|---|---|---|---|
| P1-1 | 思考预算控制（8 个级别） | Agent 配置 | 中 |
| P1-2 | 推理可见性控制（off/on/stream） | Agent 配置 | 中 |
| P1-3 | Agent 身份（`id` 与 `name` 分离） | Agent 配置 | 低 |
| P1-4 | 默认 agent 标记（`default: boolean`） | Agent 配置 | 低 |
| P1-5 | Agent 级别的系统提示覆盖 | Agent 配置 | 中 |
| P1-6 | 按 agent 的工作空间目录 | Agent 配置 | 中 |
| P1-7 | 按 agent 的技能白名单（替换语义） | Agent 配置 | 中 |
| P1-8 | 作为 agent 配置的模型 primary + fallbacks | Agent 配置 | 中 |
| P1-9 | 按 agent 的上下文限制（5 个字段） | Agent 配置 | 中 |
| P1-10 | 上下文修剪配置（mode、ttl、ratios、工具修剪） | 配置层次 | 高 |
| P1-11 | 压缩配置（27 个字段） | 配置层次 | 极高 |
| P1-12 | 运行时策略配置 | 运行时模式 | 中 |
| P1-13 | 运行时绑定（路由 + ACP） | 运行时模式 | 高 |
| P1-14 | 子代理归档 | 子代理委托 | 中 |
| P1-15 | 子代理模型选择 | 子代理委托 | 低 |
| P1-16 | 列出 agent 条目/ID 及元数据 | 作用域解析 | 中 |
| P1-17 | 解析默认 agent ID | 作用域解析 | 低 |
| P1-18 | 解析 agent 上下文限制 | 作用域解析 | 低 |
| P1-19 | 解析 agent 工作空间目录 | 作用域解析 | 中 |
| P1-20 | 解析会话 agent ID | 作用域解析 | 中 |
| P1-21 | 解析 agent 技能过滤器 | 作用域解析 | 中 |
| P1-22 | 设置有效主模型（运行时覆盖） | 作用域解析 | 中 |
| P1-23 | Agent 路由绑定查找 | 作用域解析 | 高 |
| P1-24 | 按会话的配置覆盖 | 作用域解析 | 中 |
| P1-25 | 沙箱配置（10 多个字段带 docker 子配置） | Agent 配置 | 高 |

### P2（重要 — 有价值但不阻塞）

| # | 差距 | 类别 | 复杂度 |
|---|---|---|---|
| P2-1 | 多模型支持（图像、视频、音乐、PDF 模型） | Agent 配置 | 高 |
| P2-2 | 按模型元数据目录（ModelDefinitionConfig） | Agent 配置 | 高 |
| P2-3 | Agent 私有目录 | Agent 配置 | 中 |
| P2-4 | 上下文注入模式 | Agent 配置 | 低 |
| P2-5 | 引导文件限制 | Agent 配置 | 低 |
| P2-6 | 详细模式（3 个级别） | Agent 配置 | 低 |
| P2-7 | 快速模式 | Agent 配置 | 低 |
| P2-8 | 提权模式 + 提权工具 | Agent 配置 | 中 |
| P2-9 | 工具进度详情 | Agent 配置 | 低 |
| P2-10 | 心跳配置（10 个字段） | Agent 配置 | 中 |
| P2-11 | 群聊设置 | Agent 配置 | 中 |
| P2-12 | 运行重试 | Agent 配置 | 低 |
| P2-13 | 嵌入式 PI | Agent 配置 | 高 |
| P2-14 | 参数（结构化类 JSON） | Agent 配置 | 中 |
| P2-15 | 按提供商的工具覆盖 | Agent 配置 | 中 |
| P2-16 | 代码模式（QuickJS WASI 沙箱） | Agent 配置 | 高 |
| P2-17 | 执行/文件系统工具配置 | Agent 配置 | 中 |
| P2-18 | 循环检测配置 | Agent 配置 | 中 |
| P2-19 | 子代理思考级别 | 子代理委托 | 低 |
| P2-20 | 子代理通告超时 | 子代理委托 | 低 |
| P2-21 | 子代理要求 Agent ID | 子代理委托 | 低 |
| P2-22 | 模型目录（9 种 API 类型、模态、成本） | 配置层次 | 高 |
| P2-23 | 从模型目录发现提供商 | 配置层次 | 中 |
| P2-24 | 配置缓存 | 作用域解析 | 低 |
| P2-25 | 解析 agent 私有目录 | 作用域解析 | 中 |
| P2-26 | 启动上下文配置 | 配置层次 | 中 |
| P2-27 | 沙箱工具配置 | Agent 配置 | 中 |

### P3（增强 — 锦上添花）

| # | 差距 | 类别 | 复杂度 |
|---|---|---|---|
| P3-1 | Agent 版本字段（OpenClaw 没有；LyClaw 有，所以 N/A） | Agent 配置 | N/A |
| P3-2 | 阻塞流式开关 | Agent 配置 | 低 |
| P3-3 | 人类延迟模拟 | Agent 配置 | 低 |
| P3-4 | TTS 配置 | Agent 配置 | 中 |
| P3-5 | 身份配置 | Agent 配置 | 低 |
| P3-6 | 按发送者的工具 | Agent 配置 | 中 |
| P3-7 | 消息工具配置 | Agent 配置 | 低 |
| P3-8 | 按工作空间路径解析 agent ID | 作用域解析 | 中 |
| P3-9 | 配置热重载 | 作用域解析 | 中 |

---

## 应继承的关键架构决策

### LyClaw 已经做得很好的地方（应保留的优势）

1. **JDK 动态代理模式** — `AgentProxyFactory` + `AgentInvocationHandler` 很优雅。基于代理的调用允许 agent 接口是带有 `@Agent` 注解的普通 Java 接口。这比 OpenClaw 的 JavaScript/TypeScript 基于对象的方式更简单且类型安全。

2. **流水线阶段架构** — 6 阶段流水线（ContextBuild → SecurityCheck → PlanExecution → Respond → Reflection → Metrics），核心 PlanExecution+Respond+Reflection 阶段带重试块。这比 OpenClaw 的单体式 agent 运行器更有结构化。

3. **AgentHook SPI** — 5 个钩子点（beforeRequest、beforeModel、afterModel、wrapToolCall、wrapToolExecutor、afterResult），带有序执行。这比 OpenClaw 的内联事件处理器更具扩展性。

4. **3 状态流式** — DefaultReActEngine 用于流式检测的缓冲/中继/工具 3 状态机非常精妙。它在流中检测工具调用并无缝切换模式。

5. **配置源 SPI** — 基于优先级解析的 `AgentConfigSource` 接口设计良好。现有的 `AgentConfigResolver` 可以扩展以支持深度合并和默认值层。

6. **SSE 事件类型** — 丰富的 SSE 事件分类（message、tool_call、tool_approval、status）支持细粒度的前端渲染。

### 必须根本性改变的地方

1. **从以注解为中心到以配置为中心** — `@Agent` 注解目前驱动一切。必须演变为以 `AgentConfig`（带默认值层）为主要配置机制，而注解只是众多配置源之一。

2. **从全局工具到按 Agent 工具** — `ToolRegistry` 必须支持作用域工具可见性。每个 agent 需要自己的工具配置文件、allow/deny 列表和按提供商的覆盖。

3. **从仅进程内到多运行时** — 基于代理的调用处理器必须支持分发到远程 ACP 运行时。这需要一个重大的架构添加：代理处理器变成一个薄存根，委托到远程 agent 进程。

4. **从扁平配置到层次化配置** — `AgentConfigResolver` 必须从扁平键值优先级合并演变为带字段特定语义（替换、合并、附加）的递归深度合并。

5. **从独立 Agent 到 Agent 树** — ReAct 循环必须获得生成子代理、监控其进度并纳入其结果的能力。这是所需的最大一项架构变更。

6. **从路由器驱动的模型选择到配置驱动的** — 模型选择必须从 `ModelRouter`（基于内容）转移到 agent 配置层次（基于身份），路由仅保留用于初始 agent 选择。

---

## 实施分阶段建议

### 阶段 1：配置基础（P0-1、P0-4、P0-5、P0-6）

构建 `AgentDefaultsConfig`，将 `AgentConfig` 扩展到约 40 个字段，在 `AgentConfigResolver` 中实现深度合并。这是其他一切构建的基础。

### 阶段 2：作用域解析（P0-7、P0-8、P1-16 至 P1-24）

实现所有 `resolveAgent*()` 函数。这些是只读查询函数，仅依赖于阶段 1 的配置系统。

### 阶段 3：按 Agent 的工具与模型（P0-9、P1-7、P1-8、P1-9）

作用域工具可见性、按 agent 的模型 primary+fallbacks、按 agent 的上下文限制。

### 阶段 4：子代理系统（P0-2、P0-10、P1-14、P1-15）

ReAct 循环内的 agent 生成、结果收集、生命周期管理。

### 阶段 5：多运行时（P0-3、P1-12、P1-13）

ACP 运行时支持、远程 agent 执行、运行时绑定。

### 阶段 6：高级功能（P1-1、P1-2、P2-*）

思考控制、推理可见性、沙箱配置、多模型、压缩、心跳。

---

> **接下来**：第二部分将涵盖流水线与生命周期、记忆系统、技能系统、安全性以及多 Agent 通信。
