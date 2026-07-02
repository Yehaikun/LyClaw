# LyClaw — Java AI Agent 框架

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.14-green)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

**LyClaw** 是一个 Java 21 + Spring Boot 3 + WebFlux 的 AI Agent 框架。它提供了一套完整的多 Agent 协作基础设施：声明式 Agent 定义、ReAct 推理-行动循环、子 Agent 委派、消息驱动的 Agent Mesh、可插拔的 Pipeline 管线，以及流式 SSE 事件推送。

## 核心设计理念

```
用户请求 → Agent Mesh（路由）→ LLM Agent（ReAct 循环）
                                    ↓
                           思考 → 调用工具/子Agent → 观察结果 → 继续思考
                                    ↓
                           SSE 流式推送到前端
```

**三个核心抽象：**

| 抽象 | 角色 |
|------|------|
| **AgentMesh** | 多 Agent 系统的注册中心 + 消息路由器 + 事件总线 |
| **AgentInstance** | 消息驱动的执行单元（LLM / Tool / Proxy 三种） |
| **ReActEngine** | LLM 多轮推理-行动循环引擎，流式优先 |

---

## 模块结构

```
lyclaw-ai-framework
 ├── lyclaw-framework       SPI 接口 + 注解 + 数据模型（零 Spring 依赖）
 ├── lyclaw-autoconfigure   Spring Boot 自动配置 + BeanPostProcessor 扫描
 ├── lyclaw-action          默认实现（ToolRegistry, SkillExecutor, ToolSandbox）
 ├── lyclaw-starter         一站式 Starter POM
 ├── lyclaw-web             Spring Boot 入口 + HTTP 控制器（独立部署）
 └── lyclaw-ui              Vue 3 + Vite 前端
```

---

## Agent Mesh — 多 Agent 协作核心

`AgentMesh` 是整个框架的中枢神经系统。它不是简单的路由表，而是一个完整的 Agent 运行时环境：

```java
// 动态注册 Agent
AgentRef ref = mesh.register(AgentSpec.builder()
    .agentId("code-reviewer")
    .capability("code-review")
    .model("deepseek-v4")
    .systemPrompt("你是一个代码审查员...")
    .build());

// 向任意 Agent 发送消息
AgentMessage response = mesh.send(AgentMessage.builder()
    .to("code-reviewer")
    .payload("审查这个 PR")
    .build()).join();

// 按能力发现 Agent
List<AgentRef> reviewers = mesh.findByCapability("code-review");

// 流式对话
Flux<AgentMessage> stream = mesh.sendStream(message);
```

### 三种 Agent 类型

| 类型 | 实现 | 说明 |
|------|------|------|
| **LLMAgent** | `LLMAgentInstance` | 全量 AI Agent，拥有 system prompt + tools + model，内部走 ReAct 循环 |
| **ToolAgent** | `ToolAgentInstance` | 无状态工具 Agent，封装单个 Tool 的执行 |
| **ProxyAgent** | `ProxyAgentInstance` | 包装 `@Agent` 注解接口的 JDK 动态代理（向后兼容） |

### Agent 蓝图（AgentSpec）

```java
AgentSpec spec = AgentSpec.builder()
    .agentId("my-agent")           // 唯一标识
    .name("My Agent")              // 显示名称
    .description("...")            // 描述
    .capability("code-review")    // 能力标签（用于路由发现）
    .type(AgentRef.AgentType.LLM) // Agent 类型
    .model("deepseek-v4")         // 模型
    .systemPrompt("你是...")       // 系统提示词
    .tools(toolDefs)              // 工具定义
    .ephemeral(true)              // 临时 Agent（任务完成后自动销毁）
    .ttlMs(300_000)               // 存活时间
    .parentId("parent-id")        // 父 Agent
    .supervisionStrategy(SupervisionStrategy.RESTART)  // 错误恢复策略
    .maxRetries(3)                // 最大重试
    .build();
```

### 监督策略（Supervision Tree）

Agent 出错时的恢复策略：

| 策略 | 行为 |
|------|------|
| `RESTART` | 自动重启 Agent |
| `ESCALATE` | 上报错误到上级 Supervisor |
| `IGNORE` | 忽略错误，仅记录日志 |
| `STOP` | 停止 Agent |

---

## ReAct 引擎 — 推理-行动循环

`ReActEngine` 是 LLM 交互的核心原语。实现经典的 **Reasoning + Acting** 循环：

```
┌─────────────────────────────────────────┐
│           ReAct 循环                     │
│                                          │
│  1. 发送提示词 + 工具定义给 LLM          │
│           ↓                              │
│  2. LLM 返回 文本 或 tool_calls         │
│           ↓                              │
│  3. 如果是 tool_calls → 执行工具        │
│     → 将结果反馈给 LLM → 回到步骤 2     │
│           ↓                              │
│  4. 如果是文本 → 结束，返回结果          │
└─────────────────────────────────────────┘
```

### 流式优先设计

