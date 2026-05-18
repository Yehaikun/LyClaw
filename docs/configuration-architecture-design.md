# LyClaw 配置架构重构设计

## 1. 当前配置乱象诊断

### 1.1 问题全景

```
当前配置状态（混乱）:
├── lyclaw.chat.*           ← ChatProperties, @ConfigurationProperties 绑定 ✅ 唯一正常工作的
├── lyclaw.storage.*        ← StorageProperties, @ConfigurationProperties 绑定 ✅
├── lyclaw.extension.*      ← LyClawConfigurationProperties 中嵌套 ✅
├── lyclaw.llm.*            ← 死配置！application.yml 里有，但没有 @ConfigurationProperties 绑定
├── LyClawProperties.java   ← 死代码！定义了 llm/pipeline/tools/sandbox/agent 但从未绑定
├── @Value 散落             ← TavilyWebSearchTool, ReActPlanner 两处，风格不统一
├── 硬编码常量 30+ 处        ← static final 字段散落各处，用户无法覆盖
└── 子模块 application.yml   ← 5 个文件全死，单体应用只加载 lyclaw-web 的配置
```

### 1.2 具体问题

#### 问题 1：双重 LLM 配置

application.yml 中同时存在新旧两套 LLM 配置：

```yaml
# 新配置 — 实际生效（ChatProperties, prefix=lyclaw.chat）
lyclaw.chat.models.deepseek.api-key: ${DEEPSEEK_API_KEY:}

# 旧配置 — 死代码（LyClawProperties.LlmProperties 从未绑定）
lyclaw.llm.deepseek.api-key: ${DEEPSEEK_API_KEY:}
```

#### 问题 2：LyClawProperties 是僵尸代码

`lyclaw-framework/.../config/LyClawProperties.java` 定义了精良的嵌套 POJO（LlmProperties、PipelineProperties、ToolsProperties、SandboxProperties、AgentProperties），每个都有 Java 默认值，但：

- **没有 `@ConfigurationProperties` 注解** → Spring 永远不会绑定配置到它
- **没有任何 Bean 注册** → 只有 `LyClawConfigEndpoint`（actuator）尝试 `@Autowired(required = false)` 读取，运行时永远为 null
- **与 `LyClawConfigurationProperties` 同名混淆** → 后者是 `prefix="lyclaw"` 但只包含 `extension`

#### 问题 3：硬编码泛滥

30+ 处 `static final` 常量散落在各模块，用户完全无法覆盖：

| 类别 | 示例 | 值 | 位置 |
|------|------|-----|------|
| 超时 | `MAX_TOOL_ROUNDS` | 30 | DefaultReActEngine |
| 超时 | `APPROVAL_TIMEOUT_SECONDS` | 60 | ApprovalStore |
| 限制 | `MAX_OUTPUT_LENGTH` | 10000 | ToolSandboxImpl, AnnotatedCommandTool, AnnotatedScriptTool |
| 限制 | `DEFAULT_MAX_NODES` | 50 | PlanValidatorImpl |
| 阈值 | `CONFIDENCE_THRESHOLD` | 0.5 | HybridPlanner |
| 重试 | `DEFAULT_MAX_RETRIES` | 3 | DefaultToolCallPolicy |

#### 问题 4：配置风格不一致

- ChatProperties / StorageProperties → `@ConfigurationProperties` ✅
- Tavily API key → `@Value` ❌
- ReAct max-cycles → `@Value` ❌
- 其余全部硬编码 ❌

#### 问题 5：子模块 application.yml 全死

5 个子模块（action/plan/memory/protocol/reflect）都有 `application.yml`，定义了 `server.port` 和 `spring.application.name`。但自从转为单体应用后，Spring Boot 只加载 `lyclaw-web` 的配置，这些文件纯属误导。

---

## 2. 框架配置机制设计

### 2.1 设计原则

1. **约定优于配置**：框架提供合理默认值，用户零配置即可运行
2. **按需覆盖**：用户只需在自己的 application.yml 中写与默认值不同的项
3. **Spring Boot 原生**：使用 `@ConfigurationProperties` + Java field initializer 作为默认值来源
4. **分层清晰**：每个功能域独立 Properties 类，独立 prefix
5. **IDE 友好**：生成 `spring-configuration-metadata.json`，用户写 YAML 时有自动补全和文档提示

