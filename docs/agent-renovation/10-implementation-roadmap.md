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
**包：** `lyjew.com.lyclaw.compaction`  
**文件：** `CompactionConfig.java`、`CompactionEngine.java`

```java
/**
 * 压缩配置 — 完整设计见 07-renovation-phase3-context-bootstrap.md §2.1。
 *
 * 核心字段（20+）：
 *   mode, reserveTokens, keepRecentTokens, reserveTokensFloor,
 *   maxHistoryShare, customInstructions, recentTurnsPreserve,
 *   identifierPolicy, identifierInstructions,
 *   qualityGuard (QualityGuard), midTurnPrecheck (MidTurnPrecheck),
 *   postIndexSync, memoryFlush (MemoryFlush),
 *   postCompactionSections, model, timeoutSeconds,
 *   truncateAfterCompaction, maxActiveTranscriptBytes, notifyUser
 *
 * 配置前缀：lyclaw.compaction
 */
@ConfigurationProperties(prefix = "lyclaw.compaction")
public class CompactionConfig {
    // 详见 07 文档的完整字段定义
}
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

#### 任务 4.6 — 创建 `ContextPruningConfig` + `ContextPruner`
**包：** `lyjew.com.lyclaw.compaction`  
**文件：** `ContextPruningConfig.java`、`ContextPruner.java`

```java
/**
 * 上下文修剪配置 — 完整设计见 07-renovation-phase3-context-bootstrap.md §2.2。
 *
 * 核心字段（12+）：
 *   mode (OFF / CACHE_TTL), ttl, keepLastAssistants,
 *   softTrimRatio, hardClearRatio, minPrunableToolChars,
 *   toolAllow, toolDeny, softTrim (SoftTrim), hardClear (HardClear)
 *
 * 配置前缀：lyclaw.compaction.pruning
 */
@ConfigurationProperties(prefix = "lyclaw.compaction.pruning")
public class ContextPruningConfig {
    // 详见 07 文档的完整字段定义
}
```

**`ContextPruner`（07 文档中命名）：**
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
- `ContextPrunerTest.java`
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
@ConfigurationProperties(prefix = "lyclaw.bootstrap")
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

#### 任务 5.1 — 创建 `BlockStreamingConfig` + `BlockStreamingController`
**文件：** `BlockStreamingConfig.java`（包：`lyjew.com.lyclaw.config`）、`BlockStreamingController.java`（包：`lyjew.com.lyclaw.react.stream`）

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

**`BlockStreamingController`：**
```java
public class BlockStreamingController {
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
**文件：** `HumanDelayConfig.java`（包：`lyjew.com.lyclaw.config`）、`HumanDelayController.java`（包：`lyjew.com.lyclaw.react.stream`）

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
**包：** `lyjew.com.lyclaw.react.stream`  
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
  → BlockStreamingController.coalesceToSSE()     [文本块合并]
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
**包：** `lyjew.com.lyclaw.config`  
**文件：** `AgentSandboxConfig.java`

```java
@ConfigurationProperties(prefix = "lyclaw.sandbox")
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
**包：** `lyjew.com.lyclaw.security.sandbox`  
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
**包：** `lyjew.com.lyclaw.config`  
**文件：** `HeartbeatConfig.java`

```java
@ConfigurationProperties(prefix = "lyclaw.heartbeat")
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
    @Scheduled(cron = "${lyclaw.heartbeat.cron:0 */30 * * * *}")
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

- 压缩是选择加入的，通过 `lyclaw.compaction.enabled=true` 启用。默认为 `false`（禁用）。
- 引导加载是选择加入的：文件必须存在于 agentDir/workspaceDir。无文件 → 无效果。
- 路由是选择加入的：`lyclaw.routing.enabled=true`。默认是直接调用。
- 上下文裁剪是选择加入的：`lyclaw.compaction.pruning.enabled=true`。默认 `false`。
- `ContextBuildStage` 优雅处理缺失的 `BootstrapLoader` Bean。

**现有用户的迁移步骤：**
1. 无需更改。
2. 使用引导：在智能体工作区创建 `AGENTS.md`。
3. 使用压缩：在配置中启用。
4. 使用路由：在配置中添加路由。

### 7.4 阶段 4：流式 + 沙箱 + 心跳

**破坏性变更：无**

- 文本块流式是选择加入的：`lyclaw.streaming.block.enabled=true`。默认 `false` → 原始 token 直通（当前行为）。
- 类人延迟是选择加入的：`lyclaw.streaming.human-delay.enabled=true`。默认 `false`。
- 输入指示器是选择加入的：`lyclaw.streaming.typing-indicator.mode=MESSAGE`。默认 `NEVER`。
- 沙箱需要 Docker/podman 守护进程；如果不可用，回退到现有的进程内 `ToolSandbox`。
- 心跳是选择加入的：`lyclaw.heartbeat.enabled=true`。默认 `false`。

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
| 上下文裁剪 | 不适用 | 精确的单条消息裁剪 | ContextPrunerTest |
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
│   │   ├── ContextPruner.java
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
    ├── BlockStreamingController.java
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
