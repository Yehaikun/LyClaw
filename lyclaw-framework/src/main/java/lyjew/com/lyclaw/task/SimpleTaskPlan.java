package lyjew.com.lyclaw.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.NoArgsConstructor;

import java.util.*;

@NoArgsConstructor
public class SimpleTaskPlan implements TaskPlan {

    private List<TaskNode> nodes = List.of();
    private Map<String, TaskNode> index = Map.of();

    @JsonCreator
    public SimpleTaskPlan(@JsonProperty("nodes") List<TaskNode> nodes) {
        this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes != null ? nodes : List.of()));
        this.index = new HashMap<>();
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

    @Override
    public long getEstimatedCompletionTime() {
        return nodes.stream().mapToLong(TaskNode::getTimeoutMs).sum();
    }

    @Override
    public boolean isReady() { return !nodes.isEmpty(); }
}
