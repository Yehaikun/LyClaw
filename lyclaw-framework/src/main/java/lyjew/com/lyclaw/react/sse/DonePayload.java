package lyjew.com.lyclaw.react.sse;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class DonePayload extends SsePayload {

    private final String status;
    private final Long durationMs;

    @JsonCreator
    public DonePayload(
            @JsonProperty("status") String status,
            @JsonProperty("durationMs") Long durationMs) {
        super(SseEventType.DONE);
        this.status = status;
        this.durationMs = durationMs;
    }

    public String getStatus() { return status; }
    public Long getDurationMs() { return durationMs; }
}
