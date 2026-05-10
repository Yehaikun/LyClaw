# LyClaw SSE 流式传输深度教学文档

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
