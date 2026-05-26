package lyjew.com.lyclaw.react.sse;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class PipelineStatusPayload extends SsePayload {

    private final String stage;
    private final String status;
    private final String message;

    @JsonCreator
    public PipelineStatusPayload(
            @JsonProperty("type") SseEventType type,
            @JsonProperty("stage") String stage,
            @JsonProperty("status") String status,
            @JsonProperty("message") String message) {
        super(type);
        this.stage = stage;
        this.status = status;
        this.message = message;
    }

    public PipelineStatusPayload(SseEventType type, String status, String message) {
        this(type, type.getEventName(), status, message);
    }

    public String getStage() { return stage; }
    public String getStatus() { return status; }
    public String getMessage() { return message; }
}
