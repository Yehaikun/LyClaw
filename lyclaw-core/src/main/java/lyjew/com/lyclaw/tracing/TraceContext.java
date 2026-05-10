package lyjew.com.lyclaw.tracing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class TraceContext {

    private final String traceId;
    private final Map<String, Long> stageDurations = new LinkedHashMap<>();
    private final Map<String, Long> stageStartTimes = new LinkedHashMap<>();
    private final long requestStartTime;
    private long requestEndTime;

    public TraceContext() {
        this.traceId = UUID.randomUUID().toString().replace("-", "");
        this.requestStartTime = System.currentTimeMillis();
    }

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
