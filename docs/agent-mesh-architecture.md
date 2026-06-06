# LyClaw Agent Mesh — 多 Agent 调度架构重新设计

## 0. 设计灵感来源

| 来源 | 借鉴了什么 | 为什么适用于 LyClaw |
|------|-----------|-------------------|
| **Actor Model** (Erlang OTP / Akka) | Agent = Virtual Actor，消息驱动通信，Supervision Tree 错误恢复 | 每个 Agent 是独立 actor，有自己的 state/mailbox/channel，错误不会传播 |
| **Claude Code** | Tool = Agent 的概念统一，LLM 通过 tool calls 发起委托 | Tool 和 Agent 是同一接口，调度引擎统一处理 |
| **Temporal.io / Azure Durable Functions** | Workflow as Code，Orchestrator 跟踪执行状态，支持 timeout/retry/补偿 | 长时多 Agent 协作需要可靠的执行追踪 |
| **OpenTelemetry Trace** | TraceId + SpanId 追踪跨 Agent 调用链路 | 调试和观察多 Agent 交互 |
| **LangGraph** | 有向图定义 Agent 协作流程，条件分支 + 循环 | DAG 模式和多轮辩论模式的实现基础 |
| **WanD (Weights & Biases)** | Agent 执行轨迹可视化，步骤级观察 | 调试复杂多 Agent 场景 |
| **Reactive Streams** (Reactor) | Flux 流式响应背压 | Agent 间流式数据传输 |

---

## 1. 最核心的思维转变：从"方法调用"到"消息传递"

```
当前：ToolExecutor.execute(name, args) → return result        （方法调用，同步阻塞）
      SubagentSpawner.spawn(...).block() → SubagentResult     （方法调用，同步阻塞）

新设计：mesh.send(AgentMessage) → CompletableFuture<AgentMessage>  （消息传递，可同步可异步）
        mesh.sendStream(AgentMessage) → Flux<AgentMessage>        （消息传递，流式）
```

**为什么消息传递？**

| 维度 | 方法调用 | 消息传递 |
|------|---------|---------|
| 位置 | 必须同进程 | 可跨进程/网络 |
| 并发 | 调用者线程阻塞 | 调用者不阻塞 |
| 错误 | 异常传播到调用栈 | 错误封装在消息中 |
| 追踪 | 靠调用栈 | 靠 correlationId |
| 重试 | try-catch 重试 | 消息队列重试 |
| 审计 | 难以记录 | 每条消息可持久化 |
| 流式 | 必须 callback/Flux | 消息本身就是流 |

---

## 2. Agent 生命周期设计

### 2.1 状态机

```
                   ┌─────────────────────────────────────┐
                   │         Agent 生命周期                │
                   └─────────────────────────────────────┘

                           register(AgentSpec)
                                │
                              PENDING ◄──────────┐
                                │                 │
                           start()
                                │                 │
                           ┌────┴─────┐           │
                           │ STARTING │           │
                           └────┬─────┘           │
                                │                  │
                           ┌────▼─────┐           │
                    ┌──────│  ACTIVE  │──────┐     │
                    │      └────┬─────┘      │     │
              send(msg)    ┌────┴────┐  timeout    │
                    │      │ PROGRESS │  or error   │
                    │      └────┬─────┘      │     │
                    │           │             │     │
                    │      ┌────▼─────┐      │     │
                    │      │ DEGRADED │◄─────┘     │
                    │      └────┬─────┘            │
                    │           │  recover()       │
                    │           └──────┐           │
                    │       ┌──────────┘           │
                    │       ▼                      │
                    │  ┌───────────┐               │
                    │  │  ACTIVE   │ (恢复)        │
                    │  └───────────┘               │
                    │                              │
                    │ stop()               destroy()
                    │                              │
                    ▼                              ▼
              ┌─────────┐                   ┌───────────┐
              │ STOPPED │                   │ DESTROYED │
              └─────────┘                   └───────────┘

  状态       含义                      谁能触发
  ──────    ─────────────────────────  ──────────────
  PENDING   Spec 已注册，未初始化        agentMesh.register()
  STARTING  正在初始化（加载模型/工具）   系统自动
  ACTIVE    就绪，可处理消息            系统自动（初始化完）
  PROGRESS  正在处理消息                正在执行 send() 时
  DEGRADED  部分失败（某工具不可用）     系统自动
  STOPPED   已停止，可恢复重新启动       mesh.stop()
  DESTROYED 已销毁，资源已释放          mesh.unregister()
```

