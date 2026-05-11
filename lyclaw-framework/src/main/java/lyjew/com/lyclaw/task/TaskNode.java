package lyjew.com.lyclaw.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor
public class TaskNode {

    private String nodeId;
    private String type;
    private String description;
    private List<String> requiredTools = List.of();
    private List<String> dependencies = List.of();
    private long timeoutMs;

    @JsonCreator
    public TaskNode(@JsonProperty("nodeId") String nodeId,
                    @JsonProperty("type") String type,
                    @JsonProperty("description") String description,
                    @JsonProperty("requiredTools") List<String> requiredTools,
                    @JsonProperty("dependencies") List<String> dependencies,
                    @JsonProperty("timeoutMs") long timeoutMs) {
        this.nodeId = nodeId;
        this.type = type;
        this.description = description;
        this.requiredTools = requiredTools != null ? requiredTools : List.of();
        this.dependencies = dependencies != null ? dependencies : List.of();
        this.timeoutMs = timeoutMs;
    }

    public String getNodeId() { return nodeId; }
    public String getType() { return type; }
    public String getDescription() { return description; }
    public List<String> getRequiredTools() { return requiredTools; }
    public List<String> getDependencies() { return dependencies; }
    public long getTimeoutMs() { return timeoutMs; }
}
