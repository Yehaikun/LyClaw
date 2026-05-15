# paidoding TraceId 与日志追踪系统设计详解

> 调研项目：paidoding（Spring Boot 单体应用）
> 核心机制：SLF4J MDC + Alibaba TransmittableThreadLocal

## 整体架构

```
HTTP 请求进入
    │
    ▼
┌─────────────────────────────────────────────────────┐
│  ReqRecordFilter（@WebFilter）                       │
│  ① 生成 traceId → 放入 MDC                           │
│  ② 构建 ReqInfo → 放入 TransmittableThreadLocal      │
│  ③ 写入响应头 g-trace-id                             │
│  ④ finally: MdcUtil.clear() + ReqInfoContext.clear() │
└─────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────┐
│  @MdcDot AOP 切面（可选）                             │
│  ① 解析 SpEL 表达式提取 bizCode                       │
│  ② MdcUtil.add("bizCode", value)                     │
│  ③ finally: MdcUtil.reset()（保留 traceId，清除其他） │
└─────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────┐
│  业务逻辑（可能使用 AsyncUtil 提交异步任务）            │
│  asyncExecutor = TtlExecutors.getTtlExecutorService() │
│  → 子线程自动继承父线程的 MDC 和 ReqInfoContext       │
└─────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────┐
│  Logback 输出                                        │
│  格式：[时间] [线程] 级别|traceId|bizCode|logger.M(line) - msg │
└─────────────────────────────────────────────────────┘
```

三层设计：
1. **MDC 层**：存 `traceId` 和 `bizCode`，日志中可见
2. **ReqInfoContext 层**：存完整请求上下文（userId、IP、路径等），应用内取用
3. **HTTP Header 层**：`g-trace-id` 响应头，供前端/客户端关联

---

## 一、TraceId 生成策略

项目提供两套生成器，默认使用第一种。

### 策略 A：SelfTraceIdGenerator（默认，阿里云规范）

**格式：** `{IP_HEX}.{TIMESTAMP}.{PID}{SEQ}`（32 字符）

```
示例：ac13e001.1685348263825.095001000
      ─────── ───────────── ──────
       IP十六进制  毫秒时间戳   PID+序号
```

**四部分详解：**

| 段 | 位数 | 来源 | 示例 | 说明 |
|----|------|------|------|------|
| IP_HEX | 8 字符 | `InetAddress.getLocalHost().getHostAddress()` 转十六进制 | `ac13e001` | 39.105.208.175 → 每段转 hex 拼接 |
| TIMESTAMP | 13 字符 | `System.currentTimeMillis()` | `1685348263825` | 生成时毫秒时间戳 |
| PID | 5 字符 | `ManagementFactory.getRuntimeMXBean().getName().split("@")[0]` | `09500` | JVM 进程 ID，不足 5 位左补零 |
| SEQ | 4 字符 | `ThreadLocal<Integer>` 自增，1000→9999 循环 | `1000` | 每线程独立计数，递增到 9999 后回绕 |

**异常兜底：** 生成过程抛任何异常，降级为 `UUID.randomUUID().toString().replaceAll("-", "")`。

```java
// 核心代码（简化）
public static String generate() {
    try {
        String ipHex = getIpHex(InetAddress.getLocalHost().getHostAddress());
        long timestamp = System.currentTimeMillis();
        String pid = getPid();
        String seq = getSeq(); // ThreadLocal 自增
        return ipHex + "." + timestamp + "." + pid + seq;
    } catch (Exception e) {
        return UUID.randomUUID().toString().replaceAll("-", "");
    }
}
```

**优点：** 可从 traceId 反解出生成时间、服务器 IP，方便排查。**缺点：** 依赖 `InetAddress.getLocalHost()`，Docker 容器内可能返回容器 ID 而非宿主机 IP。

### 策略 B：SkyWalkingTraceIdGenerator（备用，天行规范）

复制自 Apache SkyWalking 的 `GlobalIdGenerator`，**代码已写好但默认不使用**。

**格式：** `{PROCESS_ID}.{THREAD_ID}.{TIMESTAMP_SEQ}`