### 2.2 生命周期事件

```java
public enum AgentLifecycleEvent {
    CREATED,      // register()
    STARTING,     // start() 
    STARTED,      // 就绪
    MESSAGE_RECEIVED,  // 收到消息
    MESSAGE_SENT,      // 发出消息
    DEGRADED,     // 部分失败
    RECOVERED,    // 从降级恢复
    STOPPING,     // 正在停止（draining）
    STOPPED,      // 已停止
    DESTROYED     // 已销毁
}
```

每个事件发布到 Agent Mesh 的 EventBus，监听者可以：

```java
mesh.addListener(event -> {
    switch (event.getType()) {
        case AgentLifecycleEvent.DEGRADED -> notifyOps("Agent degraded: " + event.getAgentId());
        case AgentLifecycleEvent.DESTROYED -> cleanupResources(event.getAgentId());
    }
});
```

### 2.3 Supervision Tree（错误恢复）

```
Supervisor (Orchestrator Agent)
  │
  ├── Worker Agent A ── 挂了 ── 自动重启
  ├── Worker Agent B ── 挂了 ── 上报 Supervisor
  └── Worker Agent C ── 挂了 ── 按策略: restart | escalate | ignore
```

Supervisor 策略在 AgentSpec 中配置：

```java
AgentSpec spec = AgentSpec.builder()
    .agentId("worker-a")
    .supervisionStrategy(SupervisionStrategy.RESTART)
    .maxRetries(3)
    .build();
```

---

## 3. 通信机制设计

```
┌──────────────────────────────────────────────────────────────┐
│                    Agent Communication Bus                    │
│                                                                │
│      ┌──────────┐    ┌──────────┐    ┌──────────┐            │
│      │ Agent A  │    │ Agent B  │    │ Agent C  │            │
│      │          │    │          │    │          │            │
│      │ ┌──────┐ │    │ ┌──────┐ │    │ ┌──────┐ │            │
│      │ │Mail  │ │    │ │Mail  │ │    │ │Mail  │ │            │
│      │ │box   │ │    │ │box   │ │    │ │box   │ │            │
│      │ └──────┘ │    │ └──────┘ │    │ └──────┘ │            │
│      └────┬─────┘    └────┬─────┘    └────┬─────┘            │
│           │               │               │                    │
│           ▼               ▼               ▼                    │
│      ┌──────────────────────────────────────────────────┐     │
│      │              AgentMesh Router                     │     │
│      │  直接路由 │ 能力路由 │ 广播路由 │ 扇出路由       │     │
│      └──────────────────────────────────────────────────┘     │
│           │               │               │                    │
│           ▼               ▼               ▼                    │
│      ┌──────────────────────────────────────────────────┐     │
│      │           Transport Layer (本地/远程)              │     │
│      │  InProcessTransport │ gRPCTransport │ RedisTransport │  │
│      └──────────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────────────┘
```

### 3.1 消息协议

```java
public final class AgentMessage {

    // ── 路由字段 ──
    String messageId;         // 消息唯一标识 (UUID)
    String from;              // 发送者 Agent ID
    String to;                // 接收者 Agent ID (null = 广播/能力路由)
    String capability;        // 按能力路由时使用 (to 为 null 时生效)

    // ── 调用链追踪 ──
    String correlationId;     // 关联 ID——连接 REQUEST 和 RESPONSE
    String traceId;           // 调用链追踪 ID (OpenTelemetry)
    String parentSpanId;      // 父级 Span ID

    // ── 消息内容 ──
    MessageType type;         // REQUEST | RESPONSE | STREAM | PROGRESS | EVENT | ERROR | CANCEL
    String payload;           // 消息体 (JSON)
    Map<String, Object> metadata;  // 元数据

    // ── 控制字段 ──
    long timestamp;           // 发送时间戳
    long ttlMs;               // 生存时间 (超时毫秒)
    int priority;             // 优先级 (0=最低, 10=最高)

    // ── 流式字段 ──
    Long streamSeq;           // 流式序号 (STREAM 类型用)
    Boolean streamEnd;        // 是否为流式最后一条
}
```

### 3.2 消息类型详解

