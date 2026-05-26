# LyClaw AI Agent Framework — 项目交界文档

> 写给另一个 AI 的完整项目说明书。涵盖架构、模块、扩展点、关键流程和当前状态。

---

## 一、项目定位

LyClaw 是一个 **Java 21 + Spring Boot 3.5.14 + WebFlux（Reactor）** 的 AI Agent 框架，对标 LangChain4j / Spring AI，但更聚焦于：

- **声明式 Agent 定义** — `@Agent` 注解 + 接口定义，自动生成动态代理
- **可插拔 Pipeline 管线** — ContextBuild → SecurityCheck → Respond → Metrics
- **ReAct 推理-行动循环** — 流式优先，自动检测 tool_calls，支持用户审批
- **多层 ChatModel 装饰器** — Retry → CircuitBreaker → Fallback 自动包装
- **全响应式（Reactive）** — 基于 Reactor（WebFlux），端到端非阻塞

---

## 二、模块结构

```
lyclaw-ai-framework (父 POM)
 ├── lyclaw-framework         L0: 核心 SPI、注解、数据模型、无 Spring 运行时依赖
 ├── lyclaw-autoconfigure     L1: Spring Boot @AutoConfiguration、BeanPostProcessor
 ├── lyclaw-action            L4: 默认实现（ToolRegistry、SkillExecutor、ToolSandbox）
 ├── lyclaw-starter           一站式 POM（仅依赖聚合）
 ├── lyclaw-web               单体部署单元（不在父 POM modules 中，独立运行）
 └── lyclaw-ui                Vue 3 + Vite + TypeScript 前端（同上，独立）
```

### 依赖层次

```
lyclaw-framework (SPI + 注解 + 模型)
        ↑
lyclaw-autoconfigure (自动配置 + 处理器)
        ↑
lyclaw-action (默认实现)
        ↑
lyclaw-starter (聚合 POM)
        ↑
lyclaw-web (Spring Boot 入口 + HTTP 控制器 + @Agent 接口)
```

---

## 三、核心技术架构

### 3.1 请求处理流程

```
HTTP POST /api/chat/stream 或 /api/chat
    ↓
ChatController → ChatAgent (@Agent 代理)
    ↓
AgentInvocationHandler.invoke()
    ↓
AgentContext 构建 → HookRegistry.dispatchBeforeRequest()
    ↓
Pipeline Stage 链（顺序执行）:
    [0] ContextBuildStage     — 加载会话上下文
    [1] SecurityCheckStage    — SecurityManager 审批 + ContentFilter 链
    [3] RespondStage          — ReAct 引擎执行（核心）
    [5] MetricsStage          — 指标采集 + 追踪结束
    ↓
SSE 事件流返回前端
```

### 3.2 SSE 事件类型

前端通过 Server-Sent Events 消费响应：

| 事件名 | 触发阶段 | 数据内容 |
|--------|----------|----------|
| `session_created` | ChatController | sessionId, agentId |
| `context_build_start/complete` | ContextBuildStage | 状态消息 |
| `intercept_start/blocked/complete` | SecurityCheckStage | 安全结果 |
| `respond_start` | RespondStage | 状态消息 |
| `message` | RespondStage / ReActEngine | 文本内容（逐 chunk） |
| `thinking` | RespondStage / ReActEngine | 思考/推理内容 |
| `tool_approval` | DefaultReActEngine | toolCallId, toolName, arguments（需用户审批） |
| `tool_call` | DefaultReActEngine | toolCallId, status(executing/done), result |
| `respond_complete/metrics/done` | MetricsStage | 最终状态 |

### 3.3 AgentContext — 请求上下文对象

`AgentContext` 是贯穿整个管线的请求上下文，包含：
- `ChatRequest` / `userMessage` / `sessionId`
- `tracing` — 分布式追踪
- `chatContext` — 安全审批用上下文
- `sandboxLevel` — 安全审批结果
- `toolRegistry` — 工具注册表
- `successCount` / `failCount` / `toolResults` — 执行统计
- `terminated` / `pipelineOk` — 状态标志
- `currentStage` — 当前阶段名

---

## 四、核心扩展点（SPI）

所有扩展点都支持 **`@Component` + 接口实现 → Spring 自动发现**，无需手动注册。

### 4.1 ChatModel — AI 模型适配器

```java
public interface ChatModel {
    String provider();
    String model();
    ModelCapabilities capabilities();
    Flux<ModelResponse> stream(ChatRequest request);
    int countTokens(String text);
}
```

- **自动发现**: `@ChatModel` 注解 → `ChatModelPostProcessor` 扫描
- **装饰器链**: `FallbackChatModel → RetryChatModel → CircuitBreakerChatModel → 原始Model`
- **内置实现**: `DeepSeekChatModel`、`OpenAiProtocolChatModel`（OpenAI 兼容协议）
- **YAML 配置**: `lyclaw.chat.models.*` 支持零代码接入

