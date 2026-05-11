package lyjew.com.lyclaw.tracing;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class TraceContext {

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private String serviceName;
    private final Map<String, Long> stageDurations = new LinkedHashMap<>();
    private final Map<String, Long> stageStartTimes = new LinkedHashMap<>();
    private final long requestStartTime;
    private long requestEndTime;

    public TraceContext() {
        this.traceId = UUID.randomUUID().toString().replace("-", "");
        this.spanId = UUID.randomUUID().toString().replace("-", "");
        this.parentSpanId = null;
        this.requestStartTime = System.currentTimeMillis();
    }

    public TraceContext(String traceId) {
        this.traceId = traceId;
        this.spanId = UUID.randomUUID().toString().replace("-", "");
        this.parentSpanId = null;
        this.requestStartTime = System.currentTimeMillis();
    }

    private TraceContext(String traceId, String spanId, String parentSpanId, String serviceName) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.serviceName = serviceName;
        this.requestStartTime = System.currentTimeMillis();
    }

    public static TraceContext incoming(String traceId, String parentSpanId) {
        String spanId = UUID.randomUUID().toString().replace("-", "");
        return new TraceContext(traceId, spanId, parentSpanId, null);
    }

    public TraceContext newSpan() {
        return new TraceContext(this.traceId, UUID.randomUUID().toString().replace("-", ""),
                this.spanId, this.serviceName);
    }

    public static void wrap(Runnable runnable) {
        String traceId = MDC.get(TraceConstants.MDC_TRACE_ID);
        String spanId = MDC.get(TraceConstants.MDC_SPAN_ID);
        String service = MDC.get(TraceConstants.MDC_SERVICE);
        try {
            runnable.run();
        } finally {
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

    public void markEnd() {
        this.requestEndTime = System.currentTimeMillis();
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public String getParentSpanId() {
        return parentSpanId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public Map<String, Long> getStageDurations() {
        return stageDurations;
    }

    public long getRequestStartTime() {
        return requestStartTime;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"traceId\":\"").append(traceId)
                .append("\",\"spanId\":\"").append(spanId);
        if (parentSpanId != null && !parentSpanId.isEmpty()) {
            sb.append("\",\"parentSpanId\":\"").append(parentSpanId);
        }
        if (serviceName != null && !serviceName.isEmpty()) {
            sb.append("\",\"service\":\"").append(serviceName);
        }
        sb.append("\",\"stages\":{");
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