```java
public enum MessageType {
    /**
     * REQUEST: 期望一个 RESPONSE。
     * 调用方创建 REQUEST 消息发送给目标 Agent，
     * 目标 Agent 处理完成后回复 RESPONSE。
     * 关联方式：correlationId。
     *
     * 发送方可以选择：
     * - 同步等待 (mesh.send().join())
     * - 异步回调 (mesh.send().thenAccept())
     * - 在后续轮次中通过 correlationId 查询结果
     */
    REQUEST,

    /**
     * RESPONSE: 对 REQUEST 的回复。
     * correlationId 必须与对应的 REQUEST 一致。
     */
    RESPONSE,

    /**
     * STREAM: 流式数据块。
     * 用于 SSE 风格的流式响应。
     * streamSeq 表示序号，streamEnd 表示结束。
     */
    STREAM,

    /**
     * PROGRESS: 进度更新。
     * 长任务执行过程中定期发送。
     * payload 为进度描述 JSON: {"percent": 50, "stage": "analyzing"}
     */
    PROGRESS,

    /**
     * EVENT: 事件通知，不期望回复。
     * 用于广播状态变化、Agent 生命周期事件。
     */
    EVENT,

    /**
     * ERROR: 错误消息。
     * REQUEST 的处理过程中发生错误时回复此类型。
     * payload 包含错误信息。
     */
    ERROR,

    /**
     * CANCEL: 取消请求。
     * 发送方可以发送 CANCEL 来取消一个进行中的 REQUEST。
     * 通过 correlationId 指定要取消的请求。
     */
    CANCEL
}
```

### 3.3 路由策略

```java
public interface AgentRouter {
    /**
     * 将消息路由到目标 Agent。
     * 根据消息中的 to 字段或 capability 字段决定目标。
     */
    List<AgentRef> route(AgentMessage message, RoutingContext ctx);

    String routerName();
}
```

内置路由策略：

```
路由类型        to 字段    capability 字段    行为
───────────────────────────────────────────────────────
直接路由        "agent-x"  null              发送到 agent-x
能力路由        null       "code-review"      发送给第一个匹配的 Agent
广播路由        "*"        null              发送给所有 Agent
扇出路由        null       ["cap1", "cap2"]   发送给所有匹配的 Agent
轮询路由        "group:g1"  null              从 group g1 中选一个
```

### 3.4 传输层

```
InProcessTransport    ← 默认，同 JVM 内，直接 ConcurrentHashMap 查找 + CompletableFuture
gRPCTransport         ← 跨进程，AgentRef 包含 host:port
RedisTransport        ← 跨进程，通过 Redis Pub/Sub + Stream
```

默认使用 InProcessTransport（零依赖），远程传输通过插件提供。

---

## 4. 消息的统一：Tool = Agent

**这是整个设计最关键的洞察。**

```
当前：
  ┌──────────────┐       ┌──────────────┐       ┌──────────────┐
  │ LLM 调用工具  │ ──→   │ ToolExecutor │ ──→   │ 返回结果     │
  │              │       │ (方法调用)   │       │ (字符串)     │
  └──────────────┘       └──────────────┘       └──────────────┘

  ┌──────────────┐       ┌────────────────┐      ┌──────────────┐
  │ LLM 委托子   │ ──→   │ SubagentSpawn  │ ──→  │ SubagentRes  │
  │ Agent        │       │ .spawn().block │      │ .format()    │
  └──────────────┘       └────────────────┘      └──────────────┘

  工具和子 Agent 走完全不同的代码路径！！！
```

```
新设计：

                    ┌──→ ToolAgent (无状态，单步)
  消息 ──→ Agent ──┤
                    └──→ LLMAgent (有状态，多步推理)

  Agent 的统一接口：send(AgentMessage) → CompletableFuture<AgentMessage>

  ┌──────────────┐       ┌──────────────────┐      ┌──────────────────┐
  │ LLM 检测到    │       │ mesh.send({      │      │ ToolAgent 或      │
  │ 需要工具/子   │ ──→   │   to: toolId,    │ ──→  │ LLMAgent 处理     │
  │ Agent        │       │   payload: args  │      │ → 返回 RESPONSE  │
  │              │       │   correlationId  │      │                  │
  └──────────────┘       └──────────────────┘      └──────────────────┘
```

**为什么统一？**

