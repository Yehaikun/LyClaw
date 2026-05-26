package lyjew.com.lyclaw.react.sse;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public abstract class SsePayload {

    private final SseEventType type;
    private final long timestamp;

    protected SsePayload(@JsonProperty("type") SseEventType type) {
        this.type = type;
        this.timestamp = System.currentTimeMillis();
    }

    @JsonIgnore
    public SseEventType getType() { return type; }

    @JsonProperty("type")
    public String getTypeName() { return type.name(); }

    public long getTimestamp() { return timestamp; }
}
