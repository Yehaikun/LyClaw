package lyjew.com.lyclaw.tracing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 全链路追踪上下文 —— 记录请求经过的每个阶段的耗时，支持 toJson 导出用于日志。
 *
 * <p>TraceContext 贯穿 Pipeline 的整个执行过程。每个 PipelineStage
 * 在开始和结束时调用 beginStage() / endStage()，最终在 LoggingInterceptor
 * 或日志输出时调用 toJson() 输出完整的链路耗时信息。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class TraceContext {

    /** 追踪 ID，全局唯一 */
    private final String traceId;

    /** 阶段耗时记录（阶段名 -> 耗时 ms），使用 LinkedHashMap 保证阶段顺序 */
    private final Map<String, Long> stageDurations = new LinkedHashMap<>();

    /** 阶段开始时间记录（阶段名 -> 开始时间戳 ms） */
    private final Map<String, Long> stageStartTimes = new LinkedHashMap<>();

    /** 请求开始时间 */
    private final long requestStartTime;

    /** 请求结束时间 */
    private long requestEndTime;

    /** 自动生成 traceId */
    public TraceContext() {
        this.traceId = UUID.randomUUID().toString().replace("-", "");
        this.requestStartTime = System.currentTimeMillis();
    }

    /** 指定 traceId（如从 HTTP 头传入） */
    public TraceContext(String traceId) {
        this.traceId = traceId;
        this.requestStartTime = System.currentTimeMillis();
    }

    public void beginStage(String stageName) {
        stageStartTimes.put(stageName, System.currentTimeMillis());
    }

    public void endStage(String stageName) {
        Long startTime = stageStartTimes.remove(stageName);
        if (startTime != null) {
            long duration = System.currentTimeMillis() - startTime;
            stageDurations.merge(stageName, duration, Long::sum);
        }
    }

    public long getStageDuration(String stageName) {
        return stageDurations.getOrDefault(stageName, -1L);
    }

    public long getTotalDuration() {
        long end = requestEndTime > 0 ? requestEndTime : System.currentTimeMillis();
        return end - requestStartTime;
    }

    public void markEnd() { this.requestEndTime = System.currentTimeMillis(); }

    public String getTraceId() { return traceId; }

    public Map<String, Long> getStageDurations() { return stageDurations; }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"traceId\":\"").append(traceId)
                .append("\",\"stages\":{");
        boolean first = true;
        for (Map.Entry<String, Long> e : stageDurations.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey())
                    .append("\":").append(e.getValue());
            first = false;
        }
        sb.append("},\"total\":").append(getTotalDuration()).append("}");
        return sb.toString();
    }
}