package lyjew.com.lyclaw.tracing;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 链路追踪上下文，管理单次请求的 traceId、spanId 及分段耗时信息。
 *
 * <p>该类实现了类似 Jaeger/Zipkin 的追踪模型：一个 trace 对应一次完整请求调用链，
 * 每个 span 代表调用链中的一个操作单元。支持父子 span 嵌套、分段计时统计、
 * 以及将追踪信息输出为 JSON 格式的日志。
 * <p>同时提供 {@link #wrap(Runnable)} 静态方法，用于在异步线程池中保持 MDC 追踪信息。
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
