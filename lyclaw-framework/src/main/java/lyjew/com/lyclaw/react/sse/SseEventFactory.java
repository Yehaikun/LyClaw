package lyjew.com.lyclaw.react.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.http.codec.ServerSentEvent;

public final class SseEventFactory {

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private SseEventFactory() {}

    public static <T extends SsePayload> ServerSentEvent<String> createEvent(T payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            return ServerSentEvent.<String>builder()
                    .event(payload.getType().getEventName())
                    .data(json)
                    .build();
        } catch (JsonProcessingException e) {
            return ServerSentEvent.<String>builder()
                    .event("error")
                    .data("{\"message\":\"Serialization failed\"}")
                    .build();
        }
    }

    public static ServerSentEvent<String> message(String text) {
        return createEvent(new PipelineStatusPayload(SseEventType.MESSAGE, "llm", text));
    }

    public static ServerSentEvent<String> thinking(String text) {
        return ServerSentEvent.<String>builder().event("thinking").data(text).build();
    }

    public static ServerSentEvent<String> status(String text) {
        return ServerSentEvent.<String>builder().event("status").data(text).build();
    }

    public static ServerSentEvent<String> toolCall(ToolCallPayload p) {
        return createEvent(p);
    }

    public static ServerSentEvent<String> toolCallExecuting(String id, String name, String msg, String args) {
        return createEvent(ToolCallPayload.executing(id, name, msg, args));
    }

    public static ServerSentEvent<String> toolCallDone(String id, String name, String msg, String args, String result, boolean success) {
        return createEvent(ToolCallPayload.done(id, name, msg, args, result, success));
    }

    public static ServerSentEvent<String> toolApproval(String id, String name, String args, String msg) {
        return createEvent(new ToolApprovalPayload(id, name, args, msg));
    }

    public static ServerSentEvent<String> subagentProgress(String agentId, String type, String data) {
        return createEvent(new SubagentProgressPayload(agentId, type, data));
    }

    public static ServerSentEvent<String> pipelineStatus(SseEventType type, String message) {
        return createEvent(new PipelineStatusPayload(type, "info", message));
    }

    public static ServerSentEvent<String> pipelineStatus(SseEventType type, String status, String message) {
        return createEvent(new PipelineStatusPayload(type, status, message));
    }

    public static ServerSentEvent<String> sessionCreated(String sessionId, String agentId, boolean isNew) {
        return createEvent(new SessionPayload(sessionId, agentId, isNew));
    }

    public static ServerSentEvent<String> done(String status, Long durationMs) {
        return createEvent(new DonePayload(status, durationMs));
    }

    public static ServerSentEvent<String> metrics(long durationMs, int tasks, int toolCalls, int tokens) {
        return createEvent(new MetricsPayload(durationMs, tasks, toolCalls, tokens));
    }

    public static ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
