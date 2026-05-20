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
