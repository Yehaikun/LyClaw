# LyClaw 项目 SSE 流式输出详解

> 从 SSE 协议基础到 LyClaw 全链路实现的完整教学文档

---

## 目录

### 第一部分：基础知识

- **第一章 SSE 协议基础**
  - 1.1 SSE 协议概述（定义、历史、与 WebSocket 对比）
  - 1.2 HTTP 层面的 SSE 约定（Content-Type、缓存控制、分块传输）
  - 1.3 SSE 报文格式实例剖析（字符级拆解、常见反模式）

- **第二章 Java / Spring 生态中的响应式流**
  - 2.1 Reactor 核心概念速览（Mono、Flux、背压、冷热流）
  - 2.2 Spring WebFlux 的 SSE 支持（ServerSentEvent、produces）
  - 2.3 关键 Reactor 操作符深度解析（create、defer、generate、concatWith…）
  - 2.4 调度器（Schedulers）专题（boundedElastic、线程隔离）

### 第二部分：LyClaw 架构与实现

- **第三章 LyClaw SSE 架构总览**
  - 3.1 请求链路全景图（7 跳路径）
  - 3.2 SSE 事件类型完整分类（15 种事件）
  - 3.3 两段式 Flux 拼接架构（pipelineFlux + respondFlux）
  - 3.4 非流式降级方案

- **第四章 管道阶段：Flux.create() 事件发射器详解**
  - 4.1 execute() 方法入口剖析
  - 4.2 Flux.create() 的 sink 机制
  - 4.3 五阶段详解（CONTEXT_BUILD → INTERCEPT → PLAN → EXECUTE → REFLECT）
  - 4.4 sseEvent() 辅助方法
  - 4.5 异常处理与错误传播

- **第五章 LLM 流式响应：adapter.chatStream() 与 SSE 解析**
  - 5.1 respondFlux 的构建
  - 5.2 buildTailFlux() — 收尾事件流
  - 5.3 ModelAdapter 接口设计
  - 5.4 AbstractModelAdapter 模板方法模式
  - 5.5 DeepSeekOpenAIAdapter 实现
  - 5.6 onErrorResume 降级

- **第六章 底层 HTTP 客户端：OkHttp 与 Flux.generate() 的桥接**
  - 6.1 为什么需要桥接
  - 6.2 postStream() 方法逐行拆解
  - 6.3 StreamContext 内部类
  - 6.4 Flux.generate() vs Flux.create() 对比
  - 6.5 subscribeOn(boundedElastic) 的深入理解
  - 6.6 超时配置
  - 6.7 完整 SSE 数据流示例

- **第七章 ToolCallLoopStage：二次 SSE 路径与工具调用循环**
  - 7.1 在管道中的位置
  - 7.2 Sinks.Many 实时事件广播
  - 7.3 流式模式循环
  - 7.4 工具调用执行
  - 7.5 buildToolEventFlux()
  - 7.6 边界情况与安全保护

### 第三部分：前端与基础设施

- **第八章 前端 SSE 消费与渲染**
  - 8.1 为什么不用原生 EventSource
  - 8.2 postSSE() — Fetch API 流式消费核心实现
  - 8.3 chatStore — Pinia 状态管理
  - 8.4 ChatView.vue — 流式 UI 渲染
  - 8.5 MessageBubble.vue — 流式光标
  - 8.6 前端 SSE 完整数据流总结

- **第九章 基础设施与全链路超时**
  - 9.1 Spring Cloud Gateway 配置
  - 9.2 Vite 开发代理
  - 9.3 全链路超时对齐表
  - 9.4 错误处理全链路
  - 9.5 资源泄漏防护
  - 9.6 生产环境部署检查清单

### 附录

- 附录 A — LyClaw SSE 事件类型速查表
- 附录 B — curl 测试 SSE 命令
- 附录 C — 关键文件索引
- 附录 D — SSE 协议关键规范速查
- 附录 E — 常见问题排查
- 附录 F — 文件版本信息

---


## 第1章 SSE 协议基础

### 1.1 SSE 协议概述

#### 什么是SSE？

SSE（Server-Sent Events，服务器推送事件）是一种允许服务器主动向浏览器推送数据的
技术。想象一下：你在餐厅点了一杯咖啡，传统HTTP就像你每隔五秒问服务员"咖啡好了
吗？"（轮询），而SSE就像服务员主动过来告诉你"咖啡正在做"、"咖啡马上好"、"请享用"
（服务器推送）。

SSE 构建在 HTTP 协议之上，它不是一个全新的网络协议，而是 HTTP 的一种**使用模式**。
服务器响应头的 `Content-Type: text/event-stream` 告诉客户端："接下来的数据是以事件流
形式到达的，连接不会立即关闭，请持续监听。"

```
┌─────────────┐                         ┌─────────────┐
│   浏览器     │  HTTP GET /events       │   服务器     │
│  (客户端)    │ ──────────────────────> │  (Nginx/    │
│             │                         │  Spring)    │
│             │  HTTP 200 OK            │             │
│             │  Content-Type:          │             │
│             │   text/event-stream     │             │
│             │ <────────────────────── │             │
│             │                         │             │
│             │  data: 当前时间 10:00   │             │
│             │ <────────────────────── │             │
│             │                         │             │
│             │  data: 当前时间 10:01   │             │
│             │ <────────────────────── │             │
│             │                         │             │
│             │  event: alert           │             │
│             │  data: 服务器告警！     │             │
│             │ <────────────────────── │             │
│             │         ...             │             │
│             │        连接保持         │             │
└─────────────┘                         └─────────────┘
```

#### 历史与背景

SSE 的历史可以追溯到2004年。当时 Web 开发者想做实时更新只能用轮询（每隔几秒发一个
请求），或者依赖非标准的 "Comet" 技术（Ajax长轮询，服务器收到请求后不立即返回，
等到有数据时才返回，客户端收到后又立即发起下一个请求）。

2006年，Opera 浏览器的开发者 Ian Hickson 提出了一个名为 `server-sent DOM events` 的
方案——让服务器可以向浏览器推送 DOM 事件。这个方案的具体做法是：服务器返回一个名为
`application/x-dom-event-stream` 的自定义内容类型，浏览器解析后生成对应的 DOM 事件。

这个提案经过 W3C 的讨论和标准化，最终并入 HTML5 规范中，成为今天我们熟知的
**Server-Sent Events**。2012年，SSE 正式成为 W3C 推荐标准。2014年，HTML5 成为
W3C 推荐标准，SSE 作为其一部分也获得正式地位。

**关键时间线：**

```
2004  ─  Ajax 轮询成为主流实时方案
2006  ─  Opera 提出 server-sent DOM events 草案
2008  ─  HTML5 工作组接纳该提案，重命名为 Server-Sent Events
2009  ─  Chrome 4 率先实现 SSE
2011  ─  Firefox 6、Opera 11 支持 SSE，EventSource API 趋于稳定
2012  ─  W3C 将 SSE 发布为独立推荐标准
2014  ─  HTML5 正式推荐标准发布，SSE 作为一部分
2024  ─  SSE 被广泛用于 LLM 流式输出（ChatGPT、Claude 等）
```

#### SSE vs WebSocket 对比

这是初学者最喜欢问的问题："SSE 和 WebSocket 有什么区别？我该用哪个？"

```
                SSE                         WebSocket
┌───────────────────────────┐    ┌───────────────────────────┐
│  基于 HTTP                │    │  独立协议 (ws:// wss://)  │
│  单向：服务器→客户端      │    │  双向：客户端↔服务器      │
│  自动重连机制             │    │  需手动实现重连           │
│  纯文本数据               │    │  文本和二进制数据         │
│  EventSource API (简单)   │    │  WebSocket API (较复杂)    │
│  仅浏览器原生支持         │    │  浏览器和服务端都原生     │
│  Content-Type 协商        │    │  Upgrade 握手升级协议     │
│  复用 HTTP 端口           │    │  可复用或独立端口         │
│  仅支持 GET 请求          │    │  无方法限制               │
└───────────────────────────┘    └───────────────────────────┘
```

**详细对比表：**

| 维度           | SSE                          | WebSocket                        |
| -------------- | ---------------------------- | -------------------------------- |
| 协议层         | HTTP/1.1 或 HTTP/2           | 独立 WebSocket 协议 (RFC 6455)    |
| 传输方向       | 单向（服务器→客户端）        | 全双工（双向）                   |
| 浏览器 API     | `EventSource` 对象           | `WebSocket` 对象                 |
| 自动重连       | 内置（可配置 `retry` 字段）  | 需手动实现                       |
| 数据格式       | UTF-8 纯文本                 | 文本帧和二进制帧                 |
| 连接建立       | 普通 HTTP GET，响应流不关闭  | HTTP Upgrade 握手后切换协议       |
| 心跳/保活      | 可发送 `:` 开头的注释行      | WebSocket Ping/Pong 帧            |
| Last-Event-ID  | 协议内置断线恢复机制         | 需应用层自行实现                 |
| 实现复杂度     | 低（几行代码）               | 中等（需处理帧、分片、控制帧）    |
| 代理/防火墙    | 完全兼容 HTTP 代理           | 部分代理可能需要配置             |
| HTTP/2 优势    | 多路复用，同一连接多个流     | 每个 WebSocket 独立 TCP 连接     |
| 使用场景       | 实时通知、股票价格、LLM流式  | 聊天、游戏、协同编辑             |

**选择SSE的场景：**
- AI 大模型流式输出（ChatGPT 的 "打字机效果"）
- 实时通知、消息推送
- 监控仪表盘、日志流
- 股票价格推送
- 社交媒体动态更新
- 任何"服务器告诉你发生什么但不需要你往回说"的场景

**选择WebSocket的场景：**
- 在线聊天室（需要双向消息）
- 多人在线游戏（低延迟双向通信）
- 协同编辑（Google Docs 风格）
- 视频会议信令
- 远程控制/终端

**LyClaw 为什么选择 SSE？**
LyClaw 的核心场景是 AI 对话的流式输出——用户的请求通过 HTTP POST 发过来，服务器
调用大模型 API（如 OpenAI、Claude），模型逐 token 返回结果，服务器将这些 token 实时
推送给前端，形成"逐字显示"的效果。这是一个典型的"单向推送"场景，SSE 是最自然的
选择。同时 SSE 复用 HTTP 基础设施，不需要额外的协议升级或端口配置。

#### SSE 协议核心概念深度解析

SSE 协议的数据格式非常简洁，由四个字段组成，每个事件以空行分隔。

```
SSE 流的结构（在 HTTP 响应体中）：

event: 事件类型（可选）
id: 事件ID（可选）
data: 事件数据（可以有多个）
retry: 重连间隔毫秒（可选）
[空行]  ← 事件结束标记

event: 事件类型
data: 事件数据
[空行]  ← 又一个事件结束

... 持续
```

注意：字段顺序不重要，但通常习惯先写 `event:` 再写 `data:`。

##### `event:` 字段 —— 事件类型标识符

`event:` 字段为事件命名。客户端可以通过这个名称区分不同类型的事件。

- 如果不写 `event:` 字段，客户端会触发默认的 `message` 事件
- 如果写了 `event: custom_type`，客户端会触发 `custom_type` 事件

**LyClaw 项目中的实际例子：**

在 `OrchestratorImpl.java` 中，编排器发送多种类型的 SSE 事件：

```java
// 编排阶段事件
sink.next(sseEvent("context_build_start", "Loading session and retrieving memories"));
sink.next(sseEvent("context_build_complete", "Loaded session, retrieved N memory entries"));
sink.next(sseEvent("intercept_start", "Running security checks and content filter"));
sink.next(sseEvent("plan_start", "Planning task decomposition"));
sink.next(sseEvent("plan_complete", "Planned N task(s)"));

// 执行阶段事件
sink.next(sseEvent("action_start", "{...}"));   // JSON 格式的详细信息
sink.next(sseEvent("action_result", "{...}"));  // 执行结果

// LLM 响应流
sink.next(sseEvent("message", text));  // LLM 输出的文本块

// 终止事件
sink.next(sseEvent("done", "{\"status\":\"completed\",...}"));
```

对应的线上 SSE 流（前端看到的）：

```
event: context_build_start
data: Loading session and retrieving memories

event: context_build_complete
data: Loaded session, retrieved 10 memory entries

event: plan_start
data: Planning task decomposition

event: message
data: 您好！根据您的需求

event: message
data: ，我为您制定了以下计划…

event: done
data: {"status":"completed","durationMs":4521}

```

前端 JavaScript 处理：

```javascript
const evtSource = new EventSource("/api/chat/stream");

// 监听特定事件类型
evtSource.addEventListener("message", (e) => {
    // LLM 文本块，追加到聊天界面
    chatBox.append(e.data);
});

evtSource.addEventListener("plan_start", (e) => {
    // 显示"正在规划…"
    showStatus("正在规划…");
});

evtSource.addEventListener("plan_complete", (e) => {
    // 显示规划结果
    showStatus("规划完成: " + e.data);
});

evtSource.addEventListener("done", (e) => {
    // 流程结束
    const result = JSON.parse(e.data);
    console.log("完成，耗时: " + result.durationMs + "ms");
    evtSource.close();
});

evtSource.addEventListener("error", (e) => {
    // 错误处理
    console.error("SSE 错误:", e.data);
});
```

**重要：** `event:` 字段的值是大小写敏感的。`event: Message` 和 `event: message`
是两个不同的类型。约定俗成使用全小写加下划线的 snake_case 风格。

##### `data:` 字段 —— 数据负载

`data:` 字段是 SSE 事件的真正负载（payload）。几乎每个事件都有一个或多个 `data:` 行。

**规则：**

1. 一个事件可以有多个 `data:` 行，客户端会将它们拼接起来（每行用换行符连接）
2. `data:` 后面的空格是可选的，但推荐保留一个空格以提高可读性
3. 数据中不能有空行（空行表示事件结束），如果确实需要空行，应该用 `data:` 前缀

**单行数据：**

```
event: message
data: Hello, world!

```

客户端收到：`Hello, world!`

**多行数据：**

```
event: message
data: 第一行
data: 第二行
data: 第三行

```

客户端收到（拼接后，行间用 `\n` 连接）：

```
第一行
第二行
第三行
```

**JSON 数据（LyClaw 中的常见模式）：**

```
event: action_result
data: {"index":1,"status":"success","output":"文件已创建","durationMs":234}

```

客户端收到的是一个 JSON 字符串，需要 `JSON.parse(e.data)` 来解析。

**数据中包含特殊字符：**
SSE 数据只支持 UTF-8。如果数据包含换行符，必须使用多行 `data:` 格式。
如果数据中包含 `:` 开头的行（可能被误解析为字段），应该放在 `data:` 后面。

##### `id:` 字段 —— 事件ID与断线重连

`id:` 字段为事件分配一个唯一标识符。它的核心作用是支持**断线重连后的增量恢复**。

**工作原理：**

1. 服务器发送事件时带上 `id: 123`
2. 浏览器记录下最后收到的 ID
3. 如果连接断开，浏览器自动重连，并在新的 HTTP 请求中带上 HTTP header：
   `Last-Event-ID: 123`
4. 服务器读到这个 header，就知道"客户端最后收到 ID 123，我应该从 124 开始发送"

```
客户端                          服务器
   |                              |
   | ─── GET /events ──────────> |
   |                              |
   | <── id: 1                    |
   | <── data: 消息1              |
   | <──                          |
   |   (浏览器记录 lastId=1)      |
   |                              |
   | <── id: 2                    |
   | <── data: 消息2              |
   | <──                          |
   |   (浏览器记录 lastId=2)      |
   |                              |
   |  ~~~ 网络断开 ~~~            |
   |                              |
   | ─── GET /events ──────────> |  ← 自动重连
   |    Last-Event-ID: 2          |  带着最后的ID
   |                              |
   | <── id: 3                    |  从3开始发送
   | <── data: 消息3              |  （跳过了已收到的1、2）
   | <──                          |

```

**LyClaw 中的考虑：**
在 LyClaw 的聊天流式场景中，`id` 字段较少使用，因为：
- LLM 流式对话是一次性的（每轮对话创建新的 SSE 连接）
- 如果断线，重新发起完整的请求比恢复增量更合理
- 但在长时间运行的编排流水线（如多个 agent 任务执行），`id` 可以帮助前端在断线后
  继续显示之前未收到的进度事件

```java
// 在 LyClaw 中使用 id 的示例
sseEvent("action_progress", "Task 3/10 completed", 37)
// 格式化为 SSE:
// id: 37
// event: action_progress
// data: Task 3/10 completed
```

`ServerSentEvent.builder()` 支持 `.id("37")` 方法设置事件 ID。

##### `retry:` 字段 —— 客户端重连间隔

`retry:` 字段告诉浏览器：如果连接断开，应该等待多少毫秒后再尝试重连。

- 默认值：浏览器通常使用 3-5 秒
- 只影响后续的重连行为，不是立即生效
- 整个 SSE 流中只需要发送一次（通常放在流的开头）

```
retry: 3000   ← 告诉浏览器：掉线后 3 秒重连

event: message
data: 开始推送…

```

**LyClaw 中的应用：**
LyClaw 的 SSE 端点是 per-request 的（一个 POST 对应一个 SSE 流），所以 `retry` 实际
意义有限——流结束后连接就关闭了。但在下面场景有用：

```java
// 编排流水线开始前，设置合理的重连间隔
Flux.just(
    ServerSentEvent.<String>builder()
        .retry(Duration.ofMillis(10000))  // 10秒重连
        .build(),  // 纯 retry 事件，无 data
    sseEvent("pipeline_start", "Starting orchestration")
)
```

如果是长时间运行的监控流（如系统状态推送），`retry` 就非常关键。

##### 空行作为事件分隔符

这是整个 SSE 协议中最容易被忽略但又最重要的机制。SSE 以**两个连续的换行符**（即一个
空行）作为事件的结束标记。

```
LF = 换行符 \n (0x0A)
CR = 回车符 \r (0x0D)
CRLF = \r\n (0x0D 0x0A)

事件结束标记可以是以下任一种：
- \n\n       (两个 LF)
- \r\n\r\n   (两个 CRLF)
- \r\r       (两个 CR，不推荐)
```

在字节层面上，一个完整的 SSE 事件在网络上传输的样子：

```
十六进制转储（一个简单事件 "data: hello\n\n"）：

64 61 74 61  3A 20 68 65  6C 6C 6F 0A  0A
d  a  t  a   :     h  e   l  l  o  \n  \n

以 \n\n 结尾 — 这告诉 SSE 解析器：这个事件写完了。
```

**常见错误：**

错误1——只写了一个换行符：

```
data: hello
```
这是一个不完整的事件，解析器不会触发通知。

错误2——数据内容中有空行：

```
data: A
       ← 这里有一个真正的空行，会被误认为是事件结束
data: B
```
正确做法：

```
data: A
data:
data: B
```

错误3——在事件中间的空行：

```
event: typo_event

data: 这个事件永远不会触发
```
第一行的 `event: typo_event` 后面立即跟了一个空行，这是一个没有任何 `data:` 字段的
空事件。浏览器会触发一个 `message` 事件（因为没有 `event:`），且 `e.data` 为空字符串。
然后第二块才会被解析为事件 `typo_event`。

##### `[DONE]` 约定 —— 标记流结束

`[DONE]` 不是 SSE 协议的一部分，而是 OpenAI 在自己的 SSE 实现中创造的约定。当大模型
流式输出完成时，OpenAI 的 API 发送：

```
data: [DONE]

```

客户端检测到 `e.data === "[DONE]"` 就知道流结束了。

这个约定被广泛采纳，成了 LLM SSE 流的事实标准。许多 LLM API 提供商（包括 Anthropic、
Ollama 等）都使用或者兼容这个标记。

**LyClaw 中的等价做法：**

LyClaw 没有使用 `[DONE]` 约定，而是采用了一个更语义化的方式——发送一个 `event: done`
的 SSE 事件：

```java
// 在 OrchestratorImpl.java 的 buildTailFlux() 中
sseEvent("done",
    "{\"status\":\"completed\",\"durationMs\":" + totalDuration + "}")
```

这生成的 SSE 格式是：

```
event: done
data: {"status":"completed","durationMs":4521}

```

前端通过监听 `done` 事件来关闭 EventSource：

```javascript
evtSource.addEventListener("done", (e) => {
    evtSource.close();
    finalizeChat();
});
```

为什么不用 `[DONE]`？主要原因：

1. **语义更清晰：** `event: done` 明确告诉前端"流程结束"，而不是把一个特殊的字符串
   塞在 `data` 里让前端去判断
2. **可以有结构化数据：** `done` 事件可以携带 JSON 格式的元数据（耗时、状态等），
   而 `[DONE]` 约定只能携带一个固定字符串
3. **类型系统友好：** 不同事件类型映射到不同的处理器，而不是在一个 message handler
   里做字符串匹配

---

### 1.2 HTTP层面的SSE约定

SSE 不是凭空存在的，它是 HTTP 的"一种用法"。下面深入剖析 SSE 在 HTTP 层面的具体约定。

#### `Content-Type: text/event-stream`

这是 SSE 最核心的 HTTP 约定。服务器必须在响应头中设置：

```
Content-Type: text/event-stream
```

**为什么必须是这个 MIME 类型？**

当浏览器收到 `Content-Type: text/event-stream` 时，内部的 SSE 解析器才会被激活。如果
Content-Type 是其他值（比如 `text/plain`、`application/json`），浏览器会把响应当作
普通的 HTTP 响应处理，不会触发 `EventSource` 的事件监听。

可以把这个 MIME 类型理解为一个"协议开关"：

```
发送 Content-Type: text/event-stream
       ↓
浏览器检测到这个 MIME 类型
       ↓
激活 SSE 解析器（解析 event: data: id: retry: 字段）
       ↓
生成对应的 DOM 事件（EventSource.onmessage 等）
```

**服务器端设置（Spring WebFlux）：**

```java
@PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
    // Spring 框架自动处理 Content-Type 设置
    return orchestrator.execute(context);
}
```

`MediaType.TEXT_EVENT_STREAM_VALUE` 是一个字符串常量，值为 `"text/event-stream"`。

**注意：** 如果使用 `curl` 测试 SSE 端点，可以看到响应头：

```bash
$ curl -N -X POST http://localhost:8080/api/chat/stream \
    -H "Content-Type: application/json" \
    -d '{"sessionId":"test","messages":[{"role":"user","content":"你好"}],"stream":true}'

HTTP/1.1 200 OK
Content-Type: text/event-stream        ← 关键头
Cache-Control: no-cache                ← 禁用缓存
Connection: keep-alive                 ← 保持连接
Transfer-Encoding: chunked             ← 分块传输

event: context_build_start
data: Loading session and retrieving memories

...
```

#### `Cache-Control: no-cache`

SSE 流必须禁用缓存。原因是 SSE 的数据是实时生成的，浏览器应该看到的永远是最新的
事件，而不是缓存中的过时数据。

**为什么用 `no-cache` 而不是 `no-store`？**

- `Cache-Control: no-cache` — 告诉缓存"可以存，但每次用之前必须问服务器确认"，这允许
  HTTP/2 的服务器推送缓存优化
- `Cache-Control: no-store` — 告诉缓存"完全不存"，更严格
- 对于 SSE，`no-cache` 已经足够，因为数据是持续推送的，永远不会被"确认有效"

在 Spring WebFlux 中，框架自动设置这些缓存头。但如果需要手动控制，可以添加一个
`WebFilter`。

**Nginx 代理场景：**

如果 SSE 请求经过 Nginx 反向代理，需要特别注意 Nginx 的缓冲行为。Nginx 默认会对
响应做缓冲（等积累一定量数据后才发送给客户端），这会完全破坏 SSE 的实时性。

```nginx
location /api/chat/stream {
    proxy_pass http://backend:8080;
    proxy_buffering off;                  # 关键：关闭缓冲
    proxy_cache off;                      # 禁用缓存
    proxy_set_header Connection '';       # 支持 keep-alive
    proxy_http_version 1.1;              # 支持分块传输
    chunked_transfer_encoding on;         # 启用分块传输
}
```

#### `Accept: text/event-stream`

客户端发起 SSE 连接时，应该在请求头中声明期望的响应类型：

```http
GET /api/events HTTP/1.1
Accept: text/event-stream
```

不过，浏览器的 `EventSource` API 会自动带上这个头，开发者不需要手动设置。
对于 `POST` 方式的 SSE（如 LyClaw 的 `/api/chat/stream`），前端通常使用
`fetch()` + `ReadableStream` 而不是 `EventSource`（因为 `EventSource` 只支持 GET）。

```javascript
// fetch-based SSE 客户端（支持 POST）
async function streamChat(request) {
    const response = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request)
    });

    const reader = response.body.getReader();
    const decoder = new TextDecoder();

    while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        const chunk = decoder.decode(value, { stream: true });
        // 手动解析 SSE 格式
        parseSSEChunk(chunk);
    }
}
```

#### 长连接与分块传输编码

SSE 的核心特征之一是**HTTP 连接不关闭**。普通 HTTP 请求/响应是一次性的：

```
普通 HTTP：
客户端 → 请求 → 服务器
客户端 ← 响应 ← 服务器
[连接关闭]

SSE 下的 HTTP：
客户端 → 请求 → 服务器
客户端 ← 响应头 ← 服务器
客户端 ← 数据块1 ← 服务器
客户端 ← 数据块2 ← 服务器
客户端 ← 数据块3 ← 服务器
...（连接保持打开）
客户端 ← 数据块N ← 服务器
[服务器关闭连接 或 客户端断开]

```

**Chunked Transfer Encoding（分块传输编码）：**

HTTP/1.1 中，如果服务器不知道响应体总共有多大（SSE 就是这种情况——谁也不知道会
推送多少事件），服务器会使用 `Transfer-Encoding: chunked` 发送数据。

分块传输的格式：

```
HTTP/1.1 200 OK
Content-Type: text/event-stream
Transfer-Encoding: chunked

6E\r\n                               ← 十六进制块大小（110字节）
event: message\r\ndata: Hello!\r\n\r\n
event: message\r\ndata: World!\r\n\r\n
0\r\n                                ← 结束标记
\r\n

```

每个块前面有一个十六进制的大小标识，0 表示结束。

**HTTP/2 的改进：**

在 HTTP/2 中，分块传输编码被更高效的**数据帧（DATA frame）** 机制取代。HTTP/2 天然
支持多路复用——一个 TCP 连接上可以同时承载多个 SSE 流，大幅提升了连接利用率。

```
HTTP/1.1 SSE                     HTTP/2 SSE
┌──────────┐                    ┌──────────┐
│ 连接1:   │                    │ 单 TCP   │
│ SSE流1   │                    │ 连接:    │
├──────────┤                    │ ┌──────┐ │
│ 连接2:   │  ← 两个连接        │ │ 流1  │ │
│ SSE流2   │                     │ ├──────┤ │
└──────────┘                    │ │ 流2  │ │ ← 两个流
                                │ └──────┘ │
                                └──────────┘
```

#### 连接超时与保活策略

长连接面临的最大敌人是**超时**——代理服务器、负载均衡器、防火墙都可能设置空闲超时
（通常在 60 秒到 5 分钟之间）。如果一个 SSE 流长时间没有数据，这些中间设备就会关闭
连接。

**解决方案：SSE 注释行（Comment）**

SSE 协议支持以 `:` 开头的注释行。注释行不触发任何事件，纯粹用于保活：

```
: heartbeat   ← 以冒号开头，后面可以跟任意文字

```

**LyClaw 中实现保活的方式：**

在 LyClaw 的编排流水线中，每个阶段（Stage 1-7）都在持续推送事件，所以通常
不会有长时间无数据的情况。但如果在阶段之间（例如 LLM 生成间隔过长），可以
插入保活注释。Spring WebFlux 中的实现方法：

```java
Flux<ServerSentEvent<String>> pipelineWithHeartbeat = pipelineFlux
    .mergeWith(Flux.interval(Duration.ofSeconds(15))
        .map(tick -> ServerSentEvent.<String>builder()
            .comment("heartbeat")  // 设置 SSE 注释
            .build()));
```

`ServerSentEvent.builder().comment("heartbeat").build()` 生成的 SSE 输出：

```
: heartbeat

```

**Nginx 侧的保活配置：**

```nginx
location /api/ {
    proxy_read_timeout 600s;  # 10 分钟无数据才断开
    proxy_send_timeout 600s;
}
```

**Spring Boot 侧的连接超时：**

```yaml
# application.yml
server:
  tomcat:
    connection-timeout: 300000  # 5分钟
spring:
  webflux:
    # WebFlux 默认无超时，但可以通过 Scheduler 控制
```

#### HTTP/1.1 vs HTTP/2 下的 SSE

##### HTTP/1.1 下的 SSE

在 HTTP/1.1 中，一个 SSE 连接占用一个 TCP 连接。这是 HTTP/1.1 的队头阻塞（Head-of-Line
Blocking）问题在 SSE 场景下的体现：

```
浏览器                              服务器
  │                                  │
  │── TCP 连接1 ──────────────────> │  (SSE 流1)
  │   GET /events/stream1            │
  │<── 事件流1 ─────────────────── │
  │                                  │
  │── TCP 连接2 ──────────────────> │  (SSE 流2)
  │   GET /events/stream2            │
  │<── 事件流2 ─────────────────── │
  │                                  │
```

HTTP/1.1 规范限制了同域名下的并发连接数（通常是 6 个），如果页面同时打开了 6 个
SSE 流，第 7 个请求就需要等前面的连接释放。

##### HTTP/2 下的 SSE

HTTP/2 通过**多路复用**解决了这个问题。一个 TCP 连接可以承载多个 SSE 流：

```
浏览器                              服务器
  │                                  │
  │══ 单 TCP 连接 ═══════════════> │
  │   ├─ Stream 1: GET /events/1     │
  │   ├─ Stream 3: GET /events/2     │
  │   ├─ Stream 5: GET /events/3     │
  │   └─ ...                         │
  │                                  │
  │<══ 响应在各自的流中返回 ═══════ │
```

HTTP/2 的另一个好处是**服务器推送（Server Push）**。但在 SSE 场景中，服务器推送
很少使用，因为 SSE 本身就是流式推送。

**实用建议：**
- 如果项目使用 HTTP/2（现代 Spring Boot 通常开启），多个 SSE 流可以共享连接，
  不用担心连接数限制
- 但对于每个单独的 SSE 流，客户端最多同时保持 2-3 个（浏览器是有限制的）
- LyClaw 的 SSE 流是**短生命周期**的（一次对话结束就关闭），所以连接数压力不大

---

### 1.3 SSE报文格式实例剖析

#### 完整SSE流示例逐字符解析

让我们通过一个真实的 LyClaw SSE 流来逐字符分析：

**场景：** 用户发送"帮我创建一个Python脚本"，LyClaw 编排器执行流水线并返回 LLM 响应。

**原始字节流（十六进制 + ASCII）：**

```
00000000  65 76 65 6E 74 3A 20 63  6F 6E 74 65 78 74 5F 62  |event: context_b|
00000010  75 69 6C 64 5F 73 74 61  72 74 0A 64 61 74 61 3A  |uild_start.data:|
00000020  20 4C 6F 61 64 69 6E 67  20 73 65 73 73 69 6F 6E  | Loading session|
00000030  20 61 6E 64 20 72 65 74  72 69 65 76 69 6E 67 20  | and retrieving |
00000040  6D 65 6D 6F 72 69 65 73  0A 0A                    |memories..      |
```

解释（. 表示不可打印字符，这里都是可打印的）：

```
字节序列                    ASCII 文本            说明
────────────────────────────────────────────────────────────────
65 76 65 6E 74 3A 20       event:               字段名
63 6F 6E 74 65 78 74       context_build_start  事件类型
5F 62 75 69 6C 64 5F
73 74 61 72 74
0A                         \n                   第一行结束
                          ─────────────────────
64 61 74 61 3A 20          data:                字段名
4C 6F 61 64 69 6E 67 20   Loading              payload 内容
73 65 73 73 69 6F 6E 20   session
61 6E 64 20 72 65 74 72   and retr
69 65 76 69 6E 67 20       ieving
6D 65 6D 6F 72 69 65 73   memories
0A                         \n                   第二行结束
0A                         \n                   空行 = 事件结束
```

**完整解析：**
1. 第一行：`event: context_build_start\n` → 声明事件类型
2. 第二行：`data: Loading session and retrieving memories\n` → 数据负载
3. 第三行：`\n` → 空行，标记事件结束

浏览器触发事件：
```javascript
// event.type === "context_build_start"
// event.data === "Loading session and retrieving memories"
```

#### 复杂事件示例

以下是 LyClaw 编排流水线中一个包含 JSON 数据的 `action_result` 事件：

**SSE 格式文本：**
```
event: action_result
data: {"index":1,"status":"success","output":"文件 /tmp/script.py 已创建","durationMs":234}

```

**字节级分析（每行一个 CRLF 即 \r\n）：**
```
Hex dump                                                ASCII
────────────────────────────────────────────────────────────────
65 76 65 6E 74 3A 20                                    event:
61 63 74 69 6F 6E 5F 72  65 73 75 6C  74              action_result
0D 0A                                                   \r\n

64 61 74 61 3A 20                                       data:
7B 22 69 6E 64 65 78 22  3A 31 2C                       {"index":1,
22 73 74 61 74 75 73 22  3A 22 73  75 63 63 65 73      "status":"succes
73 22 2C 22 6F 75 74 70  75 74 22  3A 22 E6 96 87      s","output":"文
E4 BB B6 20 2F 74 6D 70  2F 73 63  72 69 70 74         件 /tmp/script
2E 70 79 20 E5 B7 B2 E5  88 9B E5  BB BA 22 2C         .py 已创建",
22 64 75 72 61 74 69 6F  6E 4D 73  22 3A 32 33 34      "durationMs":234
7D                                                      }
0D 0A                                                   \r\n

0D 0A                                                   \r\n (空行)
```

