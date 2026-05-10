package lyjew.com.lyclaw.orchestration;

import lombok.Builder;
import lombok.Data;
import lyjew.com.lyclaw.agent.AgentTask;
import lyjew.com.lyclaw.context.ChatContext;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class OrchestrationContext {

    private ChatContext chatContext;
    private List<AgentTask> tasks;
    private String collaborationModeId;
    private Map<String, Object> attributes;
    private String collaborationId;

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }
}