### 4.2 ReactivePipelineStage — 管线阶段

```java
public interface ReactivePipelineStage {
    Flux<ServerSentEvent<String>> execute(AgentContext ctx);
    int getOrder();
    String getStageName();
}
```

- **自动发现**: `@PipelineStage(after=X, before=Y)` → `PipelineStageProcessor` 拓扑排序
- **排序回退**: 拓扑排序失败时自动降级 `getOrder()` 数值排序
- **内置阶段**: ContextBuild(0) → SecurityCheck(1) → Respond(3) → Metrics(5)
- **插入点**: order 值步长 2（0,1,3,5...），新阶段可在中间插入

### 4.3 Tool — LLM 工具

```java
@Tool(name = "my_tool", description = "啥")
@Param(name = "arg1", description = "...")
```

- **三种注册模式**:
  1. 类级 `@Tool` + 方法级 `@Tool`/`@Param` → `AnnotatedToolAdapter`
  2. 类级 `@Tool` + 实现 `Tool` 接口 → 直接注册
  3. 无 `@Tool` 但实现 `Tool` 接口 → 旧版兼容
- **自动发现**: `ToolAnnotationProcessor`（BeanPostProcessor）
- **动态工具**: `ToolProvider` 接口允许运行时决定工具列表，由 `DefaultToolRegistry.onContextRefreshed()` 扫描

### 4.4 SecurityManager — 安全审批

```java
public interface SecurityManager {
    ApprovalResult approve(ChatContext context, String action);
    void revoke(String sessionId);
    boolean checkPermission(String userId, String action);
    List<String> getEffectivePolicies();
}
```

- **默认实现**: `PermissiveSecurityManager` — 允许全部，WARN 日志提示
- **覆盖方式**: `implements SecurityManager` + `@Component` → 自动替换

### 4.5 ContentFilter — 内容过滤

```java
public interface ContentFilter {
    FilterResult filter(String content, ChatContext context);
    String getFilterName();
}
```

- **链式执行**: 多个 `ContentFilter` bean 会按顺序执行，任一 reject 则终止
- **覆盖方式**: `implements ContentFilter` + `@Component`

### 4.6 AgentHook — Agent 生命周期钩子

```java
public interface AgentHook extends ModelLifecycleHook, ToolLifecycleHook,
    SessionLifecycleHook, AgentLifecycleHook, SubagentLifecycleHook,
    MessageLifecycleHook, CompactionLifecycleHook { }
```

- **27 个钩子点**：`beforeRequest`, `beforeModel`, `afterModel`, `beforeToolCall`, `afterToolCall`, `agentEnd` 等
- **自动发现**: `ReActAutoConfiguration` 创建 `HookRegistry` bean，收集所有 `AgentHook` bean
- **分派位置**: `RespondStage` 中已分派 `beforeRequest`, `beforeToolCall`, `afterToolCall`, `agentEnd`

### 4.7 ModelRouter — 模型路由

```java
public interface ModelRouter {
    RoutingDecision route(ChatRequest request, Object context);
}
```

- **自动发现**: `@ModelRouter` → `ModelRouterPostProcessor`
- **内置实现**: `FirstAvailableRouter`（按注册顺序返回第一个可用模型）

### 4.8 ToolProvider — 动态工具提供者

```java
@FunctionalInterface
public interface ToolProvider {
    ToolProviderResult provideTools(ToolProviderRequest request);
}
```

- 将 `ToolDefinition`（定义，给 LLM 看）与 `ToolExecutor`（执行逻辑）解耦
- 适用于 MCP 协议发现、权限驱动工具等场景
- 通过 `DefaultToolRegistry.onContextRefreshed()` 自动收集

---

## 五、ChatModel 装饰器链

装饰器在 `ChatModelPostProcessor` 中自动应用，包装顺序为（从外到内）：

```
CircuitBreakerChatModel      → 三态熔断（CLOSED→OPEN→HALF_OPEN）
    ↓
RetryChatModel              → 指数退避重试（含随机抖动）
    ↓
FallbackChatModel           → 按顺序尝试降级链
    ↓
OriginalChatModel           → 实际 API 调用
```

各装饰器通过 `@CircuitBreaker`, `@RetryPolicy`, `@Fallback` 注解参数驱动。

---

## 六、ReAct 引擎（DefaultReActEngine）

### 6.1 流式模式（主要路径）

