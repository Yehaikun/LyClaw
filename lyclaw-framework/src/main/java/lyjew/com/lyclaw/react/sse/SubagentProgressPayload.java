package lyjew.com.lyclaw.react.sse;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class SubagentProgressPayload extends SsePayload {

    private final String agentId;
    private final String subType;
    private final String data;

    @JsonCreator
    public SubagentProgressPayload(
            @JsonProperty("agentId") String agentId,
            @JsonProperty("subType") String subType,
            @JsonProperty("data") String data) {
        super(SseEventType.SUBAGENT_PROGRESS);
        this.agentId = agentId;
        this.subType = subType;
        this.data = data;
    }

    public String getAgentId() { return agentId; }
    @JsonProperty("type") public String getSubType() { return subType; }
    public String getData() { return data; }
}