```java
// 流式模式（默认）：先尝试 stream=true 探测
// - 纯文本 → 直接透传 SSE chunk 给前端（真流式）
// - 有 tool_calls → 收集碎片 → 非流式 ReAct 循环 → 模拟流式输出
Flux<ServerSentEvent<String>> events = reActEngine.executeStream(
    chatFacade, request, toolExecutor);
```

### 工具审批机制

非只读工具需要用户审批：

1. LLM 发起工具调用
2. 引擎推送 `tool_approval` SSE 事件到前端
3. 前端弹窗，用户点击允许/拒绝
4. 超时自动拒绝

```java
// 设置需要审批的工具
reActEngine.setApprovalRequired(Set.of("execute_command", "send_email"));
```

---

## 子 Agent 系统（Subagent）

子 Agent 委派是 LyClaw 最强大的特性之一。LLM Agent 可以在 ReAct 循环中动态地将任务委派给其他 Agent：

```
父 Agent（ReAct 循环中）
    │
    │ LLM 决定: "这个任务应该交给 code-reviewer"
    │ 调用 delegate_to_agent 工具
    ↓
SubagentSpawner
    ├── 白名单校验（allowAgents）
    ├── 深度限制（maxSpawnDepth，递归委托深度）
    ├── 数量限制（maxChildrenPerAgent）
    ├── 并发控制（Semaphore，maxConcurrent）
    ├── 超时控制（runTimeoutSeconds）
    └── 执行子 Agent 的 ReAct 循环
         ↓
    子 Agent 的 SSE 事件实时转发到父 Agent 的进度总线
```

### 委派配置

```java
SubagentConfig config = SubagentConfig.builder()
    .delegationMode("suggest")       // suggest | prefer | off
    .allowAgents(List.of("code-reviewer", "tester", "*"))
    .maxSpawnDepth(3)                // 最大递归深度
    .maxChildrenPerAgent(5)          // 每层最大子 Agent 数
    .maxConcurrent(2)                // 最大并发子 Agent
    .runTimeoutSeconds(300)          // 超时
    .model("deepseek-v4")           // 子 Agent 模型覆盖
    .thinking("high")               // 思考级别
    .build();
```

### 三层配置合并

子 Agent 配置支持三层合并（低→高优先级）：

```
框架硬编码默认值 → application.yml 全局配置 → @Agent 注解扩展字段
```

---

## Pipeline 管线

请求处理通过可插拔的 Pipeline Stage 链：

```
HTTP 请求
    ↓
[0] ContextBuildStage    — 构建 AgentContext，加载会话上下文
[1] SecurityCheckStage   — SecurityManager 审批 + ContentFilter 链
[2] AgentRouteStage      — Agent 路由预处理
[3] RespondStage         — ReAct 引擎执行（核心阶段）
[4] MetricsStage         — 指标采集 + 追踪结束
    ↓
SSE 事件流 → 前端
```

### 自定义 Stage

只需实现 `ReactivePipelineStage` 并注册为 Spring Bean：

```java
@Component
public class MyCustomStage implements ReactivePipelineStage {
    @Override
    public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
        // 你的逻辑
        return Flux.just(sseEvent("my_event", "处理中..."));
    }

    @Override public int getOrder() { return 2; }      // 在 SecurityCheck 和 Respond 之间
    @Override public String getStageName() { return "MyCustom"; }
}
```

---

## 工具系统（Tool）

工具是 LLM 与外部世界交互的桥梁。三种注册模式：

### 模式一：纯注解（最简单）

```java
@Tool(name = "calculator", description = "执行数学计算")
public class Calculator {
    @Tool(name = "calculate", description = "计算表达式")
    public String calculate(
        @Param(name = "expression", description = "数学表达式") String expr) {
        return eval(expr);
    }
}
```

### 模式二：实现 Tool 接口（完全掌控）

```java
public class MyTool implements Tool {
    @Override public String getName() { return "my_tool"; }

    @Override
    public ToolExecutionResult execute(ToolCall toolCall, ChatContext context) {
        // 完全掌控执行逻辑、错误处理
        return ToolExecutionResult.success(result);
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
            .name("my_tool")
            .description("...")
            .parameters(params)
            .readOnly(false)        // 写工具需要用户审批
            .timeout(30_000)
            .build();
    }
}
```

### 模式三：动态 ToolProvider

```java
@Component
public class MyToolProvider implements ToolProvider {
    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        // 运行时动态决定提供哪些工具
        // 适用于 MCP 协议发现、权限驱动工具等场景
    }
}
```

### 工具执行管道

```
Tool 调用 → ToolSandbox（安全隔离）
         → ToolCallPolicy（调用频率、输出长度限制）
         → 实际执行
         → SSE 推送 tool_call 事件（含执行结果）
```

---

## Hook 系统 — 27 个生命周期钩子

`AgentHook` 提供了 27 个拦截点，让你在 Agent 执行的每个阶段注入自定义逻辑：

