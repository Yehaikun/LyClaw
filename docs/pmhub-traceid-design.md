# PmHub TraceId 与日志追踪系统设计详解

> 调研项目：PmHub（Spring Cloud 微服务，8 个模块）
> 结论：项目当前无 traceId 系统，但拥有添加 traceId 所需的完整基础设施骨架

## 项目架构概览

```
客户端请求
    │
    ▼
┌─────────────────────────────────────────────────────┐
│  pmhub-gateway（6880）                               │
│  ├── AuthFilter（GlobalFilter, order=-200）          │ ← ★ traceId 生成点
│  ├── XssFilter                                       │
│  ├── BlackListUrlFilter                              │
│  ├── ValidateCodeFilter                              │
│  └── CacheRequestFilter                              │
└─────────────────────────────────────────────────────┘
    │  路由到下游服务
    ▼
┌─────────────────────────────────────────────────────┐
│  pmhub-auth / pmhub-system / pmhub-project 等        │
│  ├── HeaderInterceptor（AsyncHandlerInterceptor）    │ ← ★ traceId 提取点
│  │     ↓ 从请求头取 → 放入 SecurityContextHolder(TTL) │
│  ├── @LogAspect（操作审计日志）                       │
│  ├── @PreAuthorizeAspect（权限）                     │
│  └── 业务逻辑                                        │
│       │
│       │ Feign 调用其他服务
│       ▼
│  FeignRequestInterceptor                             │ ← ★ traceId 透传点
│     ↓ 从 SecurityContextHolder 取 → 写入请求头        │
└─────────────────────────────────────────────────────┘
```

---

## 一、当前请求拦截基础设施（无 traceId，但有完整骨架）

### 1.1 网关入口：AuthFilter

**文件：** `pmhub-gateway/src/main/java/com/laigeoffer/pmhub/gateway/filter/AuthFilter.java`

- 实现 `GlobalFilter, Ordered`（order = -200，最早执行）
- 是**所有请求的第一入口**

**当前做的事：**

```
请求进入
  │
  ① 从请求头取 JWT token
  ② 解析 token → 提取 userid / username / userkey
  ③ 把用户信息注入到下游请求头（mutate request）：
     ├── "user_id"       → userid
     ├── "username"      → username
     └── "user_key"      → userkey
  ④ 记录接口访问日志（开始时间、请求URI）
  ⑤ chain.filter(exchange) → 放行
  ⑥ 请求返回后记录耗时
```

**关键代码（简化）：**

```java
@Override
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    ServerHttpRequest request = exchange.getRequest();
    String token = getToken(request);

    // 解析 JWT claims
    String userid = JwtUtils.getUserId(token);
    String username = JwtUtils.getUserName(token);
    String userkey = JwtUtils.getUserKey(token);

    // 注入到下游请求头
    ServerHttpRequest.Builder mutate = request.mutate();
    addHeader(mutate, SecurityConstants.DETAILS_USER_ID, userid);
    addHeader(mutate, SecurityConstants.DETAILS_USERNAME, username);
    addHeader(mutate, SecurityConstants.USER_KEY, userkey);

    return chain.filter(exchange.mutate().request(mutate.build()).build());
}
```

**分析：** 这是添加 traceId 的**黄金位置**——在请求处理开始时生成一个 traceId，注入到下游请求头 `X-Trace-Id`。所有后续服务和日志就都能拿到这个 ID。

### 1.2 服务端接收：HeaderInterceptor

**文件：** `pmhub-base/pmhub-base-security/src/main/java/com/laigeoffer/pmhub/base/security/interceptor/HeaderInterceptor.java`

- 实现 `AsyncHandlerInterceptor`
- 在 `WebMvcConfig` 中注册（拦截所有路径，排除 `/login` `/logout` `/refresh`）
- order = -10

**当前做的事：**

```
请求到达服务
  │
  preHandle():
  ① 从请求头取 user_id / username / user_key
  ② 存入 SecurityContextHolder(TTL)
  ③ 解析 token → 构建 LoginUser 对象 → 也存入 SecurityContextHolder
  │
  业务逻辑执行...
  │
  afterCompletion():
  ① SecurityContextHolder.remove()  ← 清理 TTL，防止内存泄漏
```

**关键代码（简化）：**

