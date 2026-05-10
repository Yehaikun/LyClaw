package lyjew.com.lyclaw.task;

import java.util.List;

/**
 * 任务节点值对象 —— 描述一个可执行的子任务。
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class TaskNode {

    private final String nodeId;
    private final String type;
    private final String description;
    private final List<String> requiredTools;
    private final List<String> dependencies;
    private final long timeoutMs;

    public TaskNode(String nodeId, String type, String description,
                    List<String> requiredTools, List<String> dependencies,
                    long timeoutMs) {
        this.nodeId = nodeId;
        this.type = type;
        this.description = description;
        this.requiredTools = requiredTools;
        this.dependencies = dependencies;
        this.timeoutMs = timeoutMs;
    }

    public String getNodeId() { return nodeId; }
    public String getType() { return type; }
    public String getDescription() { return description; }
    public List<String> getRequiredTools() { return requiredTools; }
    public List<String> getDependencies() { return dependencies; }
    public long getTimeoutMs() { return timeoutMs; }
}