注意中文 UTF-8 编码（E6 96 87 = 文, E4 BB B6 = 件），SSE 只支持 UTF-8。

#### 多行数据示例

当数据包含换行符时，必须使用多个 `data:` 行：

**Java 代码（模拟 LLM 返回带换行的内容）：**
```java
String multiLineText = "这是第一段代码：\n    print('hello')\n这段代码很简单。";
sink.next(ServerSentEvent.<String>builder()
    .event("message")
    .data(multiLineText)
    .build());
```

Spring 的 `ServerSentEventHttpMessageWriter` 会自动把嵌入的 `\n` 转换为多行 `data:`：

**实际线缆数据：**
```
event: message
data: 这是第一段代码：
data:     print('hello')
data: 这段代码很简单。

```

客户端收到时，Spring 的序列化器会正确处理：每行 `data:` 后面追加一个 `\n`，最终
拼接成完整的文本（`data: 第一行\ndata: 第二行\n` → `第一行\n第二行`）。

#### SSE实现中的常见反模式（Anti-patterns）

**反模式1：在 data 中手动拼接换行而不是用多行 data**

```java
// 错误 —— 这会导致 SSE 解析错误
// 因为 data 内部的 \n 可能与事件分隔符混淆
sink.next(sseEvent("message", "行1\n行2\n行3"));
```

```java
// 正确 —— 依赖 Spring 的 ServerSentEvent 自动转换
ServerSentEvent.<String>builder()
    .event("message")
    .data("行1\n行2\n行3")  // Spring 会自动处理
    .build();
```

**反模式2：忘记设置 Content-Type**

```java
// 错误 —— Content-Type 默认是 application/json
@PostMapping("/chat/stream")
public Flux<String> chatStream(@RequestBody ChatRequest request) {
    return orchestrator.execute(context); // 前端收不到 SSE 事件
}
```

```java
// 正确 —— 显式声明 produces
@PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
    return orchestrator.execute(context);
}
```

**反模式3：在事件之间忘记空行**

如果手动构造 SSE 字符串：

```java
// 错误 —— 没有结束标记
String sse = "event: message\ndata: hello\n"  // 缺少结尾的 \n
```

```java
// 正确
String sse = "event: message\ndata: hello\n\n"
```

使用 `ServerSentEvent.builder()` 可以完全避免这个问题，因为序列化器会自动添加
正确的换行和空行。

**反模式4：阻塞SSE发射线程**

这是后端最严重的性能问题：

```java
// 错误 —— 在 SSE 发射流程中调用阻塞方法
public Flux<ServerSentEvent<String>> execute(ChatContext ctx) {
    return Flux.create(sink -> {
        String result = restTemplate.postForObject(...);  // 阻塞！
        sink.next(sseEvent("result", result));
        sink.complete();
    });
}
```

这种写法会阻塞 Reactor 的事件循环线程（通常是 `reactor-http-nio-*` 线程），导致
所有并发的 SSE 连接都被这个阻塞调用拖慢。

```java
// 正确 —— 使用 subscribeOn 将阻塞工作移到专用线程池
public Flux<ServerSentEvent<String>> execute(ChatContext ctx) {
    return Flux.create(sink -> {
        String result = restTemplate.postForObject(...);  // 在 boundedElastic 线程中
        sink.next(sseEvent("result", result));
        sink.complete();
    }).subscribeOn(Schedulers.boundedElastic());
}
```

LyClaw 中的 `OrchestratorImpl.execute()` 正是遵循了这个模式：

```java
return Flux.defer(() -> {
    // ... 构造 pipelineFlux 和 respondFlux ...
    return pipelineFlux.concatWith(respondFlux);
}).subscribeOn(Schedulers.boundedElastic());
```

**反模式5：一个请求创建多个SSE连接**

```javascript
// 错误 —— 前端多次订阅
const eventSource1 = new EventSource("/api/events");
const eventSource2 = new EventSource("/api/events");
// 服务器端收到两个连接，重复处理
```

```javascript
// 正确 —— 一个订阅，多个监听器
const eventSource = new EventSource("/api/events");
eventSource.addEventListener("message", handler1);
eventSource.addEventListener("action_result", handler2);
```

#### 使用 curl -N 调试 SSE 流

`curl` 是调试 SSE 端点的最便捷工具。`-N`（或 `--no-buffer`）参数是关键。

```bash
# 基本用法：与 LyClaw 的聊天流式端点交互
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "debug-session",
    "messages": [
      {"role": "user", "content": "写一段Java Hello World代码"}
    ],
    "stream": true
  }'
```

**输出示例（实时逐行显示）：**
```
event: context_build_start
data: Loading session and retrieving memories

event: context_build_complete
data: Loaded session, retrieved 10 memory entries

event: plan_start
data: Planning task decomposition

event: plan_complete
data: Planned 1 task(s)

event: plan_node
data: {"index":1,"nodeId":"task-1","type":"CODE_GEN","description":"生成Java Hello World"}

event: action_start
data: {"index":1,"total":1,"nodeId":"task-1","description":"生成Java Hello World"}

event: action_result
data: {"index":1,"status":"success","output":"public class HelloWorld {\n    public static void main(String[] args) {\n        System.out.println(\"Hello, World!\");\n    }\n}","durationMs":345}

event: action_complete
data: {"total":1,"success":1,"failed":0}

event: reflect_start
data: Reflecting on execution results

event: reflect_complete
data: {"score":0.95,"reflectionId":"refl-abc123"}

event: respond_start
data: Generating AI response

event: message
data: 以下是生成的 Java Hello World 代码：

event: message
data: 

event: message
data: ```java

event: message
data: public class HelloWorld {

event: message
data:     public static void main(String[] args) {

event: message
data:         System.out.println("Hello, World!");

event: message
data:     }

event: message
data: }

event: message
data: ```

event: message
data: 

event: message
data: 代码已经生成完毕。可以直接编译运行。

event: respond_complete
data: Response generated and memory persisted

event: metrics
data: {"totalDurationMs":4521,"tasksProcessed":1,"successRate":"1.00","reflectScore":0.95}

event: done
data: {"status":"completed","durationMs":4521}
```

**调试技巧：**

```bash
# 1. 只看响应头（查看 Content-Type 等）
curl -I -N http://localhost:8080/api/chat/stream

# 2. 查看详细网络信息（包括请求/响应头、TLS握手等）
curl -v -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"t","messages":[{"role":"user","content":"hi"}],"stream":true}'

# 3. 查看原始字节（包括不可见字符）
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"t","messages":[{"role":"user","content":"hi"}],"stream":true}' \
  | xxd  # 十六进制转储

# 4. 设置超时（防止永远挂起）
curl -N --max-time 120 http://localhost:8080/api/chat/stream ...

# 5. 保存到文件供后续分析
curl -N http://localhost:8080/api/chat/stream ... > sse_output.txt 2>&1
```

**注意：** `curl -N` 缺省使用 HTTP/1.1。要测试 HTTP/2 行为：

```bash
curl --http2 -N http://localhost:8080/api/chat/stream ...
```

---

## 第2章 Java / Spring生态中的响应式流

### 2.1 Reactor核心概念速览

#### 什么是响应式编程？

响应式编程（Reactive Programming）是一种以**数据流**和**变化传播**为核心的编程范式。
用一句话概括：**你告诉系统"当数据到达时做什么"，而不是"去取数据然后做什么"**。

传统命令式（Imperative）vs 响应式（Reactive）的对比：

```java
// 命令式 —— 主动拉取（Pull）
String result = fetchFromDatabase();   // 阻塞等待数据库返回
String processed = process(result);     // 处理结果
return processed;                       // 返回

// 响应式 —— 被动响应（Push）
return fetchFromDatabaseAsync()        // 不阻塞，返回一个 "将来会有结果" 的对象
    .map(result -> process(result));    // 声明 "当结果到达时怎么处理"
```

响应式编程的**四要素**：

1. **Publisher（发布者）** — 产生数据的一方
2. **Subscriber（订阅者）** — 消费数据的一方
3. **Subscription（订阅关系）** — 连接发布者和订阅者的桥梁
4. **Processor（处理器）** — 既是订阅者又是发布者（中间处理环节）

```
Publisher ──subscribe──> Subscriber
    │                        │
    │<── onSubscribe ────────│  ← Subscriber 向 Publisher 请求订阅
    │                        │
    │── onNext(data1) ──────>│  ← Publisher 推送数据
    │── onNext(data2) ──────>│
    │── onNext(data3) ──────>│
    │                        │
    │── onComplete() ───────>│  ← Publisher 通知结束
    │        或               │
    │── onError(throwable) ─>│  ← Publisher 通知异常
```

#### Observable 模式的进化

响应式编程的"发布-订阅"模式源自经典的 Observer 设计模式，但做了关键扩展：

```
经典 Observer 模式            Reactive Streams
────────────────────        ──────────────────────
同步调用                    异步非阻塞
无 backpressure             支持 backpressure（背压）
无错误通道                   专用错误通道 onError()
无完成信号                   专用完成信号 onComplete()
单播（一对一）              多播支持（一对多）
不可组合                    丰富的操作符组合（map、filter、flatMap...）
```

#### `Mono<T>` —— 0或1个元素的容器

`Mono<T>` 是 Reactor 中表示"0个或1个异步结果"的类型。可以把它理解为增强版的
`CompletableFuture<T>`。

```
Mono<T> 的生命周期：

创建 ─────────────────────────────────────────> 时间轴
  │
  ├── 成功：发射 1 个元素 → onComplete()
  │
  ├── 空：发射 0 个元素 → onComplete()
  │
  └── 失败：发射 0 个元素 → onError(error)
```

**关键特征：Mono 是惰性的（Lazy）**

```java
// 创建 Mono —— 此时什么都不会发生
Mono<String> greeting = Mono.just("Hello");

// 还没有订阅 → 没有代码执行
// greeting 只是一个"描述"，就像菜谱

// 订阅后 — 菜谱才开始做
greeting.subscribe(
    value -> System.out.println("收到: " + value),  // onNext
    error -> System.err.println("错误: " + error),   // onError
    () -> System.out.println("完成")                 // onComplete
);

// 输出：
// 收到: Hello
// 完成
```

**常用创建方式：**

```java
// 从已知值创建
Mono<String> m1 = Mono.just("Hello");
Mono<String> m2 = Mono.empty();         // 空的，直接发 onComplete
Mono<String> m3 = Mono.error(new RuntimeException("出错了"));

// 从 Callable 延迟创建（与 just 的区别后面讲）
Mono<String> m4 = Mono.fromCallable(() -> expensiveOperation());

// 从 Future 创建
Mono<String> m5 = Mono.fromFuture(completableFuture);

// 从另一个 Publisher 的第一个元素创建
Mono<String> m6 = Mono.from(fluxOfStrings);  // 取第一个元素
```

#### `Flux<T>` —— 0到N个元素的异步序列

`Flux<T>` 是 Reactor 中表示"0到N个异步元素"的类型。可以把它理解为增强版的异步
`Stream<T>`。

```
Flux<T> 的生命周期：

创建 ───────────────────────────────────────────────> 时间轴
  │
  ├── onNext(item1)
  ├── onNext(item2)
  ├── onNext(item3)
  ├── ...
  ├── onNext(itemN) → onComplete()    (正常结束)
  │        或
  └── onNext(itemX) → onError(error)  (异常终止)
```

**关键特征：Flux 支持背压（Backpressure）**

```java
// 创建 Flux —— 此时什么都不会发生
Flux<Integer> numbers = Flux.range(1, 100);  // 只是一个描述

// 订阅并消费
numbers
    .take(5)           // 只取前5个（背压：告诉上游少发点）
    .subscribe(
        n -> System.out.println("收到: " + n),
        error -> System.err.println("错误: " + error),
        () -> System.out.println("完成")
    );

// 输出：
// 收到: 1
// 收到: 2
// 收到: 3
// 收到: 4
// 收到: 5
// 完成
```

**常用创建方式：**

```java
// 从已知值创建
Flux<String> f1 = Flux.just("a", "b", "c");

// 从集合创建
Flux<String> f2 = Flux.fromIterable(listOfStrings);

// 从范围创建
Flux<Integer> f3 = Flux.range(1, 10);

// 从 Stream 创建
Flux<String> f4 = Flux.fromStream(stringStream);

// 定时发送
Flux<Long> f5 = Flux.interval(Duration.ofSeconds(1));  // 每秒发一个递增的 Long

// 手动创建（编程式）
Flux<String> f6 = Flux.create(sink -> {
    sink.next("手动发给sink的元素1");
    sink.next("手动发给sink的元素2");
    sink.complete();
});
```

#### Publisher → Subscriber 模型

Reactor 实现了 Reactive Streams 规范（`org.reactivestreams` 包），定义了四个接口：

```java
// 发布者 —— 提供数据
public interface Publisher<T> {
    void subscribe(Subscriber<? super T> s);
}

// 订阅者 —— 消费数据
public interface Subscriber<T> {
    void onSubscribe(Subscription s);
    void onNext(T t);
    void onError(Throwable t);
    void onComplete();
}

// 订阅关系 —— 控制流速
public interface Subscription {
    void request(long n);  // 请求 n 个元素（背压的核心）
    void cancel();         // 取消订阅
}
```

**完整的交互过程（时序图）：**

```
Publisher                Subscription              Subscriber
    │                         │                         │
    │ subscribe(subscriber)   │                         │
    │────────────────────────│────────────────────────>│
    │                         │                         │
    │                    onSubscribe(subscription)      │
    │<────────────────────────│─────────────────────────│
    │                         │                         │
    │                         │ request(5)              │
    │<────────────────────────│─────────────────────────│
    │                         │                         │
    │ onNext(item1)           │                         │
    │────────────────────────│────────────────────────>│
    │ onNext(item2)           │                         │
    │────────────────────────│────────────────────────>│
    │ onNext(item3)           │                         │
    │────────────────────────│────────────────────────>│
    │ onNext(item4)           │                         │
    │────────────────────────│────────────────────────>│
    │ onNext(item5)           │                         │
    │────────────────────────│────────────────────────>│
    │                         │ request(10)             │
    │<────────────────────────│─────────────────────────│
    │                         │                         │
    │ onComplete()            │                         │
    │────────────────────────│────────────────────────>│
```

注意：在大多数 Reactor 使用中，开发人员不需要直接操作 Subscription。Reactor 的
操作符（如 `subscribe()`、`take()`、`limitRate()`）在幕后处理 request/cancel 逻辑。

#### 背压（Backpressure）概念

背压是响应式流的核心机制，指的是**下游告诉上游"慢一点，我处理不过来"**的能力。

想象一个生产线：

```
没有背压的情况（拉取模式）：
┌──────────┐   每秒100个    ┌──────────┐   每秒10个   ┌──────────┐
│ 生产者    │ ────────────> │ 管道      │ ──────────> │ 消费者    │
│ (数据库)  │               │ (中转)    │             │ (慢处理)  │
└──────────┘               └──────────┘             └──────────┘
                                   │
                                   ├── 90%的数据堆积在管道中
                                   ├── 内存爆炸 (OOM)
                                   └── 系统崩溃

有背压的情况（推送+协商模式）：
┌──────────┐  request(10)  ┌──────────┐  request(5)  ┌──────────┐
│ 生产者    │ <──────────── │ 管道      │ <─────────── │ 消费者    │
│           │ ────────────> │           │ ───────────> │           │
│           │  发送10个     │           │  转发5个     │           │
└──────────┘               └──────────┘             └──────────┘
      生产者只发送消费者请求的数量，不会溢出
```

**Reactor 中的背压策略：**

```java
// 1. 请求限制 —— 告诉上游一次最多发多少
Flux.range(1, 1000)
    .limitRate(10)   // 每次请求10个
    .subscribe(slowConsumer);

// 2. 缓冲 —— 积压元素暂存
Flux.range(1, 1000)
    .onBackpressureBuffer(100)  // 最多缓冲100个
    .subscribe(slowConsumer);

// 3. 丢弃 —— 处理不过来的直接丢掉
Flux.range(1, 1000)
    .onBackpressureDrop(dropped -> log.warn("丢弃: {}", dropped))
    .subscribe(slowConsumer);

// 4. 最新 —— 只保留最新的，旧的丢弃
Flux.range(1, 1000)
    .onBackpressureLatest()
    .subscribe(slowConsumer);

// 5. 错误 —— 背压溢出直接抛异常
Flux.range(1, 1000)
    .onBackpressureError()
    .subscribe(slowConsumer);
```

**LyClaw 场景中的背压：**

在 SSE 流式输出中，背压天然存在：

```
LLM API ──token流──> OkHttpClient ──逐行──> Reactor Flux ──SSE──> 浏览器
  (快)                (缓冲)               (背压传递)        (慢)
```

如果浏览器网络慢（或前端渲染慢），Reactor 的背压会沿着链条反向传播：
浏览器处理不过来 → TCP 缓冲区满 → Spring 写缓冲区满 → Flux 暂停发射 →
OkHttp 读取暂停 → TCP 接收窗口收缩 → LLM API 被迫降速。

这是 SSE over HTTP 带来的自然背压——比 WebSocket 的自己做流控要简单得多。

#### Hot vs Cold 流

这是理解 Reactor 流行为的关键概念。

##### Cold 流（冷流）—— 每次订阅重新开始

像一个点播视频（Netflix）：每个观众从头开始看，互不影响。

```java
Flux<Integer> coldFlux = Flux.range(1, 3)
    .doOnNext(n -> System.out.println("发射: " + n));

// 订阅者 A
coldFlux.subscribe(n -> System.out.println("  A收到: " + n));

// 订阅者 B（又从头开始了！）
coldFlux.subscribe(n -> System.out.println("  B收到: " + n));
```

输出：
```
发射: 1
  A收到: 1
发射: 2
  A收到: 2
发射: 3
  A收到: 3
发射: 1          ← B 订阅后从头开始
  B收到: 1
发射: 2
  B收到: 2
发射: 3
  B收到: 3
```

特征：
- `Flux.just()`、`Flux.range()`、`Flux.fromIterable()` 都是 Cold
- 每个订阅者得到完整的数据序列
- LyClaw 中：`adapter.chatStream(request)` 返回的 Flux 是 Cold 的——每个 HTTP 请求
  创建新的 LLM 调用

##### Hot 流（热流）—— 订阅者只看到订阅之后的数据

像一个直播节目：你看到的是当前的画面，错过了就是错过了。

```java
ConnectableFlux<Integer> hotFlux = Flux.range(1, 10)
    .delayElements(Duration.ofMillis(100))
    .publish();  // 转换为 Hot
hotFlux.connect();  // 开始发射（不等订阅者）

// 订阅者 A —— 立即开始接收
hotFlux.subscribe(n -> System.out.println("A收到: " + n));

// 等待 500ms
Thread.sleep(500);

// 订阅者 B —— 只能收到后面的
hotFlux.subscribe(n -> System.out.println("B收到: " + n));
```

输出（A 收到 1~10，B 只能收到 6~10）：
```
A收到: 1
A收到: 2
A收到: 3
A收到: 4
A收到: 5
A收到: 6
B收到: 6       ← B 来晚了
A收到: 7
B收到: 7
...
```

**对 LyClaw 的影响：**

```java
// Cold —— 每个请求触发独立的 LLM 调用
@PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
    return orchestrator.execute(context);  // Cold: 每个 HTTP 请求独立的编排流程
}

// 每个用户看到的是自己的对话 —— 这正是我们需要的
```

---

### 2.2 Spring WebFlux的SSE支持

#### `MediaType.TEXT_EVENT_STREAM_VALUE`

在 Spring 框架中，`MediaType.TEXT_EVENT_STREAM_VALUE` 是一个字符串常量，定义在
`org.springframework.http.MediaType` 中：

```java
public static final String TEXT_EVENT_STREAM_VALUE = "text/event-stream";
```

这个常量等价于 `MediaType.TEXT_EVENT_STREAM`（MediaType 对象，非字符串）。

在 LyClaw 的 `OrchestrationController.java` 中：

```java
@PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
    return orchestrator.execute(context);
}
```

`produces = MediaType.TEXT_EVENT_STREAM_VALUE` 做了两件事：
1. 告诉 Spring：这个方法的返回值应该序列化为 `text/event-stream` 格式
2. 告诉客户端：HTTP 响应头的 `Content-Type` 会是 `text/event-stream`

如果请求的 `Accept` 头不包含 `text/event-stream`，Spring 会返回 406 Not Acceptable。

#### `ServerSentEvent<T>` Builder 模式

`org.springframework.http.codec.ServerSentEvent<T>` 是 Spring 对 SSE 协议的 Java 表示。
它是一个不可变对象，使用 Builder 模式构建。

**完整的 Builder API：**

```java
// 类型签名
public class ServerSentEvent<T> {

    // 构建器
    public static <T> Builder<T> builder() { ... }

    public interface Builder<T> {
        Builder<T> id(String id);                    // SSE 的 id: 字段
        Builder<T> event(String event);              // SSE 的 event: 字段
        Builder<T> data(T data);                     // SSE 的 data: 字段
        Builder<T> retry(Duration retry);            // SSE 的 retry: 字段
        Builder<T> comment(String comment);           // SSE 的注释行 (: 开头)
        ServerSentEvent<T> build();                   // 构建不可变对象
    }

    // 访问器
    public String id()       { ... }
    public String event()    { ... }
    public T data()          { ... }
    public Duration retry()  { ... }
    public String comment()  { ... }
}
```

**Build → 线上格式的映射关系：**

```java
// Java 代码
ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
    .id("42")
    .event("temperature")
    .data("23.5")
    .retry(Duration.ofMillis(10000))
    .build();

// 对应的线上 SSE 格式（由 ServerSentEventHttpMessageWriter 写入）：
//
// id: 42
// event: temperature
// retry: 10000
// data: 23.5
//
```

**字段对应表：**

| builder 方法  | SSE 字段    | 说明                                         |
| ------------- | ----------- | -------------------------------------------- |
| `.id("42")`   | `id: 42`    | 事件 ID，断线重连的 Last-Event-ID            |
| `.event("T")` | `event: T`  | 事件类型                                     |
| `.data(obj)`  | `data: obj` | 数据负载（T 可以是 String、byte[]、Object）  |
| `.retry(D)`   | `retry: N`  | 重连间隔（毫秒）                             |
| `.comment("")`| `: text`    | SSE 注释（不触发事件）                       |

**LyClaw 封装：**

```java
// OrchestratorImpl.java 中的辅助方法
private ServerSentEvent<String> sseEvent(String eventType, String payload) {
    return ServerSentEvent.<String>builder()
        .event(eventType)
        .data(payload)
        .build();
}

// 使用
sink.next(sseEvent("plan_start", "Planning task decomposition"));
```

**只发送注释（心跳保活）：**

```java
ServerSentEvent.<String>builder()
    .comment("heartbeat")
    .build();

// 线上格式 -> : heartbeat\n\n
```

**只发送 retry（不附带事件）：**

```java
ServerSentEvent.<String>builder()
    .retry(Duration.ofMillis(5000))
    .build();

// 线上格式 -> retry: 5000\n\n
```

#### `produces = MediaType.TEXT_EVENT_STREAM_VALUE` 声明 SSE 端点

在 Spring WebFlux 中，`@PostMapping`（或 `@GetMapping`）配合 `produces` 属性声明
一个 SSE 端点：

```java
@RestController
@RequestMapping("/api")
public class OrchestrationController {

    // SSE 流式端点
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
        return orchestrator.execute(context);
    }

    // 非流式端点（同样的逻辑，聚合后返回）
    @PostMapping("/chat")
    public Mono<ChatResult> chat(@RequestBody ChatRequest request) {
        Flux<ServerSentEvent<String>> flux = orchestrator.execute(context);
        return flux.collectList()
            .map(events -> aggregateIntoResult(events))
            .subscribeOn(Schedulers.boundedElastic());
    }
}
```

**为什么返回类型是 `Flux<ServerSentEvent<String>>`？**

- `Flux<ServerSentEvent<String>>` — 表示一个元素序列，每个元素是一个 SSE 事件
- Spring 自动将每个 `ServerSentEvent` 对象序列化为对应的 SSE 文本行
- 返回 `Flux<String>` 也可以（Spring 默认用 `message` 事件包装），但失去了事件类型
  区分的能力

**对比：不同返回类型的行为**

```java
// 1. Flux<String> —— 每个字符串变成 data: 行，没有 event: 字段
@GetMapping(value = "/stream1", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> stream1() {
    return Flux.just("hello", "world");
}
// 线上输出：
// data: hello
//
// data: world
//

// 2. Flux<ServerSentEvent<String>> —— 完全控制 SSE 格式
@GetMapping(value = "/stream2", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> stream2() {
    return Flux.just(
        ServerSentEvent.<String>builder().event("greeting").data("hello").build(),
        ServerSentEvent.<String>builder().event("farewell").data("goodbye").build()
    );
}
// 线上输出：
// event: greeting
// data: hello
//
// event: farewell
// data: goodbye
//
```

#### Spring 如何内部转换 ServerSentEvent 为字节

当 Spring WebFlux 检测到返回值是 `Flux<ServerSentEvent<T>>` 且 `produces` 包含
`text/event-stream` 时，它会寻找合适的 `HttpMessageWriter` 来处理序列化。

Spring 内部注册了一个 `ServerSentEventHttpMessageWriter`：

```
请求到达 DispatcherHandler
        │
        ▼
   找到 HandlerMethod (OrchestrationController.chatStream)
        │
        ▼
   调用方法，获得 Flux<ServerSentEvent<String>>
        │
        ▼
   查找 HttpMessageWriter
   ┌────────────────────────────────────────┐
   │ MIME 类型匹配：text/event-stream       │
   │   → ServerSentEventHttpMessageWriter   │
   └────────────────────────────────────────┘
        │
        ▼
ServerSentEventHttpMessageWriter.write()
        │
        ├── 遍历 Flux 中的每个 ServerSentEvent
        │   ├── 如果有 id:     写入 "id: " + id + "\n"
        │   ├── 如果有 event:  写入 "event: " + event + "\n"
        │   ├── 如果有 retry:  写入 "retry: " + retryMillis + "\n"
        │   ├── 如果有 data:   每个 data 对象 JSON 序列化
        │   │    ├── 简单类型 (String): 写入 "data: " + value + "\n"
        │   │    └── 复杂类型 (Object): 用 Encoder 编码后写入
        │   ├── 如果有 comment: 写入 ": " + comment + "\n"
        │   └── 写入 "\n" (事件结束空行)
        │
        ▼
   字节写入 Netty 响应通道 → 网络 → 客户端

```

**序列化细节（对于 `data` 中包含换行的情况）：**

```java
ServerSentEvent.<String>builder()
    .event("message")
    .data("第1行\n第2行\n第3行")
    .build();

// ServerSentEventHttpMessageWriter 内部处理：
// 检测到 data 是 String 且包含 \n
// 拆分为多行并每行加上 data: 前缀
//
// 输出：
// event: message
// data: 第1行
// data: 第2行
// data: 第3行
//
```

这是 `ServerSentEventHttpMessageWriter` 的核心价值 —— 自动处理多行数据的 SSE 编码，
开发者不需要手动拼接 SSE 字符串。

#### 传统 Servlet 栈对比：SseEmitter vs WebFlux

Spring 提供了两种 SSE 实现方式：

```
┌─────────────────────────────────────────────────────────────┐
│                    Spring 的两种 SSE 方案                     │
├──────────────────────┬──────────────────────────────────────┤
│    SseEmitter         │     WebFlux + ServerSentEvent        │
│   (Servlet 栈)        │     (Reactive 栈)                   │
├──────────────────────┼──────────────────────────────────────┤
│ 基于 Servlet 3.1+    │ 基于 Reactive Streams               │
│ 异步请求处理          │ 完全的响应式编程                     │
│ 每连接一个线程        │ 事件驱动，少量线程处理大量连接        │
│ 阻塞式 send() 方法    │ 非阻塞 Flux/Mono 操作符              │
│ 线程池管理复杂        │ Reactor 自动管理调度                 │
│ 传统 Spring MVC       │ Spring WebFlux                       │
└──────────────────────┴──────────────────────────────────────┘
```

**SseEmitter 示例（传统方式）：**

```java
@Controller
public class LegacySseController {

    @GetMapping("/sse/legacy")
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(300_000L);  // 5分钟超时

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    emitter.send(SseEmitter.event()
                        .name("progress")
                        .data("Step " + i + " completed"));
                    Thread.sleep(1000);
                }
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
```

**WebFlux 对比示例（LyClaw 使用的方式）：**

```java
@RestController
public class ReactiveSseController {

    @GetMapping(value = "/sse/reactive", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream() {
        return Flux.interval(Duration.ofSeconds(1))
            .map(i -> ServerSentEvent.<String>builder()
                .event("progress")
                .data("Step " + i + " completed")
                .build())
            .take(10);
    }
}
```

**关键差异总结：**

| 维度               | SseEmitter              | WebFlux Flux<ServerSentEvent> |
| ------------------ | ----------------------- | ----------------------------- |
| 线程模型           | 每连接一个线程（或线程池）| 事件循环，少量线程             |
| 连接数上限         | 受线程池大小限制         | 理论上无上限（受文件描述符限制）|
| 错误处理           | try-catch               | onErrorResume / doOnError      |
| 背压支持           | 无（可能 OOM）           | 内置背压支持                   |
| 操作符             | 手动逻辑                | 丰富的 Flux/Mono 操作符        |
| LyClaw 选择原因     | —                       | 流水线编排、LLM流式处理        |

---

### 2.3 关键Reactor操作符深度解析

以下逐一解析 LyClaw 项目中使用（或可能使用）的关键 Reactor 操作符。

#### `Flux.create(sink -> {...})` —— 手动发射

**功能：** 通过 `FluxSink` 接口手动控制元素的发射、完成和错误。这是最灵活的创建方式，
适合包装回调式 API 或事件驱动的数据源。

**工作原理：**

```java
Flux.create(sink -> {
    // 这个 lambda 在订阅时被调用
    // sink 是向 Flux 管道发射数据的出口

    sink.next("元素1");      // 发射一个元素
    sink.next("元素2");      // 再发射一个
    sink.complete();         // 通知完成
    // 或者
    // sink.error(new RuntimeException("出错了"));

}, FluxSink.OverflowStrategy.BUFFER);  // 背压溢出策略
```

**`FluxSink.OverflowStrategy` 选项：**

- `BUFFER` — 下游跟不上时缓冲（可能 OOM）
- `DROP` — 下游跟不上时丢弃新数据
- `LATEST` — 只保留最新的，覆盖旧的
- `ERROR` — 下游跟不上时抛出异常
- `IGNORE` — 完全忽略背压（危险，仅在使用者完全确定不会溢出时使用）

**LyClaw 中的实际使用（OrchestratorImpl.execute()）：**

```java
Flux<ServerSentEvent<String>> pipelineFlux = Flux.<ServerSentEvent<String>>create(sink -> {
    try {
        // Stage 1: Context Build
        sink.next(sseEvent("context_build_start", "Loading session..."));
        MemoryQueryResult memoryResult = memoryFeignClient.retrieve(memoryQuery);
        sink.next(sseEvent("context_build_complete", "Loaded session..."));

        // Stage 2: Intercept
        sink.next(sseEvent("intercept_start", "Running security checks..."));
        // 安全检查...
        sink.next(sseEvent("intercept_complete", "Security check passed"));

        // Stage 3: Plan
        sink.next(sseEvent("plan_start", "Planning..."));
        Map<String, Object> planResult = planFeignClient.plan(planReq);
        sink.next(sseEvent("plan_complete", "Planned " + nodes.size() + " task(s)"));

        // ... 更多阶段 ...

        sink.complete();
    } catch (Exception e) {
        sink.error(e);  // 将异常传播到 Reactor 的错误通道
    }
});
```

**为什么用 `create` 而不是其他创建方式？**

在 LyClaw 编排场景中，流水线的各阶段是**同步顺序执行**的，但需要在执行过程中把进度
事件实时推送给前端。`Flux.create` 完美匹配这个需求：
- 执行流程是命令式的（Step 1, Step 2, Step 3...）
- 每个步骤完成后发送一个或多个 SSE 事件
- `sink.complete()` 标记流水线阶段完成
- 异常通过 `sink.error()` 传递给下游的错误处理

**`Flux.create` vs `Flux.generate`：**

```
Flux.create:
  - 发射由开发者完全控制
  - 可以随时从任何线程调用 sink.next()
  - 适合回调式 API、事件监听器
  - 没有自然的背压支持（使用 OverflowStrategy 来缓解）

Flux.generate:
  - 同步、一次一个地生成元素
  - 每个 onNext 后暂停，等待下游的 request
  - 适合包装阻塞 IO（如按行读取文件）
  - 完全支持背压
```

#### `Flux.defer(() -> Flux...)` —— 惰性创建

**功能：** 推迟 Flux 的创建，直到有订阅者时才执行。每次订阅都创建一个**全新的 Flux 实例**。

**这是 SSE 端点中最关键的操作符之一。**

```java
// 不用 defer —— 有 bug！
@PostMapping("/sse/bad")
public Flux<String> badStream() {
    String timestamp = Instant.now().toString();  // 只执行一次！
    return Flux.just(timestamp, "hello");
    // 第一个请求看到的时间是正确的
    // 但第二个请求可能也看到同一个时间（取决于 Flux 是否被缓存）
}

// 用 defer —— 正确！
@PostMapping("/sse/good")
public Flux<String> goodStream() {
    return Flux.defer(() -> {
        String timestamp = Instant.now().toString();  // 每次订阅都执行！
        return Flux.just(timestamp, "hello");
    });
    // 每个请求都看到自己独立的时间戳
}
```

**LyClaw 中的关键使用：**

