package lyjew.com.lyclaw.react.sse;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class SessionPayload extends SsePayload {

    private final String sessionId;
    private final String agentId;
    private final boolean isNew;

    @JsonCreator
    public SessionPayload(
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("agentId") String agentId,
            @JsonProperty("isNew") boolean isNew) {
        super(SseEventType.SESSION_CREATED);
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.isNew = isNew;
    }

    public String getSessionId() { return sessionId; }
    public String getAgentId() { return agentId; }
    public boolean isNew() { return isNew; }
}