```
executeStream()
    ↓
state = 0 (buffering 思考)
    ↓ 有 tool_calls          ↓ 有 content                     ↓ 仅 thinking
state = 2 (收集模式)     state = 1 (透传模式)           继续缓冲
    ↓                        ↓
合并 chunks              逐 token 发 message SSE
    ↓                        ↓
multiRoundReActFlux()     完成
    ↓
emitRoundToolCallEvents()  →  需要审批? → emitApprovalFlow() → future.get()
    ↓
continueReActRounds()      →  下一轮（最多 maxToolRounds 轮）
```

### 6.2 审批流程

非只读工具（`readonly=false`）需要用户审批：
1. `CompletableFuture<Boolean>` 注册到 `pendingApprovals`（Map）
2. 前端收到 `tool_approval` SSE 事件，显示弹窗
3. 用户点击允许/拒绝 → `POST /api/approval/respond` → `approve()`/`deny()`
4. 超时自动拒绝（`AgentProperties.approvalStoreTimeoutSeconds`）

### 6.3 工具执行隔离

工具执行通过 `Schedulers.boundedElastic()` 隔离，防止阻塞 Netty 事件循环线程。

---

## 七、Agent 创建方式

### 7.1 Spring 环境 — @Agent 注解

```java
@Agent
public interface ChatAgent {
    String chat(@UserMessage String message);
    Flux<ServerSentEvent<String>> chatStream(@UserMessage String message);
}
```

- `AgentInterfaceProcessor`（BeanFactoryPostProcessor）扫描 `@Agent` 接口
- 为每个接口创建 `AgentProxyFactoryBean`（FactoryBean）
- 首次 `getBean()` 时，`AgentProxyFactory` 通过 `Proxy.newProxyInstance()` 创建 JDK 动态代理
- 代理 `invoke()` 时：构建 `AgentContext` → 分发 hooks → 执行 Pipeline Stage → 返回结果

### 7.2 独立模式 — 无 Spring

```java
LyClawAgent agent = LyClawAgent.configure()
    .model("deepseek-chat", "sk-xxx")
    .maxRetries(3)
    .build();
String reply = agent.chat("你好");
```

`SimpleBuilder` 自动装配：`ModelConfig` → `DeepSeekChatModel`/`OpenAiProtocolChatModel` → `RetryChatModel` → `CircuitBreakerChatModel` → `DefaultChatModelRegistry` → `FirstAvailableRouter` → `DefaultChatFacade`。

---

## 八、自动配置清单

所有 @AutoConfiguration 注册在 `lyclaw-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：

| 配置类 | 注册内容 |
|--------|----------|
| `LyClawBaseAutoConfiguration` | 包级 @ComponentScan |
| `ToolAutoConfiguration` | `ToolProperties`、`ToolAnnotationProcessor`、`ConditionFilter`、`DefaultToolRegistry` |
| `PipelineAutoConfiguration` | `PipelineProperties`、`PipelineStageProcessor`、`ExtensionFacade`、4 个内置 Stage、`PermissiveSecurityManager` |
| `InterceptorAutoConfiguration` | 已废弃的拦截器系统 |
| `ChatAutoConfiguration` | `ChatProperties`、`ChatModelRegistry`、`FirstAvailableRouter`、`ChatFacade` |
| `ReActAutoConfiguration` | `AgentProperties`、`DefaultReActEngine`、`HookRegistry` |
| `ProcessorAutoConfiguration` | `ChatModelPostProcessor`、`ModelRouterPostProcessor`、`InteractionModeProcessor`、`OpenAiProtocolAutoConfigurator` |
| `AgentProxyAutoConfiguration` | `AgentConfigResolver`、`AgentProxyFactory`、`AgentInterfaceProcessor`、`SubagentSpawner`、`DelegateToAgentToolProvider` 等 |
| `LyClawPropertiesBinder` | YAML 配置绑定 |
| `TraceAutoConfiguration` | 分布式追踪 |

### BeanPostProcessor 清单

| 处理器 | 扫描对象 | 阶段 |
|--------|----------|------|
| `ToolAnnotationProcessor` | `@Tool` + `Tool` 接口 | BPP |
| `ChatModelPostProcessor` | `@ChatModel` + 装饰器注解 | BPP |
| `ModelRouterPostProcessor` | `@ModelRouter` | BPP |
| `PipelineStageProcessor` | `ReactivePipelineStage` 接口 | BPP + SmartInitializingSingleton |
| `InteractionModeProcessor` | `@InteractionMode` | BPP |
| `AgentInterfaceProcessor` | `@Agent` 接口 | BFPP（BeanFactoryPostProcessor） |
| `InterceptorProcessor` | `Interceptor` 接口 | BPP（已废弃） |

---

## 九、关键配置（application.yml 结构）

配置文件: `lyclaw-web/src/main/resources/application.yml`

```
server.port: 8082