| 场景 | 当前（不统一） | 新设计（统一） |
|------|-------------|--------------|
| 调用翻译工具 | `toolExecutor.execute("translate", args)` | `mesh.send({to: "translate-tool", payload})` |
| 委托代码审查 | `spawner.spawn("reviewer", task).block()` | `mesh.send({to: "reviewer", payload: task})` |
| 同步异步转换 | 不适用 | 两种调用都返回 `CompletableFuture` |
| 错误处理 | 工具返回 error 字符串，子 Agent 返回 SubagentResult | 统一为 `AgentMessage{type: ERROR}` |
| 追踪 | 无内置追踪 | `traceId` + `correlationId` 跨所有调用 |
| 流式 | 子 Agent 专门传 emitter | 统一使用 `STREAM` 消息 |

---

## 5. 同步 vs 异步调用设计

### 5.1 三种交互模式

```java
public interface AgentMesh {

    // ── 模式 1: 同步请求-响应 ──
    // 调用方阻塞等待结果。适用于短操作（翻译、搜索、数学计算）。
    default AgentMessage sendAndWait(AgentMessage request) {
        return send(request).join();
    }

    // ── 模式 2: 异步回调 ──
    // 调用方不阻塞，通过 CompletableFuture 获取结果。
    // 适用于长操作（代码审查、文档生成）。
    CompletableFuture<AgentMessage> send(AgentMessage request);

    // ── 模式 3: 流式 ──
    // 调用方通过 Flux 接收多个消息块。
    // 适用于流式生成、进度追踪。
    Flux<AgentMessage> sendStream(AgentMessage request);
}
```

### 5.2 使用场景

```java
// 场景 1: 简单工具调用——同步
AgentMessage result = mesh.sendAndWait(AgentMessage.builder()
    .to("calculator")
    .payload("{\"a\": 1, \"b\": 2, \"op\": \"add\"}")
    .build());

String answer = result.getPayload(); // "3"

// 场景 2: 委托给子 Agent——异步
String correlationId = UUID.randomUUID().toString();
mesh.send(AgentMessage.builder()
    .to("code-reviewer")
    .correlationId(correlationId)
    .payload("Review PR #42: https://github.com/...")
    .ttlMs(300_000)  // 5 分钟超时
    .build())
    .thenAccept(response -> {
        log.info("Review result: {}", response.getPayload());
        // 将结果写入父 Agent 的对话上下文
    });

// 场景 3: 流式生成——streaming
mesh.sendStream(AgentMessage.builder()
    .to("story-writer")
    .payload("写一个关于 AI 的短故事")
    .build())
    .subscribe(
        chunk -> appendToUI(chunk.getPayload()),
        error -> showError(error.getMessage()),
        () -> markComplete()
    );
```

### 5.3 超时与取消

```java
// 设置超时
CompletableFuture<AgentMessage> future = mesh.send(request);
try {
    AgentMessage result = future.get(30, TimeUnit.SECONDS);
} catch (TimeoutException e) {
    // 自动发送 CANCEL 消息给目标 Agent
    mesh.send(AgentMessage.builder()
        .type(MessageType.CANCEL)
        .correlationId(request.getCorrelationId())
        .build());
}

// 由调用方主动取消
future.cancel(true);  // 也会触发 CANCEL 消息
```

### 5.4 异步完成的两种通知方式

子 Agent 完成后父 Agent 如何知道？两种方式：

**方式 A：Future 回调（适用于同步/异步场景）**

```java
// 父 Agent 发送请求
CompletableFuture<AgentMessage> future = mesh.send(request);

// 父 Agent 继续做其他事情（不需要等待）
doOtherWork();

// 子 Agent 完成后自动回调
future.thenAccept(response -> {
    // 把结果写入父 Agent 的对话上下文
    parentContext.appendResult(response);
});
```

**方式 B：消息驱动（适用于祖父孙场景）**

