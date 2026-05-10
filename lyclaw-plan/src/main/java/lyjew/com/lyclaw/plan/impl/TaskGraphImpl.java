package lyjew.com.lyclaw.plan.impl;

import lyjew.com.lyclaw.task.PlanGraph;
import lyjew.com.lyclaw.task.TaskNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

/**
 * 增强型任务图实现 —— 在 PlanGraph 基类上提供完整的 DAG 算法支持。
 *
 * <p>相较于基类 PlanGraph，增加了以下增强能力：
 * <ul>
 *   <li><b>真正的关键路径计算</b>：基于拓扑排序的最长路径算法，
 *       而非基类的全节点返回</li>
 *   <li><b>加权边</b>：每条边可附加权重（如预估耗时），用于精确的成本估算</li>
 *   <li><b>拓扑排序结果缓存</b>：多次调用避免重复计算</li>
 *   <li><b>进度详情</b>：返回按状态分组的节点计数</li>
 *   <li><b>依赖分析</b>：计算每个节点的祖先/后代集合</li>
 *   <li><b>子图提取</b>：提取从指定节点可达的子图</li>
 *   <li><b>并行度分析</b>：估算 DAG 的并行度（宽度）</li>
 * </ul>
 * </p>
 *
 * <p><b>设计动机</b>：PlanGraph 基类提供了基本的 DAG 操作（节点/边管理、状态追踪），
 * 但关键路径计算是简化的。TaskGraphImpl 通过最长路径算法提供精确的关键路径分析，
 * 并增加了并行度分析、子图提取等高级功能，使规划器能够做出更优的调度决策。</p>
 *
 * @since 2.0
 * @author LyClaw Team
 * @see PlanGraph
 * @see TaskNode
 */
public class TaskGraphImpl extends PlanGraph {

    /** 边权重映射：fromNodeId → (toNodeId → weight) */
    private final Map<String, Map<String, Long>> edgeWeights = new HashMap<>();

    /** 节点权重（预估耗时）映射 */
    private final Map<String, Long> nodeWeights = new HashMap<>();

    /** 拓扑排序结果缓存（失效时重新计算） */
    private List<String> cachedTopoOrder;

    /** 缓存是否有效 */
    private boolean cacheValid;

    /**
     * 添加节点并可选设置其预估耗时权重。
     *
     * @param node         任务节点
     * @param estimatedMs  预估耗时（毫秒），用于关键路径计算
     */
    public void addNode(TaskNode node, long estimatedMs) {
        super.addNode(node);
        nodeWeights.put(node.getNodeId(), estimatedMs);
        invalidateCache();
    }

    @Override
    public void addNode(TaskNode node) {
        super.addNode(node);
        nodeWeights.putIfAbsent(node.getNodeId(), node.getTimeoutMs());
        invalidateCache();
    }

    /**
     * 添加加权边。
     *
     * @param fromNodeId 源节点
     * @param toNodeId   目标节点
     * @param weight     边权重（0 表示无额外成本）
     */
    public void addEdge(String fromNodeId, String toNodeId, long weight) {
        super.addEdge(fromNodeId, toNodeId);
        edgeWeights.computeIfAbsent(fromNodeId, k -> new HashMap<>()).put(toNodeId, weight);
        invalidateCache();
    }

    @Override
    public void addEdge(String fromNodeId, String toNodeId) {
        addEdge(fromNodeId, toNodeId, 0);
    }

