package lyjew.com.lyclaw.reflect.topology;

import java.util.*;

/**
 * 拓扑结构校验器，在 build() 或动态加载时调用。
 */
public final class TopologyValidator {

    private TopologyValidator() {}

    public static void validate(ReflectionTopology topology) {
        List<String> errors = new ArrayList<>();
        Map<String, NodeDef> nodes = topology.getNodes();
        List<Edge> edges = topology.getEdges();

        if (topology.getEntryNodeId() == null || topology.getEntryNodeId().isBlank()) {
            errors.add("入口节点未设置");
        } else if (!nodes.containsKey(topology.getEntryNodeId())) {
            errors.add("入口节点 '" + topology.getEntryNodeId() + "' 不在节点集合中");
        }

        for (String exitId : topology.getExitNodeIds()) {
            if (!exitId.equals("STOP") && !nodes.containsKey(exitId)) {
                errors.add("出口节点 '" + exitId + "' 不在节点集合中");
            }
        }

        for (Edge edge : edges) {
            for (String from : edge.getFrom()) {
                if (!nodes.containsKey(from)) {
                    errors.add("边 " + edge.getEdgeId() + " 的 from='" + from + "' 不存在");
                }
            }
            for (String to : edge.getTo()) {
                if (!to.equals("STOP") && !nodes.containsKey(to)) {
                    errors.add("边 " + edge.getEdgeId() + " 的 to='" + to + "' 不存在");
                }
            }
            if (edge.getEdgeType() == EdgeType.FORK && edge.getTo().size() < 2) {
                errors.add("Fork 边 " + edge.getEdgeId() + " 目标数 < 2");
            }
            if (edge.getEdgeType() == EdgeType.JOIN && edge.getFrom().size() < 2) {
                errors.add("Join 边 " + edge.getEdgeId() + " 来源数 < 2");
            }
        }

        // 检查非出口节点是否有出边
        for (String nodeId : nodes.keySet()) {
            if (topology.getExitNodeIds().contains(nodeId)) continue;
            boolean hasOutgoing = edges.stream().anyMatch(e -> e.getFrom().contains(nodeId));
            if (!hasOutgoing) {
                errors.add("节点 '" + nodeId + "' 没有出边（非出口节点必须有出边）");
            }
        }

        // 嵌套拓扑节点必须有 subTopology
        for (NodeDef nd : nodes.values()) {
            if (nd.getPrimitiveType() == PrimitiveType.COMPOSITE && nd.getSubTopology() == null) {
                errors.add("COMPOSITE 节点 '" + nd.getNodeId() + "' 缺少 subTopology");
            }
        }

        // 简单回路检测：存在 Router 和 STOP
        boolean hasRouter = nodes.values().stream().anyMatch(n -> n.getPrimitiveType() == PrimitiveType.ROUTER);
        boolean hasStopExit = topology.getExitNodeIds().contains("STOP")
                || edges.stream().anyMatch(e -> e.getTo().contains("STOP"))
                || edges.stream().anyMatch(e -> e.getCondition() == EdgeCondition.ON_STOP);
        if (hasCycle(edges, nodes.keySet()) && (!hasRouter || !hasStopExit)) {
            errors.add("拓扑存在回路但缺少 Router 节点或 STOP 出口");
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("拓扑校验失败: " + String.join("; ", errors));
        }
    }

    private static boolean hasCycle(List<Edge> edges, Set<String> nodeIds) {
        for (String nodeId : nodeIds) {
            Set<String> visited = new HashSet<>();
            Set<String> path = new HashSet<>();
            if (dfs(nodeId, edges, visited, path)) return true;
        }
        return false;
    }

    private static boolean dfs(String nodeId, List<Edge> edges, Set<String> visited, Set<String> path) {
        if (path.contains(nodeId)) return true;
        if (visited.contains(nodeId)) return false;
        visited.add(nodeId);
        path.add(nodeId);
        for (Edge e : edges) {
            if (e.getFrom().contains(nodeId)) {
                for (String to : e.getTo()) {
                    if (dfs(to, edges, visited, path)) return true;
                }
            }
        }
        path.remove(nodeId);
        return false;
    }
}