```java
@Override
public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                         Object handler) {
    // 从请求头提取 → 放入 TTL 上下文
    SecurityContextHolder.setUserId(request.getHeader("user_id"));
    SecurityContextHolder.setUserName(request.getHeader("username"));
    SecurityContextHolder.setUserKey(request.getHeader("user_key"));

    // 解析 LoginUser 也放入上下文
    String token = SecurityUtils.getToken(request);
    LoginUser loginUser = tokenService.getLoginUser(token);
    SecurityContextHolder.set(SecurityConstants.LOGIN_USER, loginUser);

    return true;
}

@Override
public void afterCompletion(...) {
    SecurityContextHolder.remove();  // ★ 请求结束一定清理
}
```

**分析：** 这是 traceId 的**接收和激活点**——从请求头取出 `X-Trace-Id`，放入 MDC（`MDC.put("traceId", value)`）。`afterCompletion` 中 `SecurityContextHolder.remove()` + `MDC.clear()` 同行，保证不泄漏。

### 1.3 服务间调用：FeignRequestInterceptor

**文件：** `pmhub-base/pmhub-base-security/src/main/java/com/laigeoffer/pmhub/base/security/feign/FeignRequestInterceptor.java`

- 实现 `feign.RequestInterceptor`
- 通过 `FeignAutoConfiguration` 自动配置

**当前做的事：**

每次 Feign 调用前，从当前请求头复制以下字段到目标请求头：

```java
@Override
public void apply(RequestTemplate requestTemplate) {
    HttpServletRequest request = getHttpServletRequest();
    if (request != null) {
        // 逐个头复制到 Feign 请求
        requestTemplate.header(SecurityConstants.DETAILS_USER_ID,
            request.getHeader(SecurityConstants.DETAILS_USER_ID));
        requestTemplate.header(SecurityConstants.USER_KEY,
            request.getHeader(SecurityConstants.USER_KEY));
        requestTemplate.header(SecurityConstants.DETAILS_USERNAME,
            request.getHeader(SecurityConstants.DETAILS_USERNAME));
        requestTemplate.header(SecurityConstants.AUTHORIZATION_HEADER,
            request.getHeader(SecurityConstants.AUTHORIZATION_HEADER));
    }
}
```

**分析：** 这是 traceId 的**跨服务传播点**——加一行 `requestTemplate.header("X-Trace-Id", MDC.get("traceId"))` 就能把 traceId 传递到下游。

### 1.4 上下文持有：SecurityContextHolder

**文件：** `pmhub-base/pmhub-base-core/src/main/java/com/laigeoffer/pmhub/base/core/context/SecurityContextHolder.java`

```java
public class SecurityContextHolder {
    // 关键：使用 TransmittableThreadLocal，子线程自动继承
    private static final TransmittableThreadLocal<Map<String, Object>> THREAD_LOCAL
            = new TransmittableThreadLocal<>();

    public static void set(String key, Object value) { getMap().put(key, value); }
    public static Long getUserId() { return get("user_id"); }
    public static String getUserName() { return get("username"); }
    // ...
    public static void remove() { THREAD_LOCAL.remove(); }
}
```

**设计特点：**
- 用 `TransmittableThreadLocal`（Alibaba TTL v2.14.4）而非普通 `ThreadLocal`，子线程自动继承
- Map 结构而非多个 ThreadLocal 变量，扩展性好（新增 key 不用加新变量）
- 集中管理所有请求级别上下文（用户信息 + 可扩展 traceId）

---

## 二、线程池与异步基础设施

### 2.1 主业务线程池

**文件：** `pmhub-base/pmhub-base-core/src/main/java/com/laigeoffer/pmhub/base/core/config/ThreadPoolConfig.java`

```java
@Bean(name = "threadPoolTaskExecutor")
public ThreadPoolTaskExecutor threadPoolTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setMaxPoolSize(200);
    executor.setCorePoolSize(50);
    executor.setQueueCapacity(1000);
    executor.setKeepAliveSeconds(300);
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    return executor;  // ⚠️ 无 TaskDecorator，MDC 上下文在子线程中丢失
}
```

### 2.2 异步任务管理器（审计日志）

**文件：** `pmhub-boot/pmhub-framework/src/main/java/com/laigeoffer/pmhub/framework/manager/AsyncManager.java`

```java
public class AsyncManager {
    private ScheduledExecutorService executor;

    public void execute(TimerTask task) {
        executor.schedule(task, 10, TimeUnit.MILLISECONDS);  // 10ms 延迟
    }
}
```

**文件：** `pmhub-boot/pmhub-framework/src/main/java/com/laigeoffer/pmhub/framework/manager/factory/AsyncFactory.java`

