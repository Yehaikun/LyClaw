package lyjew.com.lyclaw.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.NoArgsConstructor;

import java.util.*;

/**
 * 简单任务计划实现，由扁平的任务节点列表组成。
 * 支持 JSON 反序列化，按节点超时时间总和估算完成时间。
 */
@NoArgsConstructor
public class SimpleTaskPlan implements TaskPlan {

    /** 不可变的任务节点列表 */
    private List<TaskNode> nodes = List.of();
    /** 节点ID到节点对象的快速索引映射 */
    private Map<String, TaskNode> index = Map.of();

    /**
     * JSON反序列化构造函数，接收节点列表并构建索引。
     *
     * @param nodes 任务节点列表，可为null
     */
    @JsonCreator
    public SimpleTaskPlan(@JsonProperty("nodes") List<TaskNode> nodes) {
        // 存储为不可变列表，防止外部修改
        this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes != null ? nodes : List.of()));
        this.index = new HashMap<>();
        // 构建节点ID到节点的快速查找索引
        for (TaskNode node : this.nodes) {
            index.put(node.getNodeId(), node);
        }
    }

    @Override
    public List<TaskNode> getNodes() { return nodes; }

    @Override
    public List<String> getDependencies(String nodeId) {
        TaskNode node = index.get(nodeId);
        return node != null ? node.getDependencies() : List.of();
    }

    /**
     * 估算完成所有任务所需的总时间，以所有节点的超时时间之和计。
     *
     * @return 预估总耗时（毫秒）
     */
    @Override
    public long getEstimatedCompletionTime() {
        return nodes.stream().mapToLong(TaskNode::getTimeoutMs).sum();
    }

    /** 只要节点列表非空即视为就绪 */
    @Override
    public boolean isReady() { return !nodes.isEmpty(); }
}