```java
// 父 Agent 在处理消息 A 的过程中，发送消息 B 给子 Agent
// 父 Agent 并不阻塞等待 B，而是返回"部分结果"给消息 A 的调用方
// 当子 Agent 完成后，通过 PROGRESS 或 RESPONSE 消息通知父 Agent
// 父 Agent 在下一个消息处理中看到结果

// 父 Agent 的实现：
CompletableFuture<AgentMessage> handleRequest(AgentMessage request) {
    // 记录子 Agent 调用
    callHistory.recordCall("code-reviewer", request.getPayload(), "call-001");

    // 异步发送，不等待
    mesh.send(AgentMessage.builder()
        .to("code-reviewer")
        .correlationId("call-001")
        .payload(request.getPayload())
        .build());

    // 立即返回一个"进行中"的响应
    return CompletableFuture.completedFuture(
        AgentMessage.response(request)
            .payload("{\"status\": \"in_progress\", \"correlationId\": \"call-001\"}")
            .build()
    );
}

// 当子 Agent 的 RESPONSE 到达时，AgentMesh 会回调：
void onChildResponse(AgentMessage response) {
    // 找到对应的父 Agent，将结果注入其上下文
    AgentMessage recorded = callHistory.completeCall(
        response.getCorrelationId(), response);
    // 如果父 Agent 还有需要，可以触发后续处理
}
```

---

## 6. Agent 如何记得自己调用过谁？

### 6.1 AgentCallHistory

每个 Agent 实例维护一个 `AgentCallHistory`，存储在 Agent Context 中：

```java
/**
 * Agent 调用历史 —— 记录此 Agent 发起的子调用。
 *
 * 存储在 AgentContext 的 VariableStore 中，
 * 因此跨 Agent 状态保存和恢复。
 */
public class AgentCallHistory {

    // agentId (此 Agent 自身的 ID)
    private final String agentId;

    // 子调用列表（按时间正序）
    private final List<ChildCall> children = new ArrayList<>();

    // correlationId → ChildCall 的快速索引
    private final Map<String, ChildCall> pending = new HashMap<>();

    /**
     * 记录发起的子调用。
     */
    public void recordCall(String childAgentId, String task,
                           String correlationId, long ttlMs) {
        ChildCall call = new ChildCall(
            childAgentId, task, correlationId,
            System.currentTimeMillis(), ttlMs
        );
        children.add(call);
        pending.put(correlationId, call);
    }

    /**
     * 子 Agent 返回 RESPONSE 时调用。
     */
    public void completeCall(String correlationId, AgentMessage response) {
        ChildCall call = pending.remove(correlationId);
        if (call != null) {
            call.complete(response);
        }
    }

    /**
     * 获取所有待处理的子调用。
     */
    public List<ChildCall> getPendingCalls() {
        return new ArrayList<>(pending.values());
    }

    /**
     * 获取调用树（JSON 格式，用于 LLM 上下文）。
     */
    public String formatCallTree() {
        // 递归格式化为 LLM 可读的文本树
        // "→ code-reviewer: Review PR #42 [IN_PROGRESS] (30s elapsed)
        //    → github-tool: Fetch diff [COMPLETED]
        //    → linter-tool: Run lint [IN_PROGRESS]"
    }

    /**
     * 单个子调用记录。
     */
    public static class ChildCall {
        String childAgentId;
        String task;
        String correlationId;
        long startedAt;
        Long completedAt;
        String status;           // PENDING, RUNNING, COMPLETED, FAILED, TIMEOUT
        String resultPreview;    // 结果摘要（前 200 字符）

        ChildCall(String childAgentId, String task, String correlationId,
                  long startedAt, long ttlMs) {
            this.childAgentId = childAgentId;
            this.task = task;
            this.correlationId = correlationId;
            this.startedAt = startedAt;
            this.status = "RUNNING";
        }

        void complete(AgentMessage response) {
            this.completedAt = System.currentTimeMillis();
            this.status = response.getType() == MessageType.ERROR ? "FAILED" : "COMPLETED";
            this.resultPreview = truncate(response.getPayload(), 200);
        }
    }
}
```

### 6.2 调用树传递给 LLM

在 ReAct 循环中，Agent 的当前调用树会被格式化为 LLM 可见的上下文：

```
当前 Agent 的调用状态：
→ code-reviewer: Review PR #42 [COMPLETED] (结果: 发现 3 个问题)
→ github-tool: Fetch PR diff [COMPLETED] (结果: 42 个文件变更)
→ linter-tool: Run ESLint [IN_PROGRESS] (已运行 10s)
  (等待中，你可以发送消息询问进度)
```

---

## 7. 子 Agent 工作完如何让父 Agent 知道？

### 7.1 消息回执机制