    /**
     * 获取真正的关键路径 —— 使用最长路径算法计算。
     *
     * <p>算法：拓扑排序 + 动态规划。
     * 对 DAG 进行拓扑排序后，按拓扑序计算每个节点的最长路径长度，
     * 最终从终点回溯得到完整的关键路径。</p>
     *
     * @return 关键路径上的节点列表（按执行顺序排列）
     */
    @Override
    public List<TaskNode> getCriticalPath() {
        Map<String, TaskNode> nodeMap = getNodeMap();
        if (nodeMap.isEmpty()) {
            return List.of();
        }

        List<String> topoOrder = getTopologicalOrder();
        if (topoOrder.isEmpty()) {
            return new ArrayList<>(nodeMap.values());
        }

        // 动态规划：计算每个节点的最长到达路径长度
        Map<String, Long> longestTo = new HashMap<>();
        Map<String, String> predecessor = new HashMap<>();

        for (String nodeId : topoOrder) {
            long nodeWeight = nodeWeights.getOrDefault(nodeId, 1000L);
            // 查找入边（依赖关系）中最长的
            long maxIncoming = 0;
            String bestPred = null;

            // 获取该节点的所有直接前驱（它所依赖的节点）
            Map<String, TaskNode> allNodes = getNodeMap();
            TaskNode currentNode = allNodes.get(nodeId);
            if (currentNode != null && currentNode.getDependencies() != null) {
                for (String depId : currentNode.getDependencies()) {
                    if (longestTo.containsKey(depId)) {
                        long depPath = longestTo.get(depId) + nodeWeights.getOrDefault(depId, 1000L);
                        // 加上边权重
                        Map<String, Long> fromEdges = edgeWeights.get(depId);
                        long edgeWeight = (fromEdges != null) ? fromEdges.getOrDefault(nodeId, 0L) : 0L;
                        depPath += edgeWeight;
                        if (depPath > maxIncoming) {
                            maxIncoming = depPath;
                            bestPred = depId;
                        }
                    }
                }
            }

            longestTo.put(nodeId, maxIncoming);
            if (bestPred != null) {
                predecessor.put(nodeId, bestPred);
            }
        }

        // 找到路径最长的终点
        String endNode = null;
        long maxLength = -1;
        for (Map.Entry<String, Long> entry : longestTo.entrySet()) {
            if (entry.getValue() > maxLength) {
                maxLength = entry.getValue();
                endNode = entry.getKey();
            }
        }

        // 从终点回溯构建路径
        List<TaskNode> criticalPath = new ArrayList<>();
        if (endNode != null) {
            Deque<TaskNode> stack = new ArrayDeque<>();
            String current = endNode;
            while (current != null) {
                TaskNode node = nodeMap.get(current);
                if (node != null) {
                    stack.push(node);
                }
                current = predecessor.get(current);
            }
            while (!stack.isEmpty()) {
                criticalPath.add(stack.pop());
            }
        }

        return criticalPath.isEmpty() ? new ArrayList<>(nodeMap.values()) : criticalPath;
    }

    /**
     * 获取按拓扑排序的节点 ID 列表。
     *
     * <p>使用 Kahn 算法进行拓扑排序。如果图中存在环（理论上不应该），
     * 则返回部分排序结果。</p>
     *
     * @return 拓扑排序的节点 ID 列表
     */
    public List<String> getTopologicalOrder() {
        if (cacheValid && cachedTopoOrder != null) {
            return cachedTopoOrder;
        }

        Map<String, TaskNode> nodeMap = getNodeMap();
        if (nodeMap.isEmpty()) {
            cachedTopoOrder = List.of();
            cacheValid = true;
            return cachedTopoOrder;
        }

        // 计算入度
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : nodeMap.keySet()) {
            inDegree.put(id, 0);
        }
        for (TaskNode node : nodeMap.values()) {
            List<String> deps = node.getDependencies();
            if (deps != null) {
                for (String dep : deps) {
                    if (inDegree.containsKey(dep)) {
                        inDegree.merge(node.getNodeId(), 1, Integer::sum);
                    }
                }
            }
        }

        // Kahn 算法
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            order.add(current);

