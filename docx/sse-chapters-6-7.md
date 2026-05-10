# LyClaw SSE流式传输深度解析

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
