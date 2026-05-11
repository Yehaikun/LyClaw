package lyjew.com.lyclaw.task;

import java.util.*;

/**
 * 任务计划的有向图表示，管理任务节点的拓扑结构和执行状态。
 * 支持获取就绪节点、传播失败、计算进度和关键路径等操作。
 */
public class PlanGraph {

    /** 任务节点的执行状态枚举 */
    public enum TaskNodeStatus { PENDING, READY, RUNNING, COMPLETED, FAILED, SKIPPED }

    /** 节点ID到节点对象的映射 */
    private final Map<String, TaskNode> nodeMap = new HashMap<>();
    /** 邻接表：父节点 → 子节点列表（正向依赖） */
    private final Map<String, List<String>> adjacency = new HashMap<>();
    /** 逆邻接表：子节点 → 父节点列表（反向依赖） */
    private final Map<String, List<String>> reverseAdjacency = new HashMap<>();
    /** 节点ID到执行状态的映射 */
    private final Map<String, TaskNodeStatus> statusMap = new HashMap<>();

    /**
     * 向图中添加一个任务节点，初始状态为 PENDING。
     *
     * @param node 要添加的任务节点
     */
    public void addNode(TaskNode node) {
        nodeMap.put(node.getNodeId(), node);
        adjacency.putIfAbsent(node.getNodeId(), new ArrayList<>());
        reverseAdjacency.putIfAbsent(node.getNodeId(), new ArrayList<>());
        statusMap.put(node.getNodeId(), TaskNodeStatus.PENDING);
    }

    /**
     * 添加一条依赖边：fromNodeId 完成后才能执行 toNodeId。
     *
     * @param fromNodeId 前置节点ID
     * @param toNodeId   后继节点ID
     */
    public void addEdge(String fromNodeId, String toNodeId) {
        adjacency.computeIfAbsent(fromNodeId, k -> new ArrayList<>()).add(toNodeId);
        reverseAdjacency.computeIfAbsent(toNodeId, k -> new ArrayList<>()).add(fromNodeId);
    }

    /**
     * 获取当前所有就绪可执行的节点。
     * 节点就绪条件：状态为 PENDING，且所有前置节点均已 COMPLETED。
     *
     * @return 就绪节点列表
     */
    public List<TaskNode> getReadyNodes() {
        List<TaskNode> ready = new ArrayList<>();
        for (Map.Entry<String, TaskNodeStatus> entry : statusMap.entrySet()) {
            if (entry.getValue() == TaskNodeStatus.PENDING) {
                List<String> parents = reverseAdjacency.get(entry.getKey());
                // 所有父节点都已完成（或无父节点），则该节点就绪
                boolean allParentsCompleted = parents == null || parents.stream()
                        .allMatch(p -> statusMap.get(p) == TaskNodeStatus.COMPLETED);
                if (allParentsCompleted) {
                    ready.add(nodeMap.get(entry.getKey()));
                }
            }
        }
        return ready;
    }

    /**
     * 标记指定节点为已完成状态。
     *
     * @param nodeId 要标记的节点ID
     */
    public void markCompleted(String nodeId) { statusMap.put(nodeId, TaskNodeStatus.COMPLETED); }

    /**
     * 标记指定节点为失败状态，并级联跳过其所有后继节点。
     *
     * @param nodeId 失败的节点ID
     */
    public void markFailed(String nodeId) {
        statusMap.put(nodeId, TaskNodeStatus.FAILED);
        cascadeSkip(nodeId); // 级联传播失败影响
    }

    /**
     * 级联跳过：从失败节点出发，BFS遍历所有直接和间接后继节点，将其标记为 SKIPPED。
     *
     * @param failedNodeId 失败节点ID
     */
    private void cascadeSkip(String failedNodeId) {
        Queue<String> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();
        queue.add(failedNodeId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) continue; // 已访问过则跳过，避免循环
            List<String> children = adjacency.get(current);
            if (children != null) {
                for (String child : children) {
                    statusMap.put(child, TaskNodeStatus.SKIPPED);
                    queue.add(child); // 继续传播到更深的子孙节点
                }
            }
        }
    }

    /**
     * 判断整个计划图是否已完成（所有节点状态为 COMPLETED 或 SKIPPED）。
     *
     * @return 已完成返回 true
     */
    public boolean isFullyCompleted() {
        return statusMap.values().stream()
                .allMatch(s -> s == TaskNodeStatus.COMPLETED || s == TaskNodeStatus.SKIPPED);
    }

    /**
     * 获取关键路径上的节点列表（当前简化实现为全部节点）。
     *
     * @return 关键路径节点列表
     */
    public List<TaskNode> getCriticalPath() { return new ArrayList<>(nodeMap.values()); }

    /**
     * 计算当前计划的执行进度，以已完成或已跳过的节点占总节点数的比例表示。
     *
     * @return 进度值（0.0~1.0），空图返回1.0
     */
    public double getProgress() {
        if (nodeMap.isEmpty()) return 1.0;
        long completed = statusMap.values().stream()
                .filter(s -> s == TaskNodeStatus.COMPLETED || s == TaskNodeStatus.SKIPPED).count();
        return (double) completed / nodeMap.size();
    }

    public Map<String, TaskNode> getNodeMap() { return nodeMap; }
    public Map<String, TaskNodeStatus> getStatusMap() { return statusMap; }
}