```
子 Agent 完成工作后，通过 AgentMesh 发送 RESPONSE 消息给父 Agent：

子 Agent                    AgentMesh                   父 Agent
   │                          │                          │
   │  执行结束                  │                          │
   │                          │                          │
   │── send(RESPONSE) ────────→│                          │
   │   correlationId: "X"     │ 查找 correlationId 的     │
   │   to: "parentAgent"      │ 消费者                    │
   │                          │                          │
   │                          ├── 有 CompletableFuture? ──→ future.complete()
   │                          │                          │
   │                          ├── 有 AgentCallHistory? ───→ callHistory.complete()
   │                          │                          │
   │                          ├── 有 Callback? ──────────→ callback.accept()
   │                          │                          │
   │                          └── 都无 → 放入延迟队列      │
   │                                   等待消费者注册      │
```

### 7.2 三种通知路径

```java
// 路径 1: CompletableFuture（直接调用者）
mesh.send(request) → future.complete(response)

// 路径 2: AgentCallHistory（非直接调用者，但同 session）
mesh.send(request)  // 内部自动记录到 callHistory
// 子 Agent 返回时，AgentMesh 自动匹配到 callHistory

// 路径 3: 延迟结果队列（调用者比结果先到）
// 如果父 Agent 在子 Agent 返回前已经结束，结果存入延迟队列
// 父 Agent 下次启动时可以从队列中获取
DelayedResult result = mesh.pollDelayedResult(parentAgentId, correlationId);
```

---

## 8. 父 Agent 如何查看子 Agent 进度？

### 8.1 PROGRESS 消息

子 Agent 在执行过程中定期发送 PROGRESS 消息：

```java
// 子 Agent 内部，每完成一个步骤：
mesh.publish(AgentMessage.builder()
    .type(MessageType.PROGRESS)
    .correlationId("call-001")        // 关联到原始 REQUEST
    .to("parentAgent")                 // 直接发给父 Agent
    .metadata(Map.of(
        "percent", 60,
        "currentStage", "Linting files...",
        "filesProcessed", 12,
        "totalFiles", 20
    ))
    .build());
```

### 8.2 父 Agent 主动查询

```java
// 父 Agent 可以随时主动查询某个子 Agent 的状态：
AgentMessage statusQuery = AgentMessage.builder()
    .type(MessageType.REQUEST)
    .to("code-reviewer")               // 目标子 Agent
    .capability(null)
    .payload("{\"query\": \"progress\", \"correlationId\": \"call-001\"}")
    .build();

mesh.sendAndWait(statusQuery)
    .getPayload();  // {"percent": 60, "stage": "linting", "eta": "30s"}
```

### 8.3 订阅 PROGRESS 事件

```java
// 父 Agent 可以订阅子 Agent 的 PROGRESS 事件
mesh.subscribe(events -> events
    .filter(msg -> msg.getType() == MessageType.PROGRESS)
    .filter(msg -> "call-001".equals(msg.getCorrelationId()))
    .subscribe(progress -> {
        updateUI(progress.getMetadata());
    }));
```

---

## 9. 任务如何派发？

### 9.1 派发流程

```
                OrchestrationSpec
                      │
                      ▼
           ┌──────────────────────┐
           │  OrchestrationEngine  │
           └──────────────────────┘
                      │
           ┌──────────┼──────────┐
           ▼          ▼          ▼
      ┌────────┐┌────────┐┌────────┐
      │SINGLE  ││CHAIN   ││FAN_OUT │
      └───┬────┘└───┬────┘└───┬────┘
          │         │         │
          ▼         ▼         ▼
      ┌───────────────────────────┐
      │   AgentMesh.send(message) │
      └───────────────────────────┘
          │         │         │
          ▼         ▼         ▼
      ┌───────────────────────────┐
      │    AgentRouter.route()    │
      │   (按 ID / 能力 / 广播)   │
      └───────────────────────────┘
          │         │         │
          ▼         ▼         ▼
      ┌───────────────────────────┐
      │   AgentInstance.send()    │
      └───────────────────────────┘
```

### 9.2 OrchestrationSpec