```
示例：d3b0d9f0e1c24a7b8f9a0c1d2e3f4a5b.12.16853482638250000
      ──────────────────────────────── ── ──────────────────
              进程实例 ID（UUID）      线程ID   时间戳×10000+序号
```

**三部分详解：**

| 段 | 来源 | 说明 |
|----|------|------|
| PROCESS_ID | `UUID.randomUUID().toString().replaceAll("-", "")` | JVM 实例一旦生成就固定，进程生命周期内不变 |
| THREAD_ID | `Thread.currentThread().getId()` | 当前线程 ID |
| TIMESTAMP_SEQ | `timestamp() * 10000 + threadSeq` | `threadSeq` 每线程独立自增 0→9999，带时钟回拨处理 |

与阿里云格式的核心区别：用 UUID 代替 IP，不暴露服务器地址。

---

## 二、TraceId 进入系统的方式

### HTTP 请求入口：ReqRecordFilter

```java
@WebFilter(urlPatterns = "/*", filterName = "reqRecordFilter", asyncSupported = true)
public class ReqRecordFilter implements Filter {
    // 注册方式：启动类 @ServletComponentScan 自动扫描

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        try {
            initReqInfo(req, res);       // ① 生成 traceId + 构建请求上下文
            chain.doFilter(req, res);    // ② 执行业务
        } finally {
            MdcUtil.clear();            // ③ 清理 MDC
            ReqInfoContext.clear();     // ③ 清理 ThreadLocal
        }
    }
}
```

**`initReqInfo()` 做的事（按顺序）：**

1. **生成 traceId** → `MdcUtil.addTraceId()`
   - 调用 `SelfTraceIdGenerator.generate()` 生成 32 字符 traceId
   - 放入 `MDC.put("traceId", value)`

2. **构建 ReqInfo 对象**（请求上下文 DTO）：
   - `host` → 请求 Host 头
   - `path` → 请求 URI
   - `userAgent` → User-Agent 头
   - `referer` → Referer 头
   - `clientIp` → 从 X-Forwarded-For / X-Real-IP / Proxy-Client-IP 逐级取，最后取 `request.getRemoteAddr()`
   - `deviceId` → 从 Cookie `deviceId` 读取
   - `userId` → 从请求 Attribute（由 Spring Security Filter 前置设置）或 Session 读取

3. **存入 TransmittableThreadLocal** → `ReqInfoContext.addReqInfo(reqInfo)`

4. **写响应头** → `response.setHeader("g-trace-id", traceId)`
   - 前端/客户端可通过响应头拿到本次请求的 traceId

### WebSocket 入口（三处）

| 入口 | 类 | 设置方式 |
|------|-----|---------|
| STOMP 握手拦截 | `AuthHandshakeInterceptor.beforeHandshake()` | 生成 traceId → 存到 WebSocket Session attributes |
| 简单 WS 握手拦截 | `SimpleWsAuthInterceptor.beforeHandshake()` | 直接调用 `MdcUtil.addTraceId()` |
| WS 消息处理 | `WsAnswerHelper.execute()` / `WebSocketResponseUtil.execute()` | 从 Session attributes 取出 traceId → `MdcUtil.add()`，finally 清理 |

三种入口都遵循同一模式：**设置 → 执行 → finally 清理**。

---

## 三、AOP 业务上下文标记：@MdcDot

在已有 traceId 的基础上，通过注解给日志附加**业务标识**（如当前操作的文章 ID）。

### 注解定义

```java
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MdcDot {
    String bizCode() default "";  // 支持 SpEL 表达式
}
```

### 切面逻辑

```java
@Aspect
public class MdcAspect {

    @Around("@annotation(MdcDot) || @within(MdcDot)")
    public Object handle(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1. 解析 SpEL 表达式 → bizCode 的值
        String bizCode = parseBizCode(joinPoint);
        // 2. 放入 MDC
        MdcUtil.addBizCode(bizCode);

        long start = System.currentTimeMillis();
        try {
            return joinPoint.proceed();  // 执行目标方法
        } finally {
            MdcUtil.reset();  // 清除 bizCode 但保留 traceId
            logCost(start);   // 打印耗时
        }
    }
}
```

