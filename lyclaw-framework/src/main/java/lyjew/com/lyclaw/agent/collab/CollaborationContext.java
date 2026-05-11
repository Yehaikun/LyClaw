package lyjew.com.lyclaw.agent.collab;

import lombok.Builder;
import lombok.Data;
import lyjew.com.lyclaw.agent.AgentHandle;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class CollaborationContext {

    private String collaborationId;
    private String modeId;
    private List<AgentHandle> participants;
    private Map<String, Object> sharedState;
    private int maxRounds;
    private long timeoutMs;

    @SuppressWarnings("unchecked")
    public <T> T getState(String key) {
        return (T) sharedState.get(key);
    }

    public void setState(String key, Object value) {
        sharedState.put(key, value);
    }
}
