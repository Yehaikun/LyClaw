package lyjew.com.lyclaw.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务节点，表示任务计划中的单个可执行单元。
 * 包含节点标识、类型、描述、依赖关系、所需工具和超时时间等核心属性。
 * 支持 JSON 反序列化。
 */
@NoArgsConstructor
public class TaskNode {

    /** 节点唯一标识 */
    private String nodeId;
    /** 节点类型，如 "tool_call", "llm_chat", "condition" 等 */
    private String type;
    /** 节点描述，说明该节点要完成的具体任务 */
    private String description;
    /** 执行该节点所需的工具名称列表 */
    private List<String> requiredTools = List.of();
    /** 该节点依赖的前置节点ID列表 */
    private List<String> dependencies = List.of();
    /** 该节点的超时时间（毫秒） */
    private long timeoutMs;

    /**
     * JSON反序列化构造函数，所有字段通过 @JsonProperty 映射。
     * requiredTools 和 dependencies 为null时自动初始化为空列表。
     */
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
        // 防御性处理：为null时使用空列表
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
