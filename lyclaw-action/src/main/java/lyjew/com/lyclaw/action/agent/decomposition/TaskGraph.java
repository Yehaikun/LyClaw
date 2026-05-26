package lyjew.com.lyclaw.action.agent.decomposition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class TaskGraph {

    private final List<TaskNode> nodes;
    private final List<TaskEdge> edges;

    public TaskGraph(List<TaskNode> nodes, List<TaskEdge> edges) {
        this.nodes = new ArrayList<>(nodes);
        this.edges = new ArrayList<>(edges);
    }

    public List<TaskNode> getRootNodes() {
        return nodes.stream()
                .filter(n -> edges.stream().noneMatch(e -> e.getToNodeId().equals(n.getId())))
                .collect(Collectors.toList());
    }

    public List<TaskNode> getNextNodes(TaskNode completed) {
        if (completed == null) {
            return getRootNodes();
        }
        String completedId = completed.getId();
        if (completedId == null) {
            return getRootNodes();
        }
        List<String> nextIds = edges.stream()
                .filter(e -> e.getFromNodeId() != null && e.getFromNodeId().equals(completedId))
                .map(TaskEdge::getToNodeId)
                .filter(id -> id != null)
                .collect(Collectors.toList());

        return nodes.stream()
                .filter(n -> n.getId() != null && nextIds.contains(n.getId()))
                .filter(n -> {
                    List<String> deps = edges.stream()
                            .filter(e -> e.getToNodeId() != null && e.getToNodeId().equals(n.getId()))
                            .map(TaskEdge::getFromNodeId)
                            .filter(id -> id != null)
                            .collect(Collectors.toList());
                    return deps.stream().allMatch(depId ->
                            nodes.stream().anyMatch(on -> on.getId() != null && on.getId().equals(depId)
                                    && on.getStatus() == TaskNode.Status.COMPLETED));
                })
                .collect(Collectors.toList());
    }

    public boolean isComplete() {
        return nodes.stream().allMatch(n -> n.getStatus() == TaskNode.Status.COMPLETED
                || n.getStatus() == TaskNode.Status.FAILED
                || n.getStatus() == TaskNode.Status.SKIPPED);
    }

    public boolean hasFailed() {
        return nodes.stream().anyMatch(n -> n.getStatus() == TaskNode.Status.FAILED);
    }

    public List<TaskNode> getNodes() { return Collections.unmodifiableList(nodes); }
    public List<TaskEdge> getEdges() { return Collections.unmodifiableList(edges); }

    public void updateNodeStatus(String nodeId, TaskNode.Status status) {
        nodes.stream().filter(n -> n.getId().equals(nodeId)).findFirst()
                .ifPresent(n -> n.setStatus(status));
    }

    public void assignNode(String nodeId, String agentId) {
        nodes.stream().filter(n -> n.getId().equals(nodeId)).findFirst()
                .ifPresent(n -> n.setAssignedAgentId(agentId));
    }

    public int totalNodes() { return nodes.size(); }
    public int completedNodes() {
        return (int) nodes.stream().filter(n -> n.getStatus() == TaskNode.Status.COMPLETED).count();
    }
}