**关键设计：`MdcUtil.reset()` vs `MdcUtil.clear()`**

| 方法 | 行为 | 适用场景 |
|------|------|---------|
| `clear()` | 全部清空 | 请求结束（Filter finally） |
| `reset()` | 清除**除 traceId 外**的所有 key | @MdcDot 切面结束（保留 traceId 给后续方法用） |

### 使用示例

```java
// 记录查看文章详情的日志，带上文章 ID
@MdcDot(bizCode = "#articleId")
public ArticleDetail getArticleDetail(Long articleId) { ... }

// 记录发文章请求，从请求体取文章 ID
@MdcDot(bizCode = "#req.articleId")
public void postArticle(ArticleReq req) { ... }

// 不指定 bizCode，仅记录方法耗时
@MdcDot
public void subscribe() { ... }
```

日志中 bizCode 列的效果：

```
2026-05-15 10:30:45 [http-nio-8080-exec-1] INFO |ac13e001...||com...Controller.getDetail(Controller.java:12) - 进入方法
2026-05-15 10:30:45 [http-nio-8080-exec-1] INFO |ac13e001...|article_12345|com...Service.query(Service.java:34) - 查询文章
```

bizCode 只在其所在切面范围内有效，切面退出后 `reset()` 清除，嵌套的 `@MdcDot` 各自独立。

---

## 四、跨线程传递：TransmittableThreadLocal

### 为什么需要

MDC 底层是 `ThreadLocal`，子线程默认不继承父线程的 MDC。当业务用 `@Async` 或线程池执行异步任务时，直接提交任务会导致子线程日志丢失 traceId。

### 解决方案：Alibaba TTL

```xml
<dependency>
    <groupId>com.alibaba</groupId>
    <artifactId>transmittable-thread-local</artifactId>
    <version>2.14.5</version>
</dependency>
```

**包含两个使用层面：**

**层面 1：ReqInfoContext 直接用 TTL 替换 ThreadLocal**

```java
public class ReqInfoContext {
    // 关键：这里的 TTL 能自动被子线程继承
    private static TransmittableThreadLocal<ReqInfo> contexts
            = new TransmittableThreadLocal<>();

    public static void addReqInfo(ReqInfo reqInfo) { contexts.set(reqInfo); }
    public static ReqInfo getReqInfo()               { return contexts.get(); }
    public static void clear()                       { contexts.remove(); }
}
```

**层面 2：AsyncUtil 用 TTL 包装线程池**

```java
public class AsyncUtil {
    private static ExecutorService executorService;

    public static void init() {
        executorService = new ThreadPoolExecutor(...);

        // 关键：用 TtlExecutors 包裹后，提交任务时自动复制父线程的 TTL
        executorService = TtlExecutors.getTtlExecutorService(executorService);
    }

    // 提交异步任务
    public static void execute(Runnable task) {
        executorService.execute(task);
    }
}
```

**TTL 对 MDC 的兼容性：** TTL 包装的线程池在任务提交时，会拦截 Runnable 并复制父线程的所有 `TransmittableThreadLocal`。虽然 MDC 本身用的是 SLF4J 的 `ThreadLocal`，但 paidoding 的实践证明在 TTL 包装的线程池中 MDC 也能正确传递。

**传递链路：**

```
HTTP 线程（traceId=xxx）
    │
    │  AsyncUtil.execute(task)
    │      │
    │      ▼ TtlExecutors 拦截
    │      复制 MDC + ReqInfoContext → 绑定到 task
    │
    ▼
工作线程（TtlExecutor 自动设置 MDC.traceId=xxx，ReqInfoContext=原值）
    │
    │  日志输出：|xxx|bizCode|... → traceId 不丢失
    │
    ▼
任务执行完毕，MDC + TTL 随线程回收清理
```

---

## 五、日志配置（Logback）

### Console 输出格式

```xml
<appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
        <pattern>%d [%t] %-5level|%mdc{traceId}|%mdc{bizCode}|%logger{36}.%M\(%file:%line\) - %msg%n</pattern>
    </encoder>
</appender>
```