```java
@Override
public Flux<ServerSentEvent<String>> execute(ChatContext context) {
    return Flux.defer(() -> {
        // 这个 lambda 在每次订阅时执行
        // context 是每个请求独立的
        // 确保不同用户的请求互不干扰

        Flux<ServerSentEvent<String>> pipelineFlux = ...;
        Flux<ServerSentEvent<String>> respondFlux = ...;
        return pipelineFlux.concatWith(respondFlux);
    }).subscribeOn(Schedulers.boundedElastic());
}
```

**为什么 `defer` 在 LyClaw 中至关重要：**

1. **per-request state（请求级别的状态）：** 每个 SSE 请求有自己的 `ChatContext`、
   自己的原子变量（`successCount`、`failCount`）、自己的工具执行结果列表
2. **线程安全：** 没有 `defer`，多个请求可能共享同一个 Flux 实例中的可变状态，
   导致数据串扰
3. **延迟初始化：** 只有在客户端真正连接时才启动编排流水线（包括 Feign 调用、
   LLM 调用等昂贵操作）

**可视化对比：**

```
没有 defer（错误）：
┌────────────────────────────────────────────────────┐
│  请求1 ──> execute(ctx1)                         │
│            Flux.create() 被调用一次                 │
│            返回的 Flux 实例被两部分共享              │
│  请求2 ──> execute(ctx2)  ← ctx2 的状态混入 ctx1  │
│            可能看到 ctx1 的部分结果                 │
└────────────────────────────────────────────────────┘

有 defer（正确）：
┌────────────────────────────────────────────────────┐
│  请求1 ──> execute(ctx1)                         │
│            → defer() 创建新的 Flux 实例             │
│            → ctx1 的状态完全隔离                   │
│                                                    │
│  请求2 ──> execute(ctx2)                          │
│            → defer() 创建另一个全新 Flux 实例       │
│            → ctx2 的状态完全隔离                   │
└────────────────────────────────────────────────────┘
```

#### `Flux.generate(stateSupplier, generator, stateConsumer)` —— 同步逐一发射

**功能：** 同步的、一次一个的生成元素。每个元素生成后等待下游请求。

**三个参数：**

1. `stateSupplier` — 创建初始状态（在订阅时调用一次）
2. `generator` — 生成器函数 `(state, sink) -> state`，每次下游请求时调用
3. `stateConsumer` — 状态清理函数（在 `sink.complete()` 或取消时调用）

**LyClaw 中的实际使用（OkHttpModelApiClient.postStream()）：**

```java
return Flux.<String, StreamContext>generate(
    // 1. stateSupplier: 初始化——建立 HTTP 连接，创建 BufferedReader
    () -> {
        try {
            Response response = httpClient.newCall(request).execute();
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null
                    ? response.body().string() : "";
                response.close();
                throw parseHttpError(response.code(), errorBody, url);
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                response.close();
                throw ModelException.of(ErrorCode.MODEL_RESPONSE_PARSE_ERROR,
                    "响应体为空");
            }
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(responseBody.byteStream()));
            return new StreamContext(response, responseBody, reader);
        } catch (IOException e) {
            throw ModelException.of(ErrorCode.MODEL_API_ERROR,
                "流式请求失败: " + e.getMessage());
        }
    },

    // 2. generator: 每次调用从 BufferedReader 读取一行
    (ctx, sink) -> {
        try {
            String line;
            while ((line = ctx.reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    sink.next(line);      // 发射一行（非空行）
                    return ctx;           // 返回状态，等待下一次调用
                }
                // 跳过空行，继续循环
            }
            // 读完了
            ctx.close();
            sink.complete();
        } catch (IOException e) {
            ctx.close();
            sink.error(ModelException.of(ErrorCode.MODEL_API_ERROR,
                "流式读取失败: " + e.getMessage()));
        }
        return ctx;
    },

    // 3. stateConsumer: 清理——关闭连接
    StreamContext::close
).subscribeOn(Schedulers.boundedElastic());
```

**执行流程：**

```
订阅发生
    │
    ▼
stateSupplier.run() → 创建 StreamContext (打开连接 + BufferedReader)
    │
    ▼
下游请求(1) → generator.run(ctx, sink)
    │           读取 BufferedReader.readLine() → "第一行"
    │           sink.next("第一行")
    │           返回 ctx
    │
    ▼
下游处理 "第一行"，然后 request(1)
    │
    ▼
generator.run(ctx, sink)
    │           读取 BufferedReader.readLine() → "第二行"
    │           sink.next("第二行")
    │           返回 ctx
    │
    ▼
    ... 持续 ...
    │
    ▼
下游 request(1) → generator.run(ctx, sink)
    │           读取 BufferedReader.readLine() → null (EOF)
    │           ctx.close()  （关闭连接）
    │           sink.complete()
    │
    ▼
stateConsumer.accept(ctx) → StreamContext.close()  （兜底清理）
```

**为什么 LyClaw 用 `generate` 而不是 `create` 来读取 OkHttp 流？**

1. **OkHttp 的 `readLine()` 是阻塞的** — `generate` 的同步、一次一个的特性天然匹配
2. **背压安全** — 如果下游（SSE 输出）处理慢，`generate` 会等待下游的 `request(N)`，
   而不是疯狂读取所有行塞进缓冲区
3. **资源管理** — `stateConsumer` 保证连接一定被关闭，即使在中途取消时

#### `Flux.just(a, b, c)` —— 发射固定值

**功能：** 创建按顺序发射固定值的 Flux。

```java
// 基本用法
Flux<String> threeItems = Flux.just("a", "b", "c");
// 订阅时按顺序输出: a, b, c, onComplete()

// 单个元素
Flux<String> singleItem = Flux.just("only one");
// 输出: only one, onComplete()

// 空 Flux
Flux<String> empty = Flux.empty();
// 直接 onComplete()
```

**LyClaw 中的使用场景：**

```java
// 在 respondFlux 的 fallback 场景
Flux.just(
    sseEvent("message", fallbackResponse),
    sseEvent("done", "{\"status\":\"completed\",\"fallback\":true}")
)

// 在 buildTailFlux 返回最终状态事件
Flux.just(
    sseEvent("respond_complete", "Response generated..."),
    sseEvent("metrics", "{...}"),
    sseEvent("done", "{...}")
)
```

**重要注意事项：** `Flux.just()` 在方法调用时就立即捕获参数值（热切 Eager），而不是
在订阅时。如果需要延迟计算，用 `Flux.defer(() -> Flux.just(compute()))`。

```java
// 错误 —— value 在方法定义时就确定了
public Flux<String> getValue() {
    String value = expensiveComputation();  // 即使没人订阅也会执行！
    return Flux.just(value);
}

// 正确 —— value 在订阅时才计算
public Flux<String> getValue() {
    return Flux.defer(() -> {
        String value = expensiveComputation();  // 有订阅者才执行
        return Flux.just(value);
    });
}
```

#### `flux1.concatWith(flux2)` —— 顺序拼接

**功能：** 将两个 Flux 按顺序拼接。`flux2` 只有在 `flux1` 完成后才开始发射。

```
concatWith 的时间线：
───────────────────────────────────────────────────>
flux1:  ──A──B──C──|            (| 表示 onComplete)
flux2:            ──X──Y──Z──|

结果:   ──A──B──C──X──Y──Z──|
```

**与 `mergeWith` 的对比：**

```
mergeWith 的时间线（并发交织）：
───────────────────────────────────────────────────>
flux1:  ──A─────B──────────C──|
flux2:  ─────X───────Y──Z─────|

结果:   ──A──X──B──Y──Z──C──|
        (哪个先到就先发，不保证顺序)
```

**LyClaw 中的使用：**

```java
// OrchestratorImpl.execute() 中拼接两个阶段
return pipelineFlux.concatWith(respondFlux);
```

这确保了：
1. 先完成 5 个流水线阶段（Context Build → Intercept → Plan → Execute → Reflect）
2. 只有流水线成功完成（`pipelineOk = true`）后，才开始 LLM 响应阶段
3. 前端看到的事件顺序是确定的：先看到流水线进度事件，再看到 LLM 逐字输出

**可视化 LyClaw 的 SSE 事件时间线：**

```
concatWith
│
├─ pipelineFlux (Stage 1-5) ─────────────────────────────│
│  context_build_start                                    │
│  context_build_complete                                 │
│  intercept_start                                        │
│  intercept_complete                                     │
│  plan_start                                             │
│  plan_complete                                          │
│  plan_node                                              │
│  action_start                                           │
│  action_result                                          │
│  action_complete                                        │
│  reflect_start                                          │
│  reflect_complete                                       │
│                                                         │
├─ respondFlux (Stage 6-7) ───────────────────────────────│
│  respond_start                                          │
│  message: "好的"                                        │
│  message: "，我来帮您"                                  │
│  message: "创建..."                                     │
│  ...                                                    │
│  respond_complete                                       │
│  metrics: {...}                                         │
│  done: {...}                                            │
```

#### `flux.map(fn)` —— 一对一转换

**功能：** 将流中的每个元素转换为另一个元素。

```
输入:  ──1──2──3──4──5──|
        ↓  ↓  ↓  ↓  ↓   (map: x -> x * 10)
输出:  ──10──20──30──40──50──|
```

```java
Flux<Integer> numbers = Flux.range(1, 5);
Flux<Integer> multiplied = numbers.map(n -> n * 10);
// 输出: 10, 20, 30, 40, 50

// 类型也可以改变
Flux<String> labels = numbers.map(n -> "Item #" + n);
// 输出: "Item #1", "Item #2", "Item #3", "Item #4", "Item #5"
```

**LyClaw 中的使用：**

```java
// 将 LLM 流式的每一行提取为 SSE 事件
adapter.chatStream(llmRequest)
    .handle((line, sink) -> {
        String text = adapter.extractSsePlainText(line);
        if (!text.isEmpty()) {
            sink.next(sseEvent("message", text));
        }
    });

// 如果用 map 的简化写法（当不需要过滤时）
adapter.chatStream(llmRequest)
    .map(line -> adapter.extractSsePlainText(line))
    .filter(text -> !text.isEmpty())
    .map(text -> sseEvent("message", text));
```

#### `flux.filter(pred)` —— 按条件过滤

**功能：** 只保留满足条件的元素，丢弃不满足的。

```
输入:  ──a──B──c──D──e──|
        ↓     ↓     ↓    (filter: isUpperCase)
输出:  ──B──────D──|
```

```java
Flux<String> mixed = Flux.just("apple", "Banana", "cherry", "Date");
Flux<String> upper = mixed.filter(s -> Character.isUpperCase(s.charAt(0)));
// 输出: "Banana", "Date"
```

#### `flux.collectList()` —— 聚合为 List

**功能：** 将整个 Flux 的所有元素收集到一个 `Mono<List<T>>` 中。

```
输入 Flux<T>:    ──A──B──C──D──|
                   ↓  ↓  ↓  ↓
collectList():   ═══════════════Mono<List<T>>
                                │
输出 Mono<List>: ─────────────────────────[A,B,C,D]──|
```

**LyClaw 中的实际使用（非流式端点）：**

```java
@PostMapping("/chat")
public Mono<ChatResult> chat(@RequestBody ChatRequest request) {
    Flux<ServerSentEvent<String>> flux = orchestrator.execute(context);
    return flux.collectList()   // 收集所有 SSE 事件
        .map(results -> {
            // results 是所有 SSE 事件的 List
            String content = results.stream()
                .filter(e -> "message".equals(e.event()))  // 只提取 message 事件
                .map(e -> e.data() != null ? e.data() : "")
                .reduce("", String::concat);               // 拼接所有文本
            return new ChatResult(content, "stop", null, null, 0L);
        })
        .subscribeOn(Schedulers.boundedElastic());
}
```

这里暴露了一个重要的设计模式：同一个 `orchestrator.execute()` 方法，在流式端点和
非流式端点中复用：
- **流式端点：** `Flux<ServerSentEvent<String>>` 直接返回 → 每个事件实时推送给前端
- **非流式端点：** `Flux.collectList().map(...)` → 收集所有事件，提取文本内容，返回
  一次性的完整结果

#### `flux.subscribeOn(Scheduler)` —— 指定订阅线程池

**功能：** 指定订阅（subscription）发生在哪个 Scheduler（线程池）上。它从上游一直
影响到订阅源头。

```java
Flux<String> flux = Flux.create(sink -> {
    System.out.println("创建线程: " + Thread.currentThread().getName());
    sink.next("hello");
    sink.complete();
});

flux.subscribeOn(Schedulers.boundedElastic())
    .subscribe(v -> System.out.println("消费线程: " + Thread.currentThread().getName()));

// 输出：
// 创建线程: boundedElastic-1
// 消费线程: boundedElastic-1
```

**`subscribeOn` 在链中的位置：** `subscribeOn` 影响的是它上游的订阅行为（Upstream），
而不是下游的消费。

```
subscribeOn 的影响范围（箭头方向 = 线程影响方向）：

上游 <── subscribeOn(Scheduler) ──> 下游
 (在此线程中执行)                    (不影响这里的线程)

具体来说：
Flux.create(sink -> { ... })        ← 在 subscribeOn 指定的线程
    .map(x -> transform(x))         ← 在 subscribeOn 指定的线程
    .filter(pred)                   ← 在 subscribeOn 指定的线程
    .subscribeOn(Schedulers.boundedElastic())  ← 声明在这里
    .subscribe(v -> ...);           ← 可能在不同线程（默认是调用线程）
```

**LyClaw 中的双重 subscribeOn：**

```java
// OrchestratorImpl.execute() 外层
return Flux.defer(() -> {
    Flux<ServerSentEvent<String>> pipelineFlux = Flux.create(sink -> { ... });
    Flux<ServerSentEvent<String>> respondFlux = ...;
    return pipelineFlux.concatWith(respondFlux);
}).subscribeOn(Schedulers.boundedElastic());       // ← 外层

// OkHttpModelApiClient.postStream() 内层
return Flux.<String, StreamContext>generate(...)
    .subscribeOn(Schedulers.boundedElastic());     // ← 内层
```

外层 `subscribeOn` 确保整个 orchestration（Feign 调用、状态管理等）在 boundedElastic
线程池中执行，不阻塞 Netty 的 event loop 线程。
内层 `subscribeOn` 确保 OkHttp 的阻塞 `readLine()` 也在 boundedElastic 线程池中。

这种"双重保险"不会创建多余的线程——如果已经在 boundedElastic 线程中，React 不会
再做切换。

#### `flux.onErrorResume(fn)` —— 错误恢复

**功能：** 当上游发生错误时，切换到备用的 Flux 继续执行。

```
正常路径：
  Flux1:  ──A──B──C──|
                      onErrorResume 不触发

错误路径：
  Flux1:  ──A──B──X (error!)
                   │
                   ▼
          onErrorResume(err -> Flux2)
                   │
  Flux2:           ──D──E──F──|
```

**LyClaw 中的使用：**

```java
return Flux.just(sseEvent("respond_start", "Generating AI response"))
    .concatWith(bodyFlux)
    .concatWith(buildTailFlux(...))
    .onErrorResume(err -> {
        // LLM 调用失败时的降级处理
        log.error("[Orchestrator] LLM call failed: {}", err.getMessage());
        String fallback = buildFinalResponse(sc, fc, toolResults, report);
        return Flux.just(
            sseEvent("message", fallback),
            sseEvent("done", "{\"status\":\"completed\",\"fallback\":true}")
        );
    });
```

**`onErrorResume` vs `onErrorReturn`：**

```java
// onErrorResume —— 动态生成 fallback（可以访问异常信息）
flux.onErrorResume(err -> {
    log.error("Error: {}", err.getMessage());
    return Flux.just("Fallback: " + err.getMessage());
});

// onErrorReturn —— 返回固定 fallback 值
flux.onErrorReturn("Something went wrong");
```

---

### 2.4 调度器(Schedulers)专题

#### 什么是 Reactor Scheduler？

在 Reactor 中，Scheduler 是一个执行上下文——它决定了操作在哪个线程（或线程池）上
运行。可以把它理解为对 `ExecutorService` 的 Reactor 风格封装。

```java
// Java 标准线程池
ExecutorService pool = Executors.newFixedThreadPool(4);

// Reactor Scheduler（对 ExecutorService 的封装）
Scheduler scheduler = Schedulers.fromExecutor(pool);
```

Reactor 提供了几个内置的 Scheduler（每个都是懒初始化的单例）：

```
Schedulers 家族：
┌──────────────────────────┬───────────────────────────────────────┐
│ Scheduler 类型            │ 适用场景                              │
├──────────────────────────┼───────────────────────────────────────┤
│ Schedulers.immediate()    │ 在当前线程立即执行（纯粹同步）        │
│ Schedulers.single()       │ 单线程，适合状态共享的小任务          │
│ Schedulers.parallel()     │ 固定大小线程池，适合 CPU 密集型       │
│ Schedulers.boundedElastic()│ 弹性线程池，适合阻塞 IO             │
│ Schedulers.elastic()      │ 无限弹性线程池（已废弃，用 bounded）  │
└──────────────────────────┴───────────────────────────────────────┘
```

#### `Schedulers.boundedElastic()` —— 阻塞IO专用

这是 LyClaw SSE 流程中**最重要**的调度器。

**特性：**
- 线程数弹性伸缩（空闲时可缩减，繁忙时可扩展到上限）
- 默认上限：CPU 核心数 × 10（但至少 10 个）
- 空闲线程 60 秒后自动回收
- 线程名称：`boundedElastic-{N}`
- 队列上限：100,000 个任务（超过后抛出 `RejectedExecutionException`）

**为什么 SSE + OkHttp 需要 boundedElastic？**

核心问题在于 OkHttp 的 `readLine()` 是**阻塞（Blocking）** 操作：

```java
// 这段代码在哪个线程运行？
BufferedReader reader = ...;
String line = reader.readLine();  // ← 阻塞！等待网络数据
```

如果这段代码运行在 Netty 的 event loop 线程（`reactor-http-nio-{N}`）上，会发生：

```
Netty Event Loop（通常只有 CPU 核心数个线程）
    │
    ├─ 连接1: readLine() 阻塞中...（等待 LLM API 返回数据）
    ├─ 连接2: readLine() 阻塞中...（等待另一个 LLM API）
    └─ 连接3: 被连接1和2阻塞，无法处理！
               （所有 Netty 线程都被阻塞请求占据）
               （新的 SSE 连接无法建立）
               （系统看起来像死了一样）
```

`subscribeOn(Schedulers.boundedElastic())` 解决了这个问题：

```
Netty Event Loop（少量线程，只做非阻塞工作）
    │ HTTP 请求到达
    │ 创建响应 Flux
    │ 声明 subscribeOn(boundedElastic)
    │ 立即返回（不等待 LLM 调用完成）
    │
    ▼
boundedElastic 线程池（可扩展，用于阻塞 IO）
    ├─ 工作线程1: readLine() 阻塞中...（等待 LLM API1）
    ├─ 工作线程2: readLine() 阻塞中...（等待 LLM API2）
    ├─ 工作线程3: 执行流水线 Feign 调用...
    ├─ 工作线程4: 空闲
    └─ ... 可扩展到上百个线程
```

**LyClaw 中的使用位置：**

```java
// 位置1: OrchestratorImpl.execute() —— 编排流水线 + LLM 调用
return Flux.defer(() -> {
    // 流水线中的 feign 调用（HTTP 调用）是同步阻塞的
    return pipelineFlux.concatWith(respondFlux);
}).subscribeOn(Schedulers.boundedElastic());

// 位置2: OkHttpModelApiClient.postStream() —— OkHttp 的阻塞读取
return Flux.<String, StreamContext>generate(
    () -> { /* 创建 OkHttp 连接 */ },
    (ctx, sink) -> { /* 阻塞 readLine */ },
    StreamContext::close
).subscribeOn(Schedulers.boundedElastic());

// 位置3: OrchestrationController.chat() —— 非流式端点
return flux.collectList()
    .map(results -> { /* 聚合处理 */ })
    .subscribeOn(Schedulers.boundedElastic());
```

#### `Schedulers.parallel()` —— CPU密集型

**特性：**
- 固定大小线程池，大小 = CPU 核心数（`Runtime.getRuntime().availableProcessors()`）
- 线程名称：`parallel-{N}`
- 线程永不回收（创建后一直存在）
- 不支持阻塞操作（没有弹性扩展能力）

**适用场景：**
```java
// CPU 密集的计算转换
flux.map(this::expensiveTransformation)      // 适合 parallel
    .filter(this::complexFilter)             // 适合 parallel
    .flatMap(this::computeIntensiveWork)      // 适合 parallel
    .subscribeOn(Schedulers.parallel());
```

**LyClaw 中 currently not used 但可能需要的场景：**
- JSON 解析/序列化的复杂转换
- 模板渲染
- 内容过滤的复杂正则匹配

#### `subscribeOn` vs `publishOn` 的区别

这是 Reactor 学习者中最容易混淆的概念。两者的区别在于**影响范围**。

```
subscribeOn —— 影响订阅发生的线程（影响上游）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

整个上游链在指定线程中执行：
  Flux.create(sink -> {...})      ← 在 subscribeOn 线程
    .map(x -> transform(x))       ← 在 subscribeOn 线程
    .filter(pred)                 ← 在 subscribeOn 线程
    .subscribeOn(Scheduler1)      ← 声明在这里
    .subscribe(consumer);         ← 在调用线程（或最后一个 publishOn 线程）


publishOn —— 影响下游操作符执行的线程
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

publishOn 之后的操作符在指定线程中执行：
  Flux.range(1, 100)
    .map(x -> heavyCompute(x))    ← 在调用线程
    .publishOn(Scheduler2)        ← 从这里切换线程
    .map(x -> anotherOp(x))       ← 在 Scheduler2 线程
    .filter(pred)                 ← 在 Scheduler2 线程
    .publishOn(Scheduler3)        ← 再次切换
    .subscribe(consumer);         ← 在 Scheduler3 线程
```

**可视化对比：**

```
只有 subscribeOn：
┌─────────────────────────────────────────────────────┐
│  Thread: boundedElastic-1                           │
│  ┌────────────────────────────────────────────────┐ │
│  │ Flux.create → map → filter → subscribe()       │ │
│  └────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────┘

subscribeOn + publishOn：
┌──────────────────────┐  ┌──────────────────────────┐
│ boundedElastic-1     │  │ parallel-1                │
│ Flux.create → map    │  │ filter → subscribe()      │
│       ↑              │  │       ↑                    │
│  subscribeOn 影响    │  │  publishOn 影响            │
└──────────────────────┘  └──────────────────────────┘
```

**实用规则：**
- 整个链只在**一个**地方放 `subscribeOn`（通常在外层）
- 可以放多个 `publishOn` 来在链中间切换线程
- `subscribeOn` 不影响 `publishOn` 声明的线程切换点

#### 线程跳跃（Thread Hopping）在响应式链中的影响

"线程跳跃"指的是数据在响应式链中传递时，不同操作符在不同线程上执行的现象。

**subscribeOn 的上游效应：**

```java
Flux.create(sink -> {
    System.out.println("create: " + Thread.currentThread().getName());
    sink.next("A");
    sink.complete();
})
.map(x -> {
    System.out.println("map1: " + Thread.currentThread().getName());
    return x + "!";
})
.subscribeOn(Schedulers.boundedElastic())  // 影响上游
.map(x -> {
    System.out.println("map2: " + Thread.currentThread().getName());
    return "<<" + x + ">>";
})
.subscribe(x -> {
    System.out.println("subscribe: " + Thread.currentThread().getName());
});

// 输出：
// create: boundedElastic-1     ← subscribeOn 的上游影响
// map1: boundedElastic-1       ← subscribeOn 的上游影响
// map2: boundedElastic-1       ← 通常在同一个线程（无 publishOn 切换）
// subscribe: boundedElastic-1
```

**publishOn 的下游效应：**

```java
Flux.create(sink -> {
    System.out.println("create: " + Thread.currentThread().getName());
    sink.next("A");
    sink.complete();
})
.subscribeOn(Schedulers.boundedElastic())
.map(x -> {
    System.out.println("before publishOn: " + Thread.currentThread().getName());
    return x;
})
.publishOn(Schedulers.parallel())  // 从这里往下切换
.map(x -> {
    System.out.println("after publishOn: " + Thread.currentThread().getName());
    return x;
})
.subscribe(x -> {
    System.out.println("subscribe: " + Thread.currentThread().getName());
});

// 输出：
// create: boundedElastic-1
// before publishOn: boundedElastic-1
// after publishOn: parallel-1          ← 线程已切换
// subscribe: parallel-1
```

**LyClaw 线程模型总结：**

```
HTTP 请求到达
    │
    ▼
[Netty Event Loop] reactor-http-nio-{N}
    │ 接收请求，构建 Flux 管道描述
    │ 声明 subscribeOn(boundedElastic)
    │ 立即返回（不阻塞）
    │
    ▼
[boundedElastic 线程池] boundedElastic-{N}
    ├── OrchestratorImpl.execute()
    │   ├── Feign 调用 (HTTP → 其他微服务)      ← 同步阻塞
    │   ├── Flux.create() 发送进度事件           ← SSE 事件发射
    │   └── concatWith() 连接下一阶段
    │
    ├── ModelAdapter.chatStream()
    │   └── OkHttpModelApiClient.postStream()
    │       ├── Flux.generate()                 ← 同步阻塞读行
    │       └── BufferedReader.readLine()        ← OkHttp 阻塞 IO
    │
    └── 数据写回 Netty 响应通道
         │
         ▼
[Netty Event Loop] reactor-http-nio-{N}
    │ 将字节写入 socket
    │ 异步 IO 完成
```

注意数据最终会**自动**回到 Netty Event Loop 进行网络写入——这是 Reactor Netty 的
内部机制，不需要开发者操心。

---

### 附录：LyClaw SSE 全链路数据流图

```
┌──────────────────────────────────────────────────────────────────────┐
│                          客户端（浏览器）                              │
│                                                                      │
│  fetch('/api/chat/stream', {                                         │
│    method: 'POST',                                                   │
│    body: JSON.stringify({ messages: [...], stream: true })           │
│  })                                                                   │
│    .then(res => readSSEStream(res.body.getReader()))                 │
│                                                                      │
└────────────────────────────┬─────────────────────────────────────────┘
                             │ HTTP POST
                             ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    Spring WebFlux + Netty                             │
│                                                                      │
│  OrchestrationController.chatStream()                                │
│    │                                                                 │
│    ├── produces: text/event-stream                                   │
│    ├── return type: Flux<ServerSentEvent<String>>                    │
│    │                                                                 │
│    └── orchestrator.execute(context)                                 │
│          │                                                            │
│          ▼                                                            │
│  OrchestratorImpl.execute()                                          │
│    Flux.defer(() -> {                                                │
│      ┌─────────────────────────────────────────────┐                │
│      │  pipelineFlux (Flux.create)                  │                │
│      │  ┌───────────────────────────────────────┐  │                │
│      │  │ Stage 1: CONTEXT_BUILD                │  │                │
│      │  │  - memoryFeignClient.retrieve()        │  │                │
│      │  │  - sseEvent("context_build_start")     │  │                │
│      │  ├───────────────────────────────────────┤  │                │
│      │  │ Stage 2: INTERCEPT                     │  │                │
│      │  │  - securityManager.approve()           │  │                │
│      │  │  - contentFilter.filter()              │  │                │
│      │  ├───────────────────────────────────────┤  │                │
│      │  │ Stage 3: PLAN                          │  │                │
│      │  │  - planFeignClient.plan()              │  │                │
│      │  │  - sseEvent("plan_start/complate")     │  │                │
│      │  ├───────────────────────────────────────┤  │                │
│      │  │ Stage 4: EXECUTE                       │  │                │
│      │  │  - actionFeignClient.executeTool()     │  │                │
│      │  │  - sseEvent("action_start/result")     │  │                │
│      │  ├───────────────────────────────────────┤  │                │
│      │  │ Stage 5: REFLECT                       │  │                │
│      │  │  - reflectFeignClient.reflect()        │  │                │
│      │  │  - sseEvent("reflect_complete")        │  │                │
│      │  └───────────────────────────────────────┘  │                │
│      │         concatWith                           │                │
│      │              ▼                               │                │
│      │  respondFlux (Flux.defer)                    │                │
│      │  ┌───────────────────────────────────────┐  │                │
│      │  │ Stage 6: RESPOND                       │  │                │
│      │  │  - adapter.chatStream(llmRequest)      │  │                │
│      │  │    └── OkHttpModelApiClient            │  │                │
│      │  │         .postStream()                   │  │                │
│      │  │         └── Flux.generate()            │  │                │
│      │  │              readLine() 逐行读取        │  │                │
│      │  │  - sseEvent("message", text)           │  │                │
│      │  ├───────────────────────────────────────┤  │                │
│      │  │ Stage 7: METRICS + DONE                │  │                │
│      │  │  - memoryFeignClient.ingest()          │  │                │
│      │  │  - sseEvent("metrics", "{...}")        │  │                │
│      │  │  - sseEvent("done", "{...}")           │  │                │
│      │  └───────────────────────────────────────┘  │                │
│      └─────────────────────────────────────────────┘                │
│    }).subscribeOn(Schedulers.boundedElastic())                       │
│                                                                      │
│  Spring 自动: Flux<ServerSentEvent> → text/event-stream 字节         │
│  通过 ServerSentEventHttpMessageWriter                               │
│                                                                      │
└────────────────────────────┬─────────────────────────────────────────┘
                             │ SSE stream (text/event-stream)
                             │ Transfer-Encoding: chunked
                             ▼
┌──────────────────────────────────────────────────────────────────────┐
│                          客户端（浏览器）                              │
│                                                                      │
│  实时解析 SSE 事件流:                                                 │
│                                                                      │
│  event: context_build_start → 显示 "加载中..."                       │
│  event: plan_start          → 显示 "正在规划..."                     │
│  event: action_result       → 显示工具执行结果                        │
│  event: message             → 逐字追加 AI 回复                        │
│  event: done                → 关闭流，完成对话                        │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

> **文档版本：** 1.0
> **编写日期：** 2026-05-11
> **适用项目：** LyClaw (基于 Spring WebFlux + Reactor + OkHttp 的 AI 编排框架)


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


---

# 第六章：底层HTTP客户端 — OkHttp与Flux.generate()的桥接

## 6.1 为什么需要桥接？

在LyClaw的SSE架构中，存在一个根本性的技术矛盾：**OkHttp是同步阻塞的HTTP客户端，而Spring Reactor的响应式编程模型要求非阻塞的异步流**。这个矛盾必须通过一个精心设计的"桥接层"来解决。

### 6.1.1 OkHttp的阻塞本质

OkHttp 是一个经典的、被广泛使用的Java HTTP客户端。它的设计哲学是同步调用——当你调用 `response.body().byteStream()` 时，当前线程会**一直阻塞**，直到远程服务器返回数据。即使是在SSE流式场景下：

```java
// OkHttp的底层行为：byteStream()返回的是一个包装过的Socket InputStream
InputStream inputStream = response.body().byteStream();
int b = inputStream.read();  // ← 如果数据还没到，这个线程会一直卡在这里
```

每一个 `read()` 调用都可能阻塞几十毫秒甚至几秒（等待LLM生成下一个token）。在传统的Servlet容器（如Tomcat）中，每个请求占用一个线程，阻塞是可以接受的——反正这个线程就是专门为这个请求服务的。但在响应式编程的世界里，情况完全不同。

### 6.1.2 Reactor的事件循环线程是稀缺资源

Spring WebFlux 底层的 Netty 使用事件循环（Event Loop）模型。Netty 一般只创建**等于CPU核心数**的事件循环线程（通常只有4-8个）。这些线程是"黄金资源"——它们负责处理所有连接的I/O事件、HTTP解析、路由分发。

```
Netty Event Loop 线程模型（4核CPU为例）：
┌──────────────────────────────────────────────────┐
│  Event Loop Thread 1  ← 处理几百个连接的I/O事件   │
│  Event Loop Thread 2  ← 处理几百个连接的I/O事件   │
│  Event Loop Thread 3  ← 处理几百个连接的I/O事件   │
│  Event Loop Thread 4  ← 处理几百个连接的I/O事件   │
└──────────────────────────────────────────────────┘
```

如果有任何一个事件循环线程被阻塞（比如在等待LLM响应），那么分配到这个线程上的**所有其他连接**都将无法得到处理。这会导致整个服务的吞吐量急剧下降，甚至完全不可用。这被称为"事件循环线程饥饿"（Event Loop Thread Starvation）。

### 6.1.3 解决方案：Flux.generate() + subscribeOn(boundedElastic)

LyClaw的解决方案可以用一句话概括：**将阻塞的OkHttp调用包装在Flux.generate()中，然后将整个订阅链调度到boundedElastic线程池上执行**。

```
架构图：
┌─────────────────────────────────────────────────────────┐
│  调用者（事件循环线程）                                    │
│  adapter.chatStream(request) → Flux<String>              │
│       │                                                  │
│       │  .subscribeOn(Schedulers.boundedElastic())       │
│       │   ↓ 立即返回控制权给事件循环                        │
│       ▼                                                  │
│  ┌─────────────────────────────────────────────┐        │
│  │  boundedElastic 线程池中的工作线程             │        │
│  │  ┌───────────────────────────────────┐      │        │
│  │  │  Flux.generate()                   │      │        │
│  │  │  ┌─────────────────────────────┐  │      │        │
│  │  │  │  状态初始化:                  │  │      │        │
│  │  │  │  httpClient.newCall(req)     │  │      │        │
│  │  │  │      .execute()  ← 阻塞       │  │      │        │
│  │  │  │  response.body().byteStream()│  │      │        │
│  │  │  │  → BufferedReader            │  │      │        │
│  │  │  └─────────────────────────────┘  │      │        │
│  │  │  ┌─────────────────────────────┐  │      │        │
│  │  │  │  逐元素生成:                  │  │      │        │
│  │  │  │  reader.readLine() ← 阻塞     │  │      │        │
│  │  │  │  → sink.next(line)           │  │      │        │
│  │  │  └─────────────────────────────┘  │      │        │
│  │  │  ┌─────────────────────────────┐  │      │        │
│  │  │  │  资源清理:                    │  │      │        │
│  │  │  │  reader.close()             │  │      │        │
│  │  │  │  body.close()               │  │      │        │
│  │  │  │  response.close()           │  │      │        │
│  │  │  └─────────────────────────────┘  │      │        │
│  │  └───────────────────────────────────┘      │        │
│  └─────────────────────────────────────────────┘        │
└─────────────────────────────────────────────────────────┘
```

关键点：`subscribeOn()` 不改变数据的流动方向，它只改变**订阅发生时所在的线程**。当调用者调用 `.subscribe()` 时，整个上游的 `Flux.generate()` 以及其中的阻塞OkHttp调用都会被调度到boundedElastic线程池上执行，而调用者的线程（通常是事件循环线程）立即返回，继续处理其他请求。

---

## 6.2 postStream()方法逐行拆解

`postStream()` 是整个SSE数据流的起点。它的完整签名和实现如下：

```java
@Override
public Flux<String> postStream(String url, Map<String, String> headers, String body) {
    Request request = buildRequest(url, headers, body);
    log.debug("发送流式请求: POST {}", url);

    return Flux.<String, StreamContext>generate(
            () -> {
                try {
                    Response response = httpClient.newCall(request).execute();

                    if (!response.isSuccessful()) {
                        String errorBody = response.body() != null
                                ? response.body().string() : "";
                        response.close();
                        throw parseHttpError(response.code(), errorBody, url);
                    }

                    ResponseBody responseBody = response.body();
                    if (responseBody == null) {
                        response.close();
                        throw ModelException.of(ErrorCode.MODEL_RESPONSE_PARSE_ERROR,
                                "响应体为空");
                    }

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(responseBody.byteStream()));
                    return new StreamContext(response, responseBody, reader);
                } catch (IOException e) {
                    throw ModelException.of(ErrorCode.MODEL_API_ERROR,
                            "流式请求失败: " + e.getMessage());
                }
            },
            (ctx, sink) -> {
                try {
                    String line;
                    while ((line = ctx.reader.readLine()) != null) {
                        if (!line.isEmpty()) {
                            sink.next(line);
                            return ctx;
                        }
                    }
                    ctx.close();
                    sink.complete();
                } catch (IOException e) {
                    ctx.close();
                    sink.error(ModelException.of(ErrorCode.MODEL_API_ERROR,
                            "流式读取失败: " + e.getMessage()));
                }
                return ctx;
            },
            StreamContext::close
    ).subscribeOn(Schedulers.boundedElastic());
}
```

### 6.2.1 Flux.generate()的三个参数

在深入每个参数的实现之前，必须先理解 `Flux.generate()` 的设计哲学。`Flux.generate()` 是 Reactor 提供的一个**拉取式（pull-based）**流生成器。它的工作机制如下：

**生产者-消费者协作模型：**

```
Reactor 订阅者（下游）                   Flux.generate() 生成器（上游）
        │                                        │
        │  request(1)  ─────────────────────▶    │
        │  （"给我一个元素"）                       │
        │                                        │  调用 generator函数
        │                                        │  读取一行SSE数据
        │                                        │  sink.next(line)
        │  ◀─────────────────────  emit(line)    │
        │  处理这个元素...                         │
        │                                        │
        │  request(1)  ─────────────────────▶    │
        │  （"再给我一个"）                         │
        │                                        │  调用 generator函数
        │                                        │  读取下一行...
        │  ◀─────────────────────  emit(line2)   │
        │  处理这个元素...                         │
        ...                                      ...