### 2.2 默认值来源机制 — 为什么用 Java 默认值而非 framework-default.yml

Spring Boot 的配置优先级（从高到低）：

```
1. 命令行参数              --lyclaw.agent.max-rounds=20
2. 环境变量                LYCLAW_AGENT_MAX_ROUNDS=20
3. 用户 application.yml    lyclaw.agent.max-rounds: 20
4. 框架 jar 内 application.yml   ← 如果框架提供，优先级低于用户配置
5. @ConfigurationProperties 的 Java field initializer  ← 最低优先级
```

**推荐方案**：使用 `@ConfigurationProperties` 类的 field initializer 作为默认值。

```java
@ConfigurationProperties(prefix = "lyclaw.agent")
public class AgentProperties {
    /** 最大 ReAct 循环轮数，超过后强制终止。默认 10。 */
    private int maxRounds = 10;

    /** 单轮 LLM 调用超时（毫秒）。默认 5 分钟。 */
    private long timeoutMs = 300_000;
}
```

理由：
- **默认值在源码中可见**：开发者不用翻 YAML 文件
- **编译时安全**：类型错误在编译期暴露
- **无优先级混淆**：不引入 jar 内 YAML 的优先级问题
- **Spring Boot 标准**：这是 Spring Boot 官方推荐的方式
- **IDE 自动补全**：`spring-boot-configuration-processor` 生成 metadata.json

### 2.3 完整配置层次

```
lyclaw.*  (root)
├── chat.*              ChatProperties          已存在 ✅，保持不动
├── storage.*           StorageProperties       已存在 ✅，保持不动
├── tool.*              ToolProperties          新建
├── agent.*             AgentProperties         新建
├── plan.*              PlanProperties          新建
├── pipeline.*          PipelineProperties      新建（轻量）
└── extension.*         ExtensionProperties     已存在 ✅，保持不动
```

### 2.4 各 Properties 类详细定义

#### ChatProperties（保持现有，不做改动）

```yaml
lyclaw.chat:
  default-provider: deepseek
  default-model: deepseek-v4-flash
  routing-enabled: false
  models:
    deepseek:
      provider: openai-protocol
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY}
      model: deepseek-v4-flash
      retry:
        max-attempts: 3
        backoff: exponential
        base-delay-ms: 1000
  fallback-chain: []
  circuit-breaker:
    failure-threshold: 5
    half-open-after-seconds: 30
    half-open-max-requests: 3
```

#### StorageProperties（保持现有，不做改动）

```yaml
lyclaw.storage:
  base-path: ${user.dir}/data/storage
  default-backend: file
  stores:
    session: file
    entity: file
    memory: inmemory
```

#### ToolProperties（新建 — 替换所有工具相关硬编码）

```java
@ConfigurationProperties(prefix = "lyclaw.tool")
public class ToolProperties {
    /** 单个工具调用默认超时（毫秒）。默认 30000（30秒）。 */
    private long defaultTimeoutMs = 30_000;

    /** 工具输出最大长度（字符数），超过截断。默认 10000。 */
    private int maxOutputLength = 10_000;

    /** 单个工具在单次会话中的最大调用次数。默认 20。 */
    private int maxCallsPerTool = 20;

    /** 工具调用失败最大重试次数。默认 3。 */
    private int maxRetries = 3;

    /** 工具执行最大总轮数。默认 10。 */
    private int maxRounds = 10;

    /** 沙箱隔离级别：DIRECT / SANDBOX / PROCESS。默认 PROCESS。 */
    private SandboxLevel sandboxLevel = SandboxLevel.PROCESS;

    /** 只读工具白名单（沙箱模式下跳过隔离）。 */
    private List<String> readOnlyTools = List.of("current_time", "calculator");

    /** Tavily 搜索 API 密钥。 */
    private String tavilyApiKey = "";
}
```

对应 YAML：

```yaml
lyclaw.tool:
  default-timeout-ms: 30000
  max-output-length: 10000
  max-calls-per-tool: 20
  max-retries: 3
  max-rounds: 10
  sandbox-level: PROCESS
  read-only-tools:
    - current_time
    - calculator
  tavily-api-key: ${TAVILY_API_KEY:}
```

#### AgentProperties（新建 — 替换 ReAct/Agent 相关硬编码）