- `recordLogininfor()` — 记录登录日志
- `recordOper()` — 记录操作日志

**分析：** 两处线程池都**没有 MDC 传播**。`ThreadPoolTaskExecutor` 没有 `TaskDecorator`，`ScheduledExecutorService` 也是裸的。异步任务日志会丢失所有 MDC 上下文。

---

## 三、日志配置

所有微服务模块使用同一套 logback 模板，文件位于各模块的 `src/main/resources/logback.xml`。

### 3.1 微服务模块格式（gateway/auth/system/project 等）

```xml
<configuration>
    <property name="log.path" value="logs/pmhub-system"/>
    <property name="log.pattern"
              value="%d{HH:mm:ss.SSS} [%thread] %-5level %logger{20} - [%method,%line] - %msg%n"/>

    <!-- INFO 日志，60 天滚动 -->
    <appender name="sys-info" class="...RollingFileAppender">
        <file>${log.path}/info.log</file>
        <rollingPolicy class="...TimeBasedRollingPolicy">
            <fileNamePattern>${log.path}/info.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>60</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>${log.pattern}</pattern>
        </encoder>
    </appender>

    <!-- ERROR 日志 -->
    <appender name="sys-error" class="...RollingFileAppender">
        <filter class="...LevelFilter">
            <level>ERROR</level>
        </filter>
        ...
    </appender>

    <root level="INFO">
        <appender-ref ref="sys-info"/>
        <appender-ref ref="sys-error"/>
    </root>
</configuration>
```

**当前格式：** `HH:mm:ss.SSS [线程] 级别 logger - [方法,行号] - 消息`

**缺了什么：** `%X{traceId}` 或 `%mdc{traceId}`

### 3.2 Admin 模块格式

**文件：** `pmhub-boot/pmhub-admin/src/main/resources/logback-spring.xml`

单独的 DEBUG/INFO/WARN/ERROR 文件 + AsyncAppender 包装。格式类似，也**没有 traceId**。

---

## 四、请求处理全链路（当前实际流程）

```
客户端
  │  GET /api/system/user/list
  ▼
pmhub-gateway:6880
  │
  ├── AuthFilter（order=-200）
  │     ├── 解析 JWT → 拿到 userId=1001, username=zhangsan
  │     ├── mutate 请求：添加 user_id / username / user_key 头
  │     ├── log.info("接口访问: GET /api/system/user/list")  ← 无 traceId
  │     └── chain.filter()
  │
  ▼  路由到 pmhub-system:6801
  │
  ├── HeaderInterceptor.preHandle()
  │     ├── 从请求头取 user_id → SecurityContextHolder.setUserId(1001)
  │     ├── 从请求头取 username → SecurityContextHolder.setUserName("zhangsan")
  │     └── 解析 LoginUser → SecurityContextHolder.set("login_user", ...)
  │
  ├── Controller 处理
  │     ├── @Log 注解触发 LogAspect → 记录操作日志到数据库
  │     └── 调用 Service
  │
  ├── Service 中 Feign 调用 pmhub-project
  │     └── FeignRequestInterceptor.apply()
  │           └── 从当前请求头复制 user_id/username/user_key/authorization
  │                 → 下游 pmhub-project 收到相同的用户头
  │
  ├── HeaderInterceptor.afterCompletion()
  │     └── SecurityContextHolder.remove()  ← 清理
  │
  ▼
响应返回客户端
```

---

## 五、当前架构的差距与补全方案

### 差距清单

| 差距 | 现状 | 影响 |
|------|------|------|
| **无 traceId 生成** | AuthFilter 不生成 | 无法追踪一个请求的完整链路 |
| **无 MDC** | 从来没有 `MDC.put` | traceId 无法出现在日志中 |
| **logback 无 %X{traceId}** | 所有 pattern 都缺 | 即使有 MDC，日志也看不见 |
| **ThreadPoolTaskExecutor 无 TaskDecorator** | 裸线程池 | 异步任务日志丢失上下文 |
| **AsyncManager 无 TTL 包装** | 裸 ScheduledExecutor | 审计日志异步线程无上下文 |
| **Feign 不传 traceId** | 只传用户头 | 跨服务 traceId 断裂 |
| **无响应头** | 不返回 traceId | 前端报错时无法提供 traceId 给后端排查 |

### 补全方案（基于现有骨架，最小改动）

**1. AuthFilter 生成 traceId：**

