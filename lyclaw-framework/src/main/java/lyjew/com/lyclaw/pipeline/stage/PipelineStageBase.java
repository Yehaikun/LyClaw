package lyjew.com.lyclaw.pipeline.stage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.react.sse.SseEventFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public abstract class PipelineStageBase implements ReactivePipelineStage {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected ServerSentEvent<String> sseEvent(String eventType, String payload) {
        return ServerSentEvent.<String>builder().event(eventType).data(payload).build();
    }

    protected ServerSentEvent<String> sseEvent(String eventType, Map<String, Object> data) {
        try {
            String json = SseEventFactory.getObjectMapper().writeValueAsString(data);
            return sseEvent(eventType, json);
        } catch (JsonProcessingException e) {
            log.error("SSE event JSON 序列化失败: event={}", eventType, e);
            return sseEvent(eventType, "{}");
        }
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
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("timestamp", Instant.now().toString());
            entry.put("level", level);
            entry.put("event", event);
            entry.put("stage", stage);
            entry.put("message", message);
            if (durationMs != null) {
                entry.put("durationMs", durationMs);
            }
            return SseEventFactory.getObjectMapper().writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            return "{\"level\":\"" + level + "\",\"event\":\"" + event + "\",\"stage\":\"" + stage + "\"}";
        }
    }
}