```

`Flux.generate()` 有3个参数和2个泛型类型参数：

**泛型类型参数：**
- `<String, StreamContext>` 中的 `String` 是**发射类型**（Emitted Type）——每一行SSE数据就是一个String。下游订阅者看到的 `Flux<String>` 正是这个类型。
- `<String, StreamContext>` 中的 `StreamContext` 是**状态类型**（State Type）——在整个流的生命周期中，生成器函数需要维护一个可变的状态对象。对于OkHttp来说，这个状态就是HTTP连接相关的资源（Response、ResponseBody、BufferedReader）。

**三个函数参数：**

| 参数 | Java类型 | 作用 | 调用时机 |
|------|----------|------|----------|
| 第1参数 | `Callable<StreamContext>` | 状态初始化器 | 订阅发生时，只调用一次 |
| 第2参数 | `BiFunction<StreamContext, SynchronousSink<String>, StreamContext>` | 逐元素生成器 | 每次下游请求一个元素时调用一次 |
| 第3参数 | `Consumer<StreamContext>` | 状态清理器 | 流终止时（正常完成、出错、取消）调用一次 |

`Flux.generate()` 的核心约束是：**生成器函数每次调用只能发射0个或1个元素**。这保证了完美的背压（backpressure）——上游永远不会比下游处理得更快。

### 6.2.2 状态初始化回调（第1参数）—— 建立HTTP连接

```java
() -> {
    try {
        Response response = httpClient.newCall(request).execute();

        if (!response.isSuccessful()) {
            String errorBody = response.body() != null
                    ? response.body().string() : "";
            response.close();
            throw parseHttpError(response.code(), errorBody, url);
        }

        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            response.close();
            throw ModelException.of(ErrorCode.MODEL_RESPONSE_PARSE_ERROR,
                    "响应体为空");
        }

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(responseBody.byteStream()));
        return new StreamContext(response, responseBody, reader);
    } catch (IOException e) {
        throw ModelException.of(ErrorCode.MODEL_API_ERROR,
                "流式请求失败: " + e.getMessage());
    }
}
```

**逐行解释：**

**`Response response = httpClient.newCall(request).execute();`**

这是整个流式请求中第一次也是最重要的一次阻塞调用。我们来拆解这个链条：

1. `httpClient` — 在构造函数中创建的 `OkHttpClient` 实例，配置了300秒的超时
2. `.newCall(request)` — 将 `Request` 对象包装成一个 `Call` 对象。`Call` 是 OkHttp 对一次HTTP请求-响应交互的抽象。这一步**不执行**任何网络操作，只是准备好执行计划。
3. `.execute()` — **同步执行**HTTP请求。当前线程会阻塞在这里，直到：
   - DNS解析完成
   - TCP三次握手完成
   - TLS握手完成（如果是HTTPS）
   - HTTP请求头发送完毕
   - HTTP响应头接收完毕

关键理解：`.execute()` 在接收到响应头之后就会返回，**不会等待响应体全部传输完成**。对于SSE流式传输来说，响应头包含了 `Content-Type: text/event-stream`，表明这是一个持续的流。`.execute()` 返回后，响应体（ResponseBody）的字节流仍然处于打开状态，后续数据会不断到达。

**`if (!response.isSuccessful()) { ... throw ... }`**

`response.isSuccessful()` 检查HTTP状态码是否在200-299范围内。如果不是2xx，说明请求失败了。此时的处理逻辑：

1. `response.body().string()` — 读取错误响应体中的文本内容（如API返回的 `{"error": "invalid api key"}`）
2. `response.close()` — 立即关闭响应，释放连接资源。注意这里使用的是 `string()` 而不是 `byteStream()`，因为 `string()` 会主动将响应体全部读入内存然后关闭流，而 `byteStream()` 会让流保持打开状态。
3. `parseHttpError(response.code(), errorBody, url)` — 根据HTTP状态码抛出对应类型的异常：
   - 401 → `MODEL_API_INVALID_KEY`（API Key无效）
   - 403 → `MODEL_API_FORBIDDEN`（无权限）
   - 429 → `MODEL_API_RATE_LIMITED`（速率限制）
   - 5xx → `MODEL_API_ERROR`（服务器错误）
   - 其他 → 带原始响应体的通用错误

**`ResponseBody responseBody = response.body();`**

获取响应体对象。注意在OkHttp中，`response.body()` 永远不会返回null（OkHttp 4.x的行为），但代码仍然做了防御性检查。

**`if (responseBody == null) { response.close(); throw ... }`**

这是一个防御性编程的安全检查。虽然理论上不会发生，但如果响应体确实为空（比如HTTP 204 No Content），就没有流可以读取，需要立即关闭响应并抛出异常。

**`BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody.byteStream()));`**

这一行创建了从字节到字符的转换管道，有三个层次：

```
responseBody.byteStream()
    ↓ 返回 InputStream（字节流）
InputStreamReader
    ↓ 将字节解码为字符（使用默认字符集 UTF-8）
BufferedReader
    ↓ 添加内部缓冲区（默认8KB），提供 readLine() 方法
```

1. `responseBody.byteStream()` — 返回响应体的原始字节输入流。这是一个 `BufferedSource`（OkHttp的内部类型，包装了Socket的InputStream），从中可以读取服务器持续发送的SSE字节数据。

2. `new InputStreamReader(byteStream)` — Java标准库的桥接类。它将底层的字节流包装成字符流。默认使用JVM的默认字符集（通常是UTF-8）。SSE协议规定数据必须是UTF-8编码的。

3. `new BufferedReader(reader)` — 在 `InputStreamReader` 之上再添加一层缓冲区。`BufferedReader` 的默认缓冲区大小是8192字节（8KB）。它提供了 `readLine()` 方法，可以高效地按行读取数据——这正是SSE解析所需要的（SSE协议中每个事件字段占一行）。

**`return new StreamContext(response, responseBody, reader);`**

将三个需要生命周期管理的对象（Response、ResponseBody、BufferedReader）打包成一个 `StreamContext` 对象返回。`StreamContext` 将成为整个流生成过程中的状态对象。

**异常处理：`catch (IOException e) { throw ModelException ... }`**

如果初始化过程中发生任何I/O错误（DNS解析失败、连接超时、SSL握手失败等），将IOException包装为LyClaw统一异常模型的 `ModelException` 并抛出。这个异常会被Reactor捕获，触发流的 `onError` 信号。

### 6.2.3 逐元素生成器（第2参数）—— 逐行读取SSE数据

```java
(ctx, sink) -> {
    try {
        String line;
        while ((line = ctx.reader.readLine()) != null) {
            if (!line.isEmpty()) {
                sink.next(line);
                return ctx;
            }
        }
        ctx.close();
        sink.complete();
    } catch (IOException e) {
        ctx.close();
        sink.error(ModelException.of(ErrorCode.MODEL_API_ERROR,
                "流式读取失败: " + e.getMessage()));
    }
    return ctx;
}
```

这是一个 `BiFunction<StreamContext, SynchronousSink<String>, StreamContext>`。每次下游请求一个元素时，这个函数被调用一次。

**函数签名分析：**
- 输入1：`ctx` — 当前的 `StreamContext` 状态对象（包含打开的Reader和连接）
- 输入2：`sink` — `SynchronousSink<String>`，用于向下游发射数据或信号
- 返回值：`StreamContext` — 更新后的状态对象（在LyClaw的实现中，状态对象不变，直接返回原对象）

**核心逻辑：**

```java
String line;
while ((line = ctx.reader.readLine()) != null) {
    if (!line.isEmpty()) {
        sink.next(line);
        return ctx;
    }
}
```

让我们逐句解析这段代码：

**`String line;`**

声明一个局部变量，用于保存每次 `readLine()` 读取到的一行文本。

**`while ((line = ctx.reader.readLine()) != null)`**

这是一个标准的Java I/O读取循环。`BufferedReader.readLine()` 方法的行为如下：

1. 从底层输入流中读取字节
2. 将字节解码为字符（UTF-8）
3. 在内部缓冲区中查找换行符（`\n`、`\r` 或 `\r\n`）
4. 返回换行符之前的所有字符（不包含换行符本身）
5. 如果到达流的末尾（没有更多数据），返回 `null`

**重点**：`readLine()` 是**阻塞调用**。如果服务器还没有发送下一行数据（LLM正在生成下一个token），当前线程会一直阻塞等待。这就是为什么必须将这个操作调度到 boundedElastic 线程池上——在这个线程池中，阻塞是安全且预期内的行为。

**`if (!line.isEmpty())`**

SSE协议规定事件之间用空行分隔。具体来说：

```
data: {"choices":[{"delta":{"content":"你"}}]}   ← 这是有效数据，非空行
                                                   ← 这是空行，表示一个事件结束
data: {"choices":[{"delta":{"content":"好"}}]}   ← 这是下一个事件
                                                   ← 又是空行
```

空行在SSE协议中充当事件边界分隔符。LyClaw的流式处理设计为：**跳过空行，只向下游发射包含实际数据的行**。每个非空行就是一个完整的SSE数据字段（通常是 `data:` 开头的一行）。

为什么用 `while` 循环而不是 `if` 判断？考虑以下场景：

```
data: {"delta":"你"}
                    ← 空行1
                    ← 空行2（连续空行）
                    ← 空行3
data: {"delta":"好"}
```

使用 `while` 循环可以**连续跳过多个空行**，在一次生成器调用中完成。如果只用 `if`，每次遇到空行就要等到下游下一次请求才能继续读取——这虽然功能正确，但效率较低。

**`sink.next(line);`**

向 `SynchronousSink` 发送一个数据元素。`SynchronousSink.next()` 的行为：
- 将 `line`（一行SSE文本）推送到下游的订阅者
- 下游订阅者处理完后，才会再次调用本生成器函数获取下一个元素
- 这就是**背压**（backpressure）的实现机制——上游不会比下游快

**`return ctx;`**

返回 `StreamContext` 状态对象。在Reactor的 `generate()` 机制中，`ctx` 在每次生成器调用之间保持不变（除非你返回一个新对象）。这个返回值会成为下一次生成器调用的第一个参数。

**流结束处理：**

```java
ctx.close();
sink.complete();
```

当 `readLine()` 返回 `null` 时，表示HTTP响应流已经关闭（服务器完成了所有数据的发送）。此时需要：
1. `ctx.close()` — 关闭所有底层资源（BufferedReader、ResponseBody、Response）
2. `sink.complete()` — 通知下游订阅者流已经完成，触发订阅者的 `onComplete` 回调

**异常处理：**

```java
catch (IOException e) {
    ctx.close();
    sink.error(ModelException.of(ErrorCode.MODEL_API_ERROR,
            "流式读取失败: " + e.getMessage()));
}
```

如果在读取过程中发生IOException（比如连接被中断、SSL错误），需要：
1. `ctx.close()` — 立即关闭所有资源，防止资源泄漏
2. `sink.error(...)` — 通知下游订阅者发生错误，触发订阅者的 `onError` 回调

### 6.2.4 资源清理回调（第3参数）—— 安全关闭三层资源

```java
StreamContext::close
```

使用方法引用（method reference）的语法，等价于：

```java
ctx -> ctx.close()
```

这个清理回调在**所有**终止场景下都会被调用：
- **正常完成**：流自然结束，`sink.complete()` 被调用后
- **异常终止**：流中发生错误，`sink.error()` 被调用后
- **取消订阅**：下游订阅者主动取消订阅（比如前端关闭了连接）

这个回调作为最后一层安全保障，确保即使前面的代码没有正确调用 `ctx.close()`，资源也会被释放。

---

## 6.3 StreamContext内部类

```java
private static class StreamContext {
    final Response response;
    final ResponseBody body;
    final BufferedReader reader;

    StreamContext(Response response, ResponseBody body, BufferedReader reader) {
        this.response = response;
        this.body = body;
        this.reader = reader;
    }

    void close() {
        try { reader.close(); } catch (IOException ignored) {}
        try { body.close(); } catch (Exception ignored) {}
        try { response.close(); } catch (Exception ignored) {}
    }
}
```

### 6.3.1 为什么是手工编写的类而不是Java Record？

用户大纲中提到 `record`，但实际上源码中 `StreamContext` 是一个普通的 `private static class`（不是record）。使用普通类而非record的原因可能是为了兼容性或简单的编码习惯。两者的区别：

| 特性 | 普通类 | Record (Java 14+) |
|------|--------|-------------------|
| 字段 | 需要显式声明 | 自动从构造参数推导 |
| getter | 手动写或lombok | 自动生成 `field()` |
| equals/hashCode | 继承自Object | 自动生成（基于所有字段） |
| toString | 继承自Object | 自动生成 |
| 可变性 | 字段可设为final | 不可变 |
| close()方法 | 需要手动编写 | 可以额外添加方法 |

### 6.3.2 三个字段的生命周期依赖关系

```
Response (最外层)
  └── ResponseBody (中间层，由Response管理)
        └── BufferedReader (最里层，包装了ResponseBody的字节流)
```

关闭顺序是从内到外：先关闭 Reader（停止读取），然后关闭 ResponseBody（释放响应体资源），最后关闭 Response（释放HTTP连接）。

如果先关闭 Response，而 Reader 还在尝试读取 → 会触发 IOException。所以必须**先关内层再关外层**。

### 6.3.3 close()方法的防御性设计

```java
void close() {
    try { reader.close(); } catch (IOException ignored) {}
    try { body.close(); } catch (Exception ignored) {}
    try { response.close(); } catch (Exception ignored) {}
}
```

这个方法的三个 `close()` 调用是**独立 try-catch 的**（不是放在一个大 try-catch 中），这是一个精心设计的防御性编程实践。

**为什么每个close独立try-catch？**

```
// 错误的写法（一个try-catch管所有）：
try {
    reader.close();
    body.close();
    response.close();
} catch (Exception e) {
    // reader.close()失败 → body和response永远不会关闭 → 资源泄漏
}

// 正确的写法（每个独立）：
try { reader.close(); } catch ... {}     // 即使失败也不影响下面
try { body.close(); } catch ... {}      // 独立执行
try { response.close(); } catch ... {}  // 独立执行
```

如果第一个 `close()` 抛出异常而使用单个 try-catch，后面的 `close()` 将不会执行，导致连接泄漏。独立 try-catch 确保：**一个资源的关闭失败不会阻止其他资源的关闭**。

**为什么异常被吞掉（ignored）？**

1. 关闭时发生的异常通常是次要的（比如远端已经断开连接，本地关闭时触发 "connection reset"）
2. 在流的终止阶段（特别是错误恢复路径），没有下游订阅者来处理这些异常了
3. 即使关闭失败，底层Socket最终也会被GC回收或超时释放
4. 在生产环境中，建议至少加上日志记录（虽然当前代码中是空的 `catch` 块）

### 6.3.4 单一职责原则

`StreamContext` 完美体现了单一职责原则：它的唯一职责就是**持有三个需要关闭的资源对象，并提供安全关闭它们的方法**。它不涉及HTTP协议、不涉及SSE解析、不涉及任何业务逻辑。这种简单的设计使得：
- 测试非常容易（只需模拟三个资源对象）
- 代码意图非常清晰
- 不容易出错

---

## 6.4 Flux.generate() vs Flux.create() 对比

LyClaw选择 `Flux.generate()` 而不是 `Flux.create()` 是一个经过深思熟虑的架构决策。理解两者的区别对于理解整个系统的数据流至关重要。

| 维度 | Flux.generate() | Flux.create() |
|------|----------------|---------------|
| **发射模型** | 拉取式（Pull）——下游请求，上游才生产 | 推送式（Push）——上游随时发射，下游被动接收 |
| **线程模型** | 单一生产者线程（串行发射） | 可以从多个线程发射（并发） |
| **背压处理** | 内置——每次调用只发射1个元素 | 需要手动配置 OverflowStrategy |
| **状态管理** | 内置状态对象，自动传递 | 需要通过闭包/外部变量手动管理 |
| **典型场景** | 包装同步/阻塞来源（IO、JDBC） | 包装异步回调、事件监听器 |
| **发射灵活性** | 严格限制每次最多1个元素 | 不限次数，可以burst发射多个 |
| **在LyClaw中的使用** | OkHttpModelApiClient.postStream() | ToolCallLoopStage.buildToolEventFlux() |

### 6.4.1 为什么LyClaw在这里不能用Flux.create()？

`Flux.create()` 的典型用法是将异步回调转化为响应式流：

```java
Flux.create(sink -> {
    // 注册一个异步回调
    someAsyncService.onData(data -> sink.next(data));
    someAsyncService.onError(err -> sink.error(err));
    someAsyncService.onComplete(() -> sink.complete());
});
```

但是OkHttp的 `BufferedReader.readLine()` 不是一个异步回调——它是一个**同步阻塞调用**。使用 `Flux.create()` 会导致：
1. 调用 `readLine()` 的代码会在 `sink.next()` 之间阻塞
2. 反压策略难以配置（应该用 `BUFFER` 还是 `DROP` 还是 `LATEST`？）
3. 代码结构不自然（需要在回调里写读取循环）

而 `Flux.generate()` 的拉取式模型与 `readLine()` 的"一次读一行"模式天然匹配：
- "读一行 → 发射一行 → 等下游 → 再读一行"正是 generate 的默认行为

### 6.4.2 在LyClaw中Flux.create()的正确使用场景

注意在 `ToolCallLoopStage.buildToolEventFlux()` 中，LyClaw使用了 `Flux.create()`：

```java
return Flux.create((Consumer<FluxSink<String>>) sink -> {
    for (ModelResponse.ToolCallRequest req : calls) {
        sink.next("data: " + json + "\n\n");  // 快速发射，不需要等待
    }
    for (ModelResponse.ToolCallRequest req : calls) {
        sink.next("data: " + json + "\n\n");  // 快速发射
    }
    sink.complete();
});
```

这里使用 `Flux.create()` 是合适的，因为：
- 数据已经在内存中（不需要等待I/O）
- 可以快速连续发射多个元素
- 不需要状态维护
- 不需要拉取式控制

---

## 6.5 subscribeOn(boundedElastic)的深入理解

```java
return Flux.<String, StreamContext>generate(...)
    .subscribeOn(Schedulers.boundedElastic());
```

这是整个桥接方案中最关键的一行。`subscribeOn()` 虽然只是简单的一个方法调用，但它深刻地改变了整个数据流的执行模型。

### 6.5.1 subscribeOn的语义

`subscribeOn(Scheduler)` 的含义是：**当有人订阅这个Flux时，订阅动作（subscription）以及整个上游数据流的产生，都在指定的Scheduler上执行**。

```
调用者线程（可能是Netty事件循环线程）：
    │
    │  flux.subscribe(subscriber)
    │  ├─ 创建订阅对象
    │  └─ 提交一个任务到 boundedElastic 线程池
    │     │
    │     └─ 立即返回！调用者线程被释放
    │
    ▼ 控制权交还给事件循环
    ▼ 事件循环继续处理下一个I/O事件
    ▼ 事件循环不会被阻塞

boundedElastic线程池中的某个工作线程：
    │
    │  开始执行被提交的任务：
    │  ├─ 调用状态初始化函数（执行 httpClient.newCall().execute()，阻塞等待响应头）
    │  ├─ 等待下游的第一次 request(1)
    │  ├─ 调用生成器函数（执行 reader.readLine()，阻塞等待数据行）
    │  ├─ sink.next(line) → 数据流传给下游
    │  ├─ 等待下游的 request(1) （背压）
    │  ├─ 调用生成器函数（继续读取下一行）
    │  ...（循环）
    │  └─ 直到流结束，调用清理函数
    │
    ▼ boundedElastic线程被阻塞，但没关系——这个线程本来就是用于阻塞任务的
```

### 6.5.2 为什么是boundedElastic而不是其他Scheduler？

Reactor提供了多种Scheduler，每种都有不同的适用场景：

| Scheduler | 线程池类型 | 线程数 | 适用场景 |
|-----------|-----------|--------|----------|
| `Schedulers.immediate()` | 无（当前线程） | — | 测试、简单场景 |
| `Schedulers.single()` | 单一守护线程 | 1 | 需要严格顺序的低频任务 |
| `Schedulers.parallel()` | 固定大小（CPU核心数） | 4-16 | CPU密集型计算（加密、压缩、序列化） |
| `Schedulers.boundedElastic()` | 弹性（按需创建，有上限） | 最多10×核心数 | **阻塞I/O**（数据库、HTTP、文件） |
| `Schedulers.elastic()` | 弹性（无上限） | 无限制 | 已废弃——boundedElastic替代 |

**为什么不能用 `parallel()`？**

`parallel()` 的线程数等于CPU核心数（比如8个）。如果4个线程都在等待LLM响应（每个可能需要几十秒），就只剩下4个线程可以用于真正的CPU计算——这是巨大的资源浪费。更糟的是，如果有超过8个并发SSE请求，其他请求只能排队等待，导致超时。

**boundedElastic的设计哲学：**

boundedElastic专门为**会阻塞的I/O任务**设计：
- **弹性创建**：当所有线程都在阻塞时，可以创建新线程（确保任务不会排队等待）
- **有上限**：最多 `10 × CPU核心数`（防止无限创建线程导致OOM）
- **空闲回收**：空闲超过60秒的线程会被回收
- **队列缓冲**：当达到上限时，新任务会在队列中等待（默认最大100,000个任务）

对于LyClaw来说，这意味着最多可以同时处理 `10 × CPU核心数` 个并发SSE流请求。对于一个4核服务器来说，就是40个并发流——对于LLM的SSE流式场景来说已经绰绰有余。

### 6.5.3 subscribeOn和publishOn的区别

这是一个常见的混淆点：

```java
// subscribeOn：影响订阅阶段和上游数据产生
flux.subscribeOn(Schedulers.boundedElastic())
    .map(data -> heavyCpuWork(data))  // 这个map仍在boundedElastic线程上执行
    .publishOn(Schedulers.parallel()) // 切换线程
    .map(data -> moreCpuWork(data))   // 这个map在parallel线程上执行
    .subscribe();
```

- `subscribeOn` — 改变的是**数据源产生数据的线程**。调用链中只生效一次（离源头最近的那个）。
- `publishOn` — 改变的是**下游操作符执行的线程**。可以有多个，每个切换后续操作符的线程。

在LyClaw中，只用 `subscribeOn` 就足够了，因为：
- 阻塞操作（HTTP请求、readLine）在源头
- 后续的SSE解析、数据转换不需要专门的线程调度
- 下游的响应写入由WebFlux框架自动处理

---

## 6.6 超时配置

```java
public OkHttpModelApiClient() {
    this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)    // 300秒
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)       // 300秒
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)      // 300秒
            .build();
}
```

### 6.6.1 为什么是300秒？

300秒（5分钟）对于LLM的SSE流式响应来说是一个合理的值：

- **短响应场景**：简单的问题可能在几秒内就返回完整的SSE流——300秒几乎用不到
- **长响应场景**：复杂的推理任务（如分析大型代码库、生成长文档）可能需要几分钟。DeepSeek等模型在处理复杂推理时可能需要2-3分钟。
- **超时保护**：如果网络中断但TCP没有及时检测到（比如某些代理服务器），readTimeout保证连接不会永远hang在那里

### 6.6.2 三种超时的区别

OkHttp的三种超时分别控制HTTP请求不同阶段的等待时间：

```
connectTimeout(300秒)
    │
    │  TCP三次握手 + TLS握手（如果是HTTPS）
    │  如果300秒内无法完成连接 → SocketTimeoutException
    │
    ▼ 连接建立成功 ──────────────────────────────────────────
    │
    │  writeTimeout(300秒)
    │  发送请求头和请求体
    │  如果300秒内无法写完请求体 → SocketTimeoutException
    │
    ▼ 请求发送完毕 ──────────────────────────────────────────
    │
    │  readTimeout(300秒)
    │  等待并读取响应数据
    │  如果两次数据块之间间隔超过300秒 → SocketTimeoutException
    │  ★ 对于SSE流式传输，这是最重要的超时
    │
    ▼ 响应读取完毕 ──────────────────────────────────────────
```

对于SSE流式传输，**readTimeout是最关键的**。它的计时方式是：从上一次成功读取数据到下一次成功读取数据之间的间隔。如果LLM突然停止生成（比如模型崩溃），readTimeout会在300秒后触发，释放连接。

### 6.6.3 超时触发后会发生什么？

当 `readTimeout` 触发时：

1. OkHttp底层的Socket会抛出 `SocketTimeoutException`
2. `ctx.reader.readLine()` 会抛出 `IOException`（包装了底层的SocketTimeoutException）
3. 生成器函数的 `catch (IOException e)` 块被触发
4. `ctx.close()` 关闭所有资源
5. `sink.error(...)` 向下游发送错误信号
6. 下游订阅者收到 `onError`，可以优雅地处理超时（如返回错误响应给客户端）

---

## 6.7 buildRequest() —— 请求构建

虽然不是postStream()的核心，但 `buildRequest()` 也值得详细解释：

```java
private Request buildRequest(String url, Map<String, String> headers, String body) {
    if (url == null || url.isBlank()) {
        throw ModelException.of(ErrorCode.MODEL_INVALID_REQUEST, "URL 不能为空");
    }

    Request.Builder builder = new Request.Builder().url(url);

    if (headers != null) {
        headers.forEach(builder::addHeader);
    }

    RequestBody requestBody = body != null
            ? RequestBody.create(body, MediaType.parse("application/json"))
            : RequestBody.create("", MediaType.parse("application/json"));

    return builder.post(requestBody).build();
}
```

**`if (url == null || url.isBlank())`**

入参校验（fail-fast原则）。如果在请求构建阶段就发现参数无效，立即抛出异常，避免建立HTTP连接后再发现错误。`isBlank()` 检查null、空字符串和纯空白字符串。

**`headers.forEach(builder::addHeader)`**

方法引用语法，等价于 `headers.forEach((key, value) -> builder.addHeader(key, value))`。

使用 `addHeader` 而不是 `setHeader`：
- `addHeader(name, value)` — 追加一个请求头。如果有多个同名头，都会发送。
- `setHeader(name, value)` — 替换所有同名请求头。

对于SSE请求，通常不会有同名请求头冲突的问题，所以两者行为相同。

**`RequestBody.create(body, MediaType.parse("application/json"))`**

创建HTTP请求体。两个参数：
1. 请求体内容（JSON字符串）
2. Content-Type（`application/json`）

OkHttp会根据Content-Type自动设置 `Content-Type` 请求头。MediaType.parse() 会解析字符串（如 `"application/json; charset=utf-8"`），处理字符集信息。

**当body为null时**：创建一个空的JSON请求体（`""`），而不是跳过请求体。这是因为大多数LLM API（包括OpenAI和DeepSeek）的chat completions端点即使是流式请求也需要有请求体（至少包含 `{"stream": true}`）。

**`return builder.post(requestBody).build();`**

使用POST方法发送请求。`.build()` 触发最终的Request对象构建，OkHttp会在内部将URL、请求头、请求体整合为一个不可变的Request对象。

---

## 6.8 实际SSE数据流示例

为了直观地理解整个数据流，下面展示一个完整的中文LLM响应示例：

### 6.8.1 上游LLM API发送的原始字节流

DeepSeek/OpenAI API通过HTTP响应体持续发送的原始SSE数据：

```
data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"你"},"finish_reason":null}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"好"},"finish_reason":null}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"，"},"finish_reason":null}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"世"},"finish_reason":null}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"界"},"finish_reason":null}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]

```

每个chunk是一个JSON对象，字段含义：
- `id` — 本次请求的唯一标识
- `choices[0].delta.content` — LLM生成的一小段文本（"你"、"好"、"世"、"界"等）
- `choices[0].finish_reason` — `null` 表示还在生成，`"stop"` 表示生成完毕
- `data: [DONE]` — OpenAI/DeepSeek的特殊标记，表示SSE流结束

注意：JSON对象中可能包含换行符和特殊字符，但LyClaw的readLine()仍能正确解析，因为SSE协议保证每行是一个完整的JSON字符串（不会跨行）。

### 6.8.2 OkHttpModelApiClient.postStream()的输出（Flux<String>）

readLine()逐行读取后，空行被跳过，向下游发射的Flux内容如下：

```
第1个事件：→ "data: {\"id\":\"chatcmpl-xxx\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}"
第2个事件：→ "data: {\"id\":\"chatcmpl-xxx\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你\"},\"finish_reason\":null}]}"
第3个事件：→ "data: {\"id\":\"chatcmpl-xxx\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"好\"},\"finish_reason\":null}]}"
第4个事件：→ "data: {\"id\":\"chatcmpl-xxx\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"，\"},\"finish_reason\":null}]}"
第5个事件：→ "data: {\"id\":\"chatcmpl-xxx\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"世\"},\"finish_reason\":null}]}"
第6个事件：→ "data: {\"id\":\"chatcmpl-xxx\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"界\"},\"finish_reason\":null}]}"
第7个事件：→ "data: {\"id\":\"chatcmpl-xxx\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}"
第8个事件：→ "data: [DONE]"
```

注意：这些不是8个独立的订阅——而是一个Flux<String>中的8次连续的 `sink.next()` 调用。

### 6.8.3 DeepSeekOpenAIAdapter.extractSsePlainText()的解析输出

ModelAdapter层解析每个SSE行，提取 `choices[0].delta.content` 字段：

```
输入行                                                        提取的纯文本
"data: ...delta":{"role":"assistant","content":""}              ""      (role声明，无内容)
"data: ...delta":{"content":"你"}                                "你"
"data: ...delta":{"content":"好"}                                "好"
"data: ...delta":{"content":"，"}                                "，"
"data: ...delta":{"content":"世"}                                "世"
"data: ...delta":{"content":"界"}                                "界"
"data: ...delta":{},"finish_reason":"stop"}"                     ""      (finish，无内容)
"data: [DONE]"                                                  ""      (终止标记)
```

累积后得到完整响应：`"你好，世界"`

### 6.8.4 OrchestratorImpl的处理流程

OrchestratorImpl（在之前章节中介绍过的5阶段管道）将解析后的文本包装成SSE事件，发送给WebFlux框架：

```java
// OrchestratorImpl 中的处理：
String plainText = adapter.extractSsePlainText(line);
if (!plainText.isEmpty()) {
    sseSink.tryEmitNext("event:message\ndata:" + plainText + "\n\n");
}
```

最终发送给浏览器的SSE格式：
```
event:message
data:你