```java
// 在 AuthFilter.filter() 里，请求处理开始处
String traceId = IdUtils.fastSimpleUUID();  // 已有工具类
mutate.header("X-Trace-Id", traceId);
exchange.getResponse().getHeaders().add("X-Trace-Id", traceId);  // 返回给前端
```

**2. HeaderInterceptor 提取并激活 MDC：**

```java
// 在 HeaderInterceptor.preHandle() 开头
String traceId = request.getHeader("X-Trace-Id");
if (traceId == null) traceId = IdUtils.fastSimpleUUID();  // 兜底
MDC.put("traceId", traceId);

// 在 afterCompletion() 末尾
MDC.clear();  // 与 SecurityContextHolder.remove() 同行
```

**3. FeignRequestInterceptor 透传：**

```java
// 加一行
String traceId = MDC.get("traceId");
if (traceId != null) requestTemplate.header("X-Trace-Id", traceId);
```

**4. logback pattern 加 traceId：**

```xml
<property name="log.pattern"
  value="%d{HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{20} - [%method,%line] - %msg%n"/>
```

**5. ThreadPoolConfig 加 TaskDecorator：**

```java
executor.setTaskDecorator(task -> {
    Map<String, String> mdcContext = MDC.getCopyOfContextMap();
    return () -> {
        if (mdcContext != null) MDC.setContextMap(mdcContext);
        try { task.run(); }
        finally { MDC.clear(); }
    };
});
```

**6. AsyncManager 用 TTL 包装：**

```java
// 初始化时包装
executor = TtlExecutors.getTtlScheduledExecutorService(
    new ScheduledThreadPoolExecutor(corePoolSize));
```

---

## 六、安全常量（SecurityConstants）

**文件：** `pmhub-base/pmhub-base-core/src/main/java/com/laigeoffer/pmhub/base/core/constant/SecurityConstants.java`

```java
public class SecurityConstants {
    public static final String DETAILS_USER_ID = "user_id";
    public static final String DETAILS_USERNAME = "username";
    public static final String USER_KEY = "user_key";
    public static final String AUTHORIZATION_HEADER = "authorization";
    public static final String FROM_SOURCE = "from-source";
    public static final String INNER = "inner";
    public static final String LOGIN_USER = "login_user";
    public static final String ROLE_PERMISSION = "role_permission";
    // 可新增：
    // public static final String TRACE_ID = "X-Trace-Id";
}
```

---

## 七、ID 生成工具（已有）

**文件：** `pmhub-base/pmhub-base-core/src/main/java/com/laigeoffer/pmhub/base/core/utils/uuid/IdUtils.java`

```java
public class IdUtils {
    public static String randomUUID()    { return UUID.randomUUID().toString(); }
    public static String simpleUUID()    { return randomUUID().replaceAll("-", ""); }
    public static String fastUUID()      { return randomUUID().replaceAll("-", ""); }
    public static String fastSimpleUUID(){ return fastUUID().replaceAll("-", ""); }
}
```

**traceId 生成建议：** 使用 `IdUtils.fastSimpleUUID()` 生成 32 字符无横线 UUID，没有外部依赖。

---

## 八、与 paidoding 的对比

| 维度 | paidoding | PmHub |
|------|-----------|-------|
| 架构 | 单体 Spring Boot | Spring Cloud 微服务 |
| traceId 生成 | SelfTraceIdGenerator（IP+时间戳+PID+序号） | 无 |
| MDC | 完整（Filter→AOP→Async→finally） | 无 |
| 跨线程 | TTL 2.14.5（与 PmHub 同版本） | TTL 2.14.4（仅用于用户上下文） |
| TTL 线程池 | `TtlExecutors.getTtlExecutorService()` | **未用** TTL 包装线程池 |
| 跨服务 | 不需要（单体） | 需要（FeignRequestInterceptor + 请求头） |
| 响应头 | `g-trace-id` | 无 |
| 业务标记 | `@MdcDot` + SpEL → `%mdc{bizCode}` | 无 |
| 日志格式 | `时间线程级别|traceId|bizCode|类.方法(文件:行)` | `时间线程级别 logger - [方法,行]` |
| Gateway | 无 | AuthFilter（已有，天然适合生成 traceId） |

**PmHub 的优势：** 已有的 Gateway → HeaderInterceptor → FeignRequestInterceptor 链路是添加 traceId 的完美骨架，比 paidoding 单体更好扩展。只需要补上生成 + MDC + 透传 + logback pattern 四步即可。