lyclaw:
  chat:
    default-provider: deepseek
    default-model: deepseek-v4-flash
    models:
      deepseek:
        provider: openai-protocol
        base-url: https://api.deepseek.com
        api-key: ${DEEPSEEK_API_KEY}
        model: deepseek-v4-flash
        retry:
          max-attempts: 3
          backoff: exponential
        fallback: []
        circuit-breaker:
          failure-threshold: 5
          half-open-after-seconds: 30

  tool:
    default-timeout-ms: 30000
    max-output-length: 10000
    max-calls-per-tool: 20
    sandbox-level: PROCESS

  agent:
    default-mode: react
    max-tool-rounds: 100
    approval-timeout-seconds: 30

  pipeline:
    enabled: true
    timeout-ms: 300000
```

完整配置见 application.yml（约 730 行），所有配置项均有框架默认值。

---

## 十、HTTP API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat` | 同步聊天 |
| POST | `/api/chat/stream` | 流式聊天（SSE） |
| GET | `/api/web/health` | 健康检查 |
| GET | `/api/web/health/liveness` | 存活检查 |
| GET | `/api/web/health/readiness` | 就绪检查 |
| POST | `/api/approval/respond` | 工具审批响应 |
| GET | `/actuator/health` | Actuator 健康 |
| GET | `/actuator/prometheus` | Prometheus 指标 |
| GET | `/swagger-ui.html` | Swagger 文档 |

---

## 十一、前端简述

`lyclaw-ui` — Vue 3 + Vite + TypeScript + Pinia

```
src/
 ├── views/          10 个页面：Dashboard, Chat, Sessions, Tools, Agent, Models, Plan, Memory, Settings
 ├── components/     16 个组件：ToolApprovalDialog, ModelSelector, SessionList, ChatMessage 等
 ├── api/            7 个 API 模块：client, chat, agent, tool, memory, plan, action
 ├── stores/         6 个 Pinia 存储
 ├── router/         路由配置
 └── types/          TypeScript 类型定义
```

---

## 十二、当前状态和已知缺口

### 已完成

- [x] 核心 ReAct 引擎（流式 + 非流式）
- [x] Pipeline 管线（4 个阶段）
- [x] 声明式 `@Agent` 代理创建
- [x] `@Tool` + 三种工具注册模式
- [x] ChatModel 装饰器链（Retry → CircuitBreaker → Fallback）
- [x] OpenAI 协议适配器 + DeepSeek 专有适配器
- [x] YAML 模型配置 + 自动注册
- [x] 用户审批流（CompletableFuture）
- [x] 独立模式 `LyClawAgent.configure()`（无 Spring）
- [x] HookRegistry + AgentHook 自动发现
- [x] PermissiveSecurityManager 默认实现
- [x] ContentFilter 多过滤器链支持
- [x] PipelineStage 排序回退（`getOrder()`）
- [x] SSE 流式事件推送
- [x] 子 Agent（Subagent）基础框架
- [x] 分布式追踪（TraceContext）

### 未完成 / 缺口

- [ ] **持久化存储** — `SessionStore`, `MemoryStore` 接口已设计但未实现
- [ ] **记忆系统** — `MemorySystem` 在重构中，当前骨架
- [ ] **容器沙箱** — Docker/Podman 后端配置存在但未完整实现
- [ ] **完整测试覆盖** — 单元测试和集成测试不完整
- [ ] **前端完整集成** — 部分视图是占位符
- [ ] **多模态支持** — 模型目录已就绪，但多模态调用未验证
- [ ] **技能系统** — SkillRegistry/Executor 基础架构存在但技能定义不完整
- [ ] **身份系统** — Identity 配置存在但基础架构待实现
- [ ] **Agent 路由** — 多 Agent 路由配置处理但不完整
- [ ] **心跳** — Heartbeat 配置已设计但待验证

---

## 十三、给 AI 助手的提示

1. **阅读代码的顺序建议**: `HANDOVER.md` → `pom.xml`（父 + 各模块） → 核心 SPI 接口 → `AutoConfiguration.imports` → `DefaultReActEngine` → `Pipeline*Stage` 实现
2. **关键设计决策**: 
   - 全响应式（Reactor），工具执行通过 `boundedElastic` 隔离
   - 扩展点优先 `BeanPostProcessor` 自动发现
   - 装饰器链通过注解反射驱动（`@RetryPolicy` → `RetryChatModel`）
   - 审批流使用 `CompletableFuture` 桥接响应式与非响应式
3. **代码生成惯例**: Java 21、Lombok（`@Slf4j`）、`Atomic*` 线程安全、`Map.of()`/`List.of()`、switch 表达式

---

*文档生成时间: 2026-05-26 | 项目根路径: /home/lyjew/Documents/Unicom/LyClaw*
