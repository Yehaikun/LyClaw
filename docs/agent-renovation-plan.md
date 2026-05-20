# LyClaw Agent 全面改造计划

> 对标 OpenClaw v1.0 的 Agent 体系，将 LyClaw 的 Agent 打造为同等强大的企业级多智能体框架。
>
> 生成日期：2026-05-20 | 版本：v1.0 | 语言：中文

---

## 目录

### 第一部分：差距分析

| 章节 | 内容 | 行数 |
|------|------|------|
| 1 | [Agent 核心配置与运行时](#1-agent-核心配置与运行时) | Agent 注解、AgentConfig、Runtime 模式、Subagent 代理、Config 层级 |
| 2 | [Hook 系统与 Pipeline](#2-hook-系统与-pipeline) | Hook (5 vs 36)、Pipeline 架构、Compaction、Context Pruning、Context Limits |
| 3 | [模型管理与沙箱](#3-模型管理与沙箱) | Model Catalog、Multi-Model、Model Fallback、Thinking 控制、Sandbox |
| 4 | [Bootstrap、路由与身份](#4-bootstrap路由与身份) | Workspace Bootstrap、Agent Routing、Identity、Group Chat、Heartbeat、Streaming |

### 第二部分：改造计划

| 章节 | 内容 | 行数 |
|------|------|------|
| 5 | [Phase 1: Agent 核心增强](#5-phase-1-agent-核心增强) | AgentConfig 体系、AgentContext 增强、Hook 5→36、AgentRuntime、ProxyFactory |
| 6 | [Phase 2: Subagent + 模型管理](#6-phase-2-subagent--模型管理) | Subagent 代理系统、Model Catalog、Multi-Model、Thinking 控制、Provider Discovery |
| 7 | [Phase 3: Context + Bootstrap + 路由](#7-phase-3-context--bootstrap--路由) | Compaction、Context Pruning、Bootstrap 文件、Agent Routing、Identity/Avatar |
| 8 | [Phase 4: Streaming + Sandbox + Heartbeat](#8-phase-4-streaming--sandbox--heartbeat) | Block Streaming、Human Delay、Docker Sandbox、Heartbeat、Run Retries |

### 第三部分：架构与路线图

| 章节 | 内容 | 行数 |
|------|------|------|
| 9 | [架构蓝图](#9-架构蓝图) | 5 张 ASCII 架构图：整体架构、生命周期流、配置层级、Subagent 树、SSE 事件流 |
| 10 | [实施路线图](#10-实施路线图) | 4 阶段任务清单、测试策略、迁移计划、风险矩阵、成功指标 |

---


---

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

---

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

---

# 第三部分：差距分析 -- 模型管理与沙箱

## LyClaw 与 OpenClaw 详细对比

本文档对比 LyClaw 当前的模型管理和沙箱子系统与 OpenClaw 对应的功能，识别差距、评估严重程度（P0=阻塞, P1=关键, P2=重要, P3=增强）以及实现复杂度估计（低/中/高/极高）。

---

## 当前架构深入剖析：LyClaw 模型与沙箱子系统

### 模型层架构

```
application.yml (lyclaw.chat.*)
        |
        v
ChatProperties (defaultProvider, defaultModel, models映射, fallbackChain)
        |
        v
@ChatModel 注解扫描 --> ChatModelPostProcessor
        |                              |
        |                              v
        |                     ChatModelRegistry.register(provider, model, instance, metadata)
        |
        v
ModelRouter 接口
   |
   +-- FirstAvailableRouter (默认，简单：选取第一个模型)
   |       |
   |       v
   |   RoutingDecision(provider, model, tier=SIMPLE|STANDARD|COMPLEX|CODE, reason)
   |
   +-- [可扩展: RegexKeywordRouter, LlmBasedRouter -- 但未实现]
           |
           v
ChatFacade (DefaultChatFacade)
   |
   +-- route(request, context) -> RoutingDecision
   +-- resolveModel(decision) -> ChatModel
   +-- chat(request)           -> ModelResponse (同步)
   +-- chat().prompt()...      -> ChatClient.Builder (流式)
   +-- countTokens()
   +-- healthCheck()
   |
   v
ChatModel 接口
   |
   +-- OpenAiProtocolChatModel (继承 AbstractChatModel)
   |       |
   |       +-- buildNativeRequest() -> JSON Map
   |       +-- sendNativeRequest()  -> WebClient POST 到 /chat/completions
   |       +-- parseChunk()         -> SSE行 -> ModelResponse
   |
   +-- DeepSeekChatModel (继承 OpenAiProtocolChatModel)
   |
   +-- [装饰器链]
   |     CircuitBreakerChatModel -> RetryChatModel -> FallbackChatModel -> 原始ChatModel
   |
   +-- ModelCapabilities (streaming, toolCalling, toolCallStreaming, thinking, vision,
   |                       promptCaching, maxInputTokens, maxOutputTokens)
   |
   +-- ChatModelMetadata (provider, displayName, description, protocol, capabilities,
                           defaultModel, defaultBaseUrl, version, priority)

关键观察：
1. ChatModel 接口设计良好，关注点分离清晰
   （提供商标识、流式传输、Token计数、验证）。
2. 装饰器模式（CircuitBreaker、Retry、Fallback）是一大优势——
   可组合、可测试、注解驱动。
3. @ChatModel 注解 + ChatModelPostProcessor 自动注册机制简洁明了。
4. 然而，整个流水线中模型名称是非结构化的字符串。
   没有目录、没有别名解析、没有输入类型感知。
5. FirstAvailableRouter 是唯一的具体路由器——它完全忽略请求
   内容，使得 RoutingTier 枚举实际上成了死代码。
6. ChatRequest 有 `thinkingEnabled` 布尔字段，但没有细粒度的思考控制。
```

### 沙箱层架构

```
AgentContext.sandboxLevel (由 SecurityCheckHook 设置，order=10)
        |
        v
SandboxHook (order=20)
   |
   +-- 包装 ToolExecutor
   +-- 委托给 ToolSandbox.execute(tool, args, sandboxLevel)
        |
        v
ToolSandbox 接口
   |
   +-- ToolSandboxImpl (@Component)
         |
         +-- execute(tool, args, level)
         |     |
         |     +-- switch(level):
         |           DIRECT  -> executeDirect()    [当前线程，无隔离]
         |           SANDBOX -> executeSandbox()   [守护线程 + 临时user.dir]
         |           PROCESS -> executeProcess()   [操作系统子进程，仅限"command"工具]
         |
         +-- isHealthy() -> AtomicBoolean
         +-- destroy()   -> 关闭线程池

executeSandbox() 内部实现：
  1. 提交到 sandboxExecutor（2线程守护线程池）
  2. Files.createTempDirectory("lyclaw-sandbox-")
  3. System.setProperty("user.dir", tempDir)  // 弱：仅影响 user.dir 读取
  4. tool.execute(toolCall, null)
  5. 恢复原始 user.dir
  6. Files.walk(tempDir).sorted(reverse).forEach(Files::delete)
  7. 超时：通过 Future.get(timeout) 实现 30秒超时

executeProcess() 内部实现：
  - 仅适用于名为 "command" 的工具
  - 委托给 CommandExecutor.execute(command, timeout=30s, maxOutputLength=10000)
  - 所有其他工具：降级为 executeSandbox()

关键观察：
1. 三级隔离概念（DIRECT/SANDBOX/PROCESS）在概念上是好的。
2. 基于 Hook 的架构（SandboxHook 作为 AgentHook）清晰且可扩展。
3. 然而，SANDBOX 级别提供的隔离非常弱：
   - 同一 JVM 进程、同一操作系统用户、同一网络命名空间
   - 仅切换 user.dir 系统属性——轻而易举即可绕过
   - 无内存/CPU 限制
   - 无文件系统命名空间隔离（chroot、挂载命名空间）
4. PROCESS 级别硬编码为仅适用于 "command" 工具。
5. 不存在基于容器的隔离（Docker/podman）。
6. 没有受控的宿主机<->沙箱文件系统桥接。

---

## 1. 模型配置与目录

| 方面 | LyClaw 当前状态 | OpenClaw 对应功能 | 差距摘要 | 严重程度与复杂度 |
|--------|---------------------|---------------------|-------------|----------------------|
| **配置入口** | `ChatProperties`（YAML `lyclaw.chat.*`）：`defaultProvider`、`defaultModel`、`routingEnabled`、每个提供商的 `ModelProperties`（baseUrl、apiKey、model、retry、fallback、options）、`fallbackChain`、`CircuitBreakerProperties` | `AgentModelConfig`：`primary`（provider+model 字符串）、`fallbacks`（字符串数组）；`AgentToolModelConfig`：为 image/imageGeneration/videoGeneration/musicGeneration/pdf 模型使用相同结构；`AgentModelEntryConfig`：contextWindow、capabilities、aliases；`mediaGenerationAutoProviderFallback`、`pdfMaxBytesMb`、`pdfMaxPages` | LyClaw 使用扁平的 YAML 配置，没有结构化的模型元数据。OpenClaw 具有类型化的配置对象，带有每个模型的 contextWindow、aliases，并为不同的媒体生成模型提供单独的配置。LyClaw 的 `ChatProperties` 没有等效的 `AgentModelEntryConfig` 元数据，也没有媒体特定的模型配置。 | **P1 / 中** |
| **模型目录** | 不存在模型目录。模型通过 `ChatModelPostProcessor` 扫描 `@ChatModel` 注解临时注册到 `ChatModelRegistry` 中。模型名称是普通字符串，除了 `ChatModelMetadata` 记录携带的元数据之外没有结构化元数据（provider、displayName、description、protocol、capabilities、defaultModel、defaultBaseUrl、version、priority）。 | `ModelCatalogEntry`：id、name、provider、alias、contextWindow、contextTokens、reasoning、输入类型（text/image/audio/video/document）、兼容性配置。两个索引：`byAlias` Map + `byKey` Map。`buildConfiguredModelCatalog()`、`canonicalizeCaseOnlyCatalogModelRef()`。`ModelRefStatus`：key、inCatalog、allowAny、allowed。 | LyClaw 完全缺少任何模型目录数据结构。模型是不透明的字符串。没有别名解析、没有输入类型感知、没有兼容性配置。 | **P0 / 高** |
| **模型元数据** | `ChatModelMetadata` 记录：provider、displayName、description、protocol、capabilities、defaultModel、defaultBaseUrl、version、priority。`ModelCapabilities`：streaming、toolCalling、toolCallStreaming、thinking、vision、promptCaching、maxInputTokens、maxOutputTokens。 | `AgentModelEntryConfig`：contextWindow、capabilities、aliases。能力包括输入类型（text/image/audio/video/document）和兼容性标志。 | LyClaw 的元数据基本但结构化。缺失：contextWindow（仅 maxInputTokens/maxOutputTokens）、aliases、显式的输入类型支持（image/audio/video/document，仅有布尔值 `vision`）。OpenClaw 的能力更丰富且具有输入类型感知。 | **P1 / 中** |
| **目录索引** | 无。`ChatModelRegistry` 提供按 `(provider, modelName)` 或按 `RoutingDecision` 的扁平查找。 | `ModelAliasIndex`：`byAlias` Map + `byKey` Map，实现按别名或规范键的 O(1) 查找。 | LyClaw 无法解析模型别名（例如 "gpt-4" -> "gpt-4-0613"）。每个模型引用必须使用确切的规范名称。 | **P2 / 中** |
| **允许列表/模型限制** | 没有允许列表概念。任何已注册的模型都可调用。 | `buildConfiguredAllowlistKeys`、`buildAllowedModelSet`、`getModelRefStatus`、`resolveAllowedModelRef`。可以通过允许列表限制模型；`ModelRefStatus` 跟踪模型引用是否在配置的允许集合内。 | LyClaw 没有模型允许列表或访问控制。这对多租户或受限部署而言是一个安全缺口。 | **P2 / 低** |
| **模型引用解析** | 简单的 `ChatModelRegistry.resolve(provider, modelName)` 或 `resolve(RoutingDecision)`。 | 约25个解析函数：`resolveBareModelDefaultProvider`、`resolveModelRefFromString`、`resolveConfiguredModelRef`、`inferUniqueProviderFromConfiguredModels`、`inferUniqueProviderFromCatalog`、`resolvePersistedOverrideModelRef`、`resolvePersistedModelRef`、`resolveDefaultModelForAgent`、`resolveSubagentConfiguredModelSelection`、`resolveSubagentSpawnModelSelection`、`resolveReasoningDefault`、`isCliProvider`。 | LyClaw 的解析是简单的注册表查找。OpenClaw 拥有多层解析流水线：裸模型 -> 默认提供商标识推断 -> 目录查找 -> 持久化覆盖 -> 子代理特定选择。LyClaw 完全没有这些功能。 | **P0 / 极高** |

---

## 2. 多模型支持（图像/视频/音乐/PDF）

| 方面 | LyClaw 当前状态 | OpenClaw 对应功能 | 差距摘要 | 严重程度与复杂度 |
|--------|---------------------|---------------------|-------------|----------------------|
| **文本聊天模型** | 通过 `ChatModel` 接口、`OpenAiProtocolChatModel`、`DeepSeekChatModel` 完全支持。 | `AgentModelConfig.primary` 是文本模型。 | 文本聊天功能对等。 | **不适用** |
| **图像生成模型** | 不支持。没有 `ImageGenerationChatModel` 或等效接口。 | `AgentToolModelConfig.imageGeneration`：单独的 provider+model+fallbacks 配置。为 DALL-E/Stable Diffusion 等提供专用流水线。 | 完全缺失。LyClaw 代理无法将图像生成作为工具使用。 | **P1 / 高** |
| **视频生成模型** | 不支持。 | `AgentToolModelConfig.videoGeneration`：单独的 provider+model+fallbacks 配置。 | 完全缺失。 | **P2 / 高** |
| **音乐生成模型** | 不支持。 | `AgentToolModelConfig.musicGeneration`：单独的 provider+model+fallbacks 配置。 | 完全缺失。 | **P3 / 高** |
| **图像理解（视觉）** | `ModelCapabilities.vision` 布尔标志存在，但 `ChatRequest` 或 `Message` 中没有实际的图像输入流水线。能力已声明但从未被消费。 | 完整的图像输入支持：输入类型包括 "image"，图像可以作为 `MessageAttachment` 附带到消息中，包含 MIME 类型和数据。 | LyClaw 声明了视觉能力，但没有机制在聊天消息中包含图像。`ChatRequest` 和 `Message` 模型类没有 attachment/image 字段。 | **P1 / 中** |
| **PDF 处理模型** | 不支持。 | `AgentToolModelConfig.pdf`：单独的 provider+model+fallbacks。`pdfMaxBytesMb`（默认10）、`pdfMaxPages`（默认20）。 | 完全缺失。没有 PDF 摄取或处理流水线。 | **P2 / 中** |
| **媒体自动回退** | 不支持。 | `mediaGenerationAutoProviderFallback`（默认 true）：当特定媒体模型未配置时，自动回退到主文本模型的提供商进行媒体生成。 | 没有等效的回退机制。每个媒体模型必须显式配置。 | **P2 / 低** |
| **音频输入** | 不支持。 | 输入类型在模型能力中包括 "audio"。 | 完全缺失。没有音频转录或理解支持。 | **P3 / 中** |
| **文档输入** | 不支持。 | 输入类型在模型能力中包括 "document"。 | 完全缺失。 | **P3 / 中** |

---

## 3. 模型选择与解析

| 方面 | LyClaw 当前状态 | OpenClaw 对应功能 | 差距摘要 | 严重程度与复杂度 |
|--------|---------------------|---------------------|-------------|----------------------|
| **路由器接口** | `ModelRouter` 接口：`route(ChatRequest, Object) -> RoutingDecision`。 | 内嵌在约25个解析函数中，没有独立的路由器接口——解析是函数式/组合式的。 | 不同的设计理念。LyClaw 使用面向对象的路由器模式；OpenClaw 使用函数式解析流水线。 | **不适用（设计差异）** |
| **路由层级** | `RoutingTier` 枚举：`SIMPLE`、`STANDARD`、`COMPLEX`、`CODE`。目的：将不同复杂度的请求路由到不同的模型。 | 没有直接的 `RoutingTier` 概念。复杂度路由通过 `ThinkLevel` 和 `Reasoning` 控制在每个代理的基础上处理。 | LyClaw 的层级系统在成本优化路由方面概念上更丰富，但只有 `FirstAvailableRouter` 实际使用它。OpenClaw 通过思考级别控制达到类似效果。 | **P2 / 中** |
| **默认路由器** | `FirstAvailableRouter`：遍历注册表，选取第一个非空提供商的第一个模型。不分析请求内容。 | `resolveDefaultModelForAgent`：从代理配置解析，具有目录感知。 | `FirstAvailableRouter` 是一个简单的占位实现。它忽略请求内容、上下文和成本考虑。OpenClaw 的默认解析是目录感知和配置驱动的。 | **P1 / 低** |
| **路由决策** | `RoutingDecision` 记录：provider、model、tier、reason。 | 解析返回特定的模型引用字符串；没有单独的决策记录类型。 | LyClaw 的决策记录是一个好模式，但未被充分利用（只有 FirstAvailableRouter 产生它们）。 | **P3（低优先级）** |
| **子代理模型选择** | 不支持。 | `resolveSubagentConfiguredModelSelection`、`resolveSubagentSpawnModelSelection`。子代理可以继承或覆盖父代理的模型。 | LyClaw 没有子代理概念，因此没有子代理模型选择。 | **P2（依赖子代理功能）/ 中** |
| **持久化模型覆盖** | 不支持。 | `resolvePersistedOverrideModelRef`、`resolvePersistedModelRef`。按对话或按用户的模型覆盖持久化到存储。 | LyClaw 无法按用户/对话持久化模型偏好。 | **P2 / 中** |
| **裸模型解析** | 不支持。 | `resolveBareModelDefaultProvider`：仅给定一个模型名称（例如 "gpt-4o"），从目录中推断提供商。 | LyClaw 总是需要显式的 `provider:model` 成对。无法解析裸模型名称。 | **P2 / 中** |
| **提供商标识推断** | 不支持。 | `inferUniqueProviderFromConfiguredModels`、`inferUniqueProviderFromCatalog`：当仅配置了一个提供商时，自动为裸模型名称推断提供商。 | 完全缺失。 | **P2 / 低** |
| **模型引用状态** | 不支持。 | `getModelRefStatus`、`ModelRefStatus`：检查模型引用是否在目录中、允许等。 | 除了注册表查找之外没有模型验证。 | **P2 / 低** |

---

## 4. 模型回退与自动探测

| 方面 | LyClaw 当前状态 | OpenClaw 对应功能 | 差距摘要 | 严重程度与复杂度 |
|--------|---------------------|---------------------|-------------|----------------------|
| **静态回退链** | `FallbackChatModel` 装饰器：递归尝试 `"provider:model"` 条目链。通过 `@Fallback` 注解驱动。默认在 `ModelException` 或 `TimeoutException` 时触发。 | `AgentModelConfig.fallbacks`：字符串数组。解析函数在模型选择期间处理回退链。 | LyClaw 的回退是运行时装饰器；OpenClaw 的是配置级别。LyClaw 的出错时处理方式更具响应性；OpenClaw 的配置方式更简单但动态性较差。两者都覆盖了核心需求。 | **P2 / 低** |
| **自动回退探测** | 不支持。 | `AutoFallbackPrimaryProbe`：{ provider, model, fallbackProvider, fallbackModel, fallbackAuthProfileId, fallbackAuthProfileIdSource }。`resolveAutoFallbackPrimaryProbe`、`markAutoFallbackPrimaryProbe`、`clearAutoFallbackPrimaryProbeSelection`。探测状态按会话键用 `Map<string, number>` 跟踪。探测之间有最小间隔。 | LyClaw 没有自动探测。当主模型恢复时无法自动检测并切换回来。OpenClaw 定期探测主模型，在健康时切换回来。 | **P1 / 高** |
| **探测状态跟踪** | 不支持（完全没有探测）。 | 按会话键的 `Map<string, number>`。跟踪最后一次探测时间戳。最小间隔约束可防止惊群式探测风暴。 | 完全缺失。没有跨回退转换的有状态健康跟踪。 | **P1（自动探测的一部分）/ 中** |
| **熔断器** | `CircuitBreakerChatModel` 装饰器：CLOSED -> OPEN -> HALF_OPEN 状态机。可配置 failureThreshold（默认5）、halfOpenAfterSeconds（30秒）、halfOpenMaxRequests（3）。基于 AtomicReference 的线程安全。 | OpenClaw 中没有独立的熔断器模式——自动回退探测服务于类似的检测恢复目的。 | LyClaw 的熔断器实现良好，但是按模型实例的。OpenClaw 的探测是跨会话感知的。同一问题的不同方法。 | **P3（现有功能已足够）/ 低** |
| **带退避的重试** | `RetryChatModel` 装饰器：maxAttempts、baseDelayMs、backoff（FIXED/EXPONENTIAL/LINEAR）、jitter。通过 `Mono.delay` 实现响应式非阻塞。 | 在所描述的 OpenClaw 模型层中不存在。 | LyClaw 具有 OpenClaw 模型层可能缺少的重试能力（运行级别的重试单独存在）。 | **不适用（LyClaw 优势）** |
| **全局回退链** | `ChatProperties.fallbackChain`：`"provider:model"` 字符串列表。 | 不直接存在。 | LyClaw 有全局回退链配置；与每个模型的 @Fallback 装饰器之间的关系不明确（可能存在冲突）。 | **P2 / 低** |

---

## 5. 思考/推理/详细程度控制

| 方面 | LyClaw 当前状态 | OpenClaw 对应功能 | 差距摘要 | 严重程度与复杂度 |
|--------|---------------------|---------------------|-------------|----------------------|
| **思考开关** | `ChatRequest.thinkingEnabled`（布尔值）。`ModelCapabilities.thinking`（布尔值）。`DefaultChatRequestBuilder.thinking(boolean)`。简单的开/关。 | `ThinkLevel`："off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "adaptive" | "max"。`Reasoning`："on" | "off" | "stream"。`resolveThinkingDefault`、`resolveThinkingDefaultWithRuntimeCatalog`、`resolveReasoningDefault`。 | LyClaw 的布尔思考开关是原始的。OpenClaw 具有细粒度思考级别（7个级别 + 自适应）、独立的推理模式以及运行时目录感知解析。这对代理质量而言是一个重大的能力差距。 | **P0 / 高** |
| **推理模式** | 不支持。仅思考开/关。 | `Reasoning`："on" | "off" | "stream"。控制推理/思考内容是否显示在输出中或保持隐藏。 | LyClaw 无法控制推理可见性或思考Token的流式行为。 | **P0（思考的一部分）/ 中** |
| **详细程度控制** | 不支持。 | `Verbose`："off" | "on" | "full"。控制向用户暴露多少内部细节。 | LyClaw 在代理/模型级别没有详细程度控制。 | **P1 / 低** |
| **特权模式** | 不支持。 | `Elevated`："off" | "on" | "ask" | "full"。控制是否允许特权/提升操作以及是否需要用户确认。 | 安全相关的差距。没有针对敏感操作的特权模式。 | **P2 / 中** |
| **阻塞流式** | 不支持。 | `BlockStreaming`："off" | "on"。`break`："text_end" | "message_end"。在特定消息边界控制阻塞/流式行为。 | 除了模型级别的流式布尔值之外，没有细粒度的流式控制。 | **P3 / 低** |
| **快速模式** | 不支持。 | `FastMode`：布尔值。通过减少思考或使用更快的模型来优化速度。 | 完全缺失。 | **P3 / 低** |
| **思考Token预算** | 不直接暴露。`OpenAiProtocolChatModel` 可能在请求体中传递 `thinking` 参数，但没有显式的 budget_tokens 控制。 | `ThinkLevel` 被提供商映射到实际的思考Token预算。目录感知解析确保提供商特定的思考配置。 | LyClaw 中没有显式的思考预算控制。用户无法指定"花费最多 N 个Token进行思考"。 | **P2 / 中** |

---

## 6. 提供商发现

| 方面 | LyClaw 当前状态 | OpenClaw 对应功能 | 差距摘要 | 严重程度与复杂度 |
|--------|---------------------|---------------------|-------------|----------------------|
| **提供商自动发现** | 无。提供商必须在 YAML 中显式配置，或通过 Java 类上的 `@ChatModel` 注解注册。 | `buildConfiguredModelCatalog`：从配置的提供商构建目录。提供商条目包括可被发现的元数据。 | LyClaw 没有提供商发现机制。每个提供商都需要手动配置。 | **P2 / 中** |
| **从 API 发现提供商** | 不支持。无法查询提供商的 API 以发现可用模型。 | 模型目录可以从提供商 API 响应（上下文窗口、能力、定价）中充实。 | 完全缺失。用户必须手动指定模型名称和能力。 | **P2 / 高** |
| **提供商协议检测** | `ModelProtocol` 枚举：OPENAI、ANTHROPIC、OLLAMA、GEMINI。在 `@ChatModel` 注解中手动指定。 | 未描述为独立于模型目录。提供商是目录条目的一部分。 | LyClaw 有显式的协议类型，这很好，但它是注解式的而非发现式的。 | **不适用（已足够）** |
| **动态提供商注册** | `ChatModelRegistry.register()` 支持运行时注册。`ChatModelPostProcessor` 在启动时扫描 Bean。 | 未描述。 | LyClaw 的注册表在运行时可扩展。 | **不适用（已足够）** |
| **提供商健康检查** | `ChatFacade.healthCheck()` 遍历所有已注册模型，对每个调用 `validate()`。返回 `Map<String, Boolean>`。 | 未描述为独立功能。 | LyClaw 内置健康检查。 | **不适用（LyClaw 优势）** |
| **提供商 API 密钥轮换** | `OpenAiProtocolChatModel.updateApiKey()` 支持运行时密钥轮换。 | 未描述。 | LyClaw 支持热密钥轮换。 | **不适用（LyClaw 优势）** |

---

## 7. 沙箱实现

| 方面 | LyClaw 当前状态 | OpenClaw 对应功能 | 差距摘要 | 严重程度与复杂度 |
|--------|---------------------|---------------------|-------------|----------------------|
| **沙箱接口** | `ToolSandbox`：`execute(Tool, Map, SandboxLevel)`、`isHealthy()`、`destroy()`。清晰、定义良好的接口。 | `AgentSandboxConfig`：Docker/podman 容器隔离配置。 | LyClaw 的接口设计良好。差距在于实现，而非契约。 | **P2 / 低** |
| **隔离级别** | `SandboxLevel` 枚举：`DIRECT`（当前线程，无隔离）、`SANDBOX`（守护线程 + 临时目录）、`PROCESS`（通过 `CommandExecutor` 创建独立操作系统进程）。 | 基于容器的隔离（Docker/podman）。没有多级枚举；单一的容器化模式。 | LyClaw 的三级隔离比 OpenClaw 的纯容器方式更细粒度。然而，SANDBOX 级别很弱：同一 JVM、同一用户、无网络隔离、无文件系统命名空间隔离。 | **P1 / 高** |
| **基于容器的沙箱** | 不支持。没有 Docker、podman 或任何容器运行时集成。 | 带有 Docker/podman 容器隔离的 `AgentSandboxConfig`。 | **这是最大的沙箱差距。** LyClaw 无法在真正隔离的容器中运行工具。当前的 SANDBOX 级别仅切换 `user.dir`——对于不遵守 `user.dir` 的恶意代码形同虚设。 | **P0 / 极高** |
| **文件系统桥接** | 不支持。`executeSandbox()` 创建临时目录，切换 `user.dir`，运行工具，删除临时目录。没有机制让沙箱化工具以受控方式从宿主机文件系统读取/写入文件。 | `SandboxFsBridge`：容器与宿主机之间的受控文件系统访问。允许沙箱化代码读取特定宿主机文件或将结果写回。 | LyClaw 的沙箱化工具完全无法访问宿主机文件（临时目录是隔离的）。没有宿主机<->沙箱文件交换的桥接。 | **P1 / 高** |
| **沙箱工具策略** | `SandboxHook`（order=20）包装 `ToolExecutor`，委托给 `ToolSandbox`。使用来自 `AgentContext` 的 `SandboxLevel`。基于 Hook 的架构很清晰。 | 带有沙箱级别允许/拒绝的工具策略。按工具配置是否以沙箱方式运行。 | LyClaw 基于 Hook 的方法在架构上是好的。然而，沙箱级别由安全检查决定，而不是由工具级别的策略配置决定。 | **P2 / 中** |
| **沙箱健康与生命周期** | `isHealthy()` AtomicBoolean，`destroy()` 关闭线程池。良好的生命周期管理。 | 容器启动/停止生命周期。 | LyClaw 的生命周期对其范围而言已足够。容器管理增加了复杂性。 | **P3 / 低** |
| **进程隔离（PROCESS 级别）** | `executeCommandInProcess()` 委托给 `CommandExecutor.execute()`：创建操作系统子进程，具有超时和输出长度限制。仅适用于 "command" 工具；其他工具降级为 SANDBOX。 | 容器隔离处理所有进程执行。 | LyClaw 的 PROCESS 隔离仅限于 "command" 工具。其他工具无法受益于进程隔离。 | **P2 / 中** |
| **工作目录隔离** | `executeSandbox()` 使用 `Files.createTempDirectory("lyclaw-sandbox-")` 并切换 `System.setProperty("user.dir")`。执行后清理。 | 容器文件系统本质上是隔离的。 | `user.dir` 切换是一种弱隔离机制。任何使用 `File(".")` 或绝对路径的代码都会完全绕过它。这不是真正的沙箱化。 | **P0 / 高** |
| **网络隔离** | 无。所有沙箱级别共享宿主机网络。 | 容器网络隔离（桥接网络、无网络等）。 | LyClaw 中没有网络隔离。沙箱化工具可以进行任意网络调用。 | **P1 / 高** |
| **资源限制（CPU/内存）** | 任何沙箱级别都没有 CPU 或内存限制。守护线程池只有 2 个线程，但没有内存约束。 | 容器资源限制（cgroups：CPU 份额、内存限制）。 | 没有资源限制。沙箱化工具可能会耗尽 JVM 堆内存。 | **P1 / 中** |
| **每个级别的超时** | 硬编码 `DEFAULT_TIMEOUT_SECONDS = 30`，适用于 SANDBOX 和 PROCESS。DIRECT 没有超时。 | 在沙箱级别未描述。 | 超时不可按工具或按沙箱级别配置。 | **P2 / 低** |

---

## 8. 运行级重试

| 方面 | LyClaw 当前状态 | OpenClaw 对应功能 | 差距摘要 | 严重程度与复杂度 |
|--------|---------------------|---------------------|-------------|----------------------|
| **运行级重试** | 不支持。`RetryChatModel` 在 API 调用级别处理按请求的重试，但没有重试整个代理"运行"（多轮对话循环）的概念。 | 带可配置限制的运行级重试：base=24、perProfile=8、min=32、max=160。迭代槽位预算。 | LyClaw 无法重试失败的代理运行。如果代理循环失败，整个对话都会失败。OpenClaw 在配置文件中预算重试槽位。 | **P1 / 高** |
| **迭代槽位预算** | 不支持。 | 重试槽位按代理循环的每次迭代进行预算。防止失控的重试成本。 | 没有等效功能。 | **P1（运行级重试的一部分）/ 中** |
| **请求级别的重试** | `RetryChatModel`：maxAttempts、baseDelayMs、backoff（FIXED/EXPONENTIAL/LINEAR）、jitter。响应式、非阻塞。可通过 `@RetryPolicy` 注解按模型配置。 | 在单个请求级别未描述。 | LyClaw 的按请求重试是一个优势。然而，它仅覆盖 API 调用失败，不覆盖代理逻辑失败。 | **不适用（LyClaw 在 API 级别的优势）** |

---

## 总结：优先级矩阵

### P0（阻塞——生产环境前必须实现）

| 编号 | 差距 | 组件 | 复杂度 |
|---|-----|-----------|------------|
| P0-1 | 没有模型目录——模型名称是不透明的字符串 | 模型目录 | 高 |
| P0-2 | 没有多层模型解析流水线 | 模型解析 | 极高 |
| P0-3 | 仅有布尔思考开关 vs 7级 ThinkLevel | 思考控制 | 高 |
| P0-4 | 没有基于容器的沙箱（Docker/podman） | 沙箱 | 极高 |
| P0-5 | `user.dir` 切换不是真正的沙箱隔离 | 沙箱 | 高 |

### P1（关键——重大能力差距）

| 编号 | 差距 | 组件 | 复杂度 |
|---|-----|-----------|------------|
| P1-1 | 没有带 contextWindow、aliases 的结构化模型配置 | 模型配置 | 中 |
| P1-2 | 没有图像生成/图像理解流水线 | 多模型 | 高/中 |
| P1-3 | 没有模型输入类型感知（image/audio/video/document） | 模型元数据 | 中 |
| P1-4 | FirstAvailableRouter 是简单的，没有内容感知路由 | 模型选择 | 低 |
| P1-5 | 没有自动回退探测（主模型恢复检测） | 回退 | 高 |
| P1-6 | 没有代理/模型级别的详细程度控制 | 思考控制 | 低 |
| P1-7 | 沙箱中没有网络隔离 | 沙箱 | 高 |
| P1-8 | 沙箱中没有资源限制（CPU/内存） | 沙箱 | 中 |
| P1-9 | 没有沙箱<->宿主机文件交换的文件系统桥接 | 沙箱 | 高 |
| P1-10 | 没有带槽位预算的运行级重试 | 运行级重试 | 高 |

### P2（重要——应该实现）

| 编号 | 差距 | 组件 | 复杂度 |
|---|-----|-----------|------------|
| P2-1 | 没有模型别名解析 | 模型目录 | 中 |
| P2-2 | 没有模型允许列表/访问控制 | 模型配置 | 低 |
| P2-3 | 没有视频/音乐生成模型 | 多模型 | 高 |
| P2-4 | 没有 PDF 处理模型 | 多模型 | 中 |
| P2-5 | 没有媒体自动提供商回退 | 多模型 | 低 |
| P2-6 | 没有子代理模型选择 | 模型解析 | 中 |
| P2-7 | 没有按用户/对话持久化模型覆盖 | 模型解析 | 中 |
| P2-8 | 没有裸模型名称解析（推断提供商） | 模型解析 | 中 |
| P2-9 | 没有提供商自动发现 | 提供商发现 | 中 |
| P2-10 | 没有思考Token预算控制 | 思考控制 | 中 |
| P2-11 | 没有提升/特权模式控制 | 思考控制 | 中 |
| P2-12 | 工具级沙箱策略配置 | 沙箱 | 中 |
| P2-13 | PROCESS 隔离仅限于 "command" 工具 | 沙箱 | 中 |
| P2-14 | 全局回退链与按模型 @Fallback 关系不明确 | 回退 | 低 |
| P2-15 | 沙箱超时不可按工具配置 | 沙箱 | 低 |

### P3（锦上添花）

| 编号 | 差距 | 组件 | 复杂度 |
|---|-----|-----------|------------|
| P3-1 | 没有音乐生成模型支持 | 多模型 | 高 |
| P3-2 | 没有音频输入/文档输入支持 | 多模型 | 中 |
| P3-3 | 没有阻塞流式控制 | 思考控制 | 低 |
| P3-4 | 没有 FastMode | 思考控制 | 低 |
| P3-5 | RoutingTier 枚举未被充分利用（只有 FirstAvailableRouter 使用它） | 模型选择 | 低 |

---

## 架构建议

### 1. 模型目录（P0-1, P0-2）

引入带有 `ModelCatalogEntry` 的 `ModelCatalog` 数据结构（借鉴 OpenClaw 的设计）：

```
ModelCatalogEntry {
    id: string              // 规范键 "provider:model"
    name: string            // 显示名称
    provider: string        // 提供商标识符
    aliases: string[]       // 替代名称
    contextWindow: int      // 总上下文窗口大小
    maxInputTokens: int     // 最大输入
    maxOutputTokens: int    // 最大输出
    thinking: boolean       // 是否支持思考
    inputTypes: Set<InputType>  // TEXT, IMAGE, AUDIO, VIDEO, DOCUMENT
    capabilities: Map<string, any>  // 可扩展
    pricing: PricingTier    // 成本信息，用于路由
}
```

构建带有 `byAlias` 和 `byKey` 映射的 `ModelAliasIndex`。使用它进行所有模型解析，替代当前的扁平注册表查找。

### 2. 思考控制（P0-3）

用 `ThinkLevel` 枚举替代 `ChatRequest.thinkingEnabled: boolean`：

```
enum ThinkLevel {
    OFF, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, ADAPTIVE, MAX
}
```

添加 `Reasoning` 枚举（ON/OFF/STREAM）和 `Verbose` 枚举（OFF/ON/FULL）。
在每个适配器中将它们映射到提供商特定的 API 参数。

### 3. 容器沙箱（P0-4, P0-5）

引入实现 `ToolSandbox` 的 `ContainerSandbox`：

- 使用 Docker SDK（`docker-java` 或 `testcontainers`）创建每次执行的容器
- 支持可配置的 Docker/podman 运行时
- 实现 `SandboxFsBridge` 用于受控的宿主机<->容器文件交换：
  - 将特定宿主机目录挂载为只读卷
  - 将输出目录挂载为读写卷
- 通过容器 cgroups（CPU 份额、内存限制）强制执行资源限制
- 提供网络隔离模式（none、bridge、host）
- 保留现有的 `PROCESS` 级别作为轻量级替代方案

### 4. 自动回退探测（P1-5）

实现基于探测的恢复检测：

- 跟踪 `Map<sessionKey, lastProbeTimestamp>`
- 回退到次要模型后，定期探测主模型
- 探测之间的最小间隔（例如 60秒）以防止惊群效应
- 探测成功后，切换回主模型并清除探测状态
- 探测失败后，增加退避时间并重新安排

### 5. 运行级重试（P1-10）

添加带槽位预算的代理运行级重试：

```
RunRetryConfig {
    baseRetries: int = 24
    perProfileRetries: int = 8
    minRetries: int = 32
    maxRetries: int = 160
}
```

按迭代预算重试槽位。跟踪已消耗与剩余槽位。
预算耗尽时使运行失败。

---

## 实现工作量估计

| 组件 | 工作量 | 风险 |
|-----------|--------|------|
| 模型目录 + 别名索引 | 2-3周 | 低——纯数据结构 |
| 模型解析流水线 | 3-4周 | 中——跨提供商的许多边界情况 |
| 思考/推理/详细程度控制 | 2-3周 | 中——需要适配器更改 |
| 容器沙箱（Docker） | 4-6周 | 高——基础设施，需要安全审计 |
| 沙箱文件系统桥接 | 2-3周 | 中——安全攻击面 |
| 自动回退探测 | 2-3周 | 中——状态管理、竞争条件 |
| 多模型支持（图像/PDF） | 3-4周 | 中——需要新的适配器类型 |
| 运行级重试 | 1-2周 | 低——主要是配置 + 循环控制 |
| 提供商发现 | 2-3周 | 中——因提供商 API 而异 |
| **总计估计** | **21-31周** | |

---

## 组件级设计说明

### 模型目录条目设计（P0-1）

`ModelCatalogEntry` 应是一个数据类（record），具有以下设计约束：

1. **规范键**：`"provider:model"` 格式，例如 `"openai:gpt-4o"`。
   这是 `byKey` 索引中的主查找键。

2. **别名**：模型名称随时间变化（例如 `"gpt-4"` -> `"gpt-4-0613"`）。
   别名允许向后兼容的引用。`byAlias` 索引将每个别名映射到其规范条目。

3. **输入类型**：使用 `Set<InputType>`（枚举：TEXT、IMAGE、AUDIO、VIDEO、DOCUMENT）
   而非布尔标志。这对多模态模型是向前兼容的，
   并支持输入类型感知路由。

4. **上下文窗口**：单个 `contextWindow` 整数（总Token数）加上
   `maxOutputTokens`。有效最大输入为 `contextWindow - maxOutputTokens`。

5. **能力映射**：可扩展的 `Map<String, Object>`，用于提供商特定的
   标志（例如 `"supportsJsonMode": true`、`"supportsParallelToolCalls": true`）。

6. **定价层级**：用于成本感知路由的枚举或值对象：
   ```
   enum PricingTier { FREE, BUDGET, STANDARD, PREMIUM }
   ```

7. **真实来源**：目录条目可以来自三个来源：
   - 静态内置目录（与框架捆绑）
   - YAML 配置覆盖
   - 运行时 API 发现（探测提供商的 /models 端点）

### 思考级别映射（P0-3）

每个 `ThinkLevel` 映射到提供商特定的参数：

| ThinkLevel | OpenAI 映射 | DeepSeek 映射 | Anthropic 映射 |
|------------|---------------|------------------|-------------------|
| OFF | `thinking: null` | `thinking: null` | `thinking: {type: "disabled"}` |
| MINIMAL | `thinking: {type: "enabled", budget_tokens: 512}` | thinking type enabled, budget 256 | `thinking: {type: "enabled", budget_tokens: 512}` |
| LOW | budget_tokens: 1024 | budget_tokens: 512 | budget_tokens: 1024 |
| MEDIUM | budget_tokens: 2048 | budget_tokens: 1024 | budget_tokens: 2048 |
| HIGH | budget_tokens: 4096 | budget_tokens: 2048 | budget_tokens: 4096 |
| XHIGH | budget_tokens: 8192 | budget_tokens: 4096 | budget_tokens: 8192 |
| ADAPTIVE | budget_tokens: auto（基于输入长度） | 相同 | 相同 |
| MAX | budget_tokens: max（提供商上限） | 最大 | 最大 |

`Reasoning` 枚举控制输出行为：
- `OFF`：思考Token被丢弃，仅返回最终答案
- `ON`：思考Token作为 `reasoning_content` 包含在响应中
- `STREAM`：思考Token作为单独的 SSE 事件流式传输

### 容器沙箱生命周期（P0-4）

每次工具执行的容器生命周期：

```
1. 准备（工具执行前）
   - 检查 Docker/podman 守护进程是否可访问
   - 拉取镜像（如果未缓存，带超时和重试）
   - 创建容器配置：
       image: "lyclaw-sandbox:latest"（预构建了常用工具）
       command: ["java", "-jar", "/sandbox/sandbox-runner.jar", "<序列化的工具调用>"]
       mounts:
         - /host/input:/sandbox/input:ro  （只读宿主机文件）
         - /host/output:/sandbox/output:rw （将结果写回）
         - /host/work:/sandbox/work:rw     （临时空间，之后清理）
       network: none|bridge|host（可配置）
       memory: "256m"（可按工具配置）
       cpuShares: 512（可按工具配置）
       timeout: 30秒（可配置）
   - 启动容器

2. 执行
   - 通过容器日志 API 流式传输工具输出
   - 监控超时
   - 完成时：读取 /sandbox/output/result.json

3. 清理（始终在 finally 块中）
   - 停止容器（如果超时则强制杀死）
   - 删除容器
   - 清理 /host/work 目录
   - 更新健康指标

4. 错误处理
   - 容器启动失败 -> 回退到 PROCESS 隔离
   - 容器超时 -> 杀死并返回错误
   - 守护进程不可达 -> 回退到 PROCESS 隔离
   - 镜像拉取失败 -> 使用缓存的镜像或回退
```

### 自动回退探测算法（P1-5）

```
每个会话的状态：
  activeModel: ChatModel           // 当前活跃的模型
  primaryModel: ChatModel          // 配置的主模型（可能与 activeModel 相同）
  fallbackChain: ChatModel[]       // 有序的回退列表
  lastProbeTime: Instant|null      // 上次探测尝试的时间戳
  probeBackoff: Duration           // 当前探测间隔（从 60秒开始）

每次聊天请求时：
  1. 尝试 activeModel.call(request)
  2. 如果成功：
       a. 如果 activeModel != primaryModel 且 now() - lastProbeTime >= probeBackoff：
            // 异步探测主模型（不阻塞用户）
            发后即忘：尝试 primaryModel.validate()
              如果成功：将 activeModel 切换回 primary，重置 probeBackoff
              如果失败：probeBackoff = min(probeBackoff * 2, MAX_BACKOFF=600秒)
            lastProbeTime = now()
       b. 向用户返回响应
  3. 如果失败：
       a. 如果 activeModel == primaryModel：
            // 主模型失败，回退
            尝试 fallbackChain 中的下一个
              如果成功：activeModel = fallbackModel，返回响应
              如果全部失败：报告错误
       b. 如果 activeModel != primaryModel：
            // 已经在回退模式，尝试下一个
            尝试 fallbackChain 中的下一个（跳过当前的）
              如果成功：activeModel = next，返回响应
              如果全部失败：报告错误

此算法确保：
  - 主模型恢复在不阻塞用户请求的情况下被检测到
  - 探测频率有界（指数退避）
  - 回退链在放弃前被穷尽
  - 无探测风暴（每个会话的时间戳）
```

### 运行级重试槽位预算（P1-10）

```
class RunRetryManager {
    baseRetries: int = 24
    perProfileRetries: int = 8
    minRetries: int = 32
    maxRetries: int = 160

    // 每次运行的状态
    totalAllocated: int        // 本次运行的总槽位数
    consumedSlots: int = 0     // 目前已消耗的槽位数
    iterationCount: int = 0    // 当前迭代次数

    fun allocateSlots(runConfig: RunConfig): int {
        val base = baseRetries
        val profileBonus = profiles.size * perProfileRetries
        val allocated = max(minRetries, min(maxRetries, base + profileBonus))
        totalAllocated = allocated
        return allocated
    }

    fun canRetry(): boolean = consumedSlots < totalAllocated

    fun consumeSlot(): void {
        if (!canRetry()) throw RetryBudgetExhaustedException()
        consumedSlots++
    }

    fun remainingSlots(): int = totalAllocated - consumedSlots
}
```

代理循环级别的重试决策：

```
ReAct 循环的每次迭代：
    try:
        response = model.call(request)
        process(response)
    catch (NonRetryableException e):
        throw  // 不重试永久性错误
    catch (RetryableException e):
        if (retryManager.canRetry()):
            retryManager.consumeSlot()
            log.warn("第 {} 次迭代失败，正在重试（剩余 {} 个槽位）",
                     iteration, retryManager.remainingSlots())
            continue  // 重试本次迭代
        else:
            throw RetryBudgetExhaustedException(
                "运行失败，已消耗全部 {} 个重试槽位", totalAllocated)
```

---

## 迁移路径

### 第一阶段：模型目录基础（第1-5周）

1. 定义 `ModelCatalogEntry`、`InputType`、`PricingTier` 数据类
2. 构建包含约50个常用模型的静态内置目录（OpenAI、DeepSeek、Anthropic、Gemini、Groq、Ollama）
3. 实现带有 `byAlias` 和 `byKey` 映射的 `ModelAliasIndex`
4. 实现 `buildConfiguredModelCatalog()` 将静态目录与 YAML 配置合并
5. 添加向后兼容回退：如果模型不在目录中，则视为不透明字符串（当前行为）
6. 将目录作为可选丰富层接入 `ChatModelRegistry`

### 第二阶段：思考与推理控制（第5-8周）

1. 定义 `ThinkLevel`、`Reasoning`、`Verbose` 枚举
2. 向 `ChatRequest` 添加字段（废弃 `thinkingEnabled` 布尔值）
3. 向 `ChatClient.ChatRequestBuilder` 添加字段
4. 实现提供商特定的映射表（OpenAI、DeepSeek、Anthropic）
5. 添加带有目录感知默认值的 `resolveThinkingDefault()`

### 第三阶段：模型解析流水线（第8-12周）

1. 为裸模型名称实现 `resolveBareModelDefaultProvider()`
2. 实现解析 "provider:model" 或 "model" 的 `resolveModelRefFromString()`
3. 为单提供商设置实现 `inferUniqueProviderFromCatalog()`
4. 实现带允许列表检查的 `resolveAllowedModelRef()`
5. 实现 `resolveDefaultModelForAgent()` 覆盖 FirstAvailableRouter
6. 为持久化覆盖添加 `resolvePersistedModelRef()`（如果持久化存在）

### 第四阶段：容器沙箱（第12-18周）

1. 添加 Docker SDK 依赖（docker-java）
2. 实现实现 `ToolSandbox` 的 `ContainerSandbox` 类
3. 创建带有 sandbox-runner 的 `lyclaw-sandbox` Docker 镜像
4. 实现用于受控文件交换的 `SandboxFsBridge`
5. 添加配置：`lyclaw.sandbox.container.*` 属性
6. 实现优雅降级：容器不可用 -> PROCESS -> SANDBOX -> DIRECT
7. 沙箱实现的安全审计
8. 使用 Testcontainers 添加集成测试

### 第五阶段：自动回退与运行级重试（第18-23周）

1. 实现带每个会话状态的 `AutoFallbackProbeManager`
2. 将探测集成到聊天流中（非阻塞发后即忘）
3. 实现带槽位预算的 `RunRetryManager`
4. 将运行级重试集成到 `DefaultReActEngine`
5. 添加指标：探测成功率、重试槽位利用率、回退频率

### 第六阶段：多模型与提供商发现（第23-31周）

1. 设计 `ImageGenerationModel`、`PdfProcessingModel` 接口
2. 为 DALL-E、Stable Diffusion 实现适配器（图像生成）
3. 实现 PDF 摄取流水线（提取文本、分块、嵌入）
4. 实现提供商 API 发现（查询 /models 端点，填充目录）
5. 向 `Message` 添加 `MessageAttachment` 以支持图像/音频/文档输入

---

## 需要解决的关键设计决策

1. **目录 vs 动态**：模型目录应该是纯静态的（基于文件）
   还是在运行时从提供商 API 充实？建议：静态基础 + 运行时
   充实，静态作为回退。

2. **容器运行时**：Docker vs Podman vs 两者都支持？Docker 采用更广泛，但
   需要守护进程。Podman 无守护进程且更安全。建议：通过
   `ContainerRuntime` 抽象同时支持两者，自动检测可用运行时。

3. **思考控制粒度**：`ThinkLevel` 应该是按请求的、按代理的，
   还是两者兼有？建议：按代理默认值，具有按请求覆盖能力。

4. **运行级重试 vs 请求级重试**：运行级重试如何与
   `RetryChatModel` 请求级重试交互？建议：请求级重试
   对运行级透明。请求重试不消耗运行级重试槽位。
   只有代理逻辑失败（工具错误、解析失败、循环失败）才消耗
   运行级重试槽位。

5. **沙箱回退策略**：当容器不可用时，系统应该
   安全关闭（拒绝执行）还是安全开放（使用更弱的隔离）？建议：
   按工具配置。敏感工具安全关闭；安全工具可以降级。

---

## 测试策略

### 单元测试
- `ModelCatalogTest`：别名解析、规范化、输入类型查询
- `ThinkLevelMappingTest`：验证每个 ThinkLevel 映射到正确的提供商参数
- `ModelResolutionPipelineTest`：每个解析函数的边界情况
- `AutoFallbackProbeManagerTest`：状态机转换、退避计时
- `RunRetryManagerTest`：槽位分配、耗尽、预算

### 集成测试
- `ContainerSandboxTest`：实际的 Docker 容器创建、执行、清理
- `SandboxFsBridgeTest`：通过桥接的文件读写、权限强制执行
- `OpenAiProtocolChatModelThinkingTest`：验证思考参数正确发送
- `FallbackChainIntegrationTest`：主模型失败 -> 回退 -> 恢复探测

### 安全测试
- `SandboxEscapeTest`：尝试 chroot 逃逸、网络逃逸、资源耗尽
- `ModelAllowlistTest`：验证受限模型无法被调用
- `ElevatedModeTest`：验证特权操作时的确认流程

---

*LyClaw 改造差距分析系列第三部分。第一部分涵盖代理架构；第二部分涵盖工具系统和记忆；第三部分涵盖模型管理与沙箱。*

---

# LyClaw vs OpenClaw 差距分析：启动引导、路由、身份、群聊、心跳、人类延迟、TTS、区块流式传输

## 执行摘要

本文档提供了 LyClaw（当前状态）与 OpenClaw（目标参考）在八个核心系统领域的全面、逐功能差距分析：工作空间启动引导、智能体路由与绑定、身份、群聊、心跳、人类延迟、TTS 和区块流式传输。每个功能都从 LyClaw 中的当前实现状态、对应的 OpenClaw 能力、差距严重程度（P0/P1/P2/P3）、实现复杂度和推荐迁移方法等方面进行了检查。

---

## 严重程度说明

| 等级 | 含义 | 需要采取的行动 |
|-------|---------|-----------------|
| **P0** | 阻塞 — 阻碍生产部署或多智能体运行 | 上线前必须实现 |
| **P1** | 关键 — 严重限制功能对等性、用户体验 | 应在接下来的 1-2 个里程碑中实现 |
| **P2** | 重要 — 锦上添花，支持高级用例 | 规划到长期路线图中 |
| **P3** | 增强 — 外观优化、小众需求或实验性功能 | 在资源允许时考虑 |

## 复杂度说明

| 等级 | 含义 | 典型工作量 |
|-------|---------|---------------|
| **高** | 需要架构变更、新建子系统、多模块协调 | 3-6 周 |
| **中** | 新建模块或对现有模块进行重大扩展 | 1-3 周 |
| **低** | 配置驱动、添加注解、独立工具类 | 2-5 天 |
| **极低** | 单文件添加、属性配置、简单透传 | < 1 天 |

---

## 1. 工作空间启动引导

### 1.1 概述

工作空间启动引导是一种机制，智能体的个性、行为准则、运行时上下文和身份通过智能体工作空间目录中的结构化文件加载。在 OpenClaw 中，这是智能体配置的基础——即智能体"知道自己是谁"的方式。

### 1.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **机制** | 仅有 `@SystemMessage` 注解 | 在智能体接口方法上使用单个注解 `@SystemMessage("text")`。`AgentInvocationHandler.resolveSystemMessage()` 方法读取此注解，并使用 `@V` 注解的参数替换 `{{varname}}` 模板占位符。 |
| **文件加载** | 无 | 没有基于文件系统的加载。没有 AGENTS.md、SOUL.md、BOOTSTRAP.md 或任何工作空间文件概念。 |
| **上下文注入** | 无 | 没有"always"、"continuation-skip"或"never"注入策略的概念。系统提示是每个方法静态的。 |
| **最大字符数** | 无 | 没有大小限制，没有截断警告。注解中的内容直接原样发送。 |
| **紧凑后注入** | 无 | 没有紧凑化后注入段落的概念。没有从工作空间文件中提取 H2/H3 段落的概念。 |
| **可选文件** | 无 | 没有可选启动引导文件（SOUL.md、USER.md、HEARTBEAT.md、IDENTITY.md）或跳过控制的概念。 |
| **模板变量** | 仅有 `{{varname}}` | 仅支持参数级别的 `@V` 替换。没有上下文感知的模板变量（例如 `{{agentName}}`、`{{currentDate}}`、`{{userName}}`）。 |

关键代码：`AgentInvocationHandler.java` 第 353-382 行（系统消息解析），`SystemMessage.java` 注解定义。

### 1.3 OpenClaw 功能详情

OpenClaw 实现了多层启动引导系统：

```
工作空间根目录/
  AGENTS.md        # 主文件：智能体行为、系统提示、工具使用指南
  SOUL.md          # 可选：个性、语调、更深层的角色定义
  BOOTSTRAP.md     # 核心启动引导配置、钩子、初始化序列
  IDENTITY.md      # 可选：头像、显示名称、智能体如何展现自己
  USER.md          # 可选：用户特定上下文、每用户偏好设置
  HEARTBEAT.md     # 轻量级上下文，仅用于心跳会话
```

**配置键：**
- `bootstrapMaxChars`（默认 20000）：单个启动引导文件的最大字符数
- `bootstrapTotalMaxChars`（默认 150000）：所有启动引导文件的最大总字符数
- `contextInjection`：`"always"` | `"continuation-skip"` | `"never"` — 控制启动引导上下文何时注入到提示中
- `bootstrapPromptTruncationWarning`：`"off"` | `"once"` | `"always"` — 控制启动引导超出限制时的截断警告行为
- `skipBootstrap`：布尔值 — 完全禁用启动引导加载
- `skipOptionalBootstrapFiles`：要跳过的文件名数组（通常为 SOUL.md、USER.md、HEARTBEAT.md、IDENTITY.md）
- `postCompactionSections`：上下文紧凑化后需重新注入的 AGENTS.md 中的 H2/H3 段落名称列表

### 1.4 逐功能差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **主系统提示** | `@SystemMessage` 注解值 | AGENTS.md 文件内容 | 注解缺乏多文件、多段落支持；无文件重载 | P0 | 中 |
| **工作空间文件加载** | 无 | 使用 glob/正则模式遍历文件系统 | 整个子系统缺失 | P0 | 中 |
| **多文件启动引导** | 每个方法只有一个字符串 | AGENTS.md + SOUL.md + BOOTSTRAP.md + IDENTITY.md + USER.md + HEARTBEAT.md | 5 种额外的文件类型，各自具有不同的语义 | P0 | 中 |
| **启动引导最大字符数控制** | 无 | `bootstrapMaxChars`（20000）、`bootstrapTotalMaxChars`（150000） | 无大小管控；存在 token 预算爆炸的风险 | P1 | 低 |
| **截断警告** | 无 | `bootstrapPromptTruncationWarning`：off/once/always | 启动引导被截断时无用户反馈 | P2 | 低 |
| **上下文注入策略** | 始终注入（如果注入了注解） | `contextInjection`：always/continuation-skip/never | 无 continuation-skip；不必要的重复注入浪费 token | P1 | 低 |
| **可选文件控制** | 无 | 每个智能体的 `skipOptionalBootstrapFiles` | 无法精细调整每个智能体加载哪些可选文件 | P2 | 低 |
| **紧凑化后段落** | 无 | `postCompactionSections`：紧凑化后重新注入 H2/H3 | 无紧凑化感知；长时间对话后智能体丢失关键上下文 | P1 | 中 |
| **模板变量引擎** | `{{varname}}` 仅参数级别 | 多来源：环境变量、渠道上下文、用户身份、时间戳 | 无法根据运行时上下文个性化系统提示 | P1 | 低 |
| **文件热重载** | 不适用（基于注解，静态） | 文件系统监视器，变更时重新读取 | 注解变更需要重新编译 + 重启；OpenClaw 可以热重载 | P2 | 中 |
| **完全跳过启动引导** | 不适用（无启动引导） | `skipBootstrap` 布尔值 | 目前已有效跳过；实现启动引导后此功能才相关 | P2 | 低 |

### 1.5 实现路线图

1. **阶段 1（P0）**：创建 `WorkspaceBootstrap` 服务，从可配置目录读取 AGENTS.md。与 `AgentInvocationHandler` 集成，将文件内容注入系统提示。支持简单的模板变量。
2. **阶段 2（P1）**：添加 BOOTSTRAP.md、IDENTITY.md 加载。实现 `contextInjection` 策略以及带有截断警告的最大字符数控制。
3. **阶段 3（P2）**：添加 SOUL.md、USER.md、HEARTBEAT.md。实现用于热重载的文件监视器。添加紧凑化后段落重新注入。

---

## 2. 启动上下文

### 2.1 概述

启动上下文提供了关于当前环境（日期、最近的对话历史、系统状态）的轻量级、临时的简报，在新会话或重置会话开始时注入。它与启动引导的不同之处在于，它是动态和时间敏感的，而不是静态的个性定义。

### 2.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **机制** | 无 | 完全没有启动上下文注入。没有每日记忆加载，没有环境简报。 |
| **会话初始化** | 手动 | `ChatController.createSession()` 仅生成一个 UUID。没有上下文准备。 |
| **应用触发器** | 无 | 没有"new"与"reset"会话事件触发上下文注入的概念。 |

### 2.3 OpenClaw 功能详情

```yaml
agentStartupContext:
  enabled: true                  # 主开关
  applyOn: ["new", "reset"]     # 哪些会话事件触发注入
  dailyMemoryDays: 2            # 回顾最近 N 天的交互记录
  maxFileBytes: 16384           # 任何单个文件的最大字节数
  maxFileChars: 1200            # 任何单个来源的最大字符数
  maxTotalChars: 2800           # 最大注入总字符数
```

### 2.4 差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **启动上下文注入** | 无 | `AgentStartupContextConfig` | 整个子系统缺失；新会话缺乏环境感知能力 | P1 | 中 |
| **启用/禁用控制** | 无 | `enabled: true/false` | 无法开关 | P2 | 低 |
| **应用触发器** | 无 | `applyOn: ["new","reset"]` | 需要会话生命周期感知 | P2 | 低 |
| **每日记忆加载** | 无 | `dailyMemoryDays: 2` | 会话开始没有最近历史摘要 | P1 | 中 |
| **大小控制** | 无 | maxFileBytes、maxFileChars、maxTotalChars | 无预算控制 | P2 | 低 |

---

## 3. 智能体路由与绑定

### 3.1 概述

智能体路由是一种机制，根据消息通过哪个渠道到达、谁发送了消息以及模式匹配规则来确定哪个智能体（或哪个智能体配置）处理传入消息。在多渠道、多智能体部署中，这是核心的分发层。

### 3.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **路由模型** | 无 — 单智能体、单端点 | `ChatController` 注入一个 `ChatAgent` Bean。`POST /api/chat/stream` 直接映射到 `chatAgent.chatStream()`。没有路由逻辑。 |
| **渠道概念** | 无 | 没有渠道抽象。唯一的"渠道"是 HTTP 端点本身。没有 Telegram、Discord、WhatsApp 或任何消息平台渠道的概念。 |
| **智能体绑定** | 无 | 没有 `AgentBinding`、`AgentRouteBinding` 或 `AgentAcpBinding` 类。 |
| **匹配规则** | 无 | 没有匹配模式，没有账户/公会见/团队/角色/对等方过滤。 |
| **ACP（智能体通信协议）** | 无 | 没有外部智能体后端连接概念。 |
| **多智能体分发** | 不支持 | 每个 JVM 只有一个智能体接口。多个智能体需要单独的 Bean 和单独的端点。 |
| **基于角色的路由** | 无 | 没有 Discord 角色或任何基于角色的路由。 |

关键代码：`ChatController.java`（单个 `ChatAgent` Bean，单个端点），`ChatAgent.java`（单个智能体接口）。

### 3.3 OpenClaw 功能详情

```
AgentBinding
  ├── AgentRouteBinding
  │     type: "route"
  │     agentId: string           # 处理此匹配的智能体
  │     comment: string           # 人类可读的描述
  │     match: AgentBindingMatch  # 模式匹配规则
  │     session: session 配置      # 此路由的会话管理
  │
  └── AgentAcpBinding
        type: "acp"
        agentId: string
        comment: string
        match: AgentBindingMatch
        acp:                     # 外部智能体后端覆盖
          url: string
          timeout: duration
          headers: map

AgentBindingMatch
  channel: string (必填)          # 渠道模式："#general"、"@botname"、"*"
  accountId: string              # 特定消息账户
  peer: { chatType, id }        # 特定对等方/聊天标识符
  guildId: string               # Discord 公会
  teamId: string                # MS Teams 团队
  roles: string[]               # Discord 基于角色的路由
```

**关键路由模式：**
- **基于渠道**：匹配渠道名称/ID 模式
- **基于账户**：根据哪个消息账户收到消息进行路由
- **基于对等方**：根据发送者身份路由到特定智能体
- **基于角色**：Discord 角色权限决定哪个智能体处理消息
- **ACP 路由**：转发到外部智能体后端

### 3.4 逐功能差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **智能体分发/路由** | 单智能体，无路由 | 多智能体分发，支持模式匹配 | 无法支持不同渠道上的多个智能体 | P0 | 高 |
| **渠道抽象** | 无 | 具有平台特定适配器的渠道概念（Telegram、Discord、WhatsApp 等） | 无法与消息平台集成 | P0 | 高 |
| **AgentBinding 模型** | 无 | `AgentRouteBinding` + `AgentAcpBinding` 类 | 核心领域模型缺失 | P0 | 中 |
| **绑定匹配规则** | 无 | Channel、accountId、peer、guildId、teamId、roles | 完全没有匹配语义 | P0 | 中 |
| **基于模式的匹配** | 无 | 渠道模式如 "#general"、"@botname"、通配符 | 无灵活的匹配语法 | P0 | 低 |
| **ACP（外部智能体后端）** | 无 | `AgentAcpBinding` 带有 URL、超时、请求头 | 无法代理到外部智能体服务 | P2 | 高 |
| **基于角色的路由** | 无 | Discord 角色 → 智能体选择 | 平台特定的高级功能 | P3 | 中 |
| **多账户路由** | 无 | 匹配规则中的 `accountId` | 仅支持单账户部署 | P1 | 中 |
| **每路由会话** | 无 | 每个绑定的会话配置 | 无每路由会话隔离 | P1 | 低 |
| **动态绑定重载** | 不适用 | 无需重启即可热重载绑定 | 更改智能体分配需要重启 | P2 | 中 |
| **回退/默认路由** | 隐式（只有一个智能体） | 无匹配时的显式默认路由 | 无显式回退语义 | P1 | 低 |

### 3.5 实现路线图

1. **阶段 1（P0）**：创建 `AgentRouter` 接口和 `DefaultAgentRouter`。实现带有 `AgentRouteBinding` 和 `AgentBindingMatch` 的 `AgentBinding` 领域模型。支持带有简单字符串模式的基于渠道的匹配。重构 `ChatController` 使用路由器而不是直接注入智能体。
2. **阶段 2（P1）**：添加基于对等方的匹配、基于账户的路由。实现每路由会话配置。添加显式默认路由回退。
3. **阶段 3（P2）**：添加 `AgentAcpBinding` 用于外部智能体后端代理。动态绑定重载。
4. **阶段 4（P3）**：为 Discord 和其他平台实现基于角色的路由。

---

## 4. 身份

### 4.1 概述

智能体身份定义了智能体如何向用户展示自己：其显示名称、头像、消息中的名称前缀、响应格式和确认反应。身份是一个核心 UX 概念，它使多智能体系统感觉独特和个性化。

### 4.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **身份配置** | 无 | 没有 `IdentityConfig` 类，没有与身份相关的属性。 |
| **显示名称** | 无 | 智能体通过 `@Agent(name = "chat")` 命名，但这仅是内部的，从不显示给用户。 |
| **头像** | 无 | 没有头像概念。没有本地文件、远程 URL 或数据 URI 头像解析。 |
| **名称前缀** | 无 | 消息显示为原始内容，没有"[机器人名称]"前缀格式。 |
| **消息前缀/响应前缀** | 无 | 没有 `resolveMessagePrefix` 或 `resolveResponsePrefix` 逻辑。 |
| **确认反应** | 无 | 没有表情符号反应确认（例如，任务完成时的勾号）。 |
| **有效消息配置** | 无 | 没有每智能体消息格式覆盖。 |

关键发现：`@Agent` 注解有 `name` 和 `description` 字段，但它们仅用于框架内的注册/标识。`AgentConfig` 对象（`AgentConfig.java`）有 name/description/version/model/provider，但没有任何与身份相关的内容。

### 4.3 OpenClaw 功能详情

```
IdentityConfig
  agentId: string
  displayName: string           # "支持机器人"、"代码助手"
  avatar: AgentAvatarResolution # 四种类型之一（见下文）
  namePrefix: string            # 此智能体的所有消息前添加此前缀
  messagePrefix: string         # 发送给此智能体的用户消息前添加此前缀
  responsePrefix: string        # 助手响应前添加此前缀
  ackReaction: string           # 消息确认时发送的表情符号反应

AgentAvatarResolution（联合类型）
  { kind: "none" }              # 无头像
  { kind: "local", filePath }   # 本地文件路径的图像
  { kind: "remote", url }       # 远程图像 URL
  { kind: "data", url }         # 数据 URI（base64 编码）

解析函数：
  resolveAgentIdentity          # 完整身份解析
  resolveIdentityNamePrefix     # 仅名称前缀
  resolveMessagePrefix          # 用户消息前的前缀
  resolveResponsePrefix         # 助手响应前的前缀
  resolveAckReaction            # 确认表情符号
  resolveEffectiveMessagesConfig # 合并后的消息格式配置
```

### 4.4 逐功能差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **身份配置** | 无（`AgentConfig` 仅有 name/model） | `IdentityConfig` 带有 displayName、avatar、前缀、反应 | 无每智能体视觉身份；所有智能体看起来相同 | P1 | 中 |
| **显示名称** | `@Agent(name=...)` — 仅内部使用 | `displayName` — 面向用户，可本地化 | 用户在聊天中看不到智能体名称 | P1 | 低 |
| **头像（无）** | 不适用 | `{kind:"none"}` | 作为默认实现很简单 | P2 | 低 |
| **头像（本地文件）** | 无 | `{kind:"local", filePath}` | 需要文件服务和 MIME 类型检测 | P2 | 低 |
| **头像（远程 URL）** | 无 | `{kind:"remote", url}` | 简单的 URL 透传；有代理/缓存考虑 | P2 | 低 |
| **头像（数据 URI）** | 无 | `{kind:"data", url}` | Base64 解码；可能存在大型内联数据 | P3 | 低 |
| **名称前缀** | 无 | `resolveIdentityNamePrefix` | 多智能体环境中消息缺乏智能体归属 | P1 | 低 |
| **消息前缀** | 无 | `resolveMessagePrefix` | 无法自定义如何为用户消息框定上下文 | P2 | 低 |
| **响应前缀** | 无 | `resolveResponsePrefix` | 无法自定义每智能体的响应格式 | P2 | 低 |
| **确认反应** | 无 | `resolveAckReaction`（表情符号） | 无消息已收到的视觉确认 | P3 | 低 |
| **消息配置合并** | 无 | `resolveEffectiveMessagesConfig` | 无法覆盖每智能体的消息格式 | P3 | 低 |

### 4.5 实现路线图

1. **阶段 1（P1）**：创建带有 displayName、namePrefix 的 `IdentityConfig` 类。集成到 `AgentInvocationHandler` 和 SSE 事件发送中，在流式响应头部包含身份元数据。在前端展示 displayName。
2. **阶段 2（P2）**：添加头像解析（local/remote/none）。添加消息前缀和响应前缀解析。接入消息构建管道。
3. **阶段 3（P3）**：添加确认反应支持。添加数据 URI 头像支持。实现 `resolveEffectiveMessagesConfig` 合并。

---

## 5. 群聊

### 5.1 概述

群聊管理控制智能体在多参与者环境中的行为：是否需要显式提及才能响应、群组成员可以使用哪些工具、谁可以触发哪些操作，以及是否存在命名的访问控制群组。

### 5.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **群组策略** | 无 | 完全没有群聊概念。智能体平等对待所有输入，单用户，没有任何提及或访问控制语义。 |
| **要求提及** | 无 | 智能体始终响应；没有提及门控。 |
| **接收策略** | 无 | 无法控制智能体是否/如何读取未被提及的消息。 |
| **群组工具** | 无 | 所有人使用相同的工具；没有每发送者或每组工具限制。 |
| **访问群组** | 无 | 没有用于跨账户白名单管理的命名访问群组。 |
| **激活模式** | 无 | 没有"mention"与"always"激活区分。 |
| **发送者访问评估** | 无 | 没有 `evaluateSenderGroupAccess` 或 `resolveToolsBySender` 逻辑。 |

### 5.3 OpenClaw 功能详情

```
GroupPolicy
  requireMention: boolean         # 智能体仅在被 @提及 时响应
  ingest: "all" | "mentions_only" # 智能体读取哪些消息
  tools: GroupToolPolicyConfig    # 群组上下文中的工具可用性
  toolsBySender: map<string, GroupToolPolicyConfig>  # 每发送者工具覆盖

GroupToolPolicyConfig
  allowedTools: string[]          # 工具名称白名单
  blockedTools: string[]          # 工具名称黑名单
  allowAllTools: boolean          # 覆盖为允许全部

GroupActivationMode
  "mention"                       # 智能体仅在 @提及 时激活
  "always"                        # 智能体读取所有消息

AccessGroupConfig
  name: string                    # 命名群组标识符
  members: string[]               # 成员标识符（电话号码、聊天 ID）

解析函数：
  resolveChannelGroupPolicy       # 每渠道群组策略解析
  resolveChannelGroupRequireMention # requireMention 解析
  resolveChannelGroupToolsPolicy  # 给定渠道群组的工具策略
  evaluateSenderGroupAccess       # 此发送者可以使用此智能体吗？
  resolveToolsBySender            # 此发送者可以使用哪些工具？
  resolveAccessGroupAllowFromState # 访问群组白名单展开
  expandAllowFromWithAccessGroups  # 跨账户白名单展开
```

### 5.4 逐功能差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **群聊支持** | 无 | 每渠道 `GroupPolicy` 配置 | 没有多参与者环境的概念 | P0 | 高 |
| **要求提及** | 无（始终响应） | `requireMention: boolean` | 智能体在群中回复每条消息；无法用于群组部署 | P0 | 中 |
| **接收策略** | 无 | `ingest: "all" \| "mentions_only"` | 无法控制隐私上下文；智能体看到所有消息 | P1 | 低 |
| **群组工具限制** | 无 | `GroupToolPolicyConfig`（allowedTools、blockedTools、allowAllTools） | 危险工具对所有群组成员可用 | P0 | 中 |
| **每发送者工具覆盖** | 无 | `toolsBySender: map` | 无法为管理员提供更高的工具访问权限，为普通用户提供受限权限 | P2 | 中 |
| **访问群组** | 无 | 带有命名群组和成员列表的 `AccessGroupConfig` | 无法跨渠道定义可复用的白名单 | P2 | 中 |
| **激活模式** | 无 | `GroupActivationMode: "mention" \| "always"` | 无法控制智能体如何加入对话 | P1 | 低 |
| **发送者访问评估** | 无 | `evaluateSenderGroupAccess` | 完全没有发送者授权 | P1 | 中 |
| **白名单展开** | 无 | `expandAllowFromWithAccessGroups` | 无法在渠道配置中引用访问群组 | P2 | 低 |

### 5.5 实现路线图

1. **阶段 1（P0）**：创建带有 `requireMention`、`GroupToolPolicyConfig` 的 `GroupPolicy` 领域模型。在传入消息处理中实现提及检测。根据 `requireMention` 门控智能体响应。实现每群组策略的工具允许/阻止过滤。
2. **阶段 2（P1）**：添加 `ingest` 策略。添加 `GroupActivationMode`。实现发送者访问评估和每发送者工具覆盖。
3. **阶段 3（P2）**：添加 `AccessGroupConfig` 和命名访问群组。实现白名单展开和跨账户支持。

---

## 6. 心跳

### 6.1 概述

心跳是一种按计划自动调用智能体的机制。按可配置的时间间隔，智能体会唤醒，可选地加载轻量级上下文（HEARTBEAT.md），并向指定的目标渠道/用户发送主动消息或状态更新。这使得智能体可以主动行动，而不仅仅是被动响应。

### 6.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **计划调用** | 无 | 没有基于调度器的智能体调用。存在 `CronJob` 模型类，但没有 cron/心跳执行引擎。 |
| **时间间隔控制** | 无 | 没有 `every` 持续时间配置。 |
| **活跃时间** | 无 | 没有时间窗口限制（例如，仅在工作时间心跳）。 |
| **投递目标** | 无 | 没有投递到特定渠道或用户的概念。 |
| **轻量级上下文** | 无 | 没有用于轻量级会话的 HEARTBEAT.md 加载。 |
| **忙碌跳过** | 无 | 没有 `skipWhenBusy` 机制。 |
| **隔离会话** | 无 | 没有 `isolatedSession` 概念（每次心跳使用全新会话）。 |

### 6.3 OpenClaw 功能详情

```
HeartbeatConfig
  every: duration                     # 默认 30m；心跳间隔
  activeHours: {                       # 可选时间窗口
    start: string                     # "09:00"
    end: string                       # "17:00"
    timezone: string                  # IANA 时区
  }
  model: string                       # 心跳调用的模型覆盖
  sessionKey: string                  # 持久化会话键
  deliveryTarget: "last" | "none" | channelId  # 心跳消息投递到何处
  directPolicy: "allow" | "block"     # 私聊消息是否触发心跳
  to: string                          # 用于 WhatsApp 的 E.164，用于 Telegram 的 chatId
  accountId: string                   # 通过哪个账户发送
  prompt: string                      # 心跳的提示覆盖
  includeSystemPromptSection: boolean # 是否包含主系统提示
  ackMaxChars: number                 # 默认 30；确认中的最大字符数
  suppressToolErrorWarnings: boolean  # 抑制心跳中的工具错误
  timeoutSeconds: number              # 每次心跳的超时时间
  lightContext: boolean               # 仅使用 HEARTBEAT.md，跳过其他启动引导
  isolatedSession: boolean            # 每次心跳使用全新会话
  skipWhenBusy: boolean               # 如果智能体正在处理其他消息则跳过
  includeReasoning: boolean           # 在心跳响应中包含推理
```

### 6.4 逐功能差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **计划心跳** | 无 | `every: duration` 类 cron 调度 | 无主动智能体能力；纯被动 | P1 | 中 |
| **时间间隔配置** | 无 | `every: 30m`（默认） | 无调度基础设施 | P1 | 低 |
| **活跃时间窗口** | 无 | `{start, end, timezone}` | 无法将心跳限制在工作时间内 | P2 | 低 |
| **模型覆盖** | 无 | `model: string` | 心跳使用与普通聊天相同的模型 | P3 | 低 |
| **会话键** | 无 | `sessionKey: string` | 无跨心跳的持久会话状态 | P2 | 低 |
| **投递目标** | 无 | `"last" \| "none" \| channelId` | 没有心跳输出到何处的概念 | P1 | 低 |
| **私聊策略** | 无 | `"allow" \| "block"` | 无法控制私聊触发心跳 | P3 | 低 |
| **接收方（to）** | 无 | E.164 / chatId | 无每心跳接收方路由 | P1 | 低 |
| **账户路由** | 无 | `accountId: string` | 无法通过特定消息账户路由心跳 | P2 | 低 |
| **提示覆盖** | 无 | `prompt: string` | 心跳使用标准系统提示 | P2 | 低 |
| **包含系统提示** | 无 | `includeSystemPromptSection: boolean` | 无段落级别控制 | P3 | 低 |
| **确认最大字符数** | 无 | `ackMaxChars: 30` | 无确认裁剪 | P3 | 低 |
| **抑制工具错误** | 无 | `suppressToolErrorWarnings: boolean` | 工具错误始终记录/报告 | P3 | 低 |
| **超时** | 无 | `timeoutSeconds: number` | 使用全局智能体超时 | P2 | 低 |
| **轻量上下文** | 无 | `lightContext: boolean`（仅 HEARTBEAT.md） | 无轻量级启动引导模式 | P2 | 低 |
| **隔离会话** | 无 | `isolatedSession: boolean` | 心跳共享会话历史 | P2 | 低 |
| **忙碌时跳过** | 无 | `skipWhenBusy: boolean` | 可能触发重叠调用 | P2 | 低 |
| **包含推理** | 无 | `includeReasoning: boolean` | 无推理开关 | P3 | 低 |

### 6.5 实现路线图

1. **阶段 1（P1）**：使用 Spring 的 `@Scheduled` 或 `TaskScheduler` 创建 `HeartbeatScheduler` 服务。实现带有 `every`、`to`、`deliveryTarget` 的 `HeartbeatConfig` 领域模型。与 `AgentInvocationHandler` 集成以进行心跳触发的调用。将投递接入渠道适配器。
2. **阶段 2（P2）**：添加 `activeHours`、`lightContext`、`isolatedSession`、`skipWhenBusy`。添加 HEARTBEAT.md 启动引导文件加载。
3. **阶段 3（P3）**：添加模型覆盖、提示覆盖、确认裁剪、抑制工具错误、包含推理。

---

## 7. 人类延迟

### 7.1 概述

人类延迟模拟区块回复之间的自然打字停顿。在流式或多消息响应中，此功能插入可配置的延迟以模拟人类打字速度，使智能体在对话界面中感觉更自然，减少机械感。

### 7.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **延迟机制** | 无 | SSE 事件以 LLM 生成 token 的最快速度发送。区块之间没有人工延迟。 |
| **每智能体配置** | 无 | 没有 `HumanDelayConfig` 类。没有 `resolveHumanDelayConfig` 解析逻辑。 |
| **延迟计算** | 无 | 没有基于字符数或基于区块数的延迟公式。 |

相关代码：在 `DefaultReActEngine` 中，`splitIntoEvents()` 在句子边界处切分文本，并通过 `Flux.fromIterable()` 立即发送。没有使用任何延迟操作符（`delayElements`、`delaySequence`）。

### 7.3 OpenClaw 功能详情

```
HumanDelayConfig
  enabled: boolean           # 主开关
  minDelayMs: number         # 区块之间的最小延迟
  maxDelayMs: number         # 区块之间的最大延迟
  charsPerSecond: number     # 打字速度；延迟 = 字符数 / charsPerSecond
  delayMode: "fixed" | "random" | "typing_speed"

解析：
  resolveHumanDelayConfig    # 每智能体，从默认值深度合并
```

### 7.4 逐功能差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **人类延迟机制** | 无 | 带有 min/max/charsPerSecond 的 `HumanDelayConfig` | 智能体响应感觉即时/机械 | P2 | 低 |
| **启用/禁用开关** | 无 | `enabled: boolean` | 无法选择性启用 | P2 | 低 |
| **最小/最大延迟边界** | 无 | `minDelayMs`、`maxDelayMs` | 无延迟范围控制 | P2 | 低 |
| **打字速度模拟** | 无 | `charsPerSecond` 公式 | 无法模拟人类打字速度 | P2 | 低 |
| **延迟模式** | 无 | fixed / random / typing_speed | 只有一种行为可能；无灵活性 | P3 | 低 |
| **每智能体解析** | 无 | `resolveHumanDelayConfig` | 所有智能体行为相同 | P2 | 低 |

### 7.5 实现路线图

1. **阶段 1（P2）**：创建 `HumanDelayConfig` 领域模型。在 SSE 流管道中实现延迟操作符插入（使用 `Flux.delayElements` 或自定义 `concatMap` 配合 `Mono.delay`）。通过每智能体配置接入。
2. **阶段 2（P3）**：添加基于字符数延迟计算的 typing_speed 模式。添加 random 延迟模式。

---

## 8. TTS（文本转语音）

### 8.1 概述

TTS 配置控制智能体文本响应如何转换为语音，包括每智能体语音选择、深度合并的消息 TTS 配置以及渠道特定的语音投递能力。

### 8.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **TTS 配置** | 无 | 没有 `TtsConfig` 类。没有语音选择。完全没有 TTS 集成。 |
| **消息 TTS 合并** | 无 | 没有每消息 TTS 覆盖的深度合并。 |
| **渠道 TTS 能力** | 无 | 没有渠道语音投递能力声明。 |

### 8.3 OpenClaw 功能详情

```
TtsConfig
  enabled: boolean
  voice: string               # 语音标识符（提供商特定）
  speed: number               # 语速倍率
  pitch: number               # 音高调整
  provider: string            # TTS 提供商（elevenlabs、azure 等）

深度合并：智能体 TtsConfig 深度合并到全局 messages.tts 配置之上。
渠道能力：每个渠道声明其支持的 TTS 语音和提供商。
```

### 8.4 逐功能差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **TTS 配置** | 无 | 每智能体 `TtsConfig` | 语音渠道没有语音体验 | P3 | 中 |
| **语音选择** | 无 | `voice: string` | 无每智能体语音身份 | P3 | 低 |
| **语速/音高控制** | 无 | `speed`、`pitch` | 无语音参数微调 | P3 | 低 |
| **提供商选择** | 无 | `provider: string` | 无法为每智能体选择 TTS 提供商 | P3 | 低 |
| **与 messages.tts 深度合并** | 无 | 深度合并语义 | 无法为每消息覆盖 TTS | P3 | 中 |
| **渠道 TTS 能力** | 无 | 每渠道语音投递声明 | 无法声明哪些渠道支持 TTS | P3 | 低 |

### 8.5 实现路线图

TTS 完全属于 P3。实现取决于与外部 TTS 提供商（ElevenLabs、Azure Cognitive Services 等）的集成。只有在所有 P0-P2 功能完成后才应考虑。

1. **阶段 1（P3）**：创建 `TtsConfig` 领域模型。与一个 TTS 提供商集成。实现基本语音选择。
2. **阶段 2（P3）**：添加快慢/音高控制。添加深度合并语义。添加渠道 TTS 能力声明。

---

## 9. 区块流式传输

### 9.1 概述

区块流式传输控制智能体响应文本如何投递给客户端。这包括是按字符还是按区块流式传输、区块如何分隔、快速回复的合并、重复抑制以及投递模式（实时流式传输 vs 仅最终结果）。

### 9.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **流式传输** | 基本 SSE | `DefaultReActEngine` 使用 Spring WebFlux 生成 `Flux<ServerSentEvent<String>>`。LLM token 通过 OpenAI 兼容的 SSE 端点流式传输。 |
| **区块切割** | 仅按句子边界 | `splitIntoEvents()` 在 `\n`、`。`、`！`、`？`、`；` 边界处切分文本。仅用于第一轮非流式工具执行结果，不用于第二轮真正的流式传输。 |
| **合并** | 无 | 没有 `blockStreamingCoalesceConfig` — 快速连续回复不会合并。 |
| **投递模式** | 始终实时 | 没有 `"final_only"` 模式。所有内容一旦可用就立即流式传输。 |
| **重复抑制** | 无 | 不删除重复的内容区块。 |
| **隐藏边界分隔符** | 无 | 没有区块之间不可见分隔符的概念。 |
| **流式传输断点** | 无 | 没有 `"text_end"` 与 `"message_end"` 断点语义。 |
| **最大区块字符数** | 无 | 没有每个区块的可配置最大大小。 |

关键代码：`DefaultReActEngine.java` 第 511-529 行（`splitIntoEvents` 方法）。`OpenAiProtocolChatModel.java` 第 264-281 行（通过 WebClient 进行 SSE 流式传输）。`RespondStage.java` 第 161-186 行（`simpleChatStream` 带有真正的逐 token 流式传输）。

### 9.3 OpenClaw 功能详情

```
blockStreamingDefault: "off" | "on"     # 区块流式传输的全局默认值

blockStreamingBreak: "text_end" | "message_end"  # 什么触发区块断点

BlockStreamingChunkConfig                # 软区块切割
  enabled: boolean
  minChars: number                       # 切割前的最小字符数
  maxChars: number                       # 每个区块的最大字符数
  breakOn: string[]                      # 切割依据的字符/模式

BlockStreamingCoalesceConfig             # 区块回复合并
  enabled: boolean
  coalesceIdleMs: number                 # 刷新合并区块前的空闲时间
  maxChunkChars: number                  # 合并输出中的最大字符数
  repeatSuppression: boolean             # 抑制重复区块

deliveryMode: "live" | "final_only"      # 实时流式传输或结束时批量投递
hiddenBoundarySeparator: string          # 区块之间不可见的分隔符（不渲染）
```

### 9.4 逐功能差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **区块流式传输开关** | 始终开启（对非流式结果进行句子切分） | `blockStreamingDefault: "off" \| "on"` | 无显式开关；混合行为（第二轮真正的流式传输，第一轮句子切分） | P1 | 低 |
| **区块断点语义** | 无 | `"text_end" \| "message_end"` | 除了句子字符外没有区块边界概念 | P2 | 低 |
| **软区块切割** | `splitIntoEvents` 固定字符切分 | 带有 min/max 字符数、可配置断点集的 `BlockStreamingChunkConfig` | 硬编码断点字符；无 min/max 控制 | P1 | 低 |
| **回复合并** | 无 | 带有空闲超时的 `BlockStreamingCoalesceConfig` | 快速连续回复作为单独区块到达 | P2 | 中 |
| **重复抑制** | 无 | `repeatSuppression: boolean` | LLM 循环或冗余输出未被过滤 | P2 | 低 |
| **投递模式** | 始终实时 | `"live" \| "final_only"` | 无法批量投递；始终流式传输 | P2 | 低 |
| **隐藏边界分隔符** | 无 | `hiddenBoundarySeparator: string` | 无法注入不可见分隔符供客户端解析 | P3 | 低 |
| **最大区块字符数** | 无（每个区块无限制） | `maxChunkChars: number` | 无限制的区块大小可能导致客户端渲染问题 | P2 | 低 |
| **每渠道/智能体配置** | 仅全局行为 | 每智能体/渠道深度合并覆盖 | 所有智能体流式传输行为相同 | P2 | 低 |

### 9.5 实现路线图

1. **阶段 1（P1）**：创建 `BlockStreamingConfig` 领域模型。重构 `splitIntoEvents` 以使用可配置的断点模式和最小/最大区块大小。添加 `blockStreamingDefault` 开关以启用/禁用句子级别切割。统一第一轮和第二轮流式传输行为。
2. **阶段 2（P2）**：添加带有空闲超时合并的 `BlockStreamingCoalesceConfig`。添加重复抑制。添加 `deliveryMode` 支持（`live` vs `final_only`）。添加 `blockStreamingBreak` 语义。
3. **阶段 3（P3）**：添加 `hiddenBoundarySeparator`。添加每渠道/每智能体深度合并配置。

---

## 10. 输入状态指示器

### 10.1 概述

输入状态指示器向用户提供关于智能体正在做什么的实时反馈：思考、执行工具、编码、压缩上下文等。这与实际响应内容不同——它是一个与消息流并行运行的"状态"通道。

### 10.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **输入状态指示器** | 无 | 没有输入状态指示器配置。没有 `typingIntervalSeconds`，没有 `typingMode`。 |
| **进度草稿** | 无 | 没有渠道进度草稿事件（tool/item/plan/approval/command-output/patch）。 |
| **状态反应** | 无 | 没有基于表情符号的状态反应（queued/thinking/tool/coding/compacting/done/error）。 |

框架确实发送了一些 SSE 事件 — `"status"`、`"tool_call"`、`"tool_approval"` — 但这些是内容事件，而不是聊天 UI 意义上的面向用户的输入状态/状态指示器。

### 10.3 OpenClaw 功能详情

```
TypingConfig
  typingIntervalSeconds: number    # 刷新输入状态指示器的频率
  typingMode:                      # 何时显示输入状态指示器
    "never"                        # 从不显示
    "instant"                      # 收到消息后立即显示
    "thinking"                     # 智能体推理时显示
    "message"                      # 撰写响应时显示

渠道进度草稿事件：
  - 工具事件：工具执行开始/进度/完成
  - 条目事件：条目级进度
  - 计划事件：计划创建和步骤进度
  - 批准事件：批准请求已发送
  - 命令输出事件：Shell 命令输出流式传输
  - 补丁事件：代码差异流式传输

状态反应（表情符号）：
  - queued：已收到消息，等待处理
  - thinking：LLM 推理进行中
  - tool：工具执行进行中
  - coding：代码生成/编辑进行中
  - compacting：上下文压缩进行中
  - done：处理完成
  - error：发生错误
```

### 10.4 逐功能差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **输入状态指示器** | 无 | `typingIntervalSeconds` + `typingMode` | 用户在思考/工具阶段没有视觉反馈 | P2 | 中 |
| **输入状态模式控制** | 无 | never/instant/thinking/message | 无法选择性启用/禁用或调整行为 | P2 | 低 |
| **进度草稿：工具** | 发送 `"status"` SSE 事件 | 结构化的工具进度事件 | 现有的"status"事件是通用的；不感知渠道 | P2 | 低 |
| **进度草稿：计划** | 无 | 计划创建和步骤进度事件 | UI 中无计划可视化 | P2 | 中 |
| **进度草稿：批准** | 发送 `"tool_approval"` SSE 事件 | 结构化的批准进度事件 | 仅对工具批准有部分实现 | P2 | 低 |
| **进度草稿：命令输出** | 无 | Shell 命令输出流式传输事件 | 无命令执行进度可视化 | P2 | 中 |
| **进度草稿：补丁** | 无 | 代码差异/补丁流式传输事件 | UI 中无代码变更预览 | P3 | 中 |
| **状态反应（表情符号）** | 无 | queued/thinking/tool/coding/compacting/done/error | 聊天中无视觉状态时间线 | P3 | 低 |
| **反应时机** | 无 | 基于智能体状态添加/移除表情符号 | 无状态-反应映射 | P3 | 低 |

### 10.5 实现路线图

1. **阶段 1（P2）**：创建 `TypingConfig` 领域模型。在 `DefaultReActEngine` 的缓冲/思考阶段实现输入状态指示器发送。添加 `typingIntervalSeconds` 控制。作为"typing"事件连接到 SSE 流。
2. **阶段 2（P2）**：为工具、计划和批准状态正式定义进度草稿事件。用结构化的进度事件替换临时的"status"事件。
3. **阶段 3（P3）**：添加命令输出和补丁进度草稿。添加带有自动状态-反应映射的表情符号状态反应。

---

## 11. 时间格式和时区

### 11.1 概述

时间配置控制如何向用户显示时间戳以及如何处理时区感知操作。这包括用户时区偏好、时间格式（12h vs 24h）、消息信封时间戳和经过时间显示。

### 11.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **时间格式** | 无 | 没有面向用户的时间格式化逻辑。所有时间戳使用系统默认值。 |
| **时区** | 无 | 没有 `userTimezone` 配置。所有操作使用服务器时区。 |
| **信封时间戳** | 无 | 消息上没有 `envelopeTimestamp` 或 `envelopeElapsed` 概念。 |

### 11.3 OpenClaw 功能详情

```
TimeConfig
  userTimezone: string         # 可选 IANA 时区（"America/New_York"、"Asia/Shanghai"）
  timeFormat: "auto" | "12" | "24"  # 12 小时制或 24 小时制时间显示
  envelopeTimezone: "utc" | "local" | "user" | IANA  # 消息时间戳的时区
  envelopeTimestamp: "on" | "off"   # 是否在消息信封上显示时间戳
  envelopeElapsed: "on" | "off"     # 是否显示经过时间
```

### 11.4 逐功能差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **用户时区** | 无 | `userTimezone: IANA` | 所有用户看到服务器时间；对全球部署造成困惑 | P2 | 低 |
| **时间格式** | 无 | `timeFormat: "auto" \| "12" \| "24"` | 无适合本地习惯的时间显示 | P3 | 低 |
| **信封时区** | 无 | `envelopeTimezone: "utc" \| "local" \| "user" \| IANA` | 跨时区时间戳模糊不清 | P2 | 低 |
| **信封时间戳开关** | 无 | `envelopeTimestamp: "on" \| "off"` | 无法显示/隐藏消息上的时间戳 | P3 | 低 |
| **信封经过时间开关** | 无 | `envelopeElapsed: "on" \| "off"` | 无法显示相对时间（"2分钟前"） | P3 | 低 |

### 11.5 实现路线图

1. **阶段 1（P2）**：添加带有 `userTimezone` 和 `envelopeTimezone` 的 `TimeConfig`。将时区感知格式化接入消息信封渲染。对所有内部时间戳使用 `java.time.ZonedDateTime`。
2. **阶段 2（P3）**：添加 `timeFormat` 选择。添加 `envelopeTimestamp` 和 `envelopeElapsed` 开关。

---

## 12. 完整功能严重程度矩阵

### 12.1 按优先级

#### P0 — 阻塞（阻碍生产）

| # | 功能 | 组件 | 复杂度 | 依赖项 |
|---|---------|-----------|------------|-------------|
| 1 | AGENTS.md 文件加载 | 启动引导 | 中 | 文件系统访问 |
| 2 | 多文件启动引导（3+ 文件） | 启动引导 | 中 | #1 |
| 3 | 智能体分发/路由 | 路由 | 高 | 渠道抽象 |
| 4 | 渠道抽象 | 路由 | 高 | 无（基础性） |
| 5 | AgentBinding 领域模型 | 路由 | 中 | #4 |
| 6 | 绑定匹配规则 | 路由 | 中 | #5 |
| 7 | 基于模式的匹配 | 路由 | 低 | #6 |
| 8 | 群聊：要求提及 | 群聊 | 中 | #4（渠道） |
| 9 | 群聊：工具限制 | 群聊 | 中 | #8 |

**P0 总计：9 个功能，估计工作量：12-18 周（并行工作）**

#### P1 — 关键优先级（下一个里程碑）

| # | 功能 | 组件 | 复杂度 | 依赖项 |
|---|---------|-----------|------------|-------------|
| 10 | bootstrapMaxChars 控制 | 启动引导 | 低 | #1 |
| 11 | 上下文注入策略 | 启动引导 | 低 | #1 |
| 12 | 紧凑化后段落 | 启动引导 | 中 | #1 + 压缩系统 |
| 13 | 模板变量引擎 | 启动引导 | 低 | #1 |
| 14 | 启动上下文注入 | 启动上下文 | 中 | 记忆系统 |
| 15 | 每日记忆加载 | 启动上下文 | 中 | #14 |
| 16 | 身份：displayName、namePrefix | 身份 | 中 | AgentConfig |
| 17 | 多账户路由 | 路由 | 中 | #4 |
| 18 | 每路由会话 | 路由 | 低 | #5 |
| 19 | 回退/默认路由 | 路由 | 低 | #6 |
| 20 | 群聊：接收策略 | 群聊 | 低 | #8 |
| 21 | 群聊：激活模式 | 群聊 | 低 | #8 |
| 22 | 群聊：发送者访问评估 | 群聊 | 中 | #8 |
| 23 | 心跳：计划调用 | 心跳 | 中 | 调度基础设施 |
| 24 | 心跳：投递目标 | 心跳 | 低 | #23 |
| 25 | 心跳：接收方路由 | 心跳 | 低 | #23 |
| 26 | 区块流式传输：开关 | 区块流式传输 | 低 | SSE 管道 |
| 27 | 区块流式传输：区块配置 | 区块流式传输 | 低 | #26 |

**P1 总计：18 个功能，估计工作量：10-16 周（并行工作）**

#### P2 — 重要优先级（长期路线图）

| # | 功能 | 组件 | 复杂度 |
|---|---------|-----------|------------|
| 28 | 启动引导截断警告 | 启动引导 | 低 |
| 29 | 可选文件跳过控制 | 启动引导 | 低 |
| 30 | 完全跳过启动引导 | 启动引导 | 低 |
| 31 | 文件热重载 | 启动引导 | 中 |
| 32 | 启动上下文：启用/禁用 | 启动上下文 | 低 |
| 33 | 启动上下文：应用触发器 | 启动上下文 | 低 |
| 34 | 启动上下文：大小控制 | 启动上下文 | 低 |
| 35 | ACP 智能体后端 | 路由 | 高 |
| 36 | 动态绑定重载 | 路由 | 中 |
| 37 | 身份：头像（无） | 身份 | 低 |
| 38 | 身份：头像（本地文件） | 身份 | 低 |
| 39 | 身份：头像（远程 URL） | 身份 | 低 |
| 40 | 身份：消息前缀 | 身份 | 低 |
| 41 | 身份：响应前缀 | 身份 | 低 |
| 42 | 群聊：每发送者工具 | 群聊 | 中 |
| 43 | 群聊：访问群组 | 群聊 | 中 |
| 44 | 群聊：白名单展开 | 群聊 | 低 |
| 45 | 心跳：活跃时间 | 心跳 | 低 |
| 46 | 心跳：会话键 | 心跳 | 低 |
| 47 | 心跳：轻量上下文 | 心跳 | 低 |
| 48 | 心跳：隔离会话 | 心跳 | 低 |
| 49 | 心跳：忙碌时跳过 | 心跳 | 低 |
| 50 | 人类延迟：机制 | 人类延迟 | 低 |
| 51 | 人类延迟：每智能体配置 | 人类延迟 | 低 |
| 52 | 区块流式传输：回复合并 | 区块流式传输 | 中 |
| 53 | 区块流式传输：重复抑制 | 区块流式传输 | 低 |
| 54 | 区块流式传输：投递模式 | 区块流式传输 | 低 |
| 55 | 区块流式传输：最大区块字符数 | 区块流式传输 | 低 |
| 56 | 输入状态指示器：核心 | 输入状态指示器 | 中 |
| 57 | 输入状态指示器：模式控制 | 输入状态指示器 | 低 |
| 58 | 输入状态指示器：进度草稿（工具/计划/批准） | 输入状态指示器 | 低 |
| 59 | 进度草稿：命令输出 | 输入状态指示器 | 中 |
| 60 | 时间：用户时区 | 时间/时区 | 低 |
| 61 | 时间：信封时区 | 时间/时区 | 低 |

**P2 总计：34 个功能，估计工作量：8-14 周（许多为低复杂度）**

#### P3 — 低优先级（锦上添花）

| # | 功能 | 组件 | 复杂度 |
|---|---------|-----------|------------|
| 62 | 基于角色的路由（Discord） | 路由 | 中 |
| 63 | 身份：头像（数据 URI） | 身份 | 低 |
| 64 | 身份：确认反应 | 身份 | 低 |
| 65 | 身份：消息配置合并 | 身份 | 低 |
| 66 | 心跳：模型覆盖 | 心跳 | 低 |
| 67 | 心跳：私聊策略 | 心跳 | 低 |
| 68 | 心跳：包含系统提示 | 心跳 | 低 |
| 69 | 心跳：确认最大字符数 | 心跳 | 低 |
| 70 | 心跳：超时 | 心跳 | 低 |
| 71 | 心跳：包含推理 | 心跳 | 低 |
| 72 | 人类延迟：延迟模式 | 人类延迟 | 低 |
| 73 | TTS：配置 | TTS | 中 |
| 74 | TTS：语音选择 | TTS | 低 |
| 75 | TTS：语速/音高 | TTS | 低 |
| 76 | TTS：提供商选择 | TTS | 低 |
| 77 | TTS：与 messages 深度合并 | TTS | 中 |
| 78 | TTS：渠道能力 | TTS | 低 |
| 79 | 区块流式传输：隐藏分隔符 | 区块流式传输 | 低 |
| 80 | 区块流式传输：每渠道配置 | 区块流式传输 | 低 |
| 81 | 输入状态指示器：进度草稿（补丁） | 输入状态指示器 | 中 |
| 82 | 输入状态指示器：状态反应 | 输入状态指示器 | 低 |
| 83 | 时间：时间格式（12/24） | 时间/时区 | 低 |
| 84 | 时间：信封时间戳开关 | 时间/时区 | 低 |
| 85 | 时间：信封经过时间开关 | 时间/时区 | 低 |

**P3 总计：24 个功能，估计工作量：6-10 周（主要是外观优化/实验性功能）**

---

## 13. 依赖关系图

以下图表展示了各组件之间的关键实现依赖关系：

```
渠道抽象 (P0) ────────────────────────────────────────────────────────┐
  ├── 智能体路由与绑定 (P0)                                            │
  │     ├── 多账户路由 (P1)                                            │
  │     ├── 每路由会话 (P1)                                            │
  │     └── ACP 后端 (P2)                                              │
  ├── 群聊 (P0)                                                        │
  │     ├── 接收策略 (P1)                                              │
  │     ├── 激活模式 (P1)                                              │
  │     ├── 发送者访问评估 (P1)                                        │
  │     ├── 每发送者工具 (P2)                                          │
  │     └── 访问群组 (P2)                                              │
  └── 身份 (P1)                                                        │
        ├── 头像 (P2)                                                  │
        ├── 消息/响应前缀 (P2)                                         │
        └── 确认反应 (P3)                                              │
                                                                       │
启动引导系统 (P0)                                                      │
  ├── 多文件加载 (P0)                                                  │
  ├── maxChars 控制 (P1)                                               │
  ├── 上下文注入策略 (P1)                                              │
  ├── 模板变量 (P1)                                                    │
  ├── 紧凑化后段落 (P1) ─── 依赖于压缩系统                             │
  ├── 可选文件 (P2)                                                    │
  ├── 文件热重载 (P2)                                                  │
  └── HEARTBEAT.md 用于心跳 (P2) ─── 依赖于心跳                        │
                                                                       │
心跳系统 (P1)                                                          │
  ├── 调度器 (P1)                                                      │
  ├── 投递目标 (P1) ─── 依赖于渠道抽象                                 │
  ├── 轻量上下文 (P2) ─── 依赖于启动引导                               │
  ├── 隔离会话 (P2)                                                    │
  └── 忙碌时跳过 (P2)                                                  │
                                                                       │
区块流式传输 (P1)                                                      │
  ├── 开关 + 区块配置 (P1)                                             │
  ├── 合并 (P2)                                                        │
  ├── 投递模式 (P2)                                                    │
  └── 隐藏分隔符 (P3)                                                  │
                                                                       │
人类延迟 (P2) ─── 依赖于区块流式传输管道                               │
输入状态指示器 (P2) ─── 依赖于 SSE 管道                                │
TTS (P3) ─── 依赖于渠道 TTS 能力                                       │
时间/时区 (P2) ─── 独立，隔离的工具类                                  │
```

---

## 14. 实现顺序建议

### 里程碑 1：基础（第 1-4 周）
- **启动引导**：AGENTS.md + BOOTSTRAP.md 文件加载，与 `@SystemMessage` 集成
- **渠道抽象**：核心渠道接口和 HTTP 渠道适配器
- 开始：**智能体路由**：AgentBinding 领域模型、模式匹配

### 里程碑 2：多智能体核心（第 5-8 周）
- 完成：**智能体路由**：路由分发、默认路由、模式匹配
- **身份**：displayName、namePrefix、基本头像（none/remote）
- **群聊**：requireMention、GroupToolPolicyConfig、工具过滤
- **启动引导**：maxChars 控制、上下文注入策略、模板变量

### 里程碑 3：主动智能体（第 9-12 周）
- **心跳**：核心调度器、投递目标、接收方路由
- **区块流式传输**：可配置的区块配置、区块流式传输开关
- **启动上下文**：注入、每日记忆加载
- **群聊**：接收策略、激活模式、发送者访问评估

### 里程碑 4：打磨与高级功能（第 13-18 周）
- **区块流式传输**：合并、重复抑制、投递模式
- **输入状态指示器**：核心指示器、进度草稿
- **人类延迟**：带有配置的延迟机制
- **启动引导**：紧凑化后段落、可选文件、热重载
- **时间/时区**：用户时区、信封时区

### 里程碑 5：实验性功能（第 19 周以上）
- **TTS**：完整的 TTS 集成
- **ACP**：外部智能体后端代理
- **基于角色的路由**：Discord 角色路由
- 剩余的 P3 功能

---

## 15. 关键设计决策

### 15.1 渠道抽象

渠道抽象是最关键的架构决策。它是路由、群聊、身份、心跳投递和输入状态指示器的基础。它必须支持：
- **多平台**：HTTP（当前）、Telegram、Discord、WhatsApp、Slack、自定义 webhook
- **平台特定元数据**：Discord 公会/角色、Telegram 聊天类型、WhatsApp E.164
- **统一消息模型**：所有渠道归一化为通用的 `InboundMessage` / `OutboundMessage`
- **适配器模式**：每个平台获得一个实现通用 `ChannelAdapter` 接口的适配器

### 15.2 智能体路由 vs Spring Bean 注入

当前，`ChatController` 直接注入 `ChatAgent` 作为 Spring Bean。路由系统必须与之共存：
- **选项 A**：用返回适当智能体代理的 `AgentRouter.resolve(request)` 替换直接注入
- **选项 B**：为简单部署保留直接注入；路由器作为可选的中间件
- **建议**：选项 B — 保持向后兼容性，同时将路由器作为可选层添加

### 15.3 启动引导文件位置

启动引导文件需要一个明确定义的位置策略：
- **每智能体目录**：`workspace/{agentId}/AGENTS.md`
- **全局回退**：`workspace/default/AGENTS.md`
- **类路径回退**：`classpath:/agents/{agentId}/AGENTS.md`
- **建议**：文件系统优先，类路径回退；可配置的基础路径

### 15.4 SSE 事件分类

LyClaw 目前使用临时的 SSE 事件类型（`"message"`、`"status"`、`"tool_call"`、`"tool_approval"`、`"respond_start"`、`"done"`）。这些需要规范化：

| 当前事件 | 建议标准 | 组件 |
|---------------|-------------------|-----------|
| `"message"` | `"text"`（内容）、`"block"`（完整区块） | 区块流式传输 |
| `"status"` | `"typing"`、`"thinking"`、`"progress"` | 输入状态指示器 |
| `"tool_call"` | `"tool.start"`、`"tool.progress"`、`"tool.done"` | 输入状态指示器 |
| `"tool_approval"` | `"approval.request"` | 输入状态指示器 |
| `"respond_start"` | `"agent.start"`、`"agent.identity"` | 身份 |
| `"done"` | `"agent.done"`、`"stream.end"` | 区块流式传输 |
| *（新增）* | `"heartbeat"` | 心跳 |
| *（新增）* | `"error"`、`"compact"` | 输入状态指示器 |

---

## 16. 指标和可观测性差距

在添加这些功能时，必须考虑可观测性：

| 功能 | 需要的指标 |
|---------|---------------|
| 启动引导 | 加载时间、文件大小、截断事件、解析错误 |
| 路由 | 路由匹配延迟、每路由匹配计数、未命中率、分发错误 |
| 身份 | 解析时间、头像获取延迟 |
| 群聊 | 提及检测率、工具拒绝计数、发送者授权失败 |
| 心跳 | 调用次数、成功率、跳过率（忙碌）、延迟、超时率 |
| 人类延迟 | 实际应用的延迟、用户感知的响应时间 |
| TTS | 合成延迟、字符数、提供商错误 |
| 区块流式传输 | 区块数、平均区块大小、合并计数、重复抑制计数 |
| 输入状态指示器 | 指示器发送次数、状态转换延迟 |

---

## 17. 汇总统计

| 分类 | P0 | P1 | P2 | P3 | 总计 |
|----------|----|----|----|----|-------|
| 启动引导 | 3 | 3 | 3 | 0 | 9 |
| 启动上下文 | 0 | 2 | 3 | 0 | 5 |
| 智能体路由 | 5 | 3 | 2 | 1 | 11 |
| 身份 | 0 | 1 | 3 | 3 | 7 |
| 群聊 | 2 | 3 | 3 | 0 | 8 |
| 心跳 | 0 | 3 | 6 | 7 | 16 |
| 人类延迟 | 0 | 0 | 2 | 1 | 3 |
| TTS | 0 | 0 | 0 | 6 | 6 |
| 区块流式传输 | 0 | 2 | 4 | 2 | 8 |
| 输入状态指示器 | 0 | 0 | 3 | 3 | 6 |
| 时间/时区 | 0 | 0 | 2 | 3 | 5 |
| **总计** | **10** | **17** | **31** | **26** | **84** |

### 工作量估算汇总

| 优先级 | 功能数量 | 估算工作量（周） | 估算工作量（并行） |
|----------|--------------|---------------------|------------------------|
| P0 | 10 | 18-26 | 12-18 |
| P1 | 17 | 14-22 | 10-16 |
| P2 | 31 | 8-14 | 6-10 |
| P3 | 26 | 6-10 | 4-8 |
| **总计** | **84** | **46-72** | **32-52** |

---

*文档生成日期：2026-05-20。涵盖 LyClaw 代码库在提交 bc2cb96（"修复沙箱问题和前端批准"）时的状态 vs OpenClaw 参考架构。*

---

# 第一阶段：Agent核心增强 — 改造方案

> **目标**: 使LyClaw的Agent配置、运行时和Hook系统达到与OpenClaw同等的水平。
> **状态**: 草案
> **依赖**: 无（此为基础设施阶段）

---

## 概述

LyClaw 是一个基于 Java/Spring Boot 的多Agent框架。其Agent系统目前具有：

| 组件 | 当前状态 | 目标 |
|---|---|---|
| `@Agent` 注解 | 6个基本字段（name, description, version, model, provider, extensions） | 约30个字段，达到完全OpenClaw对等水平 |
| `AgentConfig` | 扁平POJO，包含4个核心字段 + extensions Map | 4层层级结构（默认值 / 注解 / yaml / 运行时） |
| `AgentConfigResolver` | 基于优先级的来源合并 | 深度合并解析器，生成ResolvedAgentConfig |
| `AgentContext` | 扁平POJO，约12个字段 | 丰富的上下文，约25个字段 + 快照/恢复 |
| `AgentHook` SPI | 5个方法 + getOrder() | 36个方法的生命周期SPI |
| `AgentInvocationHandler` | JDK动态代理，5个Hook分发 | 完整的Hook生命周期分发 |
| `AgentProxyFactory` | 简单构造函数 + create(Class) | 配置感知工厂，支持运行时类型 |
| Pipeline | 6阶段SSE流式处理 | 相同阶段，增强Hook事件 |
| 运行时模式 | 仅EMBEDDED（ReAct） | EMBEDDED + ACP双模式 |

---

## 1.1 AgentConfig系统重构

### 1.1.1 问题

当前 `@Agent` 注解仅携带6个字段。每个Agent的配置（如thinking级别、沙箱设置、子Agent委托、上下文注入行为、引导限制等）都塞在不透明的 `Extension[]` 键值对中。这使得配置无类型、不可发现且容易出错。同时也没有"全局默认值"的概念供Agent继承。

### 1.1.2 设计：扩展的 `@Agent` 注解

```java
package lyjew.com.lyclaw.annotation;

import java.lang.annotation.*;
import org.springframework.stereotype.Component;

/**
 * AI Agent声明注解 — 扩展至完全OpenClaw对等水平。
 *
 * <p>字段解析优先级：Agent级别 > 全局默认值
 * ({@code lyclaw.agent.defaults.*}) > 系统内置默认值。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@Documented
public @interface Agent {

    // ── 身份标识 ──────────────────────────────────────────────
    /** Agent唯一标识符。为空时，从类简单名称（小驼峰）派生。 */
    String id() default "";

    /** 此Agent是否为默认Agent（未指定具体Agent时使用）。 */
    boolean defaultAgent() default false;          // was: (missing)

    /** 人类可读的显示名称。为空时，从id派生。 */
    String name() default "";

    /** 在UI中显示的描述信息，并用于Agent选择路由。 */
    String description() default "";

    /** 语义化版本字符串（SemVer）。 */
    String version() default "1.0.0";

    // ── 工作区 ─────────────────────────────────────────────
    /** 此Agent的工作区根目录。为空表示使用全局工作区。 */
    String workspace() default "";

    /** 工作区下Agent专属子目录。为空表示使用Agent id。 */
    String agentDir() default "";

    // ── 系统提示词覆盖 ────────────────────────────────
    /** 覆盖原本从AGENTS.md等文件引导加载的系统提示词。 */
    String systemPromptOverride() default "";

    // ── 模型 ──────────────────────────────────────────────────
    /** 模型名称（如 "deepseek-v4-flash"）。为空 = 使用默认值。 */
    String model() default "";

    /** 提供商键值（如 "deepseek", "openai"）。为空 = 使用默认值。 */
    String provider() default "";

    /** 有序的备用模型键值列表，主模型失败时按顺序尝试。 */
    String[] fallbacks() default {};

    // ── 技能 ─────────────────────────────────────────────────
    /** 附加到此Agent的技能标识符（如 "web-search", "code-interpreter"）。 */
    String[] skills() default {};

    // ── 思考 / 详细度 / 推理 ─────────────────────────
    /**
     * 默认思考级别。
     * 有效值: off, minimal, low, medium, high, xhigh, adaptive, max。
     * 为空表示使用全局默认值。
     */
    String thinkingDefault() default "";

    /** 默认详细度级别。为空 = 使用全局默认值。 */
    String verboseDefault() default "";

    /** 默认推理级别。为空 = 使用全局默认值。 */
    String reasoningDefault() default "";

    /** 快速模式：为true时跳过昂贵的预处理步骤。 */
    boolean fastModeDefault() default false;

    // ── 上下文限制 ─────────────────────────────────────────
    /** 为此Agent预留的最大上下文窗口Token数。0 = 使用全局默认值。 */
    int contextTokens() default 0;

    /** 从单个引导文件（如AGENTS.md）中加载的最大字符数。 */
    int bootstrapMaxChars() default 20000;

    /** 所有引导文件合计的最大字符数。 */
    int bootstrapTotalMaxChars() default 150000;

    /**
     * 何时将AGENTS.md / CLAUDE.md内容注入系统提示词。
     * 有效值: always, continuation-skip, never。
     */
    String contextInjection() default "always";

    // ── 子Agent委托 ────────────────────────────────────
    /**
     * 子Agent生成的委托模式。
     *   suggest — Agent建议子Agent委托，用户确认
     *   prefer  — Agent倾向委托，减少用户干预
     */
    String delegationMode() default "suggest";

    /** 此Agent允许生成的Agent id白名单。为空 = 不限制。 */
    String[] allowAgents() default {};

    /** 生成的子Agent最大嵌套深度。 */
    int maxSpawnDepth() default 1;

    /** 此Agent在单层中最多可生成的子Agent数量。 */
    int maxChildrenPerAgent() default 5;

    // ── 沙箱 ────────────────────────────────────────────────
    /**
     * 沙箱模式: none, docker, podman。
     * 为空 = 使用全局默认值。
     */
    String sandbox() default "";

    // ── 扩展（向后兼容的逃生舱口） ──────────
    /**
     * 供框架插件使用的任意键值对。
     * 优先使用上方的类型化字段；仅当插件特定配置没有类型化等价字段时使用扩展。
     */
    Extension[] extensions() default {};
}
```

### 1.1.3 AgentDefaultsConfig（全局默认值）

此类绑定到 `application.yml` 中的 `lyclaw.agent.defaults.*`，为每个Agent在其注解级别字段为空时提供回退层。

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import java.util.List;
import java.util.Map;

/**
 * 全局Agent默认值，绑定自 {@code lyclaw.agent.defaults.*}。
 *
 * <p>此类中的每个字段在 @Agent 中都有Agent级别的覆盖。
 * 解析顺序: agent注解 > lyclaw.agent.defaults > 硬编码系统默认值。
 */
@ConfigurationProperties(prefix = "lyclaw.agent.defaults")
public class AgentDefaultsConfig {

    // ── 模型默认值 ─────────────────────────────────────────
    /** 默认模型名称（如 "deepseek-v4-flash"）。 */
    private String model;                    // 系统默认值: "deepseek-v4-flash"

    /** 默认提供商键值。 */
    private String provider;                 // 系统默认值: "deepseek"

    /** 默认有序备用模型键值列表。 */
    private List<String> fallbacks = List.of();

    // ── 思考 / 详细度 / 推理 ─────────────────────────
    /** 默认思考级别: off|minimal|low|medium|high|xhigh|adaptive|max。 */
    private String thinkingDefault;          // 系统默认值: "off"

    /** 默认详细度级别。 */
    private String verboseDefault;           // 系统默认值: ""

    /** 默认推理级别。 */
    private String reasoningDefault;         // 系统默认值: ""

    /** 是否默认开启快速模式。 */
    private boolean fastModeDefault;         // 系统默认值: false

    // ── 上下文 ────────────────────────────────────────────────
    /** 何时注入引导内容: always|continuation-skip|never。 */
    private String contextInjection = "always";

    /** 每个引导文件的最大字符数。 */
    private int bootstrapMaxChars = 20000;

    /** 所有引导文件合计的最大字符数。 */
    private int bootstrapTotalMaxChars = 150000;

    /** 预留的上下文窗口Token数。 */
    private int contextTokens = 0;

    // ── 技能 ─────────────────────────────────────────────────
    /** 附加到所有Agent的默认技能。 */
    private List<String> skills = List.of();

    // ── 沙箱 ────────────────────────────────────────────────
    /** 默认沙箱模式: none|docker|podman。 */
    private String sandbox = "none";

    // ── 子Agent（委托默认值） ────────────────────────
    @NestedConfigurationProperty
    private SubagentDefaults subagents = new SubagentDefaults();

    // ── 心跳检测 ──────────────────────────────────────────────
    @NestedConfigurationProperty
    private HeartbeatDefaults heartbeat = new HeartbeatDefaults();

    // ── 运行重试 ────────────────────────────────────────────
    @NestedConfigurationProperty
    private RunRetryDefaults runRetries = new RunRetryDefaults();

    // ── 上下文限制（工具输出裁剪） ──────────────────
    @NestedConfigurationProperty
    private ContextLimitsDefaults contextLimits = new ContextLimitsDefaults();

    // ── 工作区 ──────────────────────────────────────────────
    /** 默认工作区目录。 */
    private String workspace;

    // ===== Getters / Setters =====

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public List<String> getFallbacks() { return fallbacks; }
    public void setFallbacks(List<String> fallbacks) { this.fallbacks = fallbacks; }

    public String getThinkingDefault() { return thinkingDefault; }
    public void setThinkingDefault(String thinkingDefault) { this.thinkingDefault = thinkingDefault; }

    public String getVerboseDefault() { return verboseDefault; }
    public void setVerboseDefault(String verboseDefault) { this.verboseDefault = verboseDefault; }

    public String getReasoningDefault() { return reasoningDefault; }
    public void setReasoningDefault(String reasoningDefault) { this.reasoningDefault = reasoningDefault; }

    public boolean isFastModeDefault() { return fastModeDefault; }
    public void setFastModeDefault(boolean fastModeDefault) { this.fastModeDefault = fastModeDefault; }

    public String getContextInjection() { return contextInjection; }
    public void setContextInjection(String contextInjection) { this.contextInjection = contextInjection; }

    public int getBootstrapMaxChars() { return bootstrapMaxChars; }
    public void setBootstrapMaxChars(int bootstrapMaxChars) { this.bootstrapMaxChars = bootstrapMaxChars; }

    public int getBootstrapTotalMaxChars() { return bootstrapTotalMaxChars; }
    public void setBootstrapTotalMaxChars(int bootstrapTotalMaxChars) { this.bootstrapTotalMaxChars = bootstrapTotalMaxChars; }

    public int getContextTokens() { return contextTokens; }
    public void setContextTokens(int contextTokens) { this.contextTokens = contextTokens; }

    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills; }

    public String getSandbox() { return sandbox; }
    public void setSandbox(String sandbox) { this.sandbox = sandbox; }

    public SubagentDefaults getSubagents() { return subagents; }
    public void setSubagents(SubagentDefaults subagents) { this.subagents = subagents; }

    public HeartbeatDefaults getHeartbeat() { return heartbeat; }
    public void setHeartbeat(HeartbeatDefaults heartbeat) { this.heartbeat = heartbeat; }

    public RunRetryDefaults getRunRetries() { return runRetries; }
    public void setRunRetries(RunRetryDefaults runRetries) { this.runRetries = runRetries; }

    public ContextLimitsDefaults getContextLimits() { return contextLimits; }
    public void setContextLimits(ContextLimitsDefaults contextLimits) { this.contextLimits = contextLimits; }

    public String getWorkspace() { return workspace; }
    public void setWorkspace(String workspace) { this.workspace = workspace; }

    // ===== 嵌套配置类 =====

    /** 子Agent委托默认值。 */
    public static class SubagentDefaults {
        /** 默认委托模式: suggest|prefer。 */
        private String delegationMode = "suggest";

        /** Agent id白名单。为空 = 全部允许。 */
        private List<String> allowAgents = List.of();

        /** 默认最大生成深度。 */
        private int maxSpawnDepth = 1;

        /** 默认每个Agent最大子Agent数。 */
        private int maxChildrenPerAgent = 5;

        // 为简洁省略getter/setter
        public String getDelegationMode() { return delegationMode; }
        public void setDelegationMode(String m) { this.delegationMode = m; }
        public List<String> getAllowAgents() { return allowAgents; }
        public void setAllowAgents(List<String> a) { this.allowAgents = a; }
        public int getMaxSpawnDepth() { return maxSpawnDepth; }
        public void setMaxSpawnDepth(int d) { this.maxSpawnDepth = d; }
        public int getMaxChildrenPerAgent() { return maxChildrenPerAgent; }
        public void setMaxChildrenPerAgent(int c) { this.maxChildrenPerAgent = c; }
    }

    /** 心跳检测配置。 */
    public static class HeartbeatDefaults {
        /** 是否启用心跳检测（周期性的"你仍然存活"提示）。 */
        private boolean enabled = false;

        /** 心跳检测间隔秒数。 */
        private long intervalSeconds = 60;

        /** 触发心跳前的最大空闲秒数。 */
        private long maxIdleSeconds = 300;

        // getters/setters
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public long getIntervalSeconds() { return intervalSeconds; }
        public void setIntervalSeconds(long s) { this.intervalSeconds = s; }
        public long getMaxIdleSeconds() { return maxIdleSeconds; }
        public void setMaxIdleSeconds(long s) { this.maxIdleSeconds = s; }
    }

    /** 运行重试配置。 */
    public static class RunRetryDefaults {
        /** 模型失败时的最大重试次数。 */
        private int maxAttempts = 3;

        /** 重试之间的基础延迟毫秒数。 */
        private long baseDelayMs = 1000;

        /** 退避策略: fixed|exponential。 */
        private String backoff = "exponential";

        // getters/setters
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int n) { this.maxAttempts = n; }
        public long getBaseDelayMs() { return baseDelayMs; }
        public void setBaseDelayMs(long d) { this.baseDelayMs = d; }
        public String getBackoff() { return backoff; }
        public void setBackoff(String b) { this.backoff = b; }
    }

    /** 上下文限制（工具输出裁剪 / 内存限制）。 */
    public static class ContextLimitsDefaults {
        /** 从内存检索中包括的最大字符数。 */
        private int memoryGetMaxChars = 50000;

        /** 单个工具结果包含在上下文中的最大字符数。 */
        private int toolResultMaxChars = 80000;

        /** 所有工具结果合计的最大字符数。 */
        private int toolResultTotalMaxChars = 200000;

        // getters/setters
        public int getMemoryGetMaxChars() { return memoryGetMaxChars; }
        public void setMemoryGetMaxChars(int c) { this.memoryGetMaxChars = c; }
        public int getToolResultMaxChars() { return toolResultMaxChars; }
        public void setToolResultMaxChars(int c) { this.toolResultMaxChars = c; }
        public int getToolResultTotalMaxChars() { return toolResultTotalMaxChars; }
        public void setToolResultTotalMaxChars(int c) { this.toolResultTotalMaxChars = c; }
    }
}
```

### 1.1.4 AgentSystemDefaults（硬编码回退）

当注解和 `lyclaw.agent.defaults` 都未提供值时，系统使用这些内置常量。它们定义为静态内部类或常量文件：

```java
package lyjew.com.lyclaw.config;

/**
 * 硬编码系统默认值 — 最低优先级的回退层。
 * 当Agent注解和lyclaw.agent.defaults都没有提供值时使用。
 */
public final class AgentSystemDefaults {

    private AgentSystemDefaults() {}

    public static final String MODEL            = "deepseek-v4-flash";
    public static final String PROVIDER         = "deepseek";
    public static final String THINKING_DEFAULT = "off";
    public static final String VERBOSE_DEFAULT  = "";
    public static final String REASONING_DEFAULT = "";
    public static final boolean FAST_MODE       = false;
    public static final String CONTEXT_INJECTION = "always";
    public static final int BOOTSTRAP_MAX_CHARS = 20000;
    public static final int BOOTSTRAP_TOTAL_MAX_CHARS = 150000;
    public static final int CONTEXT_TOKENS      = 0;
    public static final String SANDBOX          = "none";
    public static final String DELEGATION_MODE  = "suggest";
    public static final int MAX_SPAWN_DEPTH     = 1;
    public static final int MAX_CHILDREN        = 5;
    public static final int MEMORY_GET_MAX_CHARS = 50000;
    public static final int TOOL_RESULT_MAX_CHARS = 80000;
    public static final int TOOL_RESULT_TOTAL_MAX_CHARS = 200000;
}
```

### 1.1.5 ResolvedAgentConfig（解析输出）

解析器生成一个完全解析、深度合并、只读的配置对象。

```java
package lyjew.com.lyclaw.config;

import java.util.*;

/**
 * 完全解析的Agent配置 — 3层深度合并的输出。
 *
 * <p>每个字段都经过以下解析:
 *   agent注解 > lyclaw.agent.defaults.* > AgentSystemDefaults
 *
 * <p>此类在构造后是不可变的，以防止在Agent运行生命周期中意外修改。
 */
public class ResolvedAgentConfig {

    // ── 身份标识 ──
    private final String agentId;
    private final String agentName;
    private final String description;
    private final String version;
    private final boolean defaultAgent;

    // ── 工作区 ──
    private final String workspaceDir;
    private final String agentDir;

    // ── 系统提示词 ──
    private final String systemPromptOverride;

    // ── 模型 ──
    private final String model;
    private final String provider;
    private final List<String> fallbacks;

    // ── 思考 / 详细度 / 推理 ──
    private final String thinkingDefault;
    private final String verboseDefault;
    private final String reasoningDefault;
    private final boolean fastModeDefault;

    // ── 上下文 ──
    private final int contextTokens;
    private final String contextInjection;
    private final int bootstrapMaxChars;
    private final int bootstrapTotalMaxChars;

    // ── 技能 ──
    private final List<String> skills;

    // ── 委托 ──
    private final String delegationMode;
    private final List<String> allowAgents;
    private final int maxSpawnDepth;
    private final int maxChildrenPerAgent;

    // ── 沙箱 ──
    private final String sandbox;

    // ── 扩展（来自 @Extension[] 的剩余键值对） ──
    private final Map<String, String> extensions;

    // ── 运行时配置（从默认值复制） ──
    private final AgentDefaultsConfig.HeartbeatDefaults heartbeat;
    private final AgentDefaultsConfig.RunRetryDefaults runRetries;
    private final AgentDefaultsConfig.ContextLimitsDefaults contextLimits;

    // 私有构造函数 — 通过AgentConfigResolver使用Builder
    private ResolvedAgentConfig(Builder builder) {
        this.agentId              = builder.agentId;
        this.agentName            = builder.agentName;
        this.description          = builder.description;
        this.version              = builder.version;
        this.defaultAgent         = builder.defaultAgent;
        this.workspaceDir         = builder.workspaceDir;
        this.agentDir             = builder.agentDir;
        this.systemPromptOverride = builder.systemPromptOverride;
        this.model                = builder.model;
        this.provider             = builder.provider;
        this.fallbacks            = List.copyOf(builder.fallbacks);
        this.thinkingDefault      = builder.thinkingDefault;
        this.verboseDefault       = builder.verboseDefault;
        this.reasoningDefault     = builder.reasoningDefault;
        this.fastModeDefault      = builder.fastModeDefault;
        this.contextTokens        = builder.contextTokens;
        this.contextInjection     = builder.contextInjection;
        this.bootstrapMaxChars    = builder.bootstrapMaxChars;
        this.bootstrapTotalMaxChars = builder.bootstrapTotalMaxChars;
        this.skills               = List.copyOf(builder.skills);
        this.delegationMode       = builder.delegationMode;
        this.allowAgents          = List.copyOf(builder.allowAgents);
        this.maxSpawnDepth        = builder.maxSpawnDepth;
        this.maxChildrenPerAgent  = builder.maxChildrenPerAgent;
        this.sandbox              = builder.sandbox;
        this.extensions           = Collections.unmodifiableMap(new HashMap<>(builder.extensions));
        this.heartbeat            = builder.heartbeat;
        this.runRetries           = builder.runRetries;
        this.contextLimits        = builder.contextLimits;
    }

    // ===== Getters =====

    public String getAgentId() { return agentId; }
    public String getAgentName() { return agentName; }
    public String getDescription() { return description; }
    public String getVersion() { return version; }
    public boolean isDefaultAgent() { return defaultAgent; }
    public String getWorkspaceDir() { return workspaceDir; }
    public String getAgentDir() { return agentDir; }
    public String getSystemPromptOverride() { return systemPromptOverride; }
    public String getModel() { return model; }
    public String getProvider() { return provider; }
    public List<String> getFallbacks() { return fallbacks; }
    public String getThinkingDefault() { return thinkingDefault; }
    public String getVerboseDefault() { return verboseDefault; }
    public String getReasoningDefault() { return reasoningDefault; }
    public boolean isFastModeDefault() { return fastModeDefault; }
    public int getContextTokens() { return contextTokens; }
    public String getContextInjection() { return contextInjection; }
    public int getBootstrapMaxChars() { return bootstrapMaxChars; }
    public int getBootstrapTotalMaxChars() { return bootstrapTotalMaxChars; }
    public List<String> getSkills() { return skills; }
    public String getDelegationMode() { return delegationMode; }
    public List<String> getAllowAgents() { return allowAgents; }
    public int getMaxSpawnDepth() { return maxSpawnDepth; }
    public int getMaxChildrenPerAgent() { return maxChildrenPerAgent; }
    public String getSandbox() { return sandbox; }
    public Map<String, String> getExtensions() { return extensions; }
    public AgentDefaultsConfig.HeartbeatDefaults getHeartbeat() { return heartbeat; }
    public AgentDefaultsConfig.RunRetryDefaults getRunRetries() { return runRetries; }
    public AgentDefaultsConfig.ContextLimitsDefaults getContextLimits() { return contextLimits; }

    // ===== Builder =====

    public static class Builder {
        private String agentId = "";
        private String agentName = "";
        private String description = "";
        private String version = "1.0.0";
        private boolean defaultAgent = false;
        private String workspaceDir = "";
        private String agentDir = "";
        private String systemPromptOverride = "";
        private String model = "";
        private String provider = "";
        private List<String> fallbacks = List.of();
        private String thinkingDefault = "";
        private String verboseDefault = "";
        private String reasoningDefault = "";
        private boolean fastModeDefault = false;
        private int contextTokens = 0;
        private String contextInjection = "always";
        private int bootstrapMaxChars = 20000;
        private int bootstrapTotalMaxChars = 150000;
        private List<String> skills = List.of();
        private String delegationMode = "suggest";
        private List<String> allowAgents = List.of();
        private int maxSpawnDepth = 1;
        private int maxChildrenPerAgent = 5;
        private String sandbox = "none";
        private Map<String, String> extensions = new HashMap<>();
        private AgentDefaultsConfig.HeartbeatDefaults heartbeat = new AgentDefaultsConfig.HeartbeatDefaults();
        private AgentDefaultsConfig.RunRetryDefaults runRetries = new AgentDefaultsConfig.RunRetryDefaults();
        private AgentDefaultsConfig.ContextLimitsDefaults contextLimits = new AgentDefaultsConfig.ContextLimitsDefaults();

        // （每个字段的setter — 为简洁省略，遵循以下模式:）

        public Builder agentId(String v) { this.agentId = v; return this; }
        public Builder agentName(String v) { this.agentName = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder version(String v) { this.version = v; return this; }
        public Builder defaultAgent(boolean v) { this.defaultAgent = v; return this; }
        public Builder workspaceDir(String v) { this.workspaceDir = v; return this; }
        public Builder agentDir(String v) { this.agentDir = v; return this; }
        public Builder systemPromptOverride(String v) { this.systemPromptOverride = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder provider(String v) { this.provider = v; return this; }
        public Builder fallbacks(List<String> v) { this.fallbacks = v; return this; }
        public Builder thinkingDefault(String v) { this.thinkingDefault = v; return this; }
        public Builder verboseDefault(String v) { this.verboseDefault = v; return this; }
        public Builder reasoningDefault(String v) { this.reasoningDefault = v; return this; }
        public Builder fastModeDefault(boolean v) { this.fastModeDefault = v; return this; }
        public Builder contextTokens(int v) { this.contextTokens = v; return this; }
        public Builder contextInjection(String v) { this.contextInjection = v; return this; }
        public Builder bootstrapMaxChars(int v) { this.bootstrapMaxChars = v; return this; }
        public Builder bootstrapTotalMaxChars(int v) { this.bootstrapTotalMaxChars = v; return this; }
        public Builder skills(List<String> v) { this.skills = v; return this; }
        public Builder delegationMode(String v) { this.delegationMode = v; return this; }
        public Builder allowAgents(List<String> v) { this.allowAgents = v; return this; }
        public Builder maxSpawnDepth(int v) { this.maxSpawnDepth = v; return this; }
        public Builder maxChildrenPerAgent(int v) { this.maxChildrenPerAgent = v; return this; }
        public Builder sandbox(String v) { this.sandbox = v; return this; }
        public Builder extensions(Map<String, String> v) { this.extensions.clear(); this.extensions.putAll(v); return this; }
        public Builder heartbeat(AgentDefaultsConfig.HeartbeatDefaults v) { this.heartbeat = v; return this; }
        public Builder runRetries(AgentDefaultsConfig.RunRetryDefaults v) { this.runRetries = v; return this; }
        public Builder contextLimits(AgentDefaultsConfig.ContextLimitsDefaults v) { this.contextLimits = v; return this; }

        public ResolvedAgentConfig build() {
            return new ResolvedAgentConfig(this);
        }
    }
}
```

### 1.1.6 AgentConfigResolver增强

解析器增强了3层深度合并、列出Agent和支持工作区目录解析。

```java
package lyjew.com.lyclaw.config;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.annotation.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Agent配置解析器 — 执行3层深度合并:
 *   第1层: AgentSystemDefaults（硬编码）
 *   第2层: AgentDefaultsConfig（lyclaw.agent.defaults.*）
 *   第3层: @Agent 注解（Agent级别）
 *
 * <p>每个字段使用最高层中第一个非空/非默认值。
 * 列表是替换，不是合并（如果注解非空，则完全胜出）。
 * Map（扩展）是加法合并（键冲突时注解胜出）。
 */
public class AgentConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigResolver.class);

    private final AgentDefaultsConfig defaults;

    /** 缓存: agentId → ResolvedAgentConfig。配置刷新时失效。 */
    private final Map<String, ResolvedAgentConfig> cache = new ConcurrentHashMap<>();

    /** 已注册的Agent条目: agentId → @Agent注解(来自类)。 */
    private final Map<String, Agent> agentRegistry = new ConcurrentHashMap<>();

    public AgentConfigResolver(AgentDefaultsConfig defaults) {
        this.defaults = defaults;
    }

    /**
     * 注册Agent类以便后续解析。
     * 由AgentInterfaceProcessor在BFPP扫描期间调用。
     */
    public void registerAgent(String agentId, Agent ann) {
        agentRegistry.put(agentId, ann);
    }

    /**
     * 为给定Agent解析完整合并后的配置。
     *
     * 每个字段的解析规则:
     *   1. 如果 @Agent 字段已设置（非空字符串、非零int、非false boolean、非空列表），
     *      使用它。
     *   2. 否则如果 AgentDefaultsConfig 有非默认值，使用它。
     *   3. 否则使用 AgentSystemDefaults。
     */
    public ResolvedAgentConfig resolveAgentConfig(String agentId) {
        return cache.computeIfAbsent(agentId, id -> {
            Agent ann = agentRegistry.get(id);
            ResolvedAgentConfig.Builder b = new ResolvedAgentConfig.Builder();

            // ── 身份标识 ──
            b.agentId(id);
            b.agentName(resolveString(
                    ann != null ? ann.name() : "", defaultsField(null, "name"), id));
            b.description(resolveString(
                    ann != null ? ann.description() : "", "", ""));
            b.version(resolveString(
                    ann != null ? ann.version() : "", "1.0.0", "1.0.0"));
            b.defaultAgent(ann != null && ann.defaultAgent());

            // ── 工作区 ──
            b.workspaceDir(resolveString(
                    ann != null ? ann.workspace() : "",
                    defaults.getWorkspace(), ""));
            b.agentDir(resolveString(
                    ann != null ? ann.agentDir() : "", "", id));

            // ── 系统提示词 ──
            b.systemPromptOverride(resolveString(
                    ann != null ? ann.systemPromptOverride() : "", "", ""));

            // ── 模型 ──
            b.model(resolveString(
                    ann != null ? ann.model() : "",
                    defaults.getModel(), AgentSystemDefaults.MODEL));
            b.provider(resolveString(
                    ann != null ? ann.provider() : "",
                    defaults.getProvider(), AgentSystemDefaults.PROVIDER));
            b.fallbacks(resolveList(
                    ann != null ? List.of(ann.fallbacks()) : List.of(),
                    defaults.getFallbacks()));

            // ── 思考 / 详细度 / 推理 ──
            b.thinkingDefault(resolveString(
                    ann != null ? ann.thinkingDefault() : "",
                    defaults.getThinkingDefault(), AgentSystemDefaults.THINKING_DEFAULT));
            b.verboseDefault(resolveString(
                    ann != null ? ann.verboseDefault() : "",
                    defaults.getVerboseDefault(), AgentSystemDefaults.VERBOSE_DEFAULT));
            b.reasoningDefault(resolveString(
                    ann != null ? ann.reasoningDefault() : "",
                    defaults.getReasoningDefault(), AgentSystemDefaults.REASONING_DEFAULT));
            b.fastModeDefault(
                    (ann != null && ann.fastModeDefault())
                            || (!(ann != null && ann.fastModeDefault()) && defaults.isFastModeDefault()));

            // ── 上下文 ──
            b.contextTokens(resolveInt(
                    ann != null ? ann.contextTokens() : 0,
                    defaults.getContextTokens(), AgentSystemDefaults.CONTEXT_TOKENS));
            b.contextInjection(resolveString(
                    ann != null ? ann.contextInjection() : "",
                    defaults.getContextInjection(), AgentSystemDefaults.CONTEXT_INJECTION));
            b.bootstrapMaxChars(resolveInt(
                    ann != null ? ann.bootstrapMaxChars() : 0,
                    defaults.getBootstrapMaxChars(), AgentSystemDefaults.BOOTSTRAP_MAX_CHARS));
            b.bootstrapTotalMaxChars(resolveInt(
                    ann != null ? ann.bootstrapTotalMaxChars() : 0,
                    defaults.getBootstrapTotalMaxChars(), AgentSystemDefaults.BOOTSTRAP_TOTAL_MAX_CHARS));

            // ── 技能 ──
            b.skills(resolveList(
                    ann != null ? List.of(ann.skills()) : List.of(),
                    defaults.getSkills()));

            // ── 委托 ──
            b.delegationMode(resolveString(
                    ann != null ? ann.delegationMode() : "",
                    defaults.getSubagents().getDelegationMode(), AgentSystemDefaults.DELEGATION_MODE));
            b.allowAgents(resolveList(
                    ann != null ? List.of(ann.allowAgents()) : List.of(),
                    defaults.getSubagents().getAllowAgents()));
            b.maxSpawnDepth(resolveInt(
                    ann != null ? ann.maxSpawnDepth() : 0,
                    defaults.getSubagents().getMaxSpawnDepth(), AgentSystemDefaults.MAX_SPAWN_DEPTH));
            b.maxChildrenPerAgent(resolveInt(
                    ann != null ? ann.maxChildrenPerAgent() : 0,
                    defaults.getSubagents().getMaxChildrenPerAgent(), AgentSystemDefaults.MAX_CHILDREN));

            // ── 沙箱 ──
            b.sandbox(resolveString(
                    ann != null ? ann.sandbox() : "",
                    defaults.getSandbox(), AgentSystemDefaults.SANDBOX));

            // ── 扩展: 将注解扩展合并到任何默认值之上
            Map<String, String> extMap = new HashMap<>();
            if (ann != null) {
                for (Extension ext : ann.extensions()) {
                    extMap.put(ext.key(), ext.value());
                }
            }
            b.extensions(extMap);

            // ── 运行时配置（直接从默认值复制，无需注解覆盖） ──
            b.heartbeat(defaults.getHeartbeat());
            b.runRetries(defaults.getRunRetries());
            b.contextLimits(defaults.getContextLimits());

            log.debug("ResolvedAgentConfig for {}: model={} provider={} sandbox={}",
                    id, b.build().getModel(), b.build().getProvider(), b.build().getSandbox());
            return b.build();
        });
    }

    /**
     * 列出所有已注册的Agent ID。
     */
    public Set<String> listAgentIds() {
        return Collections.unmodifiableSet(agentRegistry.keySet());
    }

    /**
     * 列出所有已注册的Agent条目，作为 (id, name, description) 三元组。
     */
    public List<AgentEntry> listAgentEntries() {
        return agentRegistry.entrySet().stream()
                .map(e -> new AgentEntry(e.getKey(),
                        e.getValue().name().isEmpty() ? e.getKey() : e.getValue().name(),
                        e.getValue().description()))
                .collect(Collectors.toList());
    }

    /**
     * 解析默认Agent id。返回defaultAgent=true的Agent，
     * 或第一个注册的Agent，或 "default"。
     */
    public String resolveDefaultAgentId() {
        return agentRegistry.entrySet().stream()
                .filter(e -> e.getValue().defaultAgent())
                .map(Map.Entry::getKey)
                .findFirst()
                .or(() -> agentRegistry.keySet().stream().findFirst())
                .orElse("default");
    }

    /**
     * 解析Agent的完整工作区目录。
     * 通常为: {workspaceRoot}/{agentDir}
     */
    public String resolveAgentWorkspaceDir(ResolvedAgentConfig config) {
        String root = !config.getWorkspaceDir().isEmpty()
                ? config.getWorkspaceDir()
                : defaults.getWorkspace();
        if (root == null || root.isEmpty()) {
            root = System.getProperty("user.dir");
        }
        String dir = !config.getAgentDir().isEmpty() ? config.getAgentDir() : config.getAgentId();
        return root.endsWith("/") ? root + dir : root + "/" + dir;
    }

    /**
     * 使配置缓存失效（在配置刷新事件时调用）。
     */
    public void invalidate() {
        cache.clear();
    }

    // ===== 私有解析辅助方法 =====

    /** 解析可为null的Object字段: 返回第一个非null/非空白的值。 */
    private String resolveString(String agentVal, String defaultsVal, String systemVal) {
        if (agentVal != null && !agentVal.isEmpty()) return agentVal;
        if (defaultsVal != null && !defaultsVal.isEmpty()) return defaultsVal;
        return systemVal != null ? systemVal : "";
    }

    private int resolveInt(int agentVal, int defaultsVal, int systemVal) {
        if (agentVal != 0) return agentVal;
        if (defaultsVal != 0) return defaultsVal;
        return systemVal;
    }

    /** 解析列表: 如果Agent级别非空则使用它，否则使用默认值。 */
    private List<String> resolveList(List<String> agentVal, List<String> defaultsVal) {
        if (agentVal != null && !agentVal.isEmpty()) return agentVal;
        return defaultsVal != null ? defaultsVal : List.of();
    }

    /** 不在AgentDefaultsConfig根级别的字段占位符。 */
    private String defaultsField(AgentDefaultsConfig d, String field) {
        if (d == null) return "";
        return switch (field) {
            case "name" -> "";
            default -> "";
        };
    }

    // ===== 数据记录 =====

    public record AgentEntry(String id, String name, String description) {}
}
```

### 1.1.7 YAML配置示例

```yaml
# application.yml — Agent配置

lyclaw:
  agent:
    # 所有Agent继承的全局默认值
    defaults:
      model: "deepseek-v4-flash"
      provider: "deepseek"
      fallbacks:
        - "deepseek-v4-pro"
        - "openai-gpt-4o"
      thinkingDefault: "off"
      verboseDefault: ""
      reasoningDefault: ""
      fastModeDefault: false
      contextInjection: "always"
      bootstrapMaxChars: 20000
      bootstrapTotalMaxChars: 150000
      contextTokens: 0
      skills: []
      sandbox: "none"
      workspace: "/var/lyclaw/workspaces"

      # 子Agent委托默认值
      subagents:
        delegationMode: "suggest"
        allowAgents: []           # 空 = 允许全部
        maxSpawnDepth: 1
        maxChildrenPerAgent: 5

      # 心跳检测: 对长时间运行的Agent进行周期性存活检查
      heartbeat:
        enabled: false
        intervalSeconds: 60
        maxIdleSeconds: 300

      # 模型失败时的运行重试
      runRetries:
        maxAttempts: 3
        baseDelayMs: 1000
        backoff: "exponential"

      # 上下文限制: 裁剪工具输出 / 内存以保持在窗口内
      contextLimits:
        memoryGetMaxChars: 50000
        toolResultMaxChars: 80000
        toolResultTotalMaxChars: 200000

    # 每个Agent的覆盖配置（遗留路径 "lyclaw.agents" — 保留用于向后兼容）
    agents:
      code-reviewer:
        systemPromptOverride: "你是一位专家级代码审查员。请彻底但简洁地审查。"
        model: "deepseek-v4-pro"
        thinkingDefault: "high"
        maxToolRounds: 20
```

### 1.1.8 注解使用示例

```java
package com.example.agents;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.annotation.agent.UserMessage;
import lyjew.com.lyclaw.annotation.agent.V;

/**
 * 代码审查Agent — 使用专业模型和高思考级别以输出高质量结果。
 */
@Agent(
    id          = "code-reviewer",
    defaultAgent = false,
    name        = "Code Reviewer",
    description = "审查代码变更中的错误、风格和安全问题",
    version     = "2.0.0",
    model       = "deepseek-v4-pro",
    thinkingDefault = "high",
    sandbox     = "docker",
    skills      = {"code-analysis", "security-scan"},
    delegationMode = "prefer",
    allowAgents = {"tester", "linter"},
    maxSpawnDepth = 2,
    maxChildrenPerAgent = 3,
    contextInjection = "always"
)
public interface CodeReviewerAgent {

    @UserMessage("审查以下代码变更:\n\n{{diff}}")
    String review(@V("diff") String diff);

    @UserMessage("审查仓库 {{repo}} 中的PR #{{prNumber}}")
    String reviewPullRequest(@V("prNumber") int prNumber, @V("repo") String repo);
}
```

---

## 1.2 AgentContext增强

### 1.2.1 问题

当前 `AgentContext` 是一个扁平POJO，具有 `sessionId`、`userMessage`、`systemPrompt`、`toolRegistry`、`method`、`args` 等字段，以及一些Pipeline状态的原子变量。它缺乏对Agent已解析配置的感知、没有工作区路径、没有运行时类型感知，也没有子Agent跟踪。

### 1.2.2 增强的 AgentContext

```java
package lyjew.com.lyclaw.react;

import lyjew.com.lyclaw.config.ResolvedAgentConfig;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.task.TaskNode;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.tracing.TraceContext;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;

/**
 * 增强的AgentContext — 所有Hook、阶段和运行时操作的统一数据总线。
 *
 * <h3>新增字段（第一阶段新增用粗体标出）:</h3>
 * <ul>
 *   <li><b>agentId, agentName</b> — 来自ResolvedAgentConfig</li>
 *   <li><b>workspaceDir, agentDir</b> — 已解析的文件系统路径</li>
 *   <li><b>resolvedConfig</b> — 完全合并的ResolvedAgentConfig</li>
 *   <li><b>bootstrapContent</b> — 加载的AGENTS.md、CLAUDE.md内容</li>
 *   <li><b>contextLimits</b> — 内存/工具结果大小上限</li>
 *   <li><b>thinkingLevel, verboseLevel, reasoningLevel</b> — 生效的级别</li>
 *   <li><b>delegationMode, allowAgents, maxSpawnDepth, maxChildrenPerAgent</b></li>
 *   <li><b>activeSubagentIds</b> — 跟踪生成的子Agent</li>
 *   <li><b>runtimeType</b> — EMBEDDED 或 ACP</li>
 *   <li><b>runMetadata</b> — runId, jobId, trigger, channelId</li>
 * </ul>
 */
public class AgentContext {

    public enum Lifecycle { TRANSIENT, SESSION, PERSISTENT }

    /**
     * 哪个运行时引擎支持此Agent调用。
     */
    public enum AgentRuntimeType {
        /** LyClaw内置的ReAct引擎。 */
        EMBEDDED,
        /** 通过Agent Communication Protocol的外部Agent后端。 */
        ACP
    }

    // ==================== Agent身份标识（新增） ====================

    private final String agentId;
    private final String agentName;
    private final ResolvedAgentConfig resolvedConfig;

    // ==================== 工作区（新增） ====================

    private final String workspaceDir;
    private final String agentDir;

    // ==================== 引导内容（新增） ====================

    /**
     * 从AGENTS.md、CLAUDE.md、system.md等加载的内容。
     * Key = 文件名, Value = 文件内容（截断至bootstrapMaxChars）。
     */
    private final Map<String, Object> bootstrapContent = new LinkedHashMap<>();

    // ==================== 上下文限制（新增） ====================

    /** 内存检索的最大字符数。 */
    private int memoryGetMaxChars = 50000;
    /** 单个工具结果的最大字符数。 */
    private int toolResultMaxChars = 80000;
    /** 所有工具结果合计的最大字符数。 */
    private int toolResultTotalMaxChars = 200000;

    // ==================== 思考 / 详细度 / 推理（新增） ====================

    private String thinkingLevel = "off";
    private String verboseLevel = "";
    private String reasoningLevel = "";

    // ==================== 子Agent委托（新增） ====================

    private String delegationMode = "suggest";
    private List<String> allowAgents = List.of();
    private int maxSpawnDepth = 1;
    private int maxChildrenPerAgent = 5;

    /** 跟踪此Agent当前正在运行的子Agent id。 */
    private final List<String> activeSubagentIds = new CopyOnWriteArrayList<>();

    // ==================== 运行时类型（新增） ====================

    private AgentRuntimeType runtimeType = AgentRuntimeType.EMBEDDED;

    // ==================== 运行元数据（新增） ====================

    /**
     * 关于运行的任意元数据: runId, jobId, trigger（如 "webhook"）,
     * channelId（如 Slack 频道）等。
     */
    private final Map<String, Object> runMetadata = new LinkedHashMap<>();

    // ==================== 遗留字段（未更改） ====================

    private final String sessionId;
    private String userMessage;
    private String systemPrompt;
    private ChatRequest chatRequest;
    private final ToolRegistry toolRegistry;
    private final Method method;
    private final Object[] args;
    private SandboxLevel sandboxLevel;
    private Lifecycle lifecycle = Lifecycle.TRANSIENT;

    private final TraceContext tracing;

    private final List<String> toolResults = new CopyOnWriteArrayList<>();
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private final List<TaskNode> nodes = new CopyOnWriteArrayList<>();
    private final AtomicReference<Double> reflectScoreRef = new AtomicReference<>(0.0);
    private final AtomicBoolean pipelineOk = new AtomicBoolean(false);
    private final AtomicLong respondStartMs = new AtomicLong();
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private final AtomicReference<String> currentStage = new AtomicReference<>("init");

    private final Map<String, Object> attributes = new HashMap<>();

    // ==================== 构造函数 ====================

    /**
     * 带ResolvedAgentConfig的完整构造函数。
     */
    public AgentContext(String sessionId, String userMessage, String systemPrompt,
                        ToolRegistry toolRegistry, Method method, Object[] args,
                        ResolvedAgentConfig resolvedConfig) {
        this.sessionId = sessionId;
        this.userMessage = userMessage;
        this.systemPrompt = systemPrompt;
        this.toolRegistry = toolRegistry;
        this.method = method;
        this.args = args;
        this.tracing = new TraceContext();

        // 从已解析配置填充
        this.resolvedConfig = resolvedConfig;
        this.agentId = resolvedConfig.getAgentId();
        this.agentName = resolvedConfig.getAgentName();
        this.workspaceDir = resolvedConfig.getWorkspaceDir();
        this.agentDir = resolvedConfig.getAgentDir();
        this.thinkingLevel = resolvedConfig.getThinkingDefault();
        this.verboseLevel = resolvedConfig.getVerboseDefault();
        this.reasoningLevel = resolvedConfig.getReasoningDefault();
        this.delegationMode = resolvedConfig.getDelegationMode();
        this.allowAgents = resolvedConfig.getAllowAgents();
        this.maxSpawnDepth = resolvedConfig.getMaxSpawnDepth();
        this.maxChildrenPerAgent = resolvedConfig.getMaxChildrenPerAgent();

        if (resolvedConfig.getContextLimits() != null) {
            this.memoryGetMaxChars = resolvedConfig.getContextLimits().getMemoryGetMaxChars();
            this.toolResultMaxChars = resolvedConfig.getContextLimits().getToolResultMaxChars();
            this.toolResultTotalMaxChars = resolvedConfig.getContextLimits().getToolResultTotalMaxChars();
        }
    }

    /** 向后兼容的构造函数（无ResolvedAgentConfig）。 */
    public AgentContext(String sessionId, String userMessage, String systemPrompt,
                        ToolRegistry toolRegistry, Method method, Object[] args) {
        this(sessionId, userMessage, systemPrompt, toolRegistry, method, args, null);
    }

    // ==================== 新增 Getters/Setters ====================

    public String getAgentId() { return agentId; }
    public String getAgentName() { return agentName; }
    public ResolvedAgentConfig getResolvedConfig() { return resolvedConfig; }
    public String getWorkspaceDir() { return workspaceDir; }
    public String getAgentDir() { return agentDir; }

    public Map<String, Object> getBootstrapContent() { return bootstrapContent; }
    public void addBootstrapContent(String filename, Object content) {
        this.bootstrapContent.put(filename, content);
    }

    public int getMemoryGetMaxChars() { return memoryGetMaxChars; }
    public void setMemoryGetMaxChars(int v) { this.memoryGetMaxChars = v; }
    public int getToolResultMaxChars() { return toolResultMaxChars; }
    public void setToolResultMaxChars(int v) { this.toolResultMaxChars = v; }
    public int getToolResultTotalMaxChars() { return toolResultTotalMaxChars; }
    public void setToolResultTotalMaxChars(int v) { this.toolResultTotalMaxChars = v; }

    public String getThinkingLevel() { return thinkingLevel; }
    public void setThinkingLevel(String v) { this.thinkingLevel = v; }
    public String getVerboseLevel() { return verboseLevel; }
    public void setVerboseLevel(String v) { this.verboseLevel = v; }
    public String getReasoningLevel() { return reasoningLevel; }
    public void setReasoningLevel(String v) { this.reasoningLevel = v; }

    public String getDelegationMode() { return delegationMode; }
    public void setDelegationMode(String v) { this.delegationMode = v; }
    public List<String> getAllowAgents() { return allowAgents; }
    public void setAllowAgents(List<String> v) { this.allowAgents = v; }
    public int getMaxSpawnDepth() { return maxSpawnDepth; }
    public void setMaxSpawnDepth(int v) { this.maxSpawnDepth = v; }
    public int getMaxChildrenPerAgent() { return maxChildrenPerAgent; }
    public void setMaxChildrenPerAgent(int v) { this.maxChildrenPerAgent = v; }

    public List<String> getActiveSubagentIds() { return activeSubagentIds; }
    public void addActiveSubagentId(String id) { this.activeSubagentIds.add(id); }
    public void removeActiveSubagentId(String id) { this.activeSubagentIds.remove(id); }

    public AgentRuntimeType getRuntimeType() { return runtimeType; }
    public void setRuntimeType(AgentRuntimeType v) { this.runtimeType = v; }

    public Map<String, Object> getRunMetadata() { return runMetadata; }
    public void setRunMetadata(String key, Object value) { this.runMetadata.put(key, value); }
    @SuppressWarnings("unchecked")
    public <T> T getRunMetadata(String key) { return (T) runMetadata.get(key); }

    // ==================== 遗留 Getters（未更改） ====================

    public String getSessionId() { return sessionId; }
    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String userMessage) { this.userMessage = userMessage; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public ChatRequest getChatRequest() { return chatRequest; }
    public void setChatRequest(ChatRequest chatRequest) { this.chatRequest = chatRequest; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public Method getMethod() { return method; }
    public Object[] getArgs() { return args; }
    public SandboxLevel getSandboxLevel() { return sandboxLevel; }
    public void setSandboxLevel(SandboxLevel sandboxLevel) { this.sandboxLevel = sandboxLevel; }
    public Lifecycle getLifecycle() { return lifecycle; }
    public void setLifecycle(Lifecycle lifecycle) { this.lifecycle = lifecycle; }
    public TraceContext getTracing() { return tracing; }
    public List<String> getToolResults() { return toolResults; }
    public void addToolResult(String result) { toolResults.add(result); }
    public AtomicInteger getSuccessCount() { return successCount; }
    public AtomicInteger getFailCount() { return failCount; }
    public List<TaskNode> getNodes() { return nodes; }
    public void addNode(TaskNode node) { nodes.add(node); }
    public AtomicReference<Double> getReflectScoreRef() { return reflectScoreRef; }
    public AtomicBoolean getPipelineOk() { return pipelineOk; }
    public boolean isPipelineOk() { return pipelineOk.get(); }
    public void setPipelineOk(boolean value) { pipelineOk.set(value); }
    public AtomicLong getRespondStartMs() { return respondStartMs; }
    public AtomicBoolean getTerminated() { return terminated; }
    public boolean isTerminated() { return terminated.get(); }
    public void setTerminated(boolean value) { terminated.set(value); }
    public AtomicReference<String> getCurrentStage() { return currentStage; }
    public <T> T getAttribute(String key) { return (T) attributes.get(key); }
    public void setAttribute(String key, Object value) { attributes.put(key, value); }
    public Map<String, Object> getAttributes() { return attributes; }

    // ==================== 增强的快照/恢复 ====================

    /**
     * 增强的快照 — 包含所有新字段。
     */
    public Map<String, Object> toSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();

        // 遗留
        snapshot.put("sessionId", sessionId);
        snapshot.put("userMessage", userMessage);
        snapshot.put("systemPrompt", systemPrompt);
        snapshot.put("sandboxLevel", sandboxLevel != null ? sandboxLevel.name() : null);
        snapshot.put("lifecycle", lifecycle.name());
        snapshot.put("currentStage", currentStage.get());
        snapshot.put("successCount", successCount.get());
        snapshot.put("failCount", failCount.get());
        snapshot.put("pipelineOk", pipelineOk.get());
        snapshot.put("terminated", terminated.get());
        snapshot.put("reflectScore", reflectScoreRef.get());
        snapshot.put("toolResults", new ArrayList<>(toolResults));
        snapshot.put("tracing", Map.of("traceId", tracing.getTraceId()));

        // 新增 — 身份标识
        snapshot.put("agentId", agentId);
        snapshot.put("agentName", agentName);

        // 新增 — 工作区
        snapshot.put("workspaceDir", workspaceDir);
        snapshot.put("agentDir", agentDir);

        // 新增 — 级别
        snapshot.put("thinkingLevel", thinkingLevel);
        snapshot.put("verboseLevel", verboseLevel);
        snapshot.put("reasoningLevel", reasoningLevel);

        // 新增 — 委托
        snapshot.put("delegationMode", delegationMode);
        snapshot.put("allowAgents", new ArrayList<>(allowAgents));
        snapshot.put("maxSpawnDepth", maxSpawnDepth);
        snapshot.put("maxChildrenPerAgent", maxChildrenPerAgent);

        // 新增 — 上下文限制
        snapshot.put("memoryGetMaxChars", memoryGetMaxChars);
        snapshot.put("toolResultMaxChars", toolResultMaxChars);
        snapshot.put("toolResultTotalMaxChars", toolResultTotalMaxChars);

        // 新增 — 运行时
        snapshot.put("runtimeType", runtimeType.name());
        snapshot.put("activeSubagentIds", new ArrayList<>(activeSubagentIds));
        snapshot.put("runMetadata", new HashMap<>(runMetadata));

        // 新增 — 引导内容
        snapshot.put("bootstrapContent", new HashMap<>(bootstrapContent));

        return snapshot;
    }

    /**
     * 从快照恢复。运行时引用（toolRegistry, method, args）
     * 必须由调用者重新注入。
     */
    @SuppressWarnings("unchecked")
    public void restoreFromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null) return;

        // 遗留
        if (snapshot.get("sandboxLevel") != null)
            this.sandboxLevel = SandboxLevel.valueOf((String) snapshot.get("sandboxLevel"));
        if (snapshot.get("lifecycle") != null)
            this.lifecycle = Lifecycle.valueOf((String) snapshot.get("lifecycle"));
        if (snapshot.get("currentStage") != null)
            this.currentStage.set((String) snapshot.get("currentStage"));
        if (snapshot.get("successCount") != null)
            this.successCount.set(((Number) snapshot.get("successCount")).intValue());
        if (snapshot.get("failCount") != null)
            this.failCount.set(((Number) snapshot.get("failCount")).intValue());
        if (snapshot.get("pipelineOk") != null)
            this.pipelineOk.set((Boolean) snapshot.get("pipelineOk"));
        if (snapshot.get("terminated") != null)
            this.terminated.set((Boolean) snapshot.get("terminated"));
        if (snapshot.get("reflectScore") != null)
            this.reflectScoreRef.set(((Number) snapshot.get("reflectScore")).doubleValue());
        if (snapshot.get("toolResults") instanceof List<?> list) {
            this.toolResults.clear();
            for (Object item : list) this.toolResults.add((String) item);
        }

        // 新增 — 身份标识
        if (snapshot.get("agentId") != null)
            this.setRunMetadata("restoredAgentId", snapshot.get("agentId"));

        // 新增 — 级别
        if (snapshot.get("thinkingLevel") != null)
            this.thinkingLevel = (String) snapshot.get("thinkingLevel");
        if (snapshot.get("verboseLevel") != null)
            this.verboseLevel = (String) snapshot.get("verboseLevel");
        if (snapshot.get("reasoningLevel") != null)
            this.reasoningLevel = (String) snapshot.get("reasoningLevel");

        // 新增 — 委托
        if (snapshot.get("delegationMode") != null)
            this.delegationMode = (String) snapshot.get("delegationMode");
        if (snapshot.get("allowAgents") instanceof List<?> al)
            this.allowAgents = al.stream().map(Object::toString).toList();
        if (snapshot.get("maxSpawnDepth") instanceof Number n)
            this.maxSpawnDepth = n.intValue();
        if (snapshot.get("maxChildrenPerAgent") instanceof Number n)
            this.maxChildrenPerAgent = n.intValue();

        // 新增 — 上下文限制
        if (snapshot.get("memoryGetMaxChars") instanceof Number n)
            this.memoryGetMaxChars = n.intValue();
        if (snapshot.get("toolResultMaxChars") instanceof Number n)
            this.toolResultMaxChars = n.intValue();
        if (snapshot.get("toolResultTotalMaxChars") instanceof Number n)
            this.toolResultTotalMaxChars = n.intValue();

        // 新增 — 运行时
        if (snapshot.get("runtimeType") != null)
            this.runtimeType = AgentRuntimeType.valueOf((String) snapshot.get("runtimeType"));
        if (snapshot.get("activeSubagentIds") instanceof List<?> sl) {
            this.activeSubagentIds.clear();
            for (Object item : sl) this.activeSubagentIds.add((String) item);
        }
        if (snapshot.get("runMetadata") instanceof Map<?, ?> rm) {
            this.runMetadata.clear();
            for (Map.Entry<?, ?> e : rm.entrySet())
                this.runMetadata.put((String) e.getKey(), e.getValue());
        }
        if (snapshot.get("bootstrapContent") instanceof Map<?, ?> bc) {
            this.bootstrapContent.clear();
            for (Map.Entry<?, ?> e : bc.entrySet())
                this.bootstrapContent.put((String) e.getKey(), e.getValue());
        }
    }

    // ==================== 工厂方法 ====================

    public static AgentContext sessionScoped(String sessionId, String userMessage,
                                             String systemPrompt, ToolRegistry toolRegistry,
                                             Method method, Object[] args,
                                             ResolvedAgentConfig resolvedConfig) {
        AgentContext ctx = new AgentContext(sessionId, userMessage, systemPrompt,
                toolRegistry, method, args, resolvedConfig);
        ctx.setLifecycle(Lifecycle.SESSION);
        return ctx;
    }

    public static AgentContext persistentScoped(String sessionId, String userMessage,
                                                 String systemPrompt, ToolRegistry toolRegistry,
                                                 Method method, Object[] args,
                                                 ResolvedAgentConfig resolvedConfig) {
        AgentContext ctx = new AgentContext(sessionId, userMessage, systemPrompt,
                toolRegistry, method, args, resolvedConfig);
        ctx.setLifecycle(Lifecycle.PERSISTENT);
        return ctx;
    }
}
```

---

## 1.3 Hook系统扩展（从5个到36个Hook）

### 1.3.1 问题

当前 `AgentHook` 只有5个扩展点：`beforeRequest`、`beforeModel`、`afterModel`、`wrapToolCall`、`wrapToolExecutor`、`afterResult`。无法Hook到会话生命周期、Agent启动/结束、子Agent生成、压缩、消息事件或心跳贡献。

### 1.3.2 完整的Hook接口

```java
package lyjew.com.lyclaw.react;

import java.util.List;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ToolCall;

/**
 * 完整的Agent生命周期Hook SPI — 36个扩展点。
 *
 * <p>所有方法都是默认（无操作），因此实现者只需覆盖所需的方法。
 * Hook由 {@link AgentInvocationHandler} 在Agent生命周期的适当节点进行分发。
 *
 * <h3>执行顺序</h3>
 * <p>Hook在分发前按 {@link #getOrder()}（升序）排序。
 * 默认顺序为 100。</p>
 */
public interface AgentHook {

    // =====================================================================
    // 现有方法（保留用于向后兼容）
    // =====================================================================

    /** 在整个Agent调用Pipeline开始之前。
     *  抛出异常将中止请求。 */
    default void beforeRequest(AgentContext ctx) {}

    /** 每次LLM调用之前。可注入规划上下文或调整消息。 */
    default List<Message> beforeModel(List<Message> messages, AgentContext ctx) {
        return messages;
    }

    /** 每次LLM响应之后。可检测有害内容、记录日志或转换输出。 */
    default String afterModel(String response, AgentContext ctx) {
        return response;
    }

    /** 包装单个工具调用（比 wrapToolExecutor 更细粒度）。 */
    default ToolCall wrapToolCall(ToolCall toolCall, AgentContext ctx) {
        return toolCall;
    }

    /** 包装 ToolExecutor，形成装饰器链。 */
    default ToolExecutor wrapToolExecutor(ToolExecutor inner, AgentContext ctx) {
        return inner;
    }

    /** 最终结果之后，返回给调用者之前。
     *  按逆序分发（afterResult Hook从高到低执行）。 */
    default String afterResult(String result, AgentContext ctx) {
        return result;
    }

    /** 优先级。较小数字先执行。默认: 100。 */
    default int getOrder() { return 100; }

    // =====================================================================
    // 新增 — 模型生命周期
    // =====================================================================

    /** 模型解析（提供商 + 模型选择）之前。 */
    default void beforeModelResolve(AgentContext ctx) {}

    /** 当模型调用开始时调用（路由之后、API调用之前）。 */
    default void modelCallStarted(AgentContext ctx) {}

    /** 当模型调用结束时调用（成功或失败）。 */
    default void modelCallEnded(AgentContext ctx) {}

    /** 原始LLM输入（发送给模型的最终组装提示词）。 */
    default void llmInput(String prompt, AgentContext ctx) {}

    /** 原始LLM输出（完整的模型响应，解析之前）。 */
    default void llmOutput(String response, AgentContext ctx) {}

    // =====================================================================
    // 新增 — Agent生命周期
    // =====================================================================

    /** Agent运行开始之前（Pipeline入口）。 */
    default void beforeAgentStart(AgentContext ctx) {}

    /**
     * Agent回复发送回调用者之前。
     * @param reply 草稿回复文本
     * @param ctx Agent上下文
     */
    default void beforeAgentReply(String reply, AgentContext ctx) {}

    /**
     * Agent最终化之前（ReAct循环结束后、清理之前）。
     * 可返回 CONTINUE（默认）、REVISE（带指令重试）或 FINALIZE（跳过修订）的决策。
     */
    default AgentFinalizeResult beforeAgentFinalize(AgentContext ctx) {
        return AgentFinalizeResult.continue_();
    }

    /** Agent运行完成之后（清理、指标收集、通知）。 */
    default void agentEnd(AgentContext ctx) {}

    /** 每次单独的Agent调用之前（代理上的每个方法调用）。 */
    default void beforeAgentRun(AgentContext ctx) {}

    // =====================================================================
    // 新增 — 工具生命周期
    // =====================================================================

    /** 工具调用之前。包含工具名称、调用ID、序列化参数。 */
    default void beforeToolCall(String toolName, String toolCallId, String args, AgentContext ctx) {}

    /** 工具完成之后。包含结果字符串（可能为错误）。 */
    default void afterToolCall(String toolName, String toolCallId, String result, AgentContext ctx) {}

    /** 工具结果持久化到消息历史之后。 */
    default void toolResultPersist(String toolName, String result, AgentContext ctx) {}

    // =====================================================================
    // 新增 — 会话生命周期
    // =====================================================================

    /** 当新的Agent会话创建时。 */
    default void sessionStart(String sessionId, AgentContext ctx) {}

    /** 当Agent会话结束时（正常关闭或超时）。 */
    default void sessionEnd(String sessionId, AgentContext ctx) {}

    // =====================================================================
    // 新增 — 子Agent生命周期
    // =====================================================================

    /** 子Agent生成之前。Hook可通过抛异常来阻止。 */
    default void subagentSpawning(String childAgentId, String task, AgentContext ctx) {}

    /** 子Agent成功生成并创建会话之后。 */
    default void subagentSpawned(String childAgentId, String sessionKey, AgentContext ctx) {}

    /** 子Agent完成之后（成功或失败）。 */
    default void subagentEnded(String childAgentId, String outcome, AgentContext ctx) {}

    // =====================================================================
    // 新增 — 压缩
    // =====================================================================

    /** 消息历史压缩之前（上下文窗口管理）。 */
    default void beforeCompaction(AgentContext ctx) {}

    /** 消息历史压缩之后。 */
    default void afterCompaction(AgentContext ctx) {}

    // =====================================================================
    // 新增 — 消息生命周期
    // =====================================================================

    /** 从调用者/用户收到一条消息。 */
    default void messageReceived(Message msg, AgentContext ctx) {}

    /** Agent即将发送一条消息（LLM调用之前）。 */
    default void messageSending(String msg, AgentContext ctx) {}

    /** 一条消息已发送给调用者。 */
    default void messageSent(String msg, AgentContext ctx) {}

    // =====================================================================
    // 新增 — 心跳检测
    // =====================================================================

    /**
     * 向发送给LLM的周期性心跳提示提供贡献内容，
     * 用于保持长时间运行的Agent存活并知晓其上下文。
     * @return 贡献字符串（追加到心跳提示），或 "" 表示无贡献。
     */
    default String heartbeatPromptContribution(AgentContext ctx) { return ""; }
}
```

### 1.3.3 AgentFinalizeResult

```java
package lyjew.com.lyclaw.react;

/**
 * 由 {@link AgentHook#beforeAgentFinalize(AgentContext)} 返回。
 * 控制Agent运行是已完成的、需要修订还是应即刻最终化。
 */
public class AgentFinalizeResult {

    public enum Action {
        /** 正常继续 — 进行最终化并返回结果。 */
        CONTINUE,
        /** 修订 — 使用retryInstruction重新循环到respond阶段。 */
        REVISE,
        /** 即刻最终化 — 跳过任何剩余的修订逻辑。 */
        FINALIZE
    }

    private final Action action;
    private final String reason;
    private final String retryInstruction;
    private final String idempotencyKey;
    private final int maxAttempts;

    private AgentFinalizeResult(Action action, String reason, String retryInstruction,
                                String idempotencyKey, int maxAttempts) {
        this.action = action;
        this.reason = reason;
        this.retryInstruction = retryInstruction;
        this.idempotencyKey = idempotencyKey;
        this.maxAttempts = maxAttempts;
    }

    // ===== 工厂方法 =====

    public static AgentFinalizeResult continue_() {
        return new AgentFinalizeResult(Action.CONTINUE, null, null, null, 1);
    }

    public static AgentFinalizeResult revise(String reason, String retryInstruction) {
        return new AgentFinalizeResult(Action.REVISE, reason, retryInstruction, null, 3);
    }

    public static AgentFinalizeResult revise(String reason, String retryInstruction,
                                              String idempotencyKey, int maxAttempts) {
        return new AgentFinalizeResult(Action.REVISE, reason, retryInstruction,
                idempotencyKey, maxAttempts);
    }

    public static AgentFinalizeResult finalize(String reason) {
        return new AgentFinalizeResult(Action.FINALIZE, reason, null, null, 1);
    }

    // ===== Getters =====

    public Action getAction() { return action; }
    public String getReason() { return reason; }
    public String getRetryInstruction() { return retryInstruction; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public int getMaxAttempts() { return maxAttempts; }

    public boolean isContinue() { return action == Action.CONTINUE; }
    public boolean isRevise() { return action == Action.REVISE; }
    public boolean isFinalize() { return action == Action.FINALIZE; }
}
```

### 1.3.4 HookDecision（安全/审批）

```java
package lyjew.com.lyclaw.react;

import java.util.Map;

/**
 * 由控制执行门控的Hook返回的阻止/审批决策。
 * 供安全Hook、审批Hook等使用。
 */
public class HookDecision {

    public enum Outcome {
        /** 允许继续执行。 */
        PASS,
        /** 阻止执行。 */
        BLOCK
    }

    private final Outcome outcome;
    private final String reason;
    private final String message;       // 面向用户的消息
    private final String category;      // 如 "security", "approval", "rate-limit"
    private final Map<String, Object> metadata;

    private HookDecision(Outcome outcome, String reason, String message,
                         String category, Map<String, Object> metadata) {
        this.outcome = outcome;
        this.reason = reason;
        this.message = message;
        this.category = category;
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public static HookDecision pass() {
        return new HookDecision(Outcome.PASS, null, null, null, null);
    }

    public static HookDecision block(String reason, String message, String category) {
        return new HookDecision(Outcome.BLOCK, reason, message, category, null);
    }

    public static HookDecision block(String reason, String message, String category,
                                      Map<String, Object> metadata) {
        return new HookDecision(Outcome.BLOCK, reason, message, category, metadata);
    }

    public Outcome getOutcome() { return outcome; }
    public String getReason() { return reason; }
    public String getMessage() { return message; }
    public String getCategory() { return category; }
    public Map<String, Object> getMetadata() { return metadata; }
    public boolean isPass() { return outcome == Outcome.PASS; }
    public boolean isBlock() { return outcome == Outcome.BLOCK; }
}
```

### 1.3.5 HookRegistration（注册表条目）

```java
package lyjew.com.lyclaw.react;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * {@link HookRegistry}中已注册的Hook条目。
 *
 * @param pluginId    注册此Hook的插件/模块
 * @param hookName    Hook方法名称（如 "beforeModel", "afterToolCall"）
 * @param handler     处理器函数（签名因Hook而异）
 * @param priority    执行优先级（越小 = 越早）
 * @param timeoutMs   在Hook被视为挂起之前的最大执行时间（0 = 无超时）
 * @param source      Hook的注册方式（annotation, SPI, programmatic）
 */
public record HookRegistration(
        String pluginId,
        String hookName,
        Object handler,          // Function 或 BiConsumer，取决于Hook类型
        int priority,
        long timeoutMs,
        String source            // "annotation", "spi", "programmatic"
) {
    public HookRegistration {
        if (pluginId == null || pluginId.isBlank()) pluginId = "unknown";
        if (hookName == null || hookName.isBlank()) throw new IllegalArgumentException("hookName is required");
        if (handler == null) throw new IllegalArgumentException("handler is required");
        if (timeoutMs < 0) timeoutMs = 0;
        if (source == null || source.isBlank()) source = "programmatic";
    }

    public static HookRegistration of(String pluginId, String hookName,
                                       Object handler, int priority) {
        return new HookRegistration(pluginId, hookName, handler, priority, 0, "programmatic");
    }
}
```

### 1.3.6 HookRegistry

```java
package lyjew.com.lyclaw.react;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 用于Hook管理和分发的中央注册表。
 *
 * <p>Hook按Hook名称（如 "beforeModel", "afterToolCall"）分组。
 * 分发时按优先级（升序）排序并按顺序调用。
 */
public class HookRegistry {

    private static final Logger log = LoggerFactory.getLogger(HookRegistry.class);

    /** hookName → 已排序的注册列表。 */
    private final Map<String, List<HookRegistration>> registrations = new ConcurrentHashMap<>();

    /**
     * 注册一个Hook。如果Hook名称是新的，则创建列表。
     * 同一Hook名称的注册保持按优先级排序。
     */
    public void register(HookRegistration reg) {
        registrations.compute(reg.hookName(), (k, list) -> {
            if (list == null) list = new CopyOnWriteArrayList<>();
            list.add(reg);
            list.sort(Comparator.comparingInt(HookRegistration::priority));
            return list;
        });
        log.debug("Hook registered: pluginId={} hookName={} priority={}",
                reg.pluginId(), reg.hookName(), reg.priority());
    }

    /**
     * 注销给定插件的所有Hook。
     */
    public void unregisterPlugin(String pluginId) {
        registrations.forEach((hookName, list) ->
                list.removeIf(reg -> reg.pluginId().equals(pluginId)));
    }

    /**
     * 获取某个Hook名称的所有注册，按优先级排序。
     */
    public List<HookRegistration> getHooks(String hookName) {
        return registrations.getOrDefault(hookName, List.of());
    }

    /**
     * 获取所有已注册的Hook名称。
     */
    public Set<String> getHookNames() {
        return Collections.unmodifiableSet(registrations.keySet());
    }

    /**
     * 清除所有注册。
     */
    public void clear() {
        registrations.clear();
    }
}
```

### 1.3.7 AgentInvocationHandler — Hook分发更新

现有的 `AgentInvocationHandler` 更新为在生命周期的正确节点分发新的Hook：

```java
// AgentInvocationHandler.invoke() 内部 — Hook分发新增内容的伪代码:

@Override
public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    // ... (现有设置: 解析消息, 构建上下文, 创建 AgentContext) ...

    AgentContext ctx = new AgentContext(sessionId, userMessage, systemPrompt,
            toolRegistry, method, args, resolvedConfig);

    // 新增: 分发 beforeAgentStart + beforeAgentRun
    dispatch("beforeAgentStart", ctx);
    dispatch("beforeAgentRun", ctx);

    // 新增: 分发 sessionStart（每个会话一次）
    dispatch("sessionStart", ctx.getSessionId(), ctx);

    // 1. beforeRequest hooks（遗留，保留用于向后兼容）
    List<AgentHook> sorted = sortedHooks();
    for (AgentHook hook : sorted) {
        hook.beforeRequest(ctx);
    }

    // ... (现有阶段Pipeline或ReAct执行) ...

    // 在ReAct循环内，围绕每次模型调用:

    // 新增: beforeModelResolve
    dispatch("beforeModelResolve", ctx);

    // 新增: modelCallStarted
    dispatch("modelCallStarted", ctx);

    // 遗留: beforeModel（保留）
    for (AgentHook hook : sorted) {
        messages = hook.beforeModel(messages, ctx);
    }

    // 新增: llmInput
    dispatch("llmInput", assembledPrompt, ctx);

    // ... (实际LLM调用) ...

    // 新增: llmOutput
    dispatch("llmOutput", response, ctx);

    // 遗留: afterModel（保留）
    for (AgentHook hook : sorted) {
        response = hook.afterModel(response, ctx);
    }

    // 新增: modelCallEnded
    dispatch("modelCallEnded", ctx);

    // 围绕ReAct循环中每次工具调用:

    // 新增: beforeToolCall
    dispatch("beforeToolCall", toolName, toolCallId, argsJson, ctx);

    // ... (实际工具执行) ...

    // 新增: afterToolCall
    dispatch("afterToolCall", toolName, toolCallId, result, ctx);

    // 新增: toolResultPersist
    dispatch("toolResultPersist", toolName, result, ctx);

    // ReAct循环结束后（返回结果之前）:

    // 新增: beforeAgentFinalize — 允许REVISE门控
    AgentFinalizeResult finalizeResult = dispatchFinalize(ctx);
    if (finalizeResult.isRevise()) {
        // 使用 retryInstruction 重新循环到ReAct
    }

    // 遗留: afterResult（保留，逆序）
    for (int i = sorted.size() - 1; i >= 0; i--) {
        result = sorted.get(i).afterResult(result, ctx);
    }

    // 新增: agentEnd
    dispatch("agentEnd", ctx);

    // 新增: sessionEnd（如果会话正在结束）
    dispatch("sessionEnd", ctx.getSessionId(), ctx);

    return result;
}
```

AgentInvocationHandler中使用的分发辅助方法：

```java
// 根据Hook名称通用分发 — 对新增Hook使用HookRegistry，
// 对遗留SPI方法使用直接AgentHook调用。

private void dispatch(String hookName, Object... args) {
    List<HookRegistration> hooks = hookRegistry.getHooks(hookName);
    for (HookRegistration reg : hooks) {
        try {
            // 调用处理器（类型安全分发）
            invokeHandler(reg, args);
        } catch (Exception e) {
            log.warn("Hook {} (plugin={}) failed: {}", hookName, reg.pluginId(), e.getMessage());
            // Hook失败默认是非致命的；SecurityHook可抛异常来阻止
        }
    }
}

private AgentFinalizeResult dispatchFinalize(AgentContext ctx) {
    List<HookRegistration> hooks = hookRegistry.getHooks("beforeAgentFinalize");
    for (HookRegistration reg : hooks) {
        try {
            @SuppressWarnings("unchecked")
            Function<AgentContext, AgentFinalizeResult> handler =
                    (Function<AgentContext, AgentFinalizeResult>) reg.handler();
            AgentFinalizeResult result = handler.apply(ctx);
            if (result.isRevise() || result.isFinalize()) {
                return result; // 第一个非CONTINUE立即短路返回
            }
        } catch (Exception e) {
            log.warn("Finalize hook {} (plugin={}) failed: {}",
                    reg.hookName(), reg.pluginId(), e.getMessage());
        }
    }
    return AgentFinalizeResult.continue_();
}
```

### 1.3.8 示例：迁移现有Hook

现有的Hook如 `SecurityCheckHook`、`ApprovalHook`、`OutputGuardHook`、`PlanningHook`、`SandboxHook` 继续实现 `AgentHook`，行为完全相同。针对特定生命周期节点的新Hook通过 `HookRegistry` 注册：

```java
package com.example.hooks;

import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.HookRegistration;
import lyjew.com.lyclaw.react.HookRegistry;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

/**
 * 示例: 一个压缩日志记录Hook，用于追踪上下文压缩的发生。
 * 通过HookRegistry编程方式注册，而非实现AgentHook。
 */
@Component
public class CompactionLogger {

    private final HookRegistry hookRegistry;

    public CompactionLogger(HookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry;
    }

    @PostConstruct
    public void registerHooks() {
        hookRegistry.register(HookRegistration.of(
                "compaction-logger",
                "beforeCompaction",
                (java.util.function.Consumer<AgentContext>) ctx -> {
                    // 在压缩之前记录上下文大小
                },
                200
        ));

        hookRegistry.register(HookRegistration.of(
                "compaction-logger",
                "afterCompaction",
                (java.util.function.Consumer<AgentContext>) ctx -> {
                    // 在压缩之后记录上下文大小
                },
                200
        ));
    }
}
```

---

## 1.4 AgentRuntime 模式

### 1.4.1 问题

LyClaw当前仅支持EMBEDDED模式（内置ReAct引擎）。OpenClaw支持ACP（Agent Communication Protocol）模式，其中Agent后端在外部进程（如Node.js Codex CLI实例）中运行，并通过双向协议进行通信。添加ACP支持需要一个清晰的抽象。

### 1.4.2 AgentRuntimeType 枚举

```java
package lyjew.com.lyclaw.react;

/**
 * 支持Agent调用的运行时模式。
 */
public enum AgentRuntimeType {

    /**
     * 默认模式 — LyClaw的内置ReAct引擎在内部处理
     * 完整的推理-行动循环。
     */
    EMBEDDED,

    /**
     * Agent Communication Protocol模式 — Agent后端在外部进程中运行。
     * LyClaw通过双向协议（事件、回合、会话）与其通信。
     */
    ACP
}
```

### 1.4.3 AcpRuntime 接口

```java
package lyjew.com.lyclaw.react;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Map;

/**
 * ACP (Agent Communication Protocol) 运行时SPI。
 *
 * <p>实现管理外部Agent后端（如Codex CLI、自定义Agent服务器）的会话和回合。
 * 协议为:
 * <ol>
 *   <li>{@link #ensureSession(AcpRuntimeEnsureInput)} — 获取或创建会话</li>
 *   <li>{@link #startTurn(AcpRuntimeTurnInput)} — 开始一个对话回合，
 *       接收事件流（文本增量、工具调用、状态更新）</li>
 *   <li>{@link #cancel(AcpRuntimeHandle, String)} — 取消正在运行的回合</li>
 *   <li>{@link #close(AcpRuntimeHandle, String)} — 销毁会话</li>
 * </ol>
 */
public interface AcpRuntime {

    /**
     * 确保给定Agent + 会话键存在一个会话。
     * 返回可用于后续turn/cancel/close调用的句柄。
     */
    Mono<AcpRuntimeHandle> ensureSession(AcpRuntimeEnsureInput input);

    /**
     * 开始一个对话回合。返回 AcpRuntimeEvent 的 Flux:
     * text_delta（流式令牌）, tool_call, tool_result, status, done, error。
     */
    Flux<AcpRuntimeEvent> startTurn(AcpRuntimeTurnInput input);

    /**
     * 查询后端的能（模型、工具、特性）。
     */
    Mono<AcpRuntimeCapabilities> getCapabilities(AcpRuntimeHandle handle);

    /**
     * 取消正在进行的回合。
     */
    Mono<Void> cancel(AcpRuntimeHandle handle, String reason);

    /**
     * 关闭（销毁）一个会话。
     */
    Mono<Void> close(AcpRuntimeHandle handle, String reason);
}
```

### 1.4.4 AcpRuntimeHandle

```java
package lyjew.com.lyclaw.react;

/**
 * 指向活跃ACP会话的不透明句柄。
 *
 * <p>包含AcpRuntime实现所需的标识符，用于将
 * 后续turn/cancel/close请求路由到正确的后端会话。
 */
public class AcpRuntimeHandle {

    /** 会话创建时使用的会话键。 */
    private final String sessionKey;

    /** 此会话所在的后端（如 "codex-cli", "custom-agent-server"）。 */
    private final String backend;

    /** 运行时级别会话名称（可能与面向用户的会话键不同）。 */
    private final String runtimeSessionName;

    /** 此会话的工作目录。 */
    private final String cwd;

    /** 后端特定的会话标识符（如进程PID或UUID）。 */
    private final String backendSessionId;

    /** LyClaw级别的Agent会话标识符。 */
    private final String agentSessionId;

    public AcpRuntimeHandle(String sessionKey, String backend, String runtimeSessionName,
                            String cwd, String backendSessionId, String agentSessionId) {
        this.sessionKey = sessionKey;
        this.backend = backend;
        this.runtimeSessionName = runtimeSessionName;
        this.cwd = cwd;
        this.backendSessionId = backendSessionId;
        this.agentSessionId = agentSessionId;
    }

    public String getSessionKey() { return sessionKey; }
    public String getBackend() { return backend; }
    public String getRuntimeSessionName() { return runtimeSessionName; }
    public String getCwd() { return cwd; }
    public String getBackendSessionId() { return backendSessionId; }
    public String getAgentSessionId() { return agentSessionId; }
}
```

### 1.4.5 AcpRuntimeEvent

```java
package lyjew.com.lyclaw.react;

import java.util.Map;

/**
 * ACP回合期间发出的事件。
 *
 * <p>事件通过 {@link AcpRuntime#startTurn(AcpRuntimeTurnInput)} 以 Flux 形式流式传输。
 */
public class AcpRuntimeEvent {

    public enum EventType {
        /** 文本内容增量（流式令牌）。 */
        TEXT_DELTA,
        /** 后端想要调用工具。 */
        TOOL_CALL,
        /** 发送回后端的工具结果。 */
        TOOL_RESULT,
        /** 状态更新（如 "thinking", "executing tool"）。 */
        STATUS,
        /** 回合成功完成。 */
        DONE,
        /** 回合失败，带有错误。 */
        ERROR
    }

    private final EventType type;
    private final String data;
    private final Map<String, Object> metadata;

    public AcpRuntimeEvent(EventType type, String data, Map<String, Object> metadata) {
        this.type = type;
        this.data = data;
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    // ===== 工厂方法 =====

    public static AcpRuntimeEvent textDelta(String text) {
        return new AcpRuntimeEvent(EventType.TEXT_DELTA, text, null);
    }

    public static AcpRuntimeEvent toolCall(String toolName, String toolCallId,
                                            String arguments, Map<String, Object> metadata) {
        return new AcpRuntimeEvent(EventType.TOOL_CALL,
                toolName,  // data携带工具名称; metadata包含id和参数
                Map.of("toolCallId", toolCallId, "arguments", arguments));
    }

    public static AcpRuntimeEvent toolResult(String toolCallId, String result, boolean success) {
        return new AcpRuntimeEvent(EventType.TOOL_RESULT, result,
                Map.of("toolCallId", toolCallId, "success", success));
    }

    public static AcpRuntimeEvent status(String status) {
        return new AcpRuntimeEvent(EventType.STATUS, status, null);
    }

    public static AcpRuntimeEvent done(String stopReason) {
        return new AcpRuntimeEvent(EventType.DONE, null,
                Map.of("stopReason", stopReason));
    }

    public static AcpRuntimeEvent error(String errorMessage) {
        return new AcpRuntimeEvent(EventType.ERROR, errorMessage, null);
    }

    // ===== Getters =====

    public EventType getType() { return type; }
    public String getData() { return data; }
    public Map<String, Object> getMetadata() { return metadata; }

    public boolean isTextDelta() { return type == EventType.TEXT_DELTA; }
    public boolean isToolCall() { return type == EventType.TOOL_CALL; }
    public boolean isDone() { return type == EventType.DONE; }
    public boolean isError() { return type == EventType.ERROR; }
}
```

### 1.4.6 支持类型

```java
package lyjew.com.lyclaw.react;

import java.util.Map;

/**
 * {@link AcpRuntime#ensureSession(AcpRuntimeEnsureInput)} 的输入。
 */
public class AcpRuntimeEnsureInput {
    private String agentId;
    private String sessionKey;
    private String backend;        // 使用哪个后端实现
    private String workspaceDir;
    private Map<String, Object> env;
    private Map<String, Object> extra;  // 后端特定的选项

    // 为简洁省略构造函数、getter、setter
    public AcpRuntimeEnsureInput() {}

    public String getAgentId() { return agentId; }
    public void setAgentId(String v) { this.agentId = v; }
    public String getSessionKey() { return sessionKey; }
    public void setSessionKey(String v) { this.sessionKey = v; }
    public String getBackend() { return backend; }
    public void setBackend(String v) { this.backend = v; }
    public String getWorkspaceDir() { return workspaceDir; }
    public void setWorkspaceDir(String v) { this.workspaceDir = v; }
    public Map<String, Object> getEnv() { return env; }
    public void setEnv(Map<String, Object> v) { this.env = v; }
    public Map<String, Object> getExtra() { return extra; }
    public void setExtra(Map<String, Object> v) { this.extra = v; }
}
```

```java
package lyjew.com.lyclaw.react;

import java.util.Map;

/**
 * {@link AcpRuntime#startTurn(AcpRuntimeTurnInput)} 的输入。
 */
public class AcpRuntimeTurnInput {
    private AcpRuntimeHandle handle;
    private String userMessage;
    private String systemPrompt;
    private Map<String, Object> context;  // 附加上下文

    // getters/setters
    public AcpRuntimeHandle getHandle() { return handle; }
    public void setHandle(AcpRuntimeHandle v) { this.handle = v; }
    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String v) { this.userMessage = v; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String v) { this.systemPrompt = v; }
    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> v) { this.context = v; }
}
```

```java
package lyjew.com.lyclaw.react;

import java.util.List;
import java.util.Map;

/**
 * ACP运行时报告的后端能力。
 */
public class AcpRuntimeCapabilities {
    private String modelProvider;
    private String modelName;
    private List<String> availableTools;
    private Map<String, Object> features;  // 任意特性标志

    // getters/setters
    public String getModelProvider() { return modelProvider; }
    public void setModelProvider(String v) { this.modelProvider = v; }
    public String getModelName() { return modelName; }
    public void setModelName(String v) { this.modelName = v; }
    public List<String> getAvailableTools() { return availableTools; }
    public void setAvailableTools(List<String> v) { this.availableTools = v; }
    public Map<String, Object> getFeatures() { return features; }
    public void setFeatures(Map<String, Object> v) { this.features = v; }
}
```

```java
package lyjew.com.lyclaw.react;

/**
 * 完成的ACP回结果。
 */
public class AcpRuntimeTurnResult {

    public enum Status {
        COMPLETED,   // 回合正常完成
        CANCELLED,   // 回合被用户或系统取消
        FAILED       // 回合失败，带有错误
    }

    private final Status status;
    private final String stopReason;
    private final String error;
    private final String fullText;  // 累积的文本输出

    public AcpRuntimeTurnResult(Status status, String stopReason, String error, String fullText) {
        this.status = status;
        this.stopReason = stopReason;
        this.error = error;
        this.fullText = fullText;
    }

    public Status getStatus() { return status; }
    public String getStopReason() { return stopReason; }
    public String getError() { return error; }
    public String getFullText() { return fullText; }
}
```

---

## 1.5 AgentProxyFactory 重构

### 1.5.1 问题

当前 `AgentProxyFactory` 使用了层层叠加的构造函数链（5个构造函数），将 `modelOverride`/`providerOverride` 硬编码为扁平字符串。它缺乏对 `AgentDefaultsConfig` 的感知，不产生 `ResolvedAgentConfig`，也没有运行时类型选择的概念。

### 1.5.2 重构后的 AgentProxyFactory

```java
package lyjew.com.lyclaw.react;

import java.lang.reflect.Proxy;
import java.util.List;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.config.AgentDefaultsConfig;
import lyjew.com.lyclaw.config.AgentConfigResolver;
import lyjew.com.lyclaw.config.ResolvedAgentConfig;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.tool.ToolRegistry;

/**
 * Agent代理工厂 — 为 @Agent 接口创建JDK动态代理。
 *
 * <h3>第一阶段变更:</h3>
 * <ul>
 *   <li>构造函数中接受 {@link AgentDefaultsConfig}</li>
 *   <li>{@code create(Class)} 读取 @Agent 注解 → 针对默认值解析
 *       → 生成 {@link ResolvedAgentConfig}</li>
 *   <li>将 ResolvedAgentConfig 传递给 AgentInvocationHandler</li>
 *   <li>支持创建不同运行时类型（EMBEDDED vs ACP）的Agent代理</li>
 * </ul>
 */
public class AgentProxyFactory {

    private final ChatFacade chatFacade;
    private final ReActEngine reActEngine;
    private final ToolRegistry toolRegistry;
    private final AgentConfigResolver configResolver;
    private final String defaultSystemPrompt;
    private final List<AgentHook> hooks;
    private final List<ReactivePipelineStage> stages;
    private final HookRegistry hookRegistry;

    /**
     * 主构造函数 — 接受完整的依赖集。
     *
     * @param chatFacade        用于LLM调用的Chat门面
     * @param reActEngine       用于EMBEDDED运行时的ReAct引擎
     * @param toolRegistry      工具注册表
     * @param configResolver    已加载默认值的Agent配置解析器
     * @param defaultSystemPrompt 未指定时的回退系统提示词
     * @param hooks             全局Agent Hook（应用于所有Agent）
     * @param stages            Pipeline阶段
     * @param hookRegistry      用于新式Hook分发的Hook注册表
     */
    public AgentProxyFactory(ChatFacade chatFacade, ReActEngine reActEngine,
                             ToolRegistry toolRegistry,
                             AgentConfigResolver configResolver,
                             String defaultSystemPrompt,
                             List<AgentHook> hooks,
                             List<ReactivePipelineStage> stages,
                             HookRegistry hookRegistry) {
        this.chatFacade = chatFacade;
        this.reActEngine = reActEngine;
        this.toolRegistry = toolRegistry;
        this.configResolver = configResolver;
        this.defaultSystemPrompt = defaultSystemPrompt;
        this.hooks = hooks != null ? List.copyOf(hooks) : List.of();
        this.stages = stages != null ? List.copyOf(stages) : List.of();
        this.hookRegistry = hookRegistry;
    }

    /**
     * 向后兼容的构造函数 — 无独立的配置解析器。
     * 从提供的默认值创建内联解析器。
     */
    public AgentProxyFactory(ChatFacade chatFacade, ReActEngine reActEngine,
                             ToolRegistry toolRegistry,
                             AgentDefaultsConfig defaults,
                             String defaultSystemPrompt,
                             List<AgentHook> hooks,
                             List<ReactivePipelineStage> stages) {
        this(chatFacade, reActEngine, toolRegistry,
                new AgentConfigResolver(defaults),
                defaultSystemPrompt, hooks, stages, new HookRegistry());
    }

    /**
     * 最小化的向后兼容构造函数（无默认值、无Hook、无阶段）。
     */
    public AgentProxyFactory(ChatFacade chatFacade, ReActEngine reActEngine,
                             ToolRegistry toolRegistry) {
        this(chatFacade, reActEngine, toolRegistry,
                new AgentDefaultsConfig(), null, List.of(), List.of());
    }

    /**
     * 为给定的 @Agent 接口创建动态代理。
     *
     * <p>解析流程:
     * <ol>
     *   <li>从接口读取 @Agent 注解</li>
     *   <li>从注解中提取 agentId、model、provider</li>
     *   <li>向configResolver注册Agent（如果尚未注册）</li>
     *   <li>resolveAgentConfig(agentId) → ResolvedAgentConfig</li>
     *   <li>使用解析后的model/provider（注解覆盖默认值）</li>
     *   <li>根据解析后的配置或系统属性确定runtimeType</li>
     *   <li>使用ResolvedAgentConfig构建AgentInvocationHandler</li>
     *   <li>返回代理</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> agentInterface) {
        if (chatFacade == null) {
            throw new IllegalStateException("ChatFacade must not be null");
        }

        Agent ann = agentInterface.getAnnotation(Agent.class);

        String agentId = resolveAgentId(agentInterface, ann);

        // 解析系统提示词: 注解覆盖 > 默认值
        String systemPrompt = defaultSystemPrompt;
        if (ann != null && !ann.description().isEmpty() && defaultSystemPrompt == null) {
            systemPrompt = ann.description();
        }
        if (ann != null && !ann.systemPromptOverride().isEmpty()) {
            systemPrompt = ann.systemPromptOverride();
        }

        // 向配置解析器注册Agent并解析完整配置
        if (ann != null) {
            configResolver.registerAgent(agentId, ann);
        }
        ResolvedAgentConfig resolvedConfig = configResolver.resolveAgentConfig(agentId);

        // 模型/提供商: 注解覆盖默认值
        String model = resolvedConfig.getModel();
        String provider = resolvedConfig.getProvider();

        // 确定运行时类型
        AgentContext.AgentRuntimeType runtimeType = resolveRuntimeType(resolvedConfig);

        AgentInvocationHandler handler = new AgentInvocationHandler(
                chatFacade, reActEngine, toolRegistry,
                systemPrompt, model, provider,
                hooks, stages, resolvedConfig, hookRegistry, runtimeType);

        return (T) Proxy.newProxyInstance(
                agentInterface.getClassLoader(),
                new Class<?>[]{agentInterface},
                handler);
    }

    /**
     * 创建具有显式运行时类型覆盖的代理。
     */
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> agentInterface, AgentContext.AgentRuntimeType runtimeType) {
        T proxy = create(agentInterface);
        // 处理器存储了runtimeType；我们也可以在创建后通过
        // 处理器的setter来传入
        return proxy;
    }

    // ===== 私有辅助方法 =====

    private String resolveAgentId(Class<?> agentInterface, Agent ann) {
        if (ann != null && !ann.id().isEmpty()) {
            return ann.id();
        }
        if (ann != null && !ann.name().isEmpty()) {
            return ann.name();
        }
        String simpleName = agentInterface.getSimpleName();
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    private AgentContext.AgentRuntimeType resolveRuntimeType(ResolvedAgentConfig config) {
        // 检查系统属性覆盖
        String sysProp = System.getProperty("lyclaw.agent.runtime");
        if ("acp".equalsIgnoreCase(sysProp)) {
            return AgentContext.AgentRuntimeType.ACP;
        }
        // 检查配置扩展
        String extVal = config.getExtensions().get("runtimeType");
        if ("acp".equalsIgnoreCase(extVal)) {
            return AgentContext.AgentRuntimeType.ACP;
        }
        return AgentContext.AgentRuntimeType.EMBEDDED;
    }
}
```

### 1.5.3 更新后的 AgentInterfaceProcessor（FactoryBean）

`AgentInterfaceProcessor` 中的 `AgentProxyFactoryBean` 内部类需要小幅更新，以解析 `AgentProxyFactory` bean并调用新的 `create()` 签名：

```java
// AgentInterfaceProcessor.AgentProxyFactoryBean 内部:

@Override
public Object getObject() {
    DefaultListableBeanFactory registry =
            (DefaultListableBeanFactory) LazyBeanFactoryHolder.getBeanFactory();
    if (registry == null) {
        throw new IllegalStateException(
                "BeanFactory not available for @Agent proxy: " + agentInterface.getName());
    }
    AgentProxyFactory factory = registry.getBean(AgentProxyFactory.class);

    // 第一阶段变更: create() 现在内部解析配置并将其传递给处理器
    Object proxy = factory.create(agentInterface);

    String beanName = resolveBeanName();
    registry.destroySingleton(beanName);
    registry.registerSingleton(beanName, proxy);
    return proxy;
}
```

### 1.5.4 更新后的自动配置

```java
package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import lyjew.com.lyclaw.autoconfigure.processor.AgentInterfaceProcessor;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.config.AgentDefaultsConfig;
import lyjew.com.lyclaw.config.AgentConfigResolver;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.react.AgentHook;
import lyjew.com.lyclaw.react.AgentProxyFactory;
import lyjew.com.lyclaw.react.HookRegistry;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.tool.ToolRegistry;

@AutoConfiguration
@AutoConfigureAfter({ChatAutoConfiguration.class, ReActAutoConfiguration.class, ToolAutoConfiguration.class})
@ConditionalOnClass({ReActEngine.class, ToolRegistry.class, ChatFacade.class})
@EnableConfigurationProperties(AgentDefaultsConfig.class)
public class AgentProxyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentConfigResolver.class)
    public AgentConfigResolver agentConfigResolver(AgentDefaultsConfig defaults) {
        return new AgentConfigResolver(defaults);
    }

    @Bean
    @ConditionalOnMissingBean(HookRegistry.class)
    public HookRegistry hookRegistry() {
        return new HookRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(AgentProxyFactory.class)
    public AgentProxyFactory agentProxyFactory(
            ChatFacade chatFacade,
            ReActEngine reActEngine,
            ToolRegistry toolRegistry,
            AgentConfigResolver configResolver,
            HookRegistry hookRegistry,
            List<AgentHook> hooks,
            List<ReactivePipelineStage> stages) {
        List<AgentHook> hookList = hooks != null ? hooks : List.of();
        List<ReactivePipelineStage> pipelineStages = stages != null ? stages : List.of();
        return new AgentProxyFactory(chatFacade, reActEngine, toolRegistry,
                configResolver, null, hookList, pipelineStages, hookRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(AgentInterfaceProcessor.class)
    public static AgentInterfaceProcessor agentInterfaceProcessor() {
        return new AgentInterfaceProcessor();
    }
}
```

---

## 总结：第一阶段交付物

| # | 组件 | 变更 | 影响 |
|---|---|---|---|
| 1.1a | `@Agent` 注解 | 6个 → 约30个字段 | 类型化、可发现的Agent配置 |
| 1.1b | `AgentDefaultsConfig` | 新类 | 来自 `application.yml` 的全局默认值 |
| 1.1c | `AgentSystemDefaults` | 新类 | 硬编码回退常量 |
| 1.1d | `ResolvedAgentConfig` | 新的不可变类 | 3层深度合并的输出 |
| 1.1e | `AgentConfigResolver` | 增强 | resolveAgentConfig、listAgentIds、工作区目录 |
| 1.2 | `AgentContext` | +15个新字段 + 增强的快照/恢复 | 丰富的运行时数据总线 |
| 1.3a | `AgentHook` | 5个 → 36个方法 | 完整的生命周期覆盖 |
| 1.3b | `AgentFinalizeResult` | 新类 | CONTINUE/REVISE/FINALIZE门控 |
| 1.3c | `HookDecision` | 新类 | PASS/BLOCK 附带原因和元数据 |
| 1.3d | `HookRegistration` | 新 record | 类型化的Hook注册表条目 |
| 1.3e | `HookRegistry` | 新类 | 注册、分发、注销Hook |
| 1.4a | `AgentRuntimeType` | 新枚举 | EMBEDDED / ACP |
| 1.4b | `AcpRuntime` | 新接口 | ensureSession、startTurn、cancel、close |
| 1.4c | `AcpRuntimeHandle/Event/...` | 新类型 | ACP协议数据对象 |
| 1.5 | `AgentProxyFactory` | 重构 | 配置感知、运行时类型支持 |

### 向后兼容性

- 现有 `@Agent` 注解字段（`name`、`description`、`version`、`model`、`provider`、`extensions`）保持不变 — 所有新字段都有合理的默认值。
- 现有 `AgentHook` 方法保持原样 — 新方法均为 `default`（无操作）。
- `AgentContext` 构造函数重载保持了旧签名，同时也提供了接受 `ResolvedAgentConfig` 的新签名。
- `AgentProxyFactory` 保留了向后兼容的构造函数。
- `LyClawAgent.Builder` 在非Spring环境中继续正常工作。

---

# LyClaw Agent 改造第二阶段：子代理委派系统 + 模型管理增强

## 目录

1. [背景与分析](#1-背景与分析)
2. [2.1 子代理委派系统](#21-子代理委派系统)
   - [2.1.1 SubagentConfig](#211-subagentconfig)
   - [2.1.2 SubagentSpawner](#212-subagentspawner)
   - [2.1.3 内置 delegate_to_agent 工具](#213-内置-delegate_to_agent-工具)
   - [2.1.4 委派流程](#214-委派流程)
   - [2.1.5 子代理会话管理](#215-子代理会话管理)
   - [2.1.6 并发控制](#216-并发控制)
   - [2.1.7 AgentContext 对子代理的增强](#217-agentcontext-对子代理的增强)
   - [2.1.8 Agent 注解对子代理的增强](#218-agent-注解对子代理的增强)
   - [2.1.9 子代理钩子系统](#219-子代理钩子系统)
   - [2.1.10 子代理错误处理与超时](#2110-子代理错误处理与超时)
   - [2.1.11 配置（application.yml）](#2111-配置applicationyml)
2. [2.2 模型管理增强](#22-模型管理增强)
   - [2.2.1 模型目录](#221-模型目录)
   - [2.2.2 AgentDefaultsConfig 中的多模型支持](#222-agentdefaultsconfig-中的多模型支持)
   - [2.2.3 模型选择与解析](#223-模型选择与解析)
   - [2.2.4 思考/推理/详细程度控制](#224-思考推理详细程度控制)
   - [2.2.5 提供商发现](#225-提供商发现)
   - [2.2.6 模型回退链集成](#226-模型回退链集成)
   - [2.2.7 思考相关的 SSE 事件](#227-思考相关的-sse-事件)
   - [2.2.8 ChatRequest 与 ChatModel 增强](#228-chatrequest-与-chatmodel-增强)
   - [2.2.9 配置（application.yml）](#229-配置applicationyml)
3. [集成点汇总](#3-集成点汇总)
4. [迁移路径](#4-迁移路径)

---

## 1. 背景与分析

### 1.1 当前架构差距

LyClaw 目前存在两个平行但互不连通的世界：

**世界 A — 多代理基础设施（独立存在，核心循环中未使用）：**
- `AgentCoordinator`、`CollaborationHub`、`ConsensusEngine` — 多代理编排
- `AgentCommProtocol`、`AgentChannel` — 代理间通信
- `AgentRegistry`、`AgentHandle`、`AgentLifecycle` — 代理生命周期管理
- `AgentSpec`、`AgentState`、`AgentTask` — 代理描述和任务模型
- `AgentPoolSnapshot`、`AutoScaler`、`ScalingDecision` — 池扩缩容
- `ExternalAgentAdapter`、`AgentCard`、`TaskStatus` — 外部代理桥接

这些类位于 `lyclaw-framework/src/main/java/lyjew/com/lyclaw/agent/` 目录下，但**从未被**核心代理管道调用。它们是为一个假想的多代理世界设计的独立抽象，而实际的 ReAct 引擎对此毫无概念。

**世界 B — 核心代理循环（实际运行的部分）：**
- `AgentInvocationHandler` → 阶段管道（`ContextBuildStage` → `SecurityCheckStage` → `PlanExecutionStage` → `RespondStage` → `ReflectionStage` → `MetricsStage`）
- `RespondStage` 委托给 `ReActEngine.executeStream()`（具体为 `DefaultReActEngine`）
- `ReActEngine` 循环：LLM 调用 → 如果有 tool_calls，通过 `ToolExecutor` 执行工具 → 将结果反馈回去 → 重复
- `ToolRegistry` 提供工具定义和执行。不存在"委托给另一个代理"的工具。

**模型管理（基础）：**
- `ChatFacade`（由 `DefaultChatFacade` 实现）封装 `ChatModelRegistry` + `ModelRouter`
- `FirstAvailableRouter` — 总是选取第一个提供商中的第一个模型。没有任何智能。
- 三个装饰器：`CircuitBreakerChatModel`、`FallbackChatModel`、`RetryChatModel`
- `ChatProperties` — 基于 YAML 的配置，包含 `defaultProvider`、`defaultModel`、`models` 映射
- `AgentConfig` — 来自注解/yml/数据库的合并配置，包含 `model` 和 `provider` 字符串字段
- `@Agent` 注解具有 `model()` 和 `provider()` 字符串字段
- `ChatRequest` 具有 `thinkingEnabled`（boolean）和 `thinkingBudget`（Integer）— 非常基础
- `ModelCapabilities` — streaming、toolCalling、thinking、vision、promptCaching 标志

### 1.2 第二阶段目标

1. **将子代理委派集成到核心代理循环中** — 当 LLM 决定委派任务时，会生成一个新的代理会话，独立运行其完整管道，并将结果作为工具观察返回给父代理。
2. **增强模型管理** — 引入模型目录、多模型支持（图像、音频、视频生成模型）、思考/推理级别控制、提供商发现和模型别名。

---

## 2.1 子代理委派系统

### 2.1.1 SubagentConfig

```java
package lyjew.com.lyclaw.react.subagent;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 子代理生成的配置，合并自以下来源：
 * <ol>
 *   <li>硬编码默认值（本类的静态默认值）</li>
 *   <li>application.yml（lyclaw.subagent.*）</li>
 *   <li>@Agent 注解扩展（例如，"subagent.maxConcurrent"）</li>
 * </ol>
 *
 * <p>每个父代理持有一个 SubagentConfig，用于管控它可以生成哪些子代理以及如何生成。
 * 当调用 spawnSubagent() 时，子代理自身的 @Agent 注解配置首先被解析，然后
 * 被父代理的 SubagentConfig 覆盖，以确保安全限制（maxSpawnDepth、maxConcurrent
 * 始终受父代理设置约束）。</p>
 */
public class SubagentConfig {

    // ── 委派模式 ──

    /**
     * 该代理的委派模式：
     * <ul>
     *   <li>"suggest" — 告知 LLM 它<i>可以</i>委派但不是必须的。
     *       工具定义中包含建议可选委派的描述。</li>
     *   <li>"prefer" — 告知 LLM 在适用时<i>应该</i>委派。
     *       工具描述和系统提示会调整为鼓励委派。</li>
     * </ul>
     */
    private String delegationMode = "suggest";

    /**
     * 该父代理允许委派到的代理 ID 列表。
     * 包含 "*" 的单元素列表表示所有已注册的代理。
     * 空列表表示完全禁用委派。
     */
    private List<String> allowAgents = new ArrayList<>(List.of("*"));

    // ── 并发与深度 ──

    /** 每个父代理允许的最大并发子代理运行数。默认 1（串行）。 */
    private int maxConcurrent = 1;

    /**
     * 最大生成深度。1 表示父代理可以生成子代理，但子代理
     * 不能再生成孙代理（无递归生成）。2 表示允许生成孙代理，
     * 依此类推。深度通过 AgentContext.runMetadata.subagentDepth 追踪。
     */
    private int maxSpawnDepth = 1;

    /** 每个父代理允许的最大活跃子代理数（尚未归档）。 */
    private int maxChildrenPerAgent = 5;

    // ── 会话生命周期 ──

    /** 子代理会话在非活跃指定分钟后自动归档。 */
    private int archiveAfterMinutes = 60;

    // ── 子代理的模型覆盖 ──

    /**
     * 用于子代理的可选模型名称。如果为 null，则使用子代理自身
     * 的配置模型（来自 @Agent 注解或 yml）。
     */
    private String model;

    /**
     * 子代理的可选思考/推理级别。
     * 覆盖子代理自身的思考级别。
     */
    private String thinking;

    // ── 超时设置 ──

    /** 每个子代理运行的超时时间（秒）。默认 300（5 分钟）。 */
    private int runTimeoutSeconds = 300;

    /** 父代理等待子代理首次通告（token）的超时时间。 */
    private int announceTimeoutMs = 120_000;

    // ── 身份设置 ──

    /**
     * 当为 true 时，父代理 LLM 在调用 delegate_to_agent 时<b>必须</b>指定具体的 agentId。
     * 当为 false 时，父代理可以省略 agentId，系统将尝试通过能力/描述自动匹配。
     */
    private boolean requireAgentId = false;

    // ── 静态默认值 ──

    public static SubagentConfig defaults() {
        return new SubagentConfig();
    }

    public static Builder builder() {
        return new Builder();
    }

    // ── Getters / Setters ──

    public String getDelegationMode() { return delegationMode; }
    public void setDelegationMode(String delegationMode) { this.delegationMode = delegationMode; }
    public List<String> getAllowAgents() { return allowAgents; }
    public void setAllowAgents(List<String> allowAgents) { this.allowAgents = allowAgents; }
    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
    public int getMaxSpawnDepth() { return maxSpawnDepth; }
    public void setMaxSpawnDepth(int maxSpawnDepth) { this.maxSpawnDepth = maxSpawnDepth; }
    public int getMaxChildrenPerAgent() { return maxChildrenPerAgent; }
    public void setMaxChildrenPerAgent(int maxChildrenPerAgent) { this.maxChildrenPerAgent = maxChildrenPerAgent; }
    public int getArchiveAfterMinutes() { return archiveAfterMinutes; }
    public void setArchiveAfterMinutes(int archiveAfterMinutes) { this.archiveAfterMinutes = archiveAfterMinutes; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getThinking() { return thinking; }
    public void setThinking(String thinking) { this.thinking = thinking; }
    public int getRunTimeoutSeconds() { return runTimeoutSeconds; }
    public void setRunTimeoutSeconds(int runTimeoutSeconds) { this.runTimeoutSeconds = runTimeoutSeconds; }
    public int getAnnounceTimeoutMs() { return announceTimeoutMs; }
    public void setAnnounceTimeoutMs(int announceTimeoutMs) { this.announceTimeoutMs = announceTimeoutMs; }
    public boolean isRequireAgentId() { return requireAgentId; }
    public void setRequireAgentId(boolean requireAgentId) { this.requireAgentId = requireAgentId; }

    /**
     * 将另一个配置合并到本配置中。{@code other} 中的非默认值
     * 会覆盖本配置的值。用于将父配置叠加到子配置的默认值上。
     */
    public SubagentConfig merge(SubagentConfig other) {
        if (other == null) return this;
        SubagentConfig merged = new SubagentConfig();
        merged.delegationMode = other.delegationMode != null ? other.delegationMode : this.delegationMode;
        merged.allowAgents = other.allowAgents != null && !other.allowAgents.isEmpty() ? other.allowAgents : this.allowAgents;
        merged.maxConcurrent = other.maxConcurrent > 0 ? other.maxConcurrent : this.maxConcurrent;
        merged.maxSpawnDepth = other.maxSpawnDepth > 0 ? other.maxSpawnDepth : this.maxSpawnDepth;
        merged.maxChildrenPerAgent = other.maxChildrenPerAgent > 0 ? other.maxChildrenPerAgent : this.maxChildrenPerAgent;
        merged.archiveAfterMinutes = other.archiveAfterMinutes > 0 ? other.archiveAfterMinutes : this.archiveAfterMinutes;
        merged.model = other.model != null ? other.model : this.model;
        merged.thinking = other.thinking != null ? other.thinking : this.thinking;
        merged.runTimeoutSeconds = other.runTimeoutSeconds > 0 ? other.runTimeoutSeconds : this.runTimeoutSeconds;
        merged.announceTimeoutMs = other.announceTimeoutMs > 0 ? other.announceTimeoutMs : this.announceTimeoutMs;
        merged.requireAgentId = other.requireAgentId;
        return merged;
    }

    // ── Builder ──

    public static class Builder {
        private final SubagentConfig config = new SubagentConfig();

        public Builder delegationMode(String mode) { config.delegationMode = mode; return this; }
        public Builder allowAgents(List<String> agents) { config.allowAgents = agents; return this; }
        public Builder allowAllAgents() { config.allowAgents = List.of("*"); return this; }
        public Builder maxConcurrent(int n) { config.maxConcurrent = n; return this; }
        public Builder maxSpawnDepth(int n) { config.maxSpawnDepth = n; return this; }
        public Builder maxChildrenPerAgent(int n) { config.maxChildrenPerAgent = n; return this; }
        public Builder archiveAfterMinutes(int m) { config.archiveAfterMinutes = m; return this; }
        public Builder model(String model) { config.model = model; return this; }
        public Builder thinking(String thinking) { config.thinking = thinking; return this; }
        public Builder runTimeoutSeconds(int s) { config.runTimeoutSeconds = s; return this; }
        public Builder announceTimeoutMs(int ms) { config.announceTimeoutMs = ms; return this; }
        public Builder requireAgentId(boolean v) { config.requireAgentId = v; return this; }
        public SubagentConfig build() { return config; }
    }
}
```

### 2.1.2 SubagentSpawner

这是生成和运行子代理的核心编排器。它被注入到 `ToolRegistry`（或新的 `ToolProvider`）中，因此当 LLM 调用 `delegate_to_agent` 工具时，执行会通过此类进行路由。

```java
package lyjew.com.lyclaw.react.subagent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import lyjew.com.lyclaw.agent.AgentRegistry;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.config.AgentConfig;
import lyjew.com.lyclaw.config.AgentConfigResolver;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.AgentHook;
import lyjew.com.lyclaw.react.AgentInvocationHandler;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.react.ToolExecutor;
import lyjew.com.lyclaw.tool.ToolRegistry;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用于生成和管理子代理执行的核心编排器。
 *
 * <h3>生命周期</h3>
 * <ol>
 *   <li>LLM 调用 {@code delegate_to_agent} 工具 → 工具执行器调用
 *       {@link #spawnSubagent(String, String, Map, AgentContext)}</li>
 *   <li>验证：检查 allowAgents 白名单、深度限制、子代理数量限制</li>
 *   <li>从 AgentConfigResolver 解析子代理配置</li>
 *   <li>为子代理构建隔离的 AgentContext</li>
 *   <li>分发 {@code subagentSpawning} 钩子</li>
 *   <li>运行子代理的完整管道（ContextBuild → ... → Metrics）</li>
 *   <li>分发 {@code subagentSpawned} 和 {@code subagentEnded} 钩子</li>
 *   <li>将 {@link SubagentResult} 作为工具观察返回给父代理</li>
 * </ol>
 *
 * <h3>并发模型</h3>
 * <p>每个父代理拥有一个 Semaphore(maxConcurrent) 来限制并发
 * 子代理运行数。深度通过父代理的
 * {@code ctx.runMetadata.subagentDepth} 追踪。活跃子代理通过
 * {@code ctx.runMetadata.activeSubagentIds} 追踪。</p>
 *
 * @see SubagentConfig
 * @see SubagentResult
 */
public class SubagentSpawner {

    private static final Logger log = LoggerFactory.getLogger(SubagentSpawner.class);

    private final ChatFacade chatFacade;
    private final ReActEngine reActEngine;
    private final ToolRegistry toolRegistry;
    private final AgentRegistry agentRegistry;
    private final AgentConfigResolver agentConfigResolver;
    private final List<ReactivePipelineStage> defaultStages;
    private final List<AgentHook> defaultHooks;

    /**
     * 每个父代理的信号量映射，用于并发控制。
     * Key = 父代理 sessionKey。
     */
    private final Map<String, Semaphore> concurrencySemaphores = new ConcurrentHashMap<>();

    public SubagentSpawner(ChatFacade chatFacade, ReActEngine reActEngine,
                           ToolRegistry toolRegistry, AgentRegistry agentRegistry,
                           AgentConfigResolver agentConfigResolver,
                           List<ReactivePipelineStage> defaultStages,
                           List<AgentHook> defaultHooks) {
        this.chatFacade = chatFacade;
        this.reActEngine = reActEngine;
        this.toolRegistry = toolRegistry;
        this.agentRegistry = agentRegistry;
        this.agentConfigResolver = agentConfigResolver;
        this.defaultStages = defaultStages != null ? List.copyOf(defaultStages) : List.of();
        this.defaultHooks = defaultHooks != null ? List.copyOf(defaultHooks) : List.of();
    }

    /**
     * 生成一个子代理来执行给定的任务。
     *
     * <p>此方法通常从支持 {@code delegate_to_agent} 内置工具的
     * 工具执行器调用。</p>
     *
     * @param targetAgentId 要委派到的代理 ID（如果 requireAgentId 为 false
     *        且启用了自动匹配，则可以为 null）
     * @param task 子代理的自然语言任务描述
     * @param options 来自工具调用的附加选项（例如，模式覆盖）
     * @param parentCtx 父代理的上下文
     * @return 返回一个 Mono，在完成时包含子代理的结果
     */
    public Mono<SubagentResult> spawnSubagent(String targetAgentId, String task,
                                               Map<String, Object> options,
                                               AgentContext parentCtx) {
        Instant startTime = Instant.now();
        String parentSessionKey = parentCtx.getSessionId();

        // ── 1. 解析父代理的 SubagentConfig ──
        SubagentConfig parentConfig = resolveSubagentConfig(parentCtx);

        // ── 2. 验证限制 ──
        // 2a. 检查委派是否启用（非空 allowAgents）
        if (parentConfig.getAllowAgents().isEmpty()) {
            return Mono.just(SubagentResult.error("该代理已禁用委派功能"));
        }

        // 2b. 检查 allowAgents 白名单
        if (!parentConfig.getAllowAgents().contains("*")
                && !parentConfig.getAllowAgents().contains(targetAgentId)) {
            return Mono.just(SubagentResult.error(
                    "代理 '" + targetAgentId + "' 不在允许的委派列表中。 "
                    + "允许的代理: " + parentConfig.getAllowAgents()));
        }

        // 2c. 检查 maxSpawnDepth
        int parentDepth = parentCtx.getRunMetadata().getSubagentDepth();
        if (parentDepth + 1 > parentConfig.getMaxSpawnDepth()) {
            return Mono.just(SubagentResult.error(
                    "超过最大生成深度。当前深度: " + parentDepth
                    + "，最大: " + parentConfig.getMaxSpawnDepth()));
        }

        // 2d. 检查 maxChildrenPerAgent
        Set<String> activeChildren = parentCtx.getRunMetadata().getActiveSubagentIds();
        if (activeChildren.size() >= parentConfig.getMaxChildrenPerAgent()) {
            return Mono.just(SubagentResult.error(
                    "超过每个代理的最大子代理数。当前活跃: " + activeChildren.size()
                    + "，最大: " + parentConfig.getMaxChildrenPerAgent()));
        }

        // 2e. 并发信号量
        Semaphore semaphore = concurrencySemaphores.computeIfAbsent(
                parentSessionKey, k -> new Semaphore(parentConfig.getMaxConcurrent()));

        return Mono.fromCallable(() -> {
            if (!semaphore.tryAcquire()) {
                return SubagentResult.error(
                        "达到最大并发子代理数 (" + parentConfig.getMaxConcurrent() + ")");
            }
            return null; // 已获取，继续
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(earlyError -> {
            if (earlyError != null) {
                return Mono.just(earlyError);
            }
            try {
                return runSubagent(targetAgentId, task, options, parentCtx, parentConfig, startTime);
            } catch (Exception e) {
                semaphore.release();
                return Mono.just(SubagentResult.error("子代理启动失败: " + e.getMessage()));
            }
        })
        .doFinally(signalType -> {
            // 完成时始终释放信号量
            semaphore.release();
        });
    }

    /**
     * 核心执行：构建隔离的 AgentContext，运行完整管道，返回结果。
     */
    private Mono<SubagentResult> runSubagent(String targetAgentId, String task,
                                              Map<String, Object> options,
                                              AgentContext parentCtx,
                                              SubagentConfig parentConfig,
                                              Instant startTime) {
        String childAgentId = targetAgentId;
        String childSessionKey = parentCtx.getSessionId()
                + "/subagent/" + childAgentId + "/" + UUID.randomUUID().toString().substring(0, 8);

        // ── 3. 解析子代理配置 ──
        AgentConfig childAgentConfig = agentConfigResolver.resolve(childAgentId);
        if (childAgentConfig.getName() == null) {
            return Mono.just(SubagentResult.error("未知代理: " + childAgentId));
        }

        // ── 4. 为子代理构建隔离的 AgentContext ──
        // 子代理拥有自己的 toolRegistry 子集、会话和管道
        AgentContext childCtx = buildChildContext(childSessionKey, task, childAgentConfig, parentCtx);

        // 在运行元数据中设置子代理深度
        childCtx.getRunMetadata().setSubagentDepth(
                parentCtx.getRunMetadata().getSubagentDepth() + 1);
        childCtx.getRunMetadata().setParentSessionKey(parentCtx.getSessionId());
        childCtx.getRunMetadata().setSubagentTargetAgentId(childAgentId);

        // 在父代理的活跃子代理集合中追踪
        parentCtx.getRunMetadata().getActiveSubagentIds().add(childSessionKey);

        // ── 5. 分发 subagentSpawning 钩子 ──
        dispatchHooks("subagentSpawning", childCtx, null);

        // ── 6. 运行子代理的管道 ──
        // 为子代理构建轻量级 AgentInvocationHandler。
        // 子代理运行相同的管道阶段，但使用自己的上下文。
        AgentInvocationHandler childHandler = new AgentInvocationHandler(
                chatFacade, reActEngine, toolRegistry,
                childAgentConfig.getDescription(), // 系统提示
                childAgentConfig.getModel(),
                childAgentConfig.getProvider(),
                defaultHooks,
                defaultStages
        );

        return Mono.fromCallable(() -> {
            try {
                // 以阻塞模式执行子代理管道并收集结果
                String result = childHandler.executeBlocking(childCtx);
                Duration elapsed = Duration.between(startTime, Instant.now());

                // ── 7. 构建 SubagentResult ──
                SubagentResult subagentResult = SubagentResult.success(
                        childSessionKey, childAgentId, result, elapsed.toMillis(),
                        childCtx.getSuccessCount().get(), childCtx.getFailCount().get());

                // ── 8. 分发 subagentSpawned / subagentEnded 钩子 ──
                dispatchHooks("subagentSpawned", childCtx, subagentResult);
                dispatchHooks("subagentEnded", childCtx, subagentResult);

                return subagentResult;
            } catch (Exception e) {
                log.error("子代理 '{}' 执行失败: {}", childAgentId, e.getMessage(), e);
                Duration elapsed = Duration.between(startTime, Instant.now());
                return SubagentResult.error("子代理执行失败: " + e.getMessage());
            } finally {
                // 从活跃集合中移除
                parentCtx.getRunMetadata().getActiveSubagentIds().remove(childSessionKey);
                // 如果配置了则调度会话归档
                scheduleSessionArchive(childSessionKey, parentConfig.getArchiveAfterMinutes());
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .timeout(Duration.ofSeconds(parentConfig.getRunTimeoutSeconds()),
                 Mono.just(SubagentResult.error(
                         "子代理在 " + parentConfig.getRunTimeoutSeconds() + " 秒后超时")),
                 Schedulers.boundedElastic());
    }

    /**
     * 为子代理构建隔离的 AgentContext。
     */
    private AgentContext buildChildContext(String sessionKey, String task,
                                            AgentConfig childConfig,
                                            AgentContext parentCtx) {
        // 子代理获取独立的 sessionId，userMessage = 任务。
        // 系统提示来自子代理的描述。
        AgentContext childCtx = AgentContext.sessionScoped(
                sessionKey,
                task,  // 用户消息 = 委派的任务
                childConfig.getDescription(),  // 来自子代理 @Agent 的系统提示
                toolRegistry,
                parentCtx.getMethod(),  // 子代理的 method 为 null/占位符
                new Object[0]
        );

        // 构建仅包含任务消息的 ChatRequest
        ChatRequest request = ChatRequest.builder()
                .sessionId(sessionKey)
                .messages(new java.util.ArrayList<>(List.of(Message.user(task))))
                .stream(true)
                .build();

        // 如果子代理配置有模型覆盖，则应用
        if (childConfig.getModel() != null && !childConfig.getModel().isEmpty()) {
            request.setModel(childConfig.getModel());
        }

        // 从父代理的工具注册表（或限定子集）设置工具
        List<ToolDefinition> tools = toolRegistry.getAllDefinitions(request);
        request.setTools(tools);
        request.setToolChoice("auto");

        childCtx.setChatRequest(request);
        childCtx.setSandboxLevel(parentCtx.getSandboxLevel());

        // 从子代理配置设置思考级别
        String thinkingLevel = childConfig.getExtension("thinking.level", null);
        if (thinkingLevel != null) {
            childCtx.getRunMetadata().setThinkingLevel(thinkingLevel);
        }

        return childCtx;
    }

    /**
     * 从父代理 AgentContext 解析 SubagentConfig。
     * 优先级：AgentConfig 扩展 > application.yml > 硬编码默认值。
     */
    private SubagentConfig resolveSubagentConfig(AgentContext ctx) {
        SubagentConfig config = SubagentConfig.defaults();

        // 从 AgentContext 属性叠加（由 AgentInvocationHandler
        // 在解析 @Agent 注解扩展后设置）
        @SuppressWarnings("unchecked")
        Map<String, String> extensions = ctx.getAttribute("agentExtensions");
        if (extensions != null) {
            if (extensions.containsKey("subagent.delegationMode"))
                config.setDelegationMode(extensions.get("subagent.delegationMode"));
            if (extensions.containsKey("subagent.allowAgents"))
                config.setAllowAgents(List.of(extensions.get("subagent.allowAgents").split(",")));
            if (extensions.containsKey("subagent.maxConcurrent"))
                config.setMaxConcurrent(Integer.parseInt(extensions.get("subagent.maxConcurrent")));
            if (extensions.containsKey("subagent.maxSpawnDepth"))
                config.setMaxSpawnDepth(Integer.parseInt(extensions.get("subagent.maxSpawnDepth")));
            if (extensions.containsKey("subagent.maxChildrenPerAgent"))
                config.setMaxChildrenPerAgent(Integer.parseInt(extensions.get("subagent.maxChildrenPerAgent")));
            if (extensions.containsKey("subagent.archiveAfterMinutes"))
                config.setArchiveAfterMinutes(Integer.parseInt(extensions.get("subagent.archiveAfterMinutes")));
            if (extensions.containsKey("subagent.model"))
                config.setModel(extensions.get("subagent.model"));
            if (extensions.containsKey("subagent.thinking"))
                config.setThinking(extensions.get("subagent.thinking"));
            if (extensions.containsKey("subagent.runTimeoutSeconds"))
                config.setRunTimeoutSeconds(Integer.parseInt(extensions.get("subagent.runTimeoutSeconds")));
        }

        return config;
    }

    /**
     * 将生命周期事件分发给所有实现了 SubagentHook 的已注册钩子。
     */
    private void dispatchHooks(String lifecycleEvent, AgentContext childCtx,
                                SubagentResult result) {
        for (AgentHook hook : defaultHooks) {
            if (hook instanceof SubagentHook subagentHook) {
                try {
                    switch (lifecycleEvent) {
                        case "subagentSpawning":
                            subagentHook.subagentSpawning(childCtx);
                            break;
                        case "subagentSpawned":
                            subagentHook.subagentSpawned(childCtx, result);
                            break;
                        case "subagentEnded":
                            subagentHook.subagentEnded(childCtx, result);
                            break;
                    }
                } catch (Exception e) {
                    log.warn("SubagentHook '{}' 在 {} 上抛出异常: {}",
                            hook.getClass().getSimpleName(), lifecycleEvent, e.getMessage());
                }
            }
        }
    }

    private void scheduleSessionArchive(String sessionKey, int afterMinutes) {
        // 委托给会话存储，在非活跃后归档此会话。
        // 实现方式：注册一个延迟任务，检查会话是否
        // 仍然活跃，如果不活跃则将其移至冷存储。
        log.debug("已为子代理会话 {} 安排在 {} 分钟后归档",
                sessionKey, afterMinutes);
    }

    /**
     * 返回内置 delegate_to_agent 工具的工具定义。
     * 此工具由框架自动注册。
     */
    public static ToolDefinition buildDelegateToolDefinition(SubagentConfig config) {
        // 以编程方式构建 JSON Schema
        Map<String, Object> properties = new java.util.LinkedHashMap<>();

        // agentId 参数
        Map<String, Object> agentIdSchema = new java.util.LinkedHashMap<>();
        agentIdSchema.put("type", "string");
        agentIdSchema.put("description", "要委派到的专用代理的 ID");
        properties.put("agentId", agentIdSchema);

        // task 参数
        Map<String, Object> taskSchema = new java.util.LinkedHashMap<>();
        taskSchema.put("type", "string");
        taskSchema.put("description", "子代理的详细任务描述");
        properties.put("task", taskSchema);

        // mode 参数（可选覆盖）
        Map<String, Object> modeSchema = new java.util.LinkedHashMap<>();
        modeSchema.put("type", "string");
        modeSchema.put("enum", List.of("suggest", "prefer"));
        modeSchema.put("description", "本次调用的委派模式覆盖");
        properties.put("mode", modeSchema);

        // 构建完整参数 Schema
        Map<String, Object> parameters = new java.util.LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);

        // 必填字段取决于配置
        List<String> required = new java.util.ArrayList<>();
        required.add("task");
        if (config.isRequireAgentId()) {
            required.add("agentId");
        }
        parameters.put("required", required);

        // 构建函数定义
        Map<String, Object> function = new java.util.LinkedHashMap<>();
        function.put("name", "delegate_to_agent");
        function.put("description",
                config.getDelegationMode().equals("prefer")
                        ? "将任务委派给另一个专用代理。"
                          + "当另一个代理专门从事该任务时，你<b>应该</b>使用此工具。"
                        : "将任务委派给另一个专用代理。"
                          + "当另一个代理专门从事该任务时，你<b>可以</b>使用此工具。");
        function.put("parameters", parameters);

        return ToolDefinition.builder()
                .name("delegate_to_agent")
                .type("function")
                .function(function)
                .build();
    }
}
```

### 2.1.3 内置 delegate_to_agent 工具

`delegate_to_agent` 工具通过 `ToolProvider` 注册为内置工具，而非静态的 `@Tool` 注解，因为它需要在运行时访问 `AgentContext`（而静态工具无法访问）。

```java
package lyjew.com.lyclaw.react.subagent;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lyjew.com.lyclaw.tool.ToolProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * ToolProvider，将内置的 {@code delegate_to_agent} 工具注入到
 * 每个代理的工具集中。这是 LLM 发现子代理委派的方式。
 *
 * <p>当 LLM 调用此工具时，执行会路由到
 * {@link SubagentSpawner}，它生成一个新的代理会话，运行至
 * 完成，并将结果作为工具输出返回。</p>
 */
public class DelegateToAgentToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(DelegateToAgentToolProvider.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final SubagentSpawner spawner;
    private final boolean enabled;

    public DelegateToAgentToolProvider(SubagentSpawner spawner, boolean enabled) {
        this.spawner = spawner;
        this.enabled = enabled;
    }

    @Override
    public boolean isEnabled(ChatRequest request) {
        return enabled;
    }

    @Override
    public List<ToolDefinition> getDefinitions(ChatRequest request) {
        if (!enabled) return List.of();
        // 基于父代理的配置动态构建工具定义
        SubagentConfig config = SubagentConfig.defaults(); // 将在运行时从上下文解析
        return List.of(SubagentSpawner.buildDelegateToolDefinition(config));
    }

    @Override
    public ToolExecutionResult execute(ToolCall toolCall, ChatRequest request, Object context) {
        if (!"delegate_to_agent".equals(toolCall.getName())) {
            return ToolExecutionResult.error("未知工具: " + toolCall.getName());
        }

        if (!(context instanceof ToolProviderContext ctx)) {
            return ToolExecutionResult.error("缺少 ToolProviderContext");
        }

        AgentContext agentCtx = ctx.getAgentContext();

        // 解析参数
        Map<String, Object> args;
        try {
            if (toolCall.getArguments() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) toolCall.getArguments();
                args = m;
            } else {
                String argsStr = toolCall.getArguments() != null
                        ? toolCall.getArguments().toString() : "{}";
                args = objectMapper.readValue(argsStr,
                        new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            return ToolExecutionResult.error("解析 delegate_to_agent 参数失败: " + e.getMessage());
        }

        String targetAgentId = (String) args.getOrDefault("agentId", "");
        String task = (String) args.get("task");
        if (task == null || task.isEmpty()) {
            return ToolExecutionResult.error("delegate_to_agent 必须提供 task 参数");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) args.getOrDefault("options", Map.of());

        // 同步执行（阻塞），因为当前 ReAct 循环中工具执行是同步的。
        // 生成器内部使用响应式类型，但为了兼容性这里采用阻塞方式。
        try {
            SubagentResult result = spawner.spawnSubagent(targetAgentId, task, options, agentCtx)
                    .block(java.time.Duration.ofSeconds(spawner.resolveSubagentConfig(agentCtx).getRunTimeoutSeconds()));

            if (result == null) {
                return ToolExecutionResult.error("子代理返回 null（可能超时）");
            }

            String output = formatSubagentOutput(result);
            return ToolExecutionResult.success(output);
        } catch (Exception e) {
            log.error("delegate_to_agent 执行失败: {}", e.getMessage(), e);
            return ToolExecutionResult.error("子代理委派失败: " + e.getMessage());
        }
    }

    private String formatSubagentOutput(SubagentResult result) {
        StringBuilder sb = new StringBuilder();
        if (result.isSuccess()) {
            sb.append("## 子代理结果 (成功)\n\n");
            sb.append("**代理:** ").append(result.getAgentId()).append("\n");
            sb.append("**耗时:** ").append(result.getDurationMs()).append("ms\n");
            sb.append("**工具:** ").append(result.getSuccessTools())
              .append(" 成功，").append(result.getFailedTools()).append(" 失败\n\n");
            sb.append("### 输出\n\n").append(result.getOutput());
        } else {
            sb.append("## 子代理结果 (失败)\n\n");
            sb.append("**代理:** ").append(result.getAgentId()).append("\n");
            sb.append("**错误:** ").append(result.getError()).append("\n");
        }
        return sb.toString();
    }
}
```

### 2.1.4 委派流程

完整的流程，逐步说明：

```
┌──────────────────────────────────────────────────────────────────┐
│ 父代理: AgentInvocationHandler                                    │
│   阶段管道: ContextBuild → SecurityCheck → PlanExecution          │
│   → RespondStage → ReflectionStage → MetricsStage               │
│                                                                  │
│ RespondStage:                                                    │
│   ├─ ReActEngine.executeStream(chatFacade, request, toolExecutor)│
│   │                                                              │
│   │   ┌─ LLM 调用（携带包含 "delegate_to_agent" 的工具列表）     │
│   │   │                                                          │
│   │   │   LLM 决定: "我应该将这次代码审查委派给                   │
│   │   │   code-reviewer 代理。"                                  │
│   │   │                                                          │
│   │   │   → toolCall: delegate_to_agent(                         │
│   │   │       agentId="code-reviewer",                           │
│   │   │       task="审查 PR #342 中的变更...",                   │
│   │   │       mode="suggest"                                     │
│   │   │     )                                                    │
│   │   │                                                          │
│   │   ├─ ToolExecutor.execute("delegate_to_agent", ...)          │
│   │   │                                                          │
│   │   │   ┌───────────────────────────────────────────────────┐ │
│   │   │   │ SubagentSpawner.spawnSubagent()                   │ │
│   │   │   │                                                   │ │
│   │   │   │   1. 验证 allowAgents 白名单                      │ │
│   │   │   │   2. 检查 maxSpawnDepth（父深度 + 1 < 最大值）    │ │
│   │   │   │   3. 检查 maxChildrenPerAgent                     │ │
│   │   │   │   4. 获取并发信号量                                │ │
│   │   │   │   5. 解析子代理 AgentConfig                        │ │
│   │   │   │   6. 为子代理构建隔离的 AgentContext              │ │
│   │   │   │   7. 分发 subagentSpawning 钩子                    │ │
│   │   │   │   8. 运行子代理的完整管道：                          │ │
│   │   │   │      ContextBuild → SecurityCheck →                │ │
│   │   │   │      PlanExecution → Respond(ReAct) →              │ │
│   │   │   │      Reflection → Metrics                          │ │
│   │   │   │   9. 分发 subagentSpawned、subagentEnded 钩子      │ │
│   │   │   │  10. 释放信号量                                    │ │
│   │   │   │  11. 返回 SubagentResult                           │ │
│   │   │   └───────────────────────────────────────────────────┘ │
│   │   │                                                          │
│   │   ├─ 工具结果作为观察返回给父代理 LLM                         │
│   │   │                                                          │
│   │   └─ 父代理 LLM 根据子代理的结果继续                          │
│   │       并生成最终回复                                           │
│   │                                                              │
│   └─ 向客户端发送最终 SSE 事件                                     │
└──────────────────────────────────────────────────────────────────┘
```

### 2.1.5 子代理会话管理

子代理会话遵循分层会话键方案：

```
父会话键:    "abc12345"
子会话键:    "abc12345/subagent/code-reviewer/a1b2c3d4"
孙会话键:    "abc12345/subagent/code-reviewer/a1b2c3d4/subagent/tester/e5f6g7h8"
```

这使得以下功能成为可能：
- **分层追踪**：任何子代理的输出都可以追溯到根会话
- **自动归档**：当父会话被归档时，会话存储可以归档该父键下的所有会话
- **级联清理**：终止父会话可以终止所有后代子代理会话

```java
package lyjew.com.lyclaw.react.subagent;

import java.util.List;

import lyjew.com.lyclaw.model.Session;

/**
 * 子代理运行的会话管理。
 *
 * <p>每次子代理运行都会创建一个新的 {@link Session}，使用分层的
 * sessionKey（parentKey + "/subagent/" + agentId + "/" + uuid片段）。
 * 会话存储在与父代理相同的会话存储中。</p>
 */
public class SubagentSessionManager {

    private final lyjew.com.lyclaw.persistence.SessionStore sessionStore;

    public SubagentSessionManager(lyjew.com.lyclaw.persistence.SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    /**
     * 在给定的父会话键下创建一个新的子代理会话。
     */
    public Session createSubagentSession(String parentSessionKey, String agentId,
                                          String systemPrompt) {
        String sessionId = parentSessionKey + "/subagent/" + agentId
                + "/" + java.util.UUID.randomUUID().toString().substring(0, 8);

        Session session = Session.builder()
                .sessionId(sessionId)
                .name("subagent:" + agentId)
                .model(null)  // 稍后从 AgentConfig 解析
                .build();

        sessionStore.save(session);
        return session;
    }

    /**
     * 归档一个子代理会话及其所有后代会话。
     */
    public void archiveSession(String sessionKey, int afterMinutes) {
        // 查找所有键以 sessionKey 开头的会话
        List<Session> descendants = sessionStore.findByPrefix(sessionKey);
        for (Session s : descendants) {
            s.setAttribute("archived", "true");
            s.setAttribute("archivedAt", String.valueOf(System.currentTimeMillis()));
            sessionStore.save(s);
        }
    }

    /**
     * 终止父键下所有活跃的子代理会话。
     * 在父会话被终止或取消时调用。
     */
    public void terminateDescendants(String parentSessionKey) {
        List<Session> descendants = sessionStore.findByPrefix(parentSessionKey);
        for (Session s : descendants) {
            if (!"true".equals(s.getAttribute("archived"))) {
                s.setAttribute("terminated", "true");
                s.setAttribute("terminatedAt", String.valueOf(System.currentTimeMillis()));
                sessionStore.save(s);
            }
        }
    }
}
```

### 2.1.6 并发控制

```java
package lyjew.com.lyclaw.react;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 附加到 AgentContext 上的运行时元数据，用于追踪子代理状态。
 *
 * <p>它存储在 AgentContext.attributes 中，键为 "runMetadata"，
 * 但为了类型安全，我们将其暴露为类型化类。</p>
 */
public class RunMetadata {

    /**
     * 此代理在子代理生成树中的深度。
     * 0 = 根代理（无父代理）。1 = 由根代理直接生成。
     * 2 = 由第 1 级子代理生成，依此类推。
     */
    private int subagentDepth = 0;

    /**
     * 如果这是一个子代理，其父代理的会话键。
     * 对于根代理为 null。
     */
    private String parentSessionKey;

    /**
     * 如果这是一个子代理，它作为其生成的 agentId。
     * 对于根代理为 null。
     */
    private String subagentTargetAgentId;

    /**
     * 此代理生成的当前活跃子代理的会话键集合。
     * 用于强制执行 maxChildrenPerAgent。
     */
    private final Set<String> activeSubagentIds = ConcurrentHashMap.newKeySet();

    /**
     * 此上下文中模型调用的思考/推理级别。
     * "off" | "low" | "medium" | "high"。null 表示使用模型默认值。
     */
    private String thinkingLevel;

    /**
     * 此上下文的模型名称覆盖（从 AgentConfig + 默认值解析）。
     */
    private String resolvedModel;

    /**
     * 此上下文的提供商名称覆盖。
     */
    private String resolvedProvider;

    /**
     * 专门为图像理解配置的模型。
     */
    private String imageModel;

    /**
     * 归档存储的会话键。
     */
    private String archiveSessionKey;


    // ── 构造函数 ──

    public RunMetadata() {}

    public static RunMetadata root() {
        return new RunMetadata();
    }

    public static RunMetadata childOf(RunMetadata parent, String childAgentId) {
        RunMetadata child = new RunMetadata();
        child.subagentDepth = parent.subagentDepth + 1;
        child.parentSessionKey = null; // 稍后由生成器设置
        child.subagentTargetAgentId = childAgentId;
        return child;
    }

    // ── Getters / Setters ──

    public int getSubagentDepth() { return subagentDepth; }
    public void setSubagentDepth(int depth) { this.subagentDepth = depth; }

    public String getParentSessionKey() { return parentSessionKey; }
    public void setParentSessionKey(String key) { this.parentSessionKey = key; }

    public String getSubagentTargetAgentId() { return subagentTargetAgentId; }
    public void setSubagentTargetAgentId(String id) { this.subagentTargetAgentId = id; }

    public Set<String> getActiveSubagentIds() { return activeSubagentIds; }

    public String getThinkingLevel() { return thinkingLevel; }
    public void setThinkingLevel(String level) { this.thinkingLevel = level; }

    public String getResolvedModel() { return resolvedModel; }
    public void setResolvedModel(String model) { this.resolvedModel = model; }

    public String getResolvedProvider() { return resolvedProvider; }
    public void setResolvedProvider(String provider) { this.resolvedProvider = provider; }

    public String getImageModel() { return imageModel; }
    public void setImageModel(String model) { this.imageModel = model; }

    public String getArchiveSessionKey() { return archiveSessionKey; }
    public void setArchiveSessionKey(String key) { this.archiveSessionKey = key; }

    /** 此代理是否为子代理（有父代理）。 */
    public boolean isSubagent() {
        return parentSessionKey != null || subagentDepth > 0;
    }

    /** 此代理是否为生成树的根。 */
    public boolean isRoot() {
        return subagentDepth == 0 && parentSessionKey == null;
    }
}
```

### 2.1.7 AgentContext 对子代理的增强

现有的 `AgentContext` 类需要添加一个 `RunMetadata` 字段：

```java
// ── 添加到 AgentContext 的内容 ──

/** 运行时元数据，包括子代理深度、思考级别、模型解析 */
private final RunMetadata runMetadata = new RunMetadata();

public RunMetadata getRunMetadata() { return runMetadata; }


// ── 同时添加到 AgentContext.toSnapshot() ──

public Map<String, Object> toSnapshot() {
    Map<String, Object> snapshot = new HashMap<>();
    // ... 现有的字段 ...
    snapshot.put("subagentDepth", runMetadata.getSubagentDepth());
    snapshot.put("parentSessionKey", runMetadata.getParentSessionKey());
    snapshot.put("thinkingLevel", runMetadata.getThinkingLevel());
    snapshot.put("resolvedModel", runMetadata.getResolvedModel());
    return snapshot;
}


// ── 同时添加到 AgentContext.restoreFromSnapshot() ──

public void restoreFromSnapshot(Map<String, Object> snapshot) {
    if (snapshot == null) return;
    // ... 现有的字段 ...

    if (snapshot.get("subagentDepth") instanceof Number n)
        runMetadata.setSubagentDepth(n.intValue());
    if (snapshot.get("parentSessionKey") instanceof String s)
        runMetadata.setParentSessionKey(s);
    if (snapshot.get("thinkingLevel") instanceof String s)
        runMetadata.setThinkingLevel(s);
    if (snapshot.get("resolvedModel") instanceof String s)
        runMetadata.setResolvedModel(s);
}
```

### 2.1.8 Agent 注解对子代理的增强

`@Agent` 注解的 `extensions` 已经支持键值对。我们添加用于子代理配置的知名扩展键：

```
@Agent(
    name = "chat",
    description = "通用聊天助手",
    extensions = {
        @Extension(key = "subagent.delegationMode", value = "prefer"),
        @Extension(key = "subagent.allowAgents", value = "code-reviewer,tester,data-analyst"),
        @Extension(key = "subagent.maxConcurrent", value = "3"),
        @Extension(key = "subagent.maxSpawnDepth", value = "2"),
        @Extension(key = "subagent.maxChildrenPerAgent", value = "10"),
        @Extension(key = "subagent.requireAgentId", value = "true"),
        @Extension(key = "subagent.model", value = "deepseek-v4-flash"),
        @Extension(key = "subagent.thinking", value = "medium"),
        @Extension(key = "subagent.runTimeoutSeconds", value = "600"),
        @Extension(key = "thinking.level", value = "high"),
        @Extension(key = "model.image", value = "openai/dall-e-3"),
        @Extension(key = "model.pdf", value = "openai/gpt-4o"),
        @Extension(key = "model.videoGeneration", value = "openai/sora"),
    }
)
public interface SuperChatAgent {
    @SystemMessage("你是一个协调助手...")
    String chat(@UserMessage String message);
}
```

### 2.1.9 子代理钩子系统

`AgentHook` 的一个新子接口，用于子代理生命周期事件：

```java
package lyjew.com.lyclaw.react.subagent;

import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.AgentHook;

/**
 * 用于子代理生命周期事件的扩展钩子 SPI。
 *
 * <p>任何 AgentHook 实现也可以实现此接口，以接收
 * 子代理特定的生命周期回调。这些方法在 SubagentSpawner 中
 * 适当的生命周期时间点被调用。</p>
 *
 * <p>执行上下文：这些方法在子代理生成器的 boundedElastic 调度器上执行。
 * 抛出异常会记录警告但不会中断子代理管道。</p>
 */
public interface SubagentHook extends AgentHook {

    /**
     * 在子代理管道开始执行之前调用。
     * childCtx 已经完全准备好（ChatRequest、工具、系统提示已设置）。
     * 此时修改 childCtx 将影响子代理的运行。
     *
     * @param childCtx 子代理的上下文，已完全就绪
     */
    default void subagentSpawning(AgentContext childCtx) {}

    /**
     * 在子代理管道完成并产生结果后，
     * 但在结果作为工具观察返回给父代理之前调用。
     * 可以修改结果（例如，过滤敏感信息、添加元数据）。
     *
     * @param childCtx 子代理的上下文（管道已完成）
     * @param result   子代理结果（可变的；可以通过返回新结果来替换）
     */
    default void subagentSpawned(AgentContext childCtx, SubagentResult result) {}

    /**
     * 在结果被记录后、子代理会话被归档前调用。
     * 用于清理、审计或日志记录。
     *
     * @param childCtx 子代理的上下文
     * @param result   最终的子代理结果
     */
    default void subagentEnded(AgentContext childCtx, SubagentResult result) {}
}
```

### 2.1.10 子代理错误处理与超时

```java
package lyjew.com.lyclaw.react.subagent;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.ArrayList;

/**
 * 子代理委派调用的结果。
 * 作为工具观察字符串返回给父代理 LLM（通过 toString/format），
 * 但也可以被钩子和指标以编程方式使用。
 */
@Data
@Builder
public class SubagentResult {

    /** 子代理是否成功完成。 */
    private boolean success;

    /** 子代理运行的会话键。 */
    private String sessionKey;

    /** 处理委派的代理 ID。 */
    private String agentId;

    /** 子代理的最终文本输出（LLM 的最终回复）。 */
    private String output;

    /** 如果 success == false，则为错误消息。 */
    private String error;

    /** 子代理运行耗时（毫秒）。 */
    private long durationMs;

    /** 成功的工具调用次数。 */
    private int successTools;

    /** 失败的工具调用次数。 */
    private int failedTools;

    /** 如果子代理自身也调用了 delegate_to_agent，这些是其结果。 */
    @Builder.Default
    private List<SubagentResult> childResults = new ArrayList<>();

    /** 子代理的反思评分（来自 ReflectionStage），如果有的话。 */
    private Double reflectionScore;

    /** 此子代理消耗的总 token 数。 */
    private int totalTokens;


    // ── 工厂方法 ──

    public static SubagentResult success(String sessionKey, String agentId,
                                          String output, long durationMs,
                                          int successTools, int failedTools) {
        return SubagentResult.builder()
                .success(true)
                .sessionKey(sessionKey)
                .agentId(agentId)
                .output(output)
                .durationMs(durationMs)
                .successTools(successTools)
                .failedTools(failedTools)
                .build();
    }

    public static SubagentResult error(String error) {
        return SubagentResult.builder()
                .success(false)
                .agentId("unknown")
                .error(error)
                .build();
    }

    public static SubagentResult timeout(String agentId, long timeoutSeconds) {
        return SubagentResult.builder()
                .success(false)
                .agentId(agentId)
                .error("子代理在 " + timeoutSeconds + " 秒后超时")
                .durationMs(timeoutSeconds * 1000)
                .build();
    }

    public static SubagentResult rejected(String agentId, String reason) {
        return SubagentResult.builder()
                .success(false)
                .agentId(agentId)
                .error("子代理委派被拒绝: " + reason)
                .build();
    }

    /**
     * 格式化为供父代理 LLM 阅读的工具观察字符串。
     */
    public String formatAsObservation() {
        StringBuilder sb = new StringBuilder();
        sb.append("[子代理结果] ");
        sb.append("agent=").append(agentId).append(" ");
        if (success) {
            sb.append("status=成功 ");
            sb.append("durationMs=").append(durationMs).append(" ");
            sb.append("toolsSucceeded=").append(successTools).append(" ");
            sb.append("toolsFailed=").append(failedTools).append("\n");
            sb.append("输出:\n").append(output);
        } else {
            sb.append("status=失败\n");
            sb.append("错误: ").append(error);
        }
        if (reflectionScore != null) {
            sb.append("\n反思评分: ").append(String.format("%.2f", reflectionScore));
        }
        return sb.toString();
    }
}
```

### 2.1.11 配置（application.yml）

```yaml
lyclaw:
  # 全局子代理默认值
  subagent:
    enabled: true
    delegation-mode: suggest           # "suggest" 或 "prefer"
    allow-agents: "*"                  # "*" 或逗号分隔的代理 ID 列表
    max-concurrent: 1
    max-spawn-depth: 1                 # 1 = 不允许递归生成
    max-children-per-agent: 5
    archive-after-minutes: 60
    run-timeout-seconds: 300
    announce-timeout-ms: 120000
    require-agent-id: false
    model:                             # 子代理的可选模型覆盖
    thinking:                          # 子代理的可选思考级别

  agent:
    # 默认 ReAct 设置（现有）
    max-tool-rounds: 30

  # 示例：通过扩展进行按代理覆盖（在 AgentConfig 或 yml 代理配置中）
  agents:
    chat:
      name: chat
      description: "具有子代理委派功能的通用聊天助手"
      model: deepseek-v4-flash
      provider: deepseek
      extensions:
        subagent.delegation-mode: prefer
        subagent.allow-agents: "code-reviewer,tester,data-analyst"
        subagent.max-concurrent: 3
        subagent.max-spawn-depth: 2
        subagent.max-children-per-agent: 10
        subagent.require-agent-id: true
        thinking.level: high            # 第二阶段 2.2 - 思考级别
        model.image: "openai/dall-e-3"
        model.pdf: "openai/gpt-4o"

    code-reviewer:
      name: code-reviewer
      description: "专用代码审查代理"
      model: deepseek-v4-flash
      provider: deepseek
      extensions:
        subagent.max-spawn-depth: 0    # 此代理不能生成子代理
        subagent.max-concurrent: 0
        thinking.level: medium
```

---

## 2.2 模型管理增强

### 2.2.1 模型目录

一个结构化的包含所有可用模型的目录，取代当前隐式的模型发现方式。

```java
package lyjew.com.lyclaw.chat.catalog;

import java.util.List;
import java.util.Map;

/**
 * 模型目录中的一个结构化条目。
 *
 * <p>每个条目表示来自特定提供商的一个可用模型。
 * 目录在启动时从以下来源构建：
 * <ol>
 *   <li>静态配置（application.yml lyclaw.chat.models.*）</li>
 *   <li>@ChatModel 注解的 bean（自动发现）</li>
 *   <li>ProviderDiscovery 响应（如果启用则自动探测）</li>
 * </ol>
 *
 * <p>ID 是规范的引用字符串："provider/modelName"
 * 例如，"openai/gpt-4o"、"deepseek/deepseek-v4-flash"、"anthropic/claude-sonnet-4-5"。
 */
public class ModelCatalogEntry {

    // ── 身份信息 ──

    /** 完整规范引用："openai/gpt-4o" */
    private String id;

    /** 模型名称："gpt-4o" */
    private String name;

    /** 提供商名称："openai" */
    private String provider;

    /** 可选简短别名，便于使用："gpt4" */
    private String alias;

    /** 人类可读的显示名称 */
    private String displayName;

    /** 此模型的自由文本描述 */
    private String description;

    // ── 能力 ──

    /** 最大上下文窗口（tokens） */
    private int contextWindow;

    /** 发送给 API 的上下文 token 覆盖（用于那些
     *  为内部使用保留部分上下文窗口的提供商） */
    private int contextTokens;

    /** 此模型是否支持扩展推理/思考 */
    private boolean reasoning;

    /** 此模型可生成的最大输出 token 数 */
    private int maxOutputTokens;

    // ── 输入模态 ──

    /** 此模型接受的输入类型 */
    private List<ModelInputType> input;

    // ── 定价（仅供参考） ──

    /** 每 1M 输入 token 美元价格 */
    private double pricePerMillionInput;

    /** 每 1M 输出 token 美元价格 */
    private double pricePerMillionOutput;

    // ── 兼容性配置 ──

    /** 提供商特定的兼容性覆盖 */
    private ModelCompatConfig compat;

    // ── 状态 ──

    /** 此模型当前是否可用（通过健康检查验证） */
    private boolean available = true;

    /** 是否为 beta/预览模型 */
    private boolean beta;

    /** 此模型被弃用的时间（epoch 毫秒），0 = 未弃用 */
    private long deprecatedAt;


    // ── Builder ──

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final ModelCatalogEntry entry = new ModelCatalogEntry();
        public Builder id(String id) { entry.id = id; return this; }
        public Builder name(String name) { entry.name = name; return this; }
        public Builder provider(String provider) { entry.provider = provider; return this; }
        public Builder alias(String alias) { entry.alias = alias; return this; }
        public Builder displayName(String name) { entry.displayName = name; return this; }
        public Builder description(String desc) { entry.description = desc; return this; }
        public Builder contextWindow(int tokens) { entry.contextWindow = tokens; return this; }
        public Builder contextTokens(int tokens) { entry.contextTokens = tokens; return this; }
        public Builder reasoning(boolean v) { entry.reasoning = v; return this; }
        public Builder maxOutputTokens(int tokens) { entry.maxOutputTokens = tokens; return this; }
        public Builder input(List<ModelInputType> input) { entry.input = input; return this; }
        public Builder priceInput(double price) { entry.pricePerMillionInput = price; return this; }
        public Builder priceOutput(double price) { entry.pricePerMillionOutput = price; return this; }
        public Builder compat(ModelCompatConfig compat) { entry.compat = compat; return this; }
        public Builder available(boolean v) { entry.available = v; return this; }
        public Builder beta(boolean v) { entry.beta = v; return this; }
        public Builder deprecatedAt(long ts) { entry.deprecatedAt = ts; return this; }
        public ModelCatalogEntry build() { return entry; }
    }

    // ── Getters ──

    public String getId() { return id; }
    public String getName() { return name; }
    public String getProvider() { return provider; }
    public String getAlias() { return alias; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public int getContextWindow() { return contextWindow; }
    public int getContextTokens() { return contextTokens; }
    public boolean isReasoning() { return reasoning; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public List<ModelInputType> getInput() { return input; }
    public double getPricePerMillionInput() { return pricePerMillionInput; }
    public double getPricePerMillionOutput() { return pricePerMillionOutput; }
    public ModelCompatConfig getCompat() { return compat; }
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean v) { this.available = v; }
    public boolean isBeta() { return beta; }
    public long getDeprecatedAt() { return deprecatedAt; }

    /**
     * 从提供商和模型名称构建规范 ID。
     */
    public static String canonicalId(String provider, String name) {
        return provider + "/" + name;
    }
}
```

```java
package lyjew.com.lyclaw.chat.catalog;

/**
 * 模型可以接受的输入类型。
 */
public enum ModelInputType {
    /** 纯文本 */
    TEXT,

    /** 图像文件（png, jpg, gif, webp） */
    IMAGE,

    /** 音频文件（mp3, wav, ogg） */
    AUDIO,

    /** 视频文件（mp4, mov） */
    VIDEO,

    /** 文档（pdf, docx, txt） */
    DOCUMENT
}
```

```java
package lyjew.com.lyclaw.chat.catalog;

import java.util.HashMap;
import java.util.Map;

/**
 * 提供商特定的兼容性配置。
 *
 * <p>不同的提供商使用不同的字段名、头部格式
 * 和 API 约定。此配置捕获这些差异，以便
 * 模型解析服务可以构建正确的原生请求。</p>
 */
public class ModelCompatConfig {

    /** 此提供商是否需要在特定字段中使用模型名称
     *  （例如，某些提供商使用 "model"，而其他使用 "model_id"） */
    private String modelFieldName = "model";

    /** 提供商是否发送带有 "data: " 前缀的 SSE 事件 */
    private boolean sseDataPrefix = true;

    /** SSE 流是否使用 "\n\n" 作为分隔符 */
    private boolean sseDoubleNewline = true;

    /** 此提供商是否支持工具调用流式传输 */
    private boolean supportsToolCallStreaming;

    /** 思考/推理内容是在单独的字段中还是内联 */
    private String thinkingField = "reasoning_content";

    /** 在流式传输中，思考内容是与内容合并还是分离 */
    private boolean thinkingInline;

    /** 提供商特定的 HTTP 头部 */
    private final Map<String, String> headers = new HashMap<>();

    /** 要附加到 API URL 的额外查询参数 */
    private final Map<String, String> queryParams = new HashMap<>();

    /** 此提供商是否支持将系统消息作为顶级字段
     *  （OpenAI 风格），还是作为 role="system" 的消息 */
    private boolean systemMessageAsField = true;

    /** 视觉模型的最大图像大小（字节） */
    private long maxImageBytes = 20 * 1024 * 1024; // 20MB

    /** 发送前是否自动调整图像大小 */
    private boolean autoResizeImages = true;

    /** 自动调整大小的最大图像尺寸 */
    private int maxImageWidth = 2048;
    private int maxImageHeight = 2048;

    // ── Getters / Setters ──

    public String getModelFieldName() { return modelFieldName; }
    public void setModelFieldName(String v) { this.modelFieldName = v; }

    public boolean isSseDataPrefix() { return sseDataPrefix; }
    public void setSseDataPrefix(boolean v) { this.sseDataPrefix = v; }

    public boolean isSseDoubleNewline() { return sseDoubleNewline; }
    public void setSseDoubleNewline(boolean v) { this.sseDoubleNewline = v; }

    public boolean isSupportsToolCallStreaming() { return supportsToolCallStreaming; }
    public void setSupportsToolCallStreaming(boolean v) { this.supportsToolCallStreaming = v; }

    public String getThinkingField() { return thinkingField; }
    public void setThinkingField(String v) { this.thinkingField = v; }

    public boolean isThinkingInline() { return thinkingInline; }
    public void setThinkingInline(boolean v) { this.thinkingInline = v; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeader(String key, String value) { headers.put(key, value); }

    public Map<String, String> getQueryParams() { return queryParams; }

    public boolean isSystemMessageAsField() { return systemMessageAsField; }
    public void setSystemMessageAsField(boolean v) { this.systemMessageAsField = v; }

    public long getMaxImageBytes() { return maxImageBytes; }
    public void setMaxImageBytes(long v) { this.maxImageBytes = v; }

    public boolean isAutoResizeImages() { return autoResizeImages; }
    public void setAutoResizeImages(boolean v) { this.autoResizeImages = v; }

    public int getMaxImageWidth() { return maxImageWidth; }
    public void setMaxImageWidth(int v) { this.maxImageWidth = v; }

    public int getMaxImageHeight() { return maxImageHeight; }
    public void setMaxImageHeight(int v) { this.maxImageHeight = v; }

    /** OpenAI 兼容的默认值 */
    public static ModelCompatConfig openAiDefaults() {
        ModelCompatConfig c = new ModelCompatConfig();
        c.modelFieldName = "model";
        c.sseDataPrefix = true;
        c.sseDoubleNewline = true;
        c.thinkingField = "reasoning_content";
        c.systemMessageAsField = false; // messages[0].role=system
        return c;
    }

    /** Anthropic 特定的默认值 */
    public static ModelCompatConfig anthropicDefaults() {
        ModelCompatConfig c = new ModelCompatConfig();
        c.modelFieldName = "model";
        c.sseDataPrefix = true;
        c.sseDoubleNewline = true;
        c.supportsToolCallStreaming = false;
        c.thinkingField = "thinking";
        c.thinkingInline = false;
        c.systemMessageAsField = true; // 顶级 system 字段
        return c;
    }
}
```

### 2.2.2 AgentDefaultsConfig 中的多模型支持

我们引入新的 `AgentDefaultsConfig` 来取代单一模型的假设：

```java
package lyjew.com.lyclaw.chat.config;

/**
 * 按模态划分的模型选择的按代理或全局默认配置。
 *
 * <p>这将单一的 "model" 概念替换为模态特定的模型。
 * 每个字段可以是规范 ID（"openai/gpt-4o"）或别名（"gpt-4o"）。
 * 设为 null 的字段从 application.yml 中的全局默认值继承。</p>
 */
public class AgentModelConfig {

    /** 主要的聊天/文本生成模型 */
    private String chatModel;

    /** 用于图像理解（视觉）的模型 */
    private String imageModel;

    /** 用于图像生成的模型（DALL-E 等） */
    private String imageGenerationModel;

    /** 用于视频生成的模型（Sora 等） */
    private String videoGenerationModel;

    /** 用于音乐/声音生成的模型 */
    private String musicGenerationModel;

    /** 用于 PDF 阅读和理解的模型 */
    private String pdfModel;

    // ── PDF 限制 ──

    /** 最大 PDF 文件大小（MB） */
    private int pdfMaxBytesMb = 10;

    /** PDF 最大阅读页数 */
    private int pdfMaxPages = 20;

    // ── 生成设置 ──

    /** 主要图像生成模型失败时自动回退到另一个提供商 */
    private boolean mediaGenerationAutoProviderFallback = true;

    // ── Getters / Setters ──

    public String getChatModel() { return chatModel; }
    public void setChatModel(String model) { this.chatModel = model; }

    public String getImageModel() { return imageModel; }
    public void setImageModel(String model) { this.imageModel = model; }

    public String getImageGenerationModel() { return imageGenerationModel; }
    public void setImageGenerationModel(String model) { this.imageGenerationModel = model; }

    public String getVideoGenerationModel() { return videoGenerationModel; }
    public void setVideoGenerationModel(String model) { this.videoGenerationModel = model; }

    public String getMusicGenerationModel() { return musicGenerationModel; }
    public void setMusicGenerationModel(String model) { this.musicGenerationModel = model; }

    public String getPdfModel() { return pdfModel; }
    public void setPdfModel(String model) { this.pdfModel = model; }

    public int getPdfMaxBytesMb() { return pdfMaxBytesMb; }
    public void setPdfMaxBytesMb(int mb) { this.pdfMaxBytesMb = mb; }

    public int getPdfMaxPages() { return pdfMaxPages; }
    public void setPdfMaxPages(int pages) { this.pdfMaxPages = pages; }

    public boolean isMediaGenerationAutoProviderFallback() { return mediaGenerationAutoProviderFallback; }
    public void setMediaGenerationAutoProviderFallback(boolean v) { this.mediaGenerationAutoProviderFallback = v; }

    /**
     * 解析有效的聊天模型，回退到全局默认值。
     */
    public String resolveChatModel(String globalDefault) {
        return chatModel != null ? chatModel : globalDefault;
    }
}
```

### 2.2.3 模型选择与解析

```java
package lyjew.com.lyclaw.chat.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import lyjew.com.lyclaw.chat.ChatModel;
import lyjew.com.lyclaw.chat.ChatModelRegistry;
import lyjew.com.lyclaw.chat.RoutingDecision;
import lyjew.com.lyclaw.chat.RoutingTier;
import lyjew.com.lyclaw.chat.catalog.ModelCatalogEntry;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.RunMetadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用于解析给定代理 + 会话应使用哪个模型的中央服务。
 *
 * <h3>解析顺序</h3>
 * <ol>
 *   <li>检查 AgentContext.runMetadata 中的覆盖（由子代理生成器设置）</li>
 *   <li>检查 AgentConfig.model / AgentConfig.provider（来自注解/yml）</li>
 *   <li>检查代理扩展：thinking.level、model.image、model.pdf 等</li>
 *   <li>回退到全局默认值（ChatProperties.defaultProvider/defaultModel）</li>
 *   <li>如果没有配置，回退到 FirstAvailableRouter</li>
 * </ol>
 *
 * <h3>别名解析</h3>
 * <p>别名是短名称，如 "gpt-4o"，解析为 "openai/gpt-4o"。
 * 别名映射从 ModelCatalogEntry.alias 字段填充。</p>
 */
public class ModelResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ModelResolutionService.class);

    private final ChatModelRegistry registry;
    private final ModelCatalog modelCatalog;
    private final Map<String, String> aliasMap = new ConcurrentHashMap<>();

    /** 默认回退链（按优先级排序的规范 ID 列表） */
    private final List<String> defaultFallbackChain;

    public ModelResolutionService(ChatModelRegistry registry,
                                   ModelCatalog modelCatalog,
                                   List<String> defaultFallbackChain) {
        this.registry = registry;
        this.modelCatalog = modelCatalog;
        this.defaultFallbackChain = defaultFallbackChain != null
                ? List.copyOf(defaultFallbackChain) : List.of();
        buildAliasMap();
    }

    /**
     * 解析此代理上下文的主体聊天模型的有效 (provider, model) 对。
     */
    public ModelRef resolveEffectiveModel(AgentContext ctx) {
        RunMetadata meta = ctx.getRunMetadata();

        // 1. 来自 runMetadata 的覆盖
        if (meta.getResolvedModel() != null && meta.getResolvedProvider() != null) {
            return new ModelRef(meta.getResolvedProvider(), meta.getResolvedModel());
        }

        // 2. 来自 ChatRequest（由 AgentInvocationHandler 从 @Agent 注解设置）
        ChatRequest request = ctx.getChatRequest();
        if (request != null && request.getModel() != null && !request.getModel().isEmpty()) {
            // model 字段可能是规范 ID "deepseek/deepseek-v4-flash"
            // 或者只是一个模型名称，配合请求的隐式提供商
            ModelRef ref = parseModelRef(request.getModel());
            if (ref != null) return ref;
        }

        // 3. 来自 AgentConfig 扩展（由 AgentConfigResolver 设置）
        @SuppressWarnings("unchecked")
        Map<String, String> extensions = ctx.getAttribute("agentExtensions");
        if (extensions != null) {
            String configModel = extensions.get("model");
            String configProvider = extensions.get("provider");
            if (configModel != null) {
                return new ModelRef(
                        configProvider != null ? configProvider : "deepseek",
                        configModel);
            }
        }

        // 4. 回退到第一个可用模型
        return resolveFirstAvailable();
    }

    /**
     * 解析用于图像理解（视觉）的模型。
     */
    public ModelRef resolveImageModel(AgentContext ctx) {
        @SuppressWarnings("unchecked")
        Map<String, String> extensions = ctx.getAttribute("agentExtensions");
        if (extensions != null && extensions.containsKey("model.image")) {
            return parseModelRef(extensions.get("model.image"));
        }
        // 回退到主要模型（大多数现代模型都支持视觉）
        return resolveEffectiveModel(ctx);
    }

    /**
     * 解析此上下文的有效回退链。
     * 操作员覆盖 > 代理配置 > 全局默认值。
     */
    public List<String> resolveEffectiveFallbacks(AgentContext ctx) {
        @SuppressWarnings("unchecked")
        Map<String, String> extensions = ctx.getAttribute("agentExtensions");
        if (extensions != null && extensions.containsKey("fallback.chain")) {
            return List.of(extensions.get("fallback.chain").split(","));
        }
        return defaultFallbackChain;
    }

    /**
     * 将别名解析为其规范 ID。
     * 例如，"gpt-4o" → "openai/gpt-4o"
     */
    public String resolveAlias(String alias) {
        if (alias == null) return null;
        if (alias.contains("/")) return alias; // 已经是规范 ID
        return aliasMap.getOrDefault(alias, alias);
    }

    /**
     * 自动回退探测：测试模型是否适用于给定的会话。
     * 如果需要回退则返回探测配置，如果主要模型可用则返回 null。
     */
    public AutoFallbackProbe resolveAutoFallbackProbe(String sessionKey,
                                                        String primaryProvider,
                                                        String primaryModel) {
        // 检查模型是否最近在健康检查中失败
        if (!modelCatalog.isAvailable(primaryProvider, primaryModel)) {
            // 查找第一个可用的回退
            for (String fallbackId : defaultFallbackChain) {
                ModelRef ref = parseModelRef(fallbackId);
                if (ref != null && modelCatalog.isAvailable(ref.provider, ref.model)) {
                    return new AutoFallbackProbe(sessionKey, primaryProvider, primaryModel,
                            ref.provider, ref.model, "primary_unavailable");
                }
            }
        }
        return null; // 主要模型可用，不需要回退
    }

    /**
     * 解析模型引用字符串。
     * 接受："provider/model"、"model"（提供商从上下文推导）或别名。
     */
    public ModelRef parseModelRef(String ref) {
        if (ref == null || ref.isEmpty()) return null;

        // 首先尝试别名
        String resolved = resolveAlias(ref);

        int slash = resolved.indexOf('/');
        if (slash > 0) {
            return new ModelRef(resolved.substring(0, slash), resolved.substring(slash + 1));
        }
        // 未指定提供商：使用默认提供商
        return new ModelRef("deepseek", resolved);
    }

    private ModelRef resolveFirstAvailable() {
        Map<String, List<ChatModel>> all = registry.getAll();
        for (Map.Entry<String, List<ChatModel>> entry : all.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                ChatModel first = entry.getValue().get(0);
                return new ModelRef(first.provider(), first.model());
            }
        }
        throw new IllegalStateException("没有可用的 AI 模型。请至少配置一个提供商。");
    }

    private void buildAliasMap() {
        for (ModelCatalogEntry entry : modelCatalog.getAll()) {
            if (entry.getAlias() != null && !entry.getAlias().isEmpty()) {
                aliasMap.put(entry.getAlias(), entry.getId());
            }
            // 在无歧义时也将仅名称注册为别名
            aliasMap.putIfAbsent(entry.getProvider() + "/" + entry.getName(), entry.getId());
        }
    }

    // ── 内部类型 ──

    /**
     * 一个解析后的 (provider, model) 对。
     */
    public record ModelRef(String provider, String model) {
        public String canonicalId() {
            return provider + "/" + model;
        }
    }

    /**
     * 关于自动回退探测的信息。
     * 当主要模型不可用时，这告诉系统
     * 应改用哪个回退模型。
     */
    public record AutoFallbackProbe(String sessionKey,
                                     String primaryProvider, String primaryModel,
                                     String fallbackProvider, String fallbackModel,
                                     String reason) {}
}
```

### 2.2.4 思考/推理/详细程度控制

```java
package lyjew.com.lyclaw.chat.config;

/**
 * 思考/推理级别，控制在产生输出之前模型"思考"的程度。
 * 映射到提供商特定的 API 参数。
 *
 * <h3>级别</h3>
 * <ul>
 *   <li><b>OFF</b> — 思考/推理已禁用。最快，成本最低。</li>
 *   <li><b>LOW</b> — 简短推理。适合简单的工具使用任务。</li>
 *   <li><b>MEDIUM</b> — 中等推理。在大多数任务上保持平衡。</li>
 *   <li><b>HIGH</b> — 广泛推理。适用于复杂的多步骤问题。</li>
 *   <li><b>MAX</b> — 最大推理预算。最高质量，最高成本/延迟。</li>
 * </ul>
 *
 * <h3>提供商映射</h3>
 * <ul>
 *   <li>DeepSeek: "thinking" 参数，带 "enabled" + "thinking_budget"</li>
 *   <li>OpenAI o-series: "reasoning_effort": low/medium/high</li>
 *   <li>Anthropic: "thinking" 块，带 "budget_tokens"</li>
 *   <li>Gemini: "thinking_config"，带 "thinking_level"</li>
 * </ul>
 */
public enum ThinkingLevel {

    OFF(0, 0, "off"),
    LOW(1, 1024, "low"),
    MEDIUM(2, 4096, "medium"),
    HIGH(3, 16384, "high"),
    MAX(4, 32768, "max");

    private final int ordinal;
    private final int defaultBudgetTokens;
    private final String label;

    ThinkingLevel(int ordinal, int defaultBudgetTokens, String label) {
        this.ordinal = ordinal;
        this.defaultBudgetTokens = defaultBudgetTokens;
        this.label = label;
    }

    public int getDefaultBudgetTokens() { return defaultBudgetTokens; }
    public String getLabel() { return label; }

    /** 从字符串解析（不区分大小写）："off"、"low"、"medium"、"high"、"max" */
    public static ThinkingLevel fromString(String s) {
        if (s == null) return OFF;
        return switch (s.toLowerCase()) {
            case "off", "none", "disabled" -> OFF;
            case "low", "minimal" -> LOW;
            case "medium", "moderate", "balanced" -> MEDIUM;
            case "high", "extensive" -> HIGH;
            case "max", "maximum", "full" -> MAX;
            default -> OFF;
        };
    }

    /** 转换为 DeepSeek API 的 thinking 参数值 */
    public String toDeepSeekThinking() {
        if (this == OFF) return null; // 省略 thinking 块
        return "enabled";
    }

    /** 转换为 DeepSeek 的 thinking_budget token 数 */
    public int toDeepSeekBudget() {
        return defaultBudgetTokens;
    }

    /** 转换为 OpenAI 的 reasoning_effort */
    public String toOpenAiReasoningEffort() {
        return switch (this) {
            case OFF -> null;
            case LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH, MAX -> "high";
        };
    }
}
```

思考级别在管道开始时被解析并注入到 `ChatRequest` 中：

```java
// ── 在 AgentInvocationHandler.invoke() 中，阶段执行之前 ──

// 从注解/yml 解析思考级别
String thinkingStr = resolveThinkingLevel(method, args);
ctx.getRunMetadata().setThinkingLevel(thinkingStr);

// 应用到 ChatRequest
ThinkingLevel level = ThinkingLevel.fromString(thinkingStr);
if (level != ThinkingLevel.OFF) {
    request.setThinkingEnabled(true);
    request.setThinkingBudget(level.getDefaultBudgetTokens());
}
```

### 2.2.5 提供商发现

```java
package lyjew.com.lyclaw.chat.catalog;

import java.util.List;

import reactor.core.publisher.Mono;

/**
 * 用于从提供商的 API 自动发现可用模型的 SPI。
 *
 * <p>支持 /models 端点的提供商（OpenAI、DeepSeek 等）
 * 实现此接口以在启动时填充 ModelCatalog。这取代了
 * 硬编码的模型列表，并支持动态模型可用性追踪。</p>
 */
public interface ProviderDiscovery {

    /**
     * 从提供商的 API 发现所有可用模型。
     *
     * @param provider 提供商名称（例如，"openai"）
     * @param apiKey 用于认证的 API 密钥
     * @return 返回一个 Mono，在完成时包含已发现模型条目的列表
     */
    Mono<List<ModelCatalogEntry>> discoverModels(String provider, String apiKey);

    /**
     * 验证特定模型是否可用并可响应。
     * 通常发送一个最小的请求（例如，1 token 的补全）来验证。
     *
     * @param provider 提供商名称
     * @param model 模型名称
     * @param apiKey API 密钥
     * @return 如果模型响应成功则返回 true
     */
    Mono<Boolean> validateModel(String provider, String model, String apiKey);

    /**
     * 从 /models/{model} 端点获取提供商支持的功能
     * （流式传输、工具调用、思考等）。
     */
    Mono<ModelCompatConfig> probeCapabilities(String provider, String model, String apiKey);

    /**
     * 返回此发现实现是否支持给定的提供商。
     */
    boolean supportsProvider(String provider);
}
```

一个针对 OpenAI 兼容 API 的默认实现：

```java
package lyjew.com.lyclaw.chat.catalog;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lyjew.com.lyclaw.chat.ChatProperties;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * 通过 /v1/models 端点实现的 OpenAI 兼容提供商发现。
 *
 * <p>适用于 OpenAI、DeepSeek、Groq 以及任何实现
 * OpenAI /v1/models API 的提供商。如果端点不可用或
 * 返回非标准响应，则优雅地回退。</p>
 */
public class OpenAICompatibleProviderDiscovery implements ProviderDiscovery {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient httpClient;

    public OpenAICompatibleProviderDiscovery() {
        this.httpClient = HttpClient.create();
    }

    @Override
    public boolean supportsProvider(String provider) {
        // 所有使用 openai-protocol 的提供商均受支持
        return true;  // ChatProperties 确定实际协议
    }

    @Override
    public Mono<List<ModelCatalogEntry>> discoverModels(String provider, String apiKey) {
        // 使用 ChatProperties 查找提供商的 baseUrl
        ChatProperties.ModelProperties props = /* 从 ChatProperties 解析 */ null;

        String url = (props != null ? props.getBaseUrl() : "https://api.openai.com") + "/v1/models";

        return httpClient
                .headers(h -> h.set("Authorization", "Bearer " + apiKey))
                .get()
                .uri(url)
                .responseSingle((response, body) -> body.asString())
                .map(json -> {
                    try {
                        JsonNode root = mapper.readTree(json);
                        JsonNode data = root.get("data");
                        if (data == null || !data.isArray()) return List.<ModelCatalogEntry>of();

                        List<ModelCatalogEntry> entries = new java.util.ArrayList<>();
                        for (JsonNode node : data) {
                            String id = node.get("id").asText();
                            String ownedBy = provider;
                            if (node.has("owned_by")) ownedBy = node.get("owned_by").asText();

                            ModelCatalogEntry entry = ModelCatalogEntry.builder()
                                    .id(ModelCatalogEntry.canonicalId(provider, id))
                                    .name(id)
                                    .provider(provider)
                                    .displayName(id)
                                    .available(true)
                                    .build();
                            entries.add(entry);
                        }
                        return entries;
                    } catch (Exception e) {
                        return List.<ModelCatalogEntry>of();
                    }
                })
                .onErrorReturn(List.of());
    }

    @Override
    public Mono<Boolean> validateModel(String provider, String model, String apiKey) {
        // 发送一个 max_tokens=1 的最小聊天补全请求
        return Mono.just(true);  // 简化版；真实实现会进行测试调用
    }

    @Override
    public Mono<ModelCompatConfig> probeCapabilities(String provider, String model, String apiKey) {
        return Mono.just(ModelCompatConfig.openAiDefaults());
    }
}
```

### 2.2.6 模型回退链集成

来自 `ModelResolutionService` 的回退链被集成到现有的 `FallbackChatModel` 装饰器中：

```java
package lyjew.com.lyclaw.chat;

import java.util.List;

import lyjew.com.lyclaw.chat.config.ModelResolutionService;
import lyjew.com.lyclaw.chat.config.ModelResolutionService.ModelRef;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ModelResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 增强的回退模型，使用 ModelResolutionService 动态
 * 解析回退候选项，而不是使用静态硬编码列表。
 *
 * <p>与现有的 FallbackChatModel 装饰器模式集成，但
 * 添加了模型目录感知的解析。</p>
 */
public class DynamicFallbackChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(DynamicFallbackChatModel.class);

    private final ChatModel primary;
    private final ModelResolutionService resolutionService;
    private final ChatModelRegistry registry;

    /** 回退链，以规范 ID 列表形式，null 表示使用解析服务 */
    private final List<String> staticFallbackChain;

    public DynamicFallbackChatModel(ChatModel primary,
                                      ModelResolutionService resolutionService,
                                      ChatModelRegistry registry,
                                      List<String> staticFallbackChain) {
        this.primary = primary;
        this.resolutionService = resolutionService;
        this.registry = registry;
        this.staticFallbackChain = staticFallbackChain;
    }

    @Override
    public String provider() { return primary.provider(); }

    @Override
    public String model() { return primary.model(); }

    @Override
    public ModelCapabilities capabilities() { return primary.capabilities(); }

    @Override
    public Flux<ModelResponse> stream(ChatRequest request) {
        return primary.stream(request)
                .onErrorResume(error -> {
                    log.warn("主要模型 {}/{} 失败: {}。尝试回退...",
                            primary.provider(), primary.model(), error.getMessage());

                    // 按顺序尝试每个回退
                    return tryFallbacks(request, 0);
                });
    }

    private Flux<ModelResponse> tryFallbacks(ChatRequest request, int attemptIndex) {
        List<String> chain = staticFallbackChain != null
                ? staticFallbackChain
                : List.of(); // 将使用动态解析

        if (attemptIndex >= chain.size() && staticFallbackChain != null) {
            return Flux.error(new RuntimeException(
                    "所有回退模型已耗尽，对于 " + primary.provider() + "/" + primary.model()));
        }

        String fallbackId = staticFallbackChain != null
                ? chain.get(attemptIndex)
                : null;

        if (fallbackId == null) {
            // 动态回退解析 - 查找任何可用的模型
            ModelRef ref = resolutionService.parseModelRef(
                    primary.provider() + "/" + primary.model());
            if (ref == null) {
                return Flux.error(new RuntimeException("没有可用的回退模型"));
            }
            fallbackId = ref.canonicalId();
        }

        ModelRef ref = resolutionService.parseModelRef(fallbackId);
        if (ref == null) {
            return Flux.error(new RuntimeException("无效的回退 ID: " + fallbackId));
        }

        ChatModel fallback = registry.resolve(ref.provider(), ref.model());
        if (fallback == null) {
            return tryFallbacks(request, attemptIndex + 1);
        }

        log.info("正在回退到 {}/{}（第 {} 次尝试）", ref.provider(), ref.model(), attemptIndex + 1);

        return fallback.stream(request)
                .onErrorResume(err -> {
                    log.warn("回退模型 {}/{} 同样失败: {}",
                            ref.provider(), ref.model(), err.getMessage());
                    return tryFallbacks(request, attemptIndex + 1);
                });
    }

    @Override
    public int countTokens(String text) { return primary.countTokens(text); }

    @Override
    public Mono<Boolean> validate() { return primary.validate(); }
}
```

### 2.2.7 思考相关的 SSE 事件

`DefaultReActEngine` 已经通过 `ModelResponse.getThinking()` 处理了思考内容。我们通过结构化的 SSE 事件来增强这一点：

```java
// ── 添加到 DefaultReActEngine 的内容 ──

/**
 * 用于思考/推理流式传输的 SSE 事件类型。
 *
 * <p>启用思考后，流式传输期间发出的事件：
 * <ul>
 *   <li>{@code thinking_start} — 模型开始思考时发出一次
 *       （在产生任何内容之前）</li>
 *   <li>{@code thinking_delta} — 每个思考 token/块 发出一次</li>
 *   <li>{@code thinking_end} — 模型停止思考并
 *       开始产生内容时发出</li>
 * </ul>
 */
private static final String SSE_THINKING_START = "thinking_start";
private static final String SSE_THINKING_DELTA = "thinking_delta";
private static final String SSE_THINKING_END = "thinking_end";

// 在流式 handle() 回调中，检测思考内容与正文内容：

// ...在 .handle((chunk, sink) -> { ... 内部

if (chunk.getThinking() != null && !chunk.getThinking().isEmpty()) {
    // 发出思考事件而不是消息事件
    if (!thinkingStarted.get()) {
        thinkingStarted.set(true);
        sink.next(sseEvent(SSE_THINKING_START, ""));
    }
    sink.next(sseEvent(SSE_THINKING_DELTA, chunk.getThinking()));
    return;
}

if (thinkingStarted.get() && chunk.getContent() != null) {
    // 转换：思考 → 正文内容
    thinkingStarted.set(false);
    sink.next(sseEvent(SSE_THINKING_END, ""));
}
```

### 2.2.8 ChatRequest 与 ChatModel 增强

**ChatRequest 新增的用于多模型支持的字段：**

```java
// ── ChatRequest 中的新字段 ──

/** 思考/推理级别（off/low/medium/high/max） */
private String thinkingLevel;

/** 覆盖用于图像理解的模型（与主要文本模型分离） */
private String imageModel;

/** 覆盖用于 PDF 阅读的模型 */
private String pdfModel;

/** 当为 true 时，如果主要模型失败，媒体生成请求将自动回退到
 *  替代提供商 */
@Builder.Default
private boolean mediaGenerationAutoFallback = true;
```

**ChatModel 新增的思考支持方法：**

```java
// ── ChatModel 接口上的新方法 ──

/**
 * 此模型是否支持特定级别的思考/推理。
 * 不支持思考的模型将静默忽略该参数。
 */
default boolean supportsThinkingLevel(ThinkingLevel level) {
    return capabilities().isThinking();
}

/**
 * 此模型是否支持图像输入（视觉）。
 */
default boolean supportsVision() {
    return capabilities().isVision();
}
```

**ModelCapabilities 增强：**

```java
// ── ModelCapabilities 中的新字段 ──

/** 此模型是否支持图像生成 */
private boolean imageGeneration;

/** 此模型是否支持视频生成 */
private boolean videoGeneration;

/** 此模型是否支持音乐生成 */
private boolean musicGeneration;

/** 此模型是否支持 PDF 阅读 */
private boolean pdfReading;

/** 支持的最大思考努力级别 */
private ThinkingLevel maxThinkingLevel = ThinkingLevel.OFF;

// ... 包含 getters/setters 和 builder 方法 ...
```

### 2.2.9 配置（application.yml）

```yaml
lyclaw:
  chat:
    default-provider: deepseek
    default-model: deepseek-v4-flash

    # 全局模型目录（从注解 + 此配置填充）
    catalog:
      # 从提供商 API 自动发现模型
      auto-discover: true
      # 缓存已发现模型的分钟数
      discovery-cache-minutes: 60

      # 静态目录条目（不自动发现，始终可用）
      entries:
        - id: openai/gpt-4o
          alias: gpt-4o
          display-name: "GPT-4o"
          context-window: 128000
          reasoning: true
          max-output-tokens: 16384
          input: [TEXT, IMAGE, DOCUMENT]
          price-million-input: 2.50
          price-million-output: 10.00

        - id: openai/gpt-4.1
          alias: gpt-4.1
          display-name: "GPT-4.1"
          context-window: 1000000
          reasoning: true
          max-output-tokens: 32768
          input: [TEXT, IMAGE, DOCUMENT]
          price-million-input: 2.00
          price-million-output: 8.00

        - id: openai/gpt-5.0-flash
          alias: gpt-5-flash
          display-name: "GPT-5.0 Flash"
          context-window: 256000
          reasoning: true
          max-output-tokens: 16384
          input: [TEXT, IMAGE, DOCUMENT]
          beta: false
          price-million-input: 1.50
          price-million-output: 6.00

        - id: deepseek/deepseek-v4-flash
          alias: deepseek-v4-flash
          display-name: "DeepSeek V4 Flash"
          context-window: 262144
          reasoning: true
          max-output-tokens: 8192
          input: [TEXT]
          price-million-input: 0.28
          price-million-output: 1.10

        - id: anthropic/claude-opus-4-5
          alias: claude-opus-4-5
          display-name: "Claude Opus 4.5"
          context-window: 200000
          reasoning: true
          max-output-tokens: 32000
          input: [TEXT, IMAGE, DOCUMENT]
          price-million-input: 15.00
          price-million-output: 75.00

        - id: openai/dall-e-3
          alias: dall-e-3
          display-name: "DALL-E 3"
          context-window: 0
          reasoning: false
          max-output-tokens: 0
          input: [TEXT]
          price-million-input: 0
          price-million-output: 40.00  # 每张图片

    # 全局回退链（按优先级排序的规范 ID）
    fallback-chain:
      - deepseek/deepseek-v4-flash
      - openai/gpt-5.0-flash
      - openai/gpt-4.1

    # 按提供商的模型配置（现有配置，已增强）
    models:
      deepseek:
        provider: deepseek
        base-url: https://api.deepseek.com
        api-key: ${DEEPSEEK_API_KEY}
        model: deepseek-v4-flash
        retry:
          max-attempts: 3
          backoff: exponential
          base-delay-ms: 1000
        fallback:
          - openai/gpt-5.0-flash
        options:
          thinking.level: medium

      openai:
        provider: openai
        base-url: https://api.openai.com
        api-key: ${OPENAI_API_KEY}
        model: gpt-4o
        options:
          thinking.level: high

    # 全局思考默认值
    thinking:
      default-level: medium     # off | low | medium | high | max
      fallback-level: low       # 当主要模型不支持思考时使用

  # 代理级别的模型覆盖（通过 AgentConfig）
  agent:
    default-mode: react
    max-tool-rounds: 30

  # 子代理默认值（为清晰起见重复列出）
  subagent:
    enabled: true
    max-concurrent: 1
    max-spawn-depth: 1
    archive-after-minutes: 60
```

---

## 3. 集成点汇总

### 3.1 SubagentSpawner 在以下各点集成到现有系统中：

| 集成点 | 描述 |
|---|---|
| **ToolRegistry / ToolProvider** | `DelegateToAgentToolProvider` 将 `delegate_to_agent` 注册为内置工具。它是一个 `ToolProvider`（而非静态的 `@Tool`），使其能够访问 `AgentContext` 以生成子代理。 |
| **AgentInvocationHandler** | 从 `@Agent` 注解扩展中解析 `SubagentConfig`，并将其注入到 `AgentContext.runMetadata` 中。`AgentConfig` 中现有的 `agentExtensions` 映射已经支持此模式。 |
| **AgentContext** | 获得一个 `RunMetadata` 字段，包含 `subagentDepth`、`parentSessionKey`、`activeSubagentIds`、`thinkingLevel`。在上下文构建期间设置并在整个管道中携带。 |
| **ReActEngine / DefaultReActEngine** | 无需 API 更改。`delegate_to_agent` 工具在工具列表中作为常规工具出现。当 LLM 调用它时，`ToolExecutor.execute()` 路由到 `DelegateToAgentToolProvider`，后者在 `SubagentSpawner.spawnSubagent()` 上阻塞。 |
| **RespondStage** | 无需更改。对 `ToolRegistry` 的 `registerToolProvider()` 调用（或 `getAllDefinitions()` 覆盖）将委派工具注入到每个管道调用中。 |
| **管道阶段** | 所有阶段（`ContextBuildStage`、`SecurityCheckStage`、`PlanExecutionStage`、`RespondStage`、`ReflectionStage`、`MetricsStage`）对子代理的运行完全相同。唯一的区别是子代理具有嵌套的 `sessionKey` 和 `subagentDepth > 0`。 |
| **AgentRegistry** | 由 `SubagentSpawner` 用于查找子代理配置。现有的 `lookup()`、`findByCapability()`、`findAvailable()` 方法支持此功能。 |
| **AgentConfigResolver** | 用于解析子代理的 `model`、`provider`、`systemPrompt` 和 `extensions`（包括子代理限制）。现有的多源解析（注解 > yml > 数据库）适用。 |
| **SessionStore** | 由 `SubagentSessionManager` 用于分层的会话键存储和归档。 |
| **AgentHook** | 通过 `SubagentHook` 子接口扩展，用于子代理生命周期回调（`subagentSpawning`、`subagentSpawned`、`subagentEnded`）。 |

### 3.2 模型管理在以下各点集成：

| 集成点 | 描述 |
|---|---|
| **ChatFacade / DefaultChatFacade** | 获得 `ModelResolutionService` 依赖。`route()` 委托给它进行智能模型选择。`resolveModel()` 使用目录进行别名解析。 |
| **ChatModelRegistry** | 在启动时从 `ModelCatalog` 条目填充。目录条目来自静态 YAML 配置 + `@ChatModel` 注解 + `ProviderDiscovery` 自动探测。 |
| **ChatModel** 接口 | 获得 `supportsThinkingLevel()`、`supportsVision()` 默认方法。现有实现无需更改。 |
| **ChatRequest** | 获得 `thinkingLevel`、`imageModel`、`pdfModel`、`mediaGenerationAutoFallback` 字段。 |
| **ModelCapabilities** | 获得 `imageGeneration`、`videoGeneration`、`musicGeneration`、`pdfReading`、`maxThinkingLevel` 字段。 |
| **AgentContext.runMetadata** | 获得 `thinkingLevel`、`resolvedModel`、`resolvedProvider` 字段，用于每次调用的模型解析。 |
| **AgentInvocationHandler** | 从 `@Agent` 注解解析 `thinkingLevel`，在调用前设置 `ChatRequest.thinkingLevel` 和 `thinkingBudget`。 |
| **DefaultReActEngine** | 在启用思考的情况下，流式传输期间发出 `thinking_start`、`thinking_delta`、`thinking_end` SSE 事件。 |
| **AbstractChatModel** | 子类可以读取 `ChatRequest.thinkingLevel` 并将其映射到提供商特定的 API 参数（例如，DeepSeek 的 "thinking" 块、OpenAI 的 "reasoning_effort"）。 |
| **ProviderDiscovery** | 新的 SPI。`OpenAICompatibleProviderDiscovery` 是默认实现。在启动时自动填充 `ModelCatalog`。 |
| **FirstAvailableRouter** | 被 `ModelResolutionService.resolveFirstAvailable()` 替代用于默认路由，但作为回退保留。 |

---

## 4. 迁移路径

### 4.1 阶段 2a：模型管理（非破坏性）

1. **添加 `ModelCatalogEntry`、`ModelCompatConfig`、`ModelInputType`** — 新类，不涉及现有代码更改。
2. **添加 `ThinkingLevel` 枚举** — 新类。
3. **扩展 `ModelCapabilities`** — 仅附加字段，默认值为 false/0（向后兼容）。
4. **向 `ChatRequest` 添加 `thinkingLevel`** — 新字段，默认为 null（向后兼容）。
5. **添加 `ModelResolutionService`** — 新类，尚未替换任何内容。
6. **添加 `ProviderDiscovery` SPI + `OpenAICompatibleProviderDiscovery`** — 新的，不更改现有代码。
7. **添加 `AgentModelConfig`** — 用于模态特定模型解析的新类。
8. **扩展 `@Agent` 注解扩展** — 无需代码更改，只需在 `@Extension` 值中记录新的扩展键。

### 4.2 阶段 2b：子代理系统（附加，初始禁用）

1. **添加 `SubagentConfig`、`SubagentSpawner`、`SubagentSessionManager`** — 新类。
2. **添加 `RunMetadata`** — 新类。向 `AgentContext` 添加 `runMetadata` 字段（非破坏性，该字段以默认值开始）。
3. **添加 `SubagentResult`、`SubagentHook`** — 新类。
4. **添加 `DelegateToAgentToolProvider`** — 新类。通过自动配置有条件地注册（默认禁用，直到 `lyclaw.subagent.enabled=true`）。
5. **扩展 `AgentHook`** — 添加 `SubagentHook` 子接口（非破坏性，现有钩子忽略新的回调）。

### 4.3 阶段 2c：集成（功能开关控制）

1. **将 `SubagentSpawner` 接入自动配置** — 仅当 `lyclaw.subagent.enabled=true`。
2. **将 `DelegateToAgentToolProvider` 接入 `ToolRegistry`** — 通过 `ToolProvider` SPI。
3. **将 `ModelResolutionService` 接入 `DefaultChatFacade`** — 用 `resolutionService.resolveEffectiveModel()` 替换直接的 `router.route()` 调用，但保留 `FirstAvailableRouter` 作为回退。
4. **向 `DefaultReActEngine` 添加思考 SSE 事件** — 向后兼容（新事件类型，现有客户端忽略未知事件）。
5. **通过 `@Agent` 扩展键为特定代理启用子代理** — 按代理选择性加入。

### 4.4 回滚策略

- 所有新类位于独立的包中（`lyclaw.react.subagent`、`lyclaw.chat.catalog`、`lyclaw.chat.config`），便于删除。
- application.yml 中的功能开关控制所有新行为：
  - `lyclaw.subagent.enabled=false` 完全禁用委派
  - `lyclaw.chat.catalog.auto-discover=false` 禁用提供商发现
  - 思考级别默认为 OFF（行为无变化）
- 现有的 `FirstAvailableRouter` 在未配置模型目录时继续作为默认值工作。

---

## 附录：文件清单

第二阶段创建的所有新文件：

```
lyclaw-framework/src/main/java/lyjew/com/lyclaw/
├── react/
│   ├── subagent/
│   │   ├── SubagentConfig.java          (新)
│   │   ├── SubagentSpawner.java         (新)
│   │   ├── SubagentResult.java          (新)
│   │   ├── SubagentHook.java            (新)
│   │   ├── SubagentSessionManager.java  (新)
│   │   ├── DelegateToAgentToolProvider.java (新)
│   │   └── ToolProviderContext.java     (新)
│   └── RunMetadata.java                 (新)
├── chat/
│   ├── catalog/
│   │   ├── ModelCatalogEntry.java       (新)
│   │   ├── ModelInputType.java          (新)
│   │   ├── ModelCompatConfig.java       (新)
│   │   ├── ModelCatalog.java            (新，接口)
│   │   ├── InMemoryModelCatalog.java    (新)
│   │   ├── ProviderDiscovery.java       (新，SPI)
│   │   └── OpenAICompatibleProviderDiscovery.java (新)
│   ├── config/
│   │   ├── AgentModelConfig.java        (新)
│   │   ├── ThinkingLevel.java           (新)
│   │   └── ModelResolutionService.java  (新)
│   └── DynamicFallbackChatModel.java    (新)

修改的现有文件：
├── react/
│   └── AgentContext.java                （添加 runMetadata 字段、toSnapshot/restore）
├── model/
│   └── ChatRequest.java                 （添加 thinkingLevel、imageModel、pdfModel）
├── chat/
│   ├── ChatModel.java                   （添加 supportsThinkingLevel、supportsVision）
│   ├── ModelCapabilities.java           （添加 imageGeneration、videoGeneration 等）
│   └── DefaultChatFacade.java           （集成 ModelResolutionService）
```

---

# 第三阶段：上下文引擎与压缩 + 工作区引导 + 代理路由与身份

> **状态：** 草案
> **目标：** LyClaw Framework — lyclaw-framework、lyclaw-autoconfigure、lyclaw-web
> **前置阶段：** 第二阶段（反思与评估）
> **后续阶段：** 第四阶段（最终集成与打磨）
>
> LyClaw 目前没有压缩机制、没有上下文修剪、没有工作区引导文件、
> 没有代理路由，也没有身份系统。本阶段将填补所有这些空白。

---

## 目录

1. [架构概览](#架构概览)
2. [3.1 上下文引擎与压缩](#31-上下文引擎与压缩)
   - [3.1.1 CompactionConfig](#311-compactionconfig)
   - [3.1.2 CompactionEngine](#312-compactionengine)
   - [3.1.3 上下文修剪](#313-上下文修剪)
   - [3.1.4 AgentContextLimits](#314-agentcontextlimits)
   - [3.1.5 管道集成](#315-管道集成)
   - [3.1.6 YAML 配置](#316-yaml-配置)
3. [3.2 工作区引导](#32-工作区引导)
   - [3.2.1 引导文件结构](#321-引导文件结构)
   - [3.2.2 BootstrapConfig](#322-bootstrapconfig)
   - [3.2.3 BootstrapLoader](#323-bootstraploader)
   - [3.2.4 ContextInjectionPolicy](#324-contextinjectionpolicy)
   - [3.2.5 管道集成](#325-管道集成)
   - [3.2.6 YAML 配置](#326-yaml-配置)
4. [3.3 代理路由与绑定](#33-代理路由与绑定)
   - [3.3.1 AgentBindingMatch](#331-agentbindingmatch)
   - [3.3.2 AgentRouteBinding 与 AgentAcpBinding](#332-agentroutebinding-与-agentacpbinding)
   - [3.3.3 AgentRouter](#333-agentrouter)
   - [3.3.4 ChatController 更新](#334-chatcontroller-更新)
   - [3.3.5 YAML 配置](#335-yaml-配置)
5. [3.4 身份与头像](#34-身份与头像)
   - [3.4.1 IdentityConfig](#341-identityconfig)
   - [3.4.2 AvatarResolution](#342-avatarresolution)
   - [3.4.3 集成与 YAML](#343-集成与-yaml)
6. [完整 YAML 配置参考](#完整-yaml-配置参考)
7. [集成检查清单](#集成检查清单)

---

## 架构概览

```
                          ChatController
                               │
                               ▼
                    ┌─ AgentRouter ─┐
                    │  resolveAgent │
                    │  matchBinding │
                    └──────┬────────┘
                           │ agentId
                           ▼
              ┌─── 管道阶段 ───────────────────────────┐
              │                                                │
              │  ContextBuildStage                             │
              │    ├─ BootstrapLoader.loadBootstrap()          │
              │    ├─ IdentityConfig 注入                      │
              │    └─ SystemPromptBuilder.build()              │
              │                                                │
              │  SecurityCheckStage                             │
              │                                                │
              │  PlanExecutionStage                             │
              │                                                │
              │  RespondStage  (ReAct 循环)                    │
              │    ├─ CompactionEngine.midTurnPrecheck()       │
              │    └─ 强制执行 AgentContextLimits              │
              │                                                │
              │  ReflectionStage                                │
              │                                                │
              │  CompactionStage         ★ 新增 ★               │
              │    ├─ needsCompaction() 检查                   │
              │    ├─ memoryFlush (之前)                      │
              │    ├─ compact() 执行                         │
              │    ├─ validateCompaction() 质量把关            │
              │    └─ 注入 postCompactionSections             │
              │                                                │
              │  MetricsStage                                   │
              │                                                │
              │  ContextPruningScheduler  ★ 新增 ★              │
              │    (后台，周期性，CACHE_TTL)                   │
              └────────────────────────────────────────────────┘
```

---

## 3.1 上下文引擎与压缩

### 动机

长时间运行的代理会话会积累大量对话历史记录（工具输出、
多轮推理、内联文件内容）。如果没有压缩机制，LLM 上下文
窗口会被填满，API 成本急剧上升，并且由于早期关键指令被挤出上下文窗口，
代理的表現会退化。

CompactionEngine 通过以下方式解决此问题：
1. 检测上下文压力是否过高（`maxActiveTranscriptBytes`）。
2. 将"中间"历史记录总结为紧凑的表示形式，同时保留
   最近的对话轮次和会话启动指令。
3. 通过质量把关（LLM 重新检查）验证压缩结果。
4. 可选地在压缩之前刷新记忆，以便关键事实在跨越压缩边界
   时得以持久保留。

### 3.1.1 CompactionConfig

```java
package lyjew.com.lyclaw.compaction;

import lombok.Builder;
import lombok.Data;
import java.time.Duration;
import java.util.List;

/**
 * 压缩引擎的配置。
 *
 * <p>控制何时以及如何压缩会话对话记录，以防止
 * 长时间运行的代理会话出现上下文窗口溢出。</p>
 *
 * <p>映射自 application.yml 中的 {@code lyclaw.compaction}。</p>
 */
@Data
@Builder
public class CompactionConfig {

    /** 压缩策略模式。 */
    @Builder.Default
    CompactionMode mode = CompactionMode.DEFAULT;

    /**
     * 在上下文窗口顶部为此数量的 token 保留空间，
     * 用于系统提示、引导内容和工具定义。
     * 默认值：8000（按每 token 4 字符计，约 32KB）。
     */
    @Builder.Default
    int reserveTokens = 8000;

    /**
     * 保留最近 N 个 token 的对话历史不被压缩。
     * 默认值：4000（约 16KB）。
     */
    @Builder.Default
    int keepRecentTokens = 4000;

    /**
     * 硬性下限：即使 reserveTokens 计算结果建议进行更深的裁剪，
     * 也不会压缩到低于此剩余 token 数。
     * 默认值：2000。
     */
    @Builder.Default
    int reserveTokensFloor = 2000;

    /**
     * 历史记录（非系统消息）可占用的 token 预算的最大份额。
     * 当历史记录超过此份额时，触发压缩。
     * 默认值：0.5（50%）。
     */
    @Builder.Default
    double maxHistoryShare = 0.5;

    /** 注入到压缩 LLM 提示中的自定义指令。 */
    String customInstructions;

    /**
     * 保持原样保留的最近助手/用户对话轮次数。
     * 这些是紧邻当前用户消息之前的轮次。
     * 默认值：3。
     */
    @Builder.Default
    int recentTurnsPreserve = 3;

    /**
     * 压缩期间如何处理标识符（文件路径、URL、函数名）
     * 的策略。
     * STRICT：标识符必须精确保留。
     * OFF：无特殊处理。
     * CUSTOM：使用 identifierInstructions 进行指导。
     */
    @Builder.Default
    IdentifierPolicy identifierPolicy = IdentifierPolicy.STRICT;

    /** 标识符保留的自定义指令（仅 CUSTOM 模式）。 */
    String identifierInstructions;

    /** 质量把关配置。 */
    @Builder.Default
    QualityGuard qualityGuard = new QualityGuard();

    /** 中途预检查配置。 */
    @Builder.Default
    MidTurnPrecheck midTurnPrecheck = new MidTurnPrecheck();

    /** 压缩后是否同步或异步重新索引记忆。 */
    @Builder.Default
    PostIndexSync postIndexSync = PostIndexSync.ASYNC;

    /** 记忆刷新配置（在压缩之前运行）。 */
    @Builder.Default
    MemoryFlush memoryFlush = new MemoryFlush();

    /**
     * 压缩完成后注入到系统提示中的压缩后章节。
     * 典型值："Session Startup"、"Red Lines"。
     * 这些内容在上下文转移后重新锚定代理的行为。
     */
    @Builder.Default
    List<String> postCompactionSections = List.of("Session Startup", "Red Lines");

    /**
     * 为压缩 LLM 调用覆盖使用的模型。为 null 时使用会话模型。
     * 推荐使用更便宜/更快的模型（如 "deepseek-v4-flash"）。
     */
    String model;

    /** 单次压缩 LLM 调用的超时时间。默认值：900 秒。 */
    @Builder.Default
    int timeoutSeconds = 900;

    /**
     * 如果为 true，则在压缩后截断尾部内容，
     * 而不是将其与摘要一起保留。默认值：false。
     */
    @Builder.Default
    boolean truncateAfterCompaction = false;

    /**
     * 触发压缩的最大活跃对话记录字节数。
     * 默认值：10 MB（10 * 1024 * 1024）。
     */
    @Builder.Default
    long maxActiveTranscriptBytes = 10 * 1024 * 1024;

    /**
     * 如果为 true，则发送 SSE 事件通知用户压缩已发生。
     * 默认值：false（静默）。
     */
    @Builder.Default
    boolean notifyUser = false;
}
```

#### 支持的枚举和子配置

```java
package lyjew.com.lyclaw.compaction;

public enum CompactionMode {
    /** 标准压缩：总结中间历史记录，保留两端。 */
    DEFAULT,
    /**
     * 压缩前进行扩展安全检查。使用第二次 LLM 调用
     * 验证关键指令是否在摘要中得到保留。
     * 比默认模式慢，但对高风险会话更安全。
     */
    SAFEGUARD
}

public enum IdentifierPolicy {
    /** 标识符必须精确保留。 */
    STRICT,
    /** 无特殊标识符处理。 */
    OFF,
    /** 使用 identifierInstructions 进行指导。 */
    CUSTOM
}

public enum PostIndexSync {
    /** 压缩后不重新索引记忆。 */
    OFF,
    /** 触发异步重新索引；压缩立即返回。 */
    ASYNC,
    /** 等待重新索引完成后再返回。 */
    AWAIT
}
```

```java
package lyjew.com.lyclaw.compaction;

import lombok.Data;

/** 质量把关：通过第二次 LLM 调用进行压缩后验证。 */
@Data
public class QualityGuard {
    /** 启用质量把关。默认值：true。 */
    boolean enabled = true;
    /**
     * 验证失败时的最大重试次数。
     * 每次重试以更严格的指令重新运行压缩。
     * 默认值：2。
     */
    int maxRetries = 2;
}

/** 中途预检查：在长时间工具循环期间，检查是否需要压缩。 */
@Data
public class MidTurnPrecheck {
    /** 启用中途预检查。默认值：true。 */
    boolean enabled = true;
}

/**
 * 记忆刷新：在压缩丢弃原始文本之前，从即将被压缩的区域
 * 提取关键事实并将其持久化到 MemorySystem。
 */
@Data
public class MemoryFlush {
    /** 启用压缩前的记忆刷新。默认值：true。 */
    boolean enabled = true;
    /** 用于记忆提取的模型。为 null 时使用压缩模型。 */
    String model;
    /**
     * 软阈值（以 token 计）：如果待压缩区域低于此值，
     * 跳过刷新以节省成本。默认值：4000。
     */
    int softThresholdTokens = 4000;
    /**
     * 如果对话记录字节数超过此值，则无论 softThresholdTokens
     * 的值如何，强制执行记忆刷新。默认值：500KB。
     */
    long forceFlushTranscriptBytes = 500 * 1024;
    /** 记忆提取的提示覆盖。 */
    String prompt;
    /** 记忆提取的系统提示覆盖。 */
    String systemPrompt;
}
```

### 3.1.2 CompactionEngine

```java
package lyjew.com.lyclaw.compaction;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.memory.MemorySystem;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.react.AgentContext;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * CompactionEngine 负责检测上下文窗口压力
 * 并压缩会话对话记录，使代理保持在预算范围内。
 *
 * <h3>压缩的生命周期</h3>
 * <ol>
 *   <li>{@link #needsCompaction} — 对照限制检查对话记录大小</li>
 *   <li>记忆刷新（如果启用）— 从中间区域提取事实</li>
 *   <li>压缩前钩子 — 分派到 {@link AgentHook}</li>
 *   <li>{@link #compact} — LLM 对中间历史记录进行总结</li>
 *   <li>{@link #validateCompaction} — 质量把关（SAFEGUARD 模式）</li>
 *   <li>压缩后章节注入 — 重新锚定指令</li>
 *   <li>压缩后钩子 — 分派到 {@link AgentHook}</li>
 * </ol>
 *
 * <p>该引擎原地操作 Session.messages 列表：它用包含压缩摘要的
 * 合成系统消息替换被总结的中间轮次，同时保留最近的轮次和
 * 任何会话启动系统消息。</p>
 */
public class CompactionEngine {

    private final ChatFacade chatFacade;
    private final MemorySystem memorySystem;
    private final CompactionConfig config;

    public CompactionEngine(ChatFacade chatFacade, MemorySystem memorySystem,
                            CompactionConfig config) {
        this.chatFacade = chatFacade;
        this.memorySystem = memorySystem;
        this.config = config;
    }

    /**
     * 检查会话对话记录是否超过配置的限制，
     * 是否需要压缩。
     *
     * @param session 当前会话
     * @param config  压缩配置
     * @return 如果需要压缩则返回 true
     */
    public boolean needsCompaction(Session session, CompactionConfig config) {
        long transcriptBytes = estimateTranscriptBytes(session);
        if (transcriptBytes >= config.getMaxActiveTranscriptBytes()) {
            return true;
        }
        // 同时检查基于 token 的预算
        int totalTokens = estimateTokenCount(session);
        int systemTokens = estimateSystemTokens(session);
        int historyTokens = totalTokens - systemTokens;
        int budget = systemTokens + config.getReserveTokens();
        double share = (double) historyTokens / (double) totalTokens;
        return share > config.getMaxHistoryShare()
                || historyTokens > (totalTokens - config.getReserveTokensFloor());
    }

    /**
     * 对会话执行压缩。
     *
     * <p>如果 memoryFlush 已启用且对话记录超过阈值，
     * 则在开始总结之前，从中间区域提取事实并
     * 持久化到 MemorySystem。</p>
     *
     * @param session 要压缩的会话
     * @param config  压缩配置
     * @param ctx     当前代理上下文（用于钩子分派、追踪、模型访问）
     * @return 压缩结果
     */
    public Mono<CompactionResult> compact(Session session, CompactionConfig config,
                                          AgentContext ctx) {
        return Mono.fromCallable(() -> {
            // 1. 分区消息：头部（系统/启动）、中间（待总结）、尾部（最近）
            MessagePartition partition = partitionMessages(
                    session.getMessages(), config);

            // 2. 可选的记忆刷新
            if (config.getMemoryFlush().isEnabled()) {
                long middleBytes = estimateBytes(partition.middle());
                if (middleBytes >= config.getMemoryFlush().getForceFlushTranscriptBytes()
                        || estimateTokenCount(partition.middle())
                           >= config.getMemoryFlush().getSoftThresholdTokens()) {
                    flushMemory(partition.middle(), config, ctx);
                }
            }

            // 3. 构建压缩提示并调用 LLM
            String summary = callCompactionLLM(partition, config, ctx);

            // 4. 重建消息列表
            reconstructMessages(session, partition, summary, config);

            return new CompactionResult(
                    partition.headCount(), partition.middleCount(),
                    partition.tailCount(), summary.length(),
                    estimateTokenCount(session));
        });
    }

    /**
     * 验证压缩没有丢失关键信息。
     * 在 SAFEGUARD 模式下或 qualityGuard 启用时使用。
     *
     * <p>该方法将压缩前和压缩后的对话记录发送给 LLM，
     * 附带关键信息检查清单，询问压缩是否保留了这些信息。</p>
     *
     * @param result 待验证的压缩结果
     * @param config 压缩配置
     * @return 验证通过返回 true
     */
    public Mono<Boolean> validateCompaction(CompactionResult result,
                                            CompactionConfig config) {
        if (!config.getQualityGuard().isEnabled()) {
            return Mono.just(true);
        }
        // 实现：将压缩前后的摘要与检查清单一起发送给 LLM
        // ...
        return Mono.just(true);
    }

    /**
     * 中途预检查：在长时间工具调用循环期间调用，
     * 检查上下文窗口是否处于压力之下。如果是，则发出信号
     * 表明 ReAct 循环应该暂停以进行压缩。
     *
     * @param ctx 包含当前工具结果和历史记录的代理上下文
     * @return 如果中途需要压缩则返回 true
     */
    public Mono<Boolean> midTurnPrecheck(AgentContext ctx) {
        if (!config.getMidTurnPrecheck().isEnabled()) {
            return Mono.just(false);
        }
        // 从工具结果和历史记录估算当前对话记录大小
        // ...
        return Mono.just(false);
    }

    // ── 内部辅助方法 ───────────────────────────────────────────

    private long estimateTranscriptBytes(Session session) {
        return session.getMessages().stream()
                .mapToLong(m -> (m.getContent() != null ? m.getContent().length() : 0)
                        + (m.getThinking() != null ? m.getThinking().length() : 0))
                .sum();
    }

    private int estimateTokenCount(Session session) {
        // 粗略估计：每 token 4 个字符
        long chars = session.getMessages().stream()
                .mapToLong(m -> (m.getContent() != null ? m.getContent().length() : 0)
                        + (m.getThinking() != null ? m.getThinking().length() : 0))
                .sum();
        return (int) (chars / 4);
    }

    private int estimateTokenCount(List<Message> messages) {
        long chars = messages.stream()
                .mapToLong(m -> (m.getContent() != null ? m.getContent().length() : 0)
                        + (m.getThinking() != null ? m.getThinking().length() : 0))
                .sum();
        return (int) (chars / 4);
    }

    private int estimateSystemTokens(Session session) {
        return (int) session.getMessages().stream()
                .filter(m -> "system".equals(m.getRole()))
                .mapToLong(m -> m.getContent() != null ? m.getContent().length() : 0)
                .sum() / 4;
    }

    private long estimateBytes(List<Message> messages) {
        return messages.stream()
                .mapToLong(m -> (m.getContent() != null ? m.getContent().length() : 0)
                        + (m.getThinking() != null ? m.getThinking().length() : 0))
                .sum();
    }

    /**
     * 将消息列表分为三个区域：
     * - 头部：系统消息和早期会话设置
     * - 中间：历史记录的主体（待总结）
     * - 尾部：最近的 `recentTurnsPreserve` 个轮次
     */
    private MessagePartition partitionMessages(List<Message> messages,
                                               CompactionConfig config) {
        // 实现细节：遍历消息列表，标识系统前缀，
        // 标识尾部轮次，其余均为中间部分。
        // ...
        return new MessagePartition(List.of(), List.of(), List.of(), 0, 0, 0);
    }

    private void flushMemory(List<Message> middle, CompactionConfig config,
                             AgentContext ctx) {
        // 通过 LLM 从中间消息中提取事实，持久化到 MemorySystem
        // ...
    }

    private String callCompactionLLM(MessagePartition partition,
                                     CompactionConfig config, AgentContext ctx) {
        // 构建压缩提示，调用 LLM，返回摘要字符串
        // ...
        return "";
    }

    private void reconstructMessages(Session session, MessagePartition partition,
                                     String summary, CompactionConfig config) {
        // 用包含摘要的合成系统消息替换中间消息
        // ...
    }

    /** 单次压缩运行的结果。 */
    public record CompactionResult(
            int headMessages, int middleMessages, int tailMessages,
            int summaryChars, int finalTokenEstimate) {}

    private record MessagePartition(
            List<Message> head, List<Message> middle, List<Message> tail,
            int headCount, int middleCount, int tailCount) {}
}
```

### 3.1.3 上下文修剪

上下文修剪是比压缩更轻量级的机制。它不使用 LLM 进行总结，
而是修剪或替换过时的工具结果以释放上下文空间。
它在 `mode=CACHE_TTL` 时通过后台调度器运行。

```java
package lyjew.com.lyclaw.compaction;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;
import java.util.Set;

/**
 * 上下文修剪的配置 — 在不使用 LLM 总结的情况下，
 * 轻量级地修剪会话对话记录中的过时工具结果。
 *
 * <p>映射自 application.yml 中的 {@code lyclaw.compaction.pruning}。</p>
 */
@Data
@Builder
public class ContextPruningConfig {

    public enum PruningMode {
        /** 禁用修剪。 */
        OFF,
        /**
         * 缓存 TTL 模式：超过 `ttl` 的工具结果可根据
         * 年龄和大小进行软修剪或硬清除。
         */
        CACHE_TTL
    }

    /** 修剪模式。默认值：OFF。 */
    @Builder.Default
    PruningMode mode = PruningMode.OFF;

    /** 工具结果内容的生存时间。默认值：30 分钟。 */
    @Builder.Default
    Duration ttl = Duration.ofMinutes(30);

    /**
     * 保留最近 N 条助手消息不被修剪。
     * 默认值：5。
     */
    @Builder.Default
    int keepLastAssistants = 5;

    /**
     * 当工具结果的字符数超过上下文预算的此比例时，
     * 应用软修剪（保留头部 + 尾部）。
     * 默认值：0.3（30%）。
     */
    @Builder.Default
    double softTrimRatio = 0.3;

    /**
     * 当工具结果总字符数超过上下文预算的此比例时，
     * 对最旧的结果应用硬清除（替换为占位符）。
     * 默认值：0.6（60%）。
     */
    @Builder.Default
    double hardClearRatio = 0.6;

    /**
     * 工具结果可被修剪的最小字符数。
     * 较小的结果保留起来成本很低。默认值：1000。
     */
    @Builder.Default
    int minPrunableToolChars = 1000;

    /**
     * 允许列表：可以被修剪的工具名称。
     * 为空时，所有工具都符合条件（受 toolDeny 约束）。
     */
    Set<String> toolAllow;

    /**
     * 拒绝列表：不能被修剪的工具名称。
     * 用于高价值工具，如 file_read，其输出
     * 必须保留在上下文中。
     */
    Set<String> toolDeny;

    /** 软修剪配置。 */
    @Builder.Default
    SoftTrim softTrim = new SoftTrim();

    /** 硬清除配置。 */
    @Builder.Default
    HardClear hardClear = new HardClear();

    /** 软修剪：保留头部和尾部各 N 个字符，中间用 "..." 替换。 */
    @Data
    public static class SoftTrim {
        /** 修剪后的最大字符数。默认值：8000。 */
        int maxChars = 8000;
        /** 从头部保留的字符数。默认值：2000。 */
        int headChars = 2000;
        /** 从尾部保留的字符数。默认值：2000。 */
        int tailChars = 2000;
    }

    /** 硬清除：用占位符消息替换整个工具结果。 */
    @Data
    public static class HardClear {
        /** 启用硬清除。默认值：true。 */
        boolean enabled = true;
        /** 占位符文本。默认值："[earlier output trimmed for space]"。 */
        @Builder.Default
        String placeholder = "[earlier output trimmed for space]";
    }
}
```

```java
package lyjew.com.lyclaw.compaction;

import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * ContextPruner 对过时的工具结果消息应用轻量级修剪。
 *
 * <p>与 CompactionEngine（使用 LLM 总结）不同，ContextPruner
 * 使用简单规则：CACHE_TTL 模式检查每个工具结果的年龄，
 * 并根据配置的比例应用软修剪（头部+尾部截断）或
 * 硬清除（占位符替换）。</p>
 */
public class ContextPruner {

    private static final Logger log = LoggerFactory.getLogger(ContextPruner.class);

    private final ContextPruningConfig config;

    public ContextPruner(ContextPruningConfig config) {
        this.config = config;
    }

    /**
     * 原地修剪会话的消息，移除或修剪过时的工具结果。
     *
     * @param session 要修剪的会话
     * @param now     当前时间参考
     * @return 修改的消息数量
     */
    public int prune(Session session, Instant now) {
        if (config.getMode() == ContextPruningConfig.PruningMode.OFF) {
            return 0;
        }

        int modified = 0;
        Duration ttl = config.getTtl();
        Set<String> allow = config.getToolAllow();
        Set<String> deny = config.getToolDeny();

        int assistantCount = 0;
        int keepAssistant = config.getKeepLastAssistants();

        // 反向遍历消息以跟踪助手位置
        for (int i = session.getMessages().size() - 1; i >= 0; i--) {
            Message msg = session.getMessages().get(i);

            if ("assistant".equals(msg.getRole())) {
                assistantCount++;
                if (assistantCount <= keepAssistant) {
                    continue; // 保留最近的助手及其工具结果
                }
            }

            if (!"tool".equals(msg.getRole())) {
                continue;
            }

            // 检查每个工具的允许/拒绝列表
            String toolName = msg.getToolName();
            if (deny != null && deny.contains(toolName)) continue;
            if (allow != null && !allow.isEmpty() && !allow.contains(toolName)) continue;

            String content = msg.getContent();
            if (content == null || content.length() < config.getMinPrunableToolChars()) {
                continue;
            }

            Instant msgTime = msg.getTimestamp();
            if (msgTime == null) continue;

            if (Duration.between(msgTime, now).compareTo(ttl) > 0) {
                // 此工具结果已过时
                if (content.length() > config.getSoftTrim().getMaxChars() * config.getSoftTrimRatio()) {
                    // 软修剪
                    msg.setContent(softTrim(content));
                    modified++;
                }
                // TODO: 根据总比例进行硬清除
            }
        }

        log.debug("ContextPruner：在会话 {} 中修改了 {} 条消息",
                modified, session.getSessionId());
        return modified;
    }

    private String softTrim(String content) {
        var st = config.getSoftTrim();
        if (content.length() <= st.getMaxChars()) {
            return content;
        }
        return content.substring(0, st.getHeadChars())
                + "\n... [已修剪 " + (content.length() - st.getHeadChars() - st.getTailChars())
                + " 个字符] ...\n"
                + content.substring(content.length() - st.getTailChars());
    }
}
```

### 3.1.4 AgentContextLimits

在上下文构建期间强制执行的硬性限制，防止单个组件
消耗不成比例的上下文空间。

```java
package lyjew.com.lyclaw.compaction;

import lombok.Builder;
import lombok.Data;

/**
 * 各个上下文组件的硬性限制。
 *
 * <p>这些限制在上下文构建时强制执行，在上下文到达 LLM 之前生效。
 * 它们通过提供静态上限来补充动态的 CompactionEngine。</p>
 *
 * <p>映射自 application.yml 中的 {@code lyclaw.compaction.limits}。</p>
 */
@Data
@Builder
public class AgentContextLimits {

    /** 每次检索调用从 MemorySystem 返回的最大字符数。默认值：12000。 */
    @Builder.Default
    int memoryGetMaxChars = 12000;

    /** 检索的默认记忆行数。默认值：120。 */
    @Builder.Default
    int memoryGetDefaultLines = 120;

    /** 对话记录中任何单个工具结果的最大字符数。默认值：16000。 */
    @Builder.Default
    int toolResultMaxChars = 16000;

    /**
     * 压缩后注入章节内容的最大字符数。
     * postCompactionSections 中的每个章节都会被截断到此值。
     * 默认值：1800。
     */
    @Builder.Default
    int postCompactionMaxChars = 1800;

    /**
     * 将工具结果截断到 toolResultMaxChars。
     *
     * @param content 原始工具输出
     * @return 截断后的内容，如果发生截断则附加说明
     */
    public String truncateToolResult(String content) {
        if (content == null || content.length() <= toolResultMaxChars) {
            return content;
        }
        return content.substring(0, toolResultMaxChars)
                + "\n... [已截断 " + (content.length() - toolResultMaxChars)
                + " 个字符；原始总共有 " + content.length() + " 个字符]";
    }

    /**
     * 将压缩后章节截断到 postCompactionMaxChars。
     */
    public String truncatePostCompactionSection(String content) {
        if (content == null || content.length() <= postCompactionMaxChars) {
            return content;
        }
        return content.substring(0, postCompactionMaxChars) + "...";
    }
}
```

### 3.1.5 管道集成

#### CompactionStage

一个新的管道阶段，位于 ReflectionStage 之后、MetricsStage 之前。

```java
package lyjew.com.lyclaw.compaction;

import lyjew.com.lyclaw.annotation.PipelineStage;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.AgentHook;
import lyjew.com.lyclaw.model.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 管道阶段，检查上下文窗口压力并在需要时触发压缩。
 *
 * <p>排序在 ReflectionStage（可能已产生值得保留的最终评估数据）
 * 之后、MetricsStage（记录最终会话统计信息）之前。</p>
 */
@PipelineStage(
    name = "compaction",
    after = {ReflectionStage.class},
    before = {MetricsStage.class},
    group = "POSTPROCESSING"
)
public class CompactionStage implements ReactivePipelineStage {

    private static final Logger log = LoggerFactory.getLogger(CompactionStage.class);

    private final CompactionEngine compactionEngine;
    private final CompactionConfig config;
    private final List<AgentHook> hooks;
    private final AgentContextLimits limits;

    public CompactionStage(CompactionEngine compactionEngine, CompactionConfig config,
                           List<AgentHook> hooks, AgentContextLimits limits) {
        this.compactionEngine = compactionEngine;
        this.config = config;
        this.hooks = hooks;
        this.limits = limits;
    }

    @Override
    public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
        if (ctx.isTerminated()) {
            return Flux.empty();
        }

        Session session = ctx.getAttribute("session");
        if (session == null) {
            return Flux.empty();
        }

        return Flux.defer(() -> {
            if (!compactionEngine.needsCompaction(session, config)) {
                return Flux.empty();
            }

            log.info("会话 {} 触发压缩", session.getSessionId());

            // 1. 分派压缩前钩子
            hooks.forEach(h -> h.beforeCompaction(ctx));

            // 2. 执行压缩
            return compactionEngine.compact(session, config, ctx)
                    .flatMapMany(result -> {
                        // 3. 验证（质量把关）
                        return compactionEngine.validateCompaction(result, config)
                                .flatMapMany(valid -> {
                                    if (!valid) {
                                        log.warn("会话 {} 的压缩验证失败",
                                                session.getSessionId());
                                        // 可以重试或回退到截断
                                    }

                                    // 4. 注入压缩后章节
                                    injectPostCompactionSections(ctx, session);

                                    // 5. 分派压缩后钩子
                                    hooks.forEach(h -> h.afterCompaction(ctx, result));

                                    // 6. 如配置则通知用户
                                    if (config.isNotifyUser()) {
                                        return Flux.just(
                                                ServerSentEvent.<String>builder()
                                                        .event("compaction")
                                                        .data("{\"status\":\"complete\","
                                                                + "\"sessionId\":\"" + session.getSessionId() + "\","
                                                                + "\"messagesCompacted\":" + result.middleMessages() + "}")
                                                        .build()
                                        );
                                    }
                                    return Flux.empty();
                                });
                    })
                    .doOnError(e -> log.error("会话 {} 压缩失败",
                            session.getSessionId(), e))
                    .onErrorResume(e -> Flux.empty()); // 绝不阻塞管道
        });
    }

    private void injectPostCompactionSections(AgentContext ctx, Session session) {
        // 将配置的压缩后章节作为系统消息注入
        // ...
    }

    @Override
    public int getOrder() { return 500; }

    @Override
    public String getStageName() { return "compaction"; }
}
```

#### 钩子扩展

为 `AgentHook` 添加压缩生命周期的新方法：

```java
// 添加到 AgentHook 接口的方法：
public interface AgentHook {

    // ... 现有方法 ...

    /**
     * 在压缩开始之前调用。钩子可以保存关键状态、
     * 禁用工具修剪，或通知外部系统。
     */
    default void beforeCompaction(AgentContext ctx) {}

    /**
     * 在压缩成功完成后调用。
     * 钩子可以验证关键指令是否得到保留。
     *
     * @param ctx    代理上下文
     * @param result 包含指标的压缩结果
     */
    default void afterCompaction(AgentContext ctx, CompactionResult result) {}
}
```

#### 中途压缩触发

在 `DefaultReActEngine` 中，在工具执行轮次之间检查上下文压力：

```java
// 在 DefaultReActEngine.continueReActRounds() 或类似循环中：

// 每轮工具之后，检查中途上下文压力
if (compactionEngine != null) {
    Boolean needsCompaction = compactionEngine.midTurnPrecheck(ctx).block();
    if (Boolean.TRUE.equals(needsCompaction)) {
        log.warn("需要中途压缩；暂停 ReAct 循环");
        // 发出暂停事件，压缩，然后恢复
        // ...
    }
}
```

#### ContextPruningScheduler

一个后台调度器，定期运行 ContextPruner：

```java
package lyjew.com.lyclaw.compaction;

import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.storage.StoreLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

/**
 * 后台调度器，当上下文修剪模式为 CACHE_TTL 时，
 * 定期修剪过时的工具结果。
 */
public class ContextPruningScheduler {

    private static final Logger log = LoggerFactory.getLogger(ContextPruningScheduler.class);

    private final ContextPruner pruner;
    private final ContextPruningConfig config;
    private final StoreLayer storeLayer;

    public ContextPruningScheduler(ContextPruner pruner, ContextPruningConfig config,
                                   StoreLayer storeLayer) {
        this.pruner = pruner;
        this.config = config;
        this.storeLayer = storeLayer;
    }

    /**
     * 每 5 分钟运行一次修剪。仅在 mode != OFF 时活跃。
     */
    @Scheduled(fixedRate = 300_000)
    public void pruneActiveSessions() {
        if (config.getMode() == ContextPruningConfig.PruningMode.OFF) {
            return;
        }

        Instant now = Instant.now();
        var sessions = storeLayer.getActiveSessions(); // 需要 StoreLayer 扩展
        int totalModified = 0;

        for (Session session : sessions) {
            try {
                int modified = pruner.prune(session, now);
                totalModified += modified;
            } catch (Exception e) {
                log.warn("会话 {} 修剪失败：{}",
                        session.getSessionId(), e.getMessage());
            }
        }

        if (totalModified > 0) {
            log.info("ContextPruningScheduler：在 {} 个会话中修改了 {} 条消息",
                    totalModified, sessions.size());
        }
    }
}
```

### 3.1.6 YAML 配置

```yaml
lyclaw:
  # ── 压缩 ──────────────────────────────────────────
  compaction:
    # 完全启用/禁用压缩引擎
    enabled: true

    # 压缩模式：DEFAULT | SAFEGUARD
    mode: DEFAULT

    # Token 预留
    reserve-tokens: 8000
    keep-recent-tokens: 4000
    reserve-tokens-floor: 2000

    # 当历史记录超过此总 token 份额时触发
    max-history-share: 0.5

    # 压缩提示的自定义 LLM 指令
    custom-instructions: ""

    # 保持原样保留最近 N 个对话轮次
    recent-turns-preserve: 3

    # 标识符处理：STRICT | OFF | CUSTOM
    identifier-policy: STRICT

    # 压缩使用的模型覆盖（null = 使用会话模型）
    model: deepseek-v4-flash

    # 单次压缩 LLM 调用的超时时间（秒）
    timeout-seconds: 900

    # 压缩后截断尾部内容
    truncate-after-compaction: false

    # 触发压缩的最大活跃对话记录字节数
    max-active-transcript-bytes: 10485760  # 10MB

    # 压缩运行时通过 SSE 通知用户
    notify-user: false

    # ── 质量把关 ───────────────────────────────────
    quality-guard:
      enabled: true
      max-retries: 2

    # ── 中途预检查 ───────────────────────────────
    mid-turn-precheck:
      enabled: true

    # ── 压缩后索引同步 ──────────────────────
    # OFF | ASYNC | AWAIT
    post-index-sync: ASYNC

    # ── 记忆刷新（压缩前） ────────────────
    memory-flush:
      enabled: true
      # model: deepseek-v4-flash  # null = 使用压缩模型
      soft-threshold-tokens: 4000
      force-flush-transcript-bytes: 512000  # 500KB
      # prompt: ""
      # system-prompt: ""

    # 压缩后需要重新注入的章节
    post-compaction-sections:
      - "Session Startup"
      - "Red Lines"

    # ── 上下文修剪 ─────────────────────────────────
    pruning:
      # 修剪模式：OFF | CACHE_TTL
      mode: OFF

      # 工具结果的 TTL（ISO 8601 持续时间）
      ttl: PT30M

      # 保留最后 N 条助手消息不被修剪
      keep-last-assistants: 5

      # 软修剪比例（相对于上下文预算）
      soft-trim-ratio: 0.3

      # 硬清除比例
      hard-clear-ratio: 0.6

      # 工具结果可被修剪的最小字符数
      min-prunable-tool-chars: 1000

      # 工具允许/拒绝列表
      tool-allow: []
      tool-deny:
        - file_read
        - file_search

      # 软修剪参数
      soft-trim:
        max-chars: 8000
        head-chars: 2000
        tail-chars: 2000

      # 硬清除参数
      hard-clear:
        enabled: true
        placeholder: "[earlier output trimmed for space]"

    # ── 上下文限制 ──────────────────────────────────
    limits:
      memory-get-max-chars: 12000
      memory-get-default-lines: 120
      tool-result-max-chars: 16000
      post-compaction-max-chars: 1800
```

---

## 3.2 工作区引导

### 动机

目前 LyClaw 没有代理专用的引导文件。每个会话都以最小化的
系统提示开始。有了引导文件，每个代理都可以拥有丰富的、持久化的
身份：系统提示补充（AGENTS.md）、个性（SOUL.md）、一次性设置
（BOOTSTRAP.md）、身份描述（IDENTITY.md）、用户偏好（USER.md）
和心跳提示（HEARTBEAT.md）。

### 3.2.1 引导文件结构

```
{agentDir}/
  AGENTS.md      — 系统提示补充（始终注入）
  SOUL.md        — 代理个性、价值观、语气指南
  BOOTSTRAP.md   — 一次性设置指令（运行一次后跳过）
  IDENTITY.md    — 代理身份描述（名称、角色、背景）
  USER.md        — 用户上下文、偏好、自定义指令
  HEARTBEAT.md   — 心跳提示章节（周期性自检）
```

**文件语义：**

| 文件          | 注入方式       | 描述 |
|---------------|----------------|-------------|
| `AGENTS.md`   | 每轮都注入      | 核心系统提示增强。工具说明、安全规则、输出格式。不可跳过。 |
| `SOUL.md`     | 每轮都注入      | 个性与价值观。定义代理的"声音" — 语气、详细程度、风格偏好。 |
| `BOOTSTRAP.md`| 仅一次（在首次 `/new` 或 `/reset` 时） | 一次性初始化指令。仅在会话开始时执行。 |
| `IDENTITY.md` | 每轮都注入      | 代理是谁。名称、角色、背景故事。在 UI 中显示。 |
| `USER.md`     | 每轮都注入      | 用户特定上下文。偏好、自定义指令、关于用户的已知事实。 |
| `HEARTBEAT.md`| 每 N 分钟      | 周期性自检提示。鼓励代理反思目标进展。 |

### 3.2.2 BootstrapConfig

```java
package lyjew.com.lyclaw.bootstrap;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 工作区引导系统的配置。
 *
 * <p>控制加载哪些引导文件、如何注入它们，
 * 以及防止上下文窗口溢出的大小限制。</p>
 *
 * <p>映射自 application.yml 中的 {@code lyclaw.bootstrap}。</p>
 */
@Data
@Builder
public class BootstrapConfig {

    /** 完全跳过所有引导加载。默认值：false。 */
    @Builder.Default
    boolean skipBootstrap = false;

    /**
     * 需要跳过的可选引导文件列表。
     * 即使引导已启用，这些特定文件也会被忽略。
     * 例如：["SOUL.md", "HEARTBEAT.md"]。
     */
    List<String> skipOptionalBootstrapFiles;

    /**
     * 何时将引导内容注入到上下文中。
     * 默认值：ALWAYS。
     */
    @Builder.Default
    ContextInjectionPolicy contextInjection = ContextInjectionPolicy.ALWAYS;

    /** 每个引导文件的最大字符数。默认值：20000。 */
    @Builder.Default
    int bootstrapMaxChars = 20000;

    /** 所有引导文件的总最大字符数。默认值：150000。 */
    @Builder.Default
    int bootstrapTotalMaxChars = 150000;

    /**
     * 截断警告策略。
     * ONCE：内容被截断时每个会话警告一次。
     * ALWAYS：每次都警告。
     * NEVER：抑制警告。
     */
    @Builder.Default
    BootstrapTruncationWarning truncationWarning = BootstrapTruncationWarning.ONCE;

    /** 启动上下文配置。 */
    @Builder.Default
    StartupContextConfig startupContext = new StartupContextConfig();

    /**
     * 代理目录路径。如果为 null，则默认为 {@code ${user.dir}/agents/{agentId}}。
     */
    String agentDir;

    /**
     * 工作区目录路径。如果为 null，则默认为 {@code ${user.dir}}。
     */
    String workspaceDir;
}
```

```java
package lyjew.com.lyclaw.bootstrap;

public enum ContextInjectionPolicy {
    /** 每轮都注入引导文件。 */
    ALWAYS,
    /**
     * 在继续轮次中跳过引导注入。
     * 仅在 /new、/reset 或会话启动时注入。
     */
    CONTINUATION_SKIP,
    /** 从不注入引导文件（用于测试）。 */
    NEVER
}

public enum BootstrapTruncationWarning {
    /** 内容超过限制时每个会话警告一次。 */
    ONCE,
    /** 每轮都警告。 */
    ALWAYS,
    /** 从不警告。 */
    NEVER
}
```

```java
package lyjew.com.lyclaw.bootstrap;

import lombok.Builder;
import lombok.Data;

/**
 * 启动上下文：在会话启动时注入的文件列表、目录结构、
 * 最近的更改，为代理提供态势感知。
 */
@Data
@Builder
public class StartupContextConfig {

    /** 启用启动上下文注入。默认值：true。 */
    @Builder.Default
    boolean enabled = true;

    /**
     * 何时应用启动上下文。
     * FIRST_TURN：仅在会话的第一轮。
     * EVERY_RESET：在 /new 和 /reset 时。
     * EVERY_TURN：每轮都注入（冗长，不推荐）。
     */
    @Builder.Default
    StartupContextApplyOn applyOn = StartupContextApplyOn.FIRST_TURN;

    /** 启动上下文中包含的每日记忆天数。默认值：3。 */
    @Builder.Default
    int dailyMemoryDays = 3;

    /** 列出目录内容时的最大文件字节数。默认值：500KB。 */
    @Builder.Default
    long maxFileBytes = 500 * 1024;

    /** 单个目录中列出的最大文件数。默认值：200。 */
    @Builder.Default
    int maxFilesPerDir = 200;

    /** 启动上下文中目录列表的最大总字符数。默认值：8000。 */
    @Builder.Default
    int maxDirListChars = 8000;
}

public enum StartupContextApplyOn {
    FIRST_TURN, EVERY_RESET, EVERY_TURN
}
```

### 3.2.3 BootstrapLoader

```java
package lyjew.com.lyclaw.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从代理目录加载引导文件，并应用
 * 截断、上下文注入策略和大小限制。
 *
 * <p>引导文件从 {@code {agentDir}/} 加载，可选地
 * 从 {@code {workspaceDir}/} 加载（例如用于项目特定的覆盖）。</p>
 */
public class BootstrapLoader {

    private static final Logger log = LoggerFactory.getLogger(BootstrapLoader.class);

    /** 始终加载的文件（不能出现在 skipOptionalBootstrapFiles 中）。 */
    private static final Set<String> REQUIRED_FILES = Set.of("AGENTS.md");

    /** 所有已知的引导文件名。 */
    private static final List<String> ALL_FILES = List.of(
            "AGENTS.md", "SOUL.md", "BOOTSTRAP.md",
            "IDENTITY.md", "USER.md", "HEARTBEAT.md"
    );

    private final BootstrapConfig config;

    public BootstrapLoader(BootstrapConfig config) {
        this.config = config;
    }

    /**
     * 加载代理的所有引导文件。
     *
     * @param agentDir     代理目录的绝对路径（例如 /home/lyclaw/agents/coder）
     * @param workspaceDir 工作区目录的绝对路径（可选，可以为 null）
     * @param config       引导配置
     * @return 已加载的引导内容
     */
    public BootstrapContent loadBootstrap(String agentDir, String workspaceDir,
                                          BootstrapConfig config) {
        if (config.isSkipBootstrap()) {
            return BootstrapContent.empty();
        }

        Path agentPath = Path.of(agentDir);
        Path workspacePath = workspaceDir != null ? Path.of(workspaceDir) : null;

        Map<String, String> loaded = new LinkedHashMap<>();
        Set<String> skip = config.getSkipOptionalBootstrapFiles() != null
                ? Set.copyOf(config.getSkipOptionalBootstrapFiles()) : Set.of();

        int totalChars = 0;

        for (String fileName : ALL_FILES) {
            // 遵循跳过列表（但绝不跳过 AGENTS.md）
            if (skip.contains(fileName) && !REQUIRED_FILES.contains(fileName)) {
                continue;
            }

            // 首先尝试 agentDir
            Path filePath = agentPath.resolve(fileName);
            String content = readFile(filePath);

            // 回退到 workspaceDir（用于项目级别的覆盖，如 USER.md）
            if (content == null && workspacePath != null) {
                content = readFile(workspacePath.resolve(fileName));
            }

            if (content != null) {
                // 应用每个文件的截断
                content = truncate(content, config.getBootstrapMaxChars(),
                        config.getBootstrapTotalMaxChars() - totalChars);
                loaded.put(fileName, content);
                totalChars += content.length();
            }
        }

        // 应用所有文件的总限制
        if (totalChars > config.getBootstrapTotalMaxChars()) {
            loaded = enforceTotalLimit(loaded, config.getBootstrapTotalMaxChars());
        }

        boolean truncated = totalChars > config.getBootstrapTotalMaxChars();
        log.info("BootstrapLoader：为代理目录 {} 加载了 {} 个文件，共 {} 个字符{}",
                loaded.size(), totalChars, truncated ? "（已截断）" : "", agentDir);

        return new BootstrapContent(loaded, truncated);
    }

    /**
     * 构建将根据配置的 ContextInjectionPolicy 前置/追加到
     * 系统提示的注入字符串。
     *
     * @param content 已加载的引导内容
     * @param policy  注入策略
     * @return 格式化后的注入字符串
     */
    public String buildContextInjection(BootstrapContent content,
                                        ContextInjectionPolicy policy) {
        if (policy == ContextInjectionPolicy.NEVER) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        // AGENTS.md 始终放在最前面
        String agents = content.getFile("AGENTS.md");
        if (agents != null) {
            sb.append(agents).append("\n\n");
        }

        // IDENTITY.md
        String identity = content.getFile("IDENTITY.md");
        if (identity != null) {
            sb.append(identity).append("\n\n");
        }

        // SOUL.md
        String soul = content.getFile("SOUL.md");
        if (soul != null) {
            sb.append(soul).append("\n\n");
        }

        // USER.md
        String user = content.getFile("USER.md");
        if (user != null) {
            sb.append(user).append("\n\n");
        }

        // HEARTBEAT.md（如适用）
        String heartbeat = content.getFile("HEARTBEAT.md");
        if (heartbeat != null) {
            sb.append(heartbeat).append("\n\n");
        }

        // 截断警告
        if (content.isTruncated()
                && config.getTruncationWarning() != BootstrapTruncationWarning.NEVER) {
            sb.append("> 注意：部分引导内容被截断以适配上文限制。关键指令已保留。\n\n");
        }

        return sb.toString().trim();
    }

    /**
     * 截断内容以同时遵守每个文件和总限制。
     */
    public String truncate(String content, int maxChars, int remainingBudget) {
        int limit = Math.min(maxChars, remainingBudget);
        if (content == null) return null;
        if (content.length() <= limit) return content;
        return content.substring(0, limit - 30)
                + "\n... [已截断；超出限制]\n";
    }

    // ── 内部辅助方法 ───────────────────────────────────────────

    private String readFile(Path path) {
        try {
            if (Files.exists(path) && Files.isReadable(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("BootstrapLoader：读取 {} 失败：{}", path, e.getMessage());
        }
        return null;
    }

    private Map<String, String> enforceTotalLimit(Map<String, String> loaded, int totalLimit) {
        Map<String, String> result = new LinkedHashMap<>();
        int remaining = totalLimit;
        for (var entry : loaded.entrySet()) {
            if (remaining <= 0) break;
            String value = entry.getValue();
            if (value.length() > remaining) {
                value = value.substring(0, remaining - 30)
                        + "\n... [已截断；已达到引导总限制]\n";
            }
            result.put(entry.getKey(), value);
            remaining -= value.length();
        }
        return result;
    }
}
```

```java
package lyjew.com.lyclaw.bootstrap;

import java.util.Collections;
import java.util.Map;

/**
 * 已加载的引导文件内容的不可变容器。
 */
public class BootstrapContent {

    private final Map<String, String> files;
    private final boolean truncated;

    public BootstrapContent(Map<String, String> files, boolean truncated) {
        this.files = Collections.unmodifiableMap(files);
        this.truncated = truncated;
    }

    /** 获取特定引导文件的内容，如果未加载则返回 null。 */
    public String getFile(String fileName) {
        return files.get(fileName);
    }

    /** 所有已加载的文件（文件名 -> 内容）。 */
    public Map<String, String> getFiles() { return files; }

    /** 是否有任何文件被截断以适配上限制。 */
    public boolean isTruncated() { return truncated; }

    /** 已加载的文件数量。 */
    public int fileCount() { return files.size(); }

    /** 所有已加载文件的总字符数。 */
    public int totalChars() {
        return files.values().stream().mapToInt(String::length).sum();
    }

    public static BootstrapContent empty() {
        return new BootstrapContent(Map.of(), false);
    }
}
```

### 3.2.4 ContextInjectionPolicy

参见上述枚举。关键行为：

- **ALWAYS**：引导内容在每一轮都被注入到系统提示中。这确保代理始终拥有其身份和指令，代价是 token 消耗。
- **CONTINUATION_SKIP**：内容在新会话的第一轮（/new、/reset）注入，但在继续轮次中跳过。降低长时间会话的 token 成本，因为代理已经内化了其身份。
- **NEVER**：永不注入。当所有设置都直接通过 ChatRequest 中的系统提示完成时，用于测试。

### 3.2.5 管道集成

现有的 `ContextBuildStage` 被增强以加载和注入引导内容：

```java
// 在 ContextBuildStage（增强版）中：

@PipelineStage(name = "contextBuild", group = "PREPROCESSING")
public class ContextBuildStage implements ReactivePipelineStage {

    private final ContextBuilder contextBuilder;
    private final BootstrapLoader bootstrapLoader;   // 新增
    private final BootstrapConfig bootstrapConfig;    // 新增
    private final IdentityConfig identityConfig;      // 新增（参见 §3.4）

    // ... 构造函数 ...

    @Override
    public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
        if (ctx.isTerminated()) return Flux.empty();

        String agentId = ctx.getAttribute("agentId"); // 由 AgentRouter 设置
        String agentDir = bootstrapConfig.getAgentDir() != null
                ? bootstrapConfig.getAgentDir()
                : resolveAgentDir(agentId);

        // 1. 加载引导内容
        BootstrapContent bootstrap = bootstrapLoader.loadBootstrap(
                agentDir, bootstrapConfig.getWorkspaceDir(), bootstrapConfig);

        // 2. 确定注入策略
        ContextInjectionPolicy policy = bootstrapConfig.getContextInjection();
        boolean isContinuation = ctx.getAttribute("isContinuation") != null
                && (Boolean) ctx.getAttribute("isContinuation");
        if (policy == ContextInjectionPolicy.CONTINUATION_SKIP && isContinuation) {
            policy = ContextInjectionPolicy.NEVER;
        }

        // 3. 构建注入字符串
        String injection = bootstrapLoader.buildContextInjection(bootstrap, policy);

        // 4. 注入到系统提示中
        String enrichedSystemPrompt = buildEnrichedSystemPrompt(
                ctx.getSystemPrompt(), injection, identityConfig);

        ctx.setSystemPrompt(enrichedSystemPrompt);

        // ... 继续现有的上下文构建逻辑 ...

        return Flux.empty();
    }

    private String buildEnrichedSystemPrompt(String basePrompt, String bootstrapInjection,
                                             IdentityConfig identity) {
        StringBuilder sb = new StringBuilder();
        if (bootstrapInjection != null && !bootstrapInjection.isEmpty()) {
            sb.append(bootstrapInjection).append("\n\n");
        }
        if (basePrompt != null && !basePrompt.isEmpty()) {
            sb.append(basePrompt);
        }
        // 应用身份前缀（参见 §3.4）
        if (identity != null && identity.getNamePrefix() != null) {
            sb.insert(0, identity.getNamePrefix() + "\n");
        }
        return sb.toString();
    }

    private String resolveAgentDir(String agentId) {
        return System.getProperty("user.dir") + "/agents/" + agentId;
    }

    @Override
    public int getOrder() { return 10; }

    @Override
    public String getStageName() { return "contextBuild"; }
}
```

### 3.2.6 YAML 配置

```yaml
lyclaw:
  # ── 引导 ─────────────────────────────────────────
  bootstrap:
    # 跳过所有引导加载
    skip-bootstrap: false

    # 需要跳过的可选文件（AGENTS.md 永远不能跳过）
    skip-optional-bootstrap-files: []
    # 示例：["SOUL.md", "HEARTBEAT.md"]

    # 注入策略：ALWAYS | CONTINUATION_SKIP | NEVER
    context-injection: ALWAYS

    # 每个文件和总限制
    bootstrap-max-chars: 20000
    bootstrap-total-max-chars: 150000

    # 截断警告：ONCE | ALWAYS | NEVER
    truncation-warning: ONCE

    # 代理目录（null = ${user.dir}/agents/{agentId}）
    agent-dir: null
    # 工作区目录（null = ${user.dir}）
    workspace-dir: null

    # ── 启动上下文 ────────────────────────────────
    startup-context:
      enabled: true
      # FIRST_TURN | EVERY_RESET | EVERY_TURN
      apply-on: FIRST_TURN
      daily-memory-days: 3
      max-file-bytes: 512000   # 500KB
      max-files-per-dir: 200
      max-dir-list-chars: 8000
```

---

## 3.3 代理路由与绑定

### 动机

LyClaw 目前只有一个 `ChatController`，将所有流量路由到一个
`ChatAgent`。没有机制可以根据渠道（例如 Discord #general vs #engineering）、
账户或对等体身份将传入消息路由到不同的代理。

代理路由系统增加了：
1. **AgentRouteBinding** — 将路由（渠道、账户、对等体、公会、角色）映射到代理。
2. **AgentAcpBinding** — 将路由映射到具有 ACP 特定覆盖的代理。
3. **AgentRouter** — 解析哪个代理处理传入的请求。

### 3.3.1 AgentBindingMatch

```java
package lyjew.com.lyclaw.routing;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

/**
 * 将传入请求路由到代理的匹配条件。
 *
 * <p>所有字段均为可选。空/为 null 的字段表示"匹配任意内容"。
 * 多个非 null 字段之间是 AND 关系。至少需要一个字段
 * 为非 null 才能使绑定被考虑。</p>
 */
@Data
@Builder
public class AgentBindingMatch {

    /** 要匹配的渠道名称（例如 "general"、"engineering"）。 */
    String channel;

    /** 要匹配的账户 ID。 */
    String accountId;

    /** 要匹配的对等体 ID / 用户 ID。 */
    String peer;

    /** 要匹配的公会 / 服务器 ID。 */
    String guildId;

    /** 要匹配的团队 ID。 */
    String teamId;

    /** 必需的角色（用户必须拥有所有这些角色）。 */
    Set<String> roles;

    /**
     * 检查此匹配条件是否是给定条件的超集。
     * 用于查找最具体（最窄）的绑定。
     */
    public int specificity() {
        int score = 0;
        if (channel != null && !channel.isEmpty()) score++;
        if (accountId != null && !accountId.isEmpty()) score++;
        if (peer != null && !peer.isEmpty()) score++;
        if (guildId != null && !guildId.isEmpty()) score++;
        if (teamId != null && !teamId.isEmpty()) score++;
        if (roles != null && !roles.isEmpty()) score++;
        return score;
    }

    /**
     * 检查此匹配是否与给定的请求元数据匹配。
     */
    public boolean matches(RequestMetadata meta) {
        if (channel != null && !channel.equals(meta.getChannel())) return false;
        if (accountId != null && !accountId.equals(meta.getAccountId())) return false;
        if (peer != null && !peer.equals(meta.getPeer())) return false;
        if (guildId != null && !guildId.equals(meta.getGuildId())) return false;
        if (teamId != null && !teamId.equals(meta.getTeamId())) return false;
        if (roles != null && !roles.isEmpty()) {
            if (meta.getRoles() == null || !meta.getRoles().containsAll(roles)) {
                return false;
            }
        }
        return true;
    }
}
```

```java
package lyjew.com.lyclaw.routing;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

/**
 * 从传入请求中提取的元数据，用于代理路由。
 */
@Data
@Builder
public class RequestMetadata {

    String channel;       // 例如 "general"
    String accountId;     // 例如 Discord 账户 ID
    String peer;          // 用户标识符
    String guildId;       // 服务器/公会标识符
    String teamId;        // 团队标识符
    Set<String> roles;    // 用户角色

    /** 创建空的元数据（匹配默认/回退路由）。 */
    public static RequestMetadata empty() {
        return RequestMetadata.builder().build();
    }
}
```

### 3.3.2 AgentRouteBinding 与 AgentAcpBinding

```java
package lyjew.com.lyclaw.routing;

import lombok.Builder;
import lombok.Data;

/**
 * 代理绑定的基础接口。
 */
public sealed interface AgentBinding
        permits AgentRouteBinding, AgentAcpBinding {

    String getType();
    String getAgentId();
    AgentBindingMatch getMatch();
}

/**
 * 路由绑定：将一组匹配条件映射到一个代理 ID。
 *
 * <p>当请求的元数据与条件匹配时，它将被路由
 * 到指定的代理。</p>
 */
@Data
@Builder
public final class AgentRouteBinding implements AgentBinding {

    @Builder.Default
    String type = "route";

    /** 目标代理 ID。 */
    String agentId;

    /** 此绑定的人类可读注释。 */
    String comment;

    /** 匹配条件（渠道、账户、对等体、公会、团队、角色）。 */
    AgentBindingMatch match;

    /** 会话范围配置。 */
    @Builder.Default
    SessionScope session = new SessionScope();

    @Override
    public AgentBindingMatch getMatch() { return match; }

    /**
     * DM 会话范围：控制私信是与渠道绑定的会话共享
     * 还是拥有自己的会话。
     */
    @Data
    public static class SessionScope {
        /**
         * 私信的范围。
         * SHARED：DM 使用与渠道路由相同的会话。
         * ISOLATED：DM 拥有自己的会话。
         */
        @Builder.Default
        DmScope dmScope = DmScope.SHARED;
    }

    public enum DmScope { SHARED, ISOLATED }
}

/**
 * ACP（代理通信协议）绑定：类似 RouteBinding
 * 但具有额外的 ACP 特定覆盖。
 */
@Data
@Builder
public final class AgentAcpBinding implements AgentBinding {

    @Builder.Default
    String type = "acp";

    /** 目标代理 ID。 */
    String agentId;

    /** 人类可读注释。 */
    String comment;

    /** 匹配条件。 */
    AgentBindingMatch match;

    /** ACP 特定覆盖。 */
    @Builder.Default
    AcpOverrides acp = new AcpOverrides();

    @Override
    public AgentBindingMatch getMatch() { return match; }

    @Data
    public static class AcpOverrides {
        /** ACP 模式。 */
        String mode;

        /** 用于显示的 ACP 标签。 */
        String label;

        /** 此绑定的工作目录覆盖。 */
        String cwd;

        /** 后端覆盖。 */
        String backend;
    }
}
```

### 3.3.3 AgentRouter

```java
package lyjew.com.lyclaw.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 根据配置的绑定解析由哪个代理处理传入的请求。
 *
 * <h3>解析算法</h3>
 * <ol>
 *   <li>查找所有其 {@link AgentBindingMatch} 与请求元数据匹配的绑定。</li>
 *   <li>选择最具体的匹配（最高 specificity 分数）。</li>
 *   <li>如果没有匹配，返回默认代理 ID。</li>
 * </ol>
 *
 * <p>绑定通常从 YAML 配置加载
 * （参见 {@code lyclaw.routing.bindings}）或从注解加载。</p>
 */
public class AgentRouter {

    private static final Logger log = LoggerFactory.getLogger(AgentRouter.class);

    private final List<AgentBinding> bindings;
    private final String defaultAgentId;

    public AgentRouter(List<AgentBinding> bindings, String defaultAgentId) {
        // 按 specificity 降序排序（最具体的优先）
        this.bindings = new ArrayList<>(bindings);
        this.bindings.sort(Comparator
                .<AgentBinding>comparingInt(b -> b.getMatch() != null
                        ? b.getMatch().specificity() : 0)
                .reversed());
        this.defaultAgentId = defaultAgentId;
        log.info("AgentRouter 初始化：{} 个绑定，默认代理={}",
                bindings.size(), defaultAgentId);
    }

    /**
     * 为传入的请求解析代理 ID。
     *
     * @param metadata 请求元数据（渠道、账户、对等体等）
     * @return 处理此请求的代理 ID
     */
    public String resolveAgentId(RequestMetadata metadata) {
        if (metadata == null) {
            metadata = RequestMetadata.empty();
        }

        // 查找最具体的匹配绑定
        for (AgentBinding binding : bindings) {
            AgentBindingMatch match = binding.getMatch();
            if (match == null) continue; // 跳过没有匹配条件的绑定

            if (match.matches(metadata)) {
                log.debug("AgentRouter：匹配 {} -> {} (specificity={})",
                        metadata.getChannel() != null ? "#" + metadata.getChannel() : "default",
                        binding.getAgentId(),
                        match.specificity());
                return binding.getAgentId();
            }
        }

        // 没有匹配 — 使用默认值
        log.debug("AgentRouter：渠道={} 没有匹配，使用默认值={}",
                metadata.getChannel(), defaultAgentId);
        return defaultAgentId;
    }

    /**
     * 解析代理 ID 并返回完整的绑定信息（用于 ACP 覆盖等）。
     */
    public AgentBinding resolveBinding(RequestMetadata metadata) {
        if (metadata == null) {
            metadata = RequestMetadata.empty();
        }

        for (AgentBinding binding : bindings) {
            AgentBindingMatch match = binding.getMatch();
            if (match != null && match.matches(metadata)) {
                return binding;
            }
        }

        // 返回默认代理的合成路由绑定
        return AgentRouteBinding.builder()
                .agentId(defaultAgentId)
                .comment("默认路由（回退）")
                .match(AgentBindingMatch.builder().build())
                .build();
    }

    /**
     * 获取默认代理 ID。
     */
    public String getDefaultAgentId() {
        return defaultAgentId;
    }

    /**
     * 对简写表示法（如 "#general" 或 "@botname"）的模式匹配支持。
     * <p>这由按渠道/提及进行路由的平台（Discord、Slack）使用。</p>
     *
     * @param pattern 简写模式（例如 "#general"、"@coder-bot"）
     * @return 解析后的代理 ID，如果未找到则返回 null
     */
    public String resolveByPattern(String pattern) {
        if (pattern == null) return null;

        // "#channel" 表示法
        if (pattern.startsWith("#")) {
            String channel = pattern.substring(1);
            return resolveAgentId(RequestMetadata.builder().channel(channel).build());
        }

        // "@agent" 表示法 — 查找 IDENTITY.md 名称匹配的代理
        // 或者检查 pattern 是否直接匹配 agentId
        for (AgentBinding binding : bindings) {
            if (pattern.equals(binding.getAgentId())) {
                return binding.getAgentId();
            }
        }

        return null;
    }

    /** 已注册的绑定数量。 */
    public int bindingCount() {
        return bindings.size();
    }
}
```

### 3.3.4 ChatController 更新

```java
package lyjew.com.lyclaw.web.controller;

import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.routing.AgentRouter;
import lyjew.com.lyclaw.routing.RequestMetadata;
import lyjew.com.lyclaw.web.agent.ChatAgent;
import lyjew.com.lyclaw.web.agent.AgentRegistry; // 或类似
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.List;

/**
 * 增强的 ChatController，支持多代理路由。
 *
 * <p>从请求头中读取渠道/账户元数据，并使用
 * {@link AgentRouter} 在创建会话上下文之前
 * 解析目标代理。</p>
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatAgent defaultChatAgent;
    private final AgentRouter agentRouter;
    private final Map<String, ChatAgent> agentRegistry; // agentId -> 代理代理

    public ChatController(ChatAgent defaultChatAgent, AgentRouter agentRouter,
                          Map<String, ChatAgent> agentRegistry) {
        this.defaultChatAgent = defaultChatAgent;
        this.agentRouter = agentRouter;
        this.agentRegistry = agentRegistry;
    }

    /**
     * 从请求头中提取路由元数据。
     */
    private RequestMetadata extractMetadata(ChatRequest request) {
        return RequestMetadata.builder()
                .channel(request.getExtras() != null
                        ? (String) request.getExtras().get("channel") : null)
                .accountId(request.getExtras() != null
                        ? (String) request.getExtras().get("accountId") : null)
                .peer(request.getExtras() != null
                        ? (String) request.getExtras().get("peer") : null)
                .guildId(request.getExtras() != null
                        ? (String) request.getExtras().get("guildId") : null)
                .teamId(request.getExtras() != null
                        ? (String) request.getExtras().get("teamId") : null)
                .roles(extractRoles(request))
                .build();
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractRoles(ChatRequest request) {
        if (request.getExtras() != null
                && request.getExtras().get("roles") instanceof List<?> list) {
            return Set.copyOf((List<String>) list);
        }
        return Set.of();
    }

    /**
     * 根据路由元数据解析此请求的 ChatAgent。
     */
    private ChatAgent resolveAgent(ChatRequest request) {
        RequestMetadata metadata = extractMetadata(request);
        String agentId = agentRouter.resolveAgentId(metadata);
        ChatAgent agent = agentRegistry.get(agentId);
        if (agent != null) {
            return agent;
        }
        return defaultChatAgent;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
        String userMessage = request.getLastUserMessage();
        ChatAgent agent = resolveAgent(request);
        return agent.chatStream(userMessage);
    }

    @PostMapping("/chat")
    public Mono<Map<String, Object>> chat(@RequestBody ChatRequest request) {
        String userMessage = request.getLastUserMessage();
        String sessionId = request.getSessionId() != null ? request.getSessionId() : "";
        ChatAgent agent = resolveAgent(request);
        return Mono.fromCallable(() -> agent.chat(userMessage))
                .subscribeOn(Schedulers.boundedElastic())
                .map(reply -> Map.of("content", reply, "sessionId", sessionId));
    }

    // ... 会话端点保持不变 ...
}
```

### 3.3.5 YAML 配置

```yaml
lyclaw:
  # ── 路由 ───────────────────────────────────────────
  routing:
    # 没有绑定匹配时的默认代理
    default-agent: default

    # ── 绑定 ───────────────────────────────────────
    bindings:
      # 路由绑定：Discord #general 频道 -> "helper" 代理
      - type: route
        agent-id: helper
        comment: "通用聊天助手"
        match:
          channel: general
          guild-id: "111222333444"
        session:
          dm-scope: SHARED

      # 路由绑定：Discord #engineering 频道 -> "coder" 代理
      - type: route
        agent-id: coder
        comment: "工程代码助手"
        match:
          channel: engineering
          guild-id: "111222333444"

      # 路由绑定：特定用户获得 "admin" 代理
      - type: route
        agent-id: admin
        comment: "高级用户的管理助手"
        match:
          peer: "user-admin-001"
          roles: ["admin"]

      # ACP 绑定：带有工作目录覆盖
      - type: acp
        agent-id: coder
        comment: "coder 代理的 ACP 绑定"
        match:
          channel: dev-acp
        acp:
          mode: interactive
          label: "Dev ACP"
          cwd: /home/lyclaw/projects
          backend: openai-protocol

      # 全捕获回退（匹配任意内容，最低 specificity）
      - type: route
        agent-id: default
        comment: "默认回退代理"
        match: {}
```

---

## 3.4 身份与头像

### 动机

目前 LyClaw 代理没有可视身份。它们只是无名的文本
响应器。IdentityConfig 添加了显示名称、头像、名称前缀（例如
"[CoderBot] "）、响应前缀、消息前缀和确认回应。

### 3.4.1 IdentityConfig

```java
package lyjew.com.lyclaw.identity;

import lombok.Builder;
import lombok.Data;
import lyjew.com.lyclaw.bootstrap.BootstrapConfig;
import lyjew.com.lyclaw.bootstrap.BootstrapLoader;

/**
 * 代理身份和展示配置。
 *
 * <p>控制代理在 UI 中的显示方式（名称、头像）以及其
 * 消息在输出流中如何添加前缀/注释。</p>
 *
 * <p>映射自 application.yml 中的 {@code lyclaw.identity}，或
 * 从代理的 IDENTITY.md 引导文件加载。</p>
 */
@Data
@Builder
public class IdentityConfig {

    /** UI 中显示的显示名称。 */
    String displayName;

    /** 头像图片 URL（远程或数据 URI）。 */
    String avatarUrl;

    /** 头像图片文件路径（本地）。 */
    String avatarFilePath;

    /**
     * 在聊天中前置到代理回复的名称前缀。
     * 例如："[CoderBot] " -> "[CoderBot] 这是你的代码..."
     */
    String namePrefix;

    /**
     * 前置到每轮最终回复的响应前缀。
     * 与 namePrefix（在所有输出之前）不同，此后缀
     * 仅前置到最终文本回复，而非工具调用通知。
     */
    String responsePrefix;

    /**
     * 前置到此代理所有消息的消息前缀，
     * 包括工具调用 SSE 事件和状态更新。
     */
    String messagePrefix;

    /**
     * 用于确认消息的表情回应。
     * 例如："eyes" 或 "white_check_mark"。
     */
    String ackReaction;

    /**
     * 从身份配置构建显示标签。
     */
    public String getDisplayLabel() {
        if (displayName != null && !displayName.isEmpty()) {
            return displayName;
        }
        return "Agent";
    }

    /**
     * 将名称前缀应用到消息字符串。
     */
    public String applyNamePrefix(String message) {
        if (namePrefix == null || namePrefix.isEmpty()) {
            return message;
        }
        if (message == null) return namePrefix;
        return namePrefix + message;
    }

    /**
     * 将响应前缀应用到最终响应。
     */
    public String applyResponsePrefix(String response) {
        if (responsePrefix == null || responsePrefix.isEmpty()) {
            return response;
        }
        if (response == null) return responsePrefix;
        return responsePrefix + response;
    }

    /**
     * 将消息前缀应用到任何消息。
     */
    public String applyMessagePrefix(String message) {
        if (messagePrefix == null || messagePrefix.isEmpty()) {
            return message;
        }
        if (message == null) return messagePrefix;
        return messagePrefix + message;
    }
}
```

### 3.4.2 AvatarResolution

```java
package lyjew.com.lyclaw.identity;

/**
 * 代理头像的来源方式。
 */
public enum AvatarKind {
    /** 没有可用的头像。 */
    NONE,
    /** 头像从本地文件加载。 */
    LOCAL,
    /** 头像从远程 URL 加载。 */
    REMOTE,
    /** 头像以数据 URI 形式嵌入。 */
    DATA
}

/**
 * 已解析的头像信息，包含关于解析过程的元数据。
 */
public class AgentAvatarResolution {

    private final AvatarKind kind;
    private final String reason;      // 对于 NONE：为什么没有头像
    private final String filePath;    // 对于 LOCAL：绝对路径
    private final String url;         // 对于 REMOTE、DATA：URL/数据 URI
    private final String source;      // 头像在何处找到（例如 "IDENTITY.md"、"config"）

    public AgentAvatarResolution(AvatarKind kind, String reason, String filePath,
                                 String url, String source) {
        this.kind = kind;
        this.reason = reason;
        this.filePath = filePath;
        this.url = url;
        this.source = source;
    }

    public AvatarKind getKind() { return kind; }
    public String getReason() { return reason; }
    public String getFilePath() { return filePath; }
    public String getUrl() { return url; }
    public String getSource() { return source; }

    /**
     * 从 IdentityConfig 解析头像，按以下顺序尝试每种来源：
     * avatarFilePath -> avatarUrl -> NONE。
     */
    public static AgentAvatarResolution resolve(IdentityConfig config) {
        // 1. 尝试本地文件
        if (config.getAvatarFilePath() != null && !config.getAvatarFilePath().isEmpty()) {
            java.nio.file.Path path = java.nio.file.Path.of(config.getAvatarFilePath());
            if (java.nio.file.Files.exists(path)) {
                return new AgentAvatarResolution(
                        AvatarKind.LOCAL, null,
                        config.getAvatarFilePath(), null,
                        "config.avatarFilePath");
            }
            return new AgentAvatarResolution(
                    AvatarKind.NONE,
                    "文件未找到：" + config.getAvatarFilePath(),
                    null, null, "config.avatarFilePath");
        }

        // 2. 尝试 URL
        if (config.getAvatarUrl() != null && !config.getAvatarUrl().isEmpty()) {
            if (config.getAvatarUrl().startsWith("data:")) {
                return new AgentAvatarResolution(
                        AvatarKind.DATA, null,
                        null, config.getAvatarUrl(),
                        "config.avatarUrl");
            }
            return new AgentAvatarResolution(
                    AvatarKind.REMOTE, null,
                    null, config.getAvatarUrl(),
                    "config.avatarUrl");
        }

        // 3. 没有找到任何内容
        return new AgentAvatarResolution(
                AvatarKind.NONE, "未配置头像", null, null, "none");
    }

    /** 便捷方法：是否有可用头像？ */
    public boolean isAvailable() {
        return kind != AvatarKind.NONE;
    }

    @Override
    public String toString() {
        return "AgentAvatarResolution{kind=" + kind
                + (reason != null ? ", reason='" + reason + "'" : "")
                + (filePath != null ? ", filePath='" + filePath + "'" : "")
                + (url != null ? ", url='" + url + "'" : "")
                + ", source='" + source + "'}";
    }
}
```

#### IdentityService

```java
package lyjew.com.lyclaw.identity;

import lyjew.com.lyclaw.bootstrap.BootstrapContent;
import lyjew.com.lyclaw.bootstrap.BootstrapLoader;

/**
 * 解析代理身份的中心服务。
 *
 * <p>身份从三个来源加载（按优先级排序）：
 * <ol>
 *   <li>显式 YAML 配置（{@code lyclaw.identity}）</li>
 *   <li>引导文件 IDENTITY.md</li>
 *   <li>从 agentId 派生的默认值</li>
 * </ol>
 */
public class IdentityService {

    private final IdentityConfig configuredIdentity; // 来自 YAML
    private final BootstrapLoader bootstrapLoader;

    public IdentityService(IdentityConfig configuredIdentity, BootstrapLoader bootstrapLoader) {
        this.configuredIdentity = configuredIdentity;
        this.bootstrapLoader = bootstrapLoader;
    }

    /**
     * 解析代理的有效身份。
     *
     * @param agentId   代理的 ID
     * @param agentDir  代理的目录（用于加载 IDENTITY.md）
     * @return 有效的身份配置
     */
    public IdentityConfig resolveIdentity(String agentId, String agentDir) {
        // 以配置的身份为基础
        IdentityConfig.IdentityConfigBuilder builder = IdentityConfig.builder();

        if (configuredIdentity != null) {
            builder.displayName(configuredIdentity.getDisplayName())
                    .avatarUrl(configuredIdentity.getAvatarUrl())
                    .avatarFilePath(configuredIdentity.getAvatarFilePath())
                    .namePrefix(configuredIdentity.getNamePrefix())
                    .responsePrefix(configuredIdentity.getResponsePrefix())
                    .messagePrefix(configuredIdentity.getMessagePrefix())
                    .ackReaction(configuredIdentity.getAckReaction());
        }

        // 如果 IDENTITY.md 可用，则用其覆盖
        // （IDENTITY.md 内容遵循简单的 key: value 格式）
        // ... 解析 IDENTITY.md 并应用覆盖 ...

        // 回退显示名称
        if (builder.build().getDisplayName() == null) {
            builder.displayName(agentId);
        }

        return builder.build();
    }

    /**
     * 将身份前缀应用到代理响应。
     */
    public String applyIdentity(String response, IdentityConfig identity) {
        String result = response;
        result = identity.applyResponsePrefix(result);
        result = identity.applyNamePrefix(result);
        return result;
    }
}
```

### 3.4.3 集成与 YAML

#### 管道集成

在 `ContextBuildStage` 中，身份被解析并存储在 `AgentContext` 中供下游阶段使用：

```java
// 在 ContextBuildStage.execute() 中：
IdentityConfig identity = identityService.resolveIdentity(agentId, agentDir);
ctx.setAttribute("identity", identity);
ctx.setAttribute("avatarResolution", AgentAvatarResolution.resolve(identity));
```

在 `RespondStage`（或最终响应发出的任何位置）中，应用身份前缀：

```java
// 在发出最终响应之前：
IdentityConfig identity = ctx.getAttribute("identity");
if (identity != null) {
    finalResponse = identityService.applyIdentity(finalResponse, identity);
}
```

#### YAML 配置

```yaml
lyclaw:
  # ── 身份 ──────────────────────────────────────────
  identity:
    # UI 中显示的显示名称
    display-name: "LyClaw Assistant"

    # 头像 URL（远程）或文件路径（本地）
    avatar-url: null
    avatar-file-path: null

    # 应用到代理输出的前缀
    name-prefix: null         # 例如 "[CoderBot] "
    response-prefix: null     # 例如 "这是我找到的内容：\n"
    message-prefix: null      # 例如 "🤖 "

    # 确认回应表情（用于 Discord/Slack 适配器）
    ack-reaction: "eyes"
```

---

## 完整 YAML 配置参考

```yaml
lyclaw:
  # ================================================================
  #  第三阶段 — 上下文引擎、引导、路由、身份
  # ================================================================

  # ── 3.1 压缩 ────────────────────────────────────
  compaction:
    enabled: true
    mode: DEFAULT
    reserve-tokens: 8000
    keep-recent-tokens: 4000
    reserve-tokens-floor: 2000
    max-history-share: 0.5
    custom-instructions: ""
    recent-turns-preserve: 3
    identifier-policy: STRICT
    identifier-instructions: ""
    model: null
    timeout-seconds: 900
    truncate-after-compaction: false
    max-active-transcript-bytes: 10485760
    notify-user: false

    quality-guard:
      enabled: true
      max-retries: 2

    mid-turn-precheck:
      enabled: true

    post-index-sync: ASYNC

    memory-flush:
      enabled: true
      model: null
      soft-threshold-tokens: 4000
      force-flush-transcript-bytes: 512000
      prompt: null
      system-prompt: null

    post-compaction-sections:
      - "Session Startup"
      - "Red Lines"

    pruning:
      mode: OFF
      ttl: PT30M
      keep-last-assistants: 5
      soft-trim-ratio: 0.3
      hard-clear-ratio: 0.6
      min-prunable-tool-chars: 1000
      tool-allow: []
      tool-deny: [file_read, file_search]
      soft-trim:
        max-chars: 8000
        head-chars: 2000
        tail-chars: 2000
      hard-clear:
        enabled: true
        placeholder: "[earlier output trimmed for space]"

    limits:
      memory-get-max-chars: 12000
      memory-get-default-lines: 120
      tool-result-max-chars: 16000
      post-compaction-max-chars: 1800

  # ── 3.2 引导 ─────────────────────────────────────
  bootstrap:
    skip-bootstrap: false
    skip-optional-bootstrap-files: []
    context-injection: ALWAYS
    bootstrap-max-chars: 20000
    bootstrap-total-max-chars: 150000
    truncation-warning: ONCE
    agent-dir: null
    workspace-dir: null

    startup-context:
      enabled: true
      apply-on: FIRST_TURN
      daily-memory-days: 3
      max-file-bytes: 512000
      max-files-per-dir: 200
      max-dir-list-chars: 8000

  # ── 3.3 路由 ───────────────────────────────────────
  routing:
    default-agent: default
    bindings: []
    # 示例绑定：
    # - type: route
    #   agent-id: helper
    #   comment: "通用聊天助手"
    #   match:
    #     channel: general
    #     guild-id: "111222333444"
    #   session:
    #     dm-scope: SHARED

  # ── 3.4 身份 ──────────────────────────────────────
  identity:
    display-name: "LyClaw Assistant"
    avatar-url: null
    avatar-file-path: null
    name-prefix: null
    response-prefix: null
    message-prefix: null
    ack-reaction: "eyes"
```

---

## 集成检查清单

### 3.1 上下文引擎与压缩

- [ ] 创建 `lyclaw-framework/src/main/java/lyjew/com/lyclaw/compaction/` 包
- [ ] 实现 `CompactionConfig` 及所有字段和 builder
- [ ] 实现枚举：`CompactionMode`、`IdentifierPolicy`、`PostIndexSync`
- [ ] 实现子配置：`QualityGuard`、`MidTurnPrecheck`、`MemoryFlush`
- [ ] 实现 `CompactionEngine` 带 `needsCompaction()`、`compact()`、`validateCompaction()`、`midTurnPrecheck()`
- [ ] 实现 `CompactionResult` 记录
- [ ] 实现 `ContextPruningConfig` 带 `SoftTrim`、`HardClear`
- [ ] 实现 `ContextPruner` 带 `prune()` 方法
- [ ] 实现 `AgentContextLimits` 带截断辅助方法
- [ ] 创建 `CompactionStage`（`@PipelineStage`，在 ReflectionStage 之后，MetricsStage 之前）
- [ ] 向 `AgentHook` 接口添加 `beforeCompaction`/`afterCompaction` 方法
- [ ] 实现 `ContextPruningScheduler` 带 `@Scheduled`
- [ ] 向 `LyClawConfigurationProperties` 添加 `CompactionProperties` 用于 YAML 绑定
- [ ] 在 `CompactionAutoConfiguration` 中连线（或扩展现有的 autoconfigure）
- [ ] 扩展 `StoreLayer` 添加 `getActiveSessions()` 供修剪调度器使用

### 3.2 工作区引导

- [ ] 创建 `lyclaw-framework/src/main/java/lyjew/com/lyclaw/bootstrap/` 包
- [ ] 实现 `BootstrapConfig` 及所有字段和 builder
- [ ] 实现枚举：`ContextInjectionPolicy`、`BootstrapTruncationWarning`、`StartupContextApplyOn`
- [ ] 实现 `StartupContextConfig`
- [ ] 实现 `BootstrapLoader` 带 `loadBootstrap()` 和 `buildContextInjection()`
- [ ] 实现 `BootstrapContent` 不可变容器
- [ ] 增强 `ContextBuildStage` 以调用 `BootstrapLoader` 并注入内容
- [ ] 向 `LyClawConfigurationProperties` 添加 `BootstrapProperties` 用于 YAML 绑定
- [ ] 在 `BootstrapAutoConfiguration` 中连线
- [ ] 在 `/agents/default/` 中创建示例引导文件

### 3.3 代理路由与绑定

- [ ] 创建 `lyclaw-framework/src/main/java/lyjew/com/lyclaw/routing/` 包
- [ ] 实现 `RequestMetadata` 带 channel、accountId、peer、guildId、teamId、roles
- [ ] 实现 `AgentBindingMatch` 带 `matches()` 和 `specificity()`
- [ ] 实现密封的 `AgentBinding` 接口，以及 `AgentRouteBinding` 和 `AgentAcpBinding`
- [ ] 实现 `AgentRouter` 带 `resolveAgentId()`、`resolveBinding()`、`resolveByPattern()`
- [ ] 增强 `ChatController` 以从 `ChatRequest.extras` 提取元数据并路由到解析后的代理
- [ ] 向 `LyClawConfigurationProperties` 添加 `RoutingProperties` 用于 YAML 绑定
- [ ] 在 `RoutingAutoConfiguration` 中连线

### 3.4 身份与头像

- [ ] 创建 `lyclaw-framework/src/main/java/lyjew/com/lyclaw/identity/` 包
- [ ] 实现 `IdentityConfig` 带 displayName、avatar、prefixes、ackReaction
- [ ] 实现 `AvatarKind` 枚举和 `AgentAvatarResolution` 带 `resolve()`
- [ ] 实现 `IdentityService` 带 `resolveIdentity()` 和 `applyIdentity()`
- [ ] 增强 `ContextBuildStage` 以调用 `IdentityService` 并在 `AgentContext` 中存储身份
- [ ] 在 `RespondStage` 中发出最终响应之前应用身份前缀
- [ ] 向 `LyClawConfigurationProperties` 添加 `IdentityProperties` 用于 YAML 绑定
- [ ] 在 `IdentityAutoConfiguration` 中连线

### 跨领域

- [ ] 使用完整的配置参考更新 `application.yml`
- [ ] 为 `CompactionEngine`、`BootstrapLoader`、`AgentRouter`、`IdentityService` 添加单元测试
- [ ] 为带有压缩和引导的完整管道添加集成测试
- [ ] 记录新的 SSE 事件：`compaction`、身份元数据
- [ ] 更新 Actuator 端点（`LyClawConfigEndpoint`、`LyClawPipelineEndpoint`）以暴露新的配置章节

---

# 第四阶段：流式与网关增强 + 沙箱 + 心跳 + 运行重试

## 概述

第四阶段针对支撑 LyClaw 生产就绪性的四个高影响力子系统：
1. **块流式与人类延迟** — 用边界感知的块流式、合并、人类输入模拟和输入中指示器替换 `DefaultReActEngine` 中简单的 `splitIntoEvents()`。
2. **容器沙箱** — 将 `ToolSandbox` / `SandboxLevel=PROCESS` 升级为基于 Docker/Podman 的隔离，支持文件系统桥接、资源限制和 `SandboxExecutionService`。
3. **智能体心跳** — 引入类 cron 调度器，可定期 ping 智能体，产生 `heartbeat_*` SSE 事件，并传递隔离会话的轮次结果。
4. **运行重试增强** — 用 `RunRetryManager`、按回退配置文件预算和重试策略选择替换 `ReflexionLoop` 中硬编码的 `maxRetries`。

所有新代码位于现有包下：
- 流式配置 → `lyjew.com.lyclaw.config`
- 块流式逻辑 → `lyjew.com.lyclaw.react.stream`
- 沙箱 → `lyjew.com.lyclaw.security.sandbox`
- 心跳 → `lyjew.com.lyclaw.react.heartbeat`
- 运行重试 → `lyjew.com.lyclaw.react.retry`

---

## 目录

1. [4.1 块流式增强](#41-块流式增强)
2. [4.2 沙箱增强](#42-沙箱增强)
3. [4.3 心跳系统](#43-心跳系统)
4. [4.4 运行重试增强](#44-运行重试增强)
5. [集成架构图](#集成架构图)
6. [SSE 事件模式参考](#sse-事件模式参考)

---

## 4.1 块流式增强

### 4.1.1 动机

当前 `DefaultReActEngine.splitIntoEvents(String text)` 在中文标点边界处（`\n`、`。`、`！`、`？`、`；`）进行分割，并将每个片段作为单个 SSE `message` 事件发出。这对于短回复可行，但存在若干问题：

- **无块感知**：不理解 LLM 自然文本边界（段落、代码围栏、列表）。
- **无合并**：单字符块创建单独的 SSE 帧 — 浪费资源。
- **无人类延迟**：所有事件同时到达，没有"AI 正在打字"的感觉。
- **无输入中指示器**：前端无法在响应生成期间显示"思考中"或"输入中"状态。

第四阶段在 `RespondStage` 和 `DefaultReActEngine` 内部引入了分层流式管道：

```
LLM token stream
  → BlockStreamingChunk (软边界检测)
    → BlockStreamingCoalesce (合并小块)
      → HumanDelay (块间错开)
        → TypingIndicator (周期性"输入中"事件)
          → SSE emit
```

### 4.1.2 配置

#### BlockStreamingConfig

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 基于块的流式配置。
 * <p>控制 LLM token 流如何分块并传递给 SSE 客户端。
 * 用边界感知、合并、人类延迟的流式替换简单的 splitIntoEvents()。
 */
@ConfigurationProperties(prefix = "lyclaw.streaming.block")
public class BlockStreamingConfig {

    /** 启用基于块的流式。设为 false 时，回退到旧的 splitIntoEvents()。 */
    private boolean enabled = false;

    /**
     * 何时断开流式块。
     * <ul>
     *   <li>TEXT_END — 每个完整文本段落后断开（段落、列表项等）</li>
     *   <li>MESSAGE_END — 仅在整条助手消息结束时断开</li>
     * </ul>
     */
    private BlockStreamingBreak breakMode = BlockStreamingBreak.TEXT_END;

    /** 软块分块配置。 */
    private BlockStreamingChunk chunk = new BlockStreamingChunk();

    /** 块回复合并配置。 */
    private BlockStreamingCoalesce coalesce = new BlockStreamingCoalesce();

    /** 每个块帧的最大字符数。 */
    private int maxChunkChars = 2000;

    /** 如果为 true，抑制重复的相同文本块。 */
    private boolean repeatSuppression = true;

    /**
     * 流式投递模式。
     * <ul>
     *   <li>LIVE — 块形成后立即发出（默认）</li>
     *   <li>FINAL_ONLY — 缓冲所有内容，结束时发出单个事件</li>
     * </ul>
     */
    private StreamingDeliveryMode deliveryMode = StreamingDeliveryMode.LIVE;

    /**
     * 多块消息的隐藏边界分隔符。
     * 作为块之间的不可见分隔符插入，供解析响应的客户端使用。
     */
    private HiddenBoundarySeparator hiddenBoundary = HiddenBoundarySeparator.NEWLINE;

    // 此处省略 getter 和 setter

    public enum BlockStreamingBreak { TEXT_END, MESSAGE_END }
    public enum StreamingDeliveryMode { LIVE, FINAL_ONLY }
    public enum HiddenBoundarySeparator { NEWLINE, NULL_CHAR, NONE }
}
```

#### BlockStreamingChunk

```java
package lyjew.com.lyclaw.config;

/**
 * 软块分块配置。
 * <p>分块意味着决定在何处将 token 流切割为离散块。
 * 这是"软"的，因为块可以在之后被合并。
 */
public class BlockStreamingChunk {

    /**
     * 每个块的软最大字符数（中日韩文本按字节计）。
     * 当块超过此大小时将刷新，
     * 但实际边界仍受 preferNewlines 影响。
     */
    private int maxChars = 500;

    /**
     * 刷新当前块的最大空闲时间（毫秒）。
     * 如果在此持续时间内没有新 token 到达，累积的块将被发出。
     */
    private int maxIdleMs = 1000;

    /**
     * 如果为 true，优先在换行边界处分割（\n、\r\n、\n\n）。
     * 当遇到换行且当前块至少达到 maxChars 的 50% 时，
     * 块将在该边界处刷新，不考虑确切大小。
     */
    private boolean preferNewlines = true;

    /**
     * 当 preferNewlines 为 true 时，触发换行刷新的最小填充百分比（0.0-1.0）。
     */
    private double newlineFlushThreshold = 0.5;

    // 省略 getter 和 setter
}
```

#### BlockStreamingCoalesce

```java
package lyjew.com.lyclaw.config;

/**
 * 块回复合并配置。
 * <p>合并将多个小块合并为一个较大的块再进行 SSE 投递。
 * 这减少了 SSE 帧的数量，提高了网络效率。
 */
public class BlockStreamingCoalesce {

    /** 启用块合并。 */
    private boolean enabled = true;

    /** 合并块强制刷新前的最大字符数。 */
    private int maxChars = 8000;

    /**
     * 刷新合并缓冲区的最大空闲时间（毫秒）。
     * 如果在此持续时间内没有新块到达，累积的内容将被发出。
     */
    private int maxIdleMs = 3000;

    // 省略 getter 和 setter
}
```

#### HumanDelayConfig

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 类人输入延迟配置。
 * <p>在流式块之间引入可变延迟，模拟自然输入速度，改善聊天界面用户体验。
 */
@ConfigurationProperties(prefix = "lyclaw.streaming.human-delay")
public class HumanDelayConfig {

    /** 启用类人延迟模拟。 */
    private boolean enabled = false;

    /** 块之间的最小延迟（毫秒）。 */
    private int minDelayMs = 200;

    /** 块之间的最大延迟（毫秒）。 */
    private int maxDelayMs = 1500;

    /**
     * 模拟输入速度，以每秒字符数计。
     * 用于计算动态延迟：delayMs = blockChars / charsPerSecond * 1000。
     * 典型人类输入速度为 40-80 CPS；50 是一个自然的默认值。
     */
    private int charsPerSecond = 50;

    /**
     * 如果为 true，自适应速度会根据长回复调整输入速率。
     * 随着响应长度增长，智能体"加速"以避免过长的等待时间。
     */
    private boolean adaptiveSpeed = true;

    /**
     * 触发加速调整的字符阈值。
     * 当总累积响应超过此值时，charsPerSecond 会
     * 逐渐增加（最多 3 倍）用于后续块。
     */
    private int longReplyThreshold = 2000;

    // 省略 getter 和 setter
}
```

### 4.1.3 BlockStreamingController

这是替换 `splitIntoEvents()` 的核心组件。

```java
package lyjew.com.lyclaw.react.stream;

import lyjew.com.lyclaw.config.BlockStreamingConfig;
import lyjew.com.lyclaw.config.HumanDelayConfig;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 基于块的流式控制器，替代 DefaultReActEngine.splitIntoEvents()。
 *
 * <p>将原始文本响应转换为边界感知、合并、人类延迟的
 * SSE 事件 Flux。与 RespondStage 的流式管道集成。</p>
 *
 * <h3>处理管道：</h3>
 * <ol>
 *   <li>将原始文本按自然边界解析为块</li>
 *   <li>合并相邻小块</li>
 *   <li>在块之间应用人类延迟</li>
 *   <li>应用重复抑制</li>
 *   <li>发出 SSE message 事件</li>
 * </ol>
 */
public class BlockStreamingController {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final BlockStreamingConfig config;
    private final HumanDelayConfig humanDelayConfig;
    private final TypingIndicatorController typingIndicator;

    // 跟踪先前发出的文本用于重复抑制
    private String lastEmittedBlock = "";

    // 跟踪总发出字符数用于自适应速度
    private int totalEmittedChars = 0;

    public BlockStreamingController(BlockStreamingConfig config,
                                     HumanDelayConfig humanDelayConfig,
                                     TypingIndicatorController typingIndicator) {
        this.config = config;
        this.humanDelayConfig = humanDelayConfig;
        this.typingIndicator = typingIndicator;
    }

    /**
     * 将完整文本响应转换为块流式的 SSE 事件 Flux。
     * 当检测到工具调用且 ReAct 循环产生最终文本响应时使用。
     *
     * @param text 完整的助手响应文本
     * @return SSE message 事件的 Flux
     */
    public Flux<ServerSentEvent<String>> streamResponse(String text) {
        if (!config.isEnabled() || text == null || text.isEmpty()) {
            return Flux.empty();
        }

        return Flux.defer(() -> {
            List<String> blocks = segmentIntoBlocks(text);
            if (blocks.isEmpty()) {
                return Flux.empty();
            }

            blocks = coalesceBlocks(blocks);
            blocks = applyRepeatSuppression(blocks);

            if (config.getDeliveryMode() == BlockStreamingConfig.StreamingDeliveryMode.FINAL_ONLY) {
                String joined = joinWithHiddenBoundary(blocks);
                return Flux.just(sseMessage(joined));
            }

            // LIVE 模式：以人类延迟发出块
            return Flux.fromIterable(blocks)
                    .concatMap(block ->
                            Mono.just(sseMessage(block))
                                    .delayElement(calculateDelay(block))
                    );
        });
    }

    /**
     * 按自然边界将原始文本分割为块。
     *
     * <p>边界检测识别：
     * <ul>
     *   <li>段落分隔（双换行）— 最强边界</li>
     *   <li>代码围栏 (```)、列表项 (-、*、1.) — 强边界</li>
     *   <li>表格行 (|) — 强边界</li>
     *   <li>句子结束 (.!?。) — 中等边界</li>
     *   <li>换行 — 弱边界</li>
     *   <li>逗号/冒号 — 软边界（仅在接近 maxChars 时）</li>
     * </ul></p>
     */
    List<String> segmentIntoBlocks(String text) {
        BlockStreamingConfig.BlockStreamingBreak breakMode = config.getBreakMode();
        int maxChars = config.getChunk().getMaxChars();
        boolean preferNewlines = config.getChunk().isPreferNewlines();
        double newlineThreshold = config.getChunk().getNewlineFlushThreshold();

        List<String> blocks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        // 第一遍：按双换行分割（段落分隔 — 最强边界）
        String[] paragraphs = text.split("\\n\\s*\\n", -1);

        for (int p = 0; p < paragraphs.length; p++) {
            String paragraph = paragraphs[p];
            if (paragraph.isEmpty()) {
                if (p > 0 && p < paragraphs.length - 1) {
                    // 空段落 = 有意的空白行，添加为分隔符
                    blocks.add("\n\n");
                }
                continue;
            }

            // 在每个段落内，按强边界分割
            int i = 0;
            while (i < paragraph.length()) {
                char c = paragraph.charAt(i);
                buf.append(c);

                boolean shouldFlush = false;

                if (breakMode == BlockStreamingConfig.BlockStreamingBreak.MESSAGE_END) {
                    // 仅在段落边界处刷新
                    shouldFlush = false;
                } else if (buf.length() >= maxChars) {
                    // 达到 maxChars 时强制刷新
                    shouldFlush = true;
                } else if (c == '\n' && preferNewlines
                        && buf.length() >= (int)(maxChars * newlineThreshold)) {
                    // 当缓冲区足够满时在换行处软刷新
                    shouldFlush = true;
                } else if (isStrongBoundary(c, paragraph, i)) {
                    // 强边界字符
                    shouldFlush = buf.length() >= 20; // 避免单字符块
                } else if (isMediumBoundary(c) && buf.length() >= (int)(maxChars * 0.5)) {
                    // 当 > 50% 满时，在中等边界处刷新
                    shouldFlush = true;
                }

                if (shouldFlush) {
                    blocks.add(buf.toString().trim());
                    buf.setLength(0);
                }
                i++;
            }
        }

        // 刷新剩余内容
        if (buf.length() > 0) {
            String rem = buf.toString().trim();
            if (!rem.isEmpty()) {
                blocks.add(rem);
            }
        }

        return blocks;
    }

    /**
     * 合并相邻小块为较大块。
     */
    List<String> coalesceBlocks(List<String> blocks) {
        BlockStreamingCoalesce c = config.getCoalesce();
        if (!c.isEnabled() || blocks.size() <= 1) {
            return blocks;
        }

        List<String> coalesced = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String block : blocks) {
            if (buffer.length() + block.length() > c.getMaxChars()) {
                // 缓冲区将溢出 — 刷新它
                coalesced.add(buffer.toString().trim());
                buffer.setLength(0);
            }
            if (buffer.length() > 0) {
                buffer.append(config.getHiddenBoundary() ==
                        BlockStreamingConfig.HiddenBoundarySeparator.NEWLINE ? "\n" : "");
            }
            buffer.append(block);
        }

        if (buffer.length() > 0) {
            coalesced.add(buffer.toString().trim());
        }

        return coalesced;
    }

    /**
     * 移除重复的相同块。
     */
    List<String> applyRepeatSuppression(List<String> blocks) {
        if (!config.isRepeatSuppression() || blocks.isEmpty()) {
            return blocks;
        }

        List<String> filtered = new ArrayList<>();
        for (String block : blocks) {
            if (!block.equals(lastEmittedBlock)) {
                filtered.add(block);
                lastEmittedBlock = block;
            }
        }
        return filtered;
    }

    /**
     * 计算块的人类延迟。
     */
    Duration calculateDelay(String block) {
        if (!humanDelayConfig.isEnabled()) {
            return Duration.ZERO;
        }

        int charsPerSec = humanDelayConfig.getCharsPerSecond();

        if (humanDelayConfig.isAdaptiveSpeed() && totalEmittedChars > humanDelayConfig.getLongReplyThreshold()) {
            // 长回复加速：逐步将 CPS 提高到 3 倍
            double excessRatio = Math.min(1.0,
                    (double)(totalEmittedChars - humanDelayConfig.getLongReplyThreshold())
                            / humanDelayConfig.getLongReplyThreshold());
            charsPerSec = (int)(charsPerSec * (1.0 + excessRatio * 2.0));
        }

        // 基础延迟与块长度成正比
        int baseDelayMs = (int)((double)block.length() / charsPerSec * 1000);

        // 限制在最小和最大值之间
        int delayMs = Math.max(humanDelayConfig.getMinDelayMs(),
                Math.min(humanDelayConfig.getMaxDelayMs(), baseDelayMs));

        // 添加小幅随机抖动（±20%）
        double jitter = 0.8 + Math.random() * 0.4;
        delayMs = (int)(delayMs * jitter);

        totalEmittedChars += block.length();
        return Duration.ofMillis(delayMs);
    }

    /**
     * 使用配置的隐藏边界分隔符连接块。
     */
    String joinWithHiddenBoundary(List<String> blocks) {
        String sep;
        switch (config.getHiddenBoundary()) {
            case NULL_CHAR: sep = "\0"; break;
            case NONE: sep = ""; break;
            default: sep = "\n";
        }
        return String.join(sep, blocks);
    }

    private boolean isStrongBoundary(char c, String text, int pos) {
        // 标题标记：行首的 #
        if (c == '#') {
            return pos == 0 || (pos > 0 && text.charAt(pos - 1) == '\n');
        }
        // 代码围栏反引号：```
        if (c == '`' && text.length() > pos + 2
                && text.charAt(pos + 1) == '`' && text.charAt(pos + 2) == '`') {
            return true;
        }
        // 水平分隔线：---、***、___
        if ((c == '-' || c == '*' || c == '_') && text.length() > pos + 2) {
            boolean hr = text.charAt(pos + 1) == c && text.charAt(pos + 2) == c;
            if (hr) {
                return pos == 0 || (pos > 0 && text.charAt(pos - 1) == '\n');
            }
        }
        return false;
    }

    private boolean isMediumBoundary(char c) {
        return c == '\n' || c == '。' || c == '！' || c == '？'
                || c == '.' || c == '!' || c == '?' || c == '；' || c == ';';
    }

    private ServerSentEvent<String> sseMessage(String data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "message");
        payload.put("content", data);
        try {
            return ServerSentEvent.<String>builder()
                    .event("message")
                    .data(objectMapper.writeValueAsString(payload))
                    .build();
        } catch (Exception e) {
            return ServerSentEvent.<String>builder().event("message").data(data).build();
        }
    }
}
```

### 4.1.4 TypingIndicatorController

```java
package lyjew.com.lyclaw.react.stream;

import lyjew.com.lyclaw.react.AgentContext;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 控制在智能体处理间隙期间（工具执行、思考等）
 * 发送给客户端的输入中指示器 SSE 事件。
 *
 * <p>在 RespondStage 中的用法：
 * <pre>{@code
 *   Flux<ServerSentEvent<String>> typingFlux = typingIndicator.startTyping(ctx);
 *   Flux<ServerSentEvent<String>> bodyFlux = reactWithReActEngine(ctx, traceId, toolDefs);
 *   return bodyFlux.takeUntilOther(typingIndicator.stopSignal())
 *                  .mergeWith(typingFlux);
 * }</pre>
 */
public class TypingIndicatorController {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final TypingMode mode;
    private final int intervalSeconds;
    private volatile boolean active = false;

    public enum TypingMode {
        /** 从不发送输入中指示器。 */
        NEVER,
        /** 进入处理间隙时立即发送输入中指示器。 */
        INSTANT,
        /** 在"思考"阶段按间隔发送输入中指示器。 */
        THINKING,
        /** 在消息生成期间按间隔发送输入中指示器。 */
        MESSAGE
    }

    public TypingIndicatorController(TypingMode mode, int intervalSeconds) {
        this.mode = mode;
        this.intervalSeconds = intervalSeconds;
    }

    /**
     * 返回一个 Flux，按配置的间隔发出输入中指示器 SSE 事件。
     * 当调用 stopTyping() 时事件自动停止。
     *
     * @param ctx 要为其发出输入中指示器的智能体上下文
     * @return "typing" SSE 事件的 Flux，每 intervalSeconds 发出一次
     */
    public Flux<ServerSentEvent<String>> startTyping(AgentContext ctx) {
        if (mode == TypingMode.NEVER) {
            return Flux.empty();
        }
        active = true;
        return Flux.interval(Duration.ZERO, Duration.ofSeconds(intervalSeconds))
                .takeWhile(tick -> active)
                .map(tick -> buildTypingEvent(ctx));
    }

    /**
     * 停止发出输入中指示器。startTyping() 的 Flux 将在
     * 下一次 tick 时完成。
     */
    public void stopTyping() {
        this.active = false;
    }

    private ServerSentEvent<String> buildTypingEvent(AgentContext ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "typing");
        payload.put("agentId", ctx.getSessionId());  // sessionId 用作 agentId
        payload.put("stage", ctx.getCurrentStage().get());
        try {
            return ServerSentEvent.<String>builder()
                    .event("typing")
                    .data(objectMapper.writeValueAsString(payload))
                    .build();
        } catch (Exception e) {
            return ServerSentEvent.<String>builder()
                    .event("typing")
                    .data("{\"type\":\"typing\"}")
                    .build();
        }
    }
}
```

### 4.1.5 与 RespondStage 的集成

修改后的 `RespondStage` 按如下方式集成块流式：

```java
// 在 RespondStage.reactWithReActEngine() 内部：
//
// 之前（当前）：
//   return reActEngine.executeStream(chatFacade, request, toolExecutor);
//
// 之后（第四阶段）：
//   BlockStreamingController streamingCtrl = streamingControllerFactory.get(ctx);
//   TypingIndicatorController typingCtrl = typingControllerFactory.get(ctx);
//
//   Flux<ServerSentEvent<String>> typingFlux = typingCtrl.startTyping(ctx);
//   Flux<ServerSentEvent<String>> rawStream = reActEngine.executeStream(chatFacade, request, toolExecutor);
//
//   return rawStream
//       .flatMap(event -> {
//           if ("message".equals(event.event()) && event.data() != null) {
//               String data = event.data();
//               // 如果事件是最终文本块（非流式 token），应用块流式
//               if (isBlockCandidates(data)) {
//                   streamingCtrl.reset();
//                   return streamingCtrl.streamResponse(data);
//               }
//               // 否则原样透传（流式 token 已经是细粒度的）
//               return Flux.just(event);
//           }
//           return Flux.just(event);
//       })
//       .doOnTerminate(typingCtrl::stopTyping)
//       .mergeWith(typingFlux);
```

在 `DefaultReActEngine` 中，`splitIntoEvents()` 方法被委托给 `BlockStreamingController` 替换：

```java
// 在 DefaultReActEngine 中，替换：
//   private Flux<ServerSentEvent<String>> splitIntoEvents(String text) { ... }
//
// 替换为：
//   private final BlockStreamingController streamingController;
//
//   private Flux<ServerSentEvent<String>> streamFinalText(String text) {
//       if (streamingController != null) {
//           return streamingController.streamResponse(text);
//       }
//       // 旧版回退
//       // ... （保留旧的 splitIntoEvents 逻辑以向后兼容）
//   }
```

### 4.1.6 YAML 配置

```yaml
# application.yml — 块流式配置
lyclaw:
  streaming:
    block:
      enabled: true
      break-mode: TEXT_END       # TEXT_END | MESSAGE_END
      chunk:
        max-chars: 500
        max-idle-ms: 1000
        prefer-newlines: true
        newline-flush-threshold: 0.5
      coalesce:
        enabled: true
        max-chars: 8000
        max-idle-ms: 3000
      max-chunk-chars: 2000
      repeat-suppression: true
      delivery-mode: LIVE        # LIVE | FINAL_ONLY
      hidden-boundary: NEWLINE   # NEWLINE | NULL_CHAR | NONE
    human-delay:
      enabled: true
      min-delay-ms: 200
      max-delay-ms: 1500
      chars-per-second: 50
      adaptive-speed: true
      long-reply-threshold: 2000
    typing-indicator:
      mode: THINKING             # NEVER | INSTANT | THINKING | MESSAGE
      interval-seconds: 5
```

---

## 4.2 沙箱增强

### 4.2.1 动机

当前沙箱系统（通过 `ToolSandbox` 接口和 `SandboxLevel` 枚举）支持：
- `DIRECT` — 在当前线程上执行（只读工具）
- `SANDBOX` — 守护线程 + 临时工作目录
- `PROCESS` — 通过 `CommandExecutor` 的独立操作系统进程

缺失的内容：
- **容器隔离**：无 Docker/Podman 支持；`PROCESS` 级别仍作为 JVM 进程的子进程运行。
- **资源限制**：无操作系统级别的内存/CPU/超时强制执行。
- **文件系统桥接**：主机与沙箱之间无双向文件传输。
- **健康监控**：`ToolSandbox.isHealthy()` 没有实际的容器健康检查支持。
- **网络控制**：无法为不受信任的代码禁用网络访问。

### 4.2.2 AgentSandboxConfig

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于容器的沙箱配置。
 * <p>控制用于工具执行隔离的 Docker/Podman 容器设置。
 */
@ConfigurationProperties(prefix = "lyclaw.sandbox")
public class AgentSandboxConfig {

    /**
     * 沙箱后端提供者。
     * <ul>
     *   <li>NONE — 无容器隔离（使用旧版进程沙箱）</li>
     *   <li>DOCKER — 使用 docker-java SDK</li>
     *   <li>PODMAN — 使用 podman CLI（兼容 rootless 设置）</li>
     * </ul>
     */
    private SandboxBackend backend = SandboxBackend.NONE;

    /** 用于沙箱执行的容器镜像。 */
    private String image = "ubuntu:22.04";

    /** 容器内沙箱操作的根目录。 */
    private String rootDir = "/sandbox";

    /** 命令白名单：仅允许这些命令在沙箱内执行。 */
    private List<String> allowedCommands = new ArrayList<>();

    /** 命令黑名单：明确禁止这些命令。 */
    private List<String> deniedCommands = new ArrayList<>();

    /** 沙箱容器是否有网络访问权限。默认 false 以保安全。 */
    private boolean networkEnabled = false;

    /** 沙箱容器是否可以写入文件系统。 */
    private boolean fileSystemWriteEnabled = true;

    /** 容器的内存限制（MB）。 */
    private long memoryLimitMb = 512;

    /** CPU 限制（核数，可为小数）。 */
    private double cpuLimit = 1.0;

    /** 单次工具调用的最大执行时间（秒）。 */
    private int timeoutSeconds = 300;

    /** 文件系统桥接配置。 */
    private SandboxFsBridge fsBridge = new SandboxFsBridge();

    /** 容器启动超时（秒）。 */
    private int startupTimeoutSeconds = 30;

    /** 如果为 true，在同一会话的工具调用之间复用容器。 */
    private boolean reuseContainer = true;

    /** 容器自动清理前的最大空闲时间（秒）。 */
    private int containerIdleTimeoutSeconds = 600;

    /** Docker socket 路径（默认：unix:///var/run/docker.sock）。 */
    private String dockerSocket = "unix:///var/run/docker.sock";

    /** Podman 后端的 Podman socket 路径。 */
    private String podmanSocket = "unix:///run/podman/podman.sock";

    // 省略 getter 和 setter

    public enum SandboxBackend { NONE, DOCKER, PODMAN }
}
```

#### SandboxFsBridge（内部配置）

```java
/**
 * 主机-沙箱文件共享的文件系统桥接配置。
 */
public class SandboxFsBridge {

    /** 要桥接到沙箱中的主机工作空间目录（只读）。 */
    private String hostWorkspace = "./workspace";

    /** 容器内挂载主机工作空间的路径。 */
    private String sandboxWorkspace = "/workspace";

    /** 容器内的工作空间挂载是否为只读。 */
    private boolean workspaceReadOnly = true;

    /** 沙箱可写文件的主机临时目录。 */
    private String hostTmp = "./sandbox-tmp";

    /** 容器内可写临时文件的路径。 */
    private String sandboxTmp = "/tmp/sandbox";

    /** tmp 卷的最大大小（MB）。 */
    private long tmpMaxSizeMb = 500;

    // 省略 getter 和 setter
}
```

### 4.2.3 SandboxExecutionService

```java
package lyjew.com.lyclaw.security.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;

import lyjew.com.lyclaw.config.AgentSandboxConfig;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于容器的沙箱执行服务。
 *
 * <p>管理 Docker/Podman 容器生命周期，用于隔离的工具执行。
 * 与 SandboxHook 集成（在配置了容器后端时替换 SandboxLevel.PROCESS 下
 * 的直接 ToolSandbox 委托）。
 *
 * <h3>生命周期：</h3>
 * <ol>
 *   <li>createSandbox(config) — 拉取镜像，创建容器，启动它</li>
 *   <li>executeInSandbox(handle, tool, args) — 通过 docker exec 执行工具</li>
 *   <li>isHealthy(handle) — 检查容器运行状态</li>
 *   <li>destroy(handle) — 停止并删除容器</li>
 * </ol>
 */
public class SandboxExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SandboxExecutionService.class);

    private final AgentSandboxConfig config;
    private final DockerClient dockerClient;
    private final Map<String, SandboxHandle> activeHandles = new ConcurrentHashMap<>();

    public SandboxExecutionService(AgentSandboxConfig config) {
        this.config = config;
        this.dockerClient = config.getBackend() == AgentSandboxConfig.SandboxBackend.DOCKER
                ? buildDockerClient(config) : null;
    }

    // ── Docker 客户端工厂 ──────────────────────────────────────────

    private DockerClient buildDockerClient(AgentSandboxConfig config) {
        DefaultDockerClientConfig clientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(config.getDockerSocket())
                .build();

        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(clientConfig.getDockerHost())
                .sslConfig(clientConfig.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(config.getTimeoutSeconds() + 10))
                .build();

        return DockerClientImpl.getInstance(clientConfig, httpClient);
    }

    // ── 沙箱生命周期 ──────────────────────────────────────────────

    /**
     * 创建并启动沙箱容器。
     *
     * @param sessionId 此沙箱所属的会话
     * @return 成功时发出 SandboxHandle 的 Mono
     */
    public Mono<SandboxHandle> createSandbox(String sessionId) {
        if (config.getBackend() == AgentSandboxConfig.SandboxBackend.NONE) {
            return Mono.just(SandboxHandle.none());
        }

        return Mono.fromCallable(() -> {
            String containerName = "lyclaw-sandbox-" + sessionId + "-" + UUID.randomUUID().toString().substring(0, 8);

            log.info("创建沙箱容器：name={} image={}", containerName, config.getImage());

            // 如果不存在则拉取镜像
            try {
                dockerClient.pullImageCmd(config.getImage()).start().awaitCompletion();
            } catch (Exception e) {
                log.warn("镜像拉取失败（可能本地已存在）：{}", e.getMessage());
            }

            // 构建带资源限制的主机配置
            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withMemory(config.getMemoryLimitMb() * 1024 * 1024) // 字节
                    .withNanoCPUs((long)(config.getCpuLimit() * 1_000_000_000L))
                    .withNetworkMode(config.isNetworkEnabled() ? "bridge" : "none")
                    .withReadonlyRootfs(!config.isFileSystemWriteEnabled())
                    .withAutoRemove(true);

            // 挂载卷
            List<com.github.dockerjava.api.model.Bind> binds = new ArrayList<>();

            // 工作空间挂载（如配置则为只读）
            Path hostWorkspace = Paths.get(config.getFsBridge().getHostWorkspace())
                    .toAbsolutePath().normalize();
            Files.createDirectories(hostWorkspace);
            String workspaceMode = config.getFsBridge().isWorkspaceReadOnly() ? "ro" : "rw";
            binds.add(new Bind(hostWorkspace.toString(),
                    new com.github.dockerjava.api.model.Volume(config.getFsBridge().getSandboxWorkspace()),
                    AccessMode.valueOf(workspaceMode)));

            // Tmp 挂载（读写）
            Path hostTmp = Paths.get(config.getFsBridge().getHostTmp())
                    .toAbsolutePath().normalize();
            Files.createDirectories(hostTmp);
            binds.add(new Bind(hostTmp.toString(),
                    new com.github.dockerjava.api.model.Volume(config.getFsBridge().getSandboxTmp()),
                    AccessMode.rw));

            hostConfig.withBinds(binds);

            // 创建容器
            CreateContainerCmd createCmd = dockerClient.createContainerCmd(config.getImage())
                    .withName(containerName)
                    .withHostConfig(hostConfig)
                    .withWorkingDir(config.getRootDir())
                    .withCmd("sleep", "infinity") // 保持容器存活
                    .withAttachStdin(false)
                    .withAttachStdout(true)
                    .withAttachStderr(true);

            CreateContainerResponse createResp = createCmd.exec();
            String containerId = createResp.getId();

            // 启动容器
            dockerClient.startContainerCmd(containerId).exec();

            // 等待容器就绪
            boolean ready = waitForContainerReady(containerId, config.getStartupTimeoutSeconds());
            if (!ready) {
                throw new RuntimeException("沙箱容器启动超时：" + containerName);
            }

            SandboxHandle handle = new SandboxHandle(sessionId, containerId, containerName);
            activeHandles.put(sessionId, handle);

            log.info("沙箱容器已启动：containerId={} name={}", containerId, containerName);
            return handle;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── 工具执行 ─────────────────────────────────────────────────

    /**
     * 在沙箱容器内执行工具。
     *
     * @param handle 要在其中执行的沙箱
     * @param tool   工具定义
     * @param args   工具参数
     * @return 发出执行结果的 Mono
     */
    public Mono<ToolExecutionResult> executeInSandbox(SandboxHandle handle, Tool tool,
                                                       Map<String, Object> args) {
        if (handle.isNone()) {
            return Mono.just(ToolExecutionResult.failure("没有可用的沙箱容器"));
        }

        return Mono.fromCallable(() -> {
            // 构建 docker exec 命令
            String[] cmd = buildExecCommand(tool, args);

            // 对照允许/拒绝列表验证
            if (!isCommandAllowed(cmd[0])) {
                return ToolExecutionResult.failure("命令 '" + cmd[0] + "' 不允许在沙箱中执行");
            }

            ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(handle.getContainerId())
                    .withCmd(cmd)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withTty(false)
                    .exec();

            // 捕获输出
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            try {
                dockerClient.execStartCmd(execCreate.getId())
                        .withDetach(false)
                        .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<>() {
                            @Override
                            public void onNext(com.github.dockerjava.api.model.Frame frame) {
                                String text = new String(frame.getPayload());
                                if (frame.getStreamType() == com.github.dockerjava.api.model.StreamType.STDOUT) {
                                    stdout.append(text);
                                } else {
                                    stderr.append(text);
                                }
                            }
                        })
                        .awaitCompletion(config.getTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("沙箱执行超时或失败：{}", e.getMessage());
                return ToolExecutionResult.failure("沙箱执行错误：" + e.getMessage());
            }

            // 检查退出码
            InspectExecResponse execInspect = dockerClient.inspectExecCmd(execCreate.getId()).exec();
            int exitCode = execInspect.getExitCode() != null ? execInspect.getExitCode() : -1;

            if (exitCode == 0) {
                return ToolExecutionResult.success(stdout.toString().trim());
            } else {
                String error = stderr.length() > 0 ? stderr.toString().trim() : stdout.toString().trim();
                return ToolExecutionResult.failure("退出码 " + exitCode + "：" + error);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── 文件系统桥接 ──────────────────────────────────────────────

    /**
     * 将文件从主机复制到沙箱容器。
     */
    public Mono<Void> bridgeFileToSandbox(String hostPath, String sandboxPath,
                                          SandboxHandle handle) {
        if (handle.isNone()) return Mono.empty();

        return Mono.fromRunnable(() -> {
            try {
                Path hostFile = Paths.get(hostPath);
                if (!Files.exists(hostFile)) {
                    log.warn("主机文件不存在：{}", hostPath);
                    return;
                }

                try (InputStream tarStream = createTarArchive(hostFile)) {
                    dockerClient.copyArchiveToContainerCmd(handle.getContainerId())
                            .withRemotePath(Paths.get(sandboxPath))
                            .withTarInputStream(tarStream)
                            .exec();
                }
                log.debug("文件已桥接到沙箱：{} -> {}:{}",
                        hostPath, handle.getContainerId(), sandboxPath);
            } catch (Exception e) {
                log.error("桥接文件到沙箱失败：{}", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * 将文件从沙箱容器复制到主机。
     */
    public Mono<Void> bridgeFileFromSandbox(String sandboxPath, String hostPath,
                                            SandboxHandle handle) {
        if (handle.isNone()) return Mono.empty();

        return Mono.fromRunnable(() -> {
            try {
                Path hostDir = Paths.get(hostPath).getParent();
                if (hostDir != null) {
                    Files.createDirectories(hostDir);
                }

                try (InputStream tarStream = dockerClient.copyArchiveFromContainerCmd(
                        handle.getContainerId(), sandboxPath).exec()) {
                    extractTarArchive(tarStream, Paths.get(hostPath));
                }
                log.debug("文件已从沙箱桥接：{}:{} -> {}",
                        handle.getContainerId(), sandboxPath, hostPath);
            } catch (Exception e) {
                log.error("从沙箱桥接文件失败：{}", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    // ── 健康检查 ───────────────────────────────────────────────────

    /**
     * 检查沙箱容器是否仍然健康。
     */
    public Mono<Boolean> isHealthy(SandboxHandle handle) {
        if (handle.isNone()) return Mono.just(false);

        return Mono.fromCallable(() -> {
            try {
                InspectContainerResponse inspect = dockerClient.inspectContainerCmd(handle.getContainerId()).exec();
                return inspect.getState() != null && Boolean.TRUE.equals(inspect.getState().getRunning());
            } catch (Exception e) {
                log.warn("容器 {} 健康检查失败：{}", handle.getContainerId(), e.getMessage());
                return false;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── 销毁 ────────────────────────────────────────────────────────

    /**
     * 停止并删除沙箱容器，释放所有资源。
     */
    public Mono<Void> destroy(SandboxHandle handle) {
        if (handle.isNone()) return Mono.empty();

        return Mono.fromRunnable(() -> {
            try {
                dockerClient.stopContainerCmd(handle.getContainerId())
                        .withTimeout(10)
                        .exec();
                // 已配置自动删除，所以显式删除是可选的
                log.info("沙箱容器已销毁：containerId={}", handle.getContainerId());
            } catch (Exception e) {
                log.warn("销毁沙箱容器 {} 出错：{}",
                        handle.getContainerId(), e.getMessage());
                // 作为回退方案强制删除
                try {
                    dockerClient.removeContainerCmd(handle.getContainerId())
                            .withForce(true)
                            .exec();
                } catch (Exception f) {
                    log.error("容器 {} 强制删除也失败：{}",
                            handle.getContainerId(), f.getMessage());
                }
            } finally {
                activeHandles.remove(handle.getSessionId());
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * 销毁所有活跃沙箱容器。在应用关闭时调用。
     */
    public Mono<Void> destroyAll() {
        return Flux.fromIterable(new ArrayList<>(activeHandles.values()))
                .flatMap(this::destroy)
                .then();
    }

    // ── 私有辅助方法 ────────────────────────────────────────────────

    private boolean waitForContainerReady(String containerId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
                if (inspect.getState() != null && Boolean.TRUE.equals(inspect.getState().getRunning())) {
                    return true;
                }
                Thread.sleep(500);
            } catch (Exception e) {
                // 容器可能尚未就绪
            }
        }
        return false;
    }

    private String[] buildExecCommand(Tool tool, Map<String, Object> args) {
        // 对于命令工具，包装在 bash -c 中
        // 对于脚本工具，先写入脚本到 /tmp 然后执行
        String command = args.getOrDefault("command", "").toString();
        if (command.isEmpty()) {
            command = tool.getDescription();
        }
        return new String[]{"bash", "-c", command};
    }

    private boolean isCommandAllowed(String command) {
        List<String> allowed = config.getAllowedCommands();
        List<String> denied = config.getDeniedCommands();

        // 如果配置了白名单，只有白名单中的命令可以通过
        if (!allowed.isEmpty()) {
            return allowed.stream().anyMatch(cmd -> command.startsWith(cmd));
        }

        // 如果配置了黑名单，拒绝匹配的命令
        if (!denied.isEmpty()) {
            if (denied.stream().anyMatch(cmd -> command.startsWith(cmd))) {
                return false;
            }
        }

        // 无显式规则 = 允许全部（向后兼容）
        return true;
    }

    private InputStream createTarArchive(Path file) throws IOException {
        // 单文件的最小 TAR 创建（生产环境中使用 Apache Commons Compress）
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        // 简化：实际代码中使用适当的 TAR 库
        // 这是展示集成模式的占位符
        baos.write(("tar-content:" + file.getFileName()).getBytes());
        return new java.io.ByteArrayInputStream(baos.toByteArray());
    }

    private void extractTarArchive(InputStream tarStream, Path destPath) {
        // 简化：实际代码中使用适当的 TAR 库
        // 展示集成模式的占位符
    }
}
```

### 4.2.4 SandboxHandle

```java
package lyjew.com.lyclaw.security.sandbox;

/**
 * 活跃沙箱容器的句柄。
 * <p>创建后不可变；用作沙箱生命周期操作的键。
 */
public class SandboxHandle {

    private final String sessionId;
    private final String containerId;
    private final String containerName;
    private final boolean none;

    private SandboxHandle(String sessionId, String containerId, String containerName, boolean none) {
        this.sessionId = sessionId;
        this.containerId = containerId;
        this.containerName = containerName;
        this.none = none;
    }

    public SandboxHandle(String sessionId, String containerId, String containerName) {
        this(sessionId, containerId, containerName, false);
    }

    /** 当没有配置沙箱后端时创建空操作句柄。 */
    public static SandboxHandle none() {
        return new SandboxHandle("", "", "", true);
    }

    public boolean isNone() { return none; }
    public String getSessionId() { return sessionId; }
    public String getContainerId() { return containerId; }
    public String getContainerName() { return containerName; }

    @Override
    public String toString() {
        return none ? "SandboxHandle[NONE]" :
                "SandboxHandle[session=" + sessionId + ", container=" + containerId + "]";
    }
}
```

### 4.2.5 与 SandboxHook 的集成

现有的 `SandboxHook` 当前委托给 `ToolSandbox.execute(tool, args, level)`。在第四阶段中，`SandboxHook` 更新为当请求 `SandboxLevel.PROCESS` 且配置了容器后端时使用 `SandboxExecutionService`：

```java
// 更新后的 SandboxHook.wrapToolExecutor()：
//
//   SandboxLevel level = ctx.getSandboxLevel() != null ? ctx.getSandboxLevel() : SandboxLevel.DIRECT;
//
//   if (level == SandboxLevel.PROCESS && sandboxExecutionService != null) {
//       // 基于容器的沙箱
//       SandboxHandle handle = ctx.getSandboxHandle();
//       if (handle == null) {
//           // 为此会话延迟创建沙箱
//           handle = sandboxExecutionService.createSandbox(ctx.getSessionId()).block();
//           ctx.setSandboxHandle(handle);
//       }
//       return sandboxExecutionService.executeInSandbox(handle, tool, args)
//               .map(result -> result.isSuccess() ? result.getResult() : "错误：" + result.getError())
//               .block();
//   }
//
//   // 回退：DIRECT 和 SANDBOX 级别的旧版 toolSandbox
//   ToolExecutionResult result = toolSandbox.execute(tool, args, level);
//   return result.isSuccess() ? result.getResult() : "错误：" + result.getError();
```

`AgentContext` 扩展了新的字段：

```java
// 添加到 AgentContext：
private volatile SandboxHandle sandboxHandle;
public SandboxHandle getSandboxHandle() { return sandboxHandle; }
public void setSandboxHandle(SandboxHandle handle) { this.sandboxHandle = handle; }
```

### 4.2.6 YAML 配置

```yaml
# application.yml — 沙箱配置
lyclaw:
  sandbox:
    backend: DOCKER                  # NONE | DOCKER | PODMAN
    image: ubuntu:22.04
    root-dir: /sandbox
    allowed-commands:
      - python3
      - node
      - bash
      - cat
      - ls
      - grep
      - sed
      - awk
    denied-commands:
      - rm
      - dd
      - mkfs
      - shutdown
      - reboot
    network-enabled: false
    file-system-write-enabled: true
    memory-limit-mb: 512
    cpu-limit: 1.0
    timeout-seconds: 300
    startup-timeout-seconds: 30
    reuse-container: true
    container-idle-timeout-seconds: 600
    docker-socket: unix:///var/run/docker.sock
    podman-socket: unix:///run/podman/podman.sock
    fs-bridge:
      host-workspace: ./workspace
      sandbox-workspace: /workspace
      workspace-read-only: true
      host-tmp: ./sandbox-tmp
      sandbox-tmp: /tmp/sandbox
      tmp-max-size-mb: 500
```

---

## 4.3 心跳系统

### 4.3.1 动机

长期运行的智能体需要定期的"签到"ping 以：
- 验证智能体仍在运行
- 向用户提供主动状态更新
- 执行计划中的维护任务
- 支持"每日简报" / "早晨摘要"模式

心跳系统是一个类 cron 调度器，按计划运行单轮 ReAct 调用，具有可配置的轻量上下文、隔离会话和目标投递。

### 4.3.2 HeartbeatConfig

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 智能体心跳配置。
 * <p>控制计划中的"ping"调用，保持智能体活跃
 * 并向用户传递定期更新。
 */
@ConfigurationProperties(prefix = "lyclaw.heartbeat")
public class HeartbeatConfig {

    /** 为此智能体启用心跳调度器。 */
    private boolean enabled = false;

    /** 心跳运行之间的类 Cron 间隔。 */
    private Duration every = Duration.ofMinutes(30);

    /** 活跃时间窗口（时间范围的 cron 表达式，例如 "0 0 9 ? * MON-FRI"）。 */
    private String activeHoursCron;

    /** 使用人类可读格式的活跃时间配置。 */
    private ActiveHours activeHours = new ActiveHours();

    /** 心跳运行的模型覆盖（null 时使用智能体默认值）。 */
    private String model;

    /** 心跳运行分组的会话键（默认为智能体名称）。 */
    private String sessionKey;

    /** 投递心跳结果的目标。 */
    private DeliveryTarget target = DeliveryTarget.LAST;

    /** 当目标指定用户/频道时的私信策略。 */
    private DirectPolicy directPolicy = DirectPolicy.ALLOW;

    /** 目标接收者：E.164 电话号码或聊天频道 ID。 */
    private String to;

    /** 多账户频道选择的账户 ID。 */
    private String accountId;

    /** 自定义心跳提示。如果为空/null，使用默认系统提示。 */
    private String prompt;

    /** 如果为 true，在心跳上下文中包含系统提示部分。 */
    private boolean includeSystemPromptSection = true;

    /** 心跳确认消息的最大字符数。 */
    private int ackMaxChars = 30;

    /** 抑制心跳运行中的工具执行错误警告。 */
    private boolean suppressToolErrorWarnings = true;

    /** 心跳执行超时（秒）。 */
    private int timeoutSeconds = 120;

    /**
     * 如果为 true，使用轻量上下文（仅 HEARTBEAT.md）。
     * 为 false 时，加载包含所有记忆文件的完整智能体上下文。
     */
    private boolean lightContext = true;

    /**
     * 如果为 true，为每次心跳运行创建全新的隔离会话。
     * sessionKey 被复用但消息历史不会延续。
     */
    private boolean isolatedSession = true;

    /**
     * 如果为 true，当子智能体活跃运行时跳过心跳。
     * 防止心跳中断正在进行的委派任务。
     */
    private boolean skipWhenBusy = true;

    /**
     * 如果为 true，在心跳响应中包含推理/思考内容。
     */
    private boolean includeReasoning = false;

    // 省略 getter 和 setter

    public enum DeliveryTarget { LAST, NONE }
    public enum DirectPolicy { ALLOW, BLOCK }

    /**
     * 活跃时间窗口配置。
     */
    public static class ActiveHours {
        /** 窗口开始时间，HH:mm 格式。 */
        private String start = "09:00";
        /** 窗口结束时间，HH:mm 格式。 */
        private String end = "18:00";
        /** 时区标识符，例如 "Asia/Shanghai"、"America/New_York"。 */
        private String timezone = "Asia/Shanghai";
        /** 星期几（MON、TUE、...、SUN）或空表示所有天。 */
        private String daysOfWeek = "";

        // 省略 getter 和 setter
    }
}
```

### 4.3.3 HeartbeatScheduler

```java
package lyjew.com.lyclaw.react.heartbeat;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.config.HeartbeatConfig;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.DefaultReActEngine;
import lyjew.com.lyclaw.react.ToolExecutor;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.event.EventBus;
import lyjew.com.lyclaw.security.SecurityManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 Cron 的智能体心跳调度器。
 *
 * <p>实现 {@link SchedulingConfigurer} 以根据每个智能体的
 * {@link HeartbeatConfig} 动态注册心跳任务。
 *
 * <h3>每次心跳 tick 的执行流程：</h3>
 * <ol>
 *   <li>检查 activeHours 窗口 — 如果不在范围内则跳过</li>
 *   <li>检查 skipWhenBusy — 如果子智能体活跃则跳过</li>
 *   <li>创建隔离会话（如果 isolatedSession 为 true）</li>
 *   <li>加载轻量上下文（如果 lightContext — 仅 HEARTBEAT.md）</li>
 *   <li>运行带心跳提示的单轮 ReAct</li>
 *   <li>将结果投递到目标频道/用户</li>
 *   <li>分发 heartbeat_start / heartbeat_reply / heartbeat_complete 事件</li>
 * </ol>
 */
public class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);

    private final ChatFacade chatFacade;
    private final ToolRegistry toolRegistry;
    private final EventBus eventBus;
    private final SecurityManager securityManager;

    // 智能体 sessionKey → 配置的映射，用于动态调度
    private final Map<String, HeartbeatConfig> agentConfigs = new ConcurrentHashMap<>();

    // 跟踪每个智能体的活跃子智能体数量
    private final Map<String, AtomicInteger> activeSubAgents = new ConcurrentHashMap<>();

    // 用于取消的 ScheduledFuture 句柄
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    public HeartbeatScheduler(ChatFacade chatFacade, ToolRegistry toolRegistry,
                               EventBus eventBus, SecurityManager securityManager) {
        this.chatFacade = chatFacade;
        this.toolRegistry = toolRegistry;
        this.eventBus = eventBus;
        this.securityManager = securityManager;
    }

    /**
     * 为智能体注册或更新心跳配置。
     * 在智能体初始化时调用。
     *
     * @param agentId 智能体标识符
     * @param config  心跳配置
     */
    public void registerAgent(String agentId, HeartbeatConfig config) {
        if (config == null || !config.isEnabled()) {
            // 移除任何现有的调度
            cancelSchedule(agentId);
            agentConfigs.remove(agentId);
            return;
        }

        agentConfigs.put(agentId, config);

        // 取消现有调度并创建新的
        cancelSchedule(agentId);
        scheduleAgent(agentId, config);
    }

    /**
     * 通知调度器给定父智能体的子智能体已启动。
     * 由 skipWhenBusy 用于在委派期间推迟心跳。
     */
    public void onSubAgentStarted(String parentAgentId) {
        activeSubAgents.computeIfAbsent(parentAgentId, k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    /**
     * 通知调度器给定父智能体的子智能体已完成。
     */
    public void onSubAgentCompleted(String parentAgentId) {
        AtomicInteger count = activeSubAgents.get(parentAgentId);
        if (count != null && count.decrementAndGet() <= 0) {
            activeSubAgents.remove(parentAgentId);
        }
    }

    // ── 内部调度 ────────────────────────────────────────────

    private void scheduleAgent(String agentId, HeartbeatConfig config) {
        long intervalMs = config.getEvery().toMillis();

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> executeHeartbeat(agentId, config),
                intervalMs, // 初始延迟与间隔相同
                intervalMs,
                TimeUnit.MILLISECONDS
        );

        scheduledTasks.put(agentId, future);
        log.info("已为智能体 '{}' 安排心跳：每 {} 秒", agentId,
                config.getEvery().getSeconds());
    }

    private void cancelSchedule(String agentId) {
        ScheduledFuture<?> future = scheduledTasks.remove(agentId);
        if (future != null) {
            future.cancel(false);
            log.info("已取消智能体 '{}' 的心跳", agentId);
        }
    }

    // ── 心跳执行 ────────────────────────────────────────────

    private void executeHeartbeat(String agentId, HeartbeatConfig config) {
        try {
            // 1. 检查活跃时间窗口
            if (!isWithinActiveHours(config.getActiveHours())) {
                log.debug("智能体 '{}' 心跳跳过：不在活跃时间内", agentId);
                return;
            }

            // 2. 检查 skipWhenBusy
            if (config.isSkipWhenBusy()) {
                AtomicInteger count = activeSubAgents.get(agentId);
                if (count != null && count.get() > 0) {
                    log.debug("智能体 '{}' 心跳跳过：{} 个子智能体活跃中", agentId, count.get());
                    return;
                }
            }

            // 3. 创建会话键
            String sessionKey = config.getSessionKey() != null ? config.getSessionKey() : agentId;
            String runId = sessionKey + "-" + UUID.randomUUID().toString().substring(0, 8);
            long startMs = System.currentTimeMillis();

            log.info("心跳开始：agent={} runId={}", agentId, runId);

            // 4. 准备上下文
            AgentContext ctx = buildHeartbeatContext(agentId, sessionKey, runId, config);

            // 5. 运行单轮 ReAct
            String result = runHeartbeatReAct(ctx, config);

            long elapsedMs = System.currentTimeMillis() - startMs;

            // 6. 投递结果
            deliverHeartbeatResult(agentId, config, result);

            // 7. 分发事件
            dispatchHeartbeatEvent("heartbeat_complete", agentId, runId,
                    Map.of("elapsedMs", elapsedMs, "message", result.substring(0,
                            Math.min(result.length(), config.getAckMaxChars()))));

            log.info("心跳完成：agent={} runId={} 耗时={}ms", agentId, runId, elapsedMs);

        } catch (Exception e) {
            log.error("智能体 '{}' 心跳失败：{}", agentId, e.getMessage(), e);
            dispatchHeartbeatEvent("heartbeat_error", agentId, null,
                    Map.of("error", e.getMessage()));
        }
    }

    private AgentContext buildHeartbeatContext(String agentId, String sessionKey,
                                                String runId, HeartbeatConfig config) {
        String prompt = config.getPrompt();
        if (prompt == null || prompt.isEmpty()) {
            prompt = "心跳签到。提供关于当前状态和待处理任务的简要状态更新。";
        }

        if (config.isIncludeSystemPromptSection()) {
            prompt = "[系统状态检查]\n" + prompt;
        }

        // 为此次单次心跳运行创建临时上下文
        AgentContext ctx = new AgentContext(
                config.isIsolatedSession() ? runId : sessionKey,
                prompt,
                null, // 系统提示由智能体配置处理
                toolRegistry,
                null, // 无方法 — 心跳不是用户调用
                null
        );

        if (config.isLightContext()) {
            // 仅加载 HEARTBEAT.md 上下文（由记忆系统实现）
            ctx.setAttribute("heartbeatMode", true);
            ctx.setAttribute("contextFiles", List.of("HEARTBEAT.md"));
        }

        return ctx;
    }

    private String runHeartbeatReAct(AgentContext ctx, HeartbeatConfig config) {
        // 构建心跳的最小 ChatRequest
        ChatRequest request = ChatRequest.builder()
                .messages(new ArrayList<>(List.of(Message.user(ctx.getUserMessage()))))
                .stream(false) // 心跳不使用流式
                .build();

        // 使用不带工具的 ReActEngine 实例以进行轻量执行
        DefaultReActEngine engine = new DefaultReActEngine(null, null) {
            @Override
            public String execute(ChatFacade chatFacade, ChatRequest request,
                                  ToolExecutor toolExecutor) {
                // 单轮：心跳默认不进行工具调用
                try {
                    var model = chatFacade.resolveModel(chatFacade.route(request, null));
                    var response = model.chat(request);
                    String content = response.getContent();
                    request.getMessages().add(Message.assistant(content != null ? content : ""));
                    return content != null ? content : "（无响应）";
                } catch (Exception e) {
                    log.error("心跳 LLM 调用失败：{}", e.getMessage());
                    return "[心跳 LLM 错误：" + e.getMessage() + "]";
                }
            }
        };

        try {
            String result = engine.execute(chatFacade, request, null);
            return result != null ? result : "（空响应）";
        } catch (Exception e) {
            return "[心跳错误：" + e.getMessage() + "]";
        }
    }

    private void deliverHeartbeatResult(String agentId, HeartbeatConfig config, String result) {
        if (config.getTarget() == HeartbeatConfig.DeliveryTarget.NONE) {
            return;
        }

        // 投递到目标频道/用户（实现取决于频道适配器）
        // 目前发布为事件供频道适配器获取
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("agentId", agentId);
        delivery.put("message", result);
        delivery.put("to", config.getTo());
        delivery.put("accountId", config.getAccountId());
        delivery.put("timestamp", Instant.now().toString());

        eventBus.publish(new HeartbeatDeliveryEvent("heartbeat-scheduler", agentId, delivery));
    }

    private void dispatchHeartbeatEvent(String eventType, String agentId, String runId,
                                         Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>(data);
        payload.put("agentId", agentId);
        if (runId != null) payload.put("runId", runId);
        payload.put("timestamp", Instant.now().toString());
        eventBus.publish(new HeartbeatEvent("heartbeat-scheduler", eventType, payload));
    }

    // ── 活跃时间检查 ─────────────────────────────────────────────

    private boolean isWithinActiveHours(HeartbeatConfig.ActiveHours hours) {
        if (hours == null || hours.getStart() == null || hours.getEnd() == null) {
            return true; // 无限制
        }

        try {
            ZoneId zone = ZoneId.of(hours.getTimezone());
            ZonedDateTime now = ZonedDateTime.now(zone);

            LocalTime start = LocalTime.parse(hours.getStart(), DateTimeFormatter.ofPattern("HH:mm"));
            LocalTime end = LocalTime.parse(hours.getEnd(), DateTimeFormatter.ofPattern("HH:mm"));
            LocalTime current = now.toLocalTime();

            // 如果配置了星期几则检查
            if (hours.getDaysOfWeek() != null && !hours.getDaysOfWeek().isEmpty()) {
                String today = now.getDayOfWeek().name().substring(0, 3).toUpperCase();
                if (!hours.getDaysOfWeek().toUpperCase().contains(today)) {
                    return false;
                }
            }

            if (start.isBefore(end)) {
                // 正常范围：例如 09:00 - 18:00
                return !current.isBefore(start) && current.isBefore(end);
            } else {
                // 跨夜范围：例如 22:00 - 06:00
                return !current.isBefore(start) || current.isBefore(end);
            }
        } catch (Exception e) {
            log.warn("活跃时间检查失败，默认允许：{}", e.getMessage());
            return true;
        }
    }
}
```

### 4.3.4 心跳事件类型

```java
package lyjew.com.lyclaw.react.heartbeat;

import lyjew.com.lyclaw.event.Event;

import java.util.Map;

/**
 * 心跳生命周期事件。在心跳运行的每个阶段发布。
 *
 * <p>事件类型：
 * <ul>
 *   <li>heartbeat_start — agentId、sessionKey、timestamp</li>
 *   <li>heartbeat_thinking — agentId（LLM 正在生成）</li>
 *   <li>heartbeat_reply — agentId、message</li>
 *   <li>heartbeat_complete — agentId、elapsedMs、消息预览</li>
 *   <li>heartbeat_error — agentId、error</li>
 * </ul>
 */
public class HeartbeatEvent extends Event {

    private final Map<String, Object> data;

    public HeartbeatEvent(String source, String eventType, Map<String, Object> data) {
        super(source, "heartbeat." + eventType);
        this.data = data;
    }

    public Map<String, Object> getData() { return data; }
}

/**
 * 心跳投递事件。当心跳结果需要投递到
 * 目标频道或用户时发布。
 */
class HeartbeatDeliveryEvent extends Event {

    private final Map<String, Object> deliveryData;

    public HeartbeatDeliveryEvent(String source, String agentId, Map<String, Object> deliveryData) {
        super(source, "heartbeat.delivery");
        this.deliveryData = deliveryData;
    }

    public Map<String, Object> getDeliveryData() { return deliveryData; }
}
```

### 4.3.5 SSE 事件模式

心跳 SSE 事件（当心跳由外部请求而非 cron 触发时）：

| 事件 | `event:` 字段 | `data:` 结构 |
|---|---|---|
| `heartbeat_start` | `heartbeat_start` | `{"agentId":"...", "sessionKey":"...", "timestamp":"..."}` |
| `heartbeat_thinking` | `heartbeat_thinking` | `{"agentId":"..."}` |
| `heartbeat_reply` | `heartbeat_reply` | `{"agentId":"...", "message":"...", "..."}` |
| `heartbeat_complete` | `heartbeat_complete` | `{"agentId":"...", "elapsedMs":1234, "message":"预览..."}` |
| `heartbeat_error` | `heartbeat_error` | `{"agentId":"...", "error":"..."}` |

### 4.3.6 YAML 配置

```yaml
# application.yml — 每个智能体的心跳配置
lyclaw:
  heartbeat:
    enabled: true
    every: 30m                      # 持续时间：30m、1h 等
    active-hours:
      start: "09:00"
      end: "18:00"
      timezone: Asia/Shanghai
      days-of-week: MON,TUE,WED,THU,FRI
    model: null                     # null = 使用智能体默认值
    session-key: daily-checkin
    target: LAST                    # LAST | NONE
    direct-policy: ALLOW            # ALLOW | BLOCK
    to: null                        # E.164 电话或聊天 ID
    account-id: null                # 多账户选择器
    prompt: "早上好！这是您的每日简报。今天的首要任务是什么？"
    include-system-prompt-section: true
    ack-max-chars: 30
    suppress-tool-error-warnings: true
    timeout-seconds: 120
    light-context: true
    isolated-session: true
    skip-when-busy: true
    include-reasoning: false
```

---

## 4.4 运行重试增强

### 4.4.1 动机

当前的 `ReflexionLoop` 使用简单的 `maxRetries` 参数（通常为 2）和静态的 `qualityThreshold`（0.6）。这对生产环境来说是不够的：

- **硬编码的重试预算**：无按智能体或按回退模型的区分
- **无重试历史**：无法从之前的失败中学习以调整策略
- **无模型回退链**：如果主模型持续失败，没有机制来尝试替代（更便宜/更快/更小）模型
- **无重试元数据**：当前 `ReflexionResult.Attempt` 仅记录分数和反馈，不记录使用的模型/提供者

### 4.4.2 RunRetriesConfig

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ReAct 循环反思重试的运行重试配置。
 * <p>控制重试预算、策略选择和模型回退行为。
 */
@ConfigurationProperties(prefix = "lyclaw.retry")
public class RunRetriesConfig {

    /**
     * 主模型的基础重试迭代次数。
     * 总重试次数 = base + (perProfile * numberOfFallbackProfiles)
     */
    private int base = 24;

    /**
     * 每个回退模型配置文件分配的额外重试迭代次数。
     * 链中的每个回退模型获得此数量的额外尝试。
     */
    private int perProfile = 8;

    /**
     * 总重试迭代次数的最小下限。
     * 即使 base+perProfile*count 计算值更低，此下限也适用。
     */
    private int min = 32;

    /**
     * 总重试迭代次数的最大上限。
     * 防止无限制的重试循环。
     */
    private int max = 160;

    /**
     * 重试终止的质量阈值。
     * 如果反思分数达到或超过此阈值，重试提前停止。
     */
    private double qualityThreshold = 0.7;

    /**
     * 当需要重试时选择下一个模型的策略。
     * <ul>
     *   <li>SAME_MODEL — 使用相同模型重试（默认）</li>
     *   <li>FALLBACK_CHAIN — 尝试回退链中的下一个模型</li>
     *   <li>ADAPTIVE — 在 3 次连续的相同模型失败后切换到回退模型</li>
     * </ul>
     */
    private RetryStrategy defaultStrategy = RetryStrategy.ADAPTIVE;

    /**
     * 在升级到回退模型之前允许的最大连续失败次数
     * （仅在策略为 ADAPTIVE 时适用）。
     */
    private int maxConsecutiveFailuresBeforeFallback = 3;

    /**
     * 重试延迟的指数退避配置。
     */
    private RetryBackoff backoff = new RetryBackoff();

    // 省略 getter 和 setter

    public enum RetryStrategy { SAME_MODEL, FALLBACK_CHAIN, ADAPTIVE }

    /**
     * 重试延迟的指数退避。
     */
    public static class RetryBackoff {
        /** 初始延迟（毫秒）。 */
        private long initialDelayMs = 500;
        /** 最大延迟（毫秒）。 */
        private long maxDelayMs = 30_000;
        /** 退避乘数（例如 2.0 = 每次重试翻倍）。 */
        private double multiplier = 2.0;
        /** 退避适用于：BOTH = 模型调用 + 反思，LLM_ONLY、REFLECTION_ONLY */
        private BackoffTarget target = BackoffTarget.BOTH;

        // 省略 getter 和 setter
        public enum BackoffTarget { BOTH, LLM_ONLY, REFLECTION_ONLY }
    }
}
```

### 4.4.3 RunRetryManager

```java
package lyjew.com.lyclaw.react.retry;

import lyjew.com.lyclaw.config.RunRetriesConfig;
import lyjew.com.lyclaw.react.ReflexionResult;
import lyjew.com.lyclaw.task.ReflectionFeedback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理 ReAct 循环反思重试的重试预算、跟踪和策略。
 *
 * <p>用可配置的、模型感知的重试系统替换硬编码的 MAX_REFLECTION_RETRIES=2，
 * 支持回退链和自适应策略选择。
 *
 * <h3>重试预算公式：</h3>
 * <pre>
 *   totalRetries = max(min, min(max, base + perProfile * fallbackProfileCount))
 * </pre>
 *
 * <h3>重试状态机：</h3>
 * <pre>
 *   [使用模型 M 执行]
 *        |
 *        v
 *   [反思] ──score >= threshold──> [完成]
 *        |
 *   score < threshold
 *        |
 *        v
 *   [检查重试预算] ──已耗尽──> [以最佳结果完成]
 *        |
 *   预算可用
 *        |
 *        v
 *   [选择策略：相同模型 / 回退]
 *        |
 *        v
 *   [计划修订] ──> [使用（新）模型 M' 执行]
 * </pre>
 */
public class RunRetryManager {

    private static final Logger log = LoggerFactory.getLogger(RunRetryManager.class);

    private final RunRetriesConfig config;
    private final List<String> fallbackProfiles;
    private final int maxRetries;

    // 每个会话的重试历史
    private final Map<String, RetrySession> sessions = new ConcurrentHashMap<>();

    public RunRetryManager(RunRetriesConfig config, List<String> fallbackProfiles) {
        this.config = config;
        this.fallbackProfiles = fallbackProfiles != null ? fallbackProfiles : List.of();
        this.maxRetries = calculateMaxRetries(config, this.fallbackProfiles.size());
    }

    /**
     * 计算总重试预算。
     */
    private int calculateMaxRetries(RunRetriesConfig config, int fallbackCount) {
        int total = config.getBase() + config.getPerProfile() * fallbackCount;
        return Math.max(config.getMin(), Math.min(config.getMax(), total));
    }

    /**
     * 获取会话的最大重试次数。
     */
    public int getMaxRetries(String sessionId) {
        return maxRetries;
    }

    /**
     * 检查给定会话是否有更多重试可用。
     *
     * @param sessionId 要检查的会话
     * @return 如果至少还有一次重试预算则返回 true
     */
    public boolean canRetry(String sessionId) {
        RetrySession session = sessions.get(sessionId);
        if (session == null) {
            return maxRetries > 0;
        }
        return session.getAttemptCount() < maxRetries;
    }

    /**
     * 记录会话的重试尝试。
     *
     * @param sessionId 会话标识符
     * @param attempt   已完成的重试尝试
     */
    public void recordRetry(String sessionId, RetryAttempt attempt) {
        RetrySession session = sessions.computeIfAbsent(sessionId, RetrySession::new);
        session.addAttempt(attempt);
        log.debug("重试已记录：session={} attempt={}/{} score={} model={}",
                sessionId, session.getAttemptCount(), maxRetries,
                attempt.getQualityScore(), attempt.getModelUsed());
    }

    /**
     * 根据历史确定重试策略。
     *
     * @param sessionId    会话标识符
     * @param primaryModel 主模型名称
     * @return 下一次尝试要使用的模型
     */
    public String determineNextModel(String sessionId, String primaryModel) {
        RetrySession session = sessions.get(sessionId);
        if (session == null || session.getAttemptCount() == 0) {
            return primaryModel;
        }

        RunRetriesConfig.RetryStrategy strategy = config.getDefaultStrategy();

        switch (strategy) {
            case SAME_MODEL:
                return primaryModel;

            case FALLBACK_CHAIN: {
                // 每次重试轮换回退模型
                int attemptIndex = session.getAttemptCount();
                if (attemptIndex < fallbackProfiles.size()) {
                    return fallbackProfiles.get(attemptIndex);
                }
                // 循环回退模型
                return fallbackProfiles.get(attemptIndex % fallbackProfiles.size());
            }

            case ADAPTIVE:
            default: {
                // 检查当前模型的连续失败次数
                int consecutiveFailures = session.countConsecutiveFailuresWithCurrentModel();
                if (consecutiveFailures >= config.getMaxConsecutiveFailuresBeforeFallback()) {
                    // 切换到下一个回退模型
                    int fallbackIndex = session.getCurrentFallbackIndex();
                    if (fallbackIndex < fallbackProfiles.size()) {
                        session.incrementFallbackIndex();
                        String fallback = fallbackProfiles.get(fallbackIndex);
                        log.info("自适应重试切换到回退模型：{} -> {}（连续失败 {} 次）",
                                session.getCurrentModel(), fallback, consecutiveFailures);
                        return fallback;
                    }
                    // 所有回退模型已用尽，继续使用主模型
                    return primaryModel;
                }
                return session.getCurrentModel() != null ? session.getCurrentModel() : primaryModel;
            }
        }
    }

    /**
     * 计算下一次重试的指数退避延迟。
     */
    public long calculateBackoffMs(String sessionId) {
        RetrySession session = sessions.get(sessionId);
        int attemptCount = session != null ? session.getAttemptCount() : 0;

        RunRetriesConfig.RetryBackoff backoff = config.getBackoff();
        long delay = (long)(backoff.getInitialDelayMs() *
                Math.pow(backoff.getMultiplier(), attemptCount));
        return Math.min(delay, backoff.getMaxDelayMs());
    }

    /**
     * 清除会话的重试状态（在会话完成/重置时调用）。
     */
    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
    }

    /**
     * 获取监控用的重试统计信息。
     */
    public RetryStats getStats(String sessionId) {
        RetrySession session = sessions.get(sessionId);
        if (session == null) {
            return new RetryStats(0, 0, maxRetries, 0.0, 0.0);
        }
        return session.computeStats(maxRetries);
    }

    // ── 内部类型 ────────────────────────────────────────────────────

    /**
     * 每个会话的重试跟踪。
     */
    static class RetrySession {
        private final String sessionId;
        private final List<RetryAttempt> attempts = new ArrayList<>();
        private volatile int currentFallbackIndex = 0;
        private volatile String currentModel;

        RetrySession(String sessionId) { this.sessionId = sessionId; }

        void addAttempt(RetryAttempt attempt) {
            attempts.add(attempt);
            this.currentModel = attempt.getModelUsed();
        }

        int getAttemptCount() { return attempts.size(); }

        String getCurrentModel() { return currentModel; }

        int getCurrentFallbackIndex() { return currentFallbackIndex; }

        void incrementFallbackIndex() { currentFallbackIndex++; }

        int countConsecutiveFailuresWithCurrentModel() {
            int count = 0;
            for (int i = attempts.size() - 1; i >= 0; i--) {
                RetryAttempt a = attempts.get(i);
                if (currentModel != null && currentModel.equals(a.getModelUsed())
                        && a.getQualityScore() < 0.7) {
                    count++;
                } else {
                    break;
                }
            }
            return count;
        }

        RetryStats computeStats(int maxRetries) {
            if (attempts.isEmpty()) {
                return new RetryStats(0, 0, maxRetries, 0.0, 0.0);
            }
            double avgScore = attempts.stream().mapToDouble(RetryAttempt::getQualityScore).average().orElse(0.0);
            double bestScore = attempts.stream().mapToDouble(RetryAttempt::getQualityScore).max().orElse(0.0);
            return new RetryStats(attempts.size(), attempts.size(), maxRetries, avgScore, bestScore);
        }
    }

    /**
     * 单次重试尝试记录。
     */
    public static class RetryAttempt {
        private final int attemptNumber;
        private final String modelUsed;
        private final double qualityScore;
        private final List<String> errors;
        private final String suggestedStrategy;
        private final long elapsedMs;
        private final Instant timestamp;

        public RetryAttempt(int attemptNumber, String modelUsed, double qualityScore,
                            List<String> errors, String suggestedStrategy, long elapsedMs) {
            this.attemptNumber = attemptNumber;
            this.modelUsed = modelUsed;
            this.qualityScore = qualityScore;
            this.errors = errors != null ? errors : List.of();
            this.suggestedStrategy = suggestedStrategy;
            this.elapsedMs = elapsedMs;
            this.timestamp = Instant.now();
        }

        public static RetryAttempt fromReflexionResult(ReflexionResult.Attempt attempt,
                                                        String modelUsed) {
            ReflectionFeedback fb = attempt.getFeedback();
            return new RetryAttempt(
                    attempt.getAttemptNumber(),
                    modelUsed,
                    attempt.getQualityScore(),
                    fb != null ? fb.getDetectedErrors() : List.of(),
                    fb != null ? fb.getSuggestedStrategy() : null,
                    0 // elapsedMs 单独跟踪
            );
        }

        public int getAttemptNumber() { return attemptNumber; }
        public String getModelUsed() { return modelUsed; }
        public double getQualityScore() { return qualityScore; }
        public List<String> getErrors() { return errors; }
        public String getSuggestedStrategy() { return suggestedStrategy; }
        public long getElapsedMs() { return elapsedMs; }
        public Instant getTimestamp() { return timestamp; }
    }

    /**
     * 监控仪表盘用的重试统计快照。
     */
    public static class RetryStats {
        private final int attemptsUsed;
        private final int attemptsTotal;
        private final int budget;
        private final double avgQualityScore;
        private final double bestQualityScore;

        public RetryStats(int attemptsUsed, int attemptsTotal, int budget,
                          double avgQualityScore, double bestQualityScore) {
            this.attemptsUsed = attemptsUsed;
            this.attemptsTotal = attemptsTotal;
            this.budget = budget;
            this.avgQualityScore = avgQualityScore;
            this.bestQualityScore = bestQualityScore;
        }

        public int getAttemptsUsed() { return attemptsUsed; }
        public int getAttemptsTotal() { return attemptsTotal; }
        public int getBudget() { return budget; }
        public double getAvgQualityScore() { return avgQualityScore; }
        public double getBestQualityScore() { return bestQualityScore; }
        public int getRemainingBudget() { return budget - attemptsUsed; }
    }
}
```

### 4.4.4 与 AgentContext 的集成

扩展 `AgentContext` 以携带重试元数据：

```java
// AgentContext 新增内容：

/** 用于重试跟踪的运行元数据。存储在 attributes 中以保持可序列化。 */
public Map<String, Object> getRunMetadata() {
    @SuppressWarnings("unchecked")
    Map<String, Object> meta = (Map<String, Object>) getAttribute("runMetadata");
    if (meta == null) {
        meta = new HashMap<>();
        setAttribute("runMetadata", meta);
    }
    return meta;
}

public void recordRetryState(String modelUsed, double score, List<String> errors) {
    Map<String, Object> meta = getRunMetadata();
    meta.put("lastModelUsed", modelUsed);
    meta.put("lastScore", score);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> history = (List<Map<String, Object>>)
            meta.computeIfAbsent("retryHistory", k -> new ArrayList<>());
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("model", modelUsed);
    entry.put("score", score);
    entry.put("errors", errors);
    entry.put("timestamp", Instant.now().toString());
    history.add(entry);
}
```

### 4.4.5 与 ReflexionLoop 的集成

现有的 `ReflexionLoop` 增强为使用 `RunRetryManager`：

```java
// 增强后的 ReflexionLoop（与当前版本的差异）：
//
// 之前：
//   public ReflexionLoop(ReflectionEngine engine, TaskPlanner planner,
//                         int maxRetries, double qualityThreshold) { ... }
//
// 之后：
//   public class ReflexionLoop {
//       private final RunRetryManager retryManager;
//       private final String primaryModel;
//       ...
//
//       public ReflexionLoop(ReflectionEngine engine, TaskPlanner planner,
//                             RunRetryManager retryManager, String primaryModel) {
//           this.reflectionEngine = engine;
//           this.taskPlanner = planner;
//           this.retryManager = retryManager;
//           this.primaryModel = primaryModel;
//       }
//
//       public ReflexionResult execute(TaskPlan plan, ChatContext context,
//                                       StepExecutor executor) {
//           List<ReflexionResult.Attempt> attempts = new ArrayList<>();
//           TaskPlan currentPlan = plan;
//           String loopId = UUID.randomUUID().toString().substring(0, 8);
//           long startTime = System.currentTimeMillis();
//           String currentModel = primaryModel;
//
//           int attempt = 0;
//           while (retryManager.canRetry(context.getSessionId())) {
//               log.info("[Reflexion {}] 尝试 {}/{} model={}", loopId,
//                       attempt + 1, retryManager.getMaxRetries(context.getSessionId()), currentModel);
//
//               // 使用当前模型执行
//               ActionResult result = executePlan(currentPlan, executor);
//
//               // 反思
//               double score = reflect(context, result);
//
//               // 记录重试
//               retryManager.recordRetry(context.getSessionId(),
//                       new RunRetryManager.RetryAttempt(attempt, currentModel, score,
//                               extractErrors(result), null, 0));
//
//               attempts.add(new ReflexionResult.Attempt(attempt, result, score, buildFeedback(result)));
//
//               // 检查质量阈值
//               if (score >= qualityThreshold) break;
//
//               // 确定下一个模型
//               currentModel = retryManager.determineNextModel(
//                       context.getSessionId(), primaryModel);
//
//               // 应用退避
//               long backoffMs = retryManager.calculateBackoffMs(context.getSessionId());
//               if (backoffMs > 0) Thread.sleep(backoffMs);
//
//               // 修订计划
//               currentPlan = taskPlanner.revise(currentPlan, buildFeedback(result));
//               attempt++;
//           }
//
//           long totalMs = System.currentTimeMillis() - startTime;
//           return new ReflexionResult(loopId, attempts, totalMs);
//       }
//   }
```

### 4.4.6 YAML 配置

```yaml
# application.yml — 重试配置
lyclaw:
  retry:
    base: 24
    per-profile: 8
    min: 32
    max: 160
    quality-threshold: 0.7
    default-strategy: ADAPTIVE            # SAME_MODEL | FALLBACK_CHAIN | ADAPTIVE
    max-consecutive-failures-before-fallback: 3
    backoff:
      initial-delay-ms: 500
      max-delay-ms: 30000
      multiplier: 2.0
      target: BOTH                       # BOTH | LLM_ONLY | REFLECTION_ONLY
```

---

## 集成架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          第四阶段 — 系统架构                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────┐    ┌──────────────────┐    ┌───────────────────────────┐  │
│  │ 用户请求     │───>│  管道阶段         │───>│  SSE 事件流               │  │
│  │ (HTTP/MQTT)  │    │                   │    │  (到 Web/App 客户端)      │  │
│  └─────────────┘    │  ContextBuild     │    └───────────────────────────┘  │
│                      │  SecurityCheck    │              ▲                    │
│                      │  PlanExecution    │              │                    │
│                      │  RespondStage ◄───┼──────────────┘                    │
│                      │    │              │    BlockStreamingController       │
│                      │    │              │    TypingIndicatorController      │
│                      │    │              │    HumanDelay                     │
│                      │  ReflectionStage  │                                   │
│                      │  MetricsStage     │                                   │
│                      └──────────────────┘                                   │
│                              │                                              │
│              ┌───────────────┼──────────────────┐                          │
│              │               │                  │                          │
│              v               v                  v                          │
│  ┌─────────────────┐ ┌────────────┐ ┌───────────────────┐                 │
│  │  ReActEngine     │ │ SandboxHook│ │  HeartbeatScheduler│                │
│  │  (流式)           │ │            │ │                    │                 │
│  │                  │ │ SandboxExe-│ │  Cron: 每 30 分钟   │                 │
│  │  BlockStreaming  │ │ cutionSvc  │ │  活跃时间检查       │                 │
│  │  Coalesce        │ │            │ │  轻量上下文         │                 │
│  │  HumanDelay      │ │ Docker/Pod-│ │  隔离会话           │                 │
│  │  TypingIndicator │ │ man 后端   │ │  忙时跳过           │                 │
│  └────────┬─────────┘ └─────┬──────┘ └─────────┬─────────┘                 │
│           │                 │                   │                           │
│           v                 v                   v                           │
│  ┌──────────────────────────────────────────────────┐                      │
│  │               RunRetryManager                     │                      │
│  │                                                   │                      │
│  │  重试预算：base + perProfile * fallbackCount       │                      │
│  │  策略：ADAPTIVE / FALLBACK_CHAIN / SAME_MODEL     │                      │
│  │  退避：指数级，具有可配置的上限                     │                      │
│  │  会话跟踪：每个会话的重试历史                       │                      │
│  └──────────────────────────────────────────────────┘                      │
│                                                                             │
│  ┌─────────────────────── 事件总线 ───────────────────────┐               │
│  │                                                         │               │
│  │  heartbeat_start   heartbeat_thinking  heartbeat_reply  │               │
│  │  heartbeat_complete  heartbeat_error  heartbeat_delivery│               │
│  │                                                         │               │
│  │  retry_attempt    retry_exhausted    retry_fallback     │               │
│  │                                                         │               │
│  │  sandbox_created  sandbox_destroyed  sandbox_health     │               │
│  └─────────────────────────────────────────────────────────┘               │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 数据流：流式管道

```
LLM Token Stream (Flux<ModelResponse>)
     │
     ▼
┌──────────────────┐
│ 状态机            │  0=缓冲(思考中), 1=中继(流式token), 2=检测到工具
│ (DefaultReAct    │
│  Engine)         │
└────────┬─────────┘
         │  情况 1：state=1（纯文本流）
         │    → token 作为细粒度 SSE "message" 事件发出
         │
         │  情况 2：state=2（检测到工具）
         │    → 工具执行，然后是最终文本响应
         │    → 最终文本传递给 BlockStreamingController
         │
         ▼
┌──────────────────┐
│ BlockStreaming   │  segmentIntoBlocks() → coalesceBlocks() → suppressRepeats()
│ Controller       │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ HumanDelay       │  calculateDelay(block) → adaptiveSpeed → jitter
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ TypingIndicator  │  在间隙期间按间隔发出 "typing" SSE 事件
└────────┬─────────┘
         │
         ▼
    SSE 客户端
```

### 沙箱执行流程

```
    工具调用请求
         │
         ▼
    SandboxHook.wrapToolExecutor()
         │
         ▼
    ctx.getSandboxLevel() == PROCESS && backend != NONE ?
         │
    ┌────┴────┐
    │   是    │               │   否    │
    ▼         ▼               ▼         ▼
┌─────────────────┐   ┌──────────────────┐
│ SandboxExecSvc  │   │ ToolSandbox       │
│                 │   │ （旧版 DIRECT/     │
│ createSandbox() │   │  SANDBOX 模式）   │
│ 如果不存在      │   └──────────────────┘
│                 │
│ executeInSandbox│
│                 │
│ docker exec     │
│ cmd [bash -c]   │
│                 │
│ 捕获 stdout     │
│ 检查退出码      │
└────────┬────────┘
         │
         ▼
    ToolExecutionResult
```

---

## SSE 事件模式参考

### 块流式事件

| 事件名称 | `event:` | `data:` 模式 |
|---|---|---|
| message（块） | `message` | `{"type":"message","content":"块文本..."}` |
| typing | `typing` | `{"type":"typing","agentId":"...","stage":"RESPOND"}` |

### 沙箱事件

| 事件名称 | `event:` | `data:` 模式 |
|---|---|---|
| sandbox_created | `sandbox_created` | `{"containerId":"...","sessionId":"...","image":"..."}` |
| sandbox_executing | `sandbox_executing` | `{"toolName":"...","containerId":"..."}` |
| sandbox_result | `sandbox_result` | `{"toolName":"...","exitCode":0,"stdout":"..."}` |
| sandbox_destroyed | `sandbox_destroyed` | `{"containerId":"..."}` |

### 心跳事件

| 事件名称 | `event:` | `data:` 模式 |
|---|---|---|
| heartbeat_start | `heartbeat_start` | `{"agentId":"...","sessionKey":"...","timestamp":"..."}` |
| heartbeat_thinking | `heartbeat_thinking` | `{"agentId":"..."}` |
| heartbeat_reply | `heartbeat_reply` | `{"agentId":"...","message":"..."}` |
| heartbeat_complete | `heartbeat_complete` | `{"agentId":"...","elapsedMs":1234,"message":"预览..."}` |
| heartbeat_error | `heartbeat_error` | `{"agentId":"...","error":"..."}` |

### 重试事件

| 事件名称 | `event:` | `data:` 模式 |
|---|---|---|
| retry_attempt | `retry_attempt` | `{"sessionId":"...","attempt":3,"model":"gpt-4","score":0.45}` |
| retry_fallback | `retry_fallback` | `{"sessionId":"...","fromModel":"gpt-4","toModel":"gpt-4o-mini"}` |
| retry_exhausted | `retry_exhausted` | `{"sessionId":"...","totalAttempts":32,"bestScore":0.68}` |

---

## 变更摘要

### 新增文件（Java）

| 文件 | 包 | 描述 |
|---|---|---|
| `BlockStreamingConfig.java` | `lyjew.com.lyclaw.config` | 块流式配置 POJO |
| `BlockStreamingChunk.java` | `lyjew.com.lyclaw.config` | 软分块配置 |
| `BlockStreamingCoalesce.java` | `lyjew.com.lyclaw.config` | 合并配置 |
| `HumanDelayConfig.java` | `lyjew.com.lyclaw.config` | 人类输入延迟配置 |
| `BlockStreamingController.java` | `lyjew.com.lyclaw.react.stream` | 基于块的流式管道 |
| `TypingIndicatorController.java` | `lyjew.com.lyclaw.react.stream` | 输入中指示器 SSE 发送器 |
| `AgentSandboxConfig.java` | `lyjew.com.lyclaw.config` | 容器沙箱配置 |
| `SandboxExecutionService.java` | `lyjew.com.lyclaw.security.sandbox` | Docker/Podman 沙箱服务 |
| `SandboxHandle.java` | `lyjew.com.lyclaw.security.sandbox` | 沙箱容器句柄 |
| `HeartbeatConfig.java` | `lyjew.com.lyclaw.config` | 心跳配置 POJO |
| `HeartbeatScheduler.java` | `lyjew.com.lyclaw.react.heartbeat` | 基于 Cron 的心跳执行器 |
| `HeartbeatEvent.java` | `lyjew.com.lyclaw.react.heartbeat` | 心跳事件类型 |
| `RunRetriesConfig.java` | `lyjew.com.lyclaw.config` | 重试预算配置 |
| `RunRetryManager.java` | `lyjew.com.lyclaw.react.retry` | 带回退链的重试管理器 |

### 修改文件（Java）

| 文件 | 变更 |
|---|---|
| `AgentContext.java` | 添加 `SandboxHandle sandboxHandle`、`Map<String,Object> runMetadata`、`recordRetryState()` |
| `SandboxHook.java` | 当配置容器后端时集成 `SandboxExecutionService` 用于 `PROCESS` 级别 |
| `DefaultReActEngine.java` | 用 `BlockStreamingController.streamResponse()` 替换 `splitIntoEvents()` |
| `RespondStage.java` | 集成 `BlockStreamingController`、`TypingIndicatorController`、`HumanDelayConfig` |
| `ReflexionLoop.java` | 用 `RunRetryManager` 替换硬编码的 `maxRetries`，添加模型轮换 |

### 配置键（application.yml）

| 前缀 | 键 |
|---|---|
| `lyclaw.streaming.block` | enabled、break-mode、chunk.*、coalesce.*、max-chunk-chars、repeat-suppression、delivery-mode、hidden-boundary |
| `lyclaw.streaming.human-delay` | enabled、min-delay-ms、max-delay-ms、chars-per-second、adaptive-speed、long-reply-threshold |
| `lyclaw.streaming.typing-indicator` | mode、interval-seconds |
| `lyclaw.sandbox` | backend、image、root-dir、allowed-commands、denied-commands、network-enabled、file-system-write-enabled、memory-limit-mb、cpu-limit、timeout-seconds、fs-bridge.* |
| `lyclaw.heartbeat` | enabled、every、active-hours.*、model、session-key、target、direct-policy、to、account-id、prompt、include-system-prompt-section、ack-max-chars、suppress-tool-error-warnings、timeout-seconds、light-context、isolated-session、skip-when-busy、include-reasoning |
| `lyclaw.retry` | base、per-profile、min、max、quality-threshold、default-strategy、max-consecutive-failures-before-fallback、backoff.* |

---

# LyClaw 代理平台 — 改造后架构蓝图

> **状态：** 目标架构  
> **版本：** 2.0.0  
> **日期：** 2026-05-20  
> **范围：** 代理系统全面重新设计 — 传输、路由、运行时、共享服务、插件SDK、SSE流式输出以及子代理委托。

---

## 目录

1. [完整代理系统架构（改造后）](#1-完整代理系统架构改造后)
2. [代理生命周期流程（改造后）](#2-代理生命周期流程改造后)
3. [配置解析层级](#3-配置解析层级)
4. [子代理委托树](#4-子代理委托树)
5. [SSE事件流（完整）](#5-sse事件流完整)
6. [组件清单与职责](#6-组件清单与职责)
7. [关键设计决策](#7-关键设计决策)
8. [从当前架构的迁移路径](#8-从当前架构的迁移路径)

---

## 1. 完整代理系统架构（改造后）

此图展示了改造后 LyClaw 平台的每个主要子系统，按水平层（传输层、路由器、配置、运行时、共享服务、插件SDK）和垂直关注点（安全、可观测性、持久化）进行组织。

```
┌────────────────────────────────────────────────────────────────────────────────────────────┐
│                                     LyClaw 代理平台                                          │
│                              ─────────────────────────────────────                             │
│                                                                                                │
│  ┌──────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                                      传输层                                                │ │
│  │                                                                                            │ │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────────────┐  │ │
│  │  │    REST / SSE    │  │   WebSocket     │  │    WebChat      │  │    频道插件           │  │ │
│  │  │   (HTTP/1.1)     │  │    (WS/WSS)     │  │   (React UI)    │  │                      │  │ │
│  │  │                  │  │                 │  │                 │  │  ┌────────────────┐   │  │ │
│  │  │  POST /chat      │  │  ws://host/ws   │  │  内嵌           │  │  │ Telegram机器人 │   │  │ │
│  │  │  GET  /sse/stream│  │                 │  │  WebChat界面    │  │  │  (长轮询)      │   │  │ │
│  │  │  POST /agent/:id │  │  双向           │  │                 │  │  └────────────────┘   │  │ │
│  │  │                  │  │  持久连接       │  │  通过Spring Boot│  │  ┌────────────────┐   │  │ │
│  │  │  JSON请求        │  │                 │  │  提供静态资源   │  │  │ Discord机器人  │   │  │ │
│  │  │  → SSE响应       │  │                 │  │                 │  │  │  (Gateway)     │   │  │ │
│  │  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘  │  └────────────────┘   │  │ │
│  │           │                    │                    │           │  ┌────────────────┐   │  │ │
│  │           │                    │                    │           │  │ Slack机器人    │   │  │ │
│  │           │                    │                    │           │  │  (Events API)  │   │  │ │
│  │           │                    │                    │           │  └────────────────┘   │  │ │
│  │           │                    │                    │           │  ┌────────────────┐   │  │ │
│  │           │                    │                    │           │  │ 企业微信       │   │  │ │
│  │           │                    │                    │           │  │  (回调)        │   │  │ │
│  │           │                    │                    │           │  └────────────────┘   │  │ │
│  │           │                    │                    │           └──────────┬───────────┘  │ │
│  └───────────┼────────────────────┼────────────────────┼──────────────────────┼──────────────┘ │
│              │                    │                    │                      │                │
│              │              标准化内部消息 (AgentMessage)                       │                │
│              │                    │                    │                      │                │
│  ┌───────────┴────────────────────┴────────────────────┴──────────────────────┴──────────────┐ │
│  │                                      代理路由器                                             │ │
│  │                                                                                            │ │
│  │  ┌────────────────────────────────────────────────────────────────────────────────────┐   │ │
│  │  │                              路由绑定注册表                                           │   │ │
│  │  │                                                                                     │   │ │
│  │  │  ┌───────────────────────┐  ┌───────────────────────┐  ┌─────────────────────────┐  │   │ │
│  │  │  │  频道匹配              │  │  Acp绑定               │  │  提及匹配                 │  │   │ │
│  │  │  │                       │  │                        │  │                          │  │   │ │
│  │  │  │  #general  → agent1   │  │  acp:*     → codex    │  │  @bot chat → agent2      │  │   │ │
│  │  │  │  #code     → agent2   │  │  acp:cli   → claude   │  │  @bot code → code-review │  │   │ │
│  │  │  │  dm:*      → agent3   │  │  acp:gpt5  → gpt-5    │  │  @bot help → help-agent  │  │   │ │
│  │  │  │                       │  │                        │  │                          │  │   │ │
│  │  │  │  匹配优先级：          │  │  路由到外部              │  │  对消息内容进行          │  │   │ │
│  │  │  │  1. 精确频道匹配       │  │  ACP提供商后端          │  │  正则/通配符匹配         │  │   │ │
│  │  │  │  2. 通配符模式         │  │                        │  │                          │  │   │ │
│  │  │  │  3. 默认路由           │  │                        │  │                          │  │   │ │
│  │  │  └───────────────────────┘  └───────────────────────┘  └─────────────────────────┘  │   │ │
│  │  └────────────────────────────────────────────────────────────────────────────────────┘   │ │
│  │                                                                                            │ │
│  │  解析管线：   TransportCtx → RouteBinding.match() → ResolvedRoute(agentId,ctx)             │ │
│  └────────────────────────────────────────────────────────────────────────────────────────────┘ │
│              │                                                                                  │
│  ┌───────────┴──────────────────────────────────────────────────────────────────────────────┐ │
│  │                                   代理配置解析器                                            │ │
│  │                                                                                            │ │
│  │  system.defaults ────► agent.defaults ────► @Agent注解 ────► 运行时覆盖                    │ │
│  │  (application.yml)     (lyclaw.agent.*)     (ChatAgent.java)   (ChatRequest请求体)          │ │
│  │        │                      │                     │                       │              │ │
│  │        └──────────────────────┴─────────────────────┴───────────────────────┘              │ │
│  │                                      │                                                     │ │
│  │                                      ▼                                                     │ │
│  │                           ResolvedAgentConfig                                               │ │
│  │                    (不可变、线程安全的快照)                                                  │ │
│  └────────────────────────────────────────────────────────────────────────────────────────────┘ │
│              │                                                                                  │
│  ┌───────────┴──────────────────────────────────────────────────────────────────────────────┐ │
│  │                                代理运行时（每个代理）                                       │ │
│  │                                                                                            │ │
│  │  ┌─────────────────────────────────────────┐    ┌──────────────────────────────────────┐  │ │
│  │  │          内嵌运行时                      │    │           ACP 运行时                  │  │ │
│  │  │                                         │    │                                      │  │ │
│  │  │  ┌─────────────────────────────────┐    │    │  ┌────────────────────────────────┐   │  │ │
│  │  │  │        引导加载器               │    │    │  │       AcpRuntime              │   │  │ │
│  │  │  │                                 │    │    │  │                                │   │  │ │
│  │  │  │  AGENTS.md       (角色/能力)    │    │    │  │  ensureSession(agentId)        │   │  │ │
│  │  │  │  SOUL.md         (个性)         │    │    │  │  startTurn(messages, tools)    │   │  │ │
│  │  │  │  BOOTSTRAP.md    (指令)         │    │    │  │  cancel() / close()            │   │  │ │
│  │  │  │  IDENTITY.md     (我是谁)       │    │    │  │  doctor() → 健康检查           │   │  │ │
│  │  │  │  USER.md         (关于用户)     │    │    │  │                                │   │  │ │
│  │  │  │  HEARTBEAT.md    (后台)         │    │    │  └────────────────────────────────┘   │  │ │
│  │  │  │                                 │    │    │                                      │  │ │
│  │  │  │  加载 + 验证 + 缓存             │    │    │  外部LLM后端：                        │  │ │
│  │  │  └───────────────┬─────────────────┘    │    │  ┌──────────┐ ┌──────────┐          │  │ │
│  │  │                  │                      │    │  │  Codex   │ │  Claude  │          │  │ │
│  │  │  ┌───────────────┴─────────────────┐    │    │  │  (CLI)   │ │  (API)   │          │  │ │
│  │  │  │         上下文引擎              │    │    │  └──────────┘ └──────────┘          │  │ │
│  │  │  │                                 │    │    │  ┌──────────┐ ┌──────────┐          │  │ │
│  │  │  │  assemble(messages, bootstrap)  │    │    │  │  GPT-5   │ │  Gemini  │          │  │ │
│  │  │  │    → 构建系统提示词             │    │    │  │  (API)   │ │  (API)   │          │  │ │
│  │  │  │    → 注入工具定义               │    │    │  └──────────┘ └──────────┘          │  │ │
│  │  │  │    → 应用上下文窗口限制         │    │    │                                      │  │ │
│  │  │  │  compact(transcript)            │    │    └──────────────────────────────────────┘  │ │
│  │  │  │    → 总结旧轮次                 │    │                                               │ │
│  │  │  │    → 截断至token预算            │    │    ┌──────────────────────────────────────┐  │ │
│  │  │  │  prune(results, ttl)            │    │    │       心跳调度器                      │  │ │
│  │  │  │    → 移除过期的工具结果         │    │    │                                      │  │ │
│  │  │  └───────────────┬─────────────────┘    │    │  ┌────────────────┐ ┌──────────────┐ │  │ │
│  │  │                  │                      │    │  │  定时触发器    │ │ 空闲检测器   │ │  │ │
│  │  │  ┌───────────────┴─────────────────┐    │    │  │                │ │              │ │  │ │
│  │  │  │       36钩子生命周期            │    │    │  │  "0 */2 * * *" │ │ 无子代理     │ │  │ │
│  │  │  │          管线                   │    │    │  │  每2小时       │ │ + 在活跃     │ │  │ │
│  │  │  │                                 │    │    │  │                │ │   时间段内   │ │  │ │
│  │  │  │  message_received              │    │    │  └────────────────┘ └──────────────┘ │  │ │
│  │  │  │  before_agent_run              │    │    └──────────────────────────────────────┘  │ │
│  │  │  │  before_prompt_build            │    │                                               │ │
│  │  │  │  agent_turn_prepare             │    │    ┌──────────────────────────────────────┐  │ │
│  │  │  │  before_model_resolve           │    │    │        子代理生成器                   │  │ │
│  │  │  │  model_call_started             │    │    │                                      │  │ │
│  │  │  │  llm_input                      │    │    │  spawn(parentRun, childAgentId,      │  │ │
│  │  │  │  llm_output                     │    │    │         task, config)                │  │ │
│  │  │  │  before_tool_call               │    │    │    → 创建子ReActEngine                │  │ │
│  │  │  │  after_tool_call                │    │    │    → 完整的独立循环                  │  │ │
│  │  │  │  tool_result_persist            │    │    │    → 将结果返回给父代理              │  │ │
│  │  │  │  subagent_spawning              │    │    │                                      │  │ │
│  │  │  │  subagent_delivery_target       │    │    │  限制：                              │  │ │
│  │  │  │  subagent_spawned               │    │    │    maxSpawnDepth (默认 1)            │  │ │
│  │  │  │  subagent_ended                 │    │    │    maxConcurrent (默认 2)            │  │ │
│  │  │  │  before_compaction              │    │    │    maxChildrenPerAgent (默认 5)      │  │ │
│  │  │  │  after_compaction               │    │    └──────────────────────────────────────┘  │ │
│  │  │  │  model_call_ended               │    │                                               │ │
│  │  │  │  before_agent_finalize          │    │    ┌──────────────────────────────────────┐  │ │
│  │  │  │  before_agent_reply             │    │    │    沙箱 (Docker / Podman)            │  │ │
│  │  │  │  agent_end                      │    │    │                                      │  │ │
│  │  │  │  message_sending               │    │    │  每个代理容器隔离                      │  │ │
│  │  │  │  message_sent                   │    │    │  文件系统桥接 (bind mount)           │  │ │
│  │  │  │  session_end                    │    │    │  网络：无 / 受限                     │  │ │
│  │  │  │  heartbeat_prompt_contribution   │    │    │  资源限制 (CPU, 内存)                │  │ │
│  │  │  │                                 │    │    │  生命周期：创建 → 执行 → 销毁       │  │ │
│  │  │  │  (另有14个钩子点)               │    │    └──────────────────────────────────────┘  │ │
│  │  │  └───────────────┬─────────────────┘    │                                               │ │
│  │  │                  │                      │    ┌──────────────────────────────────────┐  │ │
│  │  │  ┌───────────────┴─────────────────┐    │    │        块流式输出                     │  │ │
│  │  │  │         ReAct 引擎              │    │    │                                      │  │ │
│  │  │  │                                 │    │    │  合并文本块（防抖）                   │  │ │
│  │  │  │  execute(messages, config)      │    │    │  模拟人类延迟                         │  │ │
│  │  │  │    → 单轮（无工具）             │    │    │  输入中指示器（SSE事件）              │  │ │
│  │  │  │  executeStream(messages,config) │    │    │  流式输出到 SSE / WebSocket           │  │ │
│  │  │  │    → SSE流式响应                │    │    └──────────────────────────────────────┘  │ │
│  │  │  │  multiRound(messages, config)   │    │                                               │ │
│  │  │  │    → 完整ReAct含工具调用        │    │    ┌──────────────────────────────────────┐  │ │
│  │  │  │                                 │    │    │       SSE / WS 流式输出到             │  │ │
│  │  │  │  循环控制：                      │    │    │          传输层                       │  │ │
│  │  │  │    maxRetries / runRetries      │    │    │                                      │  │ │
│  │  │  │    token预算追踪                │    │    │  SseEmitter / Flux<ServerSentEvent>  │  │ │
│  │  │  │    工具调用去重                 │    │    │  WebSocket会话广播                    │  │ │
│  │  │  │    空闲超时检测                 │    │    └──────────────────────────────────────┘  │ │
│  │  │  └─────────────────────────────────┘    │                                               │ │
│  │  └─────────────────────────────────────────┘                                               │ │
│  └────────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                                │
│  ┌──────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                                    共享服务                                                 │ │
│  │                                                                                            │ │
│  │  ┌───────────────┐ ┌───────────────┐ ┌───────────────┐ ┌───────────────┐ ┌─────────────┐  │ │
│  │  │ 模型目录      │ │ 工具注册表    │ │ 记忆系统      │ │ 会话存储      │ │技能注册表   │  │ │
│  │  │               │ │               │ │               │ │               │ │             │  │ │
│  │  │ + 解析器      │ │ + 管线        │ │ 一级: Redis   │ │ JSONL格式     │ │ + DAG图     │  │ │
│  │  │ + 回退        │ │ + 验证        │ │ 二级: PG      │ │ 仅追加        │ │ + 热重载    │  │ │
│  │  │ + 自动探测    │ │ + 速率限制    │ │ 三级: 磁盘    │ │ 每会话        │ │ + 冲突检测  │  │ │
│  │  └───────────────┘ └───────────────┘ └───────────────┘ └───────────────┘ └─────────────┘  │ │
│  │                                                                                            │ │
│  │  ┌───────────────┐ ┌───────────────┐ ┌───────────────┐ ┌───────────────┐ ┌─────────────┐  │ │
│  │  │安全管理器     │ │审批存储       │ │身份解析器     │ │ TTS引擎       │ │指标收集器   │  │ │
│  │  │               │ │               │ │               │ │               │ │             │  │ │
│  │  │ 工具白名单    │ │ 待处理队列    │ │ 信任级别      │ │ ElevenLabs    │ │ Micrometer  │  │ │
│  │  │ 黑名单        │ │ 超时管理      │ │ 配置文件      │ │ Edge TTS      │ │ Prometheus  │  │ │
│  │  │ 速率限制      │ │ 审批界面      │ │ OAuth2/OIDC   │ │ Azure Speech  │ │ Grafana     │  │ │
│  │  └───────────────┘ └───────────────┘ └───────────────┘ └───────────────┘ └─────────────┘  │ │
│  └──────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                                │
│  ┌──────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                                      插件 SDK                                              │ │
│  │                                                                                            │ │
│  │  ┌────────────────────────────────────────────────────────────────────────────────────┐   │ │
│  │  │                            插件清单 (plugin.yml)                                    │   │ │
│  │  │                                                                                     │   │ │
│  │  │  name: "my-plugin"                                                                  │   │ │
│  │  │  version: "1.0.0"                                                                   │   │ │
│  │  │  provides:                                                                          │   │ │
│  │  │    hooks:       [MyHook.class]           # 生命周期拦截                                │   │ │
│  │  │    tools:       [MyTool.class]           # @Tool注解方法                              │   │ │
│  │  │    skills:      [MySkill.class]          # 代理能力包                                 │   │ │
│  │  │    channels:    [MyChannel.class]        # 新的传输适配器                              │   │ │
│  │  │    providers:   [MyProvider.class]       # 自定义LLM后端                              │   │ │
│  │  │    models:      [MyModel.class]          # 模型目录条目                               │   │ │
│  │  │    sandboxes:   [MySandbox.class]        # 自定义沙箱实现                              │   │ │
│  │  │    approvals:   [MyApproval.class]       # 自定义审批处理器                            │   │ │
│  │  │    memories:    [MyMemory.class]         # 自定义记忆后端                              │   │ │
│  │  │                                                                                     │   │ │
│  │  │  classpath: plugin.jar                                                              │   │ │
│  │  │  dependencies:                                                                      │   │ │
│  │  │    - other-plugin:^2.0                                                              │   │ │
│  │  └────────────────────────────────────────────────────────────────────────────────────┘   │ │
│  │                                                                                            │ │
│  │  插件生命周期：  加载 → 验证 → 解析依赖 → 初始化 → 启用 → (停用)                            │ │
│  │  热重载：         监视插件目录 → 检测变更 → 无需重启即可重载                                  │ │
│  │  隔离：           每个插件独立的ClassLoader                                                  │ │
│  └──────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                                │
│  ┌──────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                              横切关注点                                                    │ │
│  │                                                                                            │ │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────┐   │ │
│  │  │ 可观测性        │  │ 配置            │  │ 持久化          │  │ 认证                │   │ │
│  │  │                 │  │                 │  │                 │  │                     │   │ │
│  │  │ OpenTelemetry   │  │ Spring Boot     │  │ PostgreSQL      │  │ OAuth2 / OIDC       │   │ │
│  │  │ 分布式追踪      │  │ 配置树          │  │   - 会话        │  │ JWT令牌             │   │ │
│  │  │ 结构化日志      │  │ 环境变量覆盖    │  │   - 转录记录    │  │ API密钥             │   │ │
│  │  │ 指标导出        │  │ 热重载          │  │   - 审批        │  │ 基于角色的访问控制  │   │ │
│  │  │                 │  │ 验证            │  │   - 身份        │  │ 多租户              │   │ │
│  │  │                 │  │ 密钥管理        │  │ Redis           │  │                     │   │ │
│  │  │                 │  │                 │  │   - 缓存        │  │                     │   │ │
│  │  │                 │  │                 │  │   - 发布/订阅   │  │                     │   │ │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────┘  └─────────────────────┘   │ │
│  └──────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                                │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 代理生命周期流程（改造后）

从入站消息到出站响应的完整处理管线，展示所有36个钩子点、分支路径（内嵌 vs ACP）、压缩、心跳和子代理生成。

```
                                    请求入口
                         (REST / WebSocket / 频道插件)
                                      │
                                      │
                         ┌────────────▼────────────┐
                         │ [钩子: message_received] │
                         │                          │
                         │  过滤/转换                 │
                         │  入站消息                   │
                         │  拦截垃圾/滥用              │
                         │  标准化频道 →              │
                         │    AgentMessage            │
                         └────────────┬────────────┘
                                      │
                                      │  AgentMessage {channel, text, userId, metadata}
                                      │
                         ┌────────────▼────────────┐
                         │     代理路由器           │
                         │                          │
                         │  解析来源：                │
                         │    频道名称                │
                         │    路由绑定模式            │
                         │    @提及目标               │
                         │    acp: 前缀              │
                         │                          │
                         │  输出：agentId            │
                         └────────────┬────────────┘
                                      │
                         ┌────────────▼────────────┐
                         │  代理配置解析器           │
                         │                          │
                         │  system.defaults          │
                         │    → agent.defaults       │
                         │      → @Agent注解         │
                         │        → 运行时覆盖       │
                         │                          │
                         │  输出：                    │
                         │    ResolvedAgentConfig    │
                         └────────────┬────────────┘
                                      │
                         ┌────────────▼────────────┐
                         │ [钩子: before_agent_run] │
                         │                          │
                         │  门禁检查：                │
                         │    通过 → 继续            │
                         │    阻止 → 返回原因        │
                         └────────────┬────────────┘
                                      │
                         ┌────────────▼────────────┐
                         │[钩子: before_agent_start]│
                         │   (已弃用 兼容)            │
                         │   映射到 before_agent_run │
                         └────────────┬────────────┘
                                      │
                         ┌────────────▼────────────┐
                         │    运行时调度             │
                         │                          │
                         │  agentConfig.runtime ==   │
                         │    "embedded" ?           │
                         │    "acp" ?                │
                         └──────┬──────────┬────────┘
                                │          │
               内嵌路径          │          │    ACP 路径
                                │          │
               ┌────────────────▼──┐  ┌────▼──────────────────────────┐
               │ 引导加载器        │  │ AcpRuntime.ensureSession()     │
               │                   │  │                                │
               │ 从磁盘加载：       │  │ 连接到外部提供商                │
               │  AGENTS.md        │  │ 认证会话                        │
               │  SOUL.md          │  │ 协商能力                        │
               │  BOOTSTRAP.md     │  │                                │
               │  IDENTITY.md      │  │ AcpRuntime.startTurn()         │
               │  USER.md          │  │                                │
               │  HEARTBEAT.md     │  │ 发送消息 + 工具                 │
               │                   │  │ 接收流式事件                    │
               │ 验证必填项        │  │ 映射ACP事件 → SSE               │
               │ 缓存到内存        │  │                                │
               └───────┬───────────┘  │ AcpRuntime.cancel()/close()    │
                       │              │   中止 / 超时时                 │
               ┌───────▼───────────┐  └────────────────────────────────┘
               │  上下文引擎       │
               │                   │
               │  assemble():       │
               │   从SessionStore   │
               │     加载会话        │
               │     历史记录        │
               │   注入引导内容      │
               │   应用上下文        │
               │     窗口限制        │
               │   构建系统          │
               │     提示词          │
               │   附加工具          │
               │     定义            │
               └───────┬───────────┘
                       │
               ┌───────▼──────────────────────┐
               │[钩子: before_prompt_build]   │
               │                              │
               │  修改系统提示词                │
               │  注入额外上下文                │
               │  添加自定义指令                │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │[钩子: agent_turn_prepare]    │
               │                              │
               │  最终提示词修改               │
               │  注入用户偏好                 │
               │  应用角色/语气               │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │[钩子: before_model_resolve]  │
               │                              │
               │  拦截模型选择                 │
               │  每次请求覆盖提供商            │
               │  应用路由规则                 │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │     模型解析                  │
               │                              │
               │  1. 显式指定模型 (请求)        │
               │  2. 代理默认模型              │
               │  3. 系统默认模型              │
               │  4. 回退链：                  │
               │     gpt5 → claude → deepseek  │
               │  5. 自动探测健康检查          │
               │     → 跳过不健康的模型        │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │[钩子: model_call_started]    │
               │                              │
               │  记录/审计LLM调用开始         │
               │  追踪token预算               │
               │  发送SSE: model_call_started │
               └───────┬──────────────────────┘
                       │
                       │
               ╔═══════▼══════════════════════════════════════════════╗
               ║                R E A C T   循  环                    ║
               ║                                                     ║
               ║  round = 0                                          ║
               ║  while (round < maxRetries) {                       ║
               ║                                                     ║
               ║    ┌───────────────────────────────────────────┐    ║
               ║    │  [钩子: llm_input]                       │    ║
               ║    │                                           │    ║
               ║    │  检查发送给LLM的提示词                     │    ║
               ║    │  脱敏敏感数据（可选）                      │    ║
               ║    │  记录用于调试                              │    ║
               ║    └───────────────────┬───────────────────────┘    ║
               ║                        │                            ║
               ║    ┌───────────────────▼───────────────────────┐    ║
               ║    │           LLM 调用                        │    ║
               ║    │                                           │    ║
               ║    │  model.call(messages, tools, config)      │    ║
               ║    │      或                                   │    ║
               ║    │  model.stream(messages, tools, config)    │    ║
               ║    │                                           │    ║
               ║    │  发送SSE: thinking_start/delta/end        │    ║
               ║    └───────────────────┬───────────────────────┘    ║
               ║                        │                            ║
               ║    ┌───────────────────▼───────────────────────┐    ║
               ║    │  [钩子: llm_output]                      │    ║
               ║    │                                           │    ║
               ║    │  检查原始LLM响应                          │    ║
               ║    │  内容审核过滤                              │    ║
               ║    │  从响应中解析工具调用                      │    ║
               ║    │  记录token使用量                          │    ║
               ║    └───────────────────┬───────────────────────┘    ║
               ║                        │                            ║
               ║    ┌───────────────────▼───────────────────────┐    ║
               ║    │         工具检测                          │    ║
               ║    │                                           │    ║
               ║    │  if (无工具调用) {                        │    ║
               ║    │    textReply = response.getContent()      │    ║
               ║    │    跳出  // 退出循环                       │    ║
               ║    │  }                                        │    ║
               ║    │                                           │    ║
               ║    │  // 有工具调用                             │    ║
               ║    │  for each toolCall in response {          │    ║
               ║    │                                           │    ║
               ║    │    ┌──────────────────────────────────┐   │    ║
               ║    │    │ [钩子: before_tool_call]        │   │    ║
               ║    │    │                                  │   │    ║
               ║    │    │  门禁：允许 / 拒绝并附原因       │   │    ║
               ║    │    │  验证工具参数                    │   │    ║
               ║    │    │  检查速率限制                    │   │    ║
               ║    │    │  应用预算约束                    │   │    ║
               ║    │    └────────────┬─────────────────────┘   │    ║
               ║    │                 │                         │    ║
               ║    │                 │  if 允许:               │    ║
               ║    │                 │                         │    ║
               ║    │    ┌────────────▼─────────────────────┐   │    ║
               ║    │    │   工具审批流程（如需要）           │   │    ║
               ║    │    │                                   │   │    ║
               ║    │    │  检查 tool.approvalRequired     │   │    ║
               ║    │    │    → approval_request SSE事件     │   │    ║
               ║    │    │      {toolCallId, name, args}    │   │    ║
               ║    │    │    → 等待前端响应                 │   │    ║
               ║    │    │      {approved: true/false}      │   │    ║
               ║    │    │    → 超时 → 自动拒绝              │   │    ║
               ║    │    └────────────┬─────────────────────┘   │    ║
               ║    │                 │                         │    ║
               ║    │    ┌────────────▼─────────────────────┐   │    ║
               ║    │    │   沙箱调度（如需要）               │   │    ║
               ║    │    │                                   │   │    ║
               ║    │    │  检查 tool.sandboxRequired      │   │    ║
               ║    │    │    → 创建/获取容器               │   │    ║
               ║    │    │    → 绑定挂载工作区              │   │    ║
               ║    │    │    → 在容器内执行                │   │    ║
               ║    │    │    → 捕获stdout/stderr          │   │    ║
               ║    │    │    → 销毁/回收容器               │   │    ║
               ║    │    │  否则：在主机上执行               │   │    ║
               ║    │    └────────────┬─────────────────────┘   │    ║
               ║    │                 │                         │    ║
               ║    │    ┌────────────▼─────────────────────┐   │    ║
               ║    │    │        执行工具                  │   │    ║
               ║    │    │                                   │   │    ║
               ║    │    │  ToolPipeline.execute(toolCall)  │   │    ║
               ║    │    │    → 解析工具实例                │   │    ║
               ║    │    │    → 反序列化参数                │   │    ║
               ║    │    │    → 调用 tool.execute()         │   │    ║
               ║    │    │    → 优雅包装错误                │   │    ║
               ║    │    │    → 返回 ToolResult             │   │    ║
               ║    │    └────────────┬─────────────────────┘   │    ║
               ║    │                 │                         │    ║
               ║    │    ┌────────────▼─────────────────────┐   │    ║
               ║    │    │ [钩子: after_tool_call]         │   │    ║
               ║    │    │                                   │   │    ║
               ║    │    │  记录结果/副作用                  │   │    ║
               ║    │    │  追踪工具使用指标                 │   │    ║
               ║    │    │  用元数据丰富结果                 │   │    ║
               ║    │    └────────────┬─────────────────────┘   │    ║
               ║    │                 │                         │    ║
               ║    │    ┌────────────▼─────────────────────┐   │    ║
               ║    │    │ [钩子: tool_result_persist]     │   │    ║
               ║    │    │                                   │   │    ║
               ║    │    │  持久化到转录记录                 │   │    ║
               ║    │    │  如果结果太大则裁剪               │   │    ║
               ║    │    │  设置TTL用于自动清理              │   │    ║
               ║    │    └────────────┬─────────────────────┘   │    ║
               ║    │                 │                         │    ║
               ║    │    继续循环 (将工具结果                  │    ║
               ║    │               追加到消息列表)             │    ║
               ║    │  } // 结束 for each toolCall              │    ║
               ║    │                                           │    ║
               ║    └───────────────────────────────────────────┘    ║
               ║                                                     ║
               ║    ┌───────────────────────────────────────────┐    ║
               ║    │         子代理生成检查                    │    ║
               ║    │                                           │    ║
               ║    │  if (toolCall.name == "delegate_to_agent")│   ║
               ║    │                                           │    ║
               ║    │    ┌─────────────────────────────────┐    │    ║
               ║    │    │ [钩子: subagent_spawning]      │    │    ║
               ║    │    │                                  │    │    ║
               ║    │    │  门禁：允许 / 拒绝               │    │    ║
               ║    │    │  转换任务 / 配置                 │    │    ║
               ║    │    └────────────┬────────────────────┘    │    ║
               ║    │                 │                         │    ║
               ║    │    ┌────────────▼────────────────────┐    │    ║
               ║    │    │ [钩子: subagent_delivery_target]│    │    ║
               ║    │    │                                  │    │    ║
               ║    │    │  解析投递频道                     │    │    ║
               ║    │    │  (使用哪个传输方式)               │    │    ║
               ║    │    └────────────┬────────────────────┘    │    ║
               ║    │                 │                         │    ║
               ║    │    ┌────────────▼────────────────────┐    │    ║
               ║    │    │  检查深度和并发数                 │    │    ║
               ║    │    │                                  │    │    ║
               ║    │    │  if (depth >= maxSpawnDepth)    │    │    ║
               ║    │    │    → 拒绝 "已达最大深度"         │    │    ║
               ║    │    │  if (activeChildren >= maxConc) │    │    ║
               ║    │    │    → 排队 或 拒绝                │    │    ║
               ║    │    └────────────┬────────────────────┘    │    ║
               ║    │                 │  允许                    │    ║
               ║    │    ┌────────────▼────────────────────┐    │    ║
               ║    │    │  生成子 ReActEngine              │    │    ║
               ║    │    │                                  │    │    ║
               ║    │    │  创建隔离的会话                   │    │    ║
               ║    │    │  加载子代理引导文件               │    │    ║
               ║    │    │  运行完整递归管线                 │    │    ║
               ║    │    │  (递归进入生命周期)               │    │    ║
               ║    │    │  等待结果（或流式输出）           │    │    ║
               ║    │    └────────────┬────────────────────┘    │    ║
               ║    │                 │                         │    ║
               ║    │    ┌────────────▼────────────────────┐    │    ║
               ║    │    │ [钩子: subagent_spawned]       │    │    ║
               ║    │    │                                  │    │    ║
               ║    │    │  通知父代理                      │    │    ║
               ║    │    │  发送SSE: subagent_spawned     │    │    ║
               ║    │    └────────────┬────────────────────┘    │    ║
               ║    │                 │                         │    ║
               ║    │    ┌────────────▼────────────────────┐    │    ║
               ║    │    │ [钩子: subagent_ended]         │    │    ║
               ║    │    │                                  │    │    ║
               ║    │    │  清理资源                        │    │    ║
               ║    │    │  发送SSE: subagent_ended       │    │    ║
               ║    │    └────────────┬────────────────────┘    │    ║
               ║    │                 │                         │    ║
               ║    │    将 ToolResult 返回给父LLM              │    ║
               ║    │    作为工具调用响应                       │    ║
               ║    │                                           │    ║
               ║    └───────────────────────────────────────────┘    ║
               ║                                                     ║
               ║    round++                                         ║
               ║    检查 runRetries 预算                             ║
               ║  } // 结束 while                                  ║
               ╚═════════════════════════════════════════════════════╝
                       │
                       │  (循环退出后：文本回复或达到最大重试次数)
                       │
               ┌───────▼──────────────────────┐
               │[钩子: model_call_ended]      │
               │                              │
               │  记录/审计LLM调用结束         │
               │  记录token使用量             │
               │  发送SSE: model_call_ended  │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │[钩子: before_agent_finalize] │
               │                              │
               │  修正门禁：                    │
               │    CONTINUE → 更多轮次       │
               │    REVISE   → 编辑回复       │
               │    FINALIZE → 继续           │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │[钩子: before_agent_reply]    │
               │                              │
               │  过滤/转换回复               │
               │  应用内容策略                 │
               │  按频道格式化                 │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │     块流式输出                │
               │                              │
               │  合并文本块                   │
               │   (防抖 50ms)                 │
               │  应用模拟人类延迟              │
               │   (可配置 5-20ms/字符)         │
               │  发送输入中指示器              │
               │   SSE: typing_start/stop      │
               │  流式传输到传输层：            │
               │    SseEmitter.send(event)     │
               │    WebSocketSession.send()    │
               │    Channel.sendMessage()      │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │     压缩检查                  │
               │                              │
               │  if (transcriptSize > limit) {│
               │                              │
               │    ┌──────────────────────┐   │
               │    │[钩子: before_compact]│   │
               │    │  压缩前钩子          │   │
               │    └──────────┬───────────┘   │
               │               │               │
               │    ┌──────────▼───────────┐   │
               │    │  总结旧轮次          │   │
               │    │  截断至预算大小       │   │
               │    │  注入压缩后段落       │   │
               │    └──────────┬───────────┘   │
               │               │               │
               │    ┌──────────▼───────────┐   │
               │    │[钩子: after_compact] │   │
               │    │  压缩后钩子          │   │
               │    └──────────────────────┘   │
               │  }                            │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │     上下文清理                │
               │                              │
               │  对每个工具结果：              │
               │    if (now - timestamp > TTL) │
               │      → 从上下文中移除         │
               │  裁剪超出keepWindow的          │
               │    旧用户消息                  │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │  [钩子: agent_end]           │
               │                              │
               │  最终清理                     │
               │  通知分发                     │
               │  释放资源                     │
               │  发送SSE: agent_end         │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │  [钩子: message_sending]     │
               │                              │
               │  最终出站过滤器               │
               │  频道特定格式化               │
               │  附件处理                     │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │     会话持久化                │
               │                              │
               │  写入JSONL转录记录            │
               │   {turn, role, content, ts}   │
               │  更新SessionStore             │
               │  发送SSE: done              │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │  [钩子: session_end]          │
               │                              │
               │  如果会话结束：                │
               │    归档转录记录                │
               │    更新分析数据                │
               │    通知webhooks                │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │  [钩子: message_sent]         │
               │                              │
               │  投递后通知                   │
               │  Webhook回调                  │
               │  分析事件                     │
               └──────────────────────────────┘


                          ╔═══════════════════════════════╗
                          ║   心跳（后台）                 ║
                          ║   ─────────────────────────   ║
                          ║                               ║
                          ║  定时触发器触发                ║
                          ║    │                          ║
                          ║  检查活跃时间段窗口            ║
                          ║    │ (例如 08:00-22:00)       ║
                          ║  检查 skipWhenBusy           ║
                          ║    │ (无活跃子代理时)         ║
                          ║  检查冷却期                   ║
                          ║    │ (最小间隔)               ║
                          ║    │                          ║
                          ║  创建隔离会话                  ║
                          ║  加载轻量上下文                ║
                          ║    │ (仅HEARTBEAT.md)         ║
                          ║    │                          ║
                          ║  单轮ReAct                    ║
                          ║    │ (无用户消息)             ║
                          ║    │                          ║
                          ║  [钩子: heartbeat_prompt_     ║
                          ║          contribution]        ║
                          ║    │                          ║
                          ║  将结果投递到目标              ║
                          ║    │ (频道/用户/webhook)      ║
                          ║                               ║
                          ╚═══════════════════════════════╝
```

---

## 3. 配置解析层级

完整的配置合并链，展示设置如何从系统级默认值流向单个运行时调用的代理实例。

```
                            lyclaw.agent.defaults
                        (application.yml / application.properties)
                                      │
           ┌──────────────────────────┼──────────────────────────┐
           │                          │                          │
           ▼                          ▼                          ▼
   ┌───────────────┐         ┌───────────────┐         ┌───────────────┐
   │  模型：       │         │  技能：       │         │  心跳：       │
   │   主模型：     │         │   - shell     │         │   启用：      │
   │     deepseek  │         │   - file      │         │     true      │
   │   回退：       │         │   - web_search│         │   定时：      │
   │     [claude]  │         │               │         │     "0 */4    │
   │   思考：       │         │  上下文限制    │         │      * * *"   │
   │     low       │         │   maxTokens:  │         │   活跃时间段： │
   │               │         │     200000    │         │     08:00-    │
   │  沙箱：        │         │   maxMessages │         │     22:00     │
   │   启用：       │         │     : 200     │         │   忙碌时跳过： │
   │     false     │         │   compactAt:  │         │     true      │
   │   引擎：       │         │     0.8       │         │               │
   │     docker    │         │               │         │  子代理：      │
   │               │         │  审批：       │         │   maxDepth:   │
   │  思考预算：    │         │   模式：      │         │     1         │
   │     16000     │         │     手动      │         │   maxConcur:  │
   │               │         │   超时：      │         │     2         │
   │               │         │     120秒     │         │   maxChildren │
   │               │         │               │         │     : 5       │
   └───────┬───────┘         └───────┬───────┘         └───────┬───────┘
           │                         │                         │
           └─────────────────────────┼─────────────────────────┘
                                     │
                                     │  deepMerge()
                                     │  (嵌套映射合并，列表拼接，
                                     │   标量值覆盖)
                                     │
                                     ▼
                     ┌─────────────────────────────────┐
                     │  @Agent 注解                    │
                     │  在 ChatAgent 接口上             │
                     │                                 │
                     │  @Agent(                        │
                     │    id = "chat",                 │
                     │    name = "Chat Assistant",     │
                     │    model = "deepseek-v4",        │
                     │    thinking = "high",            │
                     │    skills = {"code-review"},     │
                     │    bootstrap = {                 │
                     │      "AGENTS.md",               │
                     │      "SOUL.md"                  │
                     │    },                           │
                     │    approval = MANUAL,            │
                     │    sandbox = true                │
                     │  )                              │
                     │                                 │
                     └───────────────┬─────────────────┘
                                     │
                                     │  deepMerge()
                                     │  (注解值覆盖默认值)
                                     │
                                     ▼
                     ┌─────────────────────────────────┐
                     │  ChatRequest 运行时覆盖          │
                     │  (来自HTTP请求体)                 │
                     │                                 │
                     │  {                               │
                     │    "message": "...",             │
                     │    "planningMode": true,         │
                     │    "model": "claude-opus-4.5",   │
                     │    "thinking": "ultra",           │
                     │    "sessionKey": "main-abc123"   │
                     │  }                               │
                     │                                 │
                     └───────────────┬─────────────────┘
                                     │
                                     │  deepMerge()
                                     │  (运行时覆盖优先级最高，但
                                     │   安全敏感字段除外)
                                     │
                                     ▼
                     ┌─────────────────────────────────────────────────────────┐
                     │                  ResolvedAgentConfig                     │
                     │                  (不可变快照)                             │
                     │                                                         │
                     │  ┌─────────────────────────────────────────────────┐    │
                     │  │ id:           "chat"          (来源: 注解)     │    │
                     │  │ name:         "Chat Assistant"(来源: 注解)     │    │
                     │  │ model:        "claude-opus-4.5"(来源: 运行时)  │    │
                     │  │ thinking:     "ultra"         (来源: 运行时)    │    │
                     │  │ thinkingBudget: 16000         (来源: 默认值)    │    │
                     │  │ skills:    ["shell","file",   (合并: 默认值     │    │
                     │  │             "web_search",      + 注解)          │    │
                     │  │             "code-review"]                        │    │
                     │  │ sandbox:      true            (来源: 注解)      │    │
                     │  │ sandboxEngine:"docker"        (来源: 默认值)    │    │
                     │  │ approval:     MANUAL          (来源: 注解)      │    │
                     │  │ approvalTimeout: 120秒         (来源: 默认值)    │    │
                     │  │ contextMaxTokens: 200000      (来源: 默认值)    │    │
                     │  │ contextMaxMessages: 200       (来源: 默认值)    │    │
                     │  │ contextCompactAt: 0.8          (来源: 默认值)    │    │
                     │  │ heartbeatEnabled: true        (来源: 默认值)    │    │
                     │  │ heartbeatCron: "0 */4 * * *"  (来源: 默认值)    │    │
                     │  │ heartbeatActiveHours:"08-22"  (来源: 默认值)    │    │
                     │  │ subagentMaxDepth: 1           (来源: 默认值)    │    │
                     │  │ subagentMaxConcurrent: 2      (来源: 默认值)    │    │
                     │  │ subagentMaxChildren: 5        (来源: 默认值)    │    │
                     │  │ bootstrap: ["AGENTS.md",      (来源: 注解)      │    │
                     │  │             "SOUL.md"]                            │    │
                     │  │ planningMode:  true           (来源: 运行时)    │    │
                     │  └─────────────────────────────────────────────────┘    │
                     │                                                         │
                     └────────────────────────┬────────────────────────────────┘
                                              │
                                              │  被以下组件消费：
                                              │
              ┌───────────────┬───────────────┼───────────────┬───────────────┐
              │               │               │               │               │
              ▼               ▼               ▼               ▼               ▼
     ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
     │ AgentInvoc.  │ │ ReActEngine  │ │ 引导加载器   │ │ 压缩引擎     │ │ 子代理生成器 │
     │ Handler      │ │              │ │              │ │              │ │              │
     │              │ │ execute()    │ │ load()       │ │ compact()    │ │ spawn()      │
     │ invoke()     │ │ executeStream│ │              │ │              │ │              │
     └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘
              │               │               │               │               │
              └───────────────┴───────────────┼───────────────┴───────────────┘
                                              │
                              ┌───────────────┼───────────────┐
                              │               │               │
                              ▼               ▼               ▼
                     ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
                     │ 心跳调度器   │ │ 所有钩子     │ │ SSE流式输出  │
                     │              │ │ (36个点)     │ │              │
                     │ schedule()   │ │ intercept()  │ │ 使用配置     │
                     │              │ │              │ │ 发送事件     │
                     └──────────────┘ └──────────────┘ └──────────────┘
```

### 合并规则

| 优先级(低→高) | 来源 | 覆盖行为 |
|------------------------|--------|-------------------|
| 1 (最低) | `application.yml` 中的 `lyclaw.agent.defaults` | 所有代理的基础值 |
| 2 | 代理类型默认值 (`lyclaw.agent.chat.*`) | 覆盖特定代理类型的系统默认值 |
| 3 | 接口上的 `@Agent` 注解 | 覆盖此代理定义的默认值 |
| 4 (最高) | `ChatRequest` 请求体字段 | 每次请求覆盖（用户控制） |

**安全敏感字段**（如 `sandbox.enabled`、`approval.mode`）可以通过 `final: true` 在指定层级锁定，防止低优先级层或用户覆盖削弱安全策略。

---

## 4. 子代理委托树

展示递归子代理生成模型：父代理将工作委托给子代理，子代理又可以生成孙子代理，受可配置的深度和并发限制约束。

```
会话：main-abc123
═══════════════════════════════════════════════════════════════════════════════

┌─────────────────────────────────────────────────────────────────────────────┐
│  代理 "chat"  (深度 = 0, 父代理 = null)                                     │
│  ─────────────────────────────────────────                                   │
│  会话：main-abc123                                                            │
│  配置：  ResolvedAgentConfig(chat)                                           │
│  工具：   [web_search, file_read, file_write, shell, delegate_to_agent]     │
│                                                                              │
│  ┌─ 用户："请对PR进行完整的代码审查，运行测试套件，                           │
│  │         并检查安全问题。"                                                  │
│  │                                                                           │
│  ├─ LLM (思考)： "这是一个复杂的多步骤任务。我应该将                           │
│  │   代码审查委托给code-reviewer代理，测试委托给tester代理，                    │
│  │   安全检查委托给security-scanner代理。"                                   │
│  │                                                                           │
│  ├─ 工具调用 #1：delegate_to_agent("code-reviewer", {                        │
│  │       task: "审查 PR #342 中的bug和代码风格问题",                          │
│  │       files: ["src/main/**/*.java"],                                      │
│  │       context: "重点关注空安全和并发问题"                                  │
│  │   })                                                                      │
│  │   │                                                                       │
│  │   ├─ [生成深度为1的子代理]                                                 │
│  │   │                                                                       │
│  │   ▼                                                                       │
│  │  ┌───────────────────────────────────────────────────────────────────┐   │
│  │  │  子代理 "code-reviewer"  (深度 = 1, 父代理 = "chat")             │   │
│  │  │  ──────────────────────────────────────────────────────────────    │   │
│  │  │  会话：main-abc123/subagent/code-reviewer/uuid-a1b2c3d4          │   │
│  │  │  配置：  ResolvedAgentConfig(code-reviewer)                       │   │
│  │  │  工具：   [file_read, file_search, grep, delegate_to_agent]       │   │
│  │  │  自有引导文件：AGENTS.md (code-reviewer角色), SOUL.md             │   │
│  │  │                                                                    │   │
│  │  │  ┌─ 系统提示词 (由code-reviewer引导文件组装)                       │   │
│  │  │  ├─ LLM："让我先读修改过的文件..."                                 │   │
│  │  │  ├─ 工具：file_read("src/main/java/...")                          │   │
│  │  │  ├─ 工具：file_read("src/main/java/...")                          │   │
│  │  │  ├─ LLM："我发现了几个问题。让我也运行一下代码检查工具。"           │   │
│  │  │  │                                                                 │   │
│  │  │  ├─ 工具调用：delegate_to_agent("tester", {                        │   │
│  │  │  │     task: "为修改过的文件运行单元测试",                          │   │
│  │  │  │     testCommand: "mvn test -pl affected-module"                 │   │
│  │  │  │ })                                                              │   │
│  │  │  │   │                                                             │   │
│  │  │  │   ├─ [生成深度为2的子代理]                                       │   │
│  │  │  │   │                                                             │   │
│  │  │  │   ▼                                                             │   │
│  │  │  │  ┌─────────────────────────────────────────────────────────┐   │   │
│  │  │  │  │  子代理 "tester"  (深度 = 2, 父代理 = "code-reviewer")  │   │   │
│  │  │  │  │  ───────────────────────────────────────────────────     │   │   │
│  │  │  │  │  会话：main-abc123/subagent/code-reviewer/uuid-a1b2/    │   │   │
│  │  │  │  │           subagent/tester/uuid-e5f6g7h8                  │   │   │
│  │  │  │  │  配置：  ResolvedAgentConfig(tester)                     │   │   │
│  │  │  │  │  工具：   [shell, file_read]                              │   │   │
│  │  │  │  │                                                           │   │   │
│  │  │  │  │  检查：depth(2) < maxSpawnDepth(1) ?                    │   │   │
│  │  │  │  │    → 如果 maxSpawnDepth=2：允许                           │   │   │
│  │  │  │  │    → 如果 maxSpawnDepth=1 (默认)：拒绝                    │   │   │
│  │  │  │  │      错误："无法生成子代理：已达最大生成深度               │   │   │
│  │  │  │  │              (深度=2 > 最大值=1)"                        │   │   │
│  │  │  │  │                                                           │   │   │
│  │  │  │  │  [本示例假设 maxSpawnDepth=2：]                           │   │   │
│  │  │  │  │                                                           │   │   │
│  │  │  │  │  ├─ LLM："运行测试中..."                                  │   │   │
│  │  │  │  │  ├─ 工具：shell("mvn test -pl affected-module")           │   │   │
│  │  │  │  │  ├─ ToolResult："测试数：47, 失败：2, 错误：0"            │   │   │
│  │  │  │  │  ├─ LLM："2个测试失败。让我检查日志。"                     │   │   │
│  │  │  │  │  ├─ 工具：file_read("target/surefire-reports/...")        │   │   │
│  │  │  │  │  └─ LLM："失败在UserServiceTest中，由新验证逻辑中的        │   │   │
│  │  │  │  │      空指针引起。"                                         │   │   │
│  │  │  │  │                                                           │   │
│  │  │  │  │  返回：{                                                   │   │   │
│  │  │  │  │    testsRun: 47,                                           │   │   │
│  │  │  │  │    failures: 2,                                            │   │   │
│  │  │  │  │    failureDetails: "UserServiceTest: validate()中的NPE",   │   │   │
│  │  │  │  │    elapsedMs: 45200                                        │   │   │
│  │  │  │  │  }                                                         │   │   │
│  │  │  │  └─────────────────────────────────────────────────────────┘   │   │
│  │  │  │                                                                 │   │
│  │  │  └─ 收到tester结果 → 合并到审查中                                  │   │
│  │  │                                                                     │   │
│  │  │  └─ LLM："代码审查完成。发现2个bug (1个空安全，                      │   │
│  │  │      1个并发)。测试确认有2个失败。建议修复。"                        │   │
│  │  │                                                                     │   │
│  │  │  返回：{                                                            │   │
│  │  │    bugsFound: 2,                                                    │   │
│  │  │    testFailures: 2,                                                 │   │
│  │  │    reviewSummary: "...",                                            │   │
│  │  │    elapsedMs: 120000                                                │   │
│  │  │  }                                                                  │   │
│  │  └───────────────────────────────────────────────────────────────────┘   │
│  │                                                                           │
│  ├─ 工具调用 #2：delegate_to_agent("security-scanner", {                    │
│  │       task: "扫描修改过的文件中的安全漏洞",                                │
│  │       files: ["src/main/**/*.java"]                                       │
│  │   })                                                                      │
│  │   │                                                                       │
│  │   ├─ [生成深度为1的子代理 — 如果 maxConcurrent=2，且code-reviewer         │
│  │   │  仍在运行，则此子代理与其并行运行]                                    │
│  │   │                                                                       │
│  │   ▼                                                                       │
│  │  ┌───────────────────────────────────────────────────────────────────┐   │
│  │  │  子代理 "security-scanner"  (深度 = 1, 父代理 = "chat")          │   │
│  │  │  ──────────────────────────────────────────────────────────────    │   │
│  │  │  会话：main-abc123/subagent/security-scanner/uuid-i9j0k1l2        │   │
│  │  │  ... (运行完整ReAct循环，与上面类似)                                │   │
│  │  │                                                                     │   │
│  │  │  返回：{                                                            │   │
│  │  │    vulnerabilitiesFound: 1,                                         │   │
│  │  │    severity: "medium",                                              │   │
│  │  │    details: "UserQueryBuilder中存在SQL注入风险",                   │   │
│  │  │    elapsedMs: 35000                                                 │   │
│  │  │  }                                                                  │   │
│  │  └───────────────────────────────────────────────────────────────────┘   │
│  │                                                                           │
│  └─ LLM："我已获得两个子代理的结果。以下是整合报告：                           │
│      code-reviewer发现2个bug（对应2个测试失败），and                           │
│      security-scanner发现1个中等严重度的安全问题。"                          │
│                                                                              │
│  最终回复给用户（通过SSE流式输出）                                            │
└─────────────────────────────────────────────────────────────────────────────┘


                          并发和深度限制
                          ═══════════════════════════

     ┌──────────────────────────────────────────────────────────────┐
     │  maxSpawnDepth = 1  (默认)                                    │
     │    chat(深度=0) → code-reviewer(深度=1) → tester(深度=2)     │
     │                                                    ✗ 拒绝    │
     │                                                               │
     │  maxSpawnDepth = 2  (放宽)                                    │
     │    chat(深度=0) → code-reviewer(深度=1) → tester(深度=2)     │
     │                                                    ✓ 允许    │
     │                                                               │
     │  maxConcurrent = 2                                             │
     │    chat最多可同时运行2个子代理                                  │
     │    如果第3个被请求而此时有2个活跃中：排队或拒绝                 │
     │                                                               │
     │  maxChildrenPerAgent = 5                                       │
     │    chat在其生命周期内最多生成5个子代理                          │
     └──────────────────────────────────────────────────────────────┘


                          会话键层级
                          ══════════════════════

     main-abc123
       ├── main-abc123/subagent/code-reviewer/uuid-a1b2c3d4
       │     └── main-abc123/subagent/code-reviewer/uuid-a1b2c3d4/
       │           subagent/tester/uuid-e5f6g7h8
       └── main-abc123/subagent/security-scanner/uuid-i9j0k1l2

     每个子代理拥有独立的：
       - 会话键（层级结构，派生自父代理）
       - 转录记录文件（JSONL，隔离）
       - ResolvedAgentConfig（独立合并）
       - 引导文件（从代理自有目录加载）
       - ReActEngine 实例（完整管线，递归执行）
```

---

## 5. SSE事件流（完整）

一次典型请求的完整服务端事件序列，涉及思考、带审批的工具调用、子代理生成、文本流式输出和压缩。

```
                              SSE 事件流
                              ════════════════

  客户端连接：  GET /api/sse/stream?sessionKey=main-abc123
  服务端响应：  Content-Type: text/event-stream
                Connection: keep-alive
                Cache-Control: no-cache

  ╔══════════════════════════════════════════════════════════════════════════╗
  ║                         SSE 事件序列                                    ║
  ╚══════════════════════════════════════════════════════════════════════════╝

  ┌────────────────────────────────────────────────────────────────────────┐
  │ 阶段 1：初始化                                                          │
  ├────────────────────────────────────────────────────────────────────────┤

  event: run_start
  data: {
    "runId": "run-20260520-001",
    "agentId": "chat",
    "agentName": "Chat Assistant",
    "sessionKey": "main-abc123",
    "timestamp": "2026-05-20T14:30:00.000Z",
    "config": {
      "model": "claude-opus-4.5",
      "thinking": "ultra",
      "planningMode": true
    }
  }

  event: bootstrap_loaded
  data: {
    "files": ["AGENTS.md", "SOUL.md", "IDENTITY.md"],
    "totalChars": 3500,
    "totalTokens": 1200,
    "loadDurationMs": 12
  }

  event: context_built
  data: {
    "messageCount": 12,
    "toolResultCount": 5,
    "bootstrapChars": 3500,
    "tokenEstimate": 8500,
    "contextWindowUsed": "4.25%",
    "buildDurationMs": 8
  }

  event: model_resolved
  data: {
    "provider": "anthropic",
    "model": "claude-opus-4.5",
    "thinking": "ultra",
    "thinkingBudget": 16000,
    "resolutionPath": ["runtime_override", "annotation", "defaults"],
    "fallbackChain": ["claude-sonnet-4.5", "deepseek-v4-pro"],
    "healthCheckPassed": true
  }

  event: model_call_started
  data: {
    "runId": "run-20260520-001",
    "provider": "anthropic",
    "model": "claude-opus-4.5",
    "timestamp": "2026-05-20T14:30:00.120Z"
  }

  ┌────────────────────────────────────────────────────────────────────────┐
  │ 阶段 2：首次思考 + 工具调用                                              │
  ├────────────────────────────────────────────────────────────────────────┤

  event: thinking_start
  data: {
    "runId": "run-20260520-001",
    "timestamp": "2026-05-20T14:30:01.050Z"
  }

  event: thinking_delta
  data: {
    "runId": "run-20260520-001",
    "text": "I need to search for the relevant files first. Let me"
  }

  event: thinking_delta
  data: {
    "runId": "run-20260520-001",
    "text": " check the project structure to understand where the"
  }

  event: thinking_delta
  data: {
    "runId": "run-20260520-001",
    "text": " user management code is located."
  }

  event: thinking_end
  data: {
    "runId": "run-20260520-001",
    "totalThinkingTokens": 45,
    "durationMs": 1200
  }

  event: tool_call
  data: {
    "runId": "run-20260520-001",
    "toolCallId": "toolu_bdrk_01ABC123",
    "name": "file_search",
    "args": {
      "pattern": "**/User*.java",
      "path": "src/main/java"
    },
    "round": 1,
    "timestamp": "2026-05-20T14:30:02.250Z"
  }

  event: tool_result
  data: {
    "runId": "run-20260520-001",
    "toolCallId": "toolu_bdrk_01ABC123",
    "name": "file_search",
    "success": true,
    "result": {
      "files": [
        "src/main/java/com/example/user/User.java",
        "src/main/java/com/example/user/UserService.java",
        "src/main/java/com/example/user/UserController.java",
        "src/main/java/com/example/user/UserRepository.java"
      ],
      "count": 4
    },
    "durationMs": 85,
    "timestamp": "2026-05-20T14:30:02.335Z"
  }

  ┌────────────────────────────────────────────────────────────────────────┐
  │ 阶段 3：第二次工具调用（带审批的shell命令）                               │
  ├────────────────────────────────────────────────────────────────────────┤

  event: tool_call
  data: {
    "runId": "run-20260520-001",
    "toolCallId": "toolu_bdrk_02DEF456",
    "name": "shell",
    "args": {
      "command": "git log --oneline -10 src/main/java/com/example/user/",
      "workingDir": "/home/user/project"
    },
    "round": 2,
    "timestamp": "2026-05-20T14:30:05.100Z"
  }

  event: tool_approval_request
  data: {
    "runId": "run-20260520-001",
    "toolCallId": "toolu_bdrk_02DEF456",
    "name": "shell",
    "args": {
      "command": "git log --oneline -10 src/main/java/com/example/user/",
      "workingDir": "/home/user/project"
    },
    "risk": "low",
    "reason": "只读git命令",
    "timeoutSeconds": 120,
    "timestamp": "2026-05-20T14:30:05.101Z"
  }

  ── 前端显示审批对话框 ──
  ── 用户点击"批准" ──

  event: tool_approval_response
  data: {
    "runId": "run-20260520-001",
    "toolCallId": "toolu_bdrk_02DEF456",
    "approved": true,
    "approvedBy": "user@example.com",
    "timestamp": "2026-05-20T14:30:08.500Z"
  }

  event: tool_result
  data: {
    "runId": "run-20260520-001",
    "toolCallId": "toolu_bdrk_02DEF456",
    "name": "shell",
    "success": true,
    "result": {
      "exitCode": 0,
      "stdout": "abc1234 Fix user validation bug\ndef5678 Add user export feature\n...",
      "stderr": ""
    },
    "durationMs": 320,
    "sandboxed": true,
    "timestamp": "2026-05-20T14:30:08.820Z"
  }

  ┌────────────────────────────────────────────────────────────────────────┐
  │ 阶段 4：子代理生成（code-reviewer）                                       │
  ├────────────────────────────────────────────────────────────────────────┤

  event: tool_call
  data: {
    "runId": "run-20260520-001",
    "toolCallId": "toolu_bdrk_03GHI789",
    "name": "delegate_to_agent",
    "args": {
      "agentId": "code-reviewer",
      "task": "审查 UserService.java 中的bug、空安全和并发问题",
      "files": ["src/main/java/com/example/user/UserService.java"],
      "context": "最近的提交显示有验证逻辑变更"
    },
    "round": 3,
    "timestamp": "2026-05-20T14:30:12.000Z"
  }

  event: subagent_spawning
  data: {
    "parentRunId": "run-20260520-001",
    "parentAgentId": "chat",
    "childAgentId": "code-reviewer",
    "childRunId": "run-20260520-002",
    "depth": 1,
    "maxDepth": 2,
    "depthRemaining": 1,
    "task": "审查 UserService.java 中的bug、空安全和并发问题",
    "sessionKey": "main-abc123/subagent/code-reviewer/uuid-x1y2z3w4",
    "timestamp": "2026-05-20T14:30:12.050Z"
  }

  event: subagent_spawned
  data: {
    "parentRunId": "run-20260520-001",
    "childAgentId": "code-reviewer",
    "childRunId": "run-20260520-002",
    "sessionKey": "main-abc123/subagent/code-reviewer/uuid-x1y2z3w4",
    "status": "running",
    "timestamp": "2026-05-20T14:30:12.100Z"
  }

  ── 子代理在内部运行（其自身事件在单独的SSE通道上发出，                     ──
  ── 或者如果配置了，嵌套在父级流中）                                         ──

  event: subagent_ended
  data: {
    "parentRunId": "run-20260520-001",
    "childAgentId": "code-reviewer",
    "childRunId": "run-20260520-002",
    "outcome": "ok",
    "result": {
      "bugsFound": 3,
      "severity": {
        "critical": 0,
        "high": 1,
        "medium": 2
      },
      "summary": "在validate()方法中发现空安全问题，在updateUser()中发现竞态条件"
    },
    "elapsedMs": 45000,
    "tokensUsed": {
      "input": 12000,
      "output": 800,
      "total": 12800
    },
    "timestamp": "2026-05-20T14:30:57.150Z"
  }

  event: tool_result
  data: {
    "runId": "run-20260520-001",
    "toolCallId": "toolu_bdrk_03GHI789",
    "name": "delegate_to_agent",
    "success": true,
    "result": {
      "bugsFound": 3,
      "severity": {"critical": 0, "high": 1, "medium": 2},
      "summary": "在validate()方法中发现空安全问题，在updateUser()中发现竞态条件"
    },
    "durationMs": 45150,
    "timestamp": "2026-05-20T14:30:57.200Z"
  }

  ┌────────────────────────────────────────────────────────────────────────┐
  │ 阶段 5：最终思考 + 文本响应                                               │
  ├────────────────────────────────────────────────────────────────────────┤

  event: thinking_start
  data: {
    "runId": "run-20260520-001",
    "timestamp": "2026-05-20T14:30:58.000Z"
  }

  event: thinking_delta
  data: {
    "runId": "run-20260520-001",
    "text": "The code review found 3 bugs. Let me summarise"
  }

  event: thinking_delta
  data: {
    "runId": "run-20260520-001",
    "text": " the findings and provide actionable recommendations."
  }

  event: thinking_end
  data: {
    "runId": "run-20260520-001",
    "totalThinkingTokens": 28,
    "durationMs": 800
  }

  event: model_call_ended
  data: {
    "runId": "run-20260520-001",
    "provider": "anthropic",
    "model": "claude-opus-4.5",
    "totalRounds": 3,
    "totalToolCalls": 3,
    "usage": {
      "inputTokens": 18500,
      "outputTokens": 2400,
      "totalTokens": 20900,
      "cacheReadTokens": 4200,
      "cacheWriteTokens": 8500
    },
    "durationMs": 55000,
    "timestamp": "2026-05-20T14:30:58.800Z"
  }

  event: before_finalize
  data: {
    "runId": "run-20260520-001",
    "action": "finalize",
    "timestamp": "2026-05-20T14:31:00.000Z"
  }

  event: message_start
  data: {
    "runId": "run-20260520-001",
    "timestamp": "2026-05-20T14:31:00.050Z"
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "Based on my thorough analysis of the codebase, "
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "the code review of `UserService.java` found **3 bugs**:\n\n"
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "### 1. Null Safety Issue (HIGH)\n"
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "The `validate()` method does not check for null before "
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "calling `user.getEmail()`. This can cause a `NullPointerException` "
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "when the user object is not fully initialised.\n\n"
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "**Recommendation:** Add a `Objects.requireNonNull(user, \"user must not be null\")` "
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "guard at the top of the method.\n\n"
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "### 2. Race Condition (MEDIUM)\n"
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "The `updateUser()` method reads-modifies-writes without "
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "synchronisation, which can lead to lost updates under concurrent access.\n\n"
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "**Recommendation:** Use `synchronized` block or `ReentrantReadWriteLock` "
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "to protect the critical section.\n\n"
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "### 3. Resource Leak (MEDIUM)\n"
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "The file export stream in `exportUserData()` is not closed in a "
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "finally block, potentially leaking file handles.\n\n"
  }

  event: message_end
  data: {
    "runId": "run-20260520-001",
    "totalMessageChars": 856,
    "timestamp": "2026-05-20T14:31:02.500Z"
  }

  ┌────────────────────────────────────────────────────────────────────────┐
  │ 阶段 6：压缩（如果触发）                                                 │
  ├────────────────────────────────────────────────────────────────────────┤

  event: compaction_start
  data: {
    "runId": "run-20260520-001",
    "reason": "transcript_size_exceeded",
    "currentSizeBytes": 11534336,
    "limitBytes": 10485760,
    "usagePercent": 110.0,
    "timestamp": "2026-05-20T14:31:03.000Z"
  }

  event: compaction_progress
  data: {
    "runId": "run-20260520-001",
    "phase": "summarising",
    "turnsProcessed": 8,
    "totalTurns": 15,
    "timestamp": "2026-05-20T14:31:03.500Z"
  }

  event: compaction_complete
  data: {
    "runId": "run-20260520-001",
    "removedChars": 52000,
    "keptChars": 180000,
    "keptTurns": 15,
    "summarisedTurns": 8,
    "compactionRatio": "0.78",
    "durationMs": 450,
    "timestamp": "2026-05-20T14:31:03.950Z"
  }

  ┌────────────────────────────────────────────────────────────────────────┐
  │ 阶段 7：运行完成                                                         │
  ├────────────────────────────────────────────────────────────────────────┤

  event: agent_end
  data: {
    "runId": "run-20260520-001",
    "agentId": "chat",
    "sessionKey": "main-abc123",
    "elapsedMs": 65000,
    "totalRounds": 3,
    "totalToolCalls": 3,
    "totalSubagentsSpawned": 1,
    "usage": {
      "inputTokens": 18500,
      "outputTokens": 2400,
      "totalTokens": 20900
    },
    "compacted": true,
    "outcome": "completed",
    "timestamp": "2026-05-20T14:31:05.000Z"
  }

  event: done
  data: {
    "runId": "run-20260520-001",
    "sessionKey": "main-abc123",
    "timestamp": "2026-05-20T14:31:05.050Z"
  }

  ╔══════════════════════════════════════════════════════════════════════════╗
  ║                       SSE 事件类型目录                                   ║
  ╚══════════════════════════════════════════════════════════════════════════╝

  ┌──────────────────────────┬──────────────────────────────────────────────┐
  │ 事件名称                  │ 描述                                         │
  ├──────────────────────────┼──────────────────────────────────────────────┤
  │ run_start                │ 新的代理运行已启动                              │
  │ bootstrap_loaded         │ 引导文件已从磁盘加载                            │
  │ context_built            │ 上下文已组装（消息、工具、提示词）               │
  │ model_resolved           │ 经过解析链后选定的LLM模型                       │
  │ model_call_started       │ LLM API调用已启动                              │
  │ thinking_start           │ LLM思考/思维链块已开始                          │
  │ thinking_delta           │ 增量思考文本                                   │
  │ thinking_end             │ LLM思考/思维链块已结束                          │
  │ tool_call                │ LLM请求的工具调用                              │
  │ tool_approval_request    │ 工具需要用户审批（发送到界面）                   │
  │ tool_approval_response   │ 收到用户的审批决定                              │
  │ tool_result              │ 工具执行结果                                   │
  │ subagent_spawning        │ 即将生成子代理                                 │
  │ subagent_spawned         │ 子代理已成功创建并运行中                         │
  │ subagent_ended           │ 子代理已完成（ok/error/timeout）               │
  │ model_call_ended         │ LLM API调用已完成（附用量统计）                  │
  │ before_finalize          │ 最终化门禁（finalize/revise/continue）          │
  │ message_start            │ 文本响应流式输出已开始                           │
  │ message_delta            │ 增量文本响应                                   │
  │ message_end              │ 文本响应流式输出已结束                           │
  │ compaction_start         │ 上下文压缩已触发                                │
  │ compaction_progress      │ 压缩进度更新                                   │
  │ compaction_complete      │ 上下文压缩已完成                                │
  │ agent_end                │ 代理运行已完成（附摘要）                         │
  │ done                     │ SSE流已结束（连接保持打开）                      │
  │ error                    │ 发生错误（可恢复或致命）                         │
  │ heartbeat                │ 心跳保活（每30秒空闲时）                         │
  └──────────────────────────┴──────────────────────────────────────────────┘
```

---

## 6. 组件清单与职责

| # | 组件 | 层 | 职责 |
|---|-----------|-------|----------------|
| 1 | **REST/SSE控制器** | 传输 | 接受HTTP POST聊天请求，返回SSE流。处理CORS、认证、速率限制。 |
| 2 | **WebSocket处理器** | 传输 | 维护持久的双向连接。支持会话恢复。 |
| 3 | **WebChat界面** | 传输 | 基于React的聊天界面，作为静态资源提供。通过SSE/WS连接。 |
| 4 | **频道插件** | 传输 | 将外部消息平台（Telegram、Discord、Slack、WeChat）适配到内部AgentMessage格式。 |
| 5 | **代理路由器** | 路由 | 通过频道名称、路由绑定、@提及或ACP前缀将入站消息匹配到代理实例。 |
| 6 | **代理配置解析器** | 配置 | 将系统默认值、代理默认值、`@Agent`注解和运行时覆盖深度合并为`ResolvedAgentConfig`。 |
| 7 | **AgentInvocationHandler** | 运行时 | JDK动态代理，拦截`ChatAgent`接口调用并分发到正确的运行时（内嵌或ACP）。 |
| 8 | **引导加载器** | 运行时（内嵌） | 加载、验证和缓存引导Markdown文件（AGENTS.md、SOUL.md、BOOTSTRAP.md、IDENTITY.md、USER.md、HEARTBEAT.md）。 |
| 9 | **上下文引擎** | 运行时（内嵌） | 从会话历史、引导内容、工具定义和系统提示词组装完整的LLM上下文。按需压缩和清理。 |
| 10 | **ReAct引擎** | 运行时（内嵌） | 执行推理-行动循环：调用LLM，检测工具调用，执行工具，将结果反馈回去，循环直到文本回复或预算耗尽。 |
| 11 | **块流式输出** | 运行时（内嵌） | 合并文本增量，应用模拟人类延迟，发送输入中指示器，并流式传输到SSE/WebSocket。 |
| 12 | **AcpRuntime** | 运行时（ACP） | 管理外部ACP提供商会话。转发消息，将ACP事件转换为内部SSE事件。 |
| 13 | **心跳调度器** | 运行时 | 基于定时表达式的后台代理激活。在触发单轮ReAct之前检查活跃时间段、空闲状态和冷却期。 |
| 14 | **子代理生成器** | 运行时 | 创建具有隔离会话的子`ReActEngine`实例。执行深度、并发和子代理数量限制。 |
| 15 | **沙箱** | 运行时 | 在Docker/Podman容器内执行工具调用，具有文件系统桥接和资源限制。 |
| 16 | **模型目录** | 共享服务 | 可用LLM模型的注册表，包含能力、定价和健康状态。 |
| 17 | **模型解析器** | 共享服务 | 使用主模型、回退链和自动探测健康检查为请求解析最佳模型。 |
| 18 | **工具注册表** | 共享服务 | 注册和发现来自核心和插件的`@Tool`注解方法。 |
| 19 | **工具管线** | 共享服务 | 通过验证、审批、沙箱和结果丰富中间件执行工具调用。 |
| 20 | **记忆系统** | 共享服务 | 三级记忆：Redis（热）、PostgreSQL（温）、磁盘（冷）。存储会话历史和代理知识。 |
| 21 | **会话存储** | 共享服务 | 以仅追加的JSONL格式持久化对话转录记录。支持层级式子代理会话键。 |
| 22 | **技能注册表** | 共享服务 | 将代理能力包注册为DAG图。支持热重载和冲突检测。 |
| 23 | **安全管理器** | 共享服务 | 执行工具白名单/黑名单、速率限制和内容安全策略。 |
| 24 | **审批存储** | 共享服务 | 管理待处理的工具审批请求，包含超时、自动拒绝和界面集成。 |
| 25 | **身份解析器** | 共享服务 | 从OAuth2/OIDC/JWT令牌解析用户身份。映射到信任级别和权限配置。 |
| 26 | **TTS引擎** | 共享服务 | 通过ElevenLabs、Edge TTS或Azure Speech进行文本转语音合成，用于语音频道输出。 |
| 27 | **指标收集器** | 共享服务 | 导出Micrometer指标到Prometheus。通过Grafana进行仪表板展示。 |
| 28 | **插件SDK** | 可扩展性 | 定义和执行插件契约（清单、类加载器隔离、生命周期、热重载）。 |
| 29 | **钩子管线** | 横切 | 36点生命周期拦截。插件按优先级排序注册钩子处理器。 |

---

## 7. 关键设计决策

| 决策 | 理由 |
|----------|-----------|
| **内嵌 + ACP双运行时** | 必须保留现有的ACP提供商集成。新代理使用内嵌运行时。两者共享相同的传输、路由和钩子管线。 |
| **36点钩子管线** | 当前钩子的超集，加上子代理、压缩、心跳和流式输出所需的钩子。每个钩子有定义的接口、优先级和异步/同步契约。 |
| **深度合并配置解析** | 四层合并（系统 → 代理默认值 → 注解 → 运行时），对安全敏感字段使用`final: true`锁定。不可变的`ResolvedAgentConfig`快照防止运行时修改。 |
| **递归子代理模型** | 代理可以通过`delegate_to_agent`工具委托给其他代理。每个子代理运行一个完整的独立ReAct循环，拥有自己的会话、配置和引导文件。深度/并发/子代理数量限制防止失控生成。 |
| **层级会话键** | 子代理会话键派生自父代理（如`main-abc123/subagent/code-reviewer/uuid-1`），实现可追溯性、独立压缩和清理。 |
| **SSE作为主要流式协议** | 优于仅使用WebSocket，因为SSE更简单（HTTP原生、自动重连、单向服务器→客户端），而双向需求已由REST请求路径处理。WebSocket作为需要它的频道的替代方案。 |
| **带人类延迟的块流式输出** | 文本响应被合并成具有可配置延迟的自然感块，防止聊天界面中出现"文字墙"效果。 |
| **基于容器的沙箱** | 通过Docker/Podman容器进行工具执行隔离，具有文件系统桥接、网络限制和资源限制。按代理或共享容器池。 |
| **带类加载器隔离的插件SDK** | 第三方插件在其自己的ClassLoader中运行，防止依赖冲突。清单声明提供的扩展（钩子、工具、技能、频道、提供商）。 |
| **心跳作为定时驱动后台代理** | 心跳不是独立的系统，而是一个定时触发的代理运行。它使用相同的ReAct管线，但具有隔离的会话和轻量上下文（仅HEARTBEAT.md）。 |

---

## 8. 从当前架构的迁移路径

```
当前状态                               中间状态                             目标状态
─────────────                          ──────────────────                    ────────────

┌──────────────────┐                   ┌──────────────────┐                 ┌──────────────────┐
│ 单体 Spring Boot │                   │ 模块化           │                 │ 完整平台         │
│ 应用             │                   │ Spring Boot 应用 │                 │ 架构             │
│                  │                   │                  │                 │                  │
│ ChatAgent.java   │  ──阶段1──►       │ 代理运行时       │  ──阶段3──►     │ 代理运行时       │
│ (接口)           │   提取            │ (内嵌 + ACP)     │   插件SDK       │ + 插件SDK        │
│                  │   ChatAgent       │                  │                 │ + 完整钩子       │
│ LLM调用内联      │   中的运行时      │ 钩子管线         │                 │ + 子代理         │
│                  │                   │ (18个钩子)       │                 │ + 心跳           │
│ 无钩子           │  ──阶段2──►       │                  │                 │ + 沙箱           │
│ 无子代理         │   添加钩子、      │ 配置解析器       │                 │ + 压缩           │
│ 无心跳           │   配置合并、      │ (3层)            │                 │                  │
│ 无沙箱           │   压缩            │                  │                 │                  │
└──────────────────┘                   └──────────────────┘                 └──────────────────┘

阶段1 (MVP)：     从ChatAgent中提取ReActEngine、BootstrapLoader、ContextEngine。
                 保持ACP路径不变。引入AgentInvocationHandler代理。

阶段2 (核心)：     添加前18个钩子。实现深度合并的配置解析器。添加
                 压缩和清理功能。引入SSE块流式输出。

阶段3 (完整)：     完成全部36个钩子。添加子代理生成器、心跳调度器、
                 沙箱、插件SDK。实现完整架构蓝图。
```

---

> **文档维护者：** 架构团队  
> **审查周期：** 每次重大设计决策或架构变更时更新。  
> **相关文档：**
> - `07-hook-lifecycle-full.md` — 完整36钩子规范
> - `08-subagent-delegation-design.md` — 子代理生成和管理
> - `10-sse-streaming-protocol.md` — SSE事件格式规范
> - `11-plugin-sdk-contract.md` — 插件SDK接口定义

---

# LyClaw 智能体改造 — 实施路线图

> **状态：** 规划中  
> **目标：** 将 LyClaw 智能体架构提升至与 OpenClaw 在智能体配置、钩子、子智能体委托、模型目录、上下文管理、流式输出、沙箱和心跳能力方面达到同等水平。  
> **原则：** 所有变更均为增量式且向后兼容 — 现有测试、注解、钩子和管道阶段无需修改即可继续运行。

---

## 1. 总体时间线与优先级矩阵

| 阶段 | 名称 | 优先级 | 预估工作量 | 依赖项 | 风险 |
|-------|------|----------|-------------|--------------|------|
| 阶段 1 | 智能体核心增强 | P0 | 3-4 周 | 无 | 低 |
| 阶段 2 | 子智能体 + 模型 | P1 | 4-6 周 | 阶段 1 | 中 |
| 阶段 3 | 上下文 + 引导 + 路由 | P2 | 3-4 周 | 阶段 1 | 中 |
| 阶段 4 | 流式 + 沙箱 + 心跳 | P2 | 3-4 周 | 阶段 1, 2 | 高 |

**总预估工作量：13-18 周**（假设一名全职开发者；阶段 1 完成后，阶段 2-4 可并行推进）。

### 风险定义

- **低：** 变更纯粹是增量式的，带有默认值；现有代码路径不受影响。
- **中：** 新组件与现有子系统（ChatFacade、管道阶段）存在交互；需要仔细的集成测试。
- **高：** 外部依赖（Docker 守护进程、SSE 时序、定时任务）；测试受环境影响较大。

---

## 2. 阶段 1 详细任务列表 — 智能体核心增强

**目标：** 扩展基础层 — 注解、配置解析、钩子、上下文和代理工厂 — 使所有后续阶段拥有丰富的配置面可供构建。

### 当前基线（阶段 1 开始前）

| 组件 | 当前状态 |
|-----------|---------------|
| `@Agent` 注解 | 6 个字段：`name`、`description`、`version`、`model`、`provider`、`extensions` |
| `AgentHook` 接口 | 6 个方法：`beforeRequest`、`beforeModel`、`afterModel`、`wrapToolCall`、`wrapToolExecutor`、`afterResult` + `getOrder` |
| `AgentContext` | ~20 个字段：sessionId、userMessage、systemPrompt、chatRequest、toolRegistry、method、args、sandboxLevel、lifecycle、tracing、toolResults、successCount、failCount、nodes、reflectScoreRef、pipelineOk、respondStartMs、terminated、currentStage、attributes + snapshot/restore |
| `AgentConfig` | 5 个核心字段 + `Map<String, String> extensions` |
| `AgentConfigResolver` | 通过 `AgentConfigSource` SPI 实现多源优先级合并 |
| `AgentProxyFactory` | JDK 动态代理；构造函数注入 ChatFacade、ReActEngine、ToolRegistry、hooks、stages |
| `AgentInvocationHandler` | 钩子分发 + 管道阶段编排 + `MAX_REFLECTION_RETRIES = 2` |
| 钩子实现 | 5 个：SecurityCheckHook (order=10)、SandboxHook (order=20)、ApprovalHook (order=30)、PlanningHook (order=40)、OutputGuardHook (order=90) |
| 管道阶段 | 6 个：ContextBuild (0)、SecurityCheck (1)、PlanExecution (2)、Respond (3)、Reflection (4)、Metrics (5) |

---

### 第 1-2 周：配置基础

#### 任务 1.1 — 创建 `AgentDefaultsConfig` 类
**包：** `lyjew.com.lyclaw.config`  
**文件：** `AgentDefaultsConfig.java`

一个 `@ConfigurationProperties(prefix = "lyclaw.agent.defaults")` 类，持有所有系统级默认值。镜像对应 OpenClaw 的 `AgentConfig` 字段。

**需包含的字段（30+）：**
```
id, default (boolean), workspace, agentDir, systemPrompt, systemPromptOverride,
model, provider, fallbacks (List<String>), skills (List<String>),
thinkingDefault, thinkingLevel, verboseDefault, verboseLevel,
reasoningDefault, reasoningLevel, fastModeDefault, fastMode,
contextTokens, maxContextTokens, bootstrapMaxChars,
bootstrapTotalMaxChars, contextInjection (enum),
delegationMode (enum), allowAgents (List<String>),
maxSpawnDepth, maxChildrenPerAgent, sandbox (enum),
streamingEnabled, blockStreamingMaxChars, blockStreamingMaxIdleMs,
humanDelayMinMs, humanDelayMaxMs, typingMode (enum),
heartbeatEnabled, heartbeatCron, heartbeatActiveHoursStart,
heartbeatActiveHoursEnd, heartbeatLightContext,
heartbeatIsolatedSession, heartbeatSkipWhenBusy,
maxReflectionRetries, reflectionRetryThreshold
```

**验证：** 使用 `@Validated` 配合适当的 JSR-303 约束（例如，token 使用 `@Min(0)`，重试次数使用 `@Min(1)`）。

**对应的 YAML：** 在 `application.yml` 中添加 `lyclaw.agent.defaults` 配置节。

#### 任务 1.2 — 扩展 `@Agent` 注解
**文件：** `lyjew.com.lyclaw.annotation.Agent`（修改现有文件）

新增 20+ 个可选字段 — 全部设置默认值，使现有 `@Agent` 用法无需改动即可编译通过。

**新增字段：**
```java
String id() default "";                    // 稳定标识符（默认使用 name）
boolean isDefault() default false;         // 是否为默认智能体？
String workspace() default "";             // 工作区目录路径
String agentDir() default "";              // 智能体专属目录
String systemPromptOverride() default "";  // 覆盖系统提示词
String[] fallbacks() default {};           // 回退模型名称列表
String[] skills() default {};              // 技能标识符列表
String thinkingDefault() default "";       // 默认思考级别
String thinkingLevel() default "";         // 思考级别覆盖
String verboseDefault() default "";        // 默认详细级别
String verboseLevel() default "";          // 详细级别覆盖
String reasoningDefault() default "";      // 默认推理级别
String reasoningLevel() default "";        // 推理级别覆盖
boolean fastModeDefault() default false;   // 快速模式默认值
boolean fastMode() default false;          // 快速模式覆盖
int contextTokens() default 0;             // 上下文 token 预算（0 = 使用默认值）
int bootstrapMaxChars() default 0;         // 引导最大字符数（0 = 使用默认值）
int bootstrapTotalMaxChars() default 0;    // 引导总最大字符数
String contextInjection() default "";      // 注入策略
String delegationMode() default "";        // 委托模式
String[] allowAgents() default {};         // 子智能体生成白名单
int maxSpawnDepth() default 0;             // 子智能体最大递归深度
int maxChildrenPerAgent() default 0;       // 最大并发子智能体数
String sandbox() default "";               // 沙箱级别覆盖
```

#### 任务 1.3 — 创建 `ResolvedAgentConfig`
**包：** `lyjew.com.lyclaw.config`  
**文件：** `ResolvedAgentConfig.java`

一个不可变的 record（或使用建造者模式的 final 类），表示单次智能体调用的完整合并配置。这是解析过程的输出 — 合并了 `AgentDefaultsConfig` + `@Agent` 注解 + 运行时覆盖。

**设计决策：** 对已解析配置使用类 record 风格并配合 Builder，避免使用可变 `AgentConfig` 模式。现有 `AgentConfig` 保留用于源层面表示；`ResolvedAgentConfig` 是规范的运行时形式。

**字段：** 镜像对应 `AgentDefaultsConfig` 中全部 30+ 个字段，具有具体（非空、非零默认值）值。

#### 任务 1.4 — 增强 `AgentConfigResolver`，增加深度合并逻辑
**文件：** `lyjew.com.lyclaw.config.AgentConfigResolver`（修改现有文件）

添加一个新方法：
```java
public ResolvedAgentConfig resolveFull(String agentName, AgentDefaultsConfig defaults,
                                        Map<String, String> runtimeOverrides)
```

**深度合并规则：**
1. 从 `AgentDefaultsConfig` 值开始（最低优先级）。
2. 用 `@Agent` 注解中非空/非零的值覆盖。
3. 用 `AgentConfigSource` 链的值覆盖（现有多源合并）。
4. 用运行时覆盖值覆盖（最高优先级）。
5. 对于列表字段（`fallbacks`、`skills`、`allowAgents`）：拼接而非替换。
6. 对于布尔字段：显式注解 `false` 覆盖默认 `true`，但注解默认 `false` 不覆盖默认 `true`（使用 `@Nullable Boolean` 包装语义）。

#### 任务 1.5 — 将智能体配置添加到 `application.yml`
**文件：** `lyclaw-framework/src/main/resources/application.yml`（如不存在则创建）

```yaml
lyclaw:
  agent:
    defaults:
      model: "deepseek-v4-flash"
      provider: "deepseek"
      maxReflectionRetries: 2
      reflectionRetryThreshold: 0.6
      contextTokens: 128000
      maxContextTokens: 200000
      bootstrapMaxChars: 50000
      bootstrapTotalMaxChars: 200000
      contextInjection: "always"
      delegationMode: "local"
      maxSpawnDepth: 3
      maxChildrenPerAgent: 5
      sandbox: "direct"
      streamingEnabled: true
      blockStreamingMaxChars: 80
      blockStreamingMaxIdleMs: 150
      humanDelayMinMs: 100
      humanDelayMaxMs: 400
      typingMode: "message"
      heartbeatEnabled: false
      heartbeatCron: "0 */30 * * * *"
      heartbeatActiveHoursStart: "09:00"
      heartbeatActiveHoursEnd: "18:00"
      heartbeatLightContext: false
      heartbeatIsolatedSession: true
      heartbeatSkipWhenBusy: true
```

#### 任务 1.6 — 创建 `ConfigResolutionTest`
**文件：** `lyclaw-framework/src/test/java/lyjew/com/lyclaw/config/ConfigResolutionTest.java`

测试用例：
- 仅用默认值解析可生成有效的 `ResolvedAgentConfig`。
- 注解正确覆盖默认值。
- 运行时覆盖具有最高优先级。
- 列表字段跨源拼接。
- 布尔字段使用可空语义。
- 缺少的可选字段优雅回退到默认值。
- 无效配置（例如负数 token 数）抛出 `ConfigurationValidationException`。

---

### 第 2-3 周：上下文与钩子扩展

#### 任务 2.1 — 扩展 `AgentContext`，新增 15+ 个字段
**文件：** `lyjew.com.lyclaw.react.AgentContext`（修改现有文件）

**新增字段：**
```java
// 智能体标识
private String agentId;
private String agentName;

// 目录
private String workspaceDir;
private String agentDir;

// 已解析的配置
private ResolvedAgentConfig resolvedConfig;

// 引导内容
private String bootstrapContent;

// 上下文限制
private AgentContextLimits contextLimits;

// LLM 行为级别
private String thinkingLevel;
private String verboseLevel;
private String reasoningLevel;

// 子智能体委托
private String delegationMode;
private List<String> allowAgents;
private int maxSpawnDepth;
private int maxChildrenPerAgent;
private List<String> activeSubagentIds;

// 运行时元数据
private AgentRuntimeType runtimeType;
private Map<String, Object> runMetadata;
```

**快照/恢复：** 更新 `toSnapshot()` 和 `restoreFromSnapshot()` 以包含所有可序列化的新字段。运行时引用（resolvedConfig、contextLimits）应通过其自身的序列化方法包含在内。

**向后兼容性：** 保留所有现有构造函数签名。为扩展形式添加 Builder 模式。

#### 任务 2.2 — 将 `AgentHook` 从 6 个方法扩展到 36 个方法
**文件：** `lyjew.com.lyclaw.react.AgentHook`（修改现有文件）

所有新方法均为 `default` 空操作，因此现有的 5 个钩子实现无需修改即可编译通过。

**新增钩子生命周期点（按阶段分组）：**

**请求前（管道之前）：**
```
7.  onAgentResolve(AgentContext)          — 配置解析后，管道开始前
8.  onBootstrapLoad(AgentContext, String) — AGENTS.md/SOUL.md 加载后
9.  onContextInjection(AgentContext)      — 引导内容注入到消息列表后
10. onSessionCreate(AgentContext)         — 当新会话创建时
```

**管道阶段钩子（每个阶段）：**
```
11. onStageStart(AgentContext, String stageName)      — 任意阶段开始前
12. onStageComplete(AgentContext, String stageName)    — 任意阶段完成后
13. onStageError(AgentContext, String stageName, Throwable)
14. onContextBuild(AgentContext)                       — 特定于 ContextBuild 阶段
15. onSecurityCheck(AgentContext)                      — 特定于 SecurityCheck 阶段
16. onPlanExecution(AgentContext)                      — 特定于 PlanExecution 阶段
17. onRespondStart(AgentContext)                       — Respond 阶段开始前
18. onRespondComplete(AgentContext)                    — Respond 阶段完成后
19. onReflection(AgentContext)                         — 特定于 Reflection 阶段
20. onCompaction(AgentContext)                         — 当压缩执行时
```

**ReAct 循环钩子（每次迭代）：**
```
21. onReActIterationStart(AgentContext, int iteration)
22. onReActIterationEnd(AgentContext, int iteration)
23. onToolCallStart(AgentContext, ToolCall)
24. onToolCallComplete(AgentContext, ToolCall, String result)
25. onToolCallError(AgentContext, ToolCall, Throwable)
```

**子智能体钩子：**
```
26. onSubagentSpawn(AgentContext, String childAgentId)
27. onSubagentComplete(AgentContext, String childAgentId, String result)
28. onSubagentError(AgentContext, String childAgentId, Throwable)
```

**流式输出钩子：**
```
29. onBlockStream(AgentContext, String block)     — 每个合并的文本输出块
30. onTypingIndicator(AgentContext)               — 输入指示器发送时
```

**请求后：**
```
31. onAgentFinalize(AgentContext, AgentFinalizeResult)
32. onHeartbeat(AgentContext, HeartbeatConfig)
33. onSessionArchive(AgentContext)
```

**错误与生命周期：**
```
34. onMaxRetriesExceeded(AgentContext)
35. onContextOverflow(AgentContext, int currentTokens, int maxTokens)
36. onAgentTerminate(AgentContext, String reason)
```

#### 任务 2.3 — 创建 `AgentFinalizeResult`、`HookDecision`、`HookRegistration`
**包：** `lyjew.com.lyclaw.react`

**`AgentFinalizeResult`：**
```java
public record AgentFinalizeResult(
    String finalResponse,
    int totalTokens,
    int totalToolCalls,
    int successfulToolCalls,
    int failedToolCalls,
    int reActIterations,
    long durationMs,
    boolean terminatedEarly,
    String terminationReason,
    Map<String, Object> metadata
) {}
```

**`HookDecision`** — 允许钩子发出特殊操作信号：
```java
public enum HookDecision {
    CONTINUE,       // 正常流程
    SKIP_STAGE,     // 跳过当前阶段
    RETRY,          // 重试当前阶段
    TERMINATE,      // 终止管道
    DELEGATE        // 委托给子智能体
}
```

**`HookRegistration`** — 允许按名称注册/注销钩子：
```java
public record HookRegistration(String hookName, AgentHook hook, int priority) {}
```

#### 任务 2.4 — 创建 `HookRegistry`
**包：** `lyjew.com.lyclaw.react`  
**文件：** `HookRegistry.java`

集中注册中心，按名称管理所有钩子实例，提供：
- `register(HookRegistration)` / `unregister(String hookName)`
- `dispatchBeforeRequest(AgentContext)` — 按顺序调用所有 `beforeRequest` 钩子
- `dispatchOnStageStart(AgentContext, String)` — 调用所有 `onStageStart` 钩子
- ...（每个钩子生命周期点一个分发方法）
- `getHooksForLifecyclePoint(String)` — 返回特定生命周期点的有序列表
- 支持条件钩子（`Predicate<AgentContext>` 守卫）

**设计：** 每个生命周期点使用 `CopyOnWriteArrayList` 以确保线程安全的注册。每次分发时按优先级排序（缓存至注册发生变化时）。

#### 任务 2.5 — 更新 `AgentInvocationHandler` 以支持全部 36 个钩子
**文件：** `lyjew.com.lyclaw.react.AgentInvocationHandler`（修改现有文件）

从内联钩子分发重构为基于 `HookRegistry` 的分发。在以下位置添加钩子调用：

- **配置解析后**（新增）：`onAgentResolve`
- **引导加载后**（新增）：`onBootstrapLoad`
- **每个阶段前后**（新增）：`onStageStart` / `onStageComplete` / `onStageError`
- **ReAct 迭代前后**（新增）：`onReActIterationStart` / `onReActIterationEnd`
- **工具调用前后**（新增）：`onToolCallStart` / `onToolCallComplete` / `onToolCallError`
- **现有节点**：`beforeRequest`、`beforeModel`、`afterModel`、`wrapToolCall`、`wrapToolExecutor`、`afterResult`（保留）

每个钩子调用检查返回类型：如果钩子返回 `HookDecision.TERMINATE`，管道优雅停止。如果返回 `HookDecision.SKIP_STAGE`，跳过当前阶段。如果返回 `HookDecision.RETRY`，重新执行该阶段（最多达到可配置的上限）。

#### 任务 2.6 — 创建 `HookSystemTest`
**文件：** `lyclaw-framework/src/test/java/lyjew/com/lyclaw/react/HookSystemTest.java`

测试用例：
- 全部 36 个钩子在完整管道运行中按正确顺序被调用。
- `HookDecision.TERMINATE` 停止管道并生成 `AgentFinalizeResult`，其中 `terminatedEarly=true`。
- `HookDecision.SKIP_STAGE` 跳过当前阶段。
- `HookDecision.RETRY` 在限制范围内心智阶段。
- 钩子优先级排序得到正确遵循。
- 运行时钩子注册/注销正常工作。
- 现有 5 个钩子仍然正常运行（向后兼容）。
- 某一个钩子的错误不会阻止其他钩子执行。

---

### 第 3-4 周：运行时类型与集成

#### 任务 3.1 — 创建 `AgentRuntimeType` 枚举
**包：** `lyjew.com.lyclaw.react`  
**文件：** `AgentRuntimeType.java`

```java
public enum AgentRuntimeType {
    EMBEDDED,   // 智能体在进程内运行（当前行为）
    ACP         // 智能体通过智能体通信协议远程运行
}
```

#### 任务 3.2 — 创建 ACP 运行时接口
**包：** `lyjew.com.lyclaw.react.acp`

**`AcpRuntime`** — 远程智能体执行接口：
```java
public interface AcpRuntime {
    Flux<AcpRuntimeEvent> execute(AgentContext ctx);
    Mono<AcpRuntimeTurnResult> executeBlocking(AgentContext ctx);
    AcpRuntimeHandle submit(AgentContext ctx);  // 即发即忘，附带句柄
}
```

**`AcpRuntimeHandle`** — 指向正在运行的 ACP 任务的句柄：
```java
public interface AcpRuntimeHandle {
    String getTaskId();
    Flux<AcpRuntimeEvent> events();
    Mono<AcpRuntimeTurnResult> result();
    Mono<Void> cancel();
    boolean isDone();
}
```

**`AcpRuntimeEvent`** — ACP 事件的密封接口：
```java
public sealed interface AcpRuntimeEvent {
    record TextDelta(String text) implements AcpRuntimeEvent {}
    record ToolCall(String name, String arguments) implements AcpRuntimeEvent {}
    record ToolResult(String callId, String result) implements AcpRuntimeEvent {}
    record Error(String message) implements AcpRuntimeEvent {}
    record Done(AcpRuntimeTurnResult result) implements AcpRuntimeEvent {}
}
```

**`AcpRuntimeTurnResult`：**
```java
public record AcpRuntimeTurnResult(
    String finalResponse,
    int tokensUsed,
    List<ToolCallRecord> toolCalls,
    long durationMs
) {}
```

#### 任务 3.3 — 创建 `DefaultAcpRuntime`
**包：** `lyjew.com.lyclaw.react.acp`  
**文件：** `DefaultAcpRuntime.java`

基于 HTTP 的 ACP 客户端，使用 Spring `WebClient`。连接到远程智能体服务器端点，发送以 JSON 序列化的智能体上下文，接收 `AcpRuntimeEvent` 的 SSE 流。

**配置：** `lyclaw.acp.base-url`、`lyclaw.acp.timeout`、`lyclaw.acp.retry`。

#### 任务 3.4 — 重构 `AgentProxyFactory` 以支持完整配置 + 运行时类型
**文件：** `lyjew.com.lyclaw.react.AgentProxyFactory`（修改现有文件）

变更内容：
- 在构造函数中接受 `AgentDefaultsConfig`（新增重载，保留旧构造函数）。
- 从注解 + 默认值 + 来源内部解析 `ResolvedAgentConfig`。
- 根据 `resolvedConfig.getRuntimeType()` 选择 `AcpRuntime` 或嵌入式执行。
- 通过 `ctx.setResolvedConfig(...)` 将已解析的配置注入 `AgentContext`。
- 向 `AgentInvocationHandler` 传递 `HookRegistry` 而非原始的 `List<AgentHook>`。

**向后兼容性：** 保留 4 参数构造函数 `(ChatFacade, ReActEngine, ToolRegistry)`。新增 Builder API：
```java
AgentProxyFactory.builder()
    .chatFacade(chatFacade)
    .reActEngine(reActEngine)
    .toolRegistry(toolRegistry)
    .defaultsConfig(defaultsConfig)
    .hooks(hookRegistry)
    .stages(customStages)
    .build();
```

#### 任务 3.5 — 更新 AgentInterfaceProcessor (BFPP)
**文件：** 搜索现有的处理 `@Agent` 注解 Bean 的 `BeanFactoryPostProcessor` 或 `BeanPostProcessor`；如不存在则创建。

变更内容：
- 读取所有新的 `@Agent` 注解字段。
- 将每个智能体及其完整元数据注册到 `AgentRegistry`。
- 从 `application.yml` 填充 `AgentDefaultsConfig`。
- 验证注解字段与默认值的一致性（冲突时发出警告，不报错）。

#### 任务 3.6 — 集成测试：使用新配置 + 钩子的完整管道
**文件：** `lyclaw-framework/src/test/java/lyjew/com/lyclaw/react/FullPipelineIntegrationTest.java`

测试流程：
1. 定义包含扩展字段的 `@Agent` 接口。
2. 配置 `application.yml` 中的 `lyclaw.agent.defaults`。
3. 通过 `HookRegistry` 注册自定义钩子。
4. 调用智能体方法。
5. 验证：配置解析、钩子调用顺序、管道阶段执行、响应内容。

#### 任务 3.7 — 迁移现有 5 个钩子实现
**需验证的文件（无需修改代码）：**
- `SecurityCheckHook.java` (order=10)
- `SandboxHook.java` (order=20)
- `ApprovalHook.java` (order=30)
- `PlanningHook.java` (order=40)
- `OutputGuardHook.java` (order=90)

所有新的 `AgentHook` 方法均为 `default` 空操作，因此这 5 个实现无需任何更改。需明确记录此点。

#### 任务 3.8 — 文档
**文件：**
- 更新 `@Agent` 注解的 Javadoc，包含所有新字段。
- 更新 `AgentHook` 的 Javadoc，包含全部 36 个生命周期点及执行顺序。
- 为 `lyjew.com.lyclaw.react` 添加 package-info.java，含架构概述。
- 为 `lyjew.com.lyclaw.react.acp` 添加 package-info.java。

---

## 3. 阶段 2 详细任务列表 — 子智能体 + 模型

**目标：** 启用分层智能体委托（父智能体生成子智能体）以及适当的模型目录，支持回退链、思考/推理级别解析和自动回退探测。

**依赖：** 需要阶段 1 中的 `ResolvedAgentConfig` 和扩展后的 `AgentContext`。

---

### 第 5-7 周：子智能体系统

#### 任务 3.1 — 创建 `SubagentConfig`
**包：** `lyjew.com.lyclaw.agent.subagent`  
**文件：** `SubagentConfig.java`

```java
public record SubagentConfig(
    String agentId,              // 要生成的目标智能体
    String task,                 // 传递给子智能体的任务描述
    int maxTurns,               // 子智能体最大 ReAct 轮次
    boolean inheritContext,      // 子智能体是否继承父智能体的上下文
    boolean isolatedTools,       // 子智能体是否获得全新的工具集
    List<String> toolWhitelist, // 如果隔离，包含哪些工具
    long timeoutMs               // 子智能体最大执行时长（毫秒）
) {}
```

#### 任务 3.2 — 创建 `SubagentSpawner`
**文件：** `SubagentSpawner.java`

核心委托引擎：
```java
public class SubagentSpawner {
    Mono<SubagentResult> spawn(AgentContext parentCtx, SubagentConfig config);
    Flux<ServerSentEvent<String>> spawnStreaming(AgentContext parentCtx, SubagentConfig config);
}
```

**流程：**
1. 验证 `allowAgents` 白名单 — 子智能体必须在父智能体的白名单中。
2. 检查 `maxSpawnDepth` — 父智能体当前深度 + 1 不得超过配置值。
3. 获取 `maxChildrenPerAgent` 信号量许可。
4. 创建带有嵌套会话键的子 `AgentContext`（`parentId/childId/turn`）。
5. 通过 `AgentRegistry` 执行子智能体（相同管道，独立上下文）。
6. 释放信号量，归档子智能体会话。

#### 任务 3.3 — 注册 `"delegate_to_agent"` 作为内置工具
**文件：** `DelegateToAgentTool.java`

一个 `@Tool` 注解的类，将子智能体委托作为常规工具暴露给 LLM：
```json
{
  "name": "delegate_to_agent",
  "description": "将子任务委托给另一个专业智能体",
  "parameters": {
    "agent_name": "string（必填）— 目标智能体的名称",
    "task": "string（必填）— 子任务描述",
    "max_turns": "integer（可选）— 最大 ReAct 迭代次数",
    "inherit_context": "boolean（可选）— 子智能体是否可见父智能体消息"
  }
}
```

工具实现调用 `SubagentSpawner.spawn()` 并返回子智能体的结果。

#### 任务 3.4 — 实现 `allowAgents` 白名单检查
**位置：** `SubagentSpawner.validateWhitelist()`

**逻辑：**
- 如果父智能体的 `allowAgents` 为空 → 不允许委托。
- 如果父智能体的 `allowAgents` 包含 `"*"` → 允许任意智能体。
- 否则，子智能体名称必须在列表中。
- 违规 → 抛出 `SubagentDelegationDeniedException` 并附带原因。

#### 任务 3.5 — 实现 `maxSpawnDepth` 递归守卫
**逻辑：**
- 父智能体上下文携带 `currentDepth`（根为 0）。
- 子智能体上下文获得 `currentDepth = parent.currentDepth + 1`。
- 如果 `currentDepth > maxSpawnDepth` → 抛出 `MaxSpawnDepthExceededException`。
- 深度可按智能体通过注解配置；系统默认值 = 3。

#### 任务 3.6 — 实现 `maxChildrenPerAgent` 并发守卫
**逻辑：**
- 每个智能体上下文有一个 `Semaphore(maxChildrenPerAgent)`。
- `spawn()` 在创建子智能体前获取许可，子智能体完成后释放。
- 如果在超时时间内无可用许可 → 抛出 `TooManyChildrenException`。
- 许可在 `finally` 块中释放，即使子智能体出错。

#### 任务 3.7 — 实现子智能体会话管理
**逻辑：**
- 会话键：`rootSessionId/agentName/turnNumber`（嵌套层次结构）。
- 完成后自动归档子智能体会话（可配置保留策略）。
- 父智能体上下文跟踪 `activeSubagentIds` 用于监控/取消。
- `AgentContext.getActiveSubagentIds()` 返回不可修改视图。

#### 任务 3.8 — 集成到 `RespondStage`
**文件：** `lyjew.com.lyclaw.pipeline.stage.RespondStage`（修改）

当 `ReActEngine` 发出 `"delegate_to_agent"` 的工具调用时：
- 路由到 `SubagentSpawner` 而非 `ToolRegistry`。
- 将子智能体的 SSE 事件作为嵌套工具结果流式传输。
- 将子智能体结果记录在父智能体的 `toolResults` 中。

#### 任务 3.9 — 创建 `SubagentSpawnerTest`
**文件：** `lyclaw-framework/src/test/java/lyjew/com/lyclaw/agent/subagent/SubagentSpawnerTest.java`

测试用例：
- 成功委托：父 → 子 → 结果返回。
- 白名单违规：智能体不在 `allowAgents` 中 → 异常。
- 深度超限：深度为 3 的子智能体尝试生成深度 4 → 异常。
- 并发限制：在 `maxChildrenPerAgent=5` 时生成 6 个子智能体 → 第 6 个阻塞/报错。
- 嵌套委托：父 → 子 → 孙（在限制范围内）正常工作。
- 子智能体错误优雅传播给父智能体。
- 子智能体超时终止子智能体并返回部分结果。
- 会话键正确嵌套。

---

### 第 7-9 周：模型目录与解析

#### 任务 3.10 — 创建 `ModelCatalogEntry`
**包：** `lyjew.com.lyclaw.chat.catalog`  
**文件：** `ModelCatalogEntry.java`

```java
public record ModelCatalogEntry(
    String modelId,              // 例如，"deepseek-v4-pro"
    String provider,             // 例如，"deepseek"
    String displayName,          // 例如，"DeepSeek V4 Pro"
    ModelCapabilities capabilities, // 视觉、音频、工具使用等能力
    int contextWindow,           // 最大 token 数
    int maxOutputTokens,         // 最大生成 token 数
    boolean supportsThinking,    // 扩展思考支持
    boolean supportsReasoning,   // 推理/思维链支持
    boolean supportsStreaming,   // SSE 流式支持
    double costPer1kInput,       // 定价（可选）
    double costPer1kOutput,
    Map<String, Object> metadata // 提供商特定数据
) {}
```

#### 任务 3.11 — 创建 `ModelCatalog`
**文件：** `ModelCatalog.java`

```java
public class ModelCatalog {
    void register(ModelCatalogEntry entry);
    Optional<ModelCatalogEntry> lookup(String modelId);
    List<ModelCatalogEntry> listByProvider(String provider);
    List<ModelCatalogEntry> listByCapability(ModelCapabilities required);
    List<ModelCatalogEntry> listAll();
    void loadFromFile(Path yamlPath);           // 从文件加载
    void discoverFromProviders();               // 通过提供商 API 发现
}
```

**存储：** `ConcurrentHashMap<String, ModelCatalogEntry>`，以 `modelId` 为键。

**文件格式**（`models.yaml`）：
```yaml
models:
  - modelId: "deepseek-v4-pro"
    provider: "deepseek"
    contextWindow: 128000
    supportsThinking: true
    supportsStreaming: true
  - modelId: "deepseek-v4-flash"
    provider: "deepseek"
    contextWindow: 128000
    supportsStreaming: true
```

#### 任务 3.12 — 创建 `AgentModelConfig`
**包：** `lyjew.com.lyclaw.config`  
**文件：** `AgentModelConfig.java`

```java
public record AgentModelConfig(
    String primary,                    // 主模型 ID
    List<String> fallbacks,            // 有序回退链
    AgentToolModelConfig toolModels    // 工具专用模型
) {}
```

#### 任务 3.13 — 创建 `AgentToolModelConfig`
**文件：** `AgentToolModelConfig.java`

```java
public record AgentToolModelConfig(
    String imageModel,    // 图像生成/分析工具模型
    String videoModel,    // 视频工具模型
    String musicModel,    // 音频/音乐工具模型
    String pdfModel       // PDF 处理工具模型
) {}
```

#### 任务 3.14 — 创建 `ModelResolutionService`
**包：** `lyjew.com.lyclaw.chat`  
**文件：** `ModelResolutionService.java`

集中式服务，处理所有模型解析逻辑：
```java
public class ModelResolutionService {
    ChatModel resolvePrimary(AgentModelConfig config);
    List<ChatModel> resolveFallbackChain(AgentModelConfig config);
    ChatModel resolveForTool(String toolName, AgentToolModelConfig config);
    ThinkingLevel resolveThinking(ResolvedAgentConfig config);
    ReasoningLevel resolveReasoning(ResolvedAgentConfig config);
    VerboseLevel resolveVerbose(ResolvedAgentConfig config);
}
```

**解析顺序：**
1. 注解覆盖（最高优先级）
2. 运行时覆盖（来自上下文属性）
3. 智能体默认值（配置文件）
4. 系统默认值（最低优先级）

#### 任务 3.15 — 实现自动回退探测
**包：** `lyjew.com.lyclaw.chat.fallback`  
**文件：** `AutoFallbackProbe.java`

```java
public class AutoFallbackProbe {
    // 对回退链中的每个模型用轻量请求进行探测
    // 结果带 TTL 缓存
    Mono<ChatModel> probe(List<String> modelIds);
    FallbackState getState(String modelId);
}
```

**`FallbackState`：**
```java
public enum FallbackState { HEALTHY, DEGRADED, UNAVAILABLE, UNKNOWN }
```

**探测策略：**
- 启动时，用简单 token 计数请求探测所有已注册模型。
- 主模型失败时，立即探测回退链以找到第一个健康的模型。
- 探测结果缓存 30 秒（可配置）。
- 每 60 秒后台健康检查（可配置）。

#### 任务 3.16 — 实现思考/推理/详细级别解析
**位置：** `ModelResolutionService`

**级别枚举：**
```java
public enum ThinkingLevel { OFF, LOW, MEDIUM, HIGH, MAX }
public enum ReasoningLevel { OFF, BRIEF, STANDARD, DETAILED }
public enum VerboseLevel { QUIET, NORMAL, VERBOSE, DEBUG }
```

**传递到 ChatModel：**
- 构建 `ChatRequest` 时，将 `thinking_level`、`reasoning_level`、`verbose_level` 注入请求参数。
- 每个 `ChatModel` 实现读取这些参数并传递给提供商 API。
- 提供商无关：不支持某个级别的模型会优雅地忽略。

#### 任务 3.17 — 创建 `ProviderDiscovery` 接口 + OpenAI 实现
**包：** `lyjew.com.lyclaw.chat.discovery`  
**文件：** `ProviderDiscovery.java`

```java
public interface ProviderDiscovery {
    List<ModelCatalogEntry> discover();
    boolean supports(String provider);
}
```

**`OpenAIProviderDiscovery`：**
- 调用 `/v1/models` 端点。
- 将 OpenAI 模型 ID 映射为具有已知能力的 `ModelCatalogEntry`。

#### 任务 3.18 — 更新 `ChatFacade` 和 `DefaultChatFacade`
**文件：** `ChatFacade.java`、`DefaultChatFacade.java`（修改）

`ChatFacade` 上的新方法：
```java
ModelCatalog getModelCatalog();
ModelResolutionService getModelResolution();
ChatModel resolveWithFallback(AgentModelConfig config);
```

`DefaultChatFacade` 变更：
- 在构造函数中接受 `ModelCatalog` 和 `ModelResolutionService`。
- 在 `chat(ChatRequest)` 中，使用 `ModelResolutionService` 解析模型 + 思考/推理/详细级别。
- 模型出错时，通过 `AutoFallbackProbe` 触发回退链。

#### 任务 3.19 — 创建 `RunRetriesConfig` + `RunRetryManager`
**包：** `lyjew.com.lyclaw.retry`  
**文件：** `RunRetriesConfig.java`、`RunRetryManager.java`

```java
public record RunRetriesConfig(
    int maxRetries,           // 默认 2（原为硬编码的 MAX_REFLECTION_RETRIES）
    double retryThreshold,    // 默认 0.6
    long backoffMs,           // 基础退避
    double backoffMultiplier, // 指数因子
    List<Class<? extends Throwable>> retryableExceptions
) {}

public class RunRetryManager {
    boolean shouldRetry(int attempt, double score, int failCount, RunRetriesConfig config);
    long getBackoffMs(int attempt, RunRetriesConfig config);
}
```

#### 任务 3.20 — 用 `RunRetryManager` 替换硬编码的 `MAX_REFLECTION_RETRIES`
**文件：** `AgentInvocationHandler.java`（修改）

替换：
```java
// Before
private static final int MAX_REFLECTION_RETRIES = 2;
private static final double REFLECTION_RETRY_THRESHOLD = 0.6;
```

替换为：
```java
// After
private RunRetriesConfig retriesConfig;  // 注入，默认值匹配旧行为
```

#### 任务 3.21 — 模型解析、回退链、自动探测的测试
**文件：**
- `ModelResolutionServiceTest.java`
- `FallbackChainTest.java`
- `AutoFallbackProbeTest.java`

测试用例：
- 主模型从注解正确解析。
- 回退链按声明顺序解析。
- 思考/推理/详细级别按正确优先级解析（注解 > 运行时 > 默认 > 系统）。
- 自动探测检测到不健康的模型并切换到回退模型。
- 探测缓存过期并重新探测。
- 自定义工具模型解析（图像工具使用图像模型）。
- 未知模型 ID 抛出信息性异常。
- 空的回退链返回错误，而非 NPE。

---

### 第 9-10 周：集成与文档

#### 任务 3.22 — 集成测试：子智能体委托链
**文件：** `SubagentDelegationChainTest.java`

测试：父 → 子 → 孙，每个拥有自己的智能体配置和工具集。
- 验证深度跟踪（父 depth=0，子=1，孙=2）。
- 验证会话键嵌套。
- 验证孙的结果传播回父。
- 验证 `maxSpawnDepth=2` 阻止曾孙生成。

#### 任务 3.23 — 集成测试：多模型回退
**文件：** `MultiModelFallbackTest.java`

测试：将主模型配置为失败，验证回退链被探测，第一个健康模型被使用。
- 验证思考级别在回退中得以保留。
- 验证回退事件发出 SSE 消息。
- 验证重复失败耗尽回退链并产生错误。

#### 任务 3.24 — 文档
- `lyjew.com.lyclaw.agent.subagent` 的 package-info。
- `lyjew.com.lyclaw.chat.catalog` 的 package-info。
- 更新 `ChatFacade` Javadoc，包含模型目录用法。

---

## 4. 阶段 3 详细任务列表 — 上下文 + 引导 + 路由

**目标：** 智能上下文管理（压缩、裁剪）、引导文件加载（AGENTS.md 等）以及多智能体请求路由。

**依赖：** 需要阶段 1 中的 `ResolvedAgentConfig` 和 `AgentContextLimits`。

---

### 第 11-12 周：上下文管理

#### 任务 4.1 — 创建 `CompactionConfig` + `CompactionEngine`
**包：** `lyjew.com.lyclaw.context.compaction`  
**文件：** `CompactionConfig.java`、`CompactionEngine.java`

```java
public record CompactionConfig(
    boolean enabled,             // 通过配置选择加入
    int triggerTokenThreshold,   // 当上下文超过此值时触发压缩（例如 100000）
    int targetTokenCount,        // 压缩到此值（例如 30000）
    boolean preserveSystemPrompt, // 始终保留系统提示词
    boolean preserveRecentMessages, // 保留最后 N 条消息
    int recentMessagesCount,     // N
    String compactionModel       // 用于摘要的模型（可使用更便宜的模型）
) {}
```

**`CompactionEngine`：**
```java
public class CompactionEngine {
    Mono<List<Message>> compact(List<Message> messages, CompactionConfig config);
    CompactionResult compactBlocking(List<Message> messages, CompactionConfig config);
}
```

**算法：**
1. 将消息分为：系统提示词（保留）、早期消息（候选摘要）、最近 N 条消息（保留）。
2. 将早期消息发送到便宜/快速的模型，附带提示词："Summarize the key decisions, facts, and context from this conversation. Preserve all action items and pending tasks."（总结此对话中的关键决策、事实和上下文。保留所有行动项和待处理任务。）
3. 用单条系统风格消息替换早期消息：`[上下文摘要] <summary>`。
4. 验证：结果的 token 数 <= 目标值。
5. 返回 `CompactionResult`，包含压缩前后的 token 计数和摘要文本。

#### 任务 4.2 — 创建 `CompactionStage`
**包：** `lyjew.com.lyclaw.pipeline.stage`  
**文件：** `CompactionStage.java`

新的管道阶段，排序在 `ReflectionStage` 之后（order=4.5，位于 Reflection (4) 和 Metrics (5) 之间，或调整现有顺序）。

**逻辑：**
1. Reflection 完成后（在潜在重试之前），检查消息列表的 token 总数。
2. 如果超过 `triggerTokenThreshold`，运行 `CompactionEngine.compact()`。
3. 用压缩后的列表替换 `AgentContext` 中的消息。
4. 发出 SSE 事件：`compaction_complete`，附带压缩前后的 token 计数。
5. 触发 `onCompaction` 钩子。

**阶段排序更新：** `MetricsStage` 从 order=5 移至 order=6。`CompactionStage` 占据 order=5。

#### 任务 4.3 — 为压缩实现质量守卫
**位置：** `CompactionEngine`

压缩后：
1. 从压缩后的上下文重建一个"测试提示词"。
2. 询问压缩模型："Do you have enough information to continue this task? Respond YES or NO with a brief explanation."（你是否有足够的信息继续此任务？回答 YES 或 NO 并简要说明。）
3. 如果 NO → 以更保守的目标（例如原目标的 1.5 倍）重新压缩。
4. 如果 2 次重试后仍为 NO → 记录警告，继续使用原始（未压缩的）上下文。

#### 任务 4.4 — 实现轮次中上下文压力预检查
**位置：** `RespondStage`（修改）

在每次 ReAct 迭代之前：
- 估算当前消息 + 工具结果的 token 数。
- 如果接近 `maxContextTokens`（例如 >90%）：
  - 如果 `CompactionConfig.enabled`：在轮次中触发压缩。
  - 如果压缩被禁用：截断最旧的非系统消息，并发出警告 SSE 事件。
- 这可以防止因上下文溢出导致的 API 错误。

#### 任务 4.5 — 实现压缩后章节注入
**位置：** `CompactionEngine`

压缩后，注入来自 `AGENTS.md`（在引导时加载）的章节，提醒智能体其身份和约束。这可以防止压缩删除早期身份设定消息后出现"上下文漂移"。

#### 任务 4.6 — 创建 `ContextPruningConfig` + `ContextPruningEngine`
**包：** `lyjew.com.lyclaw.context.pruning`  
**文件：** `ContextPruningConfig.java`、`ContextPruningEngine.java`

```java
public record ContextPruningConfig(
    boolean enabled,
    int maxToolResults,      // 保留的最大工具结果数（最旧的优先裁剪）
    int maxMessages,         // 最大消息总数
    boolean pruneToolErrorsFirst, // 优先裁剪错误消息
    List<String> preserveTools  // 始终保留结果的工具名称列表
) {}
```

**`ContextPruningEngine`：**
精确定点移除单条消息：
- 移除超过 `maxToolResults` 的最旧工具结果。
- 移除超过 `maxMessages` 的最旧消息。
- 始终保留系统提示词。
- 将被裁剪的内容替换为占位符：`[为管理上下文，早期内容已被裁剪]`。

#### 任务 4.7 — 创建 `AgentContextLimits`
**包：** `lyjew.com.lyclaw.config`  
**文件：** `AgentContextLimits.java`

```java
public record AgentContextLimits(
    int maxTokens,           // 总上下文窗口
    int maxSystemPromptTokens, // 系统提示词预算
    int maxBootstrapTokens,  // 引导内容预算
    int maxToolResultsTokens, // 工具结果预算
    int maxMessagesTokens,   // 对话消息预算
    int reserveTokens        // 为模型响应保留的 token
) {}
```

默认值从 `ModelCatalogEntry` 中模型的 `contextWindow` 推导得出。

#### 任务 4.8 — 集成到 `ContextEngine`
**文件：** 搜索现有上下文管理；集成或创建 `ContextEngine.java`。

`ContextEngine` 成为所有上下文操作的单一入口点：
```java
public class ContextEngine {
    List<Message> buildContext(AgentContext ctx);
    List<Message> compact(AgentContext ctx);
    List<Message> prune(AgentContext ctx);
    int estimateTokens(List<Message> messages);
    AgentContextLimits getLimits(AgentContext ctx);
}
```

#### 任务 4.9 — 创建测试
**文件：**
- `CompactionEngineTest.java`
- `ContextPruningEngineTest.java`
- `CompactionStageTest.java`

测试用例：
- 压缩将 token 数降至目标值以下。
- 压缩后系统提示词得以保留。
- 最近 N 条消息得以保留。
- 质量守卫检测到信息丢失。
- 轮次中预检查在溢出前触发压缩。
- 裁剪优先移除最旧消息。
- 受保护工具的结果在裁剪中得以保留。
- Token 估算与实际偏差在 10% 以内。

---

### 第 12-13 周：引导加载

#### 任务 4.10 — 创建 `BootstrapLoader`
**包：** `lyjew.com.lyclaw.bootstrap`  
**文件：** `BootstrapLoader.java`

从智能体工作区目录加载智能体身份和指令文件：

```java
public class BootstrapLoader {
    BootstrapContent load(AgentContext ctx);
    BootstrapContent load(String agentDir, String workspaceDir);
}
```

**`BootstrapContent`：**
```java
public record BootstrapContent(
    String agentsMd,       // AGENTS.md — 核心指令
    String soulMd,         // SOUL.md — 智能体人格
    String bootstrapMd,    // BOOTSTRAP.md — 启动上下文
    String identityMd,     // IDENTITY.md — 智能体身份/名称/头像
    String userMd,         // USER.md — 用户特定覆盖
    String heartbeatMd,    // HEARTBEAT.md — 定期检查指令
    int totalChars,
    Map<String, String> metadata
) {}
```

**文件发现顺序：**
1. `{agentDir}/AGENTS.md`
2. `{workspaceDir}/AGENTS.md`（回退）
3. `{agentDir}/SOUL.md` → `{workspaceDir}/SOUL.md`
4. `{agentDir}/BOOTSTRAP.md` → `{workspaceDir}/BOOTSTRAP.md`
5. `{agentDir}/IDENTITY.md` → `{workspaceDir}/IDENTITY.md`
6. `{agentDir}/USER.md` → `{workspaceDir}/USER.md`
7. `{agentDir}/HEARTBEAT.md` → `{workspaceDir}/HEARTBEAT.md`

每个文件都是可选的；缺失的文件产生调试日志，而非错误。

#### 任务 4.11 — 创建 `BootstrapConfig` + `StartupContextConfig`
**包：** `lyjew.com.lyclaw.config`  
**文件：** `BootstrapConfig.java`

```java
@ConfigurationProperties(prefix = "lyclaw.agent.bootstrap")
public record BootstrapConfig(
    boolean enabled,
    int maxChars,              // 每个文件最大字符数（默认 50000）
    int totalMaxChars,         // 所有文件总最大字符数（默认 200000）
    ContextInjectionPolicy injectionPolicy, // 注入时机
    boolean truncateWithWarning // 截断时发出警告而非报错
) {}
```

#### 任务 4.12 — 实现 `ContextInjectionPolicy`
**文件：** `ContextInjectionPolicy.java`

```java
public enum ContextInjectionPolicy {
    ALWAYS,              // 每次请求都将引导内容注入系统提示词
    CONTINUATION_SKIP,   // 连续轮次跳过注入（会话中已有）
    NEVER                // 从不自动注入（智能体必须显式加载）
}
```

#### 任务 4.13 — 实现带警告的截断
**位置：** `BootstrapLoader`

如果引导内容总量超过 `totalMaxChars`：
1. 按优先级顺序加载文件（AGENTS.md 优先，HEARTBEAT.md 最后）。
2. 截断最后加载的文件以适应预算。
3. 添加系统消息：`[注意：部分引导文件被截断以适应上下文预算。原始大小：...]`
4. 记录详细警告日志。

#### 任务 4.14 — 增强 `ContextBuildStage` 以加载引导内容
**文件：** `ContextBuildStage.java`（修改）

加载会话和记忆后：
1. 调用 `BootstrapLoader.load(ctx)`。
2. 将 `BootstrapContent` 存储在 `ctx.setAttribute("bootstrapContent", content)`。
3. 如果 `ContextInjectionPolicy.ALWAYS` 或 `CONTINUATION_SKIP`（首轮）：
   - 将引导内容作为系统消息前置（在用户消息之前）。
4. 需要时应用截断。
5. 发出 SSE 事件：`bootstrap_loaded`，附带文件名和大小。

#### 任务 4.15 — 创建 `BootstrapLoaderTest`
**文件：** `BootstrapLoaderTest.java`

测试用例：
- 从 agentDir 加载 AGENTS.md。
- 当 agentDir 没有文件时回退到 workspaceDir。
- 缺失的可选文件不报错。
- 截断遵循 totalMaxChars。
- 发出截断警告。
- ContextInjectionPolicy.ALWAYS 在每轮注入。
- ContextInjectionPolicy.CONTINUATION_SKIP 在会话延续时跳过。
- ContextInjectionPolicy.NEVER 从不注入。
- 文件编码问题被优雅处理。
- 大文件（>10MB）被拒绝并给出明确错误。

---

### 第 13-14 周：路由与身份

#### 任务 4.16 — 创建 `AgentRouteBinding` + `AgentAcpBinding` + `AgentBindingMatch`
**包：** `lyjew.com.lyclaw.routing`  
**文件：** `AgentRouteBinding.java`、`AgentAcpBinding.java`、`AgentBindingMatch.java`

```java
public record AgentRouteBinding(
    String pattern,              // URL 路径模式，例如 "/api/agent/{agentName}"
    String agentName,            // 目标智能体
    boolean streaming,           // 此路由是否使用 SSE 流式
    Map<String, String> headers  // 要传递的额外请求头
) {}

public record AgentAcpBinding(
    String pattern,
    String acpEndpoint,          // 远程 ACP 服务器 URL
    String agentName,
    boolean streaming
) {}

public record AgentBindingMatch(
    AgentRouteBinding binding,
    Map<String, String> pathVariables
) {}
```

#### 任务 4.17 — 创建 `AgentRouter`
**文件：** `AgentRouter.java`

```java
public class AgentRouter {
    Optional<AgentBindingMatch> match(String path);
    void register(AgentRouteBinding binding);
    void register(AgentAcpBinding binding);
    void unregister(String pattern);
    List<AgentRouteBinding> listRoutes();
    List<AgentAcpBinding> listAcpBindings();
}
```

**模式匹配：** 使用 Spring 的 `AntPathMatcher` 进行 glob 风格模式匹配：
- `/api/agent/**` — 所有智能体
- `/api/agent/code-reviewer` — 特定智能体
- `/api/agent/{agentName}` — 路径变量提取

#### 任务 4.18 — 在 `application.yml` 中创建 `AgentRoutingConfig`
```yaml
lyclaw:
  agent:
    routing:
      enabled: true
      defaultAgent: "general-assistant"
      routes:
        - pattern: "/api/agent/code-reviewer"
          agentName: "code-reviewer"
          streaming: true
        - pattern: "/api/agent/data-analyst"
          agentName: "data-analyst"
          streaming: true
      acp:
        - pattern: "/api/acp/remote-agent"
          acpEndpoint: "https://remote.acp.example.com"
          agentName: "remote-agent"
          streaming: true
```

#### 任务 4.19 — 更新 `ChatController` 以支持多智能体路由
**文件：** 搜索现有的处理聊天请求的 controller；进行更新。

之前：
```java
@PostMapping("/chat")
Flux<ServerSentEvent<String>> chat(@RequestBody ChatRequest request) {
    return agent.invoke(request.getUserMessage());
}
```

之后：
```java
@PostMapping("/chat")
Flux<ServerSentEvent<String>> chat(@RequestBody ChatRequest request,
                                    @RequestHeader Map<String, String> headers) {
    String agentName = resolveAgentName(request, headers); // 来自路由或请求
    AgentHandle agent = agentRegistry.get(agentName);
    return agent.invoke(request.getUserMessage());
}

@PostMapping("/agent/{agentName}/chat")
Flux<ServerSentEvent<String>> agentChat(@PathVariable String agentName,
                                         @RequestBody ChatRequest request) {
    AgentHandle agent = agentRegistry.get(agentName);
    return agent.invoke(request.getUserMessage());
}
```

#### 任务 4.20 — 创建 `IdentityConfig` + `AgentAvatarResolution`
**包：** `lyjew.com.lyclaw.identity`  
**文件：** `IdentityConfig.java`、`AgentAvatarResolution.java`

```java
public record IdentityConfig(
    String name,              // 显示名称
    String avatar,            // 头像 URL 或 emoji
    String namePrefix,        // 响应中前置在智能体名称前的文本（例如 "🤖"）
    String messagePrefix,     // 每条消息前的前置文本（例如 "[CodeReviewer] "）
    String color,             // UI 强调色
    String description        // 简介/角色描述
) {}
```

```java
public record AgentAvatarResolution(
    String avatarUrl,
    String fallbackEmoji,
    String color
) {}
```

#### 任务 4.21 — 创建 `IdentityResolver`
**文件：** `IdentityResolver.java`

```java
public class IdentityResolver {
    IdentityConfig resolve(AgentContext ctx);
    IdentityConfig resolveFromWorkspace(String agentDir);
    IdentityConfig resolveFromAnnotation(Agent annotation);
    IdentityConfig resolveFromConfig(String agentName);
}
```

**优先级：**
1. agentDir 中的 `IDENTITY.md` 文件
2. `@Agent` 注解（新字段）
3. `application.yml` 配置
4. 默认值（智能体名称作为显示名称，无头像）

#### 任务 4.22 — 将身份信息集成到响应格式化中
**位置：** `RespondStage` 和 SSE 事件发送。

在发送 SSE `message` 事件时，前置 `messagePrefix` 并使用智能体身份格式化：
```json
{
  "event": "message",
  "data": "[CodeReviewer] 在提供的代码中发现 3 个问题...",
  "agent": {
    "name": "CodeReviewer",
    "avatar": "🔍",
    "color": "#4A90D9"
  }
}
```

#### 任务 4.23 — 路由和身份的测试
**文件：**
- `AgentRouterTest.java`
- `IdentityResolverTest.java`

测试用例：
- 路由器匹配精确模式。
- 路由器匹配 glob 模式。
- 路由器提取路径变量。
- 无匹配时路由器返回空。
- 身份从 IDENTITY.md 解析。
- 身份从注解解析。
- 身份从配置解析。
- 身份回退到默认值。
- 响应格式化包含身份元数据。

---

## 5. 阶段 4 详细任务列表 — 流式 + 沙箱 + 心跳

**目标：** 类人化的流式输出（文本块合并、输入指示器、延迟）、容器化沙箱执行以及智能体定期唤醒的心跳机制。

**依赖：** 需要阶段 1 的 `ResolvedAgentConfig` 和阶段 2 的 `RespondStage` 集成。

---

### 第 15-16 周：流式增强

#### 任务 5.1 — 创建 `BlockStreamingConfig` + `BlockStreamingEngine`
**包：** `lyjew.com.lyclaw.stream`  
**文件：** `BlockStreamingConfig.java`、`BlockStreamingEngine.java`

```java
public record BlockStreamingConfig(
    boolean enabled,           // 启用文本块合并
    int maxChars,              // 每块最大字符数（默认 80）
    long maxIdleMs,            // 刷新前最大空闲时间（默认 150ms）
    boolean preserveNewlines,  // 可能时在换行处切分块
    boolean stripThinking,     // 从输出中去除 <thinking> 标签
    boolean stripCodeFences    // 从块中去除 ``` 标记
) {}
```

**`BlockStreamingEngine`：**
```java
public class BlockStreamingEngine {
    Flux<String> coalesce(Flux<String> tokenStream, BlockStreamingConfig config);
    Flux<ServerSentEvent<String>> coalesceToSSE(Flux<String> tokenStream, BlockStreamingConfig config);
}
```

**合并算法：**
1. 将传入的 token（字符）缓冲到 `StringBuilder` 中。
2. 刷新时机：缓冲区达到 `maxChars`，或距离上次 token 经过 `maxIdleMs`。
3. 如果 `preserveNewlines`，也在 `\n` 边界处刷新。
4. 如果 `stripThinking`，过滤掉 `<thinking>` 和 `</thinking>` 标签之间的内容。
5. 将每个刷新的块作为单个 SSE `message` 事件发出。

#### 任务 5.2 — 实现 `HumanDelayConfig` + `HumanDelayController`
**文件：** `HumanDelayConfig.java`、`HumanDelayController.java`

```java
public record HumanDelayConfig(
    boolean enabled,
    long minDelayMs,        // 块之间的最小延迟（默认 100ms）
    long maxDelayMs,        // 块之间的最大延迟（默认 400ms）
    double variability,     // 随机因子 (0.0-1.0)
    boolean delayAfterNewlines, // 段落分隔后更长延迟
    long newlineExtraMs     // 换行后额外延迟（默认 200ms）
) {}
```

**`HumanDelayController`：**
```java
public class HumanDelayController {
    Mono<Void> delay();                    // 在最小和最大之间的随机延迟
    Mono<Void> delayAfterNewline();        // 段落分隔的额外延迟
}
```

延迟计算：`minDelay + random() * (maxDelay - minDelay) * variability`，如果块以 `\n\n` 结尾则加上 `newlineExtraMs`。

#### 任务 5.3 — 实现 `TypingIndicatorController`
**文件：** `TypingIndicatorController.java`

```java
public enum TypingMode {
    NEVER,      // 从不显示输入指示器
    INSTANT,    // 首条消息前立即显示
    THINKING,   // 仅在 <thinking> 块内显示（如果 stripThinking=false）
    MESSAGE     // 每个消息块前显示
}
```

**`TypingIndicatorController`：**
```java
public class TypingIndicatorController {
    Flux<ServerSentEvent<String>> wrap(Flux<ServerSentEvent<String>> stream,
                                        TypingMode mode);
}
```

逻辑：
- `NEVER`：直通，不做修改。
- `INSTANT`：首条消息前发出 `{"event": "typing", "data": "start"}`，最后一条后发出 `"stop"`。
- `THINKING`：在 `<thinking>` 标签内时发出输入指示器。
- `MESSAGE`：每个块之前发出输入指示器，每个块之后停止。

#### 任务 5.4 — 集成到 `RespondStage` SSE 输出
**文件：** `RespondStage.java`（修改）

为 SSE 流创建处理管道：
```
来自 ReActEngine 的原始 SSE 流
  → BlockStreamingEngine.coalesceToSSE()     [文本块合并]
  → HumanDelayController.delay()            [类人延迟]
  → TypingIndicatorController.wrap()        [输入指示器]
  → 发送到客户端的最终 SSE 流
```

管道中的每个阶段从 `ResolvedAgentConfig` 读取配置，并且可以通过配置禁用（直通）。

#### 任务 5.5 — 创建流式测试
**文件：**
- `BlockStreamingTest.java`
- `HumanDelayTest.java`
- `TypingIndicatorTest.java`

测试用例：
- 文本块合并：200 字符输入 → 每块 maxChars 个字符的 N 个块。
- 在空闲超时时刷新合并。
- 配置时合并保留换行符。
- `stripThinking=true` 时去除 `<thinking>` 标签。
- 块之间的类人延迟在 [min, max] 范围内。
- 换行后额外延迟。
- 输入指示器在首条消息前发出（INSTANT 模式）。
- 输入指示器不发出（NEVER 模式）。
- 端到端管道：原始 token → 合并为块 → 延迟 → 输入指示 → SSE。

---

### 第 16-17 周：沙箱执行

#### 任务 5.6 — 创建 `AgentSandboxConfig`
**包：** `lyjew.com.lyclaw.sandbox`  
**文件：** `AgentSandboxConfig.java`

```java
@ConfigurationProperties(prefix = "lyclaw.agent.sandbox")
public record AgentSandboxConfig(
    boolean enabled,              // 总开关
    String runtime,               // "docker" 或 "podman"
    String defaultImage,          // 例如 "ubuntu:22.04"
    Map<String, String> agentImages, // 每个智能体的镜像覆盖
    boolean readOnlyWorkspace,    // 以只读方式挂载工作区
    boolean writableTmp,          // 以可写方式挂载 /tmp
    long memoryLimitMb,           // 内存限制
    long cpuLimit,                // CPU 限制（每核 0.0-1.0）
    long timeoutSeconds,          // 最大执行时间
    List<String> commandWhitelist, // 允许的命令（空 = 允许全部）
    List<String> commandBlacklist, // 禁止的命令
    boolean networkDisabled,      // 禁用容器网络
    boolean pullImageOnStart       // 执行前拉取最新镜像
) {}
```

#### 任务 5.7 — 创建 `SandboxExecutionService`
**文件：** `SandboxExecutionService.java`

使用 `docker-java` SDK（或命令行回退）：
```java
public class SandboxExecutionService {
    SandboxExecutionResult execute(SandboxExecutionRequest request);
    Mono<SandboxExecutionResult> executeAsync(SandboxExecutionRequest request);
    boolean isAvailable();
    void prewarm(String image);
}
```

**`SandboxExecutionRequest`：**
```java
public record SandboxExecutionRequest(
    String image,
    String command,
    List<String> args,
    String workdir,
    Map<String, String> env,
    Map<String, String> volumeMounts,
    long timeoutSeconds
) {}
```

**`SandboxExecutionResult`：**
```java
public record SandboxExecutionResult(
    int exitCode,
    String stdout,
    String stderr,
    long durationMs,
    boolean timedOut
) {}
```

#### 任务 5.8 — 实现文件系统桥接
**位置：** `SandboxExecutionService`

- 将工作区目录以只读方式挂载到容器内的 `/workspace`。
- 将临时目录以可读写方式挂载到容器内的 `/tmp/sandbox`。
- 执行开始时，将需要的文件从工作区复制到 `/tmp/sandbox`。
- 执行结束时，将结果从 `/tmp/sandbox/output` 复制回工作区（如需要）。
- 执行后清理临时目录（可配置保留策略）。

#### 任务 5.9 — 实现命令白名单/黑名单
**位置：** `SandboxExecutionService`

容器创建前：
1. 解析命令字符串，提取基础命令（第一个单词）。
2. 如果 `commandWhitelist` 非空：命令必须在白名单中。
3. 如果 `commandBlacklist` 非空：命令必须不在黑名单中。
4. 如果白名单为空且黑名单为空：允许全部（沙箱隔离已足够）。
5. 违规 → 抛出 `CommandNotAllowedException`。

#### 任务 5.10 — 更新 `SandboxHook` 以使用 `SandboxExecutionService`
**文件：** `SandboxHook.java`（修改）

当前 `SandboxHook` 使用 `ToolSandbox` 进行进程内沙箱化。增强以检测需要容器的工具并将其路由到 `SandboxExecutionService`：

```java
// 在 SandboxHook.wrapToolExecutor() 中：
if (tool.requiresContainer()) {
    return (name, id, args) -> sandboxExecutionService.execute(...);
}
// 否则，回退到现有的 ToolSandbox
```

#### 任务 5.11 — 创建 `SandboxExecutionTest`
**文件：** `SandboxExecutionTest.java`

注意：需要 Docker 守护进程运行。使用 `@EnabledIf` 或 `@Category(RequiresDocker.class)`。

测试用例：
- 基本命令执行：`echo "hello"` → stdout = "hello"。
- 只读工作区：写入 `/workspace` 失败。
- 可写 `/tmp`：写入 `/tmp/sandbox` 成功。
- 命令白名单：白名单命令运行，非白名单命令失败。
- 命令黑名单：黑名单命令失败。
- 超时：`sleep 999` 被终止。
- 内存限制：内存密集型进程被 OOM 杀死。
- 网络禁用：`curl` 或 `wget` 失败。
- 并发执行：多个容器同时运行。
- 清理：执行后临时文件被移除。

---

### 第 17-18 周：心跳系统

#### 任务 5.12 — 创建 `HeartbeatConfig`
**包：** `lyjew.com.lyclaw.heartbeat`  
**文件：** `HeartbeatConfig.java`

```java
@ConfigurationProperties(prefix = "lyclaw.agent.heartbeat")
public record HeartbeatConfig(
    boolean enabled,
    String cron,                    // Spring cron 表达式
    String activeHoursStart,        // "09:00"
    String activeHoursEnd,          // "18:00"
    boolean lightContext,           // 仅加载 HEARTBEAT.md
    boolean isolatedSession,        // 每次心跳使用全新会话
    boolean skipWhenBusy,           // 子智能体活跃时跳过
    long timeoutSeconds,            // 最大心跳时长
    int maxConsecutiveFailures,     // N 次失败后告警
    String alertChannel             // 告警发送位置
) {}
```

#### 任务 5.13 — 创建 `HeartbeatScheduler`
**文件：** `HeartbeatScheduler.java`

```java
@Component
public class HeartbeatScheduler {
    @Scheduled(cron = "${lyclaw.agent.heartbeat.cron:0 */30 * * * *}")
    public void heartbeat() {
        // 守卫检查，然后执行
    }
}
```

**流程：**
1. 检查 `enabled` — 如果禁用则返回。
2. 检查活跃时段窗口 — 如果不在范围内则返回。
3. 检查 `skipWhenBusy` — 如果有任何智能体存在活跃子智能体则返回。
4. 对每个已注册的智能体（或仅默认智能体）：
   a. 根据 `isolatedSession` 创建新会话或重用会话。
   b. 构建上下文：如果 `lightContext`，仅 HEARTBEAT.md；否则完整引导。
   c. 使用特殊的 `__heartbeat__` 触发消息调用智能体。
   d. 记录结果，发出指标。
   e. 失败时：递增 `consecutiveFailures`，检查阈值，发送告警。
5. 记录摘要：已检测智能体数、成功数、失败数。

#### 任务 5.14 — 实现活跃时段窗口检查
**位置：** `HeartbeatScheduler`

将 `activeHoursStart` 和 `activeHoursEnd` 解析为 `LocalTime`。与 `LocalTime.now()` 比较。支持跨夜窗口（例如 22:00-06:00）。

#### 任务 5.15 — 实现 `lightContext` 模式
**位置：** `HeartbeatScheduler` / `ContextBuildStage`

当 `lightContext=true` 时：
- 仅加载 `HEARTBEAT.md` 作为系统提示词。
- 跳过 AGENTS.md、SOUL.md、BOOTSTRAP.md、IDENTITY.md、USER.md。
- 跳过记忆检索。
- 跳过工具定义（心跳仅对话，无工具调用）。
- 设置 `fastMode=true` 以使用更便宜/更快的模型。

#### 任务 5.16 — 实现 `isolatedSession` 模式
**位置：** `HeartbeatScheduler`

当 `isolatedSession=true` 时：
- 每次心跳生成新的 `sessionId`。
- 不加载之前的会话消息。
- 不持久化心跳会话。

当 `isolatedSession=false` 时：
- 使用智能体的默认持久会话。
- 心跳对话在多次运行中累积。

#### 任务 5.17 — 实现 `skipWhenBusy`
**位置：** `HeartbeatScheduler`

检查所有已注册智能体的 `AgentContext.getActiveSubagentIds()`。如果任何智能体有活跃子智能体 → 跳过本次心跳周期并记录日志：`心跳已跳过：智能体 X 有 Y 个活跃子智能体`。

#### 任务 5.18 — 注册心跳钩子
**位置：** `HookRegistry`

`HeartbeatScheduler` 注册自身以接收 `onHeartbeat` 生命周期事件。其他钩子也可以实现 `onHeartbeat` 以进行自定义定期行为。

#### 任务 5.19 — 创建 `HeartbeatSchedulerTest`
**文件：** `HeartbeatSchedulerTest.java`

测试用例：
- 心跳按 cron 调度执行（使用 `@Scheduled` 测试工具或手动触发）。
- 禁用时跳过心跳。
- 在活跃时段外跳过心跳。
- 智能体忙碌时跳过心跳。
- `lightContext` 仅加载 HEARTBEAT.md。
- `isolatedSession` 每次都创建新会话。
- 非隔离会话累积消息。
- 连续失败达到阈值时触发告警。
- 成功的心跳重置失败计数器。
- 多个智能体均被逐一检测。

---

## 6. 测试策略

### 6.1 单元测试

**原则：** 每个新类必须有对应的单元测试类。修改的现有类必须添加新测试方法（不能替换现有方法）。

**每个阶段的目标：**

| 阶段 | 新类数量 | 新测试类数量 | 最低覆盖率 |
|-------|-------------|------------------|---------------|
| 阶段 1 | ~12 | ~8 | 85% |
| 阶段 2 | ~15 | ~12 | 85% |
| 阶段 3 | ~14 | ~10 | 80% |
| 阶段 4 | ~12 | ~10 | 80% |

### 6.2 集成测试

**关键集成测试场景：**

1. **完整管道**（阶段 1）：`@Agent` 带扩展字段 → 配置解析 → 钩子分发 → 6 个阶段 → 响应。
2. **子智能体链**（阶段 2）：父委托给子，子委托给孙，结果传播，深度/容量限制被强制执行。
3. **模型回退**（阶段 2）：主模型失败 → 回退链被探测 → 使用回退模型 → 思考级别得以保留。
4. **压缩 + 重试**（阶段 3）：上下文溢出 → 压缩减少大小 → 反思分数低 → 用压缩后的上下文重试。
5. **引导 + 路由**（阶段 3）：请求路由到特定智能体 → 引导加载 → 上下文注入 → 带身份前缀的响应。
6. **流式管道**（阶段 4）：原始 token → 文本块合并 → 类人延迟 → 输入指示器 → SSE 事件。
7. **心跳**（阶段 4）：调度器触发 → 智能体唤醒 → 使用轻量上下文运行 → 结果记录。

### 6.3 向后兼容性测试

**不可妥协：** 现有全部 49 个测试必须在每个阶段后全部通过。

**向后兼容检查清单（每个阶段完成时验证）：**
- [ ] `@Agent(name="test")` 可编译并运行（新字段有默认值）。
- [ ] 现有 5 个 `AgentHook` 实现无需修改即可编译。
- [ ] `AgentProxyFactory(ChatFacade, ReActEngine, ToolRegistry)` 构造函数仍可用。
- [ ] `AgentInvocationHandler` 正确分发现有钩子。
- [ ] 所有 6 个管道阶段按正确顺序执行。
- [ ] `Flux<ServerSentEvent<String>>` 返回类型可用于 SSE 直通。
- [ ] `String` 返回类型可用于阻塞调用。
- [ ] `AgentConfigResolver.resolve(agentName)` 返回有效的 `AgentConfig`。
- [ ] `AgentContext` 构造函数和 `toSnapshot`/`restoreFromSnapshot` 正常工作。

### 6.4 性能测试

**压缩性能：**
- 100K token 的对话记录：压缩必须在 5 秒内完成。
- 200K token 的对话记录：压缩必须在 10 秒内完成。
- Token 计数估算：与实际 API 计数误差 <1%。

**流式块吞吐量：**
- 1000 token/秒输入：无背压，无事件丢失。
- 合并开销：每块 <1ms。

**子智能体生成：**
- 并发生成 5 个子智能体：所有子智能体在父超时时间内完成。
- 上下文内存：每个子智能体增加 <1MB 堆内存。

### 6.5 安全测试

**沙箱隔离：**
- 容器无法访问挂载卷之外的宿主机文件系统。
- 禁用网络的容器无法建立出站连接。
- 内存/CPU 限制由容器运行时强制执行。
- 白名单阻止通过参数进行的命令注入。

**内容安全：**
- `OutputGuardHook` 捕获压缩/摘要内容中的敏感模式。
- 引导文件不能包含可执行代码（加载时验证）。

---

## 7. 迁移计划

### 7.1 阶段 1：智能体核心增强

**破坏性变更：无**

`@Agent` 和 `AgentHook` 上的所有新字段都是可选的，具有与当前行为匹配的合理默认值：
- 新的 `@Agent` 字段默认为空字符串 / 0 / 空数组 → 视为"使用默认值"。
- 新的 `AgentHook` 方法为 `default` 空操作。
- 新的 `AgentContext` 字段初始化为 null/空，通过具有空安全的 getter 访问。
- `AgentProxyFactory` 新增构造函数重载；旧构造函数被保留。

**现有用户的迁移步骤：**
1. 更新依赖版本。无需修改代码。
2. （可选）在 `application.yml` 中添加 `lyclaw.agent.defaults` 以集中配置。
3. （可选）在 `@Agent` 注解中添加新字段进行逐智能体自定义。

### 7.2 阶段 2：子智能体 + 模型

**破坏性变更：无**

- 子智能体系统是增量式的：现有单智能体流程不变。
- `"delegate_to_agent"` 工具被添加到工具定义中；如果 LLM 从不调用它，行为完全相同。
- 模型目录是增量式的：现有 `ChatModelRegistry` 仍然工作；目录优先被查询，回退到注册表。
- `RunRetryManager` 替换硬编码常量，但默认值与原来相同（2 次重试，0.6 阈值）。

**现有用户的迁移步骤：**
1. 单智能体使用无需更改。
2. 使用子智能体：在父智能体的 `@Agent` 注解中添加 `allowAgents`。
3. 使用模型目录：可选择添加 `models.yaml` 或依赖提供商发现。

### 7.3 阶段 3：上下文 + 引导 + 路由

**破坏性变更：无**

- 压缩是选择加入的，通过 `lyclaw.agent.compaction.enabled=true` 启用。默认为 `false`（禁用）。
- 引导加载是选择加入的：文件必须存在于 agentDir/workspaceDir。无文件 → 无效果。
- 路由是选择加入的：`lyclaw.agent.routing.enabled=true`。默认是直接调用。
- 上下文裁剪是选择加入的：`lyclaw.agent.pruning.enabled=true`。默认 `false`。
- `ContextBuildStage` 优雅处理缺失的 `BootstrapLoader` Bean。

**现有用户的迁移步骤：**
1. 无需更改。
2. 使用引导：在智能体工作区创建 `AGENTS.md`。
3. 使用压缩：在配置中启用。
4. 使用路由：在配置中添加路由。

### 7.4 阶段 4：流式 + 沙箱 + 心跳

**破坏性变更：无**

- 文本块流式是选择加入的：`lyclaw.agent.streaming.block.enabled=true`。默认 `false` → 原始 token 直通（当前行为）。
- 类人延迟是选择加入的：`lyclaw.agent.streaming.humanDelay.enabled=true`。默认 `false`。
- 输入指示器是选择加入的：`lyclaw.agent.streaming.typingMode=MESSAGE`。默认 `NEVER`。
- 沙箱需要 Docker/podman 守护进程；如果不可用，回退到现有的进程内 `ToolSandbox`。
- 心跳是选择加入的：`lyclaw.agent.heartbeat.enabled=true`。默认 `false`。

**现有用户的迁移步骤：**
1. 无需更改；所有当前 SSE 行为默认保留。
2. 使用文本块流式：在配置中启用文本块合并。
3. 使用沙箱：安装 Docker，在配置中启用沙箱。
4. 使用心跳：在配置中启用，配置 cron。

---

## 8. 成功指标

| 指标 | 当前值 | 目标值 | 衡量方式 |
|--------|---------|--------|-------------|
| `@Agent` 注解字段数 | 6 | 26+ | 注解中声明的字段计数 |
| `AgentHook` 生命周期点数 | 6 | 36 | 接口中的方法计数 |
| `AgentContext` 运行时字段数 | ~20 | ~35 | 提供运行时数据的字段计数 |
| 管道阶段数 | 6 | 7（新增 CompactionStage） | `@PipelineStage` Bean 计数 |
| 钩子实现数 | 5 | 5+（新钩子可选） | 现有钩子不变 |
| 子智能体委托深度 | 不适用 | 可配置（默认 3） | 集成测试 |
| 模型回退链 | 不适用（手动） | 带探测的自动回退 | AutoFallbackProbeTest |
| 压缩 Token 缩减 | 不适用 | >80% 缩减 | CompactionEngineTest |
| 上下文裁剪 | 不适用 | 精确的单条消息裁剪 | ContextPruningEngineTest |
| 引导文件支持 | 不适用 | 6 种文件类型加载 | BootstrapLoaderTest |
| 多智能体路由 | 不适用 | 基于模式的路由 | AgentRouterTest |
| 文本块流式 | 原始 token 直通 | 带延迟的合并输出 | BlockStreamingTest |
| 类人化输入效果 | 无 | 可配置延迟 + 指示器 | HumanDelayTest |
| 沙箱执行 | 仅进程内 | Docker/podman 容器 | SandboxExecutionTest |
| 心跳系统 | 无 | Cron 调度定期检查 | HeartbeatSchedulerTest |
| 现有测试通过 | 49 | 49（零回归） | `mvn test` |
| 新测试覆盖率 | 不适用 | 每阶段 >80% | JaCoCo / jacoco-maven-plugin |
| 与 OpenClaw 的配置对齐度 | ~30% | AgentConfig 字段 >90% | 手动逐字段对比 |
| 破坏性变更 | 不适用 | 0（零） | 现有用户代码编译通过 |

---

## 9. 包结构（目标）

```
lyjew.com.lyclaw
├── agent
│   ├── subagent
│   │   ├── SubagentConfig.java
│   │   ├── SubagentSpawner.java
│   │   ├── SubagentResult.java
│   │   ├── DelegateToAgentTool.java
│   │   └── exception/
│   │       ├── SubagentDelegationDeniedException.java
│   │       ├── MaxSpawnDepthExceededException.java
│   │       └── TooManyChildrenException.java
│   └── （现有智能体类不变）
├── annotation
│   └── Agent.java（扩展，向后兼容）
├── bootstrap
│   ├── BootstrapLoader.java
│   ├── BootstrapContent.java
│   └── ContextInjectionPolicy.java
├── chat
│   ├── catalog
│   │   ├── ModelCatalog.java
│   │   ├── ModelCatalogEntry.java
│   │   └── ProviderDiscovery.java
│   ├── discovery
│   │   └── OpenAIProviderDiscovery.java
│   ├── fallback
│   │   ├── AutoFallbackProbe.java
│   │   └── FallbackState.java
│   ├── ChatFacade.java（扩展）
│   ├── ModelResolutionService.java
│   └── （现有聊天类不变）
├── config
│   ├── AgentDefaultsConfig.java（新增）
│   ├── ResolvedAgentConfig.java（新增）
│   ├── AgentConfigResolver.java（扩展）
│   ├── AgentModelConfig.java（新增）
│   ├── AgentToolModelConfig.java（新增）
│   ├── AgentContextLimits.java（新增）
│   ├── BootstrapConfig.java（新增）
│   └── （现有配置类）
├── context
│   ├── compaction
│   │   ├── CompactionConfig.java
│   │   ├── CompactionEngine.java
│   │   └── CompactionResult.java
│   ├── pruning
│   │   ├── ContextPruningConfig.java
│   │   ├── ContextPruningEngine.java
│   │   └── PruningResult.java
│   └── ContextEngine.java
├── heartbeat
│   ├── HeartbeatConfig.java
│   └── HeartbeatScheduler.java
├── identity
│   ├── IdentityConfig.java
│   ├── IdentityResolver.java
│   └── AgentAvatarResolution.java
├── pipeline
│   └── stage
│       ├── CompactionStage.java（新增）
│       ├── ContextBuildStage.java（扩展）
│       ├── RespondStage.java（扩展）
│       └── （现有阶段）
├── react
│   ├── AgentContext.java（扩展）
│   ├── AgentHook.java（扩展，36 个方法）
│   ├── AgentInvocationHandler.java（扩展）
│   ├── AgentProxyFactory.java（扩展）
│   ├── HookRegistry.java（新增）
│   ├── HookDecision.java（新增）
│   ├── HookRegistration.java（新增）
│   ├── AgentFinalizeResult.java（新增）
│   ├── AgentRuntimeType.java（新增）
│   ├── acp
│   │   ├── AcpRuntime.java
│   │   ├── AcpRuntimeHandle.java
│   │   ├── AcpRuntimeEvent.java
│   │   ├── AcpRuntimeTurnResult.java
│   │   └── DefaultAcpRuntime.java
│   └── （现有 react 类）
├── retry
│   ├── RunRetriesConfig.java
│   └── RunRetryManager.java
├── routing
│   ├── AgentRouter.java
│   ├── AgentRouteBinding.java
│   ├── AgentAcpBinding.java
│   └── AgentBindingMatch.java
├── sandbox
│   ├── AgentSandboxConfig.java
│   └── SandboxExecutionService.java
└── stream
    ├── BlockStreamingConfig.java
    ├── BlockStreamingEngine.java
    ├── HumanDelayConfig.java
    ├── HumanDelayController.java
    └── TypingIndicatorController.java
```

---

## 10. 风险登记表

| 风险编号 | 描述 | 阶段 | 可能性 | 影响 | 缓解措施 |
|---------|-------------|-------|------------|--------|------------|
| R1 | 深度合并配置逻辑不正确，导致静默的错误配置 | 1 | 中 | 高 | 全面的 ConfigResolutionTest，使用基于属性的测试 (jqwik) |
| R2 | 钩子分发顺序破坏与现有 5 个钩子的向后兼容性 | 1 | 低 | 高 | 回归套件首先运行所有现有钩子测试 |
| R3 | 基于信号量的并发守卫在嵌套子智能体生成中导致死锁 | 2 | 中 | 中 | 信号量获取带超时；测试中的死锁检测 |
| R4 | 自动回退探测给每个请求增加延迟 | 2 | 中 | 中 | 探测结果带 TTL 缓存；探测是异步的，不在关键路径上 |
| R5 | 压缩丢失关键上下文，导致智能体行为错误 | 3 | 中 | 高 | 质量守卫验证压缩输出；默认关闭，选择加入 |
| R6 | CI 中 Docker 守护进程不可用，沙箱测试失败 | 4 | 高 | 中 | `@EnabledIf` 注解在 CI 中跳过 Docker 测试；单元测试使用模拟沙箱 |
| R7 | `@Scheduled` 心跳在集成测试期间触发，导致测试不稳定 | 4 | 中 | 中 | 心跳默认禁用；测试显式设置 `enabled=false` |
| R8 | SSE 流转换管道引入背压或丢失事件 | 4 | 中 | 高 | 用 1000 token/秒进行性能测试；用慢消费者进行背压测试 |
| R9 | 迁移疲劳：太多新配置选项让用户不知所措 | 全部 | 中 | 低 | 合理的默认值；所有功能选择加入；迁移指南文档 |

---

## 11. 参考资料

- [OpenClaw AgentConfig 源代码](https://github.com/openclaw/openclaw) — 字段对齐目标
- [OpenClaw AgentHook 源代码](https://github.com/openclaw/openclaw) — 生命周期点参考
- [Spring `@ConfigurationProperties` 文档](https://docs.spring.io/spring-boot/docs/current/reference/html/configuration-metadata.html)
- [docker-java SDK](https://github.com/docker-java/docker-java) — 沙箱容器管理
- [Project Reactor 参考文档](https://projectreactor.io/docs/core/release/reference/) — SSE 流式管道
- [LyClaw Agent 注解（当前）](../../lyclaw-framework/src/main/java/lyjew/com/lyclaw/annotation/Agent.java)
- [LyClaw AgentHook（当前）](../../lyclaw-framework/src/main/java/lyjew/com/lyclaw/react/AgentHook.java)
- [LyClaw AgentContext（当前）](../../lyclaw-framework/src/main/java/lyjew/com/lyclaw/react/AgentContext.java)
- [LyClaw 管道阶段（当前）](../../lyclaw-framework/src/main/java/lyjew/com/lyclaw/pipeline/stage/)