```java
@ConfigurationProperties(prefix = "lyclaw.agent")
public class AgentProperties {
    /** 默认交互模式：react / cot / hierarchical。默认 react。 */
    private String defaultMode = "react";

    /** 最大 ReAct 工具调用轮数。默认 30。 */
    private int maxToolRounds = 30;

    /** 工具审批超时（秒）。默认 30。 */
    private int approvalTimeoutSeconds = 30;

    /** 审批存储超时（秒）。默认 60。 */
    private int approvalStoreTimeoutSeconds = 60;

    /** 单次 Agent 调用总超时（毫秒）。默认 5 分钟。 */
    private long timeoutMs = 300_000;
}
```

对应 YAML：

```yaml
lyclaw.agent:
  default-mode: react
  max-tool-rounds: 30
  approval-timeout-seconds: 30
  approval-store-timeout-seconds: 60
  timeout-ms: 300000
```

#### PlanProperties（新建 — 替换规划相关硬编码）

```java
@ConfigurationProperties(prefix = "lyclaw.plan")
public class AgentProperties {
    /** 任务规划默认超时（毫秒）。默认 30000（30秒）。 */
    private long defaultTimeoutMs = 30_000;

    /** 简单任务超时（毫秒）。默认 10000（10秒）。 */
    private long simpleTimeoutMs = 10_000;

    /** 计划最大节点数，超过触发校验告警。默认 50。 */
    private int maxNodes = 50;

    /** 计划总时间预算（毫秒），超过触发重规划。默认 10 分钟。 */
    private long timeBudgetMs = 600_000;

    /** 混合规划器：规则引擎置信度阈值（低于此值走 LLM 回退）。默认 0.5。 */
    private double hybridConfidenceThreshold = 0.5;
}
```

对应 YAML：

```yaml
lyclaw.plan:
  default-timeout-ms: 30000
  simple-timeout-ms: 10000
  max-nodes: 50
  time-budget-ms: 600000
  hybrid-confidence-threshold: 0.5
```

#### PipelineProperties（新建 — 轻量）

```java
@ConfigurationProperties(prefix = "lyclaw.pipeline")
public class PipelineProperties {
    /** 是否启用 Stage 管线。默认 true。 */
    private boolean enabled = true;

    /** 管线总超时（毫秒）。默认 5 分钟。 */
    private long timeoutMs = 300_000;
}
```

#### ExtensionProperties（保持现有）

```yaml
lyclaw.extension:
  filtering-enabled: true
  ordering-strategy: topology
  fail-fast: false
```

---

## 3. 改动方案

### 3.1 改动清单（按风险从低到高）

#### 第一批：删死代码（零风险）

| 操作 | 文件 | 理由 |
|------|------|------|
| 删除 | `lyclaw-action/src/main/resources/application.yml` | 子模块配置在单体应用中无效 |
| 删除 | `lyclaw-plan/src/main/resources/application.yml` | 同上 |
| 删除 | `lyclaw-memory/src/main/resources/application.yml` | 同上 |
| 删除 | `lyclaw-protocol/src/main/resources/application.yml` | 同上 |
| 删除 | `lyclaw-reflect/src/main/resources/application.yml` | 同上 |
| 删除 `lyclaw.llm.*` 段 | `lyclaw-web/.../application.yml` | 死配置，从未绑定 |
| 删除 | `lyclaw-framework/.../config/LyClawProperties.java` | 死 POJO |
| 删除/修改 | `lyclaw-framework/.../config/LyClawConfigEndpoint.java` | 引用了死 LyClawProperties |
| 删除/修改 | `lyclaw-autoconfigure/.../config/LyClawPropertiesBinder.java` | 绑定的是错误的 Properties 类 |

#### 第二批：新建 Properties 类（低风险 — 新增文件）

| 操作 | 文件 | 说明 |
|------|------|------|
| 新建 | `lyclaw-framework/.../config/ToolProperties.java` | `lyclaw.tool.*` |
| 新建 | `lyclaw-framework/.../config/AgentProperties.java` | `lyclaw.agent.*` |
| 新建 | `lyclaw-framework/.../config/PlanProperties.java` | `lyclaw.plan.*` |
| 新建 | `lyclaw-framework/.../config/PipelineProperties.java` | `lyclaw.pipeline.*` |

#### 第三批：注册 Properties Bean（低风险 — 新增 Bean 方法）

在对应 AutoConfiguration 中新增 `@Bean` 方法：

