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

分布式链路追踪的开山之作是 Google 2010 年发表的 **《Dapper, a Large-Scale Distributed Systems Tracing Infrastructure》** 论文。它定义了现代追踪系统的三个核心概念：

| 概念 | 说明 | Dapper 格式 |
|------|------|------------|
| **TraceId** | 一次完整请求链路的全局唯一标识，贯穿所有服务 | 64-bit 整数 |
| **SpanId** | 链路中每个操作单元的唯一标识 | 64-bit 整数 |
| **ParentSpanId** | 父 Span 的 ID，通过 NULL/非NULL 构建调用树 | 64-bit 整数 |

Dapper 还确立了四个设计目标：**低开销**（根 Span ~204ns）、**应用级透明**（植入 RPC/线程库，业务零感知）、**可伸缩**（自适应采样）、**带外收集**（Span 先写本地日志，异步汇聚）。

本节基于 Dapper 的思想，逐一分析各主流项目的 TraceId 设计方案。

### 1.1 Apache SkyWalking

```
格式：{PROCESS_ID}.{THREAD_ID}.{TIMESTAMP * 10000 + SEQ}
示例：a4ec6fc8ccab4bb4b682064698cc97e6.74.16218381104550009
长度：约 50 字符
```

**源码级结构解析**（基于 SkyWalking 9.x `GlobalIdGenerator`）：

- **PROCESS_ID**（32 位 hex）：JVM 启动时 `UUID.randomUUID().toString().replaceAll("-", "")` 静态初始化，实例级唯一
- **THREAD_ID**（变长 1-3 位十进制）：`Thread.currentThread().getId()`
- **TIMESTAMP** × **10000 + SEQ**（18-19 位十进制）：时间戳左移 4 位，低 4 位放线程自增序号（0-9999）

**时间回拨保护（两个版本）：**

| 版本 | 策略 | 实现 |
|------|------|------|
| 老版本（≤8.x） | 补偿值递增 | `lastShiftValue++`（1, 2, 3...），返回补偿值代替真实时间戳 |
| 新版本（9.x+） | 随机数 | `ThreadLocalRandom.current().nextInt()`，回拨时用随机数替代 |

**为什么 `× 10000`？** 相当于给时间戳预留 4 位十进制空间给序号。这样可以从 ID 反推出生成时间：`时间戳 ≈ 第三段 / 10000`。

**跨进程传播协议（sw8）：**

```
sw8: 1-TRACEID-SEGMENTID-SPANID-PARENT_SERVICE-PARENT_INSTANCE-PARENT_ENDPOINT-PEER
```

8 个字段以 `-` 分隔，所有字符串值 **BASE64 编码**。每个字段最多 50-150 UTF-8 字符。额外扩展头 `sw8-x` 控制追踪模式。

**适用场景：** 已部署 SkyWalking OAP 的中大型集群。Agent 字节码注入，业务代码零侵入。

### 1.2 Jaeger + OpenTelemetry

#### Jaeger

```
格式：128-bit 随机 hex（32 字符）或 64-bit 整数（历史）
示例：3c0e4b6f1a9d2e8b4c7a5f3d1e6b8a2c
传播头：uber-trace-id: {traceId}:{spanId}:{parentSpanId}:{flags}
```

Jaeger 的 traceId 完全随机，不嵌入元信息。128-bit 空间保证概率唯一。排查时依赖 Jaeger Query UI 反查 Collector 上报的 hostname/timestamp。

#### OpenTelemetry（CNCF 标准，2024+ 事实标准）

```
格式：W3C TraceContext 128-bit hex（32 字符）
传播头：traceparent: 00-{traceId(32hex)}-{spanId(16hex)}-{flags(2hex)}
示例：traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
```

**字段结构：**

| 字段 | 长度 | 说明 |
|------|------|------|
| `version` | 2 hex | 固定 `00` |
| `trace-id` | 32 hex | 128-bit，全局唯一 |
| `parent-id` | 16 hex | 64-bit span ID |
| `trace-flags` | 2 hex | 01=采样，00=不采样 |

**设计哲学："标识（ID）与元数据分离"**——traceId 只管全局唯一。定位机器通过 Span 的 `Resource` 属性（`service.name`、`host.name`、`telemetry.sdk.version`）在 OTLP 上报时携带。

**扩展头：`tracestate`**——厂商可在 `tracestate` 头中追加自定义键值对（如 `vendor=abc,tenant=123`），实现多租户透传。

**2025 年部署推荐：OTLP Gateway 模式**