event:message
data:好

event:message
data:，

event:message
data:世

event:message
data:界

```

### 6.8.5 完整的数据流总结

```
DeepSeek API 服务器
    │  TCP字节流（UTF-8编码的SSE协议）
    ▼
OkHttp Socket InputStream
    │  byteStream()
    ▼
InputStreamReader (字节→字符转换，UTF-8解码)
    │
    ▼
BufferedReader (缓冲 + readLine())
    │  Flux.generate() 逐行读取
    ▼
Flux<String> (每一行是一个String元素)
    │  emit: "data: {\"choices\":[...]}"
    ▼
ModelAdapter (SSE解析)
    │  extractSsePlainText(): JSON解析 → 提取delta.content
    ▼
OrchestratorImpl (管道处理)
    │  包装为SSE格式
    ▼
ServerSentEvent (Spring WebFlux)
    │  HTTP响应流
    ▼
浏览器 EventSource
    │  onmessage 事件
    ▼
用户看到: "你好，世界"
```

---

# 第七章：ToolCallLoopStage — 二次SSE路径与工具调用循环

## 7.1 在管道中的位置

### 7.1.1 PipelineStage接口

`ToolCallLoopStage` 实现了 `PipelineStage` 接口，这是LyClaw编排引擎管道体系的核心抽象：

```java
@Component
public class ToolCallLoopStage implements PipelineStage {

    @Override
    public int getOrder() {
        return 2;
    }

    @Override
    public String getStageName() {
        return "ToolCallLoop";
    }

    @Override
    public void process(ChatContext context, Chain chain) {
        // ... 主逻辑 ...
        chain.next(context);  // 处理完后调用下一个stage
    }
}
```

`PipelineStage` 接口定义了三个关键元素：
- **`getOrder()`** — 返回阶段在管道中的执行顺序，数字越小越先执行。返回2。
- **`getStageName()`** — 返回阶段的人类可读名称，用于日志和调试。
- **`process(ChatContext, Chain)`** — 核心处理方法。接收上下文和链对象，处理完后调用 `chain.next(context)` 将控制权传递给下一个阶段。

### 7.1.2 在整体管道中的位置

LyClaw的编排引擎使用一个多阶段管道来处理每个请求。ToolCallLoopStage的 `getOrder()` 返回2，和管道中其他阶段的顺序关系如下：

```
用户请求
    │
    ▼
┌─────────────────────────────────────────────────────┐
│  Stage 0: ContextInitStage                          │
│  初始化ChatContext，设置requestId、sessionId等        │
├─────────────────────────────────────────────────────┤
│  Stage 1: ModelSelectionStage                       │
│  根据配置选择合适的LLM模型和Adapter                   │
├─────────────────────────────────────────────────────┤
│  Stage 2: ToolCallLoopStage  ← 本章详解              │
│  执行工具调用循环：LLM决定调用工具 → 执行工具 →        │
│  结果反馈给LLM → LLM可能再次调用工具 → ...             │
├─────────────────────────────────────────────────────┤
│  Stage 3: FinalResponseStage (在OrchestratorImpl中)   │
│  构建最终响应，发送给客户端                            │
└─────────────────────────────────────────────────────┘
    │
    ▼
客户端收到响应
```

ToolCallLoopStage在 `ModelSelectionStage`（选定模型后）和 `FinalResponseStage`（返回最终结果前）之间运行。它的核心职责是：**当LLM决定调用工具时，在一个循环中执行工具并收集结果，直到LLM不再要求调用工具，或达到最大轮次**。

### 7.1.3 为什么需要工具调用循环？

现代LLM（包括DeepSeek）支持Function Calling（函数调用）能力。LLM可以"决定"调用你定义的工具来获取额外信息，而不是仅凭训练数据回答。一个典型的工具调用循环如下：

```
第1轮：用户问 "今天北京天气怎么样？"
    → LLM: "我需要调用 get_weather 工具" [tool_call: get_weather("北京")]
    → 系统执行 get_weather("北京") → 返回: "晴，25°C"
    → 将结果反馈给LLM

第2轮：LLM收到工具执行结果
    → LLM: "今天北京天气晴朗，气温25°C，适合户外活动。" [不再调用工具]
    → 循环结束，返回最终答案
```

ToolCallLoopStage就是实现这个"多轮"机制的核心组件。

---

## 7.2 依赖注入与构造函数

```java
private static final int MAX_ROUNDS = 6;
private static final long STREAM_COMPLETION_TIMEOUT_MS = 90_000;

private final ModelProvider modelProvider;
private final ToolRegistry toolRegistry;
private final ToolCallPolicy toolCallPolicy;
private final ActionFeignClient actionFeignClient;

