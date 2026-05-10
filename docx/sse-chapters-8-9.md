# 第八章：前端SSE消费与渲染

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