```
Application(SDK) → OTLP/gRPC(:4317) → OpenTelemetry Collector(Gateway)
    → batch/filter/sample → Jaeger Backend → Elasticsearch → Jaeger Query UI(:16686)
```

**采样策略（生产环境推荐）：**

| 采样率 | CPU 开销 | 网络开销 | 数据完整性 | 适用 |
|--------|----------|----------|-----------|------|
| 100% | +18% | 45 MB/s | 极高 | 预发/核心支付 |
| 10% | +3% | 4.5 MB/s | 中等 | 普通接口 |
| 1% | +0.8% | 0.5 MB/s | 低 | 高流量接口 |

### 1.3 Zipkin + B3 传播

```
格式：64-bit hex（16 字符，历史）/ 128-bit hex（32 字符，现代）
示例：80f198ee56343ba864fe8b2a57d3eff7
```

**两种传播模式：**

**多 Header 模式（B3）：**

| Header | 说明 |
|--------|------|
| `X-B3-TraceId` | 全局 trace ID（必须） |
| `X-B3-SpanId` | 当前 span ID（必须） |
| `X-B3-ParentSpanId` | 父 span ID（root span 无此头） |
| `X-B3-Sampled` | 1=采样，0=不采样 |
| `X-B3-Flags` | 1=debug（强制采样） |

**单 Header 模式（b3）：**
```
b3: {TraceId}-{SpanId}-{SamplingState}-{ParentSpanId}
示例：4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-1
```

> **注意：** `X-` 前缀已按 HTTP 标准废弃，W3C `traceparent` 是推荐的替代方案。OpenTelemetry 的 `@opentelemetry/propagator-b3` 同时兼容两种模式以保持向后兼容。

### 1.4 Spring Cloud Sleuth → Micrometer Tracing

Spring 生态的追踪方案经历了代际更替：

| 时期 | 方案 | 状态 |
|------|------|------|
| Boot 2.x | **Spring Cloud Sleuth** + Brave/Zipkin | 维护模式，已冻结 |
| Boot 3.x+ | **Micrometer Tracing** + Brave/OpenTelemetry | 官方替代，活跃开发 |

**日志格式（兼容 Boot 2.x Sleuth）：**
```
[${spring.application.name},%X{traceId},%X{spanId}]
示例：[lyclaw-orchestration,5f3a2b1c8d4e6f7a,5f3a2b1c8d4e6f7a]
```

**Logback 配置（生产环境 JSON → Logstash）：**

```xml
<appender name="LOGSTASH" class="net.logstash.logback.appender.LogstashTcpSocketAppender">
    <destination>logstash:5044</destination>
    <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
        <providers>
            <timestamp/><logLevel/><loggerName/><threadName/>
            <mdc/>  <!-- 自动包含 traceId, spanId -->
            <message/><stackTrace/>
        </providers>
    </encoder>
</appender>
```

**跨线程解决方案：** `LazyTraceExecutor` 包装线程池，或使用 Alibaba TTL。

### 1.5 阿里 EagleEye（鹰眼）

阿里基于 Dapper 自研的链路追踪系统——"鹰眼"。

**TraceId 格式：**
- 32 位随机 hex（类似 UUID 去连字符），不嵌入元信息
- 通过独立部署的鹰眼 Collector 聚合定位

**SpanId 层级体系（独有设计）：**

```
格式：层级点分表示法
示例：0, 0.1, 0.1.1, 0.2
含义：根 Span → 第一个子调用 → 子调用的子调用 → 根 Span 的第二个子调用
```

这种层级 SpanId 的优势是**直接可读调用深度和兄弟顺序**，不需要查 parentSpanId 就能理解调用拓扑。

**传播协议：**

| Header | 说明 |
|--------|------|
| `EagleEye-TraceID` | 32 hex 全局唯一 |
| `EagleEye-RpcID` | 层级 SpanId（如 `0.1.2`） |
| `EagleEye-SpanID` | 当前 Span ID |
| `EagleEye-pSpanID` | 父 Span ID |
| `EagleEye-Sampled` | 采样标志 |
| `EagleEye-UserData` | 业务透传数据（如 AB Test 标识，支持 put/putOnce） |

**2024-2025 ARMS 协议优先级：** W3C > EagleEye > SkyWalking > Jaeger > Zipkin

**可借鉴点：**
- **层级 SpanId** — 直观可读调用拓扑，适合中小项目
- **UserData 透传** — 在链路追踪的同时传递业务参数（如 AB 实验 ID），一举两得

### 1.6 美团 MTrace

美团的分布式追踪系统同样基于 Dapper。

