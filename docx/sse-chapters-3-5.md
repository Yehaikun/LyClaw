# 第三章：LyClaw SSE架构总览

在完成了SSE协议基础和Reactor响应式编程（第一章和第二章）的学习之后，本章我们将把视角拉高，纵览LyClaw项目中SSE流式对话的完整架构。我们会从一条HTTP请求的完整旅程开始，逐层剖析每一环的设计意图。

---

## 3.1 请求链路全景图

当一个用户在浏览器中发送一条聊天消息，到最终看到AI逐字回复，数据包在LyClaw架构中穿越了以下路径：

```
Browser (fetch POST /api/chat/stream)
  │
  │  Accept: text/event-stream
  │  Content-Type: application/json
  │
  ▼
Vite Dev Proxy (lyclaw-ui/vite.config.ts)
  │
  │  /api → http://localhost:8080
  │  重写 Cache-Control 为 no-cache（防止浏览器缓存SSE流）
  │
  ▼
Spring Cloud Gateway (lyclaw-gateway, port 8080)
  │
  │  Route: /api/chat, /api/chat/stream → lb://lyclaw-orchestration-service
  │  response-timeout: 300s（5分钟超时，适配长对话场景）
  │  CORS: 允许所有来源
  │
  ▼
OrchestrationController (lyclaw-orchestration-service, port 8081)
  │
  │  @PostMapping(value = "/api/chat/stream", produces = TEXT_EVENT_STREAM_VALUE)
  │  构建 ChatContext → 调用 orchestrator.execute(context)
  │
  ▼
OrchestratorImpl.execute(ChatContext context)
  │
  │  ┌─── pipelineFlux ──────────────────────────┐
  │  │   Stage 1: CONTEXT_BUILD (记忆检索)       │
  │  │   Stage 2: INTERCEPT     (安全检查+内容过滤)│
  │  │   Stage 3: PLAN          (任务规划)       │
  │  │   Stage 4: EXECUTE       (工具调用)       │
  │  │   Stage 5: REFLECT       (反思评估)       │
  │  └───────────────────────────────────────────┘
  │           │ .concatWith()
  │  ┌─── respondFlux ───────────────────────────┐
  │  │   modelProvider.getConfiguredAdapter()    │
  │  │   → adapter.chatStream(llmRequest)        │
  │  │   → extractSsePlainText() 逐行解析        │
  │  │   → sseEvent("message", text)             │
  │  │   → buildTailFlux() 收尾事件              │
  │  └───────────────────────────────────────────┘
  │
  ▼
ModelAdapter.chatStream(request)
  │
  │   AbstractModelAdapter.chatStream() [模板方法]
  │   → DeepSeekOpenAIAdapter.buildStreamRequest() [构建OpenAI格式请求体]
  │   → DeepSeekOpenAIAdapter.sendStreamRequest() [发送HTTP请求]
  │
  ▼
OkHttpModelApiClient.postStream(url, headers, body)
  │
  │   OkHttp阻塞I/O → BufferedReader.readLine()
  │   包装在 Flux.generate() 中，运行于 boundedElastic 调度器
  │
  ▼
DeepSeek / OpenAI API (https://api.deepseek.com/chat/completions)
  │
  │   返回 SSE 流：data: {"choices":[{"delta":{"content":"你"}}]}\n\n
  │           data: {"choices":[{"delta":{"content":"好"}}]}\n\n
  │           data: [DONE]\n\n
```

### 各环节详细说明

**第一跳：Vite Dev Proxy**

在开发环境中，前端运行在Vite开发服务器上。Vite配置了一个代理规则，将所有 `/api` 开头的请求转发到后端的Spring Cloud Gateway（`http://localhost:8080`）。关键代码在 `lyclaw-ui/vite.config.ts`：

```typescript
proxy: {
  '/api': {
    target: 'http://localhost:8080',
    changeOrigin: true,
    configure: (proxy) => {
      proxy.on('proxyRes', (proxyRes) => {
        if (
          proxyRes.headers['content-type'] &&
          proxyRes.headers['content-type'].includes('text/event-stream')
        ) {
          proxyRes.headers['cache-control'] = 'no-cache'
        }
      })
    },
  },
},
```

这里有一个精妙的设计：`proxyRes` 事件监听器检测到响应头的 `content-type` 包含 `text/event-stream` 时，强制将 `cache-control` 设置为 `no-cache`。这是因为某些浏览器或中间代理会对SSE流进行缓存，导致客户端接收到的数据不完整或延迟。强制 `no-cache` 确保了每次SSE连接都是全新的、实时的。

**第二跳：Spring Cloud Gateway**

Gateway是整个系统的统一入口（port 8080）。`GatewayConfig.java` 中定义了路由规则：

```java
.route("chat-api", r -> r
    .path("/api/chat", "/api/chat/stream")
    .uri("lb://lyclaw-orchestration-service"))
```

`lb://` 前缀表示使用Spring Cloud LoadBalancer进行客户端负载均衡。Gateway通过Nacos服务发现找到 `lyclaw-orchestration-service` 的所有实例，将请求路由过去。

`application.yml` 中配置了关键参数：

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          httpclient:
            response-timeout: 300s
```

响应超时设置为300秒（5分钟）。对于SSE长连接来说，这个值至关重要——如果超时太短，长时间的LLM流式响应（例如生成一篇长文章）会被Gateway强制断开。5分钟是一个合理的上限，既避免了无限等待，又给了LLM足够的生成时间。

**第三跳：OrchestrationController**

Controller的职责是请求适配——将HTTP请求中的数据转换为内部领域对象，然后委托给Orchestrator执行。这里的关键注解是：

```java
@PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
```

`produces = MediaType.TEXT_EVENT_STREAM_VALUE` 告诉Spring WebFlux：这个端点的响应Content-Type是 `text/event-stream`。Spring会据此设置响应头，使得浏览器正确识别这是一个SSE流而非普通HTTP响应。

**第四跳：OrchestratorImpl.execute()**

这是整个SSE流的核心。它返回一个 `Flux<ServerSentEvent<String>>`，Spring WebFlux会将这个Flux中的每个元素自动序列化为SSE格式（`event:xxx\ndata:yyy\n\n`）并写入HTTP响应体。

这里采用了**两段式Flux拼接架构**：`pipelineFlux`（管道阶段，第1-5阶段）和 `respondFlux`（LLM响应阶段，第6阶段），通过 `.concatWith()` 连接。我们将在3.3节详细分析这个架构。

**第五跳：ModelAdapter**

适配器层负责将内部统一的请求格式转换为具体LLM提供商的API格式。在 `AbstractModelAdapter` 中定义了模板方法骨架，具体的适配器（如 `DeepSeekOpenAIAdapter`）填充实现细节。

**第六跳：OkHttpModelApiClient**

这是最底层的HTTP客户端。它使用OkHttp库发起阻塞式HTTP请求，然后通过 `Flux.generate()` 将阻塞的 `BufferedReader.readLine()` 包装成响应式流。关键在于使用了 `Schedulers.boundedElastic()`——这是一个专门为阻塞I/O设计的调度器，它会根据需要动态创建线程池，避免了阻塞操作占用反应式事件循环线程。

---

## 3.2 SSE事件类型完整分类

在LyClaw的SSE流中，一个完整的对话会话会依次产生多种类型的事件。理解每种事件的含义、触发时机和携带数据，是读懂整个系统的基础。

### 3.2.1 管道进度事件（Pipeline Progress Events）

这些事件在LLM实际开始生成回复**之前**依次发出，对应于Orchestrator的五阶段管道。

| 事件类型 | 触发时机 | 携带数据 | 前端应做操作 |
|---------|---------|---------|------------|
| `context_build_start` | 管道第1阶段开始 | 文本描述："Loading session and retrieving memories" | 显示"正在加载上下文..."状态 |
| `context_build_complete` | 记忆检索完成 | 文本描述，包含检索到的记忆条目数 | 更新状态为"上下文已加载" |
| `intercept_start` | 管道第2阶段开始 | 文本描述："Running security checks and content filter" | 显示"正在进行安全检查..." |
| `intercept_complete` | 安全检查和内容过滤通过 | 文本描述："Security check and content filter passed" | 更新状态为"检查已通过" |
| `intercept_blocked` | 安全检查失败或内容过滤拦截 | 拦截原因文本 | 显示拦截提示，流程终止 |
| `plan_start` | 管道第3阶段开始 | 文本描述："Planning task decomposition" | 显示"正在规划任务..." |
| `plan_complete` | 任务规划完成 | 文本描述，包含规划的任务数量 | 更新状态，显示任务数量 |
| `plan_node` | 每个任务节点的详细信息 | JSON：`{"index":1,"nodeId":"xxx","type":"EXECUTE","description":"..."}` | 渲染任务节点卡片 |
| `action_start` | 开始执行某个任务节点 | JSON：`{"index":1,"total":3,"nodeId":"xxx","description":"..."}` | 显示"正在执行第1/3个任务..." |
| `action_result` | 单个任务执行完成 | JSON：`{"index":1,"status":"success","output":"...","durationMs":123}` | 显示任务执行结果，更新进度 |
| `action_complete` | 所有任务执行完成 | JSON：`{"total":3,"success":2,"failed":1}` | 显示任务执行汇总 |
| `reflect_start` | 管道第5阶段开始 | 文本描述："Reflecting on execution results" | 显示"正在反思评估..." |
| `reflect_complete` | 反思评估完成 | JSON：`{"score":0.85,"reflectionId":"xxx"}` | 显示评估分数 |

### 3.2.2 LLM响应事件（LLM Response Events）

这些事件在LLM实际开始生成文本回复时发出。

| 事件类型 | 触发时机 | 携带数据 | 前端应做操作 |
|---------|---------|---------|------------|
| `respond_start` | LLM开始生成回复 | 文本描述："Generating AI response" | 显示"AI正在生成回复..." |
| `message` | LLM每生成一段文本增量 | 纯文本token（累积的增量文本） | 逐字追加到对话区域 |
| `respond_complete` | LLM回复生成完毕，记忆已持久化 | 文本描述 | 更新回复状态为完成 |

### 3.2.3 终端事件（Terminal Events）

这些事件标志着一个SSE流的结束。

| 事件类型 | 触发时机 | 携带数据 | 前端应做操作 |
|---------|---------|---------|------------|
| `metrics` | 整个流程结束前 | JSON：`{"totalDurationMs":1234,"tasksProcessed":3,"successRate":"0.67","reflectScore":0.85}` | 可选展示性能指标 |
| `done` | 流程最终完成 | JSON：`{"status":"completed","durationMs":1234}` 或 `{"status":"blocked"}` 或 `{"status":"completed","fallback":true}` | 结束加载状态，标记对话完成 |
| `error` | 管道执行过程中发生异常 | JSON：`{"message":"错误描述"}` | 显示错误提示 |

### 3.2.4 事件流向时序图

```
时间轴 →
─────────────────────────────────────────────────────────────────────►

[context_build_start] → [context_build_complete]
[intercept_start]     → [intercept_complete] (或被 [intercept_blocked] 截断)
[plan_start]          → [plan_node] × N → [plan_complete]
[action_start] → [action_result] → [action_start] → [action_result] → [action_complete]
[reflect_start]       → [reflect_complete]

───────────────────────────管道阶段完成────────────────────────────────

[respond_start] → [message] × N → [respond_complete]
[metrics]
[done]

───────────────────────────SSE流结束───────────────────────────────────
```

重点理解：[done] 事件之前的所有事件都是SSE流的一部分，[done] 之后SSE流关闭。前端需要监听 `done` 事件（或 `intercept_blocked` 事件）来判断何时关闭EventSource连接。

---

## 3.3 两段式Flux拼接架构

这是LyClaw SSE架构中最核心的设计决策。让我们先看代码，再分析背后的设计思想。

### 3.3.1 代码结构

在 `OrchestratorImpl.execute()` 方法中，最终返回的Flux由两部分拼接而成：

```java
// pipelineFlux: 第1-5阶段（管道阶段）
Flux<ServerSentEvent<String>> pipelineFlux = Flux.create(sink -> {
    // ... 5个同步阶段依次执行
});