| AutoConfiguration | 新增 Bean |
|-------------------|-----------|
| `ToolAutoConfiguration` | `@Bean @ConfigurationProperties(prefix = "lyclaw.tool") ToolProperties toolProperties()` |
| `ReActAutoConfiguration` | `@Bean @ConfigurationProperties(prefix = "lyclaw.agent") AgentProperties agentProperties()` |
| `PipelineAutoConfiguration` | `@Bean @ConfigurationProperties(prefix = "lyclaw.pipeline") PipelineProperties pipelineProperties()` |

PlanProperties 可以放在 `ChatAutoConfiguration` 或新建 `PlanAutoConfiguration`。

#### 第四批：替换硬编码（中等风险 — 修改现有 Bean 构造函数）

这是改动最大的部分，需要逐文件替换 `static final` 常量 → 从 Properties 读取：

| 文件 | 当前 | 改为 |
|------|------|------|
| `DefaultReActEngine.java` | `MAX_TOOL_ROUNDS = 30` | 构造注入 `AgentProperties`，读 `getMaxToolRounds()` |
| `ApprovalHook.java` | `APPROVAL_TIMEOUT_SECONDS = 30` | 构造注入 `AgentProperties` |
| `ApprovalStore.java` | `APPROVAL_TIMEOUT_SECONDS = 60` | 构造注入 `AgentProperties` |
| `ToolSandboxImpl.java` | `DEFAULT_TIMEOUT_SECONDS = 30`, `MAX_OUTPUT_LENGTH = 10000` | 构造注入 `ToolProperties` |
| `AnnotatedCommandTool.java` | `TIMEOUT_SECONDS = 30`, `MAX_OUTPUT_LENGTH = 10000` | 构造注入 `ToolProperties` |
| `AnnotatedScriptTool.java` | `TIMEOUT_SECONDS = 30`, `MAX_OUTPUT_LENGTH = 10000` | 构造注入 `ToolProperties` |
| `DefaultToolCallPolicy.java` | `DEFAULT_MAX_ROUNDS = 10`, `DEFAULT_MAX_RETRIES = 3`, `DEFAULT_MAX_CALLS_PER_TOOL = 20` | 构造注入 `ToolProperties` |
| `HybridPlanner.java` | `DEFAULT_TIMEOUT_MS = 30000`, `CONFIDENCE_THRESHOLD = 0.5` | 构造注入 `PlanProperties` |
| `DAGTaskPlanner.java` | `DEFAULT_TIMEOUT_MS = 30000`, `SIMPLE_TIMEOUT_MS = 10000` | 构造注入 `PlanProperties` |
| `PlanValidatorImpl.java` | `DEFAULT_MAX_NODES = 50`, `DEFAULT_TIME_BUDGET_MS = 600000` | 构造注入 `PlanProperties` |
| `TavilyWebSearchTool.java` | `@Value("${lyclaw.tool.tavily.api-key}")` | 改为构造注入 `ToolProperties` |
| `ReActPlanner.java` | `@Value("${lyclaw.plan.react.max-cycles:5}")` | 改为构造注入 `PlanProperties` |

#### 第五批：修订 lyclaw-web 的 application.yml（低风险）

最终用户配置只需要写与默认值不同的内容：

```yaml
spring:
  application:
    name: lyclaw-web-service
server:
  port: 8082

lyclaw:
  chat:
    models:
      deepseek:
        api-key: ${DEEPSEEK_API_KEY}
  storage:
    base-path: /home/lyjew/Documents/Unicom/LyClaw/LyClaw
  tool:
    tavily-api-key: ${TAVILY_API_KEY:}
```

对比当前 29 行缩减到约 15 行（删除所有冗余项）。

### 3.2 改动影响评估

| 维度 | 评估 |
|------|------|
| **新增文件** | 4 个 Properties 类（各约 40 行） |
| **修改文件** | 约 12 个类（在构造函数中增加 Properties 参数注入） |
| **删除文件** | 6 个（5 个子模块 application.yml + LyClawProperties.java） |
| **修改配置** | 1 个（lyclaw-web application.yml 精简） |
| **测试影响** | Properties 类不需要单元测试（纯数据对象）；修改的 Bean 的现有单元测试需要更新 mock |
| **编译风险** | 低 — 所有改动是纯 Java + Spring 标准机制 |
| **运行时风险** | 低 — Properties 默认值保证行为不变，用户可选覆盖 |
| **回滚难度** | 低 — git revert 即可 |

