# LyClaw 全链路追踪与日志系统设计

> **版本**：3.0.0
> **日期**：2026-05-15
> **状态**：设计阶段
> **参考项目**：[Apache SkyWalking](https://skywalking.apache.org/)、[Jaeger](https://www.jaegertracing.io/)、[OpenTelemetry](https://opentelemetry.io/)、[W3C TraceContext](https://www.w3.org/TR/trace-context/)、paicoding（技术派）

---

## 目录

1. [业界调研：TraceId 设计方案](#1-业界调研traceid-设计方案)
2. [LyClaw 现状分析](#2-lyclaw-现状分析)
3. [LyClaw TraceId 方案设计](#3-lyclaw-traceid-方案设计)
4. [日志格式标准化](#4-日志格式标准化)
5. [StopWatch 分段计时设计](#5-stopwatch-分段计时设计)
6. [日志文件归档设计](#6-日志文件归档设计)
7. [MDC 跨线程传播设计](#7-mdc-跨线程传播设计)
8. [多项目日志格式兼容设计](#8-多项目日志格式兼容设计)
9. [代码变更清单](#9-代码变更清单)
10. [实施优先级](#10-实施优先级)

---

## 1. 业界调研：TraceId 设计方案

### 1.1 各大项目的 TraceId 格式对比

#### Apache SkyWalking

```
格式：{INSTANCE_UUID}.{THREAD_ID}.{TIMESTAMP}{SEQ}
示例：b3e8f2a1c6d4.145.17782070341650001
长度：~50 字符
```

**结构解析：**
- `INSTANCE_UUID`（32 位 hex）：服务实例唯一标识，JVM 启动时生成 UUID 去连字符
- `THREAD_ID`（变长 1-3 位）：`Thread.currentThread().getId()`，无锁高性能
- `TIMESTAMP`（13 位毫秒时间戳）：`System.currentTimeMillis()`
- `SEQ`（4 位序号）：ThreadLocal 自增，0-9999 循环

**时间回拨处理（关键设计）：** SkyWalking 的 `IDContext` 内部类实现了时间回拨补偿算法——当检测到 `currentTimeMillis < lastTimestamp` 时，用补偿值（1, 2, 3...）替代真实时间戳，保证 ID 单调递增。

**优点：** 无需 IP 查詢，纯内存生成，高吞吐（每线程每秒 10000 个 ID）。ThreadLocal 无竞争。
**缺点：** 无法反向定位到机器（instance UUID 是随机值），长度偏长。

#### Jaeger

```
格式：{HEX_128BIT}
示例：3c0e4b6f1a9d2e8b4c7a5f3d1e6b8a2c
长度：32 字符（128-bit hex）
```

**生成策略：** 随机 128-bit + Thrift 协议传播。Jaeger 客户端生成完全随机的 128-bit traceId（高位 + 低位），没有嵌入元信息。定位机器需要依赖 Jaeger Collector 的 `jaeger-agent` 上报 hostname。

**优点：** 全局唯一概率极高（2^128 空间），不暴露机器信息。
**缺点：** 纯随机无法反查——看到一条 traceId 无法知道它来自哪个实例、什么时间生成的。排查问题时需要先查 Jaeger UI 反查元数据。

#### W3C TraceContext 标准

```
格式：{VERSION}-{TRACE_ID}-{PARENT_SPAN_ID}-{TRACE_FLAGS}
示例：00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
长度：55 字符
```

**字段说明：**
- `version`（2 hex）：固定 `00`
- `trace-id`（32 hex）：128-bit 全局唯一
- `parent-id`（16 hex）：父 span 的 64-bit ID
- `trace-flags`（2 hex）：采样标志（01=采样，00=不采样）

**HTTP 头名：** `traceparent`（标准头）+ `tracestate`（厂商扩展）
**优点：** 跨厂商互操作标准，支持多租户扩展（`tracestate` 头）。
**缺点：** trace-id 仍然是随机 hex，没有嵌入元信息。

#### OpenTelemetry

OpenTelemetry 的 TraceId 格式遵循 W3C TraceContext 标准——128-bit 随机 hex。不嵌入元信息，但通过 `Resource` 概念（`service.name`、`host.name`、`telemetry.sdk.version` 等）在 Span 上报时携带机器信息。

**结论：** OpenTelemetry 将"标识（ID）"和"元数据（Resource/Span Attributes）"分离。ID 只管全局唯一，定位机器靠 Span Attributes。

#### paicoding（技术派）— SelfTraceIdGenerator

```
格式：{IP_HEX}.{TIMESTAMP}.{PID}{SEQ}
示例：0a8b15f6.1778207034160.4652861000
长度：32 字符
```

**结构解析：**
- `IP_HEX`（8 hex）：`10.139.21.246` → 每段转 hex 补齐 2 位 → `0a8b15f6`
- `TIMESTAMP`（13 位）：`Instant.now().toEpochMilli()`
- `PID`（5 位零补齐）：`ManagementFactory.getRuntimeMXBean().getName().split("@")[0]`
- `SEQ`（4 位）：static volatile Integer 自增，1000-9999 循环

**优点：**
- **可反查**：看到 traceId 就能定位到 IP、精确时间、进程号，排查问题时不需要再查配置中心
- **紧凑**：32 字符，比 SkyWalking 短
- **分段可读**：用 `.` 分隔成 3 段，人类可读

**缺点：**
- IP 转 hex 依赖 `InetAddress.getLocalHost()`，多网卡时可能拿到错误的 IP
- static volatile 自增（非 ThreadLocal），高并发下 CAS 竞争

### 1.2 设计理念分类

| 理念 | 代表 | 核心思路 | 适用场景 |
|------|------|---------|---------|
| **纯随机** | Jaeger, W3C | 128-bit 随机 hex，ID 只做唯一标识 | 大型分布式系统，需要 Collector 聚合 |
| **元信息嵌入** | paicoding, SkyWalking（部分） | ID 内嵌入 IP/时间/PID，支持反向定位 | 中小规模，希望从日志直接定位机器 |
| **时间有序** | Snowflake 变体 | 时间戳在前，天然按时间排序 | 需要数据库索引友好的场景 |

### 1.3 LyClaw 的选择：元信息嵌入 + 时间回拨保护

LyClaw 是中小型项目（5-8 个微服务），没有部署 Jaeger Collector 或 SkyWalking OAP。这意味着**必须能从日志里直接定位机器和时间**——否则排查问题时需要额外查 Nacos/配置中心。

**决策：采用 paicoding 的 `IP_HEX.TIMESTAMP.PIDSEQ` 格式，并引入 SkyWalking 的时间回拨保护机制。**

融合方案：
- **格式**：paicoding 的三段式 `IP_HEX.TIMESTAMP.PIDSEQ`
- **时间回拨保护**：SkyWalking 的补偿值算法
- **线程模型**：ThreadLocal 自增序号（SkyWalking 方案），替代 static volatile（paicoding 方案），消除 CAS 竞争
- **PID 获取**：paicoding 的 `RuntimeMXBean.getName()`（JDK 标准，无需额外依赖）

---

## 2. LyClaw 现状分析

### 2.1 已有追踪组件（保留并增强）

| 组件 | 文件 | 职责 | 3.0 变更 |
|------|------|------|---------|
| `TraceConstants` | `lyclaw-framework/.../tracing/TraceConstants.java` | HTTP 头 + MDC key 常量 | 不修改 |
| `TraceGatewayFilter` | `lyclaw-gateway/.../TraceGatewayFilter.java` | 网关入口 GlobalFilter | **替换 UUID 生成** |
| `TraceWebFilter` | `lyclaw-framework/.../tracing/TraceWebFilter.java` | 微服务 WebFilter，MDC 设置 | **替换 UUID 生成** |
| `TraceFeignInterceptor` | `lyclaw-feign/.../tracing/TraceFeignInterceptor.java` | Feign 请求头注入 | **替换 UUID 生成** |
| `FeignConfig` | `lyclaw-feign/.../tracing/FeignConfig.java` | 注册 TraceFeignInterceptor | 不修改 |
| `TraceAutoConfiguration` | `lyclaw-framework/.../tracing/TraceAutoConfiguration.java` | Reactor Hooks 自动传播 | 不修改 |
| `TraceContext` | `lyclaw-framework/.../tracing/TraceContext.java` | 请求级追踪上下文 + 分段计时 | **增强 StopWatch 输出** |
| `StructuredLogHelper` | `lyclaw-framework/.../logging/StructuredLogHelper.java` | JSON 结构化日志 | 不修改 |
| `PipelineStageBase.logJson()` | `lyclaw-orchestration/.../stage/PipelineStageBase.java` | 管线阶段 JSON 日志 | 不修改 |
| `GlobalErrorAttributes` | `lyclaw-orchestration/.../web/GlobalErrorAttributes.java` | 全局错误响应 | 不修改 |
| `GatewayMetricsFilter` | `lyclaw-gateway/.../GatewayMetricsFilter.java` | 网关请求指标 | 不修改 |
| `OrchestratorImpl` | `lyclaw-orchestration/.../impl/OrchestratorImpl.java` | MDC 注入 + doFinally 清理 | 不修改 |

### 2.2 完整请求链路（保留）

```
外部客户端
  └─> Gateway (TraceGatewayFilter 生成 traceId → 注入请求头)
        └─> 路由到目标服务
              └─> OrchestrationService (TraceWebFilter 设置 MDC)
                    └─> OrchestratorImpl (MDC.put traceId)
                          └─> ContextBuildStage (检索记忆)
                          └─> PlanStage (任务规划)
                          └─> ActionStage (工具调用)
                          └─> ReflectStage (反思总结)
                          └─> Feign 调用 (TraceFeignInterceptor 注入 traceId)
                                └─> 下游服务 (TraceWebFilter 接收)
```

### 2.3 必须修复的问题

#### P0-1：TraceId 使用 UUID，无法反向定位（关键）

```
当前：UUID.randomUUID().toString().replace("-", "")
结果：f47ac10b58cc4372a5670e02b2c3d479
```

看到这串字符，无法判断它来自哪个实例、什么时间、哪个进程。必须查日志聚合系统。

**修复：** 替换为 `IP_HEX.TIMESTAMP.PIDSEQ` 结构化生成。

#### P0-2：MemoryFeignClient 缺少 FeignConfig

```java
// MemoryFeignClient.java:29 — 缺少 configuration
@FeignClient(name = "lyclaw-memory-service", path = "/api/memory")
public interface MemoryFeignClient {
```

对比其他 4 个 FeignClient 都有 `configuration = FeignConfig.class`，导致记忆服务调用没有 traceId 传播。

#### P1-1：日志时间缺少日期

```
当前：%d{HH:mm:ss.SSS}
输出：14:23:57.123
```

只有时间没有日期。排查跨天问题时无法确定日期。修复：`[yyyy-MM-dd HH:mm:ss]` + 北京时区。

#### P1-2：只有 ConsoleAppender，没有文件归档

当前所有 7 个模块的 `logback-spring.xml` 只有 `ConsoleAppender`，重启后控制台日志丢失。

#### P1-3：9 个自定义线程池缺少 MDC 传播

| 线程池 | 模块 | 线程数 |
|--------|------|--------|
| `DefaultSkillExecutor` | lyclaw-action | 4 |
| `ActionExecutorImpl` | lyclaw-action | 4 |
| `ToolSandboxImpl` | lyclaw-action | 2 |
| `AgentLifecycleManager` | lyclaw-orchestration | cached |
| `DefaultAgentCoordinator` | lyclaw-orchestration | 5 |
| `InfraEventBus` | lyclaw-infra | virtual |
| `ExternalAgentAdapterImpl` | lyclaw-protocol | virtual |
| `McpServerImpl` | lyclaw-protocol | virtual |
| `A2aDiscovery` | lyclaw-protocol | virtual |

这些线程池里执行的代码打日志，traceId 会丢失。

#### P1-4：logback-spring.xml 引用不存在的文件

```xml
<include optional="true" resource="logback-json-base.xml"/>
```

`logback-json-base.xml` 不存在，optional=true 所以不报错，但 JSON appender 永远不会生效。

---

## 3. LyClaw TraceId 方案设计

### 3.1 TraceId 格式

```
格式：{IP_HEX}.{TIMESTAMP}.{PID}{SEQ}
示例：0a8b15f6.1778207034160.4652861000
      ├─ IP ─┤ ├─ 时间戳 ─┤ ├PID┤├SEQ┤
      8 hex    13 位毫秒    5位  4位
总长度：32 字符
```

**各段说明：**

| 段 | 长度 | 来源 | 示例值 | 用途 |
|----|------|------|--------|------|
| IP_HEX | 8 hex | `InetAddress.getLocalHost()` 每 octet 转 2 位 hex | `0a8b15f6` | 反向定位机器 |
| TIMESTAMP | 13 位 | `System.currentTimeMillis()` | `1778207034160` | 反向定位时间 |
| PID | 5 位 | `RuntimeMXBean.getName().split("@")[0]` 零补齐 | `46528` | 区分同机多实例 |
| SEQ | 4 位 | ThreadLocal 自增 1000-9999 | `1000` | 同毫秒内区分 |

### 3.2 生成算法（融合 paicoding + SkyWalking）

```java
// LyClawTraceIdGenerator.java (新增)

public class LyClawTraceIdGenerator {

    // ========== ThreadLocal 自增序号（消除 CAS 竞争）==========
    private static final int MIN_SEQ = 1000;
    private static final int MAX_SEQ = 10000;

    private static final ThreadLocal<SeqContext> SEQ_HOLDER =
            ThreadLocal.withInitial(() -> new SeqContext(MIN_SEQ));

    // ========== 时间回拨保护（借鉴 SkyWalking）==========
    private static volatile long lastGlobalTimestamp;
    private static volatile int shiftValue;

    /**
     * 生成 32 位结构化 traceId。
     */
    public static String generate() {
        try {
            StringBuilder sb = new StringBuilder(32);
            // 1. IP → 8 位 hex
            sb.append(convertIp(getLocalIp()));
            sb.append('.');
            // 2. 时间戳（含回拨保护）→ 13 位
            sb.append(timestamp());
            sb.append('.');
            // 3. PID（5 位零补齐）
            sb.append(getPid());
            // 4. 线程序号（4 位，1000-9999 循环）
            sb.append(SEQ_HOLDER.get().nextSeq());
            return sb.toString();
        } catch (Exception e) {
            // 降级：极端情况下用 UUID
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    // ---- IP 转 hex ----
    private static String convertIp(String ip) {
        // "10.139.21.246" → "0a8b15f6"
        StringBuilder sb = new StringBuilder(8);
        for (String octet : ip.split("\\.")) {
            sb.append(String.format("%02x", Integer.parseInt(octet)));
        }
        return sb.toString();
    }

    // ---- 带时间回拨保护的时间戳 ----
    private static long timestamp() {
        long now = System.currentTimeMillis();
        synchronized (LyClawTraceIdGenerator.class) {
            if (now < lastGlobalTimestamp) {
                shiftValue++;
                return shiftValue;      // 回拨时用补偿值
            }
            lastGlobalTimestamp = now;
            return now;
        }
    }

    // ---- PID ----
    private static String getPid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        String pid = name.split("@")[0];
        return String.format("%05d", Integer.parseInt(pid));
    }

    // ========== 内部类：每线程独立序号 ==========
    private static class SeqContext {
        private int seq;
        SeqContext(int start) { this.seq = start; }

        int nextSeq() {
            if (seq >= MAX_SEQ) seq = MIN_SEQ;
            return seq++;
        }
    }
}
```

### 3.3 设计要点

**为什么用 ThreadLocal 而不是 static volatile（paicoding 原版用 static volatile）？**
paicoding 是 Servlet 模型（一个请求一个线程），static volatile 在高并发时会有 CAS 自旋。LyClaw 用 WebFlux（Reactor 事件循环），大量协程在少量线程上切换，改用 ThreadLocal 完全消除竞争。

**为什么加时间回拨保护（paicoding 原版没有）？**
NTP 校准可能导致系统时间回拨几毫秒。如果不处理，可能在同一毫秒内生成重复的 SEQ（ThreadLocal 的 SEQ 已经循环了一圈）。SkyWalking 的补偿值方案是最轻量的解法——只在检测到回拨时激活。

**为什么保留 SkyWalking 的格式作为备选？**
paicoding 代码中已经有 `SkyWalkingTraceIdGenerator`（`{INSTANCE_UUID}.{THREAD_ID}.{TIMESTAMP}SEQ`），如果 LyClaw 后续接入 SkyWalking Agent，可以直接切换到兼容格式。通过配置 `lyclaw.tracing.generator=skywalking` 切换。

### 3.4 SpanId 设计

SpanId 不需要嵌入元信息（traceId 已经承担了定位职责），保持简洁：

```
格式：{16 hex}（UUID 前 16 位）
示例：f47ac10b58cc4372
```

每个新的服务调用/阶段生成新的 spanId，用于构建 Span 树形结构。

---

## 4. 日志格式标准化

### 4.1 两种日志格式（对齐 paicoding）

LyClaw 像 paicoding 一样，区分两类日志输出：

#### 请求日志（req log）

专用于 HTTP 请求记录的 logger，logger name 为 `req`，独立文件。

```
格式：[yyyy-MM-dd HH:mm:ss|TRACE_ID|] - method=METHOD; remoteIp=IP; ...; cost=MS
```

**示例（对齐 paicoding logs/req-dev.log）：**
```
[2026-05-08 10:23:57|0a8b15f6.1778207034160.4652861000|] - method=GET; remoteIp=10.139.21.246; user=1; uri=/api/chat; cost=2958
```

**Logback 配置：**
```xml
<appender name="REQ_LOG" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <File>logs/req-dev.log</File>
    <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
        <FileNamePattern>logs/arch/req/req.%d{yyyy-MM-dd}.%i.log.gz</FileNamePattern>
        <maxFileSize>100MB</maxFileSize>
        <maxHistory>10</maxHistory>
        <totalSizeCap>1GB</totalSizeCap>
    </rollingPolicy>
    <encoder>
        <charset>UTF-8</charset>
        <pattern>[%d{yyyy-MM-dd HH:mm:ss}|%mdc{traceId}|] - %msg%n</pattern>
    </encoder>
</appender>

<logger name="req" level="INFO" additivity="false">
    <appender-ref ref="REQ_LOG"/>
</logger>
```

#### 服务日志（application log）

业务逻辑日志，JSON 结构化输出。

```
格式：[yyyy-MM-dd HH:mm:ss]|TRACE_ID|BIZ_CODE|{"logger":"...","thread":"...","msg":"..."}
```

**示例（对齐 paicoding logs/pai-dev.log）：**
```
[2026-05-08 10:23:40]|0a8b15f6.1778207034160.4652861000||{"logger":"l.c.l.o.OrchestratorImpl", "thread":"reactor-http-1", "msg":"Pipeline started"}
```

**Logback 配置：**
```xml
<appender name="SERVICE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <File>logs/lyclaw-dev.log</File>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
        <fileNamePattern>logs/arch/lyclaw-dev.%d.%i.log</fileNamePattern>
        <maxHistory>3</maxHistory>
        <timeBasedFileNamingAndTriggeringPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedFNATP">
            <maxFileSize>100MB</maxFileSize>
        </timeBasedFileNamingAndTriggeringPolicy>
    </rollingPolicy>
    <encoder>
        <charset>UTF-8</charset>
        <pattern>[%d{yyyy-MM-dd HH:mm:ss}]|%mdc{traceId}|%mdc{bizCode}|{"logger":"%logger{36}", "thread":"%thread", "msg":"%msg"}%n</pattern>
    </encoder>
</appender>
```

### 4.2 日志格式总览

| 日志类型 | Logger | Appender | 文件名 | 归档目录 | 保留 |
|----------|--------|----------|--------|---------|------|
| 请求日志 | `req` | REQ_LOG | `logs/req-dev.log` | `logs/arch/req/` | 10天 / 1GB |
| 服务日志 | ROOT/app | SERVICE | `logs/lyclaw-dev.log` | `logs/arch/` | 3天 |
| 控制台 | ROOT/app | CONSOLE | —（stdout） | — | — |
| 错误报警 | ERROR | ERROR_ALARM | —（邮件/钉钉） | — | — |

### 4.3 控制台日志格式

开发环境使用时，控制台日志不需要 JSON 格式化，保持人类可读：

```
格式：%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{36} - %msg%n
示例：2026-05-08 10:23:57.123 [reactor-http-1] [0a8b15f6.1778207034160.4652861000] INFO  l.c.l.o.OrchestratorImpl - Pipeline started
```

### 4.4 时区设置

所有日志时间使用 **北京时间（UTC+8 / Asia/Shanghai）**：

```xml
<!-- logback-spring.xml -->
<timestamp key="bySecond" datePattern="yyyy-MM-dd HH:mm:ss" timeZone="Asia/Shanghai"/>
<pattern>[%d{yyyy-MM-dd HH:mm:ss}]|%mdc{traceId}|%mdc{bizCode}|...%n</pattern>
```

JVM 启动参数：
```
-Duser.timezone=Asia/Shanghai
```

---

## 5. StopWatch 分段计时设计

### 5.1 设计目标

paicoding 使用 Hutool 的 `StopWatch` + `prettyPrint(TimeUnit.MILLISECONDS)` 输出每一步的耗时和百分比。LyClaw 已有 `TraceContext.beginStage()` / `endStage()` 方法，需要增强其输出格式。

### 5.2 增强 TraceContext

```
当前：toJson() → {"traceId":"...","stages":{"CONTEXT_BUILD":42,...},"total":1500}
增强：prettyPrint() → 对齐 paicoding 的 StopWatch 格式
```

**增强方法：**

```java
// TraceContext.java 新增方法
public String prettyPrint() {
    long total = getTotalDuration();
    StringBuilder sb = new StringBuilder();
    sb.append("\nStopWatch '").append(serviceName != null ? serviceName : "pipeline")
      .append("': running time = ").append(total).append(" ms\n");
    sb.append("---------------------------------------------\n");
    sb.append("ms         %     Task name\n");
    sb.append("---------------------------------------------\n");

    NumberFormat pf = NumberFormat.getPercentInstance();
    pf.setMinimumIntegerDigits(2);
    pf.setMinimumFractionDigits(2);

    for (Map.Entry<String, Long> e : stageDurations.entrySet()) {
        long ms = e.getValue();
        sb.append(String.format("%09d", ms)).append("  ");
        sb.append(pf.format((double) ms / total)).append("  ");
        sb.append(e.getKey()).append("\n");
    }
    sb.append("---------------------------------------------");
    return sb.toString();
}
```

**输出示例：**
```
StopWatch 'pipeline': running time = 1500 ms
---------------------------------------------
ms         %     Task name
---------------------------------------------
000000275  18%   CONTEXT_BUILD
000000042  03%   PLAN
000001050  70%   ACTION
000000127  08%   REFLECT
000000006  01%   METRICS
---------------------------------------------
```

### 5.3 使用方式

```java
// OrchestratorImpl.java — 在 pipeline 执行完成后
TraceContext trace = context.getTracing();
trace.beginStage("CONTEXT_BUILD");
// ... context build logic ...
trace.endStage("CONTEXT_BUILD");

trace.beginStage("ACTION");
// ... action execution ...
trace.endStage("ACTION");

// ... more stages ...

trace.markEnd();
log.info("Trace cost: {}", trace.prettyPrint());
```

### 5.4 paicoding 的 CompletableFutureBridge 模式（可选扩展）

paicoding 的 `AsyncUtil.CompletableFutureBridge` 提供了一种更高级的用法——在并发执行多个任务时，自动追踪每个子任务的耗时：

```java
// paicoding 风格
CompletableFutureBridge bridge = AsyncUtil.concurrentExecutor("记忆写入");
bridge.async(() -> storeEmbedding(), "向量嵌入")
      .async(() -> storeEntry(), "PG写入")
      .allExecuted()
      .prettyPrint();
```

LyClaw v1 不强制实现此模式，但 `TraceContext.prettyPrint()` 的输出格式与之一致，后续可以扩展。

---

## 6. 日志文件归档设计

### 6.1 目录结构（对齐 paicoding）

```
logs/
├── lyclaw-dev.log          ← 当前服务日志（滚动写入）
├── req-dev.log             ← 当前请求日志（滚动写入）
└── arch/                   ← 归档目录
    ├── lyclaw-dev.2026-05-07.0.log   ← 昨天归档的服务日志
    ├── lyclaw-dev.2026-05-06.0.log   ← 前天的
    └── req/                           ← 请求日志单独归档
        ├── req.2026-05-07.0.log.gz   ← 昨天归档的请求日志（压缩）
        └── req.2026-05-06.0.log.gz   ← 前天的
```

### 6.2 滚动策略

| 策略 | 服务日志 | 请求日志 |
|------|---------|---------|
| 滚动触发 | 每天 + 100MB 上限 | 每天 + 100MB 上限 |
| 归档格式 | `lyclaw-dev.%d.%i.log` | `req.%d.%i.log.gz` |
| 保留天数 | 3 天 | 10 天 |
| 总大小上限 | 无 | 1GB |
| 压缩 | 否 | gzip |

### 6.3 环境区分

```xml
<springProperty scope="context" name="log.env" source="env.name" defaultValue="dev"/>
<property name="log.service.name" value="lyclaw"/>

<File>logs/${log.service.name}-${log.env}.log</File>
```

- 开发环境：`logs/lyclaw-dev.log`
- 生产环境：`logs/lyclaw-prod.log`

### 6.4 统一 logback-spring.xml（所有 7 个模块共享）

新建 `lyclaw-framework/src/main/resources/logback-spring-base.xml` 作为基础配置，各模块通过 `<include>` 引用：

```xml
<!-- 各模块的 logback-spring.xml 简化为 -->
<configuration>
    <include resource="logback-spring-base.xml"/>
    <springProperty scope="context" name="spring.application.name" source="spring.application.name"/>
</configuration>
```

不再需要每个模块复制粘贴同样的 appender 配置。

---

## 7. MDC 跨线程传播设计

### 7.1 问题模型

```
请求线程 (reactor-http-1)
  MDC: {traceId: "0a8b15f6.xxx", spanId: "xxx"}
    └─> 提交任务到 自定义线程池
          └─> 工作线程 (lyclaw-worker-3)
                MDC: {} ← 丢失！
                log.info("...") ← 日志没有 traceId
```

### 7.2 Reactor 场景（已解决）

`TraceAutoConfiguration` 已配置 `Hooks.enableAutomaticContextPropagation()`，Reactor 操作符链自动传播 MDC。**无需额外工作。**

### 7.3 自定义线程池场景（需修复）

9 个自定义线程池需要被 TTL（TransmittableThreadLocal）包装。

**方案一：TtlExecutors 包装（推荐）**

在 `ThreadPoolFactory` 中统一处理：

```java
// lyclaw-framework/src/main/java/lyjew/com/lyclaw/util/ThreadPoolFactory.java

import com.alibaba.ttl.threadpool.TtlExecutors;

public class ThreadPoolFactory {

    public static ExecutorService newFixedThreadPool(int nThreads, String name) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                nThreads, nThreads, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                new NamedThreadFactory(name),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        // TTL 包装，自动传播 MDC
        return TtlExecutors.getTtlExecutorService(pool);
    }
}
```

**方案二：TraceContext.wrap()（无需引入 TTL）**

如果不想引入 TTL 依赖，在每次提交任务时手动 wrap：

```java
executorService.execute(TraceContext.wrap(() -> {
    // 任务代码，MDC 自动有 traceId
}));
```

**推荐方案一**。TTL 已经在 Alibaba Nacos、Dubbo 等生态中广泛使用，LyClaw 已引入 Nacos 依赖。TTL 对 JDK 17 + Virtual Threads 兼容良好。

### 7.4 Virtual Thread 场景

`InfraEventBus` 等使用 Virtual Threads 的线程池，TTL 也支持（TTL 2.14+ 兼容 Virtual Threads）。

```java
ExecutorService virtualPool = TtlExecutors.getTtlExecutorService(
    Executors.newVirtualThreadPerTaskExecutor()
);
```

---

## 8. 多项目日志格式兼容设计

### 8.1 设计目标

LyClaw 的日志格式设计要能兼容未来可能接入的其他项目。不同项目可能有不同的 traceId 格式和日志字段（如 paicoding 的 `bizCode`），LyClaw 的格式设计要预留扩展点。

### 8.2 扩展机制

**MDC 字段可配置化：**

```java
// TraceConstants.java 新增
public static final String MDC_BIZ_CODE = "bizCode";    // 业务编码（paicoding 使用）
public static final String MDC_USER_ID = "userId";      // 用户 ID
public static final String MDC_MODULE = "module";       // 模块名
```

**Logback pattern 可配置化：**

当前 LyClaw 的模式：
```
[%d{yyyy-MM-dd HH:mm:ss}]|%mdc{traceId}|%mdc{bizCode}|{"logger":..., "thread":..., "msg":...}%n
```

paicoding 的模式（对比）：
```
[%d{yyyy-MM-dd HH:mm:ss}]|%mdc{traceId}|%mdc{bizCode}|{"logger":..., "thread":..., "msg":...}%n
```

可以看到，格式一致。如果接入 paicoding，只需要在 MDC 中设置 `bizCode` 即可在同一套日志基础设施中统一处理。

### 8.3 多 traceId 格式兼容

如果未来接入的项目使用不同的 traceId 格式（如纯 UUID、W3C traceparent），`LyClawTraceIdGenerator` 接口化：

```java
// 新增 SPI 接口
public interface TraceIdGenerator {
    String generate();
}

// 实现一：LyClaw 默认实现
@Component
@ConditionalOnProperty(name = "lyclaw.tracing.generator", havingValue = "structured", matchIfMissing = true)
public class StructuredTraceIdGenerator implements TraceIdGenerator {
    // IP_HEX.TIMESTAMP.PIDSEQ 格式
}

// 实现二：W3C 兼容格式
@Component
@ConditionalOnProperty(name = "lyclaw.tracing.generator", havingValue = "w3c")
public class W3cTraceIdGenerator implements TraceIdGenerator {
    // {version}-{trace-id}-{parent-id}-{trace-flags} 格式
}

// 实现三：兼容其他项目传入的格式
@Component
@ConditionalOnProperty(name = "lyclaw.tracing.generator", havingValue = "passthrough")
public class PassthroughTraceIdGenerator implements TraceIdGenerator {
    // 直接使用上游传入的 traceId，不生成
}
```

**Logback 格式不变。** 无论哪种 traceId 格式，logback 的 `%mdc{traceId}` 都能正确输出。

### 8.4 日志文件命名兼容

```
logs/
├── {project}-{env}.log          ← 主日志（project 可配置）
├── req-{env}.log                ← 请求日志
└── arch/
    └── {project}-{env}.{date}.{index}.log
```

通过 `spring.application.name` 自动确定 `project`，确保不同项目的日志文件不会互相覆盖。

---

## 9. 代码变更清单

### 9.1 新增文件

| 文件 | 路径 | 说明 |
|------|------|------|
| `LyClawTraceIdGenerator` | `lyclaw-framework/.../tracing/LyClawTraceIdGenerator.java` | 结构化 traceId 生成器 |
| `TraceIdGenerator` | `lyclaw-framework/.../tracing/TraceIdGenerator.java` | TraceId 生成 SPI 接口 |
| `StopWatchFormatter` | `lyclaw-framework/.../tracing/StopWatchFormatter.java` | 独立的 StopWatch 格式化工具（从 TraceContext 拆分） |
| `logback-spring-base.xml` | `lyclaw-framework/src/main/resources/logback-spring-base.xml` | 统一的 logback 基础配置 |

### 9.2 修改文件

| 文件 | 变更内容 |
|------|---------|
| `TraceGatewayFilter.java` | `UUID.randomUUID()` → `LyClawTraceIdGenerator.generate()` |
| `TraceWebFilter.java` | `UUID.randomUUID()` → `LyClawTraceIdGenerator.generate()` |
| `TraceFeignInterceptor.java` | `UUID.randomUUID()` → `LyClawTraceIdGenerator.generate()` |
| `TraceContext.java` | 新增 `prettyPrint()` 方法，格式化 StopWatch 输出；构造器改用 `LyClawTraceIdGenerator` |
| `MemoryFeignClient.java` | **修复：** 添加 `configuration = FeignConfig.class` |
| `ThreadPoolFactory.java` | **修复：** `TtlExecutors.getTtlExecutorService()` 包装所有自定义线程池 |
| 7 个 `logback-spring.xml` | **重写：** 改为 `include logback-spring-base.xml`，添加 FILE + REQ_LOG appender，北京时区，新 pattern |

### 9.3 不修改（保留不变）

| 文件 | 原因 |
|------|------|
| `TraceConstants.java` | HTTP 头 + MDC key 已足够 |
| `TraceAutoConfiguration.java` | Reactor Hooks 配置正确 |
| `PipelineStageBase.logJson()` | JSON 日志方法不变 |
| `OrchestratorImpl.java` | 管线执行逻辑不变，只增强 TraceContext 输出 |
| `StructuredLogHelper.java` | JSON 工具不变 |
| `GlobalErrorAttributes.java` | 错误响应不变 |
| `GatewayMetricsFilter.java` | 指标输出不变 |
| `FeignConfig.java` | 注册逻辑不变 |

---

## 10. 实施优先级

### P0 — 阻断 Bug（立即修复）

| 任务 | 涉及文件 | 工作量 |
|------|---------|--------|
| 新建 `LyClawTraceIdGenerator` | NEW `LyClawTraceIdGenerator.java` | 0.5d |
| 替换 `TraceGatewayFilter` 的 UUID 生成 | MODIFY `TraceGatewayFilter.java` | 0.25d |
| 替换 `TraceWebFilter` 的 UUID 生成 | MODIFY `TraceWebFilter.java` | 0.25d |
| 替换 `TraceFeignInterceptor` 的 UUID 生成 | MODIFY `TraceFeignInterceptor.java` | 0.25d |
| 替换 `TraceContext` 构造器的 UUID 生成 | MODIFY `TraceContext.java` | 0.25d |
| **修复：** `MemoryFeignClient` 加 `FeignConfig` | MODIFY `MemoryFeignClient.java` | 0.1d |

### P1 — 功能缺陷（第一周修复）

| 任务 | 涉及文件 | 工作量 |
|------|---------|--------|
| 新建 `logback-spring-base.xml` | NEW file | 0.5d |
| 重写 7 个 `logback-spring.xml`（统一模式+北京时区+文件归档） | MODIFY 7 files | 0.5d |
| 9 个线程池 TTL 包装 | MODIFY `ThreadPoolFactory.java` + 9 个线程池定义处 | 1d |
| `TraceContext.prettyPrint()` StopWatch 增强 | MODIFY `TraceContext.java` | 0.5d |
| Orchestrator 调用 `prettyPrint()` 输出每请求耗时 | MODIFY `OrchestratorImpl.java` | 0.25d |

### P2 — 改进优化（第二周及后续）

| 任务 | 涉及文件 | 工作量 |
|------|---------|--------|
| `TraceIdGenerator` SPI 抽离 | NEW `TraceIdGenerator.java`, REFACTOR `LyClawTraceIdGenerator` | 0.5d |
| 多项目格式兼容（`bizCode`、`userId` MDC 常量） | MODIFY `TraceConstants.java` | 0.25d |
| 错误报警 Appender（钉钉/邮件） | NEW `ErrorAlarmAppender.java` | 1d |
| 生产环境 JSON 日志格式（对齐 ELK/Loki 采集） | NEW `logback-spring-json.xml` | 0.5d |

---

> **下一步：** 按 P0 → P1 → P2 顺序实现。P0 的 6 个任务总工作量约 1.6 天，实现后 traceId 即可反向定位到机器和时间。