public ToolCallLoopStage(ModelProvider modelProvider,
                         @org.springframework.lang.Nullable ToolRegistry toolRegistry,
                         @org.springframework.lang.Nullable ToolCallPolicy toolCallPolicy,
                         ActionFeignClient actionFeignClient) {
    this.modelProvider = modelProvider;
    this.toolRegistry = toolRegistry;
    this.toolCallPolicy = toolCallPolicy;
    this.actionFeignClient = actionFeignClient;
    log.info("[ToolCallLoopStage] Initialized: provider={}, toolRegistry={}",
            modelProvider.getClass().getSimpleName(),
            toolRegistry != null ? toolRegistry.getClass().getSimpleName() : "none");
}
```

**`MAX_ROUNDS = 6`**

最大工具调用轮次。这是一个安全阀——防止LLM陷入无限的工具调用循环（比如某个工具始终返回错误，LLM反复尝试调用）。6轮在大多数场景下足够（通常1-3轮就结束了）。

**`STREAM_COMPLETION_TIMEOUT_MS = 90_000`**

流式响应的最大等待时间（90秒）。如果LLM在90秒内没有完成整个SSE流，循环会超时退出。注意这个值与 `OkHttpModelApiClient` 中的 `TIMEOUT_SECONDS = 300`（300秒）是不同层次的超时：
- OkHttp的300秒：单次HTTP请求的超时（TCP层面）
- ToolCallLoopStage的90秒：单轮工具调用循环中等待LLM完整响应的时间（业务层面）

**`@org.springframework.lang.Nullable` 注解**

`toolRegistry` 和 `toolCallPolicy` 被标注为可空依赖。这意味着LyClaw可以在没有配置工具注册表的情况下运行（只是不会执行工具调用）。`actionFeignClient` 没有标注Nullable，说明远程工具调用客户端是必需的依赖（即使它可能实现为一个no-op）。

---

## 7.3 主流程process()方法 — 整体架构

```java
@Override
public void process(ChatContext context, Chain chain) {
    ModelAdapter adapter = modelProvider.getConfiguredAdapter();
    List<Message> messages = context.getRequest().getMessages();
    boolean isStream = context.getRequest().isStream();

    log.info("[ToolCallLoopStage] Entry, mode={}", isStream ? "streaming" : "sync");

    List<Flux<String>> allFluxes = new ArrayList<>(4);
    ChatResultHolder syncResult = new ChatResultHolder();

    int round = 0;
    while (round < MAX_ROUNDS) {
        // ... 每轮的处理逻辑（流式或同步） ...

        if (calls == null || calls.isEmpty()) {
            break;  // LLM没有要求调用工具，循环结束
        }

        Flux<String> eventFlux = executeTools(context, adapter, messages, calls, isStream);
        if (eventFlux != null) {
            allFluxes.add(eventFlux);
        }

        round++;
    }

    // 合并所有Flux，存入context供下游stage使用
    if (isStream) {
        Flux<String> merged = allFluxes.isEmpty() ? Flux.empty()
                : allFluxes.size() == 1 ? allFluxes.get(0)
                : Flux.concat(allFluxes);
        context.setAttribute("__stream_flux__", merged);
    }

    chain.next(context);
}
```

### 7.3.1 流程解析

**`ModelAdapter adapter = modelProvider.getConfiguredAdapter();`**

从 `ModelProvider` 中获取当前配置的模型适配器。`ModelProvider` 是之前管道阶段（ModelSelectionStage）选择的模型提供者。这个 `adapter` 是 `ModelAdapter` 接口的实现（如 `DeepSeekOpenAIAdapter`），封装了与具体LLM API的所有交互细节。

**`List<Message> messages = context.getRequest().getMessages();`**

从请求上下文中获取消息列表。这个列表包含用户消息、系统提示、以及历史对话。在工具调用循环中，这个列表会不断增长——每次LLM响应后（作为assistant消息）、每次工具执行后（作为tool消息），都会向列表中添加新消息。

**`boolean isStream = context.getRequest().isStream();`**

判断当前请求是否为流式模式。`true` 表示客户端期望SSE流式响应，`false` 表示客户端期望发送完整请求后一次性接收完整响应。ToolCallLoopStage对两种模式采用了不同的处理路径。

**`List<Flux<String>> allFluxes = new ArrayList<>(4);`**

收集所有轮次中产生的工具调用事件Flux。`4` 是初始容量（预估值）。在流式模式下，每个包含工具调用的轮次会产生一个事件Flux，最后通过 `Flux.concat()` 将它们串联成一个连续的SSE流。

**`while (round < MAX_ROUNDS)`**

主循环。每次迭代代表一轮"LLM生成 → 检查工具调用 → 执行工具"的循环。循环在以下情况退出：
1. LLM没有要求调用工具（自然结束）
2. 达到 `MAX_ROUNDS`（安全阀）
3. 流超时（`doneLatch.await()` 超时）
4. 线程被中断

---

## 7.4 流式模式下的单轮处理

这是ToolCallLoopStage最核心也最复杂的部分——在流式模式下处理一轮工具调用。

```java
if (isStream) {
    StringBuilder collector = new StringBuilder();

    @SuppressWarnings("unchecked")
    Sinks.Many<String> existingSink = (Sinks.Many<String>)
            context.getAttribute("__realtime_sink__");
    final Sinks.Many<String> realtimeSink = existingSink != null
            ? existingSink : Sinks.many().replay().all();
    Flux<String> realtimeFlux = realtimeSink.asFlux();
    context.setAttribute("__realtime_flux__", realtimeFlux);

    Flux<String> rawFlux = adapter.chatStream(context.getRequest());
    CountDownLatch doneLatch = new CountDownLatch(1);

    rawFlux.subscribe(
        chunk -> {
            collector.append(chunk).append('\n');
            realtimeSink.tryEmitNext(chunk);
        },
        error -> {
            log.error("[ToolCallLoopStage] Stream error: {}", error.getMessage());
            realtimeSink.tryEmitComplete();
            doneLatch.countDown();
        },
        () -> {
            realtimeSink.tryEmitComplete();
            String fullSSE = collector.toString();
            log.info("[ToolCallLoopStage] Stream complete, collected {} bytes",
                    fullSSE.length());

            List<ModelResponse.ToolCallRequest> calls =
                    adapter.extractSseToolCalls(fullSSE);
            streamCallsRef.set(calls);
            streamPlainTextRef.set(adapter.extractSsePlainText(fullSSE));
            streamTokenUsageRef.set(adapter.extractSseTokenUsage(fullSSE));

            String text = streamPlainTextRef.get();
            messages.add(Message.builder()
                    .role("assistant")
                    .content(text != null ? text : "")
                    .model(adapter.getModel())
                    .createdAt(LocalDateTime.now())
                    .build());

            log.info("[ToolCallLoopStage] Parsed: {} chars, toolCalls={}",
                    streamPlainTextRef.get() != null
                            ? streamPlainTextRef.get().length() : 0,
                    calls.size());
            doneLatch.countDown();
        }
    );

    try {
        if (!doneLatch.await(STREAM_COMPLETION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            log.error("[ToolCallLoopStage] Stream timeout {}ms",
                    STREAM_COMPLETION_TIMEOUT_MS);
            break;
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
    }

    allFluxes.add(Flux.empty());

    context.setAttribute("__stream_full_content__", streamPlainTextRef.get());
    context.setAttribute("__stream_token_usage__", streamTokenUsageRef.get());
    context.setAttribute("__stream_consumed__", true);
}
```

### 7.4.1 Sinks.Many：实时事件广播器

```java
@SuppressWarnings("unchecked")
Sinks.Many<String> existingSink = (Sinks.Many<String>)
        context.getAttribute("__realtime_sink__");
final Sinks.Many<String> realtimeSink = existingSink != null
        ? existingSink : Sinks.many().replay().all();
```

**什么是Sinks.Many？**

`Sinks.Many` 是Reactor提供的一个**程序化控制的发射器**（Programmatic Sink），它允许代码在任意时间、任意线程上向多个订阅者发射数据。你可以把它理解为一个"手动控制的广播站"。

**`.replay().all()` 的含义：**

```
Sinks.many().replay().all() 创建了一个支持回放的广播发射器：

特性1：多播（multicast）
    sink.tryEmitNext("事件1")  → 所有订阅者都收到 "事件1"
    sink.tryEmitNext("事件2")  → 所有订阅者都收到 "事件2"

特性2：全部回放（replay all）
    当第1个订阅者连接时 → 收到事件1, 事件2, 事件3...
    当第2个订阅者稍后连接时 → 也会收到事件1, 事件2, 事件3...（从开头开始）

特性3：无限缓冲
    .all() 表示回放所有历史事件。历史事件会缓存在内存中。
```

**为什么需要从context中获取已有的sink？**

```java
Sinks.Many<String> existingSink = (Sinks.Many<String>)
        context.getAttribute("__realtime_sink__");
```

`ChatContext` 是一个在整个请求处理过程中传递的上下文对象。在流式模式下，`OrchestratorImpl`（主响应阶段）会创建并设置 `__realtime_sink__`。ToolCallLoopStage（工具调用阶段）需要继续使用这**同一个**sink，确保实时SSE事件（LLM文本流 + 工具调用事件）通过同一个通道发送给客户端。

**如果context中还没有sink（兜底逻辑）：**

```java
final Sinks.Many<String> realtimeSink = existingSink != null
        ? existingSink : Sinks.many().replay().all();
```

如果 `__realtime_sink__` 为null（比如手动测试、或者没有经过主响应阶段直接进入工具调用），创建一个新的sink作为兜底。这保证了代码的健壮性。

**将Flux存入context：**

```java
Flux<String> realtimeFlux = realtimeSink.asFlux();
context.setAttribute("__realtime_flux__", realtimeFlux);
```

`asFlux()` 将 `Sinks.Many` 转换为只读的 `Flux` 视图。下游阶段可以通过这个Flux订阅实时事件，但不能通过它发射数据。存入context让后续阶段（如FinalResponseStage）可以访问这个Flux。

### 7.4.2 订阅SSE流并收集完整响应

```java
Flux<String> rawFlux = adapter.chatStream(context.getRequest());
CountDownLatch doneLatch = new CountDownLatch(1);
```

**`adapter.chatStream(context.getRequest())`**

调用模型适配器的流式聊天方法。这个方法内部调用 `OkHttpModelApiClient.postStream()` 建立SSE连接，然后解析每一行SSE数据，提取纯文本内容。返回值是一个 `Flux<String>`，每个元素是LLM生成的一小段文本（如 "你"、"好"、"世"、"界"）。

**`CountDownLatch doneLatch = new CountDownLatch(1)`**

创建一个计数为1的倒计时门闩。`CountDownLatch` 是Java并发包中的同步工具：

```
CountDownLatch原理：
- 初始计数 = 1
- latch.await() → 当前线程阻塞，直到计数变为0
- latch.countDown() → 计数减1（当计数变为0时，所有阻塞的线程被唤醒）
```

为什么需要在响应式代码中使用阻塞同步工具？因为ToolCallLoopStage的逻辑要求：**必须等待LLM的完整响应才能决定下一步动作**（是否包含工具调用，调用什么工具）。而LLM的完整响应是由一个异步完成的 `Flux<String>` 提供的。

这两者之间存在天然的矛盾：
- 响应式代码是异步、非阻塞的
- 业务逻辑是同步、需要等待结果的

`CountDownLatch` 就是这个矛盾的解决方案——它允许工具调用循环的线程安全地等待异步流完成。

### 7.4.3 三重回调：onNext、onError、onComplete

```java
rawFlux.subscribe(
    chunk -> {                          // ← onNext 回调
        collector.append(chunk).append('\n');
        realtimeSink.tryEmitNext(chunk);
    },
    error -> {                          // ← onError 回调
        log.error("[ToolCallLoopStage] Stream error: {}", error.getMessage());
        realtimeSink.tryEmitComplete();
        doneLatch.countDown();
    },
    () -> {                             // ← onComplete 回调
        realtimeSink.tryEmitComplete();
        String fullSSE = collector.toString();
        // ... 解析工具调用、存消息 ...
        doneLatch.countDown();
    }
);
```

#### onNext回调：逐块接收LLM文本

```java
chunk -> {
    collector.append(chunk).append('\n');
    realtimeSink.tryEmitNext(chunk);
}
```

**`collector.append(chunk).append('\n')`**

`collector` 是一个 `StringBuilder`，这里将每个文本块追加到其中，并在每个块之间添加换行符。`StringBuilder` 是可变的字符序列，适合频繁拼接的场景。

**为什么需要在每个chunk之间加换行符？**

当整个流完成后，`collector.toString()` 返回完整的累积文本（例如 `"你\n好\n，\n世\n界\n"`）。`ModelAdapter.extractSseToolCalls()` 需要从完整文本中解析工具调用请求。工具调用的请求可能出现在任意位置——换行符确保解析器能正确处理。

**`realtimeSink.tryEmitNext(chunk)`**

将文本块推送到实时广播器中。任何订阅了 `__realtime_flux__` 的消费者（如SSE响应写入器）都能立即收到这个文本块。

**为什么用 `tryEmitNext` 而不是 `emitNext`？**

- `tryEmitNext(value)` 返回 `Sinks.EmitResult`（包括 OK、FAIL_NON_SERIALIZED、FAIL_OVERFLOW 等）
- `emitNext(value)` 会内部重试，不返回结果
- LyClaw使用 `tryEmitNext` 是因为它不阻塞——如果发射失败（比如下游背压），返回失败状态而不是阻塞等待

#### onError回调：处理流错误

```java
error -> {
    log.error("[ToolCallLoopStage] Stream error: {}", error.getMessage());
    realtimeSink.tryEmitComplete();
    doneLatch.countDown();
}
```

**`realtimeSink.tryEmitComplete()`**

当即时错误发生时，关闭实时广播器。`tryEmitComplete()` 通知所有订阅者流已结束（即使是因为错误）。如果不调用这个方法，实时广播器的订阅者会一直等待，永远不会收到完成信号。

**`doneLatch.countDown()`**

释放倒计时门闩——即使出现了错误，也要让 `process()` 方法继续执行（而不是无限期地等待）。

#### onComplete回调：处理流完成

```java
() -> {
    realtimeSink.tryEmitComplete();
    String fullSSE = collector.toString();

    List<ModelResponse.ToolCallRequest> calls =
            adapter.extractSseToolCalls(fullSSE);
    streamCallsRef.set(calls);
    streamPlainTextRef.set(adapter.extractSsePlainText(fullSSE));
    streamTokenUsageRef.set(adapter.extractSseTokenUsage(fullSSE));

    String text = streamPlainTextRef.get();
    messages.add(Message.builder()
            .role("assistant")
            .content(text != null ? text : "")
            .model(adapter.getModel())
            .createdAt(LocalDateTime.now())
            .build());

    doneLatch.countDown();
}
```

这是三个回调中最复杂的一个。让我们逐步解析：

**`realtimeSink.tryEmitComplete()`**

流的自然结束：通知所有订阅者流已完成。与错误路径不同的是，这里是**正常完成**——订阅者将收到 `onComplete` 信号而非 `onError`。

**`String fullSSE = collector.toString();`**

将所有累积的文本块拼接成完整的SSE响应文本。此时collector的内容类似：
```
你
好
，
世
界
```

**`adapter.extractSseToolCalls(fullSSE)`**

从累积的完整文本中提取工具调用请求。`DeepSeekOpenAIAdapter.extractSseToolCalls()` 内部逻辑：
1. 解析累积文本中的JSON片段
2. 查找 `tool_calls` 字段（OpenAI/DeepSeek的工具调用格式）
3. 返回 `List<ModelResponse.ToolCallRequest>`（可能为空）

**`adapter.extractSsePlainText(fullSSE)`**

从累积文本中提取纯文本内容（非工具调用部分），例如"你好，世界"。即使LLM决定调用工具，它可能也会生成一段文本解释（如 "让我帮你查一下天气"）。

**`adapter.extractSseTokenUsage(fullSSE)`**

提取token使用统计信息（prompt tokens、completion tokens、total tokens），用于监控和计费。

**`streamCallsRef.set(calls)` 等三个AtomicReference**

使用 `AtomicReference` 将解析结果从订阅回调的线程（Reactor线程）传递到主流程的线程（boundedElastic线程）。`AtomicReference` 保证了跨线程的可见性（happens-before语义）。

**`messages.add(Message.builder()...)`**

将LLM的响应作为一条 `assistant` 角色的消息添加到消息列表中。这是工具调用循环的关键步骤——下一轮循环时，LLM会看到自己说过了什么、工具返回了什么，从而能做出正确的后续决策。

```
消息列表的演变示例：
第1轮开始前：[{role:"user", content:"查天气"}]
第1轮LLM响应后：[{role:"user", content:"查天气"}, {role:"assistant", content:"让我查一下", tool_calls:[...]}]
第1轮工具执行后：[{role:"user", content:"查天气"}, {role:"assistant", content:"让我查一下", tool_calls:[...]}, {role:"tool", content:"晴25°C"}]
第2轮LLM响应后：[{role:"user", content:"查天气"}, {role:"assistant", content:"让我查一下", tool_calls:[...]}, {role:"tool", content:"晴25°C"}, {role:"assistant", content:"今天晴天，25°C"}]
```

**`doneLatch.countDown()`**

释放门闩——通知主流程线程流已完成。

### 7.4.4 阻塞等待与超时处理

```java
try {
    if (!doneLatch.await(STREAM_COMPLETION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        log.error("[ToolCallLoopStage] Stream timeout {}ms",
                STREAM_COMPLETION_TIMEOUT_MS);
        break;
    }
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    break;
}
```

**`doneLatch.await(90_000, TimeUnit.MILLISECONDS)`**

阻塞当前线程（boundedElastic线程），最多等待90秒。返回值：
- `true` — 门闩在超时前被释放（`countDown()` 被调用）
- `false` — 90秒后仍未释放（流超时）

**超时处理：`break`**

如果等待超时，直接跳出整个while循环。这可能意味着LLM的响应时间异常长（比如模型卡住、网络问题）。`break` 后，`process()` 方法会调用 `chain.next(context)`，将不完整的响应传递给下游阶段处理。

**中断处理：`Thread.currentThread().interrupt()`**

当线程被 `interrupt()` 中断时，`await()` 抛出 `InterruptedException`。捕获后需要执行两件事：
1. `Thread.currentThread().interrupt()` — **恢复中断状态**（因为捕获InterruptedException会清除中断标志）
2. `break` — 退出循环

恢复中断状态是一个好的编程实践——虽然LyClaw在这里的处理中没有在break之后检查中断状态，但保留标志让调用栈上层的代码（如果有）能够检测到中断。

---

## 7.5 同步模式下的单轮处理

```java
private void handleSyncRound(ChatContext context, ModelAdapter adapter,
                             List<Message> messages, ChatResultHolder syncResult) {
    ModelResponse response = adapter.chat(context.getRequest());

    log.info("[ToolCallLoopStage] Response: toolCall={}, contentLen={}",
            response.hasToolCalls(),
            response.getContent() != null ? response.getContent().length() : 0);

    messages.add(Message.builder()
            .role("assistant")
            .content(response.getContent() != null ? response.getContent() : "")
            .model(adapter.getModel())
            .build());

    syncResult.response = response;
    syncResult.content = response.getContent();
}
```

**与流式模式的关键区别：**

1. **`adapter.chat()`（同步）vs `adapter.chatStream()`（流式）** — 同步模式下，LLM的完整响应一次性返回，不需要SSE流处理。
2. **不需要 `CountDownLatch`** — 同步调用本身就是阻塞的，当前线程等待直到响应完成。
3. **不需要 `StringBuilder` 累积** — 完整响应已经在 `ModelResponse` 对象中。
4. **不需要 `AtomicReference` 跨线程传递** — 所有操作都在同一个线程上。
5. **不需要 `Sinks.Many` 实时广播** — 同步模式下没有实时SSE事件。

`ChatResultHolder` 是一个简单的内部类，用于在方法之间传递同步模式的响应结果：

```java
private static class ChatResultHolder {
    ModelResponse response;
    String content;
}
```

---

## 7.6 工具调用判断与循环控制

```java
List<ModelResponse.ToolCallRequest> calls;
if (isStream) {
    calls = streamCallsRef.get();
} else {
    ModelResponse response = syncResult.response;
    calls = (response != null && response.hasToolCalls())
            ? response.getToolCalls() : List.of();
}

if (calls == null || calls.isEmpty()) {
    log.info("[ToolCallLoopStage] No tool calls, ending loop");
    break;
}

log.info("[ToolCallLoopStage] Detected {} tool call(s)", calls.size());
```

**`streamCallsRef.get()`**

从 `AtomicReference` 中读取流式模式下提取的工具调用列表。`get()` 方法提供了happens-before保证——可以看到订阅回调线程在 `set()` 之前的所有写入。

**`response.hasToolCalls()`**

检查LLM响应中是否包含工具调用请求。在OpenAI/DeepSeek的API中，工具调用请求出现在 `choices[0].message.tool_calls` 字段中。

**`calls == null || calls.isEmpty() → break`**

如果LLM没有要求调用任何工具，说明对话已经完成（LLM给出了最终答案），退出循环。这是工具调用循环的**正常退出条件**。

---

## 7.7 executeTools() —— 工具调用执行

```java
private Flux<String> executeTools(ChatContext context, ModelAdapter adapter,
                                  List<Message> messages,
                                  List<ModelResponse.ToolCallRequest> calls,
                                  boolean isStream) {

    Message lastAssistant = findLastAssistant(messages);
    if (lastAssistant != null && lastAssistant.getToolCalls() == null) {
        List<ToolCall> toolCalls = new ArrayList<>();
        for (ModelResponse.ToolCallRequest req : calls) {
            toolCalls.add(ToolCall.builder()
                    .toolCallId(req.getId())
                    .name(req.getName())
                    .arguments(req.getArguments())
                    .build());
        }
        lastAssistant.setToolCalls(toolCalls);
    }

    for (ModelResponse.ToolCallRequest req : calls) {
        try {
            ToolCall toolCall = ToolCall.builder()
                    .toolCallId(req.getId())
                    .name(req.getName())
                    .arguments(req.getArguments())
                    .build();

            log.info("[ToolCallLoopStage] Executing tool: {} {} args={}",
                    toolCall.getName(), toolCall.getToolCallId(),
                    toolCall.getArguments());

            ToolResult result = executeToolViaFeignOrLocal(context, toolCall, req);

            messages.add(Message.builder()
                    .role("tool")
                    .toolCallId(req.getId())
                    .content(result.isSuccess() ? result.getResult() : result.getError())
                    .build());

            log.info("[ToolCallLoopStage] Tool {} completed: {}",
                    toolCall.getName(),
                    result.isSuccess() ? "success" : "failed");
        } catch (Exception e) {
            ToolErrorAction action = toolCallPolicy != null
                    ? toolCallPolicy.handleToolError(null, e, context)
                    : ToolErrorAction.SKIP;
            messages.add(Message.builder()
                    .role("tool")
                    .toolCallId(req.getId())
                    .content("Error: " + e.getMessage())
                    .build());
            log.error("[ToolCallLoopStage] Tool {} execution exception: {}",
                    req.getName(), e.getMessage());

            if (action == ToolErrorAction.ABORT) {
                context.setAttribute("error", e.getMessage());
                break;
            }
        }
    }

    if (isStream) {
        Flux<String> toolFlux = buildToolEventFlux(calls);
        @SuppressWarnings("unchecked")
        Sinks.Many<String> realtimeSink = (Sinks.Many<String>)
                context.getAttribute("__realtime_sink__");
        if (realtimeSink != null) {
            toolFlux.subscribe(
                event -> realtimeSink.tryEmitNext(event),
                error -> log.warn("Tool event push error", error),
                () -> {}
            );
        }
        return toolFlux;
    }
    return null;
}
```

### 7.7.1 回填assistant消息的tool_calls字段

```java
Message lastAssistant = findLastAssistant(messages);
if (lastAssistant != null && lastAssistant.getToolCalls() == null) {
    List<ToolCall> toolCalls = new ArrayList<>();
    for (ModelResponse.ToolCallRequest req : calls) {
        toolCalls.add(ToolCall.builder()
                .toolCallId(req.getId())
                .name(req.getName())
                .arguments(req.getArguments())
                .build());
    }
    lastAssistant.setToolCalls(toolCalls);
}
```

在流式模式下，LLM的assistant响应在 `onComplete` 回调中已经被添加到消息列表，但当时还没有解析工具调用信息。这是因为工具调用信息分散在SSE流的各个chunk中，只有流完全结束后才能完整解析。

这段代码做的是**回填**（backfill）操作：
1. `findLastAssistant(messages)` — 从消息列表末尾向前查找，找到最近一条 `role="assistant"` 的消息
2. `lastAssistant.getToolCalls() == null` — 检查这条消息是否已经有工具调用信息
3. 如果还没有，将解析出的 `ModelResponse.ToolCallRequest` 转换为 `ToolCall` 对象并设置到消息中

为什么需要回填？因为OpenAI/DeepSeek的API要求对话历史中的每条assistant消息（如果它请求了工具调用）必须包含 `tool_calls` 字段。下一轮请求LLM时，LLM会验证消息格式的完整性。

**`findLastAssistant()` 的实现：**

```java
private Message findLastAssistant(List<Message> messages) {
    for (int i = messages.size() - 1; i >= 0; i--) {
        if ("assistant".equals(messages.get(i).getRole())) {
            return messages.get(i);
        }
    }
    return null;
}
```

从消息列表的末尾向前遍历，找到最近的一条助手消息。这是一个O(n)的线性搜索，但由于消息列表通常不会太长（几十条到几百条），性能影响可以忽略。

### 7.7.2 逐个执行工具调用

```java
for (ModelResponse.ToolCallRequest req : calls) {
    try {
        ToolCall toolCall = ToolCall.builder()
                .toolCallId(req.getId())
                .name(req.getName())
                .arguments(req.getArguments())
                .build();

        ToolResult result = executeToolViaFeignOrLocal(context, toolCall, req);

        messages.add(Message.builder()
                .role("tool")
                .toolCallId(req.getId())
                .content(result.isSuccess() ? result.getResult() : result.getError())
                .build());
    } catch (Exception e) { ... }
}
```

LLM可能在一次响应中请求调用多个工具（parallel tool calls）。这个循环**顺序**执行每个工具调用。

**`ToolCall.builder()` 从API响应构建领域对象：**

`ModelResponse.ToolCallRequest` 是API解析层的对象（直接来自SSE JSON），而 `ToolCall` 是LyClaw的领域对象。这里做了一个转换：
- `req.getId()` — 工具调用的唯一ID（由LLM或API生成，用于关联响应）
- `req.getName()` — 工具名称（如 `get_weather`、`search_code`）
- `req.getArguments()` — 工具参数（JSON格式，如 `{"city": "北京"}`）

**`executeToolViaFeignOrLocal(context, toolCall, req)`**

执行工具调用，返回 `ToolResult`。这个方法有两个执行路径（见7.7.3节）。

**`messages.add(Message.builder().role("tool")...)`**

将工具执行结果作为一条 `role="tool"` 的消息添加到对话历史中。这是OpenAI/DeepSeek API要求的格式——每条工具调用必须有一个对应的工具结果消息。

**工具执行结果的存储：**
- 成功：存储 `result.getResult()`（工具返回的数据，如 `{"temperature": 25, "condition": "晴"}`）
- 失败：存储 `result.getError()`（错误信息）
- `toolCallId` 字段关联到具体的工具调用

**异常处理：**

```java
catch (Exception e) {
    ToolErrorAction action = toolCallPolicy != null
            ? toolCallPolicy.handleToolError(null, e, context)
            : ToolErrorAction.SKIP;
    messages.add(Message.builder()
            .role("tool")
            .toolCallId(req.getId())
            .content("Error: " + e.getMessage())
            .build());

    if (action == ToolErrorAction.ABORT) {
        context.setAttribute("error", e.getMessage());
        break;
    }
}
```

- `toolCallPolicy.handleToolError()` — 调用工具错误策略，决定如何处理工具执行失败
- `ToolErrorAction.SKIP` — 跳过这个工具，继续执行后续工具
- `ToolErrorAction.ABORT` — 终止所有工具执行，设置错误信息，跳出循环
- `ToolErrorAction.RETRY` — 如果需要重试（LyClaw当前实现中未使用）

### 7.7.3 双层工具执行策略：Feign远程调用 + 本地Registry回退

```java
private ToolResult executeToolViaFeignOrLocal(ChatContext context, ToolCall toolCall,
                                               ModelResponse.ToolCallRequest req) {
    if (actionFeignClient != null) {
        try {
            Map<String, Object> argsMap = new HashMap<>();
            argsMap.put("arguments", toolCall.getArguments());
            ToolExecuteRequest feignReq = ToolExecuteRequest.builder()
                    .toolName(toolCall.getName())
                    .args(argsMap)
                    .sessionId(context.getRequest().getSessionId())
                    .build();
            ToolResult remoteResult = actionFeignClient.executeTool(feignReq);
            if (remoteResult.isSuccess()) {
                return ToolResult.success(
                        remoteResult.getOutput() != null ? remoteResult.getOutput() : "success");
            } else {
                return ToolResult.failure(
                        remoteResult.getErrorMessage() != null
                                ? remoteResult.getErrorMessage() : "remote tool failed");
            }
        } catch (Exception feignError) {
            log.warn("[ToolCallLoopStage] ActionFeignClient failed, falling back to local ToolRegistry: {}",
                    feignError.getMessage());
        }
    }

    if (toolRegistry != null) {
        return toolRegistry.execute(toolCall, context);
    }
    return ToolResult.failure("No ToolRegistry or ActionFeignClient available");
}
```

这个方法实现了**远程优先，本地回退**（Remote-First, Local-Fallback）的两层工具执行策略：

**第一层：Feign远程调用**

```
OrchestrationService(8081)  ──Feign──▶  ActionService(8084)
    │                                        │
    │  POST /api/tool/execute                │
    │  Body: {toolName, args, sessionId}     │
    │                                        │  执行工具
    │  ◀── {success, output, errorMessage}  │
    │                                        │
```

`ActionFeignClient` 是一个Spring Cloud OpenFeign客户端，它将工具执行委托给专门的 `action-service` 微服务。

1. `argsMap.put("arguments", toolCall.getArguments())` — 构建请求参数映射
2. `ToolExecuteRequest.builder()` — 构建Feign请求对象
3. `actionFeignClient.executeTool(feignReq)` — 通过HTTP调用远程Action服务
4. 如果成功：返回 `ToolResult.success(remoteResult.getOutput())`
5. 如果远程返回失败：返回 `ToolResult.failure(errorMessage)`

**第二层：本地回退**

如果Feign调用抛出异常（网络错误、服务不可用、超时等），catch块捕获异常并记录警告，然后回退到本地 `ToolRegistry`：

```java
if (toolRegistry != null) {
    return toolRegistry.execute(toolCall, context);
}
```

`ToolRegistry` 是本地注册的工具集合。它允许配置一些简单的工具在编排服务本地执行，而不需要走远程调用。

**第三层：完全失败**

如果既没有远程Action服务，也没有本地ToolRegistry：

```java
return ToolResult.failure("No ToolRegistry or ActionFeignClient available");
```

---

## 7.8 buildToolEventFlux() —— 构建工具调用事件流

```java
private Flux<String> buildToolEventFlux(List<ModelResponse.ToolCallRequest> calls) {
    return Flux.create((Consumer<FluxSink<String>>) sink -> {
        for (ModelResponse.ToolCallRequest req : calls) {
            String json = "{\"type\":\"tool_call\",\"name\":\""
                    + escapeJson(req.getName()) + "\",\"status\":\"executing\"}";
            sink.next("data: " + json + "\n\n");
        }
        for (ModelResponse.ToolCallRequest req : calls) {
            String json = "{\"type\":\"tool_call\",\"name\":\""
                    + escapeJson(req.getName()) + "\",\"status\":\"done\"}";
            sink.next("data: " + json + "\n\n");
        }
        sink.complete();
    });
}
```

### 7.8.1 为什么这里用Flux.create()？

与第六章中 `postStream()` 使用 `Flux.generate()` 不同，这里使用 `Flux.create()`：

| 原因 | 说明 |
|------|------|
| **数据已在内存中** | 工具调用列表 `calls` 已经完全可知，不需要等待I/O |
| **快速连续发射** | 不需要等待下游的背压请求（虽然Flux.create也会尊重背压） |
| **不需要状态维护** | 在生成器函数中可以一次性发射所有数据 |
| **编程模型更直观** | 两重循环 + 直接 `sink.next()` 比 generate 的逐次返回更自然 |

### 7.8.2 SSE格式的事件构造

`buildToolEventFlux` 为每个工具调用生成两个SSE事件：一个"正在执行"事件和一个"执行完成"事件。

**事件1（正在执行）：**
```
data: {"type":"tool_call","name":"get_weather","status":"executing"}

```

**事件2（执行完成）：**
```
data: {"type":"tool_call","name":"get_weather","status":"done"}

```

**事件格式解析：**
- `"data: " + json` — SSE协议格式的前缀
- `"\n\n"` — SSE协议的双换行符，表示事件结束
- JSON字段：`type`（事件类型）、`name`（工具名称）、`status`（执行状态）

先发射所有工具的"executing"事件，再发射所有工具的"done"事件：
```
executing: get_weather
executing: search_code    ← 如果LLM调用了两个工具
done: get_weather
done: search_code
```

### 7.8.3 escapeJson() —— JSON字符串转义

```java
private static String escapeJson(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
}
```

手工进行JSON字符串转义，确保工具名称中的特殊字符不会破坏JSON格式。注意替换顺序——**必须先转义反斜杠（`\` → `\\`），再转义引号（`"` → `\"`）**：

```
如果是先转义引号再转义反斜杠：
  "hello\"world"  →  (先)  "hello\\\"world"  ✓ 正确
  "hello\"world"  →  (先)  \"hello\\"world\"  ✗ 错误，引号没有被正确转义

正确顺序：
  hello\"world
  → (第1步: 转义反斜杠)  hello\\\"world
  → (第2步: 转义引号)    hello\\\\\\"world
```

如果在转义引号之后再转义反斜杠，会导致引号前刚插入的转义反斜杠也被再次转义。

### 7.8.4 工具事件与实时广播的集成

```java
if (isStream) {
    Flux<String> toolFlux = buildToolEventFlux(calls);
    @SuppressWarnings("unchecked")
    Sinks.Many<String> realtimeSink = (Sinks.Many<String>)
            context.getAttribute("__realtime_sink__");
    if (realtimeSink != null) {
        toolFlux.subscribe(
            event -> realtimeSink.tryEmitNext(event),
            error -> log.warn("Tool event push error", error),
            () -> {}
        );
    }
    return toolFlux;
}
```

工具事件通过两个通道发送：

1. **`realtimeSink.tryEmitNext(event)`** — 实时广播通道：通过 `__realtime_sink__`，工具调用事件被广播给所有订阅者（包括SSE响应写入器）。这使得前端可以实时看到工具调用的进度。

2. **`return toolFlux`** — 合并到 `allFluxes`：`toolFlux` 被添加到 `allFluxes` 列表中，最终通过 `Flux.concat(allFluxes)` 合并到一个连续的SSE事件流中存入context。

两个通道都发送相同的事件数据，但目标不同：
- `__realtime_sink__` → 实时SSE响应（前端EventSource直接接收）
- `__stream_flux__` → 存储在context中，供后续管道阶段使用

---

## 7.9 流合并与管道传递

```java
if (isStream) {
    Flux<String> merged = allFluxes.isEmpty() ? Flux.empty()
            : allFluxes.size() == 1 ? allFluxes.get(0)
            : Flux.concat(allFluxes);
    context.setAttribute("__stream_flux__", merged);
    log.info("[ToolCallLoopStage] Streaming: merged {} Flux segments", allFluxes.size());
} else {
    log.info("[ToolCallLoopStage] Sync completed");
}

chain.next(context);
```

### 7.9.1 Flux.concat() —— 串联多个流

`Flux.concat()` 将多个 `Flux<String>` 按照顺序连接成一个连续的流：

```
allFluxes = [
    Flux<String> {},              ← 轮次1（无工具调用，Flux.empty()）
    Flux<String> {事件a, 事件b},  ← 轮次2（工具调用事件）
    Flux<String> {},              ← 轮次3（无工具调用）
    Flux<String> {事件c}          ← 轮次4（工具调用事件）
]

Flux.concat(allFluxes) 产生的流：
    → 事件a
    → 事件b
    → 事件c
    → complete
```

空Flux（`Flux.empty()`）在 `concat` 中不产生任何元素，所以串联后的流中不会有空隙。

### 7.9.2 存入context供后续使用

```java
context.setAttribute("__stream_flux__", merged);
```

将合并后的工具调用事件流存入 `ChatContext`。后续阶段（如 `FinalResponseStage`）可以从context中取出这个Flux，将其与主响应SSE流合并，形成完整的事件序列发送给客户端。

**context中的关键属性总结：**

| 属性名 | 类型 | 生产者 | 消费者 |
|--------|------|--------|--------|
| `__realtime_sink__` | `Sinks.Many<String>` | OrchestratorImpl | ToolCallLoopStage, FinalResponseStage |
| `__realtime_flux__` | `Flux<String>` | ToolCallLoopStage | FinalResponseStage |
| `__stream_flux__` | `Flux<String>` | ToolCallLoopStage | FinalResponseStage |
| `__stream_full_content__` | `String` | ToolCallLoopStage | FinalResponseStage |
| `__stream_token_usage__` | `String` | ToolCallLoopStage | FinalResponseStage |
| `__stream_consumed__` | `Boolean` | ToolCallLoopStage | FinalResponseStage |

### 7.9.3 chain.next(context) —— 责任链传递

```java
chain.next(context);
```

这是责任链模式的核心调用。`Chain` 对象维护了管道阶段的执行顺序，`chain.next(context)` 将控制权传递给下一个阶段（根据 `getOrder()` 排序的下一个 `PipelineStage`）。

ToolCallLoopStage结束后的典型管道流程：

```
ToolCallLoopStage.process()
    │
    │  chain.next(context)  ← 控制权传递
    ▼
FinalResponseStage.process()
    │  读取 context 中的 __realtime_flux__ 和 __stream_flux__
    │  构建最终的 SSE 响应
    │  写入 HTTP 响应流
    ▼
客户端收到完整的 SSE 事件流
```

---

## 7.10 完整的工具调用循环时序图

以下是流式模式下一次典型的工具调用循环的完整时序：

```
时间轴 ─────────────────────────────────────────────────────────────────►

客户端                   ToolCallLoopStage            LLM API           Action Service
  │                            │                        │                    │
  │  请求 (tool call enabled)   │                        │                    │
  │──────────────────────────▶│                        │                    │
  │                            │                        │                    │
  │                            │  ★ 第1轮开始            │                    │
  │                            │  chatStream(request)    │                    │
  │                            │──────────────────────▶│                    │
  │                            │                        │                    │
  │   SSE: "让"               │  ◀── SSE chunk "让" ──│                    │
  │◀──────────────────────────│                        │                    │
  │   SSE: "我"               │  ◀── SSE chunk "我" ──│                    │
  │◀──────────────────────────│                        │                    │
  │   SSE: "查"               │  ◀── SSE chunk "查" ──│                    │
  │◀──────────────────────────│                        │                    │
  │   SSE: "一"               │  ◀── SSE chunk "一" ──│                    │
  │◀──────────────────────────│                        │                    │
  │   SSE: "下"               │  ◀── SSE chunk "下" ──│                    │
  │◀──────────────────────────│                        │                    │
  │                            │  ◀── SSE chunk         │                    │
  │                            │    finish_reason=tool  │                    │
  │                            │    tool_calls=[{       │                    │
  │                            │      name:"get_weather"│                    │
  │                            │      args:{"city":"BJ"}│                    │
  │                            │    }]                  │                    │
  │                            │                        │                    │
  │                            │  extractSseToolCalls() │                    │
  │                            │  → [get_weather("BJ")] │                    │
  │                            │                        │                    │
  │   SSE: 工具调用事件         │                        │                    │
  │   "type":"tool_call",      │                        │                    │
  │   "name":"get_weather",    │                        │                    │
  │   "status":"executing"     │                        │                    │
  │◀──────────────────────────│                        │                    │
  │                            │                        │                    │
  │                            │  executeToolViaFeign    │                    │
  │                            │  ("get_weather",        │                    │
  │                            │   {"city":"北京"})      │                    │
  │                            │────────────────────────────────────────▶│
  │                            │                        │                    │
  │                            │  ◀── {"output":"25°C"} ─────────────────│
  │                            │                        │                    │
  │   SSE: 工具调用事件         │                        │                    │
  │   "type":"tool_call",      │                        │                    │
  │   "name":"get_weather",    │                        │                    │
  │   "status":"done"          │                        │                    │
  │◀──────────────────────────│                        │                    │
  │                            │                        │                    │
  │                            │  ★ 第2轮开始            │                    │
  │                            │  chatStream(request +   │                    │
  │                            │    tool_result)         │                    │
  │                            │──────────────────────▶│                    │
  │                            │                        │                    │
  │   SSE: "今"               │  ◀── SSE chunk "今" ──│                    │
  │◀──────────────────────────│                        │                    │
  │   SSE: "天"               │  ◀── SSE chunk "天" ──│                    │
  │◀──────────────────────────│                        │                    │
  │   SSE: "晴"               │  ◀── SSE chunk "晴" ──│                    │
  │◀──────────────────────────│                        │                    │
  │   SSE: "天"               │  ◀── SSE chunk "天" ──│                    │
  │◀──────────────────────────│                        │                    │
  │                            │  ◀── finish_reason=     │                    │
  │                            │       stop              │                    │
  │                            │  extractSseToolCalls()  │                    │
  │                            │  → [] (空，无工具调用)   │                    │
  │                            │                        │                    │
  │                            │  break (退出循环)        │                    │
  │                            │  chain.next(context)    │                    │
  │                            │                        │                    │
  │  最终响应："今天晴天"       │  FinalResponseStage     │                    │
  │◀──────────────────────────│                        │                    │
```

---

## 7.11 边界情况与安全保护

### 7.11.1 最大轮次保护（MAX_ROUNDS = 6）

```java
int round = 0;
while (round < MAX_ROUNDS) { ... round++; }
```

防止无限循环的最重要机制。考虑以下场景：
- LLM反复尝试调用一个不存在的工具
- 工具执行结果LLM始终不满意（如搜索结果为空），反复尝试
- LLM在不同工具之间循环调用

### 7.11.2 流超时保护（STREAM_COMPLETION_TIMEOUT_MS = 90,000）

```java
if (!doneLatch.await(STREAM_COMPLETION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
    log.error("[ToolCallLoopStage] Stream timeout {}ms", STREAM_COMPLETION_TIMEOUT_MS);
    break;
}
```

保护机制防止单个流请求永久挂起。90秒对于大多数工具调用场景来说足够（通常工具执行在几秒内完成）。

### 7.11.3 线程中断保护

```java
catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    break;
}
```

正确处理线程中断，保留中断状态供上层处理。

### 7.11.4 工具执行异常保护

```java
catch (Exception e) {
    ToolErrorAction action = toolCallPolicy != null
            ? toolCallPolicy.handleToolError(null, e, context)
            : ToolErrorAction.SKIP;
    // ...
    if (action == ToolErrorAction.ABORT) {
        context.setAttribute("error", e.getMessage());
        break;
    }
}
```

对于工具执行的异常，不是无条件终止，而是通过策略模式决定如何处理（跳过、终止或重试）。

### 7.11.5 空工具调用列表保护

```java
if (calls == null || calls.isEmpty()) {
    log.info("[ToolCallLoopStage] No tool calls, ending loop");
    break;
}
```

正常的退出条件——如果LLM没有要求调用工具，说明对话已完成。


## 8.1 为什么不用原生的EventSource？

浏览器内置的 `EventSource` API 是 W3C SSE 规范的标准客户端实现，但它有一个致命限制：**只支持 GET 请求**。`EventSource` 构造函数只能接受 URL 和可选的配置对象，无法指定 HTTP 方法，更无法附带请求体。

```javascript
// EventSource 只能这样用——没有 method 参数，没有 body 参数
const es = new EventSource('/api/chat/stream');
// es 会自动发送 GET 请求，无法改为 POST
```

LyClaw 的聊天流式接口 `/api/chat/stream` 需要 **POST 请求**，原因如下：

1. **请求体包含完整的对话历史**。每次请求都携带 `messages` 数组，随着对话进行，这个数组可能包含数十条消息、数万字符。GET 请求将参数塞进 URL 查询字符串是不可行的——URL 有长度限制（通常约 2000 字符），而且对话内容暴露在 URL 中既不安全也不优雅。

2. **请求体包含复杂的 JSON 配置**。`ChatRequest` 对象包含 `sessionId`、`messages`、`model`、`maxTokens`、`temperature`、`topP`、`tools`、`thinkingEnabled`、`thinkingBudget`、`toolChoice`、`stopSequences`、`extras` 等字段，这些远非 URL 参数能承载。

3. **POST 支持自定义请求头**。LyClaw 服务端通过 `Accept: text/event-stream` 请求头进行内容协商——流式请求返回 SSE 流，非流式请求返回 JSON。使用 `fetch()` 可以自由设置所有请求头。

**解决方案**：使用 `fetch()` API 获取 `ReadableStream`，手动解析 SSE 字节流。这带来三大优势：

| 特性 | EventSource (原生) | fetch() + ReadableStream (LyClaw) |
|------|-------------------|-----------------------------------|
| HTTP 方法 | 仅 GET | GET / POST / PUT / DELETE |
| 请求体 | 不支持 | 支持 (JSON, FormData 等) |
| 自定义请求头 | 不支持 | 完全支持 |
| 取消请求 | 仅 close() (不发送信号给服务器) | AbortController.abort() |
| 超时控制 | 无内置机制 | 可实现任意超时策略 |
| 自动重连 | 内置 (根据 retry 字段) | 需手动实现 |
| 事件类型 | 自动分发 addEventListener | 手动解析 event: 行 |

**缺点**：

- **无自动重连**：`EventSource` 在连接断开后会自动重连（发送 `Last-Event-Id` 头），而手动实现需要自行处理重连逻辑。不过 LyClaw 的每次聊天请求都是无状态的——每次发送新消息都新建一条 SSE 连接，因此自动重连并无必要。
- **需手动解析 SSE 协议**：`EventSource` 自动解析 `event:`、`data:`、`id:`、`retry:` 行并按事件类型分发，手动实现需要自己写解析器（约 50 行代码）。

---

## 8.2 postSSE()——Fetch API 流式消费核心实现

`postSSE()` 是整个前端 SSE 消费的引擎，位于文件 `lyclaw-ui/src/api/client.ts`（第 87-217 行）。它是一个使用 `fetch()` + `ReadableStream` + 手动 SSE 解析的异步函数。

### 8.2.1 函数签名

```typescript
export async function postSSE(
  path: string,                                    // API 路径，如 '/api/chat/stream'
  body: unknown,                                   // 请求体（ChatRequest 对象）
  onChunk: (text: string) => void,                 // 每收到一个 LLM 文本 token 时调用
  onDone: () => void,                              // 流正常结束时调用
  onError: (err: Error) => void,                   // 发生错误时调用
): Promise<void>
```

**设计要点**：

- `async function` 返回 `Promise<void>`，但这不是普通的异步函数——它的 Promise 在整个流结束后才 resolve，而不是收到 HTTP 响应头时。调用者可以 `await postSSE(...)` 来等待整个对话完成。
- 三个回调函数是**依赖注入**模式：`postSSE()` 只负责传输层的 SSE 解析，不关心数据如何被消费。调用者通过回调决定每个 token 追加到哪里、完成时做什么、出错时如何降级。
- `body: unknown`——类型擦除为 `unknown`，因为不同的 SSE 接口可能有不同的请求体结构（聊天、工具执行、计划生成等）。实际调用时 TypeScript 的类型检查在被调用方（如 `postChatStream`）完成。

### 8.2.2 超时保护系统

LyClaw 设计了**双层超时**保护，代码（第 95-121 行）：

```typescript
const controller = new AbortController()
const READ_TIMEOUT_MS = 60_000   // 每块之间超时：60 秒
const MAX_TOTAL_MS = 300_000     // 整个流超时：300 秒（5 分钟）

let readTimer: ReturnType<typeof setTimeout> | null = null
let maxTimer: ReturnType<typeof setTimeout> | null = null

function clearTimers() {
  if (readTimer !== null) { clearTimeout(readTimer); readTimer = null }
  if (maxTimer !== null) { clearTimeout(maxTimer); maxTimer = null }
}

function resetReadTimeout() {
  if (readTimer !== null) clearTimeout(readTimer)
  readTimer = setTimeout(() => {
    controller.abort()
    onError(new ApiError(0, `Stream stalled: no data for ${READ_TIMEOUT_MS / 1000}s`, null))
  }, READ_TIMEOUT_MS)
}

// 硬性总超时
maxTimer = setTimeout(() => {
  controller.abort()
  onError(new ApiError(0, `Stream timed out after ${MAX_TOTAL_MS / 1000}s`, null))
}, MAX_TOTAL_MS)

// 启动逐块读超时
resetReadTimeout()
```

#### 逐块超时（Read Timeout）——60 秒

- **目的**：检测流是否"卡住"（stall）。正常的 SSE 流每秒产生数十个 token，如果 60 秒内没收到任何数据，说明 LLM 可能无限循环或后端线程阻塞。
- **重置机制**：`resetReadTimeout()` 在每次 `reader.read()` 成功返回后被调用。每次收到新数据块（哪怕只有 1 字节），计时器就重新开始计时 60 秒。
- **为何选择 60 秒**：LLM 有时会因工具调用执行、记忆检索等阶段暂停几秒到十几秒（正常），但不太可能暂停超过 60 秒（异常）。这个值在"容忍正常延迟"和"快速检测异常"之间取得平衡。
- **触发后行为**：调用 `controller.abort()` 取消 fetch 请求，然后通过 `onError()` 回调通知上层一个 `ApiError`，消息为 `"Stream stalled: no data for 60s"`。

#### 总超时（Max Total Timeout）——300 秒（5 分钟）

- **目的**：硬性上限，防止流"永远"不结束。即使每 50 秒都有数据到达（单块超时一直不触发），也不能让连接无限持续。
- **不重置**：`maxTimer` 在函数开始时设置一次，永远不被重置。无论流中有多少数据，5 分钟后一定会被 `controller.abort()` 终止。
- **与后端对齐**：后端 OkHttp 客户端超时 300 秒，Spring Cloud Gateway 响应超时 300 秒，前端最大总时间 300 秒——三层同为 300 秒，确保没有任何层会提前切断其他层。

#### 清理机制

```typescript
} finally {
  clearTimers()
}
```

`finally` 块确保无论流以何种方式结束（正常完成、超时中止、网络错误、用户取消），两个定时器都会被清除。如果不清除，定时器会永远挂在事件循环中，造成**内存泄漏**。`clearTimers()` 先将定时器设为 `null` 再清除，防止重复清除。

### 8.2.3 请求发送

代码（第 125-150 行）：

```typescript
const url = `${BASE_URL}${path}`    // BASE_URL = '' (开发时相对路径，被 Vite 代理)

const response = await fetch(url, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',         // <-- 关键：告诉服务器我们要 SSE 流
  },
  body: JSON.stringify(body),
  signal: controller.signal,             // <-- 关联 AbortController
})
```

#### `Accept: text/event-stream` 请求头

这是 SSE 的**内容协商**机制。当服务端同时提供 `/api/chat/stream`（SSE 流式）和 `/api/chat`（JSON 非流式）时，请求头告诉服务端客户端期望的响应格式。LyClaw 的 `OrchestrationController` 可以通过检查这个头来决定返回 `Flux<ServerSentEvent>` 还是 `Mono<ChatResult>`。

实际上 LyClaw 用两个不同的端点（`/api/chat/stream` vs `/api/chat`）来区分流式和非流式，但保留 `Accept` 头是一种良好的 HTTP 实践，也有助于网关层的路由和日志。

#### `signal: controller.signal`

将 `AbortController` 的 `signal` 传给 `fetch()` 后，调用 `controller.abort()` 会立即取消正在进行的 HTTP 请求，包括：

- 如果请求还没发送（排队中），取消发送
- 如果请求正在传输响应体，中断连接
- `reader.read()` 中等待的 Promise 会被 reject 一个 `AbortError`

#### 响应状态检查

```typescript
if (!response.ok) {
  let errorBody: unknown
  try {
    errorBody = await response.json()
  } catch {
    errorBody = await response.text()
  }
  const err = new ApiError(
    response.status,
    `SSE request failed: ${response.status} ${response.statusText}`,
    errorBody,
  )
  onError(err)
  return            // <-- 不抛异常，通过回调通知上层
}
```

非 2xx 状态码的处理逻辑：
1. 尝试将响应体解析为 JSON（后端通常返回结构化错误信息）
2. 如果解析失败，回退到纯文本
3. 创建 `ApiError` 对象，通过 `onError` 回调通知（而非 `throw`）
4. `return` 提前退出，不会进入流读取循环

> **设计决策：为什么 `onError` 而非 `throw`？** 因为 `postSSE` 的调用者已经在 `await` 这个函数——如果内部 throw，调用者的 `try/catch` 可以捕获，但同时也失去了通过 `onError` 回调统一的错误处理路径。使用 `onError` 保持所有错误走同一条通道，调用者无需同时处理 `catch(err)` 和 `onError(err)` 两个路径。

### 8.2.4 ReadableStream 获取

代码（第 152-156 行）：

```typescript
const reader = response.body?.getReader()
if (!reader) {
  onError(new Error('Response body is not readable'))
  return
}
```

#### `response.body`

`fetch()` 返回的 `Response` 对象有一个 `body` 属性，类型是 `ReadableStream<Uint8Array> | null`。对于流式响应（如 SSE），`body` 是一个可读的字节流；对于非流式响应，`body` 可能为 `null`，也可能在响应体完全接收后才可用。

在 SSE 场景下，服务端在发送响应头后立即开始推送数据块，`response.body` 立即可用。`null` 的情况出现在：
- HEAD 请求
- 204 No Content
- 某些 CORS 限制场景

#### `.getReader()`

`ReadableStream.getReader()` 返回一个 `ReadableStreamDefaultReader<Uint8Array>`。调用后：
- 流被**锁定**（locked）——其他代码不能再调用 `getReader()` 或执行其他读取操作
- `reader` 是唯一的读取入口，独占式访问

读者可以使用两种方式读取：
- `reader.read()`——返回 `Promise<{ done: boolean, value: Uint8Array | undefined }>`，每次调用读取下一个可用的数据块
- `reader.releaseLock()`——释放锁，允许流被其他 reader 读取

LyClaw 使用 `reader.read()` 的循环模式，逐块消费底层字节流。

### 8.2.5 流式解码与行缓冲

代码（第 158-159 行及第 182-205 行）：

```typescript
const decoder = new TextDecoder()           // UTF-8 解码器
let buffer = ''                              // 行缓冲：累积未完成的行
let currentEvent = ''                        // 当前 SSE 事件类型

while (true) {
  const { done, value } = await reader.read()

  // 每次成功读取重置逐块超时
  resetReadTimeout()

  if (done) {
    // 流结束——处理缓冲中可能剩余的未完成行
    if (buffer.trim()) {
      const lines = buffer.split('\n')
      processLines(lines)
    }
    onDone()
    return
  }

  // 将 Uint8Array 解码为字符串，累积到缓冲区
  buffer += decoder.decode(value, { stream: true })

  // 按换行符分割
  const lines = buffer.split('\n')

  // 最后一行可能不完整（被字节边界截断），留在缓冲区
  buffer = lines.pop() || ''

  // 处理所有完整行
  if (processLines(lines)) {
    onDone()    // processLines 返回 true 表示收到 done 事件
    return
  }
}
```

#### 为什么需要行缓冲？

TCP 是一个**字节流**协议，不是消息协议。服务端调用 `sink.next(sseEvent)` 时，Reactor 框架将其写入 TCP 套接字缓冲区。在网络层，数据被分割成 TCP 段（segment），每个段的大小取决于 MTU（通常约 1500 字节）、拥塞窗口等因素。

一个 SSE 事件可能被 TCP 在任意位置切分：

```
服务端发送：
  "event: message\ndata: 你好，我"

TCP 可能分成两个数据块：
  Chunk 1: "event: message\ndata: 你"
  Chunk 2: "好，我\n\n"
```

如果在 Chunk 1 处不做缓冲直接按 `\n` 分割：
- 第一行 `"event: message"` 完整
- 第二行 `"data: 你"` 不完整——**文本 token 被截断**

行缓冲解决此问题：每次读取后将新数据追加到 `buffer`，按 `\n` 分割，最后一段（没有以 `\n` 结尾的部分）留在 `buffer` 中，等待下一个块的到来。

#### `TextDecoder` 的 `{ stream: true }` 参数

这是**最容易忽视但最关键的**细节。来看一个具体例子：

```javascript
// 中文字符"你"的 UTF-8 编码是 3 字节：E4 BD A0
// 如果 TCP 将"你好"切成：
// Chunk 1: [E4, BD]        —— 2 字节，不完整的"你"
// Chunk 2: [A0, E5, A5, BD] —— 后 1 字节"你" + 完整"好"

const decoder = new TextDecoder();

// 没有 { stream: true }
decoder.decode(new Uint8Array([0xE4, 0xBD]));           // → "��"  (U+FFFD 替换字符)
decoder.decode(new Uint8Array([0xA0, 0xE5, 0xA5, 0xBD])); // → "��好"

// 有 { stream: true }
decoder.decode(new Uint8Array([0xE4, 0xBD]), { stream: true });           // → ""  (保留不完整序列)
decoder.decode(new Uint8Array([0xA0, 0xE5, 0xA5, 0xBD]));                 // → "你好"
```

`{ stream: true }` 告诉 `TextDecoder`：**"这个数据块后面还有更多数据"**。遇到不完整的 UTF-8 多字节序列时，解码器不会立即替换为 U+FFFD（替换字符 `�`），而是保留该序列，等待下一个 `decode()` 调用的输入与之拼接。

这正是 SSE 流式场景的核心需求——LLM 生成的每个 token 可能包含中文、emoji（4 字节 UTF-8）、日文假名等。没有 `{ stream: true }`，流式输出会出现大量乱码 `�����`。

注意：LyClaw 的当前代码使用的是 `new TextDecoder()` 不带参数，但在实际的循环中调用 `decoder.decode(value, { stream: true })` 时传入了参数。最后一帧 `done` 时的解码 `decoder.decode()` 不带 `{ stream: true }` 参数（或不传入），让解码器刷新所有残留字节。

#### `lines.pop() || ''` 的妙用

```javascript
const lines = buffer.split('\n')
buffer = lines.pop() || ''
```

`Array.prototype.pop()` 移除并返回数组的最后一个元素。对于行缓冲模式，最后一行可能是不完整的：

- **Chunk 以 `\n` 结尾**：`split('\n')` 产生的最后一个元素是 `""`（空字符串），`pop()` 返回 `""`，`buffer` 被设定为 `""`——正确，没有残留数据。
- **Chunk 不以 `\n` 结尾**：`split('\n')` 产生的最后一个元素是不完整的行内容，`pop()` 返回该内容，`buffer` 被设定为该内容——正确，等待下一个块补全。
- **Chunk 为空字符串**：`split('\n')` 返回 `[""]`，`pop()` 返回 `""`，`buffer` 为 `""`——边界情况但逻辑正确。

`|| ''` 的防御性写法：如果 `lines` 为空数组（极罕见情况如 `split` 返回空数组），`pop()` 返回 `undefined`，`|| ''` 将其转为空字符串，避免 `buffer` 变成 `undefined`。

#### `reader.read()` 的 `done` 分支

当服务端完成 SSE 流后，关闭连接。在 TCP 层，这表现为 FIN 包。在 `fetch()` 的 `ReadableStream` 层，`reader.read()` 返回 `{ done: true, value: undefined }`。

`done` 分支的处理：
1. **先处理缓冲**：`buffer` 中可能有最后一批数据（最后一个 SSE 事件可能不以 `\n` 结尾，或者最后一行是不完整的，但没有更多数据了——服务端关闭连接前会确保事件完整）
2. **调用 `onDone()`**：通知上层流已正常结束
3. **`return`**：退出 `while(true)` 循环

为什么在 `done` 之前 `processLines` 能返回 `true` 也会触发 `onDone`？因为 `processLines` 内部在检测到 `event: done` 或 `data: [DONE]` 时返回 `true`——这两种信号意味着服务端主动发送了结束信号，不等 TCP FIN。

### 8.2.6 SSE 行解析器

代码（第 162-180 行）：

```typescript
function processLines(lines: string[]): boolean {
  for (const line of lines) {
    if (line.startsWith('event:')) {
      currentEvent = line.slice(6).trim()
      if (currentEvent === 'done') {
        return true    // 遇到 done 事件，立即通知结束
      }
    } else if (line.startsWith('data:')) {
      const data = line.slice(5).trim()
      if (data === '[DONE]') {
        return true    // OpenAI 兼容的结束信号
      }
      if (data && currentEvent === 'message') {
        onChunk(data)  // LLM 文本 token
      }
    }
    // 以 ':' 开头的是 SSE 注释（通常用于 keep-alive 心跳）
    // 空行是事件分隔符
    // 其他未识别行被静默忽略
    // 所有「事件类型」和「数据行」之间不产生副作用
  }
  return false
}
```

#### SSE 行类型处理

标准 SSE 协议定义了以下字段：

| 字段 | 前缀 | LyClaw 处理方式 |
|------|------|----------------|
| `event` | `event:` | 记录到 `currentEvent` 变量。若值为 `done`，立即返回 `true` 结束流 |
| `data` | `data:` | 若当前事件为 `message`，调用 `onChunk(data)`；若为 `[DONE]`，返回 `true` 结束流 |
| `id` | `id:` | 未处理（LyClaw 不需要断点续传） |
| `retry` | `retry:` | 未处理（LyClaw 无自动重连） |
| 注释 | `:` 开头 | 忽略 |
| 空行 | (空字符串) | 忽略（事件分隔符） |

#### `event: done` 与 `data: [DONE]` 两种结束信号

LyClaw 服务端使用 `event: done` 作为流的结束信号：

```
event: done
data: {"status":"complete","durationMs":12345}
```

前端检测到 `event:` 行为 `done` 时，直接返回 `true`，无需等待 `data:` 行。

同时，前端也兼容 OpenAI 风格的 `data: [DONE]`：

```
data: [DONE]
```

这是防御性设计——如果后端切换到 OpenAI 兼容的 SSE 格式，前端无需修改。

#### `event: message` + `data: <token>` 的处理

这组合产生了实际可见的输出：

```
event: message
data: 你好

event: message
data: ，我

event: message
data: 是LyClaw
```

每个 `data:` 文本是一个 LLM token——可能是半个词、一个词、甚至单个标点。前端将这些 token 逐个追加到 `currentStreamingText` 字符串，Vue 响应式系统自动重新渲染。

#### 其他事件类型的处理

LyClaw 后端产生大量非 `message` 类型的 SSE 事件：

| 事件类型 | data 内容 | 目的 | 前端效果 |
|---------|----------|------|---------|
| `context_build_start` | 文本描述 | 记忆检索开始 | 静默忽略 |
| `context_build_complete` | 文本描述 | 记忆检索完成 | 静默忽略 |
| `intercept_blocked` | 安全拒绝原因 | 安全检查不通过 | 静默忽略（`currentEvent` 无匹配） |
| `plan_start` | 文本描述 | 任务规划开始 | 静默忽略 |
| `plan_node` | JSON 节点信息 | 规划节点详情 | 静默忽略 |
| `plan_complete` | 统计信息 | 规划完成 | 静默忽略 |
| `action_start` | JSON 工具信息 | 工具开始执行 | 静默忽略 |
| `action_result` | JSON 执行结果 | 工具执行结果 | 静默忽略 |
| `action_complete` | JSON 统计信息 | 所有工具执行完 | 静默忽略 |
| `reflect_start` | 文本描述 | 反思开始 | 静默忽略 |
| `reflect_complete` | JSON 评分 | 反思完成 | 静默忽略 |
| `respond_start` | 文本描述 | LLM 开始生成回复 | 静默忽略 |
| `message` | 文本 token | LLM 生成的每个 token | `onChunk(data)` |
| `respond_complete` | 文本描述 | 回复生成完成 | 静默忽略 |
| `metrics` | JSON 性能指标 | 流水线统计 | 静默忽略 |
| `error` | JSON 错误信息 | 异常发生 | 静默忽略（待增强） |

这些"静默忽略"的事件并非无用——它们对应后端流水线的各个阶段。`currentEvent` 变量会被更新（如 `currentEvent = 'action_start'`），但后续的 `data:` 行处理中 `currentEvent === 'message'` 不成立，因此不触发 `onChunk`。这样，`processLines` 自然过滤掉了所有非 message 事件，无需显式白名单或黑名单。

> **历史记录保留**：这些事件的 `data` 文本虽然不显示在聊天界面，但它们随流数据一起被后端写入 `EventLogStorage`（见第七章），供调试和回放使用。

### 8.2.7 错误处理

代码（第 206-216 行）：

```typescript
} catch (err) {
  if ((err as Error).name === 'AbortError') {
    onError(new ApiError(0, `SSE stream aborted`, null))
  } else if (err instanceof ApiError) {
    onError(err)
  } else {
    onError(err as Error)
  }
} finally {
  clearTimers()
}
```

#### AbortError

`AbortError` 是 `DOMException` 的一种，当 `AbortController.abort()` 被调用时，`fetch()` 和 `reader.read()` 的 Promise 都会被 reject 一个 `name === 'AbortError'` 的错误。

产生 `AbortError` 的场景（正常操作）：
- 用户点击"停止生成"按钮
- 逐块超时（60 秒无数据）
- 总超时（5 分钟）

产生 `AbortError` 的场景（异常操作）：
- 浏览器页面关闭（fetch 请求被浏览器取消）

**处理方式**：将其转为 `ApiError(status=0, message='SSE stream aborted')`，通过 `onError` 传给上层。`status=0` 是特殊标记，上层 `chatStore.sendMessage()` 据此判断是 abort 还是真正的网络错误，决定是否触发非流式降级。

#### ApiError

源于 `response.ok === false` 的 HTTP 错误——4xx（客户端错误，如 400 Bad Request、401 Unauthorized、429 Rate Limit）、5xx（服务端错误，如 500 Internal Server Error、502 Bad Gateway）。

`ApiError` 携带 `status` 和 `body`，上层可以做差异化处理（如 401 跳转登录页）。

#### 通用 Error

来自以下场景：
- `reader` 为 `null`（`Response body is not readable`）
- JSON 解析错误（`JSON.stringify(body)` 失败——极罕见）
- 网络层错误（DNS 解析失败、TCP 连接被拒、TLS 握手失败、连接意外断开）
- `TextDecoder` 致命解码错误（几乎不可能）

#### finally 块

`clearTimers()` 在 `finally` 中执行，确保无论正常完成还是异常退出，两个定时器都会被清除。这是**防止定时器泄漏**的关键——如果不清除定时器，即使流已经结束，定时器回调仍会在未来某个时刻触发，试图操作已经失效的 `controller`，可能导致不可预测的副作用。

---

## 8.3 chatStore——Pinia 状态管理

`chatStore` 是聊天功能的核心状态管理单元，位于 `lyclaw-ui/src/stores/chat.ts`，使用 Pinia 的 Setup Store 语法（`defineStore` + Composition API）。

### 8.3.1 状态定义

```typescript
export const useChatStore = defineStore('chat', () => {
  const messages = ref<Message[]>([])           // 已完成的消息列表
  const currentStreamingText = ref<string>('')  // 当前正在流式接收的文本
  const isStreaming = ref<boolean>(false)       // 是否正在流式接收中
  const error = ref<string | null>(null)        // 错误消息
  const currentModel = ref<string>('deepseek-4-pro')
  const currentProvider = ref<string>('deepseek')
  const currentSessionId = ref<string | null>(null)
  // ...
})
```

**关键状态之间的协作关系**：

| 状态组合 | isStreaming | currentStreamingText | 含义 | UI 渲染 |
|---------|-------------|---------------------|------|---------|
| 空闲 | false | '' | 没有进行中的对话 | 显示消息列表 + 输入框 |
| 思考中 | true | '' | 已发送请求，LLM 尚未产出 token | 显示"思考中..."动画 |
| 流式输出中 | true | 非空 | LLM 正在逐 token 生成 | 显示消息列表 + 流式气泡（带光标） |
| 刚完成 | false | '' | 流刚结束，`onDone` 已推送消息 | 显示完整消息列表 |

`isStreaming` 和 `currentStreamingText` 是两个独立的 ref，需要仔细配合。以下状态管理逻辑确保它们的一致性：

- `sendMessage` 开始时：`isStreaming = true`，`currentStreamingText = ''`（同时设置，进入"思考中"状态）
- `onChunk` 每次调用：`currentStreamingText += chunk`（仅追加文本，`isStreaming` 保持 `true`）
- `onDone` 调用时：先 push 消息，然后 `currentStreamingText = ''`，再 `isStreaming = false`
- `onError` 调用时：先 push 部分消息（如果有），然后 `currentStreamingText = ''`，再 `isStreaming = false`

注意**设置顺序**：先清空 `currentStreamingText` 再设为 `isStreaming = false`。如果顺序颠倒，`isStreaming = false` 后 UI 的 `tempStreamingMessage` 会立即变为 `null`（因为 `isStreaming && currentStreamingText` 为 `false`），而 Vue 的批量更新机制可能在不同 tick 处理 ref 变化，导致闪烁。

### 8.3.2 sendMessage()——流式发送完整流程

代码（第 39-112 行）：

```typescript
async function sendMessage(text: string, sessionId?: string): Promise<void> {
  if (!text.trim()) return

  // 第一步：创建并追加用户消息
  const userMsg: Message = { role: 'user', content: text }
  messages.value.push(userMsg)
  error.value = null

  // 第二步：确保会话存在（自动创建或使用已有）
  const targetSessionId = sessionId || currentSessionId.value
  let activeSessionId: string | null = targetSessionId

  if (!activeSessionId) {
    try {
      const session = await createSession()
      activeSessionId = session.sessionId
      currentSessionId.value = activeSessionId
    } catch (err) {
      error.value = `Failed to create session: ${(err as Error).message}`
      return
    }
  }

  // 第三步：构造请求
  const request: ChatRequest = {
    sessionId: activeSessionId,
    messages: messages.value.map((m) => ({ role: m.role, content: m.content })),
    stream: true,
  }

  // 第四步：进入流式状态
  isStreaming.value = true
  currentStreamingText.value = ''

  // 第五步：发送 SSE 请求
  try {
    await postChatStream(
      request,
      // --- onChunk 回调 ---
      (chunk: string) => {
        currentStreamingText.value += chunk
      },
      // --- onDone 回调 ---
      () => {
        const assistantMsg: Message = {
          role: 'assistant',
          content: currentStreamingText.value,
          model: currentModel.value,
        }
        messages.value.push(assistantMsg)
        currentStreamingText.value = ''
        isStreaming.value = false
      },
      // --- onError 回调 ---
      (err: Error) => {
        if (currentStreamingText.value) {
          const partialMsg: Message = {
            role: 'assistant',
            content: currentStreamingText.value,
            model: currentModel.value,
          }
          messages.value.push(partialMsg)
        }
        currentStreamingText.value = ''
        isStreaming.value = false

        if (err.name !== 'AbortError' &&
            !(err instanceof ApiError && (err as ApiError).status === 0)) {
          error.value = err.message
          sendMessageNonStreaming(request)
        } else {
          error.value = null
        }
      },
    )
  } catch (err) {
    isStreaming.value = false
    error.value = (err as Error).message
  }
}
```

#### 流程图解

```
用户点击发送
  │
  ├─ 创建 user Message，push 到 messages[]
  │
  ├─ 确保 session 存在（不存在则 createSession）
  │
  ├─ isStreaming = true, currentStreamingText = ''
  │    UI 开始显示"思考中..."动画
  │
  ├─ postChatStream(request, onChunk, onDone, onError)
  │    │
  │    ├─ [onChunk 被多次调用]
  │    │   currentStreamingText += token
  │    │   UI 更新流式气泡内容，自动滚动
  │    │
  │    └─ [onDone 调用]
  │        push assistant Message 到 messages[]
  │        清空 currentStreamingText
  │        isStreaming = false
  │
  └─ [异常路径] onError 或 catch
       ├─ 保存部分文本（如有）为 assistant Message
       ├─ 清空 streaming 状态
       └─ 非 abort 错误：降级到 sendMessageNonStreaming()
```

#### onChunk——Vue 响应式的力量

```typescript
(chunk: string) => {
  currentStreamingText.value += chunk
}
```

这一行看似简单的字符串拼接，背后是 Vue 3 的 `Proxy` 响应式系统在工作：

1. `currentStreamingText` 是一个 `ref<string>`，实际是一个 `{ value: string }` 的响应式代理
2. `+=` 操作触发 `value` 的 setter
3. Vue 的依赖追踪系统通知所有依赖 `currentStreamingText.value` 的计算属性和组件
4. `ChatView.vue` 中的 `tempStreamingMessage` computed 被标记为 dirty
5. 组件重新渲染，新的 token 出现在屏幕上

这个过程在每次 `onChunk` 调用时都重复一次。如果 LLM 每秒生成约 30 个 token（常见速度），则 Vue 每秒触发约 30 次重新渲染。Vue 的虚拟 DOM diff 和批量异步更新确保这个频率不会造成性能问题——实际 DOM 更新会被合并到一个 `nextTick` 中。

#### onDone——流结束处理

```typescript
() => {
  const assistantMsg: Message = {
    role: 'assistant',
    content: currentStreamingText.value,    // 完整的流式文本
    model: currentModel.value,
  }
  messages.value.push(assistantMsg)
  currentStreamingText.value = ''
  isStreaming.value = false
}
```

关键点：
- **取 `currentStreamingText` 的完整值**：所有 token 通过 `+=` 累积在此，此时即完整回复
- **添加 `model` 字段**：记录是哪个模型生成的，显示在消息气泡头部
- **推送到 `messages` 数组**：从"临时流式气泡"变为"持久消息"，排序在消息列表末尾
- **清空流式状态**：临时气泡消失，下次用户发送消息时重新开始

#### onError——错误处理与降级策略

```typescript
(err: Error) => {
  // 保存已接收的部分文本（即使流中断了，部分内容也好过什么都没有）
  if (currentStreamingText.value) {
    const partialMsg: Message = {
      role: 'assistant',
      content: currentStreamingText.value,
      model: currentModel.value,
    }
    messages.value.push(partialMsg)
  }
  currentStreamingText.value = ''
  isStreaming.value = false

  // 判断是否需要降级到非流式
  if (err.name !== 'AbortError' &&
      !(err instanceof ApiError && (err as ApiError).status === 0)) {
    error.value = err.message
    sendMessageNonStreaming(request)   // 降级重试
  } else {
    error.value = null   // AbortError 或内部超时——静默处理
  }
}
```

**降级决策树**：

```
发生错误
  │
  ├─ 是 AbortError（用户取消、或超时）
  │   └─ 已保存部分文本（如有）
  │   └─ 不显示错误信息
  │   └─ 不触发非流式降级（用户取消 = 不要继续；超时 = 可能还会超时）
  │
  ├─ 是 ApiError 且 status === 0（内部超时已被转为 ApiError）
  │   └─ 同上，不触发降级
  │
  └─ 其他错误（网络故障、HTTP 4xx/5xx）
      └─ 显示错误信息到 error bar
      └─ 触发非流式降级 sendMessageNonStreaming()
```

**为什么 AbortError 不降级？**
- **用户主动取消**：用户已明确不想继续，自动重试违背用户意图
- **超时**：如果流式都超时了（60 秒无数据或 5 分钟总超时），非流式大概率也会超时或耗时极长——服务端可能正在处理一个过于复杂的请求
- **部分内容已保存**：用户可以看到 LLM 生成到一半的内容，比什么都看不到强

**部分文本保存（Partial Text Salvage）**：即使流中断，已经通过 `onChunk` 累积的文本是有价值的——LLM 可能在生成 80% 的内容后因网络问题中断，保存这 80% 让用户看到，比显示一条"出错了"的通用错误信息更有用。

### 8.3.3 sendMessageNonStreaming()——非流式降级

代码（第 114-144 行）：

```typescript
async function sendMessageNonStreaming(request: ChatRequest): Promise<void> {
  try {
    const result: ChatResult = await postChat({
      ...request,
      stream: false,
    })
    const assistantMsg: Message = {
      role: 'assistant',
      content: result.content,
      model: currentModel.value,
      usage: result.tokenUsage
        ? parseTokenUsage(result.tokenUsage)
        : undefined,
      toolCalls: result.toolResults
        ? result.toolResults.map((tr) => ({
            toolCallId: '',
            name: tr.toolName,
            arguments: '',
            result: tr.output,
          }))
        : undefined,
    }
    messages.value.push(assistantMsg)
    isStreaming.value = false
  } catch (fallbackErr) {
    error.value = `Chat failed: ${(fallbackErr as Error).message}`
    isStreaming.value = false
    throw fallbackErr
  }
}
```

非流式降级的核心区别：

| 维度 | 流式 (stream: true) | 非流式 (stream: false) |
|------|-------------------|----------------------|
| 端点 | POST /api/chat/stream | POST /api/chat |
| 响应类型 | text/event-stream (SSE) | application/json |
| 前端实现 | postSSE() → ReadableStream + 手动 SSE 解析 | post() → fetch + response.json() |
| 用户感知 | token 逐个出现 | 等待后一次性出现 |
| 额外数据 | 服务端同样运行完整流水线 | 服务端可跳过 SSE 包装，直接返回 ChatResult |
| Token 使用信息 | 无（当前实现未从 SSE 提取） | 有（`result.tokenUsage`） |
| 工具调用结果 | 无（当前实现未从 SSE 提取） | 有（`result.toolResults`） |

> **注意**：LyClaw 当前实现的非流式 `postChat` 内部使用标准的 `post<T>()`，底层是 `fetch()` 后调用 `response.json()`。这意味着服务端的 `/api/chat` 端点需要等整个流水线完成后才返回——没有中间进度。这保证了非流式降级能拿到完整结果，但缺少了流式体验。

`parseTokenUsage()` 是一个防御性辅助函数（第 202-218 行），处理两种可能格式：

```typescript
// 格式一（camelCase）：
{ promptTokens: 123, completionTokens: 456, totalTokens: 579 }

// 格式二（snake_case，OpenAI 风格）：
{ prompt_tokens: 123, completion_tokens: 456, total_tokens: 579 }
```

### 8.3.4 其他 Actions

```typescript
/** 停止当前正在进行的流式生成 */
function stopGeneration(): void {
  isStreaming.value = false
}
```

当前的 `stopGeneration` 实现较为简单——仅设置 `isStreaming = false` 来通知 UI 停止显示流式状态。完整的停止实现需要在 `chatStore` 中持有 `AbortController` 引用并调用 `controller.abort()`，这是未来的优化方向（目前 store 不直接持有 `AbortController`，`postSSE` 内部自行管理）。

```typescript
/** 清空所有聊天消息 */
function clearChat(): void {
  messages.value = []
  currentStreamingText.value = ''
  error.value = null
}
```

同时清空消息列表、流式文本和错误信息。注意不清空 `currentSessionId`——用户可以继续在同一会话中开始新对话。

```typescript
/** 重试最后一条用户消息 */
async function retry(): Promise<void> {
  const lastUser = lastUserMessage.value
  if (!lastUser) return

  const userIdx = messages.value.lastIndexOf(lastUser)
  messages.value.splice(userIdx)  // 删除最后一条用户消息及之后的所有内容
  await sendMessage(lastUser.content, currentSessionId.value ?? undefined)
}
```

`retry` 的逻辑：
1. 通过 `lastUserMessage` getter 找到最后一条用户消息
2. 用 `lastIndexOf` 找到索引（因为可能有内容完全相同的消息）
3. `splice(userIdx)` 删除该索引及之后的所有元素（包括失败的 assistant 回复、部分流式文本等）
4. 用同样的文本重新调用 `sendMessage`

---

## 8.4 ChatView.vue——流式 UI 渲染

`ChatView.vue` 是聊天界面的主组件，位于 `lyclaw-ui/src/views/ChatView.vue`，负责协调消息渲染、流式状态展示和用户交互。

### 8.4.1 思考中状态

```typescript
const isThinking = computed(() =>
  chatStore.isStreaming && !chatStore.currentStreamingText
)
```

`isThinking` 为 `true` 的精确条件：**正在流式接收 AND 尚未收到任何文本 token**。

这个状态对应的时间窗口：从 `sendMessage()` 设置 `isStreaming = true` 开始，到后端 LLM 产生第一个 `event: message` 事件为止。这段时间内后端流水线正在执行：

```
前端发送请求
  ↓ (网络延迟：数十毫秒)
后端接收请求
  ↓
OrchestratorImpl 启动流水线
  ↓
context_build_start → 检索记忆（数百毫秒，取决于记忆数量）
  ↓
plan_start → 任务规划（数百毫秒）
  ↓
[可能多次工具调用循环：action_start → action_result → action_complete]
  ↓
reflect_start → reflect_complete
  ↓
respond_start → LLM 开始推理（预热延迟：数百毫秒到数秒）
  ↓
event: message (第一个 token 到达)
  ↓
前端 onChunk 被调用，currentStreamingText 从 '' 变为非空
  ↓
isThinking 变为 false
```

这段时间最短约 1 秒（简单问候），最长可达 30 秒以上（复杂推理 + 多轮工具调用）。UI 在此期间显示动画，避免用户面对空白界面产生"没反应"的错觉。

**Template 部分**：

```html
<div v-if="isThinking" class="thinking-bubble">
  <div class="thinking-bubble-inner">
    <div class="message-role-icon thinking-avatar">
      <span class="role-letter">L</span>
    </div>
    <div class="message-body">
      <div class="message-header">
        <span class="message-role-label">LyClaw</span>
        <span class="message-model-badge">{{ chatStore.currentModel }}</span>
      </div>
      <div class="thinking-indicator">
        <span class="thinking-dot" />
        <span class="thinking-dot" />
        <span class="thinking-dot" />
        <span class="thinking-text">思考中...</span>
      </div>
    </div>
  </div>
</div>
```

**CSS 动画——三点弹跳**：

```css
.thinking-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-muted-soft);
  animation: thinking-bounce 1.4s ease-in-out infinite both;
}

.thinking-dot:nth-child(1) { animation-delay: 0s; }
.thinking-dot:nth-child(2) { animation-delay: 0.2s; }
.thinking-dot:nth-child(3) { animation-delay: 0.4s; }

@keyframes thinking-bounce {
  0%, 80%, 100% {
    transform: scale(0.6);
    opacity: 0.4;
  }
  40% {
    transform: scale(1);
    opacity: 1;
  }
}
```

三个圆点使用**相同的动画但不同的延迟**，形成波浪效果：
- 点 1：立即开始弹跳（0s 延迟）
- 点 2：0.2 秒后开始弹跳
- 点 3：0.4 秒后开始弹跳

`1.4s` 的动画周期与 `0.2s` 的延迟差配合，确保三个点始终处于不同相位。`animation-fill-mode: both` 确保动画开始前和结束后状态正确。

`ease-in-out` 缓动函数：圆点在 0%-40% 上升（scale 0.6→1, opacity 0.4→1），40%-80% 下降（scale 1→0.6, opacity 1→0.4），80%-100% 保持低位等待下一个循环。

### 8.4.2 临时流式消息气泡

```typescript
const tempStreamingMessage = computed<Message | null>(() => {
  if (chatStore.isStreaming && chatStore.currentStreamingText) {
    return {
      role: 'assistant',
      content: chatStore.currentStreamingText,
      model: chatStore.currentModel,
    }
  }
  return null
})
```

`tempStreamingMessage` 是一个**虚拟消息**——它不存储在 `chatStore.messages` 数组中，而是由计算属性动态生成。其存在的意义：

- **复用 `MessageBubble` 组件**：无需为流式文本创建单独的渲染组件，复用了现有的消息气泡组件和 Markdown 渲染
- **避免对 messages 数组的副作用**：流式文本频繁变化（每秒数十次），如果每次都 push/update 到 messages 数组，会触发不必要的数组响应式更新
- **分离关注点**：`messages` 数组代表"已完成的历史记录"，`tempStreamingMessage` 代表"正在进行中的临时内容"

**Template 部分**：

```html
<MessageBubble
  v-if="tempStreamingMessage"
  :message="tempStreamingMessage"
  :is-last="true"
  :is-streaming="true"
/>
```

`is-streaming="true"` 传递给 `MessageBubble` 后，触发流式光标的显示（见 8.5 节）。

**已完成消息的渲染**：

```html
<MessageBubble
  v-for="(msg, index) in allMessages"
  :key="index"
  :message="msg"
  :is-last="index === allMessages.length - 1 && !chatStore.isStreaming"
  :is-streaming="false"
/>
```

`is-last` 的判断包含 `&& !chatStore.isStreaming`——只有流已经结束时的最后一条消息才是"真正的最后一条"。如果流还在进行中，最后一条消息（倒数第二条实际消息，流式气泡在它之后）的 `is-last` 为 `false`。这避免了流式输出时已有消息显示多余的视觉元素。

### 8.4.3 自动滚动

```typescript
function scrollToBottom() {
  nextTick(() => {
    if (messageListRef.value && settingsStore.autoScroll) {
      messageListRef.value.scrollTop = messageListRef.value.scrollHeight
    }
  })
}

// 监听消息数量变化（新消息添加时）
watch(
  () => chatStore.messages.length,
  () => scrollToBottom(),
)

// 监听流式文本变化（token 逐个追加时）
watch(
  () => chatStore.currentStreamingText,
  () => scrollToBottom(),
)
```

**双 Watcher 设计**是流式 UI 的关键：

1. **`messages.length` watcher**：当新消息被 push 到数组时触发——包括用户消息、助手流式完成后的最终消息、非流式降级的响应消息。但仅此还不够——流式输出过程中，消息数量不变，文本在 `currentStreamingText` 中增长，滚动条不会自动跟随。

2. **`currentStreamingText` watcher**：每次 `onChunk` 调用时 `currentStreamingText` 的值改变，触发此 watcher。这确保在流式输出过程中（可能持续数十秒，生成数千个 token），滚动条始终跟随最新内容。

**`nextTick()` 的必要性**：

Vue 的 DOM 更新是**异步批量**的。当响应式数据改变时，Vue 不会立即更新 DOM，而是将更新推入队列，在下一个"tick"中批量执行。如果在 watcher 回调中直接设置 `scrollTop`，此时 DOM 尚未更新，`scrollHeight` 仍是旧值。

`nextTick()` 等待 Vue 完成当前批次的 DOM 更新后再执行滚动。伪代码等价于：

```javascript
// 没有 nextTick()：
scrollHeight  // = 1000  (新内容还没渲染)
scrollTop = scrollHeight  // = 1000 (滚动到旧底部)

// 有 nextTick()：
await DOM 更新完成
scrollHeight  // = 1500  (新内容已渲染)
scrollTop = scrollHeight  // = 1500 (滚动到新底部)
```

**`settingsStore.autoScroll` 检查**：用户可以在设置中关闭自动滚动。如果关闭，即使有新内容也不会滚动——这在用户正在查看历史消息时很重要（否则新内容到达会突然跳走）。

### 8.4.4 错误栏

```html
<div v-if="chatStore.error" class="error-bar">
  <span class="error-text">{{ chatStore.error }}</span>
  <button class="error-retry-btn" @click="handleRetry">Retry</button>
  <button class="error-dismiss-btn" @click="chatStore.error = null">Dismiss</button>
</div>
```

错误栏在主消息区和输入框之间显示。三个元素：
- **错误文本**：单行显示，超出宽度用省略号（`text-overflow: ellipsis; white-space: nowrap; overflow: hidden`）
- **Retry 按钮**：调用 `chatStore.retry()`——删除最后一次交互然后重新发送
- **Dismiss 按钮**：直接清空 `error` ref，隐藏错误栏

---

## 8.5 MessageBubble.vue——流式光标效果

`MessageBubble.vue` 位于 `lyclaw-ui/src/components/MessageBubble.vue`，负责渲染单条聊天消息（用户或助手）。流式场景下的关键职责是显示**闪烁的光标**。

### 8.5.1 组件 Props

```typescript
const props = defineProps<{
  message: Message       // 消息数据
  isLast: boolean        // 是否为最后一条消息
  isStreaming: boolean   // 是否正在流式输出中
}>()
```

### 8.5.2 流式光标显示逻辑

```typescript
const isUser = computed(() => props.message.role === 'user')

const showStreamingCursor = computed(
  () => props.isStreaming && props.isLast && !isUser.value,
)
```

`showStreamingCursor` 为 `true` 需要**三个条件同时满足**：

| 条件 | 含义 | 排除的场景 |
|------|------|----------|
| `isStreaming` | 当前正在流式输出中 | `onDone` 后不再显示光标 |
| `isLast` | 这条消息是最后一条 | 只有最新的流式消息需要光标 |
| `!isUser` | 不是用户消息 | 用户消息不需要光标 |

### 8.5.3 Template 中的光标元素

```html
<div class="message-content">
  <MarkdownRenderer :content="message.content" />
  <span v-if="showStreamingCursor" class="streaming-cursor">▊</span>
</div>
```

光标**紧跟在 Markdown 渲染内容之后**，作为 `<span>` 内联元素。当流式文本逐个 token 追加时，光标始终出现在最新文本的末尾——就像终端中的闪烁光标。

**为什么光标在 `MarkdownRenderer` 之外？** `MarkdownRenderer` 负责将 markdown 文本渲染为 HTML。如果光标在其内部，markdown 解析器可能会误将 `▊` 当作内容的一部分进行处理（如包裹在 `<p>` 标签内）。放在外部后，光标是一个独立的视觉元素，不影响 markdown 解析。

### 8.5.4 光标字符：▊ (U+258A)

`▊` 是 Unicode 字符 `LEFT THREE QUARTERS BLOCK`（U+258A），属于 `Block Elements` 区块。选择它的原因：

- **视觉清晰**：比简单的竖线 `|` 更宽、更显眼
- **终端风格**：与命令行/终端的光标风格一致，暗示"AI 正在生成"
- **居中对齐**：作为内联字符自然与文本基线对齐，无需额外 CSS 定位
- **无需图标库**：Unicode 字符，零依赖

### 8.5.5 CSS 闪烁动画

```css
.streaming-cursor {
  display: inline;
  animation: blink 1s step-end infinite;
  color: var(--color-primary);
  font-weight: 400;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}
```

**`step-end` 时间函数**是最关键的 CSS 选择：

`step-end`（等价于 `steps(1, end)`）使动画在关键帧之间**瞬间跳变**，没有平滑过渡。效果：

```
时间轴：0ms    250ms   500ms   750ms   1000ms
opacity: 1  →  0  →   1   →   0   →   1
```

如果使用默认的 `ease`（平滑过渡），光标会有淡入淡出效果，看起来像在"呼吸"而非"闪烁"。`step-end` 创造的硬切换（瞬间开/关）更接近真实终端光标。

**1s 周期**：可见 0.5 秒，隐藏 0.5 秒——这个频率既不会太快（让人感到紧张），也不会太慢（让用户以为流已停止）。浏览器的 `animation` 属性默认 `infinite` 循环，只要 `showStreamingCursor` 为 `true`，动画就永不停止。

**`color: var(--color-primary)`**：光标的颜色与主题色一致（亮色主题为蓝色系，暗色主题为对应色），确保在两种主题下都清晰可见。

### 8.5.6 消息角色头像

```html
<div class="message-role-icon">
  <span v-if="isUser" class="role-letter">U</span>
  <span v-else class="role-letter">L</span>
</div>
```

- 用户消息：头像显示 "U"（User），背景为主题色（`var(--color-primary)`）
- 助手消息：头像显示 "L"（LyClaw），背景为深色（`var(--color-surface-dark)`）

在流式场景下，临时流式消息气泡中的角色为 `assistant`，因此显示 "L" 头像和 "LyClaw" 标签。

### 8.5.7 用户消息 vs 助手消息的样式差异

```css
/* 助手消息——透明背景，左侧布局 */
.message-assistant .message-body {
  background: transparent;
  padding: 0;
}

/* 用户消息——带色气泡，右对齐 */
.message-user .message-body {
  background: var(--chat-bubble-user-bg);
  color: var(--chat-bubble-user-fg);
  border-radius: var(--chat-bubble-user-radius);
  padding: var(--spacing-md) var(--spacing-lg);
}

.message-user .message-bubble-inner {
  justify-content: flex-end;
}
```

这种布局类似于主流聊天应用（微信、iMessage）：
- 助手消息靠左，无背景（透明），内容可由 Markdown 自由渲染
- 用户消息靠右，带圆角色背景气泡，头像在文字右侧

流式消息永远是助手角色，因此始终使用左侧布局。

---

## 8.6 前端 SSE 完整数据流总结

将以上所有组件串联起来，一次完整的流式对话的前端数据流如下：

```
用户输入文本 "你好"
  │
  ▼
ChatView.handleSend("你好")
  │
  ▼
chatStore.sendMessage("你好")
  ├─ messages.push({ role: 'user', content: '你好' })
  │   → ChatView.allMessages computed 更新
  │   → messages.length watcher 触发 scrollToBottom()
  ├─ [若无 session] createSession() → sessionId
  ├─ isStreaming = true, currentStreamingText = ''
  │   → ChatView.isThinking computed 变为 true
  │   → 思考中动画显示
  │
  ▼
chatApi.postChatStream(request, onChunk, onDone, onError)
  │
  ▼
client.postSSE('/api/chat/stream', body, onChunk, onDone, onError)
  │
  ├─ 设置双层超时 (60s/300s)
  ├─ fetch(url, { method: 'POST', headers: { Accept: 'text/event-stream' } })
  │   ├─ [网络层] HTTP 请求 → Vite proxy → Spring Cloud Gateway → Orchestration Service
  │   │   后端运行完整流水线 → 产生 SSE 事件
  │   │
  │   ├─ [响应到达] Content-Type: text/event-stream
  │   ├─ reader = response.body.getReader()
  │   │
  │   ├─ [循环] while (true) { await reader.read() }
  │   │   │
  │   │   ├─ Chunk 1: "event: context_build_start\ndata: Loading...\n\n"
  │   │   │   → decoder.decode() → split('\n') → processLines()
  │   │   │   → currentEvent = 'context_build_start'
  │   │   │   → data 被忽略 (currentEvent !== 'message')
  │   │   │
  │   │   ├─ Chunk 2: "event: respond_start\ndata: Generating...\n\n"
  │   │   │   → currentEvent = 'respond_start'
  │   │   │   → data 被忽略
  │   │   │
  │   │   ├─ Chunk 3: "event: message\ndata: 你好\n\n"
  │   │   │   → currentEvent = 'message'
  │   │   │   → processLines: data='你好', currentEvent='message' → onChunk('你好')
  │   │   │   → chatStore: currentStreamingText = '你好'
  │   │   │   → ChatView: tempStreamingMessage computed 更新
  │   │   │   → MessageBubble 渲染 '你好' + ▊
  │   │   │   → currentStreamingText watcher 触发 scrollToBottom()
  │   │   │
  │   │   ├─ Chunk 4: "event: message\ndata: ，我\n\n"
  │   │   │   → onChunk('，我')
  │   │   │   → currentStreamingText = '你好，我'
  │   │   │
  │   │   ├─ Chunk N: "event: message\ndata: 是LyClaw\n\n"
  │   │   │   → onChunk('是LyClaw')
  │   │   │   → currentStreamingText = '你好，我是LyClaw...'
  │   │   │
  │   │   ├─ Chunk N+1: "event: respond_complete\ndata: Response...\n\n"
  │   │   │   → currentEvent = 'respond_complete'
  │   │   │   → data 被忽略
  │   │   │
  │   │   ├─ Chunk N+2: "event: done\ndata: {\"status\":\"complete\"}\n\n"
  │   │   │   → processLines: event='done' → return true
  │   │   │   → 退出 while 循环
  │   │   │   → onDone() 被调用
  │   │   │
  │   │   └─ reader.read() → { done: true } (TCP FIN)
  │   │       → onDone() (如尚未调用)
  │   │
  │   └─ finally: clearTimers()
  │
  ▼
onDone 回调 (在 chatStore.sendMessage 中)
  ├─ messages.push({ role: 'assistant', content: '你好，我是LyClaw...' })
  │   → ChatView.allMessages computed 更新
  │   → messages.length watcher 触发 scrollToBottom()
  ├─ currentStreamingText = ''
  │   → ChatView.tempStreamingMessage computed → null
  │   → MessageBubble 临时气泡消失
  ├─ isStreaming = false
  │   → ChatView.isThinking → false
  │   → 思考中动画消失
  │
  └─ UI 回到空闲状态：显示完整消息列表 + 输入框
```

---

# 第九章：基础设施与全链路超时

## 9.1 Spring Cloud Gateway 配置

LyClaw 使用 Spring Cloud Gateway 作为 API 网关，所有前端请求（包括 SSE 流式请求）都通过网关路由到后端微服务。

### 9.1.1 响应超时配置

`lyclaw-gateway/src/main/resources/bootstrap.yml`（第 10-12 行）：

```yaml
spring:
  cloud:
    gateway:
      httpclient:
        response-timeout: 300s
```

Gateway 默认的响应超时是 **30 秒**。对于普通 REST API 请求，30 秒完全足够；但对于 SSE 流式请求，LLM 生成回复可能需要数分钟——如果不延长超时，Gateway 会在 30 秒后返回 `504 Gateway Timeout`，切断正在进行的 SSE 流。

**300 秒（5 分钟）的选择理由**：

- 与后端 OkHttp 客户端超时一致（也是 300 秒）
- 与前端 `MAX_TOTAL_MS` 一致（300 秒）
- 覆盖绝大多数 LLM 调用：即使是带多轮工具调用的复杂请求，也很少超过 5 分钟
- 不超过浏览器和负载均衡器的默认连接超时（通常是 5-10 分钟）

**配置同样出现在 `application.yml`**（位于 Gateway 的 webflux 配置节点下），确保在所有环境下都生效。

### 9.1.2 默认过滤器

```yaml
spring:
  cloud:
    gateway:
      default-filters:
        - DedupeResponseHeader=Access-Control-Allow-Origin
```

`DedupeResponseHeader` 过滤器去除重复的 `Access-Control-Allow-Origin` 响应头。在微服务架构中，CORS 头可能被多个服务层添加（Gateway 自身 + 后端微服务），重复的头会导致浏览器拒绝响应。此过滤器确保最终响应中只有一个 `Access-Control-Allow-Origin` 头。

### 9.1.3 Docker Compose 中的 Gateway

`docker-compose.yml`（第 17-22 行）：

```yaml
lyclaw-gateway:
  build: ./lyclaw-gateway
  ports:
    - "8080:8080"
  depends_on:
    nacos:
      condition: service_healthy
```

Gateway 依赖 Nacos 服务注册中心启动。`condition: service_healthy` 确保 Nacos 健康检查通过后（`curl http://localhost:8848/nacos/v1/console/health/readiness`）才启动 Gateway。这是因为 Gateway 启动时需要从 Nacos 拉取服务注册表，如果 Nacos 不可用，Gateway 将无法路由任何请求。

### 9.1.4 完整微服务拓扑

```
                     ┌─────────────────────┐
  Browser ──:8080──▶ │  lyclaw-gateway     │
  (fetch SSE)        │  (Spring Cloud GW)  │
                     └──────┬──────────────┘
                            │ 服务发现 (Nacos :8848)
              ┌─────────────┼─────────────┬──────────────┬──────────────┐
              ▼             ▼             ▼              ▼              ▼
       orchestration    memory         plan           action         reflect
       :8081            :8082          :8083          :8084          :8085
              │
              ▼
         protocol
         :8086
```

SSE 请求路径：
```
浏览器 → Gateway (:8080) → Orchestration (:8081) → (内部调用) Memory/Plan/Action/Reflect
```

Gateway 的路由规则（在 `GatewayConfig.java` 中定义）将 `/api/chat/stream` 路由到 `lb://lyclaw-orchestration-service`，实现负载均衡。

---

## 9.2 Vite 开发代理

在开发模式下，前端运行在 Vite 开发服务器（默认 `localhost:5173`），后端运行在 `localhost:8080`。Vite 的代理功能将 `/api` 请求从开发服务器转发到后端。

`lyclaw-ui/vite.config.ts`（第 8-26 行）：

```typescript
server: {
  host: '0.0.0.0',
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
},
```

### 9.2.1 为什么需要更改 Cache-Control？

SSE 流**必须不被缓存**。如果代理或浏览器缓存了 SSE 响应，连接将无法正常工作：

1. **浏览器缓存**：浏览器可能缓存响应头的元数据，影响后续 SSE 连接的行为
2. **代理缓存**：反向代理（如 nginx、http-proxy）可能缓冲整个响应，破坏流式传输
3. **服务端推送**：SSE 是服务端主动推送的持久连接，缓存语义会扭曲这一特性

Vite 内置的 `http-proxy` 在某些情况下可能对 SSE 响应添加或继承缓存头。`proxyRes` 事件监听器在代理接收到后端响应后、转发给浏览器前，检查响应类型：

```typescript
if (proxyRes.headers['content-type']?.includes('text/event-stream')) {
  proxyRes.headers['cache-control'] = 'no-cache';
}
```

- 只在响应是 `text/event-stream` 时强制设置 `Cache-Control: no-cache`
- 不影响其他普通 API 请求的响应头
- `no-cache`（而非 `no-store`）：允许缓存但要求每次使用前验证（revalidate），对 SSE 来说等同于不缓存

### 9.2.2 changeOrigin 和 host

```typescript
target: 'http://localhost:8080',
changeOrigin: true,
host: '0.0.0.0',
```

- `target: 'http://localhost:8080'`：所有 `/api/*` 请求被转发到本地的 8080 端口（Spring Cloud Gateway）
- `changeOrigin: true`：修改请求头中的 `Origin` 字段为 target URL 的 origin。这对 CORS 很重要——如果不改，后端看到的 `Origin` 是 `http://localhost:5173`（Vite 开发服务器），可能与 CORS 白名单不匹配
- `host: '0.0.0.0'`：Vite 开发服务器监听所有网络接口，允许局域网内其他设备访问（如手机真机测试）

---

## 9.3 全链路超时对齐表

SSE 流式请求穿越多个系统层，每层都有自己的超时机制。各层超时必须**协调对齐**，否则会导致"上游还没完成，下游已经切断"的尴尬局面。

| 层 | 组件 | 超时值 | 作用范围 | 配置位置 |
|----|------|--------|---------|---------|
| **L1 前端总超时** | AbortController + maxTimer | 300s | 整个 SSE 流（fetch 连接） | `client.ts` 第 96 行 |
| **L1 前端逐块超时** | AbortController + readTimer | 60s | 相邻两个 TCP 数据块之间 | `client.ts` 第 95 行 |
| **L2 Vite 代理** | http-proxy | (继承 Node.js 默认) | /api 请求代理连接 | `vite.config.ts` |
| **L3 Spring Cloud Gateway** | HttpClient 响应超时 | 300s | Gateway → 后端微服务的 HTTP 调用 | `bootstrap.yml` 第 12 行 |
| **L4 微服务 HTTP 客户端** | OkHttpClient | 300s (connect/read/write) | Orchestration → LLM API | `OkHttpModelApiClient` 配置 |
| **L5 工具调用超时** | CountDownLatch.await() | 90s | 单轮工具调用循环 | `ToolCallLoopStage` |
| **L6 LLM API 自身** | 上游 LLM 提供商 | 取决于提供商（通常 120-600s） | API 调用自身 | 不可控 |

### 层次对齐原则

```
前端 300s ≥ Gateway 300s ≥ OkHttp 300s > 工具调用 90s > 逐块 60s

方向：外层 ≥ 内层 ≥ 更内层
```

- **外层不能短于内层**：如果前端 60s 就超时了，但 Gateway 允许 300s，那么 Gateway 还在等待，前端已经放弃——浪费后端资源
- **逐块超时应短于总超时**：60s << 300s。如果 60 秒没数据，大概率有问题，不需要等满 300 秒
- **所有总超时对齐**：前端 300s、Gateway 300s、OkHttp 300s——三层同时到期或几乎同时
- **工具调用在两者之间**：90s > 60s（允许工具执行偶尔慢），但 90s < 300s（不会无限等工具）

### 生产环境额外考量

Docker Compose 环境没有额外的反向代理层。如果部署到 Kubernetes，通常会在 Gateway 前面还有一层 Ingress Controller（如 nginx-ingress），它也有自己的超时设置（`proxy-read-timeout`）。这个值也需要调整到 300s 以上，否则会在 Ingress 层切断 SSE 连接。

---

## 9.4 错误处理全链路

当 SSE 流式请求失败时，错误可能发生在链路的任何一层。每层有各自的错误检测和传播机制。

### 9.4.1 各层错误机制

| 层 | 错误来源 | 错误表现 | 传播方式 | 前端捕获方式 |
|----|---------|---------|---------|------------|
| **Controller** | 请求验证失败（缺少字段、session 不存在） | HTTP 4xx/5xx + JSON 错误体 | HTTP 响应状态码 | `response.ok === false` → `ApiError` |
| **OrchestratorImpl 流水线** | 记忆检索失败、规划超时、工具执行异常 | `sink.next(sseEvent("error", {message}))` + `sink.next(sseEvent("done", {status:"error"}))` | SSE 事件流内的 error 事件 | 当前静默忽略（待增强） |
| **OrchestratorImpl LLM** | LLM API 返回错误（限流、上下文过长） | `onErrorResume` → 降级文本 + `event: done` | Flux 错误恢复操作符 | 正常 `onDone`，但内容为降级文本 |
| **OkHttpModelApiClient** | 网络连接失败、TLS 错误 | `sink.error(e)` 或 IOException | Reactor Flux 错误信号 → 空流 | `reader.read()` 抛异常或 done=true 无内容 |
| **前端 fetch()** | DNS 失败、TCP 拒绝、TLS 不匹配 | TypeError 或 DOMException | fetch() Promise reject | `catch(err)` → `onError(err)` |
| **前端超时 (readTimer)** | 60s 无数据 | `controller.abort()` | fetch abort → `AbortError` | catch → `onError(new ApiError(0, ...))` |
| **前端超时 (maxTimer)** | 300s 总时长 | `controller.abort()` | fetch abort → `AbortError` | catch → `onError(new ApiError(0, ...))` |

### 9.4.2 当前实现的局限性——SSE error 事件未处理

LyClaw 后端通过 `sink.next(sseEvent("error", ...))` 发送错误事件，在 SSE 流中表现为：

```
event: error
data: {"message":"Tool execution failed: timeout"}

event: done
data: {"status":"error","error":"Tool execution failed: timeout"}
```

但前端的 `processLines` 函数**没有特殊处理 `event: error`** 的情况——`currentEvent` 被设为 `'error'`，但后续的 `data:` 行处理中 `currentEvent === 'message'` 为 `false`，数据被静默忽略。

只有当 `event: done` 出现时，`processLines` 返回 `true`，触发 `onDone()`——但此时 `done` 事件携带的 `data`（包含错误信息）也没有被提取。

**建议改进**（未来优化方向）：

```typescript
// 在 processLines 中添加：
if (currentEvent === 'error') {
  // error 事件的 data 行捕获错误详情
  try {
    const err = JSON.parse(data)
    onError(new Error(err.message || 'Unknown SSE error'))
  } catch {
    onError(new Error(data))
  }
  return true
}
```

### 9.4.3 用户发起的取消

用户点击"停止生成"按钮触发 `chatStore.stopGeneration()`，当前实现仅设置 `isStreaming = false`。完整的取消链路（未来实现）应该是：

```
用户点击 Stop
  → chatStore.stopGeneration()
  → controller.abort()         // postSSE 中的 AbortController
  → fetch() 被取消
  → reader.read() reject AbortError
  → catch 分支: onError(new ApiError(0, 'SSE stream aborted', null))
  → chatStore.onError: 保存部分文本，不降级
```

---

## 9.5 资源泄漏防护

SSE 连接是**长连接**，可能持续数分钟。如果资源管理不当，连接意外中断会留下未清理的资源——定时器、Reader 锁、服务端连接的 Flux 订阅。

### 9.5.1 后端资源泄漏防护

**Flux.generate() cleaner 回调**（`OkHttpModelApiClient`）：

```java
Flux.generate(
    () -> { /* 初始化：创建 OkHttp 请求 */ },
    (state, sink) -> {
        // 从 OkHttp 响应流中读取数据，sink.next() 发射
    },
    state -> {
        // ← cleaner 回调：无论正常完成、错误还是取消，都会执行
        state.response.close();
        state.client.dispatcher().executorService().shutdown();
    }
)
```

`Flux.generate()` 的第三个参数是 **state cleaner**。当 Flux 被以下任何方式终止时都会调用：
- `sink.complete()`——正常完成
- `sink.error()`——错误终止
- 下游取消订阅（如客户端断开连接）——Reactor 取消信号

**StreamContext.close() 的三层 try-catch**：

```java
public void close() {
    // 每个资源独立 try-catch，一个失败不影响其他
    try { resource1.close(); } catch (Exception e) { log.warn(...); }
    try { resource2.close(); } catch (Exception e) { log.warn(...); }
    try { resource3.close(); } catch (Exception e) { log.warn(...); }
}
```

**sink.complete() 确保流终止**：

```java
try {
    // 流水线逻辑
} catch (Exception e) {
    sink.next(sseEvent("error", e.getMessage()));
} finally {
    sink.next(sseEvent("done", Collections.singletonMap("status", "completed")));
    sink.complete();  // 无论成功失败，都要终止 Flux
}
```

### 9.5.2 前端资源泄漏防护

**定时器清理**：

```typescript
} finally {
  clearTimers()           // 确保两个定时器都被清除
}
```

`finally` 块保证定时器在以下所有情况下都被清除：
- `reader.read()` 循环正常退出（`done === true` 或 `processLines` 返回 `true`）
- `reader.read()` 抛出异常（`AbortError`、`TypeError` 等）
- 外层 `try/catch` 的 `catch` 块执行完毕后
- 即使 `catch` 块内部抛出新异常

**Reader 锁的隐式释放**：

当 `reader.read()` 抛出 `AbortError` 或其他错误时，`ReadableStream` 的 reader 锁定会自动释放。这是浏览器实现的行为——流遇到错误或取消后，锁被释放，允许后续代码重新获取 reader（如果需要）。

**residual buffer 处理**：

```typescript
if (done) {
  if (buffer.trim()) {
    const lines = buffer.split('\n')
    processLines(lines)         // 不丢失最后一帧数据
  }
  onDone()
  return
}
```

即使流在 TCP FIN 到达前没有完整地结束一个 SSE 事件（理论上不应该发生，但防御性编程），缓冲中的残留数据也会被处理。`buffer.trim()` 确保只处理非空缓冲——避免将纯空格或空白行当作有效数据触发 `onDone` 副作用。

**AbortError 静默处理**：

```typescript
if ((err as Error).name === 'AbortError') {
  onError(new ApiError(0, `SSE stream aborted`, null))
}
```

将 `AbortError` 转为 `ApiError(status=0)` 而非直接抛出。这避免了未处理的 Promise rejection，同时也让上层能区分超时/取消（status=0）和网络错误（status 为实际 HTTP 状态码或通用错误）。

---

## 9.6 生产环境部署检查清单

部署 LyClaw SSE 功能到生产环境时，需要检查和确认以下所有配置：

### 后端配置

- [ ] **Spring Cloud Gateway** `response-timeout` ≥ 300s（`bootstrap.yml`）
- [ ] **OkHttpClient** connect/read/write timeout ≥ 300s
- [ ] **ToolCallLoopStage** `CountDownLatch` timeout 设置合理（建议 90s）
- [ ] **CORS 配置**允许 `Content-Type: application/json` 和 `Accept: text/event-stream` 请求头
- [ ] **日志级别**：SSE 事件发送日志设为 DEBUG（生产环境），避免大量日志影响性能

### 前端配置

- [ ] **Vite proxy**（仅开发环境）中 SSE 响应的 `Cache-Control` 处理
- [ ] **生产构建**：前端通过 nginx/Ingress 反向代理，确认代理配置不会缓冲 SSE 响应
  - nginx: `proxy_buffering off;`
  - nginx: `proxy_read_timeout 300s;`
  - nginx: `proxy_set_header Connection '';`
  - nginx: `chunked_transfer_encoding on;`

### 网络层配置

- [ ] **负载均衡器**（如 AWS ALB、GCP LB）：空闲连接超时 ≥ 300s
- [ ] **防火墙**：不切断长时间空闲的 HTTP 连接
- [ ] **CDN**：SSE 端点排除在 CDN 缓存之外

### 监控和告警

- [ ] **SSE 断开率**：监控客户端异常断开的比例
- [ ] **流时长分布**：P50/P95/P99 流持续时间
- [ ] **超时事件**：60s 逐块超时和 300s 总超时的触发频率
- [ ] **降级率**：流式失败 → 非流式降级的比例

---

# 附录 A——LyClaw SSE 事件类型速查表

| event 类型 | data 载荷格式 | 触发时机 | 前端动作 | 数据示例 |
|-----------|-------------|---------|---------|---------|
| `context_build_start` | 纯文本 | 流水线开始检索会话记忆 | 静默忽略（仅日志） | `"Loading session and retrieving memories"` |
| `context_build_complete` | 纯文本 | 记忆检索完成 | 静默忽略 | `"Loaded session, retrieved 5 memory entries"` |
| `intercept_blocked` | 纯文本 | 安全检查不通过，请求被拦截 | 静默忽略（currentEvent 更新但无匹配） | `"Security check denied: harmful content detected"` |
| `plan_start` | 纯文本 | 开始任务规划（deep-plan 策略） | 静默忽略 | `"Planning task decomposition"` |
| `plan_node` | JSON | 每个规划节点生成时 | 静默忽略 | `{"nodeId":"n1","type":"tool_call","description":"Search web"}` |
| `plan_complete` | 纯文本 | 任务规划完成 | 静默忽略 | `"Planned 3 task(s)"` |
| `action_start` | JSON | 工具调用循环中，每个工具开始执行 | 静默忽略 | `{"index":0,"total":2,"nodeId":"n1"}` |
| `action_result` | JSON | 每个工具执行完毕（含成功/失败） | 静默忽略 | `{"toolName":"web_search","status":"success","output":"...","duration":1234}` |
| `action_complete` | JSON | 工具调用循环完成（所有工具执行完） | 静默忽略 | `{"total":2,"success":2,"failed":0}` |
| `reflect_start` | 纯文本 | 开始反思（reflection loop 启用时） | 静默忽略 | `"Reflecting on execution results"` |
| `reflect_complete` | JSON | 反思完成 | 静默忽略 | `{"score":85,"reflectionId":"r-abc123"}` |
| `respond_start` | 纯文本 | 开始生成 AI 回复（LLM 调用开始） | 静默忽略 | `"Generating AI response"` |
| **`message`** | **纯文本 (LLM token)** | **LLM 流式生成每个文本 token** | **`onChunk(data)` 追加到 `currentStreamingText`** | `"你好"`, `"，"`, `"我"`, `"是"`, `"LyClaw"` |
| `respond_complete` | 纯文本 | 回复生成完成，记忆已持久化 | 静默忽略 | `"Response generated and memory persisted"` |
| `metrics` | JSON | 整个流水线结束后，性能指标统计 | 静默忽略（开发者可在日志中查看） | `{"totalDurationMs":12345,"llmDurationMs":8900,"toolDurationMs":2300,"tokenRate":12.5}` |
| **`done`** | **JSON** | **SSE 流正常结束标记** | **`processLines` 返回 true → `onDone()`** | `{"status":"complete","durationMs":12345}` |
| `error` | JSON | 流水线中发生可恢复的错误 | 静默忽略（待增强：应触发 onError） | `{"message":"Tool execution timeout","toolName":"web_search"}` |

### 事件时序关系

```
一个完整的 SSE 流中的事件顺序（简化版）：

context_build_start
context_build_complete
│
├─[如有工具调用]
│   plan_start
│   plan_node (×N)
│   plan_complete
│   action_start
│   action_result
│   action_complete
│   (可能多轮)
│   reflect_start
│   reflect_complete
│
respond_start
message (×数十到数千)    ← 用户可见的 token 流
respond_complete
│
metrics
done
```

### message 事件细节

`message` 是唯一触发 `onChunk` 的事件。每个 `data:` 行包含一个 LLM 文本 token。token 是 LLM 分词器的产物，不是自然语言的词或字：

- 英文："Hello" 可能是一个 token，" world" 可能是下一个 token（注意前导空格）
- 中文："你好" 可能是一个 token，"世界" 是下一个
- 标点：逗号、句号通常是独立 token
- 代码：缩进中的空格、换行符、关键字都可能是独立 token

前端通过 `currentStreamingText += chunk` 不断拼接，形成最终的自然语言回复。Vue 的响应式系统负责将每次拼接实时渲染到 DOM 中。

---

# 附录 B——curl 测试 SSE

## B.1 流式模式——实时查看 SSE 事件

```bash
curl -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -N \
  --max-time 300 \
  -d '{
    "sessionId": "test-001",
    "messages": [{"role": "user", "content": "你好，请简单介绍一下自己"}],
    "stream": true
  }'
```

参数详解：

| 参数 | 作用 |
|------|------|
| `-X POST` | 指定 HTTP 方法为 POST |
| `-H "Content-Type: application/json"` | 请求体为 JSON |
| `-H "Accept: text/event-stream"` | 请求 SSE 流式响应 |
| `-N` / `--no-buffer` | **关键**：禁用 curl 的输出缓冲。不加此参数，curl 会缓冲所有数据直到连接关闭后才输出——看不到实时效果 |
| `--max-time 300` | 总超时 300 秒，与前端 maxTimer 对齐 |
| `"stream": true` | 请求体中的流式标志 |

预期输出（实时逐行出现）：

```
event: context_build_start
data: Loading session and retrieving memories

event: context_build_complete
data: Loaded session test-001, retrieved 0 memory entries

event: respond_start
data: Generating AI response

event: message
data: 你好

event: message
data: ！我

event: message
data: 是Ly

event: message
data: Claw

event: message
data: ，

event: message
data: 一个

...

event: respond_complete
data: Response generated and memory persisted

event: metrics
data: {"totalDurationMs":3456,"llmDurationMs":2800,"tokenRate":15.2}

event: done
data: {"status":"complete","durationMs":3456}
```

## B.2 非流式模式——等待完整响应

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "test-002",
    "messages": [{"role": "user", "content": "你好"}],
    "stream": false
  }'
```

预期输出（连接关闭后一次性返回）：

```json
{
  "content": "你好！我是 LyClaw，一个人工智能助手...",
  "finishReason": "stop",
  "tokenUsage": "{\"promptTokens\":25,\"completionTokens\":50,\"totalTokens\":75}",
  "toolResults": null,
  "durationMs": 2345
}
```

## B.3 测试不同场景

### 测试超时

```bash
# 模拟复杂请求——可能耗时较长
curl -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -N \
  --max-time 60 \
  -d '{
    "sessionId": "test-timeout",
    "messages": [{"role": "user", "content": "请详细分析量子计算的原理、现状和未来发展趋势"}],
    "stream": true
  }'
```

### 测试错误处理

```bash
# 错误的端点——应返回 404
curl -X POST http://localhost:8080/api/chat/stream-wrong \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"messages": [{"role": "user", "content": "hi"}]}'
```

### 测试 CORS（浏览器环境）

在浏览器控制台中运行：

```javascript
fetch('http://localhost:8080/api/chat/stream', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Accept': 'text/event-stream',
  },
  body: JSON.stringify({
    sessionId: 'test-cors',
    messages: [{ role: 'user', content: 'Hello' }],
    stream: true,
  }),
}).then(async (res) => {
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    console.log(decoder.decode(value, { stream: true }));
  }
});
```

应能在控制台看到逐行输出的 SSE 事件文本。

---

# 附录 C——关键文件索引

## C.1 前端文件

| 文件路径 | 关键内容 | 行数参考 |
|---------|---------|---------|
| `lyclaw-ui/src/api/client.ts` | `postSSE()`——Fetch API + ReadableStream 手动 SSE 消费：双层超时、UTF-8 流式解码、SSE 行解析器、AbortController 取消、错误处理、定时器清理 | 87-217 |
| `lyclaw-ui/src/api/chat.ts` | `postChatStream()`——对 `postSSE` 的封装，指定 `/api/chat/stream` 端点；`postChat()`——非流式降级端点 | 4-15 |
| `lyclaw-ui/src/stores/chat.ts` | `useChatStore`——Pinia Setup Store：`sendMessage()` 流式发送含 onChunk/onDone/onError 回调、`sendMessageNonStreaming()` 非流式降级、`retry()` 重试、`stopGeneration()` 停止 | 39-168 |
| `lyclaw-ui/src/views/ChatView.vue` | 聊天主视图——`isThinking` 计算属性、`tempStreamingMessage` 虚拟消息、双 watcher 自动滚动、思考动画渲染、错误栏 | 23-117 |
| `lyclaw-ui/src/components/MessageBubble.vue` | 消息气泡组件——`showStreamingCursor` 逻辑、`▊` 光标字符、`step-end` blink 动画、用户/助手消息样式差异、Markdown 渲染 | 13-17, 38-39, 162-172 |
| `lyclaw-ui/src/types/index.ts` | TypeScript 类型定义——`Message`、`ChatRequest`、`ChatResult`、`SSEEvent`、`ToolCall`、`Session` 等 | 62-112, 331-334 |
| `lyclaw-ui/vite.config.ts` | Vite 开发服务器代理配置——`/api` 代理到 `localhost:8080`、`proxyRes` 事件检测 SSE 响应并强制 `Cache-Control: no-cache` | 10-25 |
| `lyclaw-ui/src/stores/settings.ts` | 用户设置状态管理——`autoScroll` 控制是否自动滚动到底部 | 69 |
| `lyclaw-ui/src/stores/session.ts` | 会话管理——`createSession()`、`fetchSessions()`、`deleteSession()` | 41-72 |

## C.2 后端文件（跨章节参考）

| 文件路径 | 关键内容 |
|---------|---------|
| `lyclaw-orchestration-service/src/main/java/.../OrchestratorImpl.java` | 核心 SSE 流水线：`pipelineFlux` → `respondFlux` → `concatWith`，`Sinks.Many` 事件发射，`ToolCallLoopStage` 循环 |
| `lyclaw-orchestration-service/src/main/java/.../OrchestrationController.java` | SSE 端点定义：`@PostMapping("/api/chat/stream")`，返回 `Flux<ServerSentEvent<String>>` |
| `lyclaw-orchestration-common/src/main/java/.../ModelAdapter.java` | `chatStream()` 接口定义，`extractSsePlainText()` 文本提取方法 |
| `lyclaw-orchestration-common/src/main/java/.../AbstractModelAdapter.java` | 模板方法：`chatStream` → `buildStreamRequest` → `sendStreamRequest` |
| `lyclaw-orchestration-common/src/main/java/.../DeepSeekOpenAIAdapter.java` | OpenAI 兼容 SSE 解析：`stripSseDataPrefix`、`extractSsePlainText` |
| `lyclaw-orchestration-common/src/main/java/.../OkHttpModelApiClient.java` | `Flux.generate()` 桥接 OkHttp 阻塞 I/O，State Cleaner 资源清理 |
| `lyclaw-orchestration-service/src/main/java/.../ToolCallLoopStage.java` | 工具调用循环，`Sinks.Many` 实时广播，`CountDownLatch` 超时控制 |
| `lyclaw-gateway/src/main/java/.../GatewayConfig.java` | `/api/chat/stream` 路由到 `lb://lyclaw-orchestration-service` |
| `lyclaw-gateway/src/main/resources/bootstrap.yml` | `spring.cloud.gateway.httpclient.response-timeout: 300s` |
| `lyclaw-gateway/src/main/resources/application.yml` | Gateway webflux 层同样配置 `response-timeout: 300s` |