### 3.3 改动量评估

```
新增: ~200 行 Java（4 个 Properties 类）
修改: ~80 行 Java（12 个类的构造函数签名 + 字段替换）
删除: ~180 行（6 个文件 + application.yml 精简）
净增: ~100 行
```

工作量：**1-2 天**。改动集中在 framework 层，autoconfigure 和 web 层改动很小。

---

## 4. 架构图

### 4.1 配置加载流程

```
┌─────────────────────────────────────────────────────────────┐
│                    Spring Boot 启动                          │
│                                                             │
│  1. 加载 classpath:application.yml (lyclaw-web 的配置)       │
│  2. 处理 ${ENV_VAR} 占位符                                   │
│  3. @ConfigurationProperties 绑定                            │
│     ├── lyclaw.chat    → ChatProperties   (field defaults)   │
│     ├── lyclaw.storage → StorageProperties (field defaults)  │
│     ├── lyclaw.tool    → ToolProperties   (field defaults)   │
│     ├── lyclaw.agent   → AgentProperties  (field defaults)   │
│     ├── lyclaw.plan    → PlanProperties   (field defaults)   │
│     ├── lyclaw.pipeline→ PipelineProperties(field defaults)  │
│     └── lyclaw.extension→ExtensionProperties(field defaults) │
│                                                             │
│  默认值优先级:                                                │
│    命令行 > 环境变量 > application.yml > Java field init     │
│                                                             │
│  用户只需写 application.yml 中与默认值不同的项                 │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 Properties 类与 Bean 的依赖关系

```
┌─────────────────────────────────────────────────────────────────┐
│                     lyclaw-autoconfigure                         │
│                                                                 │
│  ChatAutoConfiguration                                          │
│  ├── ChatProperties chatProperties()    ← @ConfigurationProps   │
│  ├── ChatModelRegistry registry()                                │
│  └── ChatFacade chatFacade()                                     │
│                                                                 │
│  ToolAutoConfiguration                                          │
│  ├── ToolProperties toolProperties()   ← @ConfigurationProps    │
│  │   └── 注入 → ToolSandboxImpl, DefaultToolCallPolicy,         │
│  │             AnnotatedCommandTool, AnnotatedScriptTool,        │
│  │             TavilyWebSearchTool                               │
│  └── ToolAnnotationProcessor                                    │
│                                                                 │
│  ReActAutoConfiguration                                         │
│  ├── AgentProperties agentProperties() ← @ConfigurationProps    │
│  │   └── 注入 → DefaultReActEngine, ApprovalHook, ApprovalStore  │
│  └── DefaultReActEngine                                         │
│                                                                 │
│  PipelineAutoConfiguration                                      │
│  ├── PipelineProperties pipelineProps() ← @ConfigurationProps   │
│  └── PipelineStageProcessor                                     │
│                                                                 │
│  （PlanProperties 可放在 ChatAutoConfiguration 或新建）           │
│  PlanProperties planProperties()        ← @ConfigurationProps   │
│  └── 注入 → HybridPlanner, DAGTaskPlanner, PlanValidatorImpl     │
│                                                                 │
│  StorageAutoConfiguration                                       │
│  └── StorageProperties storageProps()   ← @ConfigurationProps   │
│                                                                 │
│  ProcessorAutoConfiguration                                     │
│  └── ExtensionProperties (via LyClawConfigurationProperties)     │
└─────────────────────────────────────────────────────────────────┘
```

### 4.3 用户使用全景图

```
┌──────────────────────────────────────────────────────────────┐
│                    用户项目 (lyclaw-web)                       │
│                                                              │
│  application.yml                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ server.port: 8082                                      │  │
│  │                                                        │  │
│  │ lyclaw:                                                │  │
│  │   chat:                                                │  │
│  │     models:                                            │  │
│  │       deepseek:                                        │  │
│  │         api-key: ${DEEPSEEK_API_KEY}  ← 必须覆盖        │  │
│  │       openai:        ← 加一个模型只需 4 行 YAML          │  │
│  │         provider: openai-protocol                      │  │
│  │         base-url: https://api.openai.com               │  │
│  │         api-key: ${OPENAI_API_KEY}                     │  │
│  │         model: gpt-4o                                  │  │
│  │   tool:                                                │  │
│  │     sandbox-level: SANDBOX    ← 想更严格就覆盖           │  │
│  │     tavily-api-key: ${TAVILY_API_KEY}                   │  │
│  │   agent:                                               │  │
│  │     max-tool-rounds: 50       ← 想更宽松就覆盖           │  │
│  │   storage:                                             │  │
│  │     base-path: /data/my-app   ← 改存储路径               │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│  未配置的项全部使用框架默认值：                                  │
│    lyclaw.chat.circuit-breaker.failure-threshold → 5          │
│    lyclaw.tool.max-output-length → 10000                      │
│    lyclaw.plan.max-nodes → 50                                 │
│    lyclaw.pipeline.enabled → true                             │
│    ... 等等                                                   │
└──────────────────────────────────────────────────────────────┘
```

---

## 5. 实施路线

### 第 1 步：删死代码（30 分钟）

```
删除：lyclaw-{action,plan,memory,protocol,reflect}/src/main/resources/application.yml
删除：lyclaw-framework/.../config/LyClawProperties.java
修改：lyclaw-web/.../application.yml — 删除 lyclaw.llm.* 段
修改：lyclaw-framework/.../config/LyClawConfigEndpoint.java — 改为读新 Properties
删除：lyclaw-autoconfigure/.../config/LyClawPropertiesBinder.java
编译验证
```

### 第 2 步：新建 4 个 Properties 类（1 小时）

```
新建：lyclaw-framework/.../config/ToolProperties.java
新建：lyclaw-framework/.../config/AgentProperties.java
新建：lyclaw-framework/.../config/PlanProperties.java
新建：lyclaw-framework/.../config/PipelineProperties.java
编译验证
```

### 第 3 步：注册 Bean + 替换硬编码（2-3 小时）

```
修改：ToolAutoConfiguration — 注册 ToolProperties Bean
修改：ReActAutoConfiguration — 注册 AgentProperties Bean
修改：PipelineAutoConfiguration — 注册 PipelineProperties Bean
修改：ChatAutoConfiguration — 注册 PlanProperties Bean

