package lyjew.com.lyclaw.pipeline.stage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 响应式管线阶段的抽象基类，提供 SSE 事件构建、JSON 转义和结构化日志工具方法。
 */
public abstract class PipelineStageBase implements ReactivePipelineStage {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private static final ObjectMapper objectMapper = new ObjectMapper();

    protected ServerSentEvent<String> sseEvent(String eventType, String payload) {
        return ServerSentEvent.<String>builder().event(eventType).data(payload).build();
    }

    /** 用 Map 构建 JSON 数据的 SSE 事件，避免手工拼接 JSON 字符串。 */
    protected ServerSentEvent<String> sseEvent(String eventType, Map<String, Object> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
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
            return objectMapper.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            return "{\"level\":\"" + level + "\",\"event\":\"" + event + "\",\"stage\":\"" + stage + "\"}";
        }
    }
}