**输出示例：**

```
2026-05-15 10:30:45.123 [http-nio-8080-exec-1] INFO |ac13e001.1685348263825.095001000||c.g.p.f.w.h.f.ReqRecordFilter.doFilter(ReqRecordFilter.java:78) - 请求进入
2026-05-15 10:30:45.456 [http-nio-8080-exec-1] INFO |ac13e001.1685348263825.095001000|article_12345|c.g.p.f.s.ArticleService.query(ArticleService.java:34) - 查询文章
```

格式解读：`时间 [线程名] 级别 |traceId|bizCode|类.方法(文件:行号) - 消息`

### 文件输出格式

```xml
<!-- 服务日志 -->
<appender name="service-file" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <encoder>
        <pattern>[%d{yyyy-MM-dd HH:mm:ss}]|%mdc{traceId}|%mdc{bizCode}|{"logger":"%logger{36}","thread":"%thread","msg":"%msg"}%n</pattern>
    </encoder>
</appender>

<!-- 请求日志（无 bizCode 列） -->
<appender name="req-file" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <encoder>
        <pattern>[%d{yyyy-MM-dd HH:mm:ss}|%mdc{traceId}|] - %msg%n</pattern>
    </encoder>
</appender>
```

服务日志多了 JSON 结构的 logger/thread/msg，方便日志平台（ELK）采集解析。请求日志只保留时间和 traceId，轻量化。

### 日志布局总结

| 位置 | 格式 | 用途 |
|------|------|------|
| Console | `时间 [线程] 级别|traceId|bizCode|类.方法(文件:行) - 消息` | 开发调试 |
| service-file | `[时间]|traceId|bizCode|{JSON}` | ELK 采集 |
| req-file | `[时间|traceId|] - 消息` | 请求记录 |

---

## 六、清理策略

**每处设置 traceId 的代码，都在 finally 中清理：**

| 入口 | 设置 | 清理 |
|------|------|------|
| `ReqRecordFilter.doFilter()` | `MdcUtil.addTraceId()` | `MdcUtil.clear()` + `ReqInfoContext.clear()` |
| `MdcAspect` (@MdcDot) | `MdcUtil.addBizCode()` | `MdcUtil.reset()`（保留 traceId） |
| `WsAnswerHelper.execute()` | `MdcUtil.add(traceId)` | `MdcUtil.clear()` + `ReqInfoContext.clear()` |
| `WebSocketResponseUtil.execute()` | `MdcUtil.add(traceId)` | `MdcUtil.clear()` + `ReqInfoContext.clear()` |
| `SimpleWsAuthInterceptor` | `MdcUtil.addTraceId()` | `MdcUtil.clear()` + `ReqInfoContext.clear()` |

**为什么必须清理：** 线程池中的线程会复用，上一次请求的 MDC 残留会污染下一次请求。finally 块保证无论业务抛不抛异常，MDC 都会被清空。

---

## 七、MdcUtil 工具类设计

```java
public class MdcUtil {
    public static final String TRACE_ID_KEY = "traceId";
    private static final String BIZ_CODE_KEY = "bizCode";

    // 生成并添加 traceId
    public static void addTraceId() {
        MDC.put(TRACE_ID_KEY, SelfTraceIdGenerator.generate());
    }

    // 手动设置 traceId（WebSocket 恢复时用）
    public static void addTraceId(String traceId) {
        MDC.put(TRACE_ID_KEY, traceId);
    }

    // 添加业务标识
    public static void addBizCode(String bizCode) {
        MDC.put(BIZ_CODE_KEY, bizCode);
    }

    // 清除除 traceId 外的所有 key（@MdcDot 切面结束时用）
    public static void reset() {
        String traceId = MDC.get(TRACE_ID_KEY);
        MDC.clear();
        if (traceId != null) {
            MDC.put(TRACE_ID_KEY, traceId);
        }
    }

    // 全量清除（请求结束时用）
    public static void clear() {
        MDC.clear();
    }
}
```

**reset() 和 clear() 的语义：**

