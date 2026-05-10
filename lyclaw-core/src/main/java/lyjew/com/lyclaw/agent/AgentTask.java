package lyjew.com.lyclaw.agent;

import java.util.Map;

public class AgentTask {

    private final String taskId;
    private final String type;
    private final String target;
    private final String payload;
    private final Map<String, Object> metadata;

    public AgentTask(String taskId, String type, String target,
                     String payload, Map<String, Object> metadata) {
        this.taskId = taskId;
        this.type = type;
        this.target = target;
        this.payload = payload;
        this.metadata = metadata;
    }

    public String getTaskId() { return taskId; }
    public String getType() { return type; }
    public String getTarget() { return target; }
    public String getPayload() { return payload; }
    public Map<String, Object> getMetadata() { return metadata; }
}
