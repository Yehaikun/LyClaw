package lyjew.com.lyclaw.tracing;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 链路追踪上下文（Trace Context），管理单次请求的全链路追踪信息和分段性能统计。
 *
 * <p>本类实现了分布式链路追踪（Distributed Tracing）的核心模型，其设计参考了业界广泛
 * 使用的 Jaeger 和 Zipkin 追踪系统的基本概念。在一个典型的微服务调用链中，一条完整的
 * 用户请求可能跨越多个服务、多个线程和多个异步操作。TraceContext 通过 traceId（追踪 ID）
 * 和 spanId（跨度 ID）两个核心标识符，将分散在不同服务、不同线程中的调用片段串联成
 * 一条完整的调用链路，从而实现端到端的请求追踪和性能分析。
 *
 * <p>核心概念说明：
 * <ul>
 *   <li><b>Trace（追踪）</b>：对应一次完整的用户请求调用链，由一个全局唯一的 traceId
 *       标识。从请求进入系统开始，到响应返回结束，跨越的所有服务调用都属于同一个 Trace。
 *       traceId 在系统边界处生成，并通过 HTTP Header 或消息头在服务间传播</li>
 *   <li><b>Span（跨度）</b>：Trace 中的一个操作单元，由一个全局唯一的 spanId 标识。
 *       每个 Span 代表调用链中的一个具体操作（如一次 HTTP 调用、一次数据库查询、
 *       一次工具执行等）。Span 之间通过 parentSpanId 形成树形层级结构，根 Span 的
 *       parentSpanId 为 null</li>
 *   <li><b>Stage（阶段）</b>：Span 内部的分段计时单元，用于更细粒度地统计各子步骤的
 *       耗时。通过 beginStage/endStage 方法对进行计时，常用于识别性能瓶颈</li>
 * </ul>
 *
 * <p>主要功能：
 * <ul>
 *   <li><b>Span 生命周期管理</b>：构造器自动生成 traceId 和 spanId（使用 UUID 去连字符），
 *       支持从上游传入 traceId 以保持链路连续性。{@link #newSpan()} 方法创建当前 span
 *       的子 span，实现嵌套追踪</li>
 *   <li><b>分段计时</b>：{@link #beginStage(String)} 和 {@link #endStage(String)} 方法
 *       支持命名分段的精确耗时统计，各阶段耗时自动累加，支持同一阶段多次计时的累加汇总。
 *       {@link #getTotalDuration()} 返回从请求开始到当前时刻（或标记结束时）的总耗时</li>
 *   <li><b>MDC 跨线程传播</b>：{@link #wrap(Runnable)} 静态工具方法自动保存和恢复 SLF4J
 *       MDC（Mapped Diagnostic Context）中的追踪信息（traceId、spanId、serviceName），
 *       确保在使用自定义线程池执行异步任务时追踪信息不会丢失或泄露到其他不相关的请求</li>
 *   <li><b>JSON 序列化</b>：{@link #toJson()} 方法将追踪上下文输出为结构化 JSON 字符串，
 *       包含 traceId、spanId、parentSpanId（可选）、service（可选）、各阶段耗时和总耗时，
 *       便于日志系统采集和可视化分析</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * TraceContext trace = new TraceContext();  // 创建根 Trace
 * trace.beginStage("memory_search");
 * // ... 执行记忆搜索 ...
 * trace.endStage("memory_search");
 * trace.beginStage("model_call");
 * // ... 调用 AI 模型 ...
 * trace.endStage("model_call");
 * trace.markEnd();
 * log.info("Trace: {}", trace.toJson());
 * }</pre>
 *
 * @see lyjew.com.lyclaw.tracing.TraceConstants
 */
public class TraceContext {

    /** 追踪 ID，标识一次完整的请求调用链 */
    private final String traceId;
    /** 当前 span ID，标识调用链中的一个操作单元 */
    private final String spanId;
    /** 父级 span ID，用于构建 span 树形结构 */
    private final String parentSpanId;
    /** 服务名称，标识当前微服务 */
    private String serviceName;
    /** 各分段的累计耗时（毫秒），key 为分段名称，value 为累计毫秒数 */
    private final Map<String, Long> stageDurations = new LinkedHashMap<>();
    /** 各分段开始计时的时间戳，用于计算单次分段耗时 */
    private final Map<String, Long> stageStartTimes = new LinkedHashMap<>();
    /** 请求开始时间戳（毫秒） */
    private final long requestStartTime;
    /** 请求结束时间戳（毫秒），0 表示尚未结束 */
    private long requestEndTime;

    /**
     * 创建一个新的根 TraceContext（无父 span）。
     */
    public TraceContext() {
        this.traceId = UUID.randomUUID().toString().replace("-", "");
        this.spanId = UUID.randomUUID().toString().replace("-", "");
        this.parentSpanId = null;
        this.requestStartTime = System.currentTimeMillis();
    }

    /**
     * 使用指定的 traceId 创建根 span。
     *
     * @param traceId 上游传入的追踪 ID
     */
    public TraceContext(String traceId) {
        this.traceId = traceId;
        this.spanId = UUID.randomUUID().toString().replace("-", "");
        this.parentSpanId = null;
        this.requestStartTime = System.currentTimeMillis();
    }

    /**
     * 全参数私有构造器，由内部工厂方法调用。
     */
    private TraceContext(String traceId, String spanId, String parentSpanId, String serviceName) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.serviceName = serviceName;
        this.requestStartTime = System.currentTimeMillis();
    }

    /**
     * 为来自上游服务的请求创建 TraceContext。
     *
     * @param traceId      上游传递的 traceId
     * @param parentSpanId 上游传递的 spanId（作为当前请求的父级）
     * @return 新的 TraceContext 实例
     */
    public static TraceContext incoming(String traceId, String parentSpanId) {
        String spanId = UUID.randomUUID().toString().replace("-", "");
        return new TraceContext(traceId, spanId, parentSpanId, null);
    }

    /**
     * 创建当前 span 的子 span，用于追踪下游或内部调用。
     *
     * @return 子 span 对应的 TraceContext
     */
    public TraceContext newSpan() {
        return new TraceContext(this.traceId, UUID.randomUUID().toString().replace("-", ""),
                this.spanId, this.serviceName);
    }

    /**
     * 在保持当前线程追踪上下文的前提下执行 Runnable。
     *
     * <p>先保存 MDC 中的追踪信息，执行任务，最后在 finally 块中恢复 MDC。
     * 适用于在自定义线程池中执行任务时防止追踪信息丢失。
     *
     * @param runnable 要执行的任务
     */
    public static void wrap(Runnable runnable) {
        // 保存当前线程的 MDC 追踪信息
        String traceId = MDC.get(TraceConstants.MDC_TRACE_ID);
        String spanId = MDC.get(TraceConstants.MDC_SPAN_ID);
        String service = MDC.get(TraceConstants.MDC_SERVICE);
        try {
            runnable.run();
        } finally {
            // 恢复 MDC 中的追踪信息，防止线程池复用导致信息泄露或丢失
            if (traceId != null) {
                MDC.put(TraceConstants.MDC_TRACE_ID, traceId);
            } else {
                MDC.remove(TraceConstants.MDC_TRACE_ID);
            }
            if (spanId != null) {
                MDC.put(TraceConstants.MDC_SPAN_ID, spanId);
            } else {
                MDC.remove(TraceConstants.MDC_SPAN_ID);
            }
            if (service != null) {
                MDC.put(TraceConstants.MDC_SERVICE, service);
            } else {
                MDC.remove(TraceConstants.MDC_SERVICE);
            }
        }
    }

    /**
     * 开始一个命名分段的计时。
     *
     * @param stageName 分段名称
     */
    public void beginStage(String stageName) {
        stageStartTimes.put(stageName, System.currentTimeMillis());
    }

    /**
     * 结束一个命名分段的计时，并将耗时累加到该分段的累计时间中。
     *
     * @param stageName 分段名称
     */
    public void endStage(String stageName) {
        Long startTime = stageStartTimes.remove(stageName);
        if (startTime != null) {
            long duration = System.currentTimeMillis() - startTime;
            stageDurations.merge(stageName, duration, Long::sum);
        }
    }

    /**
     * 获取指定分段的累计耗时。
     *
     * @param stageName 分段名称
     * @return 累计耗时（毫秒），若不存在则返回 -1
     */
    public long getStageDuration(String stageName) {
        return stageDurations.getOrDefault(stageName, -1L);
    }

    /**
     * 获取从请求开始到当前时刻或标记结束时刻的总耗时。
     *
     * @return 总耗时（毫秒）
     */
    public long getTotalDuration() {
        long end = requestEndTime > 0 ? requestEndTime : System.currentTimeMillis();
        return end - requestStartTime;
    }

    /**
     * 标记请求结束，记录结束时间。
     */
    public void markEnd() {
        this.requestEndTime = System.currentTimeMillis();
    }

    /**
     * @return 追踪 ID
     */
    public String getTraceId() {
        return traceId;
    }

    /**
     * @return 当前 span ID
     */
    public String getSpanId() {
        return spanId;
    }

    /**
     * @return 父级 span ID，根 span 时为 null
     */
    public String getParentSpanId() {
        return parentSpanId;
    }

    /**
     * @return 服务名称
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * @param serviceName 服务名称
     */
    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    /**
     * @return 各分段的累计耗时映射
     */
    public Map<String, Long> getStageDurations() {
        return stageDurations;
    }

    /**
     * @return 请求开始时间戳（毫秒）
     */
    public long getRequestStartTime() {
        return requestStartTime;
    }

    /**
     * 将追踪上下文序列化为 JSON 字符串，包含 traceId、spanId、分段耗时和总耗时。
     *
     * @return JSON 格式的追踪信息字符串
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        // 序列化核心追踪字段
        sb.append("{\"traceId\":\"").append(traceId)
                .append("\",\"spanId\":\"").append(spanId);
        // 可选字段：仅在非空时序列化
        if (parentSpanId != null && !parentSpanId.isEmpty()) {
            sb.append("\",\"parentSpanId\":\"").append(parentSpanId);
        }
        if (serviceName != null && !serviceName.isEmpty()) {
            sb.append("\",\"service\":\"").append(serviceName);
        }
        // 序列化各分段耗时
        sb.append("\",\"stages\":{");
        boolean first = true;
        for (Map.Entry<String, Long> e : stageDurations.entrySet()) {
            if (!first) sb.append(","); // 除第一个元素外，前面加逗号分隔
            sb.append("\"").append(e.getKey())
                    .append("\":").append(e.getValue());
            first = false;
        }
        sb.append("},\"total\":").append(getTotalDuration()).append("}");
        return sb.toString();
    }
}
