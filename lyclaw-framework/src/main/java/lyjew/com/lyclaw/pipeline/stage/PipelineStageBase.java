package lyjew.com.lyclaw.pipeline.stage;

import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import org.springframework.http.codec.ServerSentEvent;

import java.time.Instant;
import java.util.List;

/**
 * 响应式管线阶段的抽象基类，提供公共工具方法。
 */
public abstract class PipelineStageBase implements ReactivePipelineStage {

    protected ServerSentEvent<String> sseEvent(String eventType, String payload) {
        return ServerSentEvent.<String>builder().event(eventType).data(payload).build();
    }

    protected String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    protected String logJson(String level, String event, String stage, String traceId,
                              String message, Long durationMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"timestamp\":\"").append(Instant.now().toString()).append("\"");
        sb.append(",\"level\":\"").append(level).append("\"");
        sb.append(",\"event\":\"").append(event).append("\"");
        sb.append(",\"stage\":\"").append(stage).append("\"");
        sb.append(",\"traceId\":\"").append(traceId).append("\"");
        sb.append(",\"message\":\"").append(escapeJson(message)).append("\"");
        if (durationMs != null) {
            sb.append(",\"durationMs\":").append(durationMs);
        }
        sb.append("}");
        return sb.toString();
    }
}
