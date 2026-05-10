package lyjew.com.lyclaw.task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * 规划图 —— 表示任务拆解后的 DAG 结构。
 *
 * <p>DAG 节点映射: nodeId → TaskNode
 * 邻接表: nodeId → 依赖它的子节点列表 (出边)
 * 反向邻接表: nodeId → 它所依赖的父节点列表 (入边)
 * 并行组: 同一组内的节点可并行执行</p>
 *
 * @since 2.0
 */
public class PlanGraph {

    public enum TaskNodeStatus {
        PENDING, READY, RUNNING, COMPLETED, FAILED, SKIPPED
    }

    private final Map<String, TaskNode> nodeMap = new HashMap<>();
    private final Map<String, List<String>> adjacency = new HashMap<>();
    private final Map<String, List<String>> reverseAdjacency = new HashMap<>();
    private final Map<String, TaskNodeStatus> statusMap = new HashMap<>();

    public void addNode(TaskNode node) {
        nodeMap.put(node.getNodeId(), node);
        adjacency.putIfAbsent(node.getNodeId(), new ArrayList<>());
        reverseAdjacency.putIfAbsent(node.getNodeId(), new ArrayList<>());
        statusMap.put(node.getNodeId(), TaskNodeStatus.PENDING);
    }

    public void addEdge(String fromNodeId, String toNodeId) {
        adjacency.computeIfAbsent(fromNodeId, k -> new ArrayList<>()).add(toNodeId);
        reverseAdjacency.computeIfAbsent(toNodeId, k -> new ArrayList<>()).add(fromNodeId);
    }

    public List<TaskNode> getReadyNodes() {
        List<TaskNode> ready = new ArrayList<>();
        for (Map.Entry<String, TaskNodeStatus> entry : statusMap.entrySet()) {
            if (entry.getValue() == TaskNodeStatus.PENDING) {
                List<String> parents = reverseAdjacency.get(entry.getKey());
                boolean allParentsCompleted = parents == null || parents.stream()
                        .allMatch(p -> statusMap.get(p) == TaskNodeStatus.COMPLETED);
                if (allParentsCompleted) {
                    ready.add(nodeMap.get(entry.getKey()));
                }
            }
        }
        return ready;
    }

    public void markCompleted(String nodeId) {
        statusMap.put(nodeId, TaskNodeStatus.COMPLETED);
    }

    public void markFailed(String nodeId) {
        statusMap.put(nodeId, TaskNodeStatus.FAILED);
        cascadeSkip(nodeId);
    }

    private void cascadeSkip(String failedNodeId) {
        Queue<String> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();
        queue.add(failedNodeId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) continue;
            List<String> children = adjacency.get(current);
            if (children != null) {
                for (String child : children) {
                    statusMap.put(child, TaskNodeStatus.SKIPPED);
                    queue.add(child);
                }
            }
        }
    }

    public boolean isFullyCompleted() {
        return statusMap.values().stream()
                .allMatch(s -> s == TaskNodeStatus.COMPLETED || s == TaskNodeStatus.SKIPPED);
    }

    public List<TaskNode> getCriticalPath() {
        // simplified: return nodes with no slack
        return new ArrayList<>(nodeMap.values());
    }

    public double getProgress() {
        if (nodeMap.isEmpty()) return 1.0;
        long completed = statusMap.values().stream()
                .filter(s -> s == TaskNodeStatus.COMPLETED || s == TaskNodeStatus.SKIPPED)
                .count();
        return (double) completed / nodeMap.size();
    }

    public Map<String, TaskNode> getNodeMap() { return nodeMap; }
    public Map<String, TaskNodeStatus> getStatusMap() { return statusMap; }
}