逐文件替换 static final → 构造注入 Properties：
  修改：DefaultReActEngine.java
  修改：ApprovalHook.java
  修改：ApprovalStore.java
  修改：ToolSandboxImpl.java
  修改：AnnotatedCommandTool.java
  修改：AnnotatedScriptTool.java
  修改：DefaultToolCallPolicy.java
  修改：HybridPlanner.java
  修改：DAGTaskPlanner.java
  修改：PlanValidatorImpl.java
  修改：TavilyWebSearchTool.java（去掉 @Value）
  修改：ReActPlanner.java（去掉 @Value）

编译验证 + 运行测试
```

### 第 4 步：精简用户配置 + 验证（30 分钟）

```
精简：lyclaw-web/.../application.yml — 只保留与默认值不同的项
启动应用，curl 测试 /api/chat
测试配置覆盖：命令行传入 --lyclaw.agent.max-tool-rounds=50
```

---

## 6. 不改动的部分

- `@Agent` / `@Tool` / `@PipelineStage` 注解机制 — 保持不动
- ChatProperties / StorageProperties / ExtensionProperties — 保持现有的 `@ConfigurationProperties` 绑定方式
- `ChatAutoConfiguration` / `StorageAutoConfiguration` — 保持现有 Bean 注册逻辑
- 所有 AutoConfiguration 的 `@ConditionalOnMissingBean` 逻辑 — 保持不动
- 所有 `@Bean` PostProcessor（ChatModelPostProcessor 等）— 保持不动
- 前端 lyclaw-ui — 完全不碰

---

## 7. 与 Spring Boot 标准做法对比

| 特性 | Spring Boot 标准 | 当前 LyClaw | 重构后 LyClaw |
|------|-----------------|-------------|--------------|
| 配置外部化 | `@ConfigurationProperties` | 混杂 | 全部 `@ConfigurationProperties` |
| 默认值 | Java field initializer | 硬编码常量 | Java field initializer |
| IDE 提示 | `spring-configuration-metadata.json` | 无 | 可生成 |
| 用户覆盖 | application.yml | application.yml（部分项无效） | application.yml（全覆盖） |
| 配置文档 | Javadoc on fields | 无 | Javadoc on fields |
| 条件装配 | `@ConditionalOnProperty` | 未使用 | 可用 |