// respondFlux: 第6阶段（LLM响应）
Flux<ServerSentEvent<String>> respondFlux = Flux.defer(() -> {
    // ... LLM流式调用
});

// 拼接并控制线程
return pipelineFlux.concatWith(respondFlux);
```

最外层包裹了 `Flux.defer()` 和 `.subscribeOn(Schedulers.boundedElastic())`：

```java
return Flux.defer(() -> {
    // ... pipelineFlux + respondFlux 的定义和拼接
    return pipelineFlux.concatWith(respondFlux);
}).subscribeOn(Schedulers.boundedElastic());
```

### 3.3.2 为什么需要两个Flux而不是一个？

**原因一：阶段性质不同**

管道阶段（Stage 1-5）是**同步的、有序的**业务流程。每个阶段依赖前一个阶段的完成，必须按照严格顺序执行。使用 `Flux.create()` 可以让我们用命令式的编程风格（调用 `sink.next()` 发射事件），代码可读性高，调试友好。

LLM响应阶段（Stage 6）是**异步的、流式的**数据消费。DeepSeek API返回的是一个SSE流，我们需要将这个流逐行解析、转换、封装。这天然适合Reactor的响应式操作符链（`.map()`, `.filter()`, `.handle()`）。

**原因二：清晰的事件顺序保证**

使用 `.concatWith()` 连接两个Flux，保证了第一个Flux的所有事件**全部发出完毕后**，才开始发射第二个Flux的事件。这意味着：
- 前端收到的所有 `context_build_*`、`intercept_*`、`plan_*`、`action_*`、`reflect_*` 事件一定在 `respond_start` 之前
- 前端可以自然地用管道事件驱动进度条，用LLM事件驱动文本渲染
- 不会有管道事件和LLM token交织出现的情况

**原因三：失败隔离**

如果管道阶段失败（例如安全检查拦截），`pipelineOk` 被设置为 `false`。`respondFlux` 开头的检查：

```java
Flux<ServerSentEvent<String>> respondFlux = Flux.defer(() -> {
    if (!pipelineOk.get()) {
        return Flux.empty();
    }
    // ... LLM调用
});
```

这样LLM调用被跳过，但整个SSE流仍然通过 `pipelineFlux` 正常结束（发出了 `intercept_blocked` + `done` 事件），浏览器端不会看到异常中断。

### 3.3.3 为什么respondFlux使用Flux.defer()？

`Flux.defer()` 是一个**延迟执行**的操作符。它接受一个 `Supplier<Publisher<T>>`，只有在有订阅者订阅时才会调用这个Supplier来创建实际的Publisher。

这里的关键原因是：`Flux.defer()` 内部的代码在**订阅时才执行**，而不是在**组装时**执行。如果不用 `defer()`，那么：

```java
// 错误做法：这段代码在 execute() 方法返回时就执行了
int sc = successCount.get(); // 此时 successCount 还是 0！
ModelAdapter adapter = modelProvider.getConfiguredAdapter();
// ...