            // 找到所有依赖 current 的节点
            for (TaskNode node : nodeMap.values()) {
                List<String> deps = node.getDependencies();
                if (deps != null && deps.contains(current)) {
                    int newDegree = inDegree.merge(node.getNodeId(), -1, Integer::sum);
                    if (newDegree == 0) {
                        queue.add(node.getNodeId());
                    }
                }
            }
        }

        cachedTopoOrder = order;
        cacheValid = true;
        return order;
    }

    /**
     * 获取进度详情 —— 按状态分组的节点计数。
     *
     * @return 包含各状态计数的进度映射
     */
    public Map<String, Object> getProgressDetail() {
        Map<String, TaskNodeStatus> statusMap = getStatusMap();
        Map<TaskNodeStatus, Long> counts = new HashMap<>();
        for (TaskNodeStatus status : TaskNodeStatus.values()) {
            counts.put(status, 0L);
        }
        for (TaskNodeStatus s : statusMap.values()) {
            counts.merge(s, 1L, Long::sum);
        }

        Map<String, Object> detail = new HashMap<>();
        detail.put("progress", getProgress());
        detail.put("total", getNodeMap().size());
        for (Map.Entry<TaskNodeStatus, Long> entry : counts.entrySet()) {
            detail.put(entry.getKey().name().toLowerCase(), entry.getValue());
        }
        return detail;
    }

    /**
     * 获取 DAG 的并行度（最大宽度）—— 无依赖关系可同时执行的节点数上限。
     *
     * <p>算法：拓扑排序后，逐层计算无依赖节点的数量，取最大值。</p>
     *
     * @return 最大并行度
     */
    public int getMaxParallelism() {
        Map<String, TaskNode> nodeMap = getNodeMap();
        if (nodeMap.isEmpty()) {
            return 0;
        }

        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : nodeMap.keySet()) {
            inDegree.put(id, 0);
        }

        Map<String, List<String>> reverseDep = new HashMap<>();
        for (TaskNode node : nodeMap.values()) {
            reverseDep.putIfAbsent(node.getNodeId(), new ArrayList<>());
            List<String> deps = node.getDependencies();
            if (deps != null) {
                for (String dep : deps) {
                    reverseDep.computeIfAbsent(dep, k -> new ArrayList<>()).add(node.getNodeId());
                    inDegree.merge(node.getNodeId(), 1, Integer::sum);
                }
            }
        }

        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        int maxWidth = 0;
        while (!queue.isEmpty()) {
            int levelSize = queue.size();
            maxWidth = Math.max(maxWidth, levelSize);

            for (int i = 0; i < levelSize; i++) {
                String current = queue.poll();
                List<String> dependents = reverseDep.getOrDefault(current, List.of());
                for (String dependent : dependents) {
                    int newDegree = inDegree.merge(dependent, -1, Integer::sum);
                    if (newDegree == 0) {
                        queue.add(dependent);
                    }
                }
            }
        }

        return maxWidth;
    }

    /**
     * 获取指定节点的所有祖先（包括间接依赖链上的所有节点）。
     *
     * @param nodeId 目标节点 ID
     * @return 祖先节点 ID 集合
     */
    public Set<String> getAncestors(String nodeId) {
        Set<String> ancestors = new HashSet<>();
        Map<String, TaskNode> nodeMap = getNodeMap();
        TaskNode node = nodeMap.get(nodeId);
        if (node == null) {
            return ancestors;
        }

        Deque<String> stack = new ArrayDeque<>();
        if (node.getDependencies() != null) {
            for (String dep : node.getDependencies()) {
                stack.push(dep);
            }
        }

        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (!ancestors.add(current)) continue;

            TaskNode currentDode = nodeMap.get(current);
            if (currentDode != null && currentDode.getDependencies() != null) {
                for (String dep : currentDode.getDependencies()) {
                    if (!ancestors.contains(dep)) {
                        stack.push(dep);
                    }
                }
            }
        }

        return ancestors;
    }

    /**
     * 获取指定节点的所有后代（包括间接依赖该节点的所有节点）。
     *
     * @param nodeId 源节点 ID
     * @return 后代节点 ID 集合
     */
    public Set<String> getDescendants(String nodeId) {
        Set<String> descendants = new HashSet<>();
        Map<String, TaskNode> nodeMap = getNodeMap();
        Map<String, List<String>> reverseDep = new HashMap<>();
        for (TaskNode n : nodeMap.values()) {
            List<String> deps = n.getDependencies();
            if (deps != null) {
                for (String dep : deps) {
                    reverseDep.computeIfAbsent(dep, k -> new ArrayList<>()).add(n.getNodeId());
                }
            }
        }

        Deque<String> queue = new ArrayDeque<>();
        List<String> directChildren = reverseDep.getOrDefault(nodeId, List.of());
        queue.addAll(directChildren);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!descendants.add(current)) continue;

            List<String> children = reverseDep.getOrDefault(current, List.of());
            for (String child : children) {
                if (!descendants.contains(child)) {
                    queue.add(child);
                }
            }
        }

        return descendants;
    }

    /**
     * 提取从指定根节点可达的子图。
     *
     * @param rootId 根节点 ID
     * @return 新的 TaskGraphImpl，包含子图
     */
    public TaskGraphImpl extractSubgraph(String rootId) {
        TaskGraphImpl subgraph = new TaskGraphImpl();
        Map<String, TaskNode> nodeMap = getNodeMap();
        TaskNode root = nodeMap.get(rootId);
        if (root == null) {
            return subgraph;
        }

        // BFS 收集所有可达节点
        Set<String> reachable = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!reachable.add(current)) continue;

            Map<String, List<String>> reverseDep = new HashMap<>();
            for (TaskNode n : nodeMap.values()) {
                List<String> deps = n.getDependencies();
                if (deps != null) {
                    for (String dep : deps) {
                        reverseDep.computeIfAbsent(dep, k -> new ArrayList<>()).add(n.getNodeId());
                    }
                }
            }

            List<String> children = reverseDep.getOrDefault(current, List.of());
            for (String child : children) {
                if (!reachable.contains(child)) {
                    queue.add(child);
                }
            }
        }

        // 重建子图
        for (String id : reachable) {
            TaskNode node = nodeMap.get(id);
            if (node != null) {
                long weight = nodeWeights.getOrDefault(id, node.getTimeoutMs());
                subgraph.addNode(node, weight);
            }
        }
        for (String id : reachable) {
            TaskNode node = nodeMap.get(id);
            if (node != null && node.getDependencies() != null) {
                for (String dep : node.getDependencies()) {
                    if (reachable.contains(dep)) {
                        long edgeWeight = 0;
                        Map<String, Long> fromEdges = edgeWeights.get(dep);
                        if (fromEdges != null) {
                            edgeWeight = fromEdges.getOrDefault(id, 0L);
                        }
                        subgraph.addEdge(dep, id, edgeWeight);
                    }
                }
            }
        }

        return subgraph;
    }

    /**
     * 获取依赖关系摘要 —— 用于调试和日志输出。
     *
     * @return 格式化的依赖关系字符串
     */
    public String getDependencySummary() {
        StringBuilder sb = new StringBuilder();
        Map<String, TaskNode> nodeMap = getNodeMap();

        sb.append("TaskGraph summary: ").append(nodeMap.size()).append(" nodes\n");
        for (TaskNode node : nodeMap.values()) {
            sb.append("  ").append(node.getNodeId())
                    .append(" [").append(node.getType()).append("]");
            List<String> deps = node.getDependencies();
            if (deps != null && !deps.isEmpty()) {
                sb.append(" ← depends on: ").append(deps);
            } else {
                sb.append(" ← ROOT");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 使缓存失效。当图结构发生变化时调用。 */
    private void invalidateCache() {
        cacheValid = false;
        cachedTopoOrder = null;
    }
}