```java
public class OrchestrationSpec {
    OrchestrationPattern pattern;          // SINGLE | CHAIN | FAN_OUT | DEBATE | DAG | SUPERVISOR
    String task;                            // 任务描述
    String payload;                         // 任务负载（JSON）
    List<String> agentIds;                  // 显式指定 Agent（可选）
    List<String> requiredCapabilities;      // 按能力选择（可选）
    Map<String, Object> config;             // 模式特有配置

    // ── 控制参数 ──
    long timeoutMs = 300_000;
    boolean waitForAll = true;             // FAN_OUT 时是否等所有完成
    int maxDebateRounds = 3;               // DEBATE 最大轮数
    String aggregationStrategy = "vote";    // 汇聚策略: vote | sum | llm | first
}

public enum OrchestrationPattern {
    SINGLE,     // 路由到最佳 Agent
    CHAIN,      // A → B → C 流水线
    FAN_OUT,    // 并行 → 汇聚
    DEBATE,     // 多轮辩论 → 综合
    DAG,        // 有向无环图依赖
    SUPERVISOR  // Supervisor 分解 → Workers 执行 → 汇总
}
```

### 9.3 OrchestrationEngine

```java
public class OrchestrationEngine {

    private final AgentMesh mesh;
    private final AgentRegistry registry;

    /**
     * 执行编排规格。
     */
    public OrchestrationResult execute(OrchestrationSpec spec) {
        return switch (spec.getPattern()) {
            case SINGLE -> executeSingle(spec);
            case CHAIN -> executeChain(spec);
            case FAN_OUT -> executeFanOut(spec);
            case DEBATE -> executeDebate(spec);
            case DAG -> executeDag(spec);
            case SUPERVISOR -> executeSupervisor(spec);
        };
    }

    // ── SINGLE: 路由到 1 个 Agent ──
    private OrchestrationResult executeSingle(OrchestrationSpec spec) {
        AgentRef target = resolveAgent(spec);
        AgentMessage response = mesh.sendAndWait(AgentMessage.builder()
            .to(target.getAgentId())
            .type(MessageType.REQUEST)
            .payload(spec.getPayload())
            .ttlMs(spec.getTimeoutMs())
            .build());
        return OrchestrationResult.from(response);
    }

    // ── CHAIN: A → B → C ──
    private OrchestrationResult executeChain(OrchestrationSpec spec) {
        String currentPayload = spec.getPayload();
        for (AgentRef agent : resolveAgents(spec)) {
            AgentMessage response = mesh.sendAndWait(AgentMessage.builder()
                .to(agent.getAgentId())
                .type(MessageType.REQUEST)
                .payload(currentPayload)
                .ttlMs(spec.getTimeoutMs())
                .build());
            currentPayload = response.getPayload();  // 结果传给下一个
        }
        return OrchestrationResult.success(currentPayload);
    }

    // ── FAN_OUT: 并行 → 根据策略汇聚 ──
    private OrchestrationResult executeFanOut(OrchestrationSpec spec) {
        List<AgentRef> agents = resolveAgents(spec);
        List<CompletableFuture<AgentMessage>> futures = agents.stream()
            .map(agent -> mesh.send(AgentMessage.builder()
                .to(agent.getAgentId())
                .type(MessageType.REQUEST)
                .payload(spec.getPayload())
                .build()))
            .toList();

        // 等所有完成（或超时）
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .get(spec.getTimeoutMs(), TimeUnit.MILLISECONDS);

        List<AgentMessage> results = futures.stream()
            .map(CompletableFuture::join)
            .toList();

        // 根据汇聚策略合并结果
        return aggregate(results, spec.getAggregationStrategy());
    }

    // ── DEBATE: 多轮辩论 ──
    private OrchestrationResult executeDebate(OrchestrationSpec spec) {
        List<String> opinions = new ArrayList<>();
        // 每轮: 每个 Agent 看到其他 Agent 的观点，补充/反驳
        for (int round = 0; round < spec.getMaxDebateRounds(); round++) {
            // ...
        }
        // 最后综合所有观点
        return synthesize(opinions);
    }
}
```

---

## 10. 完整调用链路示例