## C.3 基础设施文件

| 文件路径 | 关键内容 |
|---------|---------|
| `docker-compose.yml` | 服务编排：nacos (:8848)、gateway (:8080)、orchestration (:8081)、memory (:8082)、plan (:8083)、action (:8084)、reflect (:8085)、protocol (:8086) |
| `lyclaw-orchestration-service/src/main/resources/application.yml` | 后端服务配置（Nacos 注册、数据库、OkHttp 超时等） |

---

# 附录 D——SSE 协议关键规范速查

## D.1 SSE 流格式（W3C 规范）

```
stream        = 1*event
event         = *(comment / field) end-of-line
comment       = colon *any-char end-of-line
field         = 1*name-char [space [value]] end-of-line
end-of-line   = (cr lf / cr / lf)
```

实际中最常见的格式：

```
event: message\n
data: 你好\n
\n
```

## D.2 LyClaw 使用的字段

| 字段 | 规范定义 | LyClaw 使用方式 |
|------|---------|---------------|
| `event:` | 事件类型，用于客户端按类型分发 | `message`、`done`、`error` 及各种流水线阶段标记 |
| `data:` | 事件数据，可以多行（客户端拼接） | 单行：LLM token（message 事件）、JSON（done/error 事件） |
| `id:` | 事件 ID，用于断点重连（`Last-Event-Id`） | LyClaw 不使用（无需重连） |
| `retry:` | 重连间隔（毫秒） | LyClaw 不使用 |
| `:`（注释） | 注释行，以冒号开头 | LyClaw 不使用 |