**TraceId：** 64 位整数，使用 UUID 异或生成。

**四个埋点阶段（抽象为统一模型）：**

```
Client Send    → 客户端发起请求时埋点
Server Receive → 服务端接收请求时埋点（回填 traceId/spanId）
Server Send    → 服务端返回时埋点（归档上下文到异步上传队列）
Client Receive → 客户端接收时埋点
```

**存储架构：**
```
Span → 异步队列（内存缓冲）→ 压缩（10:1）→ Kafka → HBase（按 traceId RowKey 实时查询）→ Hive（离线分析）
```

**跨线程传递：**
- 自研 `TransmittableThreadLocal`（继承 InheritableThreadLocal 但支持线程池复用）
- javaagent + instrument 无侵入增强 `ThreadPoolExecutor`、`ScheduledThreadPoolExecutor`、`ForkJoinTask`

**可借鉴点：**
- **四阶段埋点模型** — 通用抽象，任何 RPC/HTTP/MQ 调用都适用
- **压缩传输** — 10 倍压缩比，大幅降低网络开销
- **HBase RowKey = traceId** — 天然支持按 traceId 精确查询

### 1.7 综合对比

| 维度 | Google Dapper | SkyWalking | Jaeger/OTel | Zipkin B3 | 阿里 EagleEye | 美团 MTrace | paicoding | **LyClaw v1** |
|------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **TraceId 格式** | 64-bit int | UUID.ThreadId.TimestampSeq | 128-bit hex | 64/128-bit hex | 32 hex | 64-bit int | IP.Timestamp.PIDSeq | **IP.Timestamp.PIDSeq** |
| **长度** | ~19 位 | ~50 字符 | 32 字符 | 16/32 字符 | 32 字符 | ~19 位 | 32 字符 | **32 字符** |
| **可排序** | ✅ 自增 | ✅ 含时间戳 | ❌ 随机 | ❌ 随机 | ❌ 随机 | ✅ 自增 | ✅ 时间戳在前 | ✅ |
| **可反向定位** | ❌ | ✅ 线程+时间 | ❌ | ❌ | ❌ | ❌ | ✅ IP+时间+PID | ✅ |
| **时间回拨保护** | N/A | ✅ 补偿值/随机数 | N/A | N/A | N/A | N/A | ❌ | **✅ SkyWalking 算法** |
| **传播协议** | RPC 植入 | sw8 (BASE64) | W3C traceparent | B3 headers | EagleEye headers | RPC 植入 | 自定义 HTTP 头 | **X-Trace-Id + traceparent 双兼容** |
| **SpanId 设计** | 64-bit int | 三段式同格式 | 16 hex | 16 hex | 层级点分 `0.1.2` | 签名生成 | 16 hex | **层级点分 `0.1.2`** |
| **无外部依赖** | ✅ | ❌ 需 OAP | ❌ 需 Collector | ❌ 需 Collector | ❌ 需 Collector | ❌ 需全套 | ✅ | ✅ |
| **线程模型** | ThreadLocal | ThreadLocal | Context API | Brave API | ThreadLocal | TTL | static volatile | **ThreadLocal** |

### 1.8 两大设计流派与 LyClaw 的选择

经过以上调研，可以归纳出两大设计流派和一条第三条路：

```
纯随机派                       元信息嵌入派
(Jaeger/W3C/Zipkin)            (paicoding/SkyWalking)
┌────────────────────┐         ┌────────────────────┐
│ ID = 随机数        │         │ ID = 结构化字段    │
│ 元数据 = 独立上报  │         │ 元数据 = 嵌入 ID   │
│ 需要 Collector     │         │ 无需 Collector     │
│ 分布式集群首选     │         │ 可直读定位         │
└────────────────────┘         └────────────────────┘
          │                            │
          └──────────┬─────────────────┘
                     │
          ┌──────────┴──────────┐
          │  融合派（LyClaw）    │
          │  嵌入元信息 +        │
          │  保留 Collector 扩展点│
          └─────────────────────┘
```

**LyClaw 的选择：元信息嵌入 + 保留标准扩展点**

核心逻辑：
1. **LyClaw 是中小型项目（5-8 个微服务），没有部署 Jaeger Collector 或 SkyWalking OAP。** 也不能为了一个 traceId 日志让用户先搭一套追踪基础设施。因此必须在 traceId 本身嵌入 IP 和时间——排查问题时从日志直接定位。
2. **但 v2 如果要接入 SkyWalking/OpenTelemetry，需要平滑切换。** 所以 traceId 的生成通过 `TraceIdGenerator` SPI 接口隔离，HTTP 头名保持 `X-Trace-Id` 同时支持 `traceparent`（W3C 兼容模式）。

