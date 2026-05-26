package lyjew.com.lyclaw.react.sse;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class DonePayload extends SsePayload {

    private final String status;
    private final Long durationMs;
    private final Boolean fallback;

    @JsonCreator
    public DonePayload(
            @JsonProperty("status") String status,
            @JsonProperty("durationMs") Long durationMs,
            @JsonProperty("fallback") Boolean fallback) {
        super(SseEventType.DONE);
        this.status = status;
        this.durationMs = durationMs;
        this.fallback = fallback;
    }

    public DonePayload(String status, Long durationMs) {
        this(status, durationMs, null);
    }

    public String getStatus() { return status; }
    public Long getDurationMs() { return durationMs; }
    public Boolean getFallback() { return fallback; }
}