## D.3 字符编码

- SSE 流的默认编码为 UTF-8
- BOM（Byte Order Mark, U+FEFF）不应使用，如出现应被忽略
- 行结束符：LF（`\n`）、CR（`\r`）、或 CRLF（`\r\n`）。LyClaw 使用 LF
- 空行（仅包含行结束符）分隔不同的事件

## D.4 浏览器兼容性

| 浏览器 | ReadableStream | TextDecoder | fetch() POST | SSE 支持 |
|--------|---------------|-------------|-------------|---------|
| Chrome 43+ | 43+ | 38+ | 42+ | 6+ |
| Firefox 65+ | 65+ | 18+ | 39+ | 6+ |
| Safari 10.1+ | 10.1+ | 10+ | 10.1+ | 7+ |
| Edge 79+ | 79+ | 12+ | 14+ | 79+ |

LyClaw 的 `fetch()` + `ReadableStream` 方案在所有现代浏览器中都可用。最低要求的 Chrome 版本是 43（2015 年发布），覆盖了几乎所有当前使用的浏览器。

---

# 附录 E——常见问题与排查

## E.1 前端未收到任何 SSE 数据

**现象**：发送消息后，"思考中..."动画一直显示，没有错误也没有内容。

**排查步骤**：

1. 检查浏览器开发者工具 Network 标签：
   - 找到 `/api/chat/stream` 请求
   - 查看状态码是否为 200
   - 查看 Response 标签是否有数据
   - 查看 Timing 标签，确认请求未卡在"Stalled"或"Initial connection"阶段

2. 检查 Gateway 路由：
   ```bash
   curl -N http://localhost:8080/api/chat/stream \
     -H "Content-Type: application/json" \
     -H "Accept: text/event-stream" \
     -d '{"messages":[{"role":"user","content":"hi"}],"stream":true}'
   ```
   若 curl 能收到事件，说明后端正常，问题在前端代理或浏览器。

3. 检查 Vite 代理是否转发 SSE：
   - Vite 控制台是否有代理错误
   - 尝试直接访问 `http://localhost:8080/api/chat/stream`（绕过代理）

## E.2 流式文本出现乱码

**现象**：中文输出某些字符显示为 `�` 或乱码。

**可能原因**：

1. `TextDecoder` 未使用 `{ stream: true }`
   - 检查 `client.ts` 中 `decoder.decode(value, { stream: true })` 是否带参数
2. HTTP 响应头 `Content-Type` 缺少 `charset=utf-8`
   - 检查后端 `OrchestrationController` 的 `@RequestMapping` produces 属性
3. 代理层修改了 Content-Type 字符编码
   - 检查 nginx/Ingress 的 `charset` 指令

## E.3 504 Gateway Timeout 在流式请求中

**现象**：流式输出约 30 秒后突然返回 504 错误。

**原因**：Gateway 默认超时 30 秒，覆盖了流式请求。检查：

1. `bootstrap.yml` 中 `spring.cloud.gateway.httpclient.response-timeout` 是否设为 ≥ 300s
2. 如果使用 nginx 前端代理，`proxy_read_timeout` 是否 ≥ 300s
3. 如果使用 Kubernetes Ingress，ingress controller 的 `proxy-read-timeout` annotation 是否 ≥ 300s

## E.4 流式输出"卡住"不滚动

**现象**：流式文本在增长，但滚动条不自动跟随到最新内容。

**排查**：

1. 检查用户设置中的 `autoScroll` 是否为 `true`
2. 检查 `scrollToBottom()` 中 `settingsStore.autoScroll` 条件
3. 检查 `currentStreamingText` watcher 是否在工作（添加 console.log 验证）

## E.5 开发模式下 CORS 错误

**现象**：浏览器控制台提示 CORS 错误，SSE 连接无法建立。

**解决方案**：

1. 确认 Vite 代理 `changeOrigin: true` 已设置
2. 检查后端 Gateway 的 CORS 配置允许 Vite 开发服务器的 origin（`http://localhost:5173`）
3. 确认 `DedupeResponseHeader=Access-Control-Allow-Origin` 过滤器未错误地删除 CORS 头

---

# 附录 F——文件编写日期与版本信息

| 文档信息 | 内容 |
|---------|------|
| 编写日期 | 2026 年 5 月 11 日 |
| 适用代码版本 | LyClaw main 分支 `d6427d2` |
| 前端框架 | Vue 3.5 + Pinia 3.0 + TypeScript 5.x |
| 后端框架 | Spring Boot 3.x + Spring Cloud Gateway + Project Reactor |
| 构建工具 | Vite 6.x (前端), Gradle (后端) |