**融合方案：paicoding 格式 + SkyWalking 时间回拨保护 + EagleEye 层级 SpanId：**

| 设计点 | 来源 | 理由 |
|--------|------|------|
| `IP_HEX.TIMESTAMP.PIDSEQ` 三段格式 | paicoding | 紧凑 32 字符，可反查 IP/时间/PID |
| 时间回拨保护 | SkyWalking | NTP 校时可能导致时钟回拨，补偿值算法最轻量 |
| ThreadLocal 序号 | SkyWalking | 消除 paicoding 原版 static volatile 的 CAS 竞争 |
| 层级 SpanId（`0.1.2`） | EagleEye | 直观可读调用深度，比 16 hex 更易排查 |
| `TraceIdGenerator` SPI | OpenTelemetry | v2 切换到 W3C traceparent 时只换实现，不动调用方 |
| 四阶段埋点模型 | 美团 MTrace | 统一 RPC/HTTP/MQ 的埋点抽象 |

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

SpanId 的生成规则可以比 traceId 简单（traceId 已经承担了定位职责），但 v1 采用 EagleEye 的**层级点分 SpanId** 增强可读性。

**v1 默认：EagleEye 层级 SpanId**

```
格式：{ROOT}.{CHILD}.{GRANDCHILD}...
示例：0, 0.1, 0.1.1, 0.2, 0.3
```

| SpanId | 含义 | parent 隐含 |
|--------|------|------------|
| `0` | 根 Span（网关入口） | 无 |
| `0.1` | 根的第一次子调用（如 orchestration） | `0` |
| `0.1.1` | orchestration 的第一次子调用（如 feign→memory） | `0.1` |
| `0.2` | 根的第二次子调用（如 feign→plan） | `0` |
| `0.3` | 根的第三次子调用（如 feign→action） | `0` |

**优点：**
- 直接可读调用深度（看点的数量）
- 兄弟顺序可见（`0.1` 在 `0.2` 前面）
- 不需要查 parentSpanId 就能理解拓扑关系
- 32 字符以内，比 16 hex 更紧凑

**生成方式：**

```java
// TraceContext 中
public TraceContext newSpan() {
    int next = this.childCount.incrementAndGet();
    String childSpanId = this.spanId.equals("0") 
        ? "0." + next                    // 根结点下
        : this.spanId + "." + next;      // 非根结点下
    return new TraceContext(this.traceId, childSpanId, this.spanId, this.serviceName);
}
```

**v2 兼容：** 如果后续接入 OpenTelemetry Collector，SpanId 需要与 W3C 兼容（16 位 hex）。通过 `TraceIdGenerator` SPI 切换为纯 hex 模式，`0.1.2` 作为 parent 关系存储在 Span Attributes 中。

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

### 8.5 四阶段埋点模型（借鉴美团 MTrace）

美团 MTrace 的四阶段埋点是对所有 RPC/HTTP/MQ 调用的通用抽象。LyClaw 在 v1 的 Pipeline Stage 埋点可以预留此结构化字段，方便未来扩展：

```
Client Send      → pipeline_stage="CLIENT_SEND",    span_role="producer"
Server Receive   → pipeline_stage="SERVER_RECEIVE", span_role="consumer"  
Server Send      → pipeline_stage="SERVER_SEND",    span_role="producer"
Client Receive   → pipeline_stage="CLIENT_RECEIVE", span_role="consumer"
```

这四个阶段在日志中以 MDC key `spanRole` 标记（可选字段），不会增加日志体积但规划了埋点标准化方向。

### 8.6 与 OpenTelemetry 的互操作路径

v1 LyClaw 的自定义 traceId（`IP_HEX.TIMESTAMP.PIDSEQ`）与 W3C traceparent 不兼容，但可以通过以下路径在 v2 平滑过渡：

```
v1: X-Trace-Id 头（LyClaw 格式）
     ↓
v2: X-Trace-Id + traceparent 双头期（同时发送两个头）
     ↓  
v3: traceparent 为主，X-Trace-Id 保留兼容期
```

LyClaw 默认的 `StructuredTraceIdGenerator` 生成的 32 字符 hex 可以在 v2 映射为 W3C traceparent 的 `trace-id` 字段（虽不完美但可工作），加上 OpenTelemetry SDK 的 `Resource` 属性上报 IP/PID 作为 Span Attributes，实现从"嵌入元信息"到"标识与元数据分离"的无痛迁移。

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