```
请求进入 → addTraceId()     MDC: {traceId: "xxx"}
           │
  @MdcDot 进入 → addBizCode()  MDC: {traceId: "xxx", bizCode: "article_1"}
  @MdcDot 退出 → reset()       MDC: {traceId: "xxx"}               ← bizCode 清除
           │
  业务方法 → addBizCode()      MDC: {traceId: "xxx", bizCode: "article_2"}
  业务方法 → reset()           MDC: {traceId: "xxx"}
           │
请求结束 → clear()             MDC: {}                              ← 全部清除
```

---

## 八、HTTP 响应头：g-trace-id

`ReqRecordFilter` 在每次请求处理后设置响应头：

```java
response.setHeader("g-trace-id", traceId);
```

**用途：**
- 前端在浏览器 Network 面板看到请求的 traceId
- 前端报错时把 traceId 反馈给后端，后端用它搜索日志
- 如果前端也做日志上报（Sentry 等），可以带上 traceId 关联前后端

paidoding **不从请求头读取** traceId——就是说不管前端传不传 `g-trace-id`，后端都自己生成一个新的。这意味着 traceId 不跨服务传递（单体应用不需要）。

---

## 九、ReentrantLock + Condition 的巧妙用法

`WsAnswerHelper` 在 STOMP WebSocket 消息处理中，需要跨线程传递 traceId，用了一个精巧的设计：

```java
public void execute(SimpMessageHeaderAccessor accessor, Runnable run) {
    String traceId = accessor.getSessionAttributes().get(MdcUtil.TRACE_ID_KEY);
    ReqInfo reqInfo = ...;

    // 用锁和条件同步，保证 traceId 设置后才执行业务
    ReentrantLock lock = new ReentrantLock();
    Condition condition = lock.newCondition();

    executor.execute(() -> {
        lock.lock();
        try {
            MdcUtil.add(MdcUtil.TRACE_ID_KEY, traceId);
            ReqInfoContext.addReqInfo(reqInfo);
            condition.signal();  // 通知等待线程：上下文已就绪
        } finally {
            lock.unlock();
        }
        try {
            run.run();  // 执行实际业务
        } finally {
            MdcUtil.clear();
            ReqInfoContext.clear();
        }
    });

    lock.lock();
    try {
        condition.await();  // 等待工作线程设置完上下文
    } finally {
        lock.unlock();
    }
}
```

这个设计的目的是：在 WebSocket 消息处理的异步子线程中，**保证 traceId 设置完成后再开始执行业务逻辑**，避免因 MDC 未就绪而输出无 traceId 的日志。

---

## 十、与 LyClaw 的对比

| 维度 | paidoding | LyClaw（当前） |
|------|-----------|----------------|
| 架构 | 单体 Spring Boot | 微服务（gateway + 多 service） |
| traceId 生成 | 阿里云规范（IP+时间戳+PID+序号） | 无（仅在 Tracing 中用 UUID） |
| MDC | ★ 全链路（Filter→AOP→Async→finally） | 无 MDC |
| 跨线程传递 | Alibaba TTL 2.14.5 | 无 |
| 日志格式 | `时间线程级别|traceId|bizCode|类.方法(文件:行)` | 无 traceId 列 |
| 响应头 | `g-trace-id` | 无 |
| WebSocket | 握手拦截器设置 + finally 清理 | 无 |
| AOP 业务标记 | `@MdcDot` + SpEL | 无 |
| 服务间传递 | 不需要（单体） | 需要（Feign/RestTemplate 拦截器） |

**LyClaw 可以从 paidoding 借鉴的：**

1. MDC + TTL 作为 traceId 核心基础设施
2. Filter 统一设置、finally 统一清理的模式
3. `@MdcDot` 注解给日志附加业务上下文
4. `g-trace-id` 响应头让前端可追踪
5. logback 格式中 `%mdc{traceId}` 作为固定列
6. **额外需要的**：Feign 拦截器传递 traceId（`RequestInterceptor` 把 MDC traceId 写入请求头）、Gateway 入口生成 traceId 并全局透传