```
用户: "帮我审查这个 PR"
  │
  ▼
ChatController → AgentMesh.send({
    to: "orchestrator",
    payload: "帮我审查这个 PR: https://github.com/...",
    correlationId: "trace-001"
})
  │
  ▼
Orchestrator: 分析任务 → 应采用 DAG 模式
  │ 1. 分解任务
  │    ├── Fetch PR diff (github-tool)
  │    ├── Run ESLint (linter-tool)
  │    ├── Run tests (ci-tool)
  │    └── 综合分析 (code-reviewer agent)
  │
  │ 2. 创建 AgentCallHistory:
  │    ┌─────────────────────────────────────┐
  │    │ call-001 → github-tool [RUNNING]     │
  │    │ call-002 → linter-tool [PENDING]     │
  │    │ call-003 → ci-tool [PENDING]         │
  │    │ call-004 → code-reviewer [PENDING]   │
  │    └─────────────────────────────────────┘
  │
  │ 3. 异步并行派发独立任务
  │    mesh.send({to: "github-tool", payload: "...", correlationId: "call-001"})
  │    // 不等待，返回 CompletableFuture
  │
  │ 4. 同步等第一个结果
  │    github-tool 完成 → RESPONSE payload: diff
  │
  │ 5. 派发依赖任务（需要 diff）
  │    mesh.send({to: "linter-tool", payload: diff, correlationId: "call-002"})
  │    mesh.send({to: "ci-tool", payload: diff, correlationId: "call-003"})
  │
  │ 6. 等待所有工具完成
  │    CompletableFuture.allOf(futures...).join()
  │
  │ 7. 派发综合分析给 code-reviewer
  │    mesh.send({to: "code-reviewer", payload: {
  │        diff: "...", lintResults: "...", testResults: "..."
  │    }, correlationId: "call-004"})
  │
  │ 8. code-reviewer 在 ReAct 循环中可能需要更多信息
  │    → mesh.send({to: "github-tool", payload: "get file content: src/main.js"})
  │    → PROGRESS: {"stage": "analyzing file src/main.js"}
  │
  │ 9. code-reviewer 完成 → RESPONSE: "发现 5 个问题..."
  │
  ▼
结果汇聚 → 返回最终审查报告给用户
```

---

## 11. 向后兼容

```
旧 @Agent 接口 → AgentProxyFactory → ProxyAgentInstance
                                        ├── send(message) → 拆包 → invoke()
                                        └── invoke() → Stage管线 → ReActEngine

ProxyAgentInstance 实现了 AgentInstance 接口，
在 send() 中将 AgentMessage 转换为方法参数，
调用现有的 AgentInvocationHandler.invoke()，
将返回值包装为 AgentMessage.RESPONSE。

这样所有现有 @Agent 接口代码无需改动即可接入 Agent Mesh。
```

---

## 12. 实现计划

### Phase 1 — 消息协议 + AgentRef + Agent实例核心（当前可做）

```
AgentMessage      — 消息协议（含类型、路由、追踪）
MessageType       — 消息类型枚举
AgentRef          — 轻量级 Agent 引用
AgentInstance     — Agent 运行时接口
AgentSpec         — Agent 创建蓝图
AgentLifecycle    — 生命周期状态机 + 事件
AgentCallHistory  — 调用历史记录
AgentMesh         — 核心接口（register/send/sendStream/publish）
DefaultAgentMesh  — 默认实现（InProcessTransport）
ToolAgentInstance — 工具 Agent 包装
LLMAgentInstance  — LLM Agent 包装
```

### Phase 2 — 编排引擎

```
OrchestrationSpec — 编排规格
OrchestrationEngine — 编排引擎（SINGLE/CHAIN/FAN_OUT/DEBATE）
OrchestrationResult — 编排结果
```

### Phase 3 — 高级能力

```
DAG 模式     （复用现有 SupervisorOrchestrator）
远程传输     （gRPC）
Supervision  （错误恢复树）
可观测性     （OpenTelemetry 追踪）
```

---

## 13. 总结：设计决策一览

| 问题 | 决策 | 原因 |
|------|------|------|
| Agent 是什么 | Virtual Actor + AgentRef | 运行时动态创建，位置透明 |
| 通信方式 | 不可变消息 (AgentMessage) | 异步、可追踪、跨进程 |
| 工具 vs Agent | 同一接口 | 调度引擎统一处理 |
| 同步异步 | 3 种模式: 同步/异步/流式 | 适配不同场景 |
| 调用记忆 | AgentCallHistory | 持久化、可查询 |
| 子完成通知 | RESPONSE 消息 + CompletableFuture | 解耦、可组合 |
| 进度查询 | PROGRESS 消息 | 非侵入式 |
| 任务派发 | OrchestrationSpec + OrchestrationEngine | 声明式、可扩展 |
| 追踪 | traceId + correlationId | 跨 Agent 调用链可见 |
| 错误处理 | ERROR 消息 + Supervision Tree | 不丢失、可恢复 |