// 正确做法：defer() 保证这段代码在 subscribeOn 确定的线程中、在 pipelineFlux 完成后才执行
Flux<ServerSentEvent<String>> respondFlux = Flux.defer(() -> {
    int sc = successCount.get(); // 此时管道已完成，successCount 是正确的值
    // ...
});
```

还有一个重要作用：`.subscribeOn(Schedulers.boundedElastic())` 放在最外层，控制的是整个链的订阅线程。`defer()` 保证了内部代码也在订阅时（而非组装时）执行，从而共享同一个调度器上下文。

### 3.3.4 完整时序图

```
Thread: boundedElastic-1
│
├─ Flux.defer() 被订阅
│
├─ pipelineFlux (Flux.create) 开始
│  ├─ sink.next("context_build_start")
│  ├─ memoryFeignClient.retrieve()         ← 阻塞调用
│  ├─ sink.next("context_build_complete")
│  ├─ sink.next("intercept_start")
│  ├─ securityManager.approve()            ← 阻塞调用
│  ├─ contentFilter.filter()               ← 阻塞调用
│  ├─ sink.next("intercept_complete")
│  ├─ sink.next("plan_start")
│  ├─ planFeignClient.plan()               ← 阻塞调用(Feign)
│  ├─ sink.next("plan_complete")
│  ├─ for each node:
│  │   ├─ sink.next("action_start")
│  │   ├─ actionFeignClient.executeTool()  ← 阻塞调用(Feign)
│  │   └─ sink.next("action_result")
│  ├─ sink.next("action_complete")
│  ├─ sink.next("reflect_start")
│  ├─ reflectFeignClient.reflect()         ← 阻塞调用(Feign)
│  ├─ sink.next("reflect_complete")
│  ├─ pipelineOk.set(true)
│  └─ sink.complete()
│
├─ respondFlux (Flux.defer) 开始
│  ├─ if (!pipelineOk.get()) ← false，继续
│  ├─ sink.next("respond_start")
│  ├─ adapter.chatStream(llmRequest)
│  │  └─ OkHttp → BufferdReader.readLine()
│  │     ├─ sink.next(sseEvent("message", "你"))
│  │     ├─ sink.next(sseEvent("message", "好"))
│  │     ├─ sink.next(sseEvent("message", "！"))
│  │     └─ ...
│  ├─ buildTailFlux()
│  │  ├─ memoryFeignClient.ingest()        ← 阻塞调用
│  │  ├─ sink.next("respond_complete")
│  │  ├─ sink.next("metrics")
│  │  └─ sink.next("done")
│  └─ 完成
│
└─ SSE流关闭
```

注意：整个流程都在 `boundedElastic` 调度器的一个线程上执行。这意味着每个HTTP请求占用一个独立线程，但`boundedElastic` 有线程池上限（默认是CPU核心数×10），超过上限的请求会排队等待。

---

## 3.4 非流式降级方案 /chat

除了SSE流式端点，OrchestrationController还提供了一个非流式的降级端点：

```java
@PostMapping("/chat")
public Mono<ChatResult> chat(@RequestBody ChatRequest request) {
    Session session = resolveSession(request.getSessionId());
    lyjew.com.lyclaw.model.ChatRequest modelRequest = buildModelRequest(request);
    ChatContext context = buildChatContext(modelRequest, session);
    Flux<ServerSentEvent<String>> flux = orchestrator.execute(context);
    return flux.collectList()
            .map(results -> {
                String content = results != null
                        ? results.stream()
                                .filter(e -> "message".equals(e.event()))
                                .map(e -> e.data() != null ? e.data() : "")
                                .reduce("", String::concat)
                        : "";
                return new ChatResult(content, "stop", null, null, 0L);
            })
            .subscribeOn(Schedulers.boundedElastic());
}
```

### 3.4.1 工作原理

这个端点内部调用的**仍然是** `orchestrator.execute(context)`——与流式端点完全相同的业务逻辑。区别在于对返回值的处理：

1. **`flux.collectList()`**：将整个Flux中的所有元素收集到一个 `List<ServerSentEvent<String>>` 中。这是一个聚合操作——它会等待所有事件（包括管道事件、LLM token事件、done事件）全部发射完毕后，一次性返回。

2. **`.map(results -> ...)`**：对收集到的所有SSE事件进行后处理：
   - `.filter(e -> "message".equals(e.event()))`：只保留事件类型为 `"message"` 的事件
   - `.map(e -> e.data() != null ? e.data() : "")`：提取每个message事件的数据载荷
   - `.reduce("", String::concat)`：将所有文本片段拼接成一个完整的字符串

3. **`new ChatResult(content, "stop", null, null, 0L)`**：将拼接后的完整文本包装为 `ChatResult` 对象返回。`ChatResult` 是一个简单的POJO，包含 `content`（完整回复文本）、`finishReason`、`tokenUsage`、`toolResults` 和 `durationMs`。

4. **`.subscribeOn(Schedulers.boundedElastic())`**：将整个阻塞工作放在弹性线程池上。

### 3.4.2 存在的意义

这个降级端点存在的原因：

1. **兼容不支持SSE的客户端**：某些老旧的HTTP客户端库或CLI工具无法处理SSE流式响应。`/chat` 端点返回普通的 `application/json` 响应，可以被任何HTTP客户端消费。

2. **简化集成测试**：在编写集成测试时，断言一个 `Mono<ChatResult>` 比断言一个 `Flux<ServerSentEvent<String>>` 简单得多——不需要处理时序问题，不需要逐个验证事件。

3. **预防性降级**：如果前端发现EventSource连接频繁失败，可以自动降级到 `/chat` 端点，至少保证基本功能可用。

---

# 第四章：管道阶段 — Flux.create()事件发射器详解

本章我们将深入 `OrchestratorImpl.execute()` 方法的内部，逐行解读管道阶段（Pipeline Stages 1-5）的实现。通过阅读 `/home/lyjew/Documents/Unicom/LyClaw/lyclaw-orchestration/src/main/java/lyjew/com/lyclaw/orchestration/impl/OrchestratorImpl.java` 的源代码，理解每一行代码的设计意图。

---

## 4.1 execute()方法入口剖析

```java
@Override
public Flux<ServerSentEvent<String>> execute(ChatContext context) {
    return Flux.defer(() -> {
        long orchestrationStart = System.currentTimeMillis();
        ChatRequest request = context.getRequest();
        String sessionId = request.getSessionId();
        String userMessage = request.getLastUserMessage();
```

### 逐行解读

**`public Flux<ServerSentEvent<String>> execute(ChatContext context)`**

这是 `Orchestrator` 接口的核心方法。返回值类型 `Flux<ServerSentEvent<String>>` 中的泛型参数包括两层：
- 外层 `Flux<T>`：表示这是一个响应式流，会随时间推移发射零到多个元素
- 内层 `ServerSentEvent<String>`：每个元素是一个SSE事件，携带 `event` 类型标签和 `data` 数据载荷（均为String）

`ChatContext` 是一个聚合对象，包含了当前会话的全部上下文信息：请求对象、会话对象、拦截器链、模型提供器等。

**`return Flux.defer(() -> {`**

`Flux.defer()` 是Reactor中一个非常重要但容易被忽视的操作符。它的签名为：

```java
public static <T> Flux<T> defer(Supplier<? extends Publisher<T>> supplier)
```

在Java的响应式编程中，"组装时"（assembly time）和"订阅时"（subscription time）是两个不同的阶段。`Flux.defer()` 将Publisher的创建推迟到订阅时：

- **如果没有defer()**：`execute()` 方法内的代码（包括pipelineFlux和respondFlux的组装、`orchestrationStart` 的赋值等）在 `execute()` 被调用时就立即执行。但此时还没有HTTP订阅者（Spring WebFlux在处理请求时才会订阅），可能导致时机问题。
- **有了defer()**：整个lambda内的代码在每个HTTP请求到来、Spring WebFlux订阅这个Flux时才执行。这意味着每个HTTP请求都会得到自己独立的执行上下文。

这里实际上有两层 `defer()`：
1. 外层（第83行）：将 `execute()` 的整个逻辑推迟到订阅时
2. 内层（第294行）：将 `respondFlux` 的创建推迟到 `pipelineFlux` 完成之后

**`long orchestrationStart = System.currentTimeMillis();`**

记录编排流程的开始时间戳（毫秒级）。这个值会在后续的 `buildTailFlux()` 方法中用于计算总耗时。`System.currentTimeMillis()` 返回的是自1970年1月1日以来的毫秒数，在同一个JVM进程中具有单调性（虽然不保证严格单调，但在正常运行时不会出现时钟倒退）。

**`ChatRequest request = context.getRequest();`**

从 `ChatContext` 中取出 `ChatRequest` 对象。`ChatRequest` 包含了用户的消息列表（`List<Message>`）、会话ID、是否流式等核心请求信息。

**`String sessionId = request.getSessionId();`**

提取会话ID。这是一个全局唯一的标识符，用于：
- 关联同一会话中的多轮对话
- 向记忆服务标识会话范围
- 日志追踪（日志中打印sessionId便于问题排查）

**`String userMessage = request.getLastUserMessage();`**

提取用户最后一条消息的文本内容。这是用户的当前输入，会被用于记忆检索的查询关键词、任务规划的意图描述、内容过滤的检测目标。

### 共享可变状态初始化

```java
final List<String> toolResults = new ArrayList<>();
final AtomicInteger successCount = new AtomicInteger(0);
final AtomicInteger failCount = new AtomicInteger(0);
final List<TaskNode> nodes = new ArrayList<>();
final AtomicReference<ReflectionReport> reportRef = new AtomicReference<>();
final AtomicReference<Double> reflectScoreRef = new AtomicReference<>(0.0);
final AtomicBoolean pipelineOk = new AtomicBoolean(false);
final AtomicLong respondStartMs = new AtomicLong();
```

这八个变量是管道阶段（pipelineFlux）和响应阶段（respondFlux）之间的**共享状态**。管道阶段写入，响应阶段读取。

**`final List<String> toolResults`**

一个普通的 `ArrayList`。收集所有工具执行的输出结果。由于管道阶段是单线程顺序执行的（所有操作都在同一个boundedElastic线程上），这里使用普通的非线程安全List是安全的——不存在并发写入的问题。这个List会被传递给 `buildFinalResponse()` 和 `buildLlmRequest()`。

**`final AtomicInteger successCount` / `failCount`**

使用 `AtomicInteger` 而非普通的 `int`。虽然当前实现在单线程下 `int` 也能工作，但选择 `AtomicInteger` 是一个防御性设计——如果将来管道阶段被改为并行执行不同节点的工具调用（例如使用 `Flux.parallel()`），`AtomicInteger` 的 `incrementAndGet()` 能保证线程安全的计数。

更深层的原因是Java的lambda表达式限制：在lambda中只能访问**effectively final**（事实上不可变）的局部变量。`int successCount = 0` 无法在lambda中修改（`successCount++` 会编译错误）。而 `AtomicInteger` 是一个引用类型，它的引用是final的（`final AtomicInteger successCount = ...`），但其内部的值可以通过 `.incrementAndGet()` 修改。

**`final List<TaskNode> nodes`**

存储规划阶段生成的 `TaskNode` 列表。每个 `TaskNode` 包含 `nodeId`、`type`（工具名称）、`description`、`requiredTools`、`dependencies`、`timeoutMs` 等字段。

**`final AtomicReference<ReflectionReport> reportRef`**

`AtomicReference` 是Java并发包中的原子引用类型，提供对引用类型值的原子读写操作。这里存储反思阶段返回的 `ReflectionReport` 对象，包含 `overallScore`（总体评分）、`quality`（质量评估包含accuracy/completeness/safety三个维度）等信息。在响应阶段读取这个report来构建回复。

**`final AtomicReference<Double> reflectScoreRef`**

单独存储反思评分的数值，用于在 `buildTailFlux()` 的metrics事件中输出。

**`final AtomicBoolean pipelineOk`**

管道状态标志。初始值为 `false`。只有当所有5个管道阶段成功完成后，才在Stage 5末尾设置为 `true`。`respondFlux` 在开始时检查这个标志——如果为 `false`，说明管道在中途因安全检查拦截或异常而终止，直接返回 `Flux.empty()` 跳过LLM调用。

**`final AtomicLong respondStartMs`**

记录响应阶段开始的时间戳（即管道完成、LLM调用开始的时间点）。用于在 `buildTailFlux()` 中统计RESPOND阶段的耗时。

---

## 4.2 Flux.create()的sink机制

```java
Flux<ServerSentEvent<String>> pipelineFlux = Flux.<ServerSentEvent<String>>create(sink -> {
    try {
        // ... 5个阶段依次执行，通过 sink.next() 发射事件
        sink.complete();
    } catch (Exception e) {
        sink.next(sseEvent("error", "..."));
        sink.next(sseEvent("done", "{\"status\":\"error\"}"));
        sink.complete();
    }
});
```

### Flux.create() 的语义

`Flux.create()` 是Reactor中创建Flux的三种主要工厂方法之一（另外两种是 `Flux.just()` 和 `Flux.generate()`）。它们的区别：

| 方法 | 发射时机 | 适用场景 |
|------|---------|---------|
| `Flux.just(T...)` | 立即发射所有已知元素 | 少量、确定的元素 |
| `Flux.generate(callable, generator)` | 每次下游请求时同步生成一个元素 | 按需生成，每次一个 |
| `Flux.create(sink -> {...})` | 由代码主动调用sink方法发射，支持多线程 | 事件驱动、桥接回调/监听器 |

在LyClaw中，管道阶段包含了多次 `sink.next()` 调用，穿插在Feign远程调用之间。每次 `sink.next()` 立即向下游（即HTTP响应流）推送一个SSE事件。这种"推送式"编程模型非常适合 `Flux.create()`。

### FluxSink的API

`Flux.create()` 提供给lambda的参数是一个 `FluxSink<T>` 实例，称为"sink"（水槽/发射器）。它有三个核心方法：

**`sink.next(T element)`**

向Flux的下游订阅者发射一个元素。在LyClaw中，每个 `sink.next(sseEvent(...))` 都会导致Spring WebFlux将一个完整的SSE消息写入HTTP响应体：

```
event:context_build_start
data:Loading session and retrieving memories

```

调用 `sink.next()` 时，如果下游的订阅者（Spring WebFlux的响应写入器）已经取消订阅（例如客户端断开连接），这个调用会被忽略但不抛异常——这是Reactor的反压（backpressure）处理机制的一部分。

**`sink.complete()`**

通知下游：这个Flux已经完成，不会再有更多元素。Spring WebFlux收到这个信号后，会关闭SSE的HTTP响应体（发送最后一个空行，然后关闭连接）。

**`sink.error(Throwable)`**

通知下游：这个Flux因错误而终止。在LyClaw的管道阶段中，**我们不使用 `sink.error()`**，原因是HTTP响应已经以200 OK状态码开始发送了——此时无法再修改HTTP状态码。改用 `sink.next(errorEvent)` + `sink.next(doneEvent)` + `sink.complete()` 的方式，将错误信息作为正常SSE事件的一部分发送给客户端，让前端在应用层面处理错误。

### try/catch模式的设计考量

```java
try {
    // 5个阶段
    sink.complete();           // 正常完成
} catch (Exception e) {
    sink.next(errorEvent);     // 发送错误事件
    sink.next(doneEvent);      // 发送完成事件
    sink.complete();           // 正常关闭（而非sink.error()）
}
```

为什么不使用 `sink.error(e)`？如果在HTTP响应已经开始发送之后调用 `sink.error()`，会有以下问题：

1. HTTP状态码已经是200，无法改为500
2. Spring WebFlux可能会尝试向已写入的响应体追加错误信息，这在某些容器中会导致 `IllegalStateException`
3. 前端可能无法正确解析混合了SSE事件和错误响应的内容

因此，LyClaw采用了**业务级别的错误传播**：将错误信息编码为SSE事件（`event:error`），前端通过监听 `error` 事件来处理异常情况。

---

## 4.3 五阶段详解

### 4.3.1 Stage 1: CONTEXT_BUILD（上下文构建）

```java
long t1 = System.currentTimeMillis();
sink.next(sseEvent("context_build_start", "Loading session and retrieving memories"));
log.info("[Orchestrator] Stage 1: CONTEXT_BUILD for session={}", sessionId);

MemoryQuery memoryQuery = MemoryQuery.builder()
        .queryText(userMessage)
        .topK(10)
        .build();
MemoryQueryResult memoryResult = memoryFeignClient.retrieve(memoryQuery);
int memoryHits = memoryResult != null ? memoryResult.getTotalHits() : 0;
log.info("[Orchestrator] Memory retrieved: {} entries in {}ms",
        memoryHits, memoryResult != null ? memoryResult.getQueryTimeMs() : 0);
sink.next(sseEvent("context_build_complete",
        "Loaded session, retrieved " + memoryHits + " memory entries"));
```

**`long t1 = System.currentTimeMillis()`**

为这一阶段记录起始时间戳。后续用于计算阶段耗时并上报给MetricsCollector。

**`sink.next(sseEvent("context_build_start", ...))`**

发射一个 `context_build_start` 事件，通知前端管道已经开始工作。前端的典型处理是显示一个加载指示器或进度提示。

**`MemoryQuery.builder().queryText(userMessage).topK(10).build()`**

使用建造者模式（Builder Pattern）构建一个 `MemoryQuery` 对象：

- `.queryText(userMessage)`：用用户的当前消息作为查询文本，记忆服务会基于语义相似度检索相关历史记录
- `.topK(10)`：返回最相关的前10条记忆。`topK` 是信息检索领域的常用术语，K代表返回结果的数量

建造者模式的优势在此处很明显——调用者不需要知道 `MemoryQuery` 的构造函数签名和所有可选参数，链式调用清晰表达了每个参数的含义。

**`memoryFeignClient.retrieve(memoryQuery)`**

通过Feign声明式HTTP客户端调用远程的记忆服务。这一行代码的底层会：
1. 将 `memoryQuery` 序列化为JSON
2. 通过HTTP POST发送到 `lyclaw-memory-service`
3. 将响应JSON反序列化为 `MemoryQueryResult` 对象

这是一个同步阻塞调用——当前线程会等待记忆服务返回结果后才继续执行。由于整个方法运行在 `Schedulers.boundedElastic()` 上，这个阻塞不会影响其他请求的处理。

**`int memoryHits = memoryResult != null ? memoryResult.getTotalHits() : 0`**

防御性编程。`memoryResult` 可能为 `null`（例如服务降级或网络超时），此时将命中数设为0，管道继续执行而不是崩溃。

**`sink.next(sseEvent("context_build_complete", ...))`**

发射完成事件，携带检索到的记忆条目数。前端可以据此展示"已加载3条相关记忆"等信息。

**MetricsCollector调用**

```java
if (metricsCollector != null) {
    metricsCollector.recordMemoryRetrieval(
            memoryResult != null ? memoryResult.getQueryTimeMs() : 0, memoryHits);
    metricsCollector.recordPipelineStage("CONTEXT_BUILD",
            System.currentTimeMillis() - t1);
}
```

`metricsCollector` 可能为 `null`（例如在单元测试中未注入），所以先判空。`recordMemoryRetrieval()` 记录记忆检索的耗时和命中数，`recordPipelineStage()` 记录整个阶段的耗时。这些指标最终可能被发送到监控系统（如Prometheus、Micrometer）用于观测系统性能。

### 4.3.2 Stage 2: INTERCEPT（拦截检查）

```java
long t2 = System.currentTimeMillis();
sink.next(sseEvent("intercept_start", "Running security checks and content filter"));

if (securityManager != null) {
    var approvalResult = securityManager.approve(context, "EXECUTE_CHAT");
    if (!approvalResult.isApproved()) {
        log.warn("[Orchestrator] Security check denied: {}", approvalResult.getReason());
        sink.next(sseEvent("intercept_blocked", "Security check denied: " + approvalResult.getReason()));
        sink.next(sseEvent("done", "{\"status\":\"blocked\"}"));
        sink.complete();
        return;
    }
}

if (contentFilter != null) {
    FilterResult filterResult = contentFilter.filter(userMessage, context);
    if (!filterResult.isPassed()) {
        log.warn("[Orchestrator] Content filter blocked: {}", filterResult.getReason());
        sink.next(sseEvent("intercept_blocked", "Content filter blocked: " + filterResult.getReason()));
        sink.next(sseEvent("done", "{\"status\":\"blocked\"}"));
        sink.complete();
        return;
    }
}
sink.next(sseEvent("intercept_complete", "Security check and content filter passed"));
```

这个阶段执行两层检查：安全鉴权和内容过滤。

**安全检查：`securityManager.approve(context, "EXECUTE_CHAT")`**

`SecurityManager` 是权限管理组件。`approve()` 方法接受两个参数：
- `context`：当前会话的完整上下文，包含用户身份、会话信息等
- `"EXECUTE_CHAT"`：操作类型字符串，标识当前请求想要执行的操作为"执行聊天"

返回值 `approvalResult.isApproved()` 表示是否授权通过。不通过的原因通过 `getReason()` 获取，可能是"用户未登录"、"会话已过期"、"权限不足"等。

**阻断路径的关键代码**

```java
if (!approvalResult.isApproved()) {
    sink.next(sseEvent("intercept_blocked", "..."));
    sink.next(sseEvent("done", "{\"status\":\"blocked\"}"));
    sink.complete();
    return;  // ← 关键：跳出 lambda，不再执行后续阶段
}
```

这几行代码展示了SSE流中途终止的标准模式：

1. `sink.next(sseEvent("intercept_blocked", ...))` —— 告诉前端发生了拦截
2. `sink.next(sseEvent("done", ...))` —— 告诉前端流已结束（status为blocked）
3. `sink.complete()` —— 从Reactor层面关闭Flux
4. `return` —— 从lambda中返回，不执行后续的PLAN/EXECUTE/REFLECT阶段

注意 `return` 只退出当前的lambda（即 `Flux.create(sink -> { ... })` 的lambda），不影响 `execute()` 方法的返回值。外层的 `.concatWith(respondFlux)` 仍然生效，但由于 `pipelineOk` 仍为 `false`，`respondFlux` 会返回 `Flux.empty()`。

**内容过滤：`contentFilter.filter(userMessage, context)`**

`ContentFilter` 是内容安全组件。它检查用户的消息是否包含敏感、违规或不适当的内容。`filter()` 方法返回 `FilterResult` 对象，包含两个关键字段：
- `isPassed()`：是否通过过滤（`true`=内容安全，`false`=内容被拦截）
- `getReason()`：拦截原因的描述文本

同样，如果内容被拦截，走同样的阻断路径。

### 4.3.3 Stage 3: PLAN（任务规划）

```java
long t3 = System.currentTimeMillis();
sink.next(sseEvent("plan_start", "Planning task decomposition"));

PlanRequest planReq = PlanRequest.builder()
        .sessionId(sessionId)
        .userIntent(userMessage)
        .strategy("default")
        .context(Map.of("sessionId", sessionId, "timestamp", System.currentTimeMillis()))
        .build();
Map<String, Object> planResult = planFeignClient.plan(planReq);
```

**`PlanRequest.builder()`**

构建规划请求。关键字段：
- `.sessionId(sessionId)`：会话标识，规划服务可能需要历史对话信息
- `.userIntent(userMessage)`：用户的原始意图文本，规划服务据此分解任务
- `.strategy("default")`：规划策略。"default"表示使用默认的任务分解策略
- `.context(Map.of(...))`：附加的上下文信息，这里传入了会话ID和时间戳。`Map.of()` 是Java 9引入的不可变Map工厂方法，最多支持10个键值对

**`planFeignClient.plan(planReq)`**

通过Feign调用远程的规划服务（`lyclaw-plan-service`）。返回值是 `Map<String, Object>`——一个通用的键值对结构。这是因为规划服务返回的是动态的JSON结构，不同策略可能返回不同格式的结果。使用 `Map<String, Object>` 避免了为每种可能的响应格式定义专用的Java类。

**解析规划结果**

```java
@SuppressWarnings("unchecked")
List<Map<String, Object>> rawNodes = planResult != null && planResult.get("nodes") instanceof List
        ? (List<Map<String, Object>>) planResult.get("nodes")
        : Collections.emptyList();
```

这行代码包含了多个关键的防御性编程技巧：

1. **`planResult != null`**：先判空，防止NPE。如果规划服务返回了null（不可能发生但防御性编程），则使用空列表。
2. **`planResult.get("nodes") instanceof List`**：类型检查。确认 `"nodes"` 键对应的值确实是List类型后，再进行强制转换。
3. **`(List<Map<String, Object>>) planResult.get("nodes")`**：强制类型转换。由于Java的类型擦除（type erasure），泛型信息在运行时丢失，所以这里只能转换为原始类型 `List` 再赋给 `List<Map<String, Object>>`。
4. **`@SuppressWarnings("unchecked")`**：抑制编译器的"unchecked cast"警告。因为在编译时无法验证 `List` 的元素是否确实是 `Map<String, Object>`，编译器会发出警告。`@SuppressWarnings` 告诉编译器"我知道这个转换的风险，已经通过 `instanceof` 做了运行时检查"。
5. **`Collections.emptyList()`**：作为fallback的空不可变列表。使用 `Collections.emptyList()` 而非 `new ArrayList<>()` 是一个微观性能优化——它返回的是一个单例空列表对象，不会创建新的对象。

**遍历解析每个节点**

```java
for (Map<String, Object> raw : rawNodes) {
    @SuppressWarnings("unchecked")
    List<String> tools = raw.containsKey("requiredTools") && raw.get("requiredTools") instanceof List
            ? (List<String>) raw.get("requiredTools") : Collections.emptyList();
    @SuppressWarnings("unchecked")
    List<String> deps = raw.containsKey("dependencies") && raw.get("dependencies") instanceof List
            ? (List<String>) raw.get("dependencies") : Collections.emptyList();
    nodes.add(new TaskNode(
            (String) raw.getOrDefault("nodeId", ""),
            (String) raw.getOrDefault("type", "EXECUTE"),
            (String) raw.getOrDefault("description", ""),
            tools,
            deps,
            raw.get("timeoutMs") instanceof Number
                    ? ((Number) raw.get("timeoutMs")).longValue() : 30000L));
}
```

每个节点被解析为一个 `TaskNode` 对象：

1. **`raw.getOrDefault("nodeId", "")`**：从Map中获取节点ID，如果不存在则默认为空字符串
2. **`raw.getOrDefault("type", "EXECUTE")`**：节点类型，默认为 `"EXECUTE"`
3. **`raw.getOrDefault("description", "")`**：节点描述
4. **`tools`/`deps`**：所需工具列表和依赖关系列表，每个都用 `instanceof List` 做了防御检查
5. **`raw.get("timeoutMs") instanceof Number ? ((Number) raw.get("timeoutMs")).longValue() : 30000L`**：
   - `instanceof Number` 检查：JSON反序列化时，整数值可能被解析为 `Integer`、`Long` 或 `Double`（取决于具体JSON库和数值大小），使用最宽泛的 `Number` 类型做检查，然后用 `.longValue()` 安全转换为 `long`
   - 默认超时 `30000L`：30秒。`L` 后缀表示这是一个 `long` 类型的字面量

**发射规划进度事件**

```java
sink.next(sseEvent("plan_complete", "Planned " + nodes.size() + " task(s)"));

for (int i = 0; i < nodes.size(); i++) {
    TaskNode node = nodes.get(i);
    sink.next(sseEvent("plan_node",
            "{\"index\":" + (i + 1) + ",\"nodeId\":\"" + escapeJson(node.getNodeId())
                    + "\",\"type\":\"" + escapeJson(node.getType())
                    + "\",\"description\":\"" + escapeJson(node.getDescription()) + "\"}"));
}
```

`plan_complete` 事件后，每个任务节点发出一个 `plan_node` 事件。注意这里的手动JSON拼接方式（而非使用Jackson序列化）。这是一种轻量级的取舍——对于结构简单的JSON（只有4个字段），手动拼接避免了Jackson对象的创建开销，但代价是可读性和可维护性。

`escapeJson()` 方法（定义在类末尾）对字符串进行JSON转义，防止节点ID或描述中包含双引号、换行符等特殊字符破坏JSON结构。

### 4.3.4 Stage 4: EXECUTE（工具执行）

```java
long t4 = System.currentTimeMillis();
log.info("[Orchestrator] Stage 4: EXECUTE {} task(s)", nodes.size());

for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
    TaskNode node = nodes.get(nodeIndex);
    sink.next(sseEvent("action_start",
            "{\"index\":" + (nodeIndex + 1) + ",\"total\":" + nodes.size()
                    + ",\"nodeId\":\"" + escapeJson(node.getNodeId())
                    + "\",\"description\":\"" + escapeJson(node.getDescription()) + "\"}"));
```

工具执行阶段是顺序循环执行的——每个节点按顺序逐个执行。`action_start` 事件包含：
- `index`：当前是第几个节点（从1开始，`nodeIndex + 1`）
- `total`：节点总数（用于前端的进度条展示，如"2/5"）
- `nodeId` 和 `description`：节点的标识和描述

**构建和执行每个工具调用**

```java
long toolStart = System.currentTimeMillis();
try {
    ToolExecuteRequest toolReq = ToolExecuteRequest.builder()
            .toolName(node.getType())
            .args(Map.of("nodeId", node.getNodeId(),
                    "description", node.getDescription(),
                    "sessionId", sessionId))
            .sessionId(sessionId)
            .build();
    ToolResult result = actionFeignClient.executeTool(toolReq);
    long toolDuration = System.currentTimeMillis() - toolStart;
```

`ToolExecuteRequest` 的构建：
- `.toolName(node.getType())`：工具名称直接使用TaskNode的type字段（如"EXECUTE"、"WEB_SEARCH"等）
- `.args(...)`：传递给工具的参数Map，包括节点ID、描述和会话ID
- `.sessionId(sessionId)`：会话ID，工具可能需要用到来查询会话上下文

`actionFeignClient.executeTool(toolReq)` 通过Feign调用远程的动作执行服务（`lyclaw-action-service`）。

`toolDuration` 记录单个工具的执行耗时，用于在action_result事件中透传给前端。

**处理成功结果**

```java
if (result != null && result.isSuccess()) {
    successCount.incrementAndGet();
    String output = result.getOutput() != null ? result.getOutput() : "";
    toolResults.add(output);
    sink.next(sseEvent("action_result",
            "{\"index\":" + (nodeIndex + 1) + ",\"status\":\"success\",\"output\":\""
                    + escapeJson(output) + "\",\"durationMs\":" + toolDuration + "}"));
    if (metricsCollector != null) metricsCollector.recordToolCall(node.getType(), true, toolDuration);
}
```

- `successCount.incrementAndGet()`：原子递增成功计数。`.incrementAndGet()` 是先加1再返回新值的原子操作，相当于 `++count`（而非 `count++`）
- `escapeJson(output)`：工具输出可能包含特殊字符，必须转义后再拼入JSON字符串
- `metricsCollector.recordToolCall(node.getType(), true, toolDuration)`：记录工具调用的指标（工具名、是否成功、耗时）

**处理失败结果**

```java
} else {
    failCount.incrementAndGet();
    String error = result != null ? result.getErrorMessage() : "unknown error";
    toolResults.add("ERROR: " + error);
    sink.next(sseEvent("action_result",
            "{\"index\":" + (nodeIndex + 1) + ",\"status\":\"failed\",\"error\":\""
                    + escapeJson(error) + "\",\"durationMs\":" + toolDuration + "}"));
    if (metricsCollector != null) metricsCollector.recordToolCall(node.getType(), false, toolDuration);
}
```

失败的结果仍被收集到 `toolResults` 中（前缀 "ERROR: "），这样LLM在生成回复时可以看到哪些工具失败了，从而可能给出替代方案或道歉。

**处理工具执行异常**

```java
} catch (Exception e) {
    failCount.incrementAndGet();
    long toolDuration = System.currentTimeMillis() - toolStart;
    log.error("[Orchestrator] Tool execution failed: nodeId={}, error={}",
            node.getNodeId(), e.getMessage());
    toolResults.add("ERROR: " + e.getMessage());
    sink.next(sseEvent("action_result",
            "{\"index\":" + (nodeIndex + 1) + ",\"status\":\"error\",\"error\":\""
                    + escapeJson(e.getMessage()) + "\",\"durationMs\":" + toolDuration + "}"));
    if (metricsCollector != null) metricsCollector.recordToolCall(node.getType(), false, toolDuration);
}
```

这个 `catch` 块捕获的是工具执行过程中抛出的**未预期异常**（如网络超时、序列化错误等）。注意它与上面 `else` 分支的区别：
- `else` 分支（`result.isSuccess() == false`）：工具执行了但返回了失败状态
- `catch` 分支：工具根本没有正常返回（抛出了异常）

两者的共同点是：**都不会中断管道**。单个工具失败不影响其他工具的执行，也不影响后续的反思和LLM响应阶段。这种"fail-soft"（软失败）设计确保了系统的鲁棒性。

**发射action_complete**

```java
sink.next(sseEvent("action_complete",
        "{\"total\":" + nodes.size() + ",\"success\":" + successCount.get()
                + ",\"failed\":" + failCount.get() + "}"));
```

在所有节点执行完毕后，发射一个汇总事件，告诉前端本次共执行了多少个工具，成功几个，失败几个。

### 4.3.5 Stage 5: REFLECT（反思评估）

```java
long t5 = System.currentTimeMillis();
sink.next(sseEvent("reflect_start", "Reflecting on execution results"));

String combinedOutput = String.join("\n", toolResults);
ReflectRequest reflectReq = ReflectRequest.builder()
        .sessionId(sessionId)
        .output(combinedOutput.isEmpty() ? userMessage : combinedOutput)
        .context("Orchestration pipeline execution - " + nodes.size() + " tasks processed")
        .build();
ReflectionReport r = reflectFeignClient.reflect(reflectReq);
reportRef.set(r);
double score = r != null ? r.getOverallScore() : 0.0;
reflectScoreRef.set(score);

sink.next(sseEvent("reflect_complete",
        "{\"score\":" + score + ",\"reflectionId\":\""
                + (r != null ? r.getReflectionId() : "N/A") + "\"}"));
```

**`String combinedOutput = String.join("\n", toolResults)`**

将所有工具的输出用换行符连接成一个字符串。`String.join()` 是Java 8引入的静态方法，第一个参数是分隔符，第二个参数是 `Iterable<String>`。这是将多个字符串连接成一个的最简洁写法。

**`combinedOutput.isEmpty() ? userMessage : combinedOutput`**

一个边界条件处理：如果工具没有产生任何输出（例如规划了0个任务），则使用用户原始消息作为反思的输入，确保反思服务至少有内容可以评估。

**`ReflectRequest.builder()`**

构建反思请求：
- `.output(...)`：需要反思的内容（工具执行结果或用户消息）
- `.context(...)`：附加的上下文描述

**`reflectFeignClient.reflect(reflectReq)`**

通过Feign调用远程的反思服务（`lyclaw-reflect-service`）。反思服务会对执行结果进行评估，返回一个 `ReflectionReport` 对象，包含评分、质量维度等。

**管道收尾**

```java
respondStartMs.set(System.currentTimeMillis());
pipelineOk.set(true);
sink.complete();
```

这是管道阶段的最后三行：

1. `respondStartMs.set(System.currentTimeMillis())`：记录响应阶段开始的时间戳
2. `pipelineOk.set(true)`：设置管道成功标志，允许响应阶段执行LLM调用
3. `sink.complete()`：从Reactor层面完成pipelineFlux

---

## 4.4 sseEvent()辅助方法

```java
private ServerSentEvent<String> sseEvent(String eventType, String payload) {
    return ServerSentEvent.<String>builder().event(eventType).data(payload).build();
}
```

这是一个简洁的工厂方法，封装了 `ServerSentEvent` 的构建过程。

**`ServerSentEvent.<String>builder()`**

`ServerSentEvent` 是Spring WebFlux提供的一个不可变值对象，用于表示SSE协议的单个事件。`.builder()` 返回一个 `Builder<T>` 实例，`T` 是 `data` 字段的类型。`<String>` 显式指定了泛型类型（虽然类型推断通常也能工作，但显式写出更清晰）。

最终Spring会将这个对象序列化为：

```
event:{eventType}
data:{payload}

```

**`.event(eventType)`**

设置SSE事件的 `event:` 字段。在LyClaw中，这是一个类别标签，如 `"message"`、`"done"`、`"context_build_start"` 等。前端JavaScript通过 `eventSource.addEventListener('message', callback)` 来监听特定事件类型。

**`.data(payload)`**

设置SSE事件的 `data:` 字段。这是事件的实际载荷，可以是纯文本、JSON字符串或任何字符串。Spring会自动处理多行data的情况（如果data包含换行符，会拆成多个 `data:` 行）。

**`.build()`**

构建不可变的 `ServerSentEvent` 对象。Builder会验证必要的字段是否已设置（event和数据都不能为null），然后创建对象。

---

## 4.5 异常处理与错误传播

```java
} catch (Exception e) {
    log.error("[Orchestrator] Pipeline failed: {}", e.getMessage(), e);
    sink.next(sseEvent("error",
            "{\"message\":\"" + escapeJson(e.getMessage()) + "\"}"));
    sink.next(sseEvent("done", "{\"status\":\"error\"}"));
    sink.complete();
}
```

这个catch块包裹了管道阶段的所有5个子阶段。它捕获的是未能在子阶段内部处理的"意外"异常。

### 异常传播策略的选择

在Reactor中处理异常有三个层次：

**层次一：sink.error(Throwable)**
```java
sink.error(e);  // 将异常传播给Reactor的错误通道
```
这会触发下游的 `onError` 回调。Spring WebFlux收到 `sink.error()` 后会尝试终止HTTP响应，但可能因响应已开始发送而出错。

**层次二：不处理，让异常向上抛出**
```java
// 不捕获，让异常逐层向上传播
```
在 `Flux.create()` 中抛出的未捕获异常会被Reactor包装为 `onError` 信号。与使用`sink.error()`的效果类似。

**层次三：业务级错误传播（LyClaw采用）**
```java
sink.next(sseEvent("error", "..."));
sink.next(sseEvent("done", "{\"status\":\"error\"}"));
sink.complete();
```
将错误编码为SSE事件，以正常方式完成流。前端通过监听 `error` 事件来处理。这是SSE流式API的最佳实践。

### 为什么选择层次三

1. **HTTP协议约束**：一旦服务器发送了第一个字节的响应体，HTTP状态码和响应头就已经确定了。无法将一个200响应改为500响应。
2. **前端一致性**：前端只需要监听SSE事件即可获取所有信息（包括错误信息），不需要额外处理HTTP错误状态码。
3. **调试友好**：在浏览器Network面板中，`done`事件的 `status` 字段可以直观地看到请求的最终状态：`"completed"`（成功）、`"blocked"`（被拦截）、`"error"`（异常）。

注意 `sink.complete()` 仍然是必需的——即使发生了异常，也必须显式调用它来通知Reactor框架（以及Spring WebFlux）这个Flux已经结束，不再有更多元素。

---

# 第五章：LLM流式响应 — adapter.chatStream()与SSE解析

本章我们将深入LyClaw的LLM适配层，详细分析从 `respondFlux` 的构建到DeepSeek API的SSE响应解析的完整链路。阅读本章前，请先阅读以下源文件：
- `/home/lyjew/Documents/Unicom/LyClaw/lyclaw-core/src/main/java/lyjew/com/lyclaw/adapter/ModelAdapter.java`
- `/home/lyjew/Documents/Unicom/LyClaw/lyclaw-core/src/main/java/lyjew/com/lyclaw/template/AbstractModelAdapter.java`
- `/home/lyjew/Documents/Unicom/LyClaw/lyclaw-adapter/src/main/java/lyjew/com/lyclaw/adapter/deepseek/DeepSeekOpenAIAdapter.java`

---

## 5.1 respondFlux的构建

在管道阶段的 `sink.complete()` 之后，`respondFlux` 开始执行。以下是 `OrchestratorImpl.execute()` 中构建 `respondFlux` 的完整代码：

```java
Flux<ServerSentEvent<String>> respondFlux = Flux.defer(() -> {
    if (!pipelineOk.get()) {
        return Flux.empty();
    }

    int sc = successCount.get();
    int fc = failCount.get();
    ReflectionReport report = reportRef.get();
    double score = reflectScoreRef.get();

    log.info("[Orchestrator] Stage 6: RESPOND");

    ModelAdapter adapter = modelProvider.getConfiguredAdapter();

    Flux<ServerSentEvent<String>> bodyFlux;
    if (adapter != null) {
        lyjew.com.lyclaw.model.ChatRequest llmRequest = buildLlmRequest(context, toolResults);
        log.info("[Orchestrator] Calling LLM: provider={}, model={}, messages={}",
                adapter.getProvider(), adapter.getModel(), llmRequest.getMessageCount());

        bodyFlux = adapter.chatStream(llmRequest)
                .handle((line, sink) -> {
                    String text = adapter.extractSsePlainText(line);
                    if (!text.isEmpty()) {
                        sink.next(sseEvent("message", text));
                    }
                });
    } else {
        log.warn("[Orchestrator] No LLM adapter configured, using hardcoded response");
        String responseText = buildFinalResponse(sc, fc, toolResults, report);
        bodyFlux = Flux.just(sseEvent("message", responseText));
    }

    return Flux.just(sseEvent("respond_start", "Generating AI response"))
            .concatWith(bodyFlux)
            .concatWith(buildTailFlux(context, orchestrationStart, respondStartMs.get(),
                    sc, fc, toolResults, report, score))
            .onErrorResume(err -> {
                log.error("[Orchestrator] LLM call failed: {}", err.getMessage());
                String fallback = buildFinalResponse(sc, fc, toolResults, report);
                return Flux.just(
                        sseEvent("message", fallback),
                        sseEvent("done", "{\"status\":\"completed\",\"fallback\":true}")
                );
            });
});
```

### 逐行解读

**`Flux.defer(() -> {`**

为什么respondFlux也要用 `defer()` 包裹？原因有两个：

1. **延迟获取共享状态**：`successCount.get()`、`reportRef.get()` 这些值只有在管道阶段完成后才能得到正确的结果。`defer()` 保证了这些Getter在管道完成后才被调用。
2. **延迟创建adapter**：`modelProvider.getConfiguredAdapter()` 可能涉及配置刷新等操作，延迟到实际需要时才执行可以减少不必要的开销。

**`if (!pipelineOk.get()) { return Flux.empty(); }`**

这是管道和响应之间的"安全阀"。如果管道阶段因为安全检查拦截或异常而没有完成（`pipelineOk` 仍为 `false`），直接返回一个空的Flux。这意味着不会调用LLM，SSE流在管道的 `done` 事件后就结束了。

`Flux.empty()` 是一个不发射任何元素、直接完成信号的Flux。它比返回 `null` 要安全得多——Reactor的操作符链可以正常处理空的Flux（`.concatWith(Flux.empty())` 等同于无操作）。

**`int sc = successCount.get();`**

通过 `.get()` 方法从 `AtomicInteger` 中读取int值。这是一个原子读操作，保证了内存可见性（即使将来管道阶段改为多线程执行，也能看到最新的值）。

**`ModelAdapter adapter = modelProvider.getConfiguredAdapter();`**

`ModelProvider` 是模型适配器的工厂/注册中心。`getConfiguredAdapter()` 返回当前系统中已配置的默认适配器实例。在LyClaw中，这通常是 `DeepSeekOpenAIAdapter` 的实例。

这个设计使得适配器对Orchestrator来说是透明的——Orchestrator不关心具体使用的是哪个LLM提供商，只通过 `ModelAdapter` 接口调用。

**适配器为null的降级路径**

```java
if (adapter != null) {
    // 正常路径：调用LLM
} else {
    log.warn("[Orchestrator] No LLM adapter configured, using hardcoded response");
    String responseText = buildFinalResponse(sc, fc, toolResults, report);
    bodyFlux = Flux.just(sseEvent("message", responseText));
}
```

如果系统中没有配置任何LLM适配器（例如初次部署、配置错误），系统不会崩溃，而是使用 `buildFinalResponse()` 生成一个硬编码的摘要回复。回复内容包括：
- 工具执行统计（成功/失败数量）
- 反思评分
- 工具结果摘要（每条结果截取前200字符）

`Flux.just(sseEvent("message", responseText))` 创建了一个只包含单个元素的Flux。这仍然是一个合法的响应——前端会收到一个 `message` 事件，包含完整的回复文本。

**正常路径：调用adapter.chatStream()**

```java
bodyFlux = adapter.chatStream(llmRequest)
        .handle((line, sink) -> {
            String text = adapter.extractSsePlainText(line);
            if (!text.isEmpty()) {
                sink.next(sseEvent("message", text));
            }
        });
```

这里使用了一个关键的Reactor操作符：`.handle()`。

**`.handle()` 操作符的双重功能**

`.handle()` 是Reactor中介于 `.map()` 和 `.filter()` 之间的一个操作符。它的签名是：

```java
public final <R> Flux<R> handle(BiConsumer<? super T, SynchronousSink<R>> handler)
```

- 它可以**转换**元素（像 `.map()`）
- 它可以**过滤**元素（像 `.filter()`）
- 它可以**生成零到多个元素**（类似 `.flatMap()` 但只有同步能力）

在这个场景中，`.handle()` 同时做了两件事：
1. 调用 `adapter.extractSsePlainText(line)` 将原始SSE行转换为纯文本
2. 检查文本是否为空——如果为空，不调用 `sink.next()`，相当于过滤掉了这个元素

如果使用 `.map().filter()` 链来实现：
```java
.map(line -> adapter.extractSsePlainText(line))
.filter(text -> !text.isEmpty())
.map(text -> sseEvent("message", text))
```

这需要两次遍历。而 `.handle()` 一次遍历就完成了转换+过滤+封装的全部操作，减少了对象创建和GC压力。

**三段式拼接**

```java
return Flux.just(sseEvent("respond_start", "Generating AI response"))
        .concatWith(bodyFlux)
        .concatWith(buildTailFlux(...))
        .onErrorResume(err -> { ... });
```

`respondFlux` 内部也是一个三段式拼接：

1. **`Flux.just(sseEvent("respond_start", ...))`**：发射一个单元素的Flux，通知前端LLM开始生成回复
2. **`bodyFlux`**：LLM token的流式数据
3. **`buildTailFlux(...)`**：收尾事件（respond_complete、metrics、done）

`.concatWith()` 保证了严格的事件顺序——前端永远不会在 `respond_start` 之前收到 `message` 事件。

---

## 5.2 buildTailFlux() — 收尾事件流

```java
private Flux<ServerSentEvent<String>> buildTailFlux(ChatContext context, long orchestrationStart, long respondStartMs,
                                    int successCount, int failCount, List<String> toolResults,
                                    ReflectionReport report, double score) {
    String sessionId = context.getRequest().getSessionId();
    int taskCount = toolResults.size();
```

### 持久化记忆

```java
PerceptionData perception = PerceptionData.builder()
        .role("assistant")
        .content("Orchestration completed | Tasks: " + taskCount
                + " | Success: " + successCount
                + " | Failed: " + failCount
                + " | ReflectScore: " + score)
        .timestamp(System.currentTimeMillis())
        .metadata(Map.of("sessionId", sessionId,
                "taskCount", taskCount,
                "successCount", successCount,
                "failCount", failCount,
                "orchestrationDurationMs", System.currentTimeMillis() - orchestrationStart))
        .build();
memoryFeignClient.ingest(perception, sessionId, "default");
```

在LLM回复完成后，系统会将本次对话的内容持久化到记忆服务中。`PerceptionData` 对象记录了：

- `.role("assistant")`：角色标识，标记为AI助手的输出
- `.content(...)`：摘要内容，包括任务统计和反思评分
- `.timestamp(...)`：时间戳，用于记忆的时间线排序
- `.metadata(...)`：结构化元数据（会话ID、任务数量、成功/失败数、总耗时）

`memoryFeignClient.ingest()` 将这个感知数据发送到记忆服务进行存储。之后的对话中，`CONTEXT_BUILD` 阶段就可以检索到这些历史记忆。

### 记录指标

```java
if (metricsCollector != null) {
    metricsCollector.recordPipelineStage("RESPOND",
            System.currentTimeMillis() - respondStartMs);
}

long totalDuration = System.currentTimeMillis() - orchestrationStart;
log.info("[Orchestrator] Orchestration completed: {} tasks in {}ms",
        taskCount, totalDuration);

if (metricsCollector != null) {
    metricsCollector.recordPipelineStage("ORCHESTRATION_TOTAL", totalDuration);
    metricsCollector.recordLlmCall("orchestrator", "llm", 0, 0, totalDuration);
    metricsCollector.recordPipelineStage("METRICS", 0);
}
```

`totalDuration` 是从 `execute()` 方法入口开始计算的总耗时，涵盖了所有6个阶段。这个值在 `metrics` 事件中透传给前端。

### 发射收尾事件

```java
return Flux.just(
        sseEvent("respond_complete", "Response generated and memory persisted"),
        sseEvent("metrics",
                "{\"totalDurationMs\":" + totalDuration
                        + ",\"tasksProcessed\":" + taskCount
                        + ",\"successRate\":"
                        + (taskCount > 0
                                ? String.format("%.2f", (double) successCount / taskCount)
                                : "1.0")
                        + ",\"reflectScore\":" + score + "}"),
        sseEvent("done",
                "{\"status\":\"completed\",\"durationMs\":" + totalDuration + "}")
);
```

三个收尾事件按顺序发射：

1. **`respond_complete`**：通知前端LLM回复已生成并且记忆已持久化
2. **`metrics`**：性能指标JSON。包含：
   - `totalDurationMs`：总耗时（毫秒）
   - `tasksProcessed`：处理的工具任务总数
   - `successRate`：任务成功率（格式化为两位小数的百分比）。`String.format("%.2f", value)` 将浮点数格式化为保留两位小数的字符串（例如 `0.666...` → `"0.67"`）
   - `reflectScore`：反思评分
3. **`done`**：最终完成事件。`status` 为 `"completed"`，并携带总耗时

`Flux.just()` 接收三个参数，意味着这个Flux会依次发射这三个元素然后自动完成（不需要显式调用 `sink.complete()`）。

---

## 5.3 ModelAdapter接口设计

```java
public interface ModelAdapter {

    ModelResponse chat(ChatRequest request);

    Flux<String> chatStream(ChatRequest request);

    int countTokens(String text);

    boolean validate();

    String getProvider();

    boolean isConfigured();

    void configure(ModelConfig config);

    String getModel();

    String getBaseUrl();

    default List<ModelResponse.ToolCallRequest> extractSseToolCalls(String rawSSE) {
        return List.of();
    }

    default String extractSsePlainText(String rawSSE) {
        return "";
    }

    default String extractSseTokenUsage(String rawSSE) {
        return "prompt=0 completion=0 total=0";
    }
}
```

### 接口方法分类

**核心调用方法**

- **`ModelResponse chat(ChatRequest request)`**：同步（阻塞式）调用LLM。返回完整的 `ModelResponse`，包含生成的文本、token用量、工具调用等。适用于不需要流式的场景。
- **`Flux<String> chatStream(ChatRequest request)`**：流式调用LLM。返回一个 `Flux<String>`，每个元素是来自LLM API的一行原始SSE数据（如 `data: {"choices":[{"delta":{"content":"你好"}}]}`）。注意返回类型是 `Flux<String>` 而非 `Flux<ServerSentEvent>`——这是"关注点分离"的设计：

  - 适配层的职责是**与LLM API通信**，返回的是原始的SSE文本行
  - Orchestrator层的职责是**将原始SSE行转换为面向客户端的SSE事件**
  
  这种分层使得适配层不需要了解LyClaw内部的事件类型体系。

**配置管理方法**

- **`getProvider()`**：返回提供商名称字符串（如 `"deepseek-openai"`）。用于日志记录和配置匹配。
- **`isConfigured()`**：检查适配器是否已完成配置（API密钥、基础URL、模型名均已设置）。
- **`configure(ModelConfig config)`**：使用外部配置初始化适配器。通常在应用启动时调用。
- **`getModel()`**：返回当前使用的模型名称（如 `"deepseek-v4-flash"`）。
- **`getBaseUrl()`**：返回API基础URL（如 `"https://api.deepseek.com"`）。

**工具方法**

- **`countTokens(String text)`**：估算给定文本的token数量。不同模型有不同的tokenizer，这个方法提供一个近似估算。
- **`validate()`**：验证连接是否可用。通常发送一个最小化的测试请求，检查是否能得到有效的响应。

**SSE解析方法（default实现）**

- **`extractSseToolCalls(String rawSSE)`**：从SSE流中提取工具调用请求。默认返回空列表——大多数对话不涉及工具调用。
- **`extractSsePlainText(String rawSSE)`**：从SSE流中提取纯文本内容。这是最核心的解析方法——将 `data: {"choices":[{"delta":{"content":"你好"}}]}` 转换为 `"你好"`。
- **`extractSseTokenUsage(String rawSSE)`**：从SSE流中提取token用量信息。默认返回全零的占位字符串。

这三个方法是 `default` 方法（Java 8接口默认方法），意味着实现类可以选择性地重写它们。对于不支持工具调用或没有特殊解析需求的适配器，默认实现就足够了。

### 设计模式：策略模式与适配器模式

`ModelAdapter` 接口同时应用了两种设计模式：

- **策略模式（Strategy Pattern）**：不同的LLM提供商（DeepSeek、OpenAI、Claude等）是不同的策略实现。`ModelProvider` 在运行时根据配置选择具体的策略。
- **适配器模式（Adapter Pattern）**：`ModelAdapter` 将各种LLM API的差异封装在内部，对外暴露统一的 `ChatRequest`/`ModelResponse` 模型。Orchestrator不需要知道底层是DeepSeek还是OpenAI。

---

## 5.4 AbstractModelAdapter模板方法模式

```java
@Slf4j
public abstract class AbstractModelAdapter implements ModelAdapter {

    protected String apiKey;
    protected String baseUrl;
    protected String model;
    protected boolean configured = false;
```

### 模板方法模式的核心

`AbstractModelAdapter` 是一个抽象类，实现了 `ModelAdapter` 接口中的通用逻辑，同时定义了子类必须实现的抽象方法。这体现了**模板方法模式（Template Method Pattern）**：

```
AbstractModelAdapter (模板)
  │
  ├── chatStream()           ← 模板方法（定义了算法骨架）
  │   ├── checkConfigured()  ← 具体方法（在模板中实现）
  │   ├── beforeCall()       ← 具体方法（在模板中实现）
  │   ├── buildStreamRequest()  ← 抽象方法（子类实现）
  │   ├── sendStreamRequest()   ← 抽象方法（子类实现）
  │   └── handleError()      ← 具体方法（在模板中实现）
  │
  └── 具体子类: DeepSeekOpenAIAdapter
      ├── buildStreamRequest()  ← 构建DeepSeek API格式的请求体
      └── sendStreamRequest()   ← 向DeepSeek API发送请求
```

### 配置方法详解

```java
@Override
public void configure(ModelConfig config) {
    if (config == null) {
        throw ErrorCode.ADAPTER_NOT_CONFIGURED.exception("ModelConfig 为 null");
    }
    if (!getProvider().equals(config.getProvider())) {
        throw ErrorCode.ADAPTER_NOT_CONFIGURED.exception(
                "Provider 不匹配：期望 " + getProvider() + "，实际 " + config.getProvider());
    }
    this.apiKey = config.getApiKey();
    this.baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : getDefaultBaseUrl();
    this.model = config.getModel() != null && !config.getModel().isEmpty()
            ? config.getModel() : getDefaultModel();
    this.configured = true;
}
```

配置方法的防御性检查：

1. **`config == null`**：参数为空，直接抛异常
2. **`!getProvider().equals(config.getProvider())`**：Provider不匹配。例如，`DeepSeekOpenAIAdapter` 的 `getProvider()` 返回 `"deepseek-openai"`，如果配置传入了 `"openai"`，说明配置错误，立即抛出异常。
3. **`this.baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : getDefaultBaseUrl()`**：如果配置中提供了自定义URL则使用，否则使用默认值（`DEFAULT_BASE_URL = "https://api.deepseek.com"`）
4. **`this.model = config.getModel() != null && !config.getModel().isEmpty() ? config.getModel() : getDefaultModel()`**：同样的三目运算fallback模式，如果未指定模型则使用默认模型（`"deepseek-v4-flash"`）

### chatStream()模板方法

```java
@Override
public Flux<String> chatStream(ChatRequest request) {
    checkConfigured();
    try {
        beforeCall(request);
        Object apiRequest = buildStreamRequest(request);
        return sendStreamRequest(apiRequest)
                .doOnError(error -> {
                    log.error("[{}] 流式请求失败", getProvider(), error);
                    handleError(error);
                });
    } catch (ModelException e) {
        return Flux.error(e);
    } catch (Exception e) {
        handleError(e);
        return Flux.error(e);
    }
}
```

这个方法是模板方法模式的典范：

1. **`checkConfigured()`**：前置条件检查。如果适配器未配置，直接抛出异常。这是一个快速失败（fail-fast）的设计。
2. **`beforeCall(request)`**：调用前的钩子。在抽象类中的实现只是验证消息列表不为空，但子类可以覆写以添加更多检查（如参数校验、触发钩子等）。
3. **`Object apiRequest = buildStreamRequest(request)`**：**模板方法中的抽象步骤**。将内部统一的 `ChatRequest` 转换为特定LLM API的请求对象。返回类型是 `Object`——因为不同API的请求格式差异很大（OpenAI是JSON对象、Anthropic是另一个JSON结构），使用 `Object` 提供了最大的灵活性。
4. **`return sendStreamRequest(apiRequest)`**：**模板方法中的抽象步骤**。发送流式HTTP请求，返回 `Flux<String>`。
5. **`.doOnError(error -> { handleError(error); })`**：Reactor的错误副作用处理。注意 `.doOnError()` 只执行副作用（如日志记录），不会改变错误信号——错误仍然会向下游传播。

**两层异常捕获**

外层的 `try/catch` 捕获 `buildStreamRequest()` 阶段的异常（如JSON序列化失败），内层的 `.doOnError()` 处理HTTP请求阶段的异常（如网络超时）。两层捕获确保了无论哪一步出错，都有兜底处理。

两个catch分支的区别：
- `ModelException`：已知的业务异常（如配置错误、API返回错误），直接包装为 `Flux.error()`
- `catch (Exception e)`：未知的系统异常，先调用 `handleError()` 进行处理（默认实现是包装为 `ModelException`），然后同样返回 `Flux.error()`

### checkConfigured()

```java
private void checkConfigured() {
    if (!isConfigured()) {
        throw ErrorCode.ADAPTER_NOT_CONFIGURED.exception("适配器 [" + getProvider() + "] 尚未配置");
    }
}
```

`isConfigured()` 的实现：

```java
public boolean isConfigured() {
    return configured && apiKey != null && !apiKey.isEmpty();
}
```

三个条件必须同时满足：
1. `configured` 标志为 `true`（说明 `configure()` 方法已被成功调用）
2. `apiKey` 不为 `null`
3. `apiKey` 不为空字符串

### beforeCall() / afterCall()

```java
protected void beforeCall(ChatRequest request) {
    if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
        throw ErrorCode.MODEL_INVALID_REQUEST.exception("消息列表不能为空");
    }
}

protected void afterCall(ModelResponse response) {
    if (response != null && response.getUsage() != null) {
        log.info("[{}] Token用量: prompt={}, completion={}, total={}", getProvider(),
                response.getUsage().getPromptTokens(), response.getUsage().getCompletionTokens(),
                response.getUsage().getTotalTokens());
    }
}
```

这两个方法是钩子方法（Hook Method）——子类可以覆写它们来插入自定义逻辑。例如：
- `beforeCall()`：可以添加请求日志、参数校验、触发限流检查等
- `afterCall()`：可以记录token用量用于计费、更新缓存等

---

## 5.5 DeepSeekOpenAIAdapter实现

```java
@Slf4j
@Component
public class DeepSeekOpenAIAdapter extends AbstractModelAdapter {

    private static final String ENDPOINT = "/chat/completions";
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";

    private final ModelApiClient httpClient;
    private final OpenAIResponseParser responseParser;
    private final ObjectMapper objectMapper;
```

`@Component` 注解使这个类被Spring自动扫描并注册为Bean。由于它实现了 `ModelAdapter` 接口，`ModelProvider` 可以通过依赖注入获取它。

### buildStreamRequest() / buildOpenAIRequest()

```java
@Override
protected Object buildStreamRequest(ChatRequest request) {
    return buildOpenAIRequest(request, true);
}
```

`buildStreamRequest()` 只是 `buildOpenAIRequest()` 的一个包装，强制传入 `stream=true`。这保证了流式请求一定会带上 `"stream": true`。

```java
private OpenAIRequest buildOpenAIRequest(ChatRequest request, boolean stream) {
    String modelName = (request.getModel() != null && !request.getModel().isEmpty())
            ? request.getModel() : this.model;

    OpenAIRequest.OpenAIRequestBuilder builder = OpenAIRequest.builder()
            .model(modelName)
            .stream(stream);

    if (request.getMaxTokens() != null && request.getMaxTokens() > 0) {
        builder.maxTokens(request.getMaxTokens());
    }
    if (request.getTemperature() != null) {
        builder.temperature(clampTemperature(request.getTemperature(), 0.0, 2.0));
    }
    if (request.getTopP() != null) {
        builder.topP(request.getTopP());
    }
    if (request.getStopSequences() != null && !request.getStopSequences().isEmpty()) {
        builder.stop(request.getStopSequences());
    }
    builder.messages(buildMessages(request));
```

**模型名fallback**：`request.getModel() != null ? request.getModel() : this.model`。用户可以在请求中指定模型（覆盖默认配置），如果不指定则使用适配器配置的默认模型。

**参数设置**：

- `maxTokens`：最大生成token数。只有在值大于0时才设置（0或null表示不限制）。
- `temperature`：采样温度，控制输出的随机性。通过 `clampTemperature()` 限制在 [0.0, 2.0] 范围内——OpenAI API的温度范围是0-2，超出范围会返回错误。
- `topP`：核采样（nucleus sampling）参数，只从累积概率超过topP的最小token集合中采样。
- `stop`：停止序列列表。当生成的文本中出现这些序列时，API停止生成。
- `messages`：对话消息列表，通过 `buildMessages()` 构建。

**工具配置**

```java
if (request.hasTools()) {
    builder.tools(buildTools(request.getTools()));
    builder.toolChoice(resolveToolChoice(request));
}
```

如果请求中包含了工具定义（function calling），则将工具列表和工具选择策略设置到API请求中。

`resolveToolChoice()` 方法处理了多种输入格式：
- 字符串 `"auto"`、`"none"`、`"required"`：直接透传
- 非空字符串（工具名）：构造 `{"type":"function","function":{"name":"xxx"}}` 结构
- Map对象：直接透传（允许高级用户自定义复杂结构）
- 其他：默认 `"auto"`（让模型自己决定是否调用工具）

**思考模式配置**

```java
if (request.isThinkingEnabled()) {
    builder.thinking(OpenAIRequest.Thinking.builder()
            .type("enabled")
            .build());
    builder.reasoningEffort(
            request.getThinkingBudget() != null && request.getThinkingBudget() > 8000
                    ? "high" : "medium");
} else {
    builder.thinking(OpenAIRequest.Thinking.builder()
            .type("disabled")
            .build());
}
```

DeepSeek V4模型支持思考模式（thinking/reasoning），模型在生成最终回复之前会先进行内部推理。这段代码根据 `request.isThinkingEnabled()` 决定是否启用：

- 启用时：设置 `thinking.type = "enabled"`，并根据 `thinkingBudget` 决定推理深度（>8000 tokens用"high"，否则用"medium"）
- 禁用时：设置 `thinking.type = "disabled"`

### buildMessages() 消息构建

```java
private List<OpenAIRequest.Message> buildMessages(ChatRequest request) {
    List<OpenAIRequest.Message> messages = new ArrayList<>();

    if (request.hasSystemPrompt()) {
        OpenAIRequest.Message systemMsg = new OpenAIRequest.Message();
        systemMsg.setRole("system");
        systemMsg.setContent(request.getSystemPrompt());
        messages.add(systemMsg);
    }

    for (Message msg : request.getMessages()) {
        if ("system".equals(msg.getRole())) {
            continue;  // 跳过已在上面添加过的system消息
        }
        // ... 处理 user、assistant、tool 消息
    }
}
```

这段代码将内部的 `Message` 对象转换为 `OpenAIRequest.Message` 对象。关键的处理逻辑：

1. **System消息特殊处理**：如果 `ChatRequest` 中有 `systemPrompt`（独立字段），先构建一个system消息。然后在遍历消息列表时跳过role为"system"的消息，避免重复。
2. **Tool消息**：如果是 `tool` 角色，需要设置 `toolCallId`（对应之前的工具调用请求），以便API知道这个结果是哪个工具调用的返回值。
3. **Assistant工具调用消息**：如果assistant消息中包含 `toolCalls`（即之前助手请求调用工具），需要将这些工具调用请求转换为OpenAI格式的 `ToolCall` 结构。

### sendStreamRequest()

```java
@Override
protected Flux<String> sendStreamRequest(Object apiRequest) {
    String url = baseUrl + ENDPOINT;
    Map<String, String> headers = buildHeaders();

    try {
        String body = objectMapper.writeValueAsString(apiRequest);
        return httpClient.postStream(url, headers, body);
    } catch (JsonProcessingException e) {
        return Flux.error(ModelException.of(ErrorCode.MODEL_INVALID_REQUEST,
                "请求序列化失败: " + e.getMessage()));
    }
}
```

**URL拼接**：`baseUrl + ENDPOINT` → `"https://api.deepseek.com/chat/completions"`

**请求头构建**

```java
private Map<String, String> buildHeaders() {
    Map<String, String> headers = new HashMap<>();
    headers.put("Authorization", "Bearer " + apiKey);
    headers.put("Content-Type", "application/json");
    return headers;
}
```

两个必需的请求头：
- `Authorization: Bearer {apiKey}`：DeepSeek API使用Bearer Token认证
- `Content-Type: application/json`：请求体是JSON格式

**`objectMapper.writeValueAsString(apiRequest)`**：使用Jackson将 `OpenAIRequest` 对象序列化为JSON字符串。如果序列化失败（例如字段类型不匹配），捕获 `JsonProcessingException` 并返回 `Flux.error()`。

**`httpClient.postStream(url, headers, body)`**：调用底层的 `OkHttpModelApiClient` 发送流式POST请求，返回 `Flux<String>`。

### OkHttpModelApiClient的Flux.generate()桥接

让我们回到 `OkHttpModelApiClient.postStream()` 方法，这是阻塞I/O和响应式流之间的桥梁：

```java
@Override
public Flux<String> postStream(String url, Map<String, String> headers, String body) {
    Request request = buildRequest(url, headers, body);

    return Flux.<String, StreamContext>generate(
            () -> {
                // 1. 初始化：创建HTTP请求并获取响应
                Response response = httpClient.newCall(request).execute();
                ResponseBody responseBody = response.body();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(responseBody.byteStream()));
                return new StreamContext(response, responseBody, reader);
            },
            (ctx, sink) -> {
                // 2. 生成数据：每次拉取一行
                String line;
                while ((line = ctx.reader.readLine()) != null) {
                    if (!line.isEmpty()) {
                        sink.next(line);    // 只发射非空行
                        return ctx;         // 返回更新后的状态
                    }
                }
                ctx.close();
                sink.complete();            // 流结束
            },
            StreamContext::close            // 3. 清理：关闭资源
    ).subscribeOn(Schedulers.boundedElastic());
}
```

**`Flux.generate()` 的三参数形式**

`Flux.generate(Callable<S>, BiFunction<S, SynchronousSink<T>, S>, Consumer<S>)` 接收三个参数：

1. **`Callable<S>`（状态初始化）**：创建一个 `StreamContext`，它持有：
   - `Response response`：OkHttp的HTTP响应对象（用于后续关闭）
   - `ResponseBody body`：响应体（用于关闭）
   - `BufferedReader reader`：缓冲字符读取器，从响应体的字节流中逐行读取

2. **`BiFunction<S, SynchronousSink<T>, S>`（生成器函数）**：每次下游请求数据时调用
   - 从 `BufferedReader` 中读取一行
   - 如果行不为空，通过 `sink.next(line)` 发射给下游，然后返回更新后的状态
   - 如果读到null（流结束），关闭资源并调用 `sink.complete()`
   - 如果发生IOException，关闭资源并调用 `sink.error()`

3. **`Consumer<S>`（清理回调）**：当Flux终止（无论正常还是异常）时调用，确保资源被释放

**`StreamContext.close()`**

```java
void close() {
    try { reader.close(); } catch (IOException ignored) {}
    try { body.close(); } catch (Exception ignored) {}
    try { response.close(); } catch (Exception ignored) {}
}
```

三层资源释放，每一层都用try/catch包裹以防止释放失败影响下一层。`Response.close()` 特别重要——它会归还HTTP连接到OkHttp的连接池，不关闭会导致连接泄漏。

**`.subscribeOn(Schedulers.boundedElastic())`**

这个操作符指定了整个 `Flux.generate()` 的订阅和执行都在 `boundedElastic` 调度器上进行。`boundedElastic` 是Reactor专门为阻塞I/O设计的调度器：
- 线程池大小默认是CPU核心数的10倍
- 空闲线程在60秒后被回收
- 队列可以缓存最多100000个任务

### extractSsePlainText() 详细解析

```java
@Override
public String extractSsePlainText(String rawSSE) {
    if (rawSSE == null || rawSSE.isEmpty()) return "";
    StringBuilder text = new StringBuilder();

    for (String line : rawSSE.split("\n")) {
        String json = stripSseDataPrefix(line);
        if (json == null) continue;
        try {
            JsonNode delta = objectMapper.readTree(json)
                    .path("choices").get(0).path("delta");
            JsonNode content = delta.get("content");
            if (content != null && !content.isNull()) {
                text.append(content.asText());
            } else {
                JsonNode reasoning = delta.get("reasoning_content");
                if (reasoning != null && !reasoning.isNull()) {
                    text.append(reasoning.asText());
                }
            }
        } catch (Exception ignored) {}
    }
    return text.toString();
}
```

**输入示例**

`extractSsePlainText()` 接收的参数是一个可能包含多行SSE数据的字符串。典型的输入：

```
data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"你好"}}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"，世界"}}]}

data: [DONE]

```

**处理步骤**

**步骤1：`stripSseDataPrefix(line)`**

```java
private String stripSseDataPrefix(String line) {
    String trimmed = line.trim();
    if (trimmed.isEmpty()) return null;
    if (trimmed.startsWith("data:")) {
        trimmed = trimmed.substring(5).trim();
    }
    if (trimmed.isEmpty() || "[DONE]".equals(trimmed)) return null;
    return trimmed;
}
```

- `.trim()`：去除首尾空白字符
- 如果完全空白 → 返回 `null`（空行被跳过，这在SSE协议中表示事件分隔符）
- 如果以 `"data:"` 开头 → 去掉 `"data:"` 前缀（5个字符）再trim
- 如果去掉前缀后为空或是 `"[DONE]"` → 返回 `null`（`[DONE]` 是OpenAI/DeepSeek SSE流的终止标记）

**步骤2：解析JSON提取delta**

```java
JsonNode delta = objectMapper.readTree(json)
        .path("choices").get(0).path("delta");
```

- `objectMapper.readTree(json)`：将JSON字符串解析为Jackson的 `JsonNode` 树结构
- `.path("choices")`：访问 `choices` 字段。`.path()` 和 `.get()` 的区别是：`.path()` 在字段不存在时返回 `MissingNode`（不会抛异常），而 `.get()` 会返回 `null`。但由于后面调用了 `.get(0)`（这在 `choices` 为null时会抛NPE），这里的 `.path()` 使用实际上没有发挥防御作用——这是一个微小的代码异味。
- `.get(0)`：取choices数组的第一个（也是唯一一个）元素。在流式响应中，每个chunk只包含一个choice。
- `.path("delta")`：访问 `delta` 字段。`delta` 包含这一chunk的增量内容。

**步骤3：提取文本内容**

```java
JsonNode content = delta.get("content");
if (content != null && !content.isNull()) {
    text.append(content.asText());
} else {
    JsonNode reasoning = delta.get("reasoning_content");
    if (reasoning != null && !reasoning.isNull()) {
        text.append(reasoning.asText());
    }
}
```

处理两种内容类型：

1. **`content` 字段**：模型生成的常规文本内容。这是最常见的case——`{"delta":{"content":"你好"}}`。
2. **`reasoning_content` 字段**：DeepSeek V4模型的思考过程。当启用thinking模式时，模型在生成最终回复之前会先生成推理内容（通过 `reasoning_content` 字段流式输出）。

`!content.isNull()` 检查很重要——在JSON中，字段存在但值为 `null`（如 `{"content": null}`）和字段不存在是两种不同情况。Jackson用 `NullNode` 表示前者，`MissingNode`（或 `.get()` 返回 `null`）表示后者。

**`catch (Exception ignored) {}`**

捕获所有解析异常并静默忽略。这可能包括：
- JSON格式错误（API返回了非JSON内容）
- `choices` 数组为空
- 数字格式错误

一个更完善的做法是记录警告日志，但考虑到流式响应的高频率（每秒钟可能数十个chunk），静默忽略是一种权衡。

### extractSseTokenUsage()

```java
@Override
public String extractSseTokenUsage(String rawSSE) {
    String[] lines = rawSSE.split("\n");
    for (int i = lines.length - 1; i >= 0; i--) {
        String json = stripSseDataPrefix(lines[i]);
        if (json == null) continue;
        try {
            JsonNode usage = objectMapper.readTree(json).get("usage");
            if (usage != null) {
                long prompt = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asLong() : 0;
                long completion = usage.has("completion_tokens") ? usage.get("completion_tokens").asLong() : 0;
                long total = usage.has("total_tokens") ? usage.get("total_tokens").asLong() : 0;
                return "prompt=" + prompt + " completion=" + completion + " total=" + total;
            }
        } catch (Exception ignored) {}
    }
    return "prompt=0 completion=0 total=0";
}
```

**倒序查找**：`for (int i = lines.length - 1; i >= 0; i--)`。在OpenAI/DeepSeek的SSE流中，token用量信息只在最后一个chunk中携带（位于 `usage` 字段而非 `choices` 数组内）。从最后一行开始倒序查找更高效——通常第一行就能找到。

**JSON结构**：最后一行的 `usage` 字段格式为：
```json
{"usage": {"prompt_tokens": 150, "completion_tokens": 80, "total_tokens": 230}}
```

### extractSseToolCalls()

```java
@Override
public List<ModelResponse.ToolCallRequest> extractSseToolCalls(String rawSSE) {
    if (rawSSE == null || rawSSE.isEmpty()) return List.of();
    List<ModelResponse.ToolCallRequest> result = new ArrayList<>();

    for (String line : rawSSE.split("\n")) {
        String json = stripSseDataPrefix(line);
        if (json == null) continue;

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode delta = root.path("choices").get(0).path("delta");
            JsonNode tcNode = delta.get("tool_calls");
            if (tcNode == null || !tcNode.isArray()) continue;

            for (JsonNode tc : tcNode) {
                int idx = tc.has("index") ? tc.get("index").asInt() : 0;
                String id = tc.has("id") ? tc.get("id").asText() : null;
                JsonNode func = tc.get("function");
                String name = (func != null && func.has("name")) ? func.get("name").asText() : null;
                String args = (func != null && func.has("arguments")) ? func.get("arguments").asText() : null;

                ModelResponse.ToolCallRequest existing = findOrCreate(result, idx, id, name);
                if (args != null && !args.isEmpty()) {
                    existing.appendArguments(args);
                }
            }
        } catch (Exception ignored) {}
    }
    return result;
}
```

工具调用在SSE流中是**分片段**传输的。一个工具调用可能跨越多个SSE chunk。例如：

```
Chunk 1: delta.tool_calls[0] = {"index":0, "id":"call_123", "function":{"name":"search"}}
Chunk 2: delta.tool_calls[0] = {"index":0, "function":{"arguments":"{\"query\":"}}
Chunk 3: delta.tool_calls[0] = {"index":0, "function":{"arguments":"\"天气\"}"}}
```

这三个chunk的数据需要被合并成一个完整的工具调用请求。

**`findOrCreate()` 方法**

```java
private ModelResponse.ToolCallRequest findOrCreate(
        List<ModelResponse.ToolCallRequest> list,
        int index, String id, String name) {

    for (ModelResponse.ToolCallRequest tcr : list) {
        if (tcr.getIndex() == index) {
            return tcr;
        }
    }
    ModelResponse.ToolCallRequest tcr = ModelResponse.ToolCallRequest.builder()
            .index(index)
            .id(id != null ? id : "")
            .name(name != null ? name : "")
            .arguments("")
            .build();
    list.add(tcr);
    return tcr;
}
```

这个方法实现了"按索引查找或创建"的逻辑：
1. 在已有列表中查找 `index` 匹配的 `ToolCallRequest`
2. 如果找到（说明这是之前某个chunk的延续），直接返回
3. 如果没找到（这是该工具调用的第一个chunk），创建一个新的 `ToolCallRequest`，设置 `id` 和 `name`（只在第一个chunk中出现），`arguments` 初始为空

`ModelResponse.ToolCallRequest` 需要有一个 `appendArguments(String args)` 方法，用于将后续chunk中的arguments片段追加到已有的arguments字符串中。

---

## 5.6 onErrorResume降级

```java
.onErrorResume(err -> {
    log.error("[Orchestrator] LLM call failed: {}", err.getMessage());
    String fallback = buildFinalResponse(sc, fc, toolResults, report);
    return Flux.just(
            sseEvent("message", fallback),
            sseEvent("done", "{\"status\":\"completed\",\"fallback\":true}")
    );
});
```

### Reacor的`.onErrorResume()`

`.onErrorResume()` 是Reactor中用于错误恢复的操作符。当上游的Flux发出错误信号时（不论是 `sink.error()`、抛出的异常、还是其他操作符产生的错误），`.onErrorResume()` 会：
1. 捕获错误信号
2. 调用提供的回调函数
3. 用回调返回的新Publisher替换原来的Publisher，继续向下游发送数据

这与传统的 `try/catch` 不同——传统的 `try/catch` 会让整个响应中断，而 `.onErrorResume()` 允许**无缝切换**到备用数据源。

### 降级策略

当LLM调用失败时（可能的原因：API密钥失效、网络中断、DeepSeek服务宕机、请求被限流等），系统不会让前端看到一个断开的连接或500错误。取而代之的是：

1. **`log.error(...)`**：记录错误日志用于运维排查
2. **`buildFinalResponse(sc, fc, toolResults, report)`**：基于管道阶段的已有结果构建一个硬编码的回复摘要。这个回复包含了工具执行的全部信息——虽然不是LLM生成的，但至少让用户知道发生了什么。
3. **返回备用Flux**：
   - `sseEvent("message", fallback)`：将硬编码回复作为 `message` 事件发出
   - `sseEvent("done", "{\"status\":\"completed\",\"fallback\":true}")`：done事件中 `fallback:true` 标记这是降级回复。前端可以据此展示"LLM服务暂时不可用，以下是工具执行结果"之类的提示。

### 降级对用户体验的影响

降级后的前端体验：
- 用户能看到完整的管道进度事件（5个阶段）
- 用户能看到LLM生成的回复被一个系统生成的摘要替代
- 用户不会看到报错页面或空白对话框
- 高级用户可以展开工具执行结果查看详细数据

这是一种"优雅降级"（Graceful Degradation）的设计理念——在部分功能不可用时，系统仍然尽可能提供有用的信息。

---

## 本章小结

通过这三章的学习，你应该对LyClaw的SSE流式架构有了全面的理解：

- **第三章**：理解了从浏览器到LLM API的完整请求链路，以及SSE事件的全部分类
- **第四章**：深入了 `Flux.create()` 的事件发射机制，逐行分析了五个管道阶段的实现
- **第五章**：掌握了适配器模式、模板方法模式在LLM调用中的应用，以及 `Flux.generate()` 桥接阻塞I/O的技术

这些知识不仅适用于LyClaw项目，也可以应用到任何需要SSE流式响应的Java WebFlux项目中。