```java
@Component
public class MyAgentHook implements AgentHook {
    @Override
    public void beforeRequest(AgentContext ctx) {
        // 请求开始前
    }

    @Override
    public ToolExecutor wrapToolExecutor(ToolExecutor executor, AgentContext ctx) {
        // 包装工具执行器（添加日志、限流等）
        return (name, id, args) -> {
            log.info("Tool call: {}", name);
            return executor.execute(name, id, args);
        };
    }

    @Override
    public void beforeModel(ChatRequest request, AgentContext ctx) {
        // LLM 调用前：修改提示词、注入上下文
    }

    @Override
    public void afterModel(ModelResponse response, AgentContext ctx) {
        // LLM 返回后：后处理、安全过滤
    }

    @Override
    public void agentEnd(AgentContext ctx) { /* Agent 结束 */ }

    @Override
    public void subagentSpawning(AgentContext childCtx) { /* 子 Agent 即将生成 */ }

    @Override
    public void subagentSpawned(AgentContext childCtx, SubagentResult result) {
        /* 子 Agent 完成 */
    }
}
```

---

## 声明式 Agent（@Agent 注解）

在 Spring 环境中，用注解定义 Agent：

```java
@Agent(
    id = "chat",
    name = "Chat Agent",
    description = "通用对话助手",
    model = "deepseek-v4",
    delegationMode = "suggest",
    allowAgents = {"code-reviewer", "tester"},
    maxSpawnDepth = 3
)
public interface ChatAgent {
    String chat(@UserMessage String message);

    Flux<ServerSentEvent<String>> chatStream(@UserMessage String message);
}
```

框架通过 `AgentInterfaceProcessor`（BeanFactoryPostProcessor）自动扫描 `@Agent` 接口，生成 JDK 动态代理。代理背后自动组装了完整的 ReAct 循环 + Pipeline + Hook 链。

---

## 独立模式 — 无需 Spring

```java
LyClawAgent agent = LyClawAgent.configure()
    .model("deepseek-chat", "sk-your-api-key")
    .maxRetries(3)
    .circuitBreakerThreshold(5)
    .build();

String reply = agent.chat("你好，请帮我分析这段代码...");
```

`SimpleBuilder` 自动装配完整的调用链：
```
ModelConfig → DeepSeekChatModel / OpenAI协议适配器
            → RetryChatModel（指数退避重试）
            → CircuitBreakerChatModel（三态熔断）
            → DefaultChatModelRegistry → FirstAvailableRouter → DefaultChatFacade
```

---

## 模型抽象

### 适配器层

| 适配器 | 说明 |
|--------|------|
| `DeepSeekChatModel` | DeepSeek 专有适配器 |
| `OpenAiProtocolChatModel` | OpenAI 兼容协议适配器（支持任意 OpenAI 兼容 API） |

### 装饰器链

所有 ChatModel 自动被装饰器包装：

```
CircuitBreakerChatModel    ← 三态熔断（CLOSED → OPEN → HALF_OPEN）
    ↓
RetryChatModel             ← 指数退避重试 + 随机抖动
    ↓
FallbackChatModel          ← 降级链（主模型失败时按序尝试备用模型）
    ↓
原始 ChatModel             ← 实际 API 调用
```

### YAML 配置

```yaml
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
        circuit-breaker:
          failure-threshold: 5
          half-open-after-seconds: 30
```

---

## SSE 事件流

前端通过 Server-Sent Events 实时消费 Agent 执行状态：

| 事件 | 说明 |
|------|------|
| `session_created` | 会话创建 |
| `thinking` | LLM 思考/推理过程 |
| `message` | 文本内容（逐 chunk 流式推送） |
| `tool_approval` | 需要用户审批的工具调用 |
| `tool_call` | 工具执行状态（executing → done + result） |
| `subagent_progress` | 子 Agent 执行进度（实时转发） |
| `pipeline_status` | 管线阶段状态 |
| `metrics` | 执行指标（Token 消耗、耗时等） |
| `done` | 完成 |

---

## 快速开始

### 前置条件

- Java 21+
- Maven 3.8+
- DeepSeek API Key（或其他 OpenAI 兼容 API）

### 1. 克隆项目

```bash
git clone https://github.com/Yehaikun/LyClaw.git
cd LyClaw
```

### 2. 配置 API Key

```bash
export DEEPSEEK_API_KEY=sk-your-key
```

### 3. 编译运行

```bash
mvn clean package -DskipTests
cd lyclaw-web
mvn spring-boot:run
```

### 4. 访问

- 前端界面: `http://localhost:8082`
- API: `POST http://localhost:8082/api/chat/stream`

### 5. 独立模式（无需启动服务）

```java
LyClawAgent agent = LyClawAgent.configure()
    .model("deepseek-chat", "sk-xxx")
    .build();
String reply = agent.chat("用 Java 写一个快速排序");
```

---

## License

MIT © 2025 LyClaw

---

*Built with Java 21, Spring Boot 3.5.14, Reactor (WebFlux), Vue 3*
