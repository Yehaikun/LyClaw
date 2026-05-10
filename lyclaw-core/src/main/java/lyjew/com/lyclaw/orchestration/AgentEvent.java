package lyjew.com.lyclaw.orchestration;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class AgentEvent {

    public enum EventType {
        TASK_STARTED, TASK_PROGRESS, TASK_COMPLETED, TASK_FAILED,
        AGENT_STATE_CHANGED, COLLABORATION_STARTED, COLLABORATION_ENDED,
        CONSENSUS_REACHED, MESSAGE_RECEIVED, ALERT_TRIGGERED
    }

    private EventType type;
    private String agentId;
    private String data;
    private Map<String, Object> metadata;
    private long timestamp;
}
