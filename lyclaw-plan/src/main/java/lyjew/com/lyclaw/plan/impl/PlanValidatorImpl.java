package lyjew.com.lyclaw.plan.impl;

import lyjew.com.lyclaw.task.PlanValidator;
import lyjew.com.lyclaw.task.TaskNode;
import lyjew.com.lyclaw.task.TaskPlan;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计划验证器实现 —— 验证 TaskPlan 的结构完整性和可行性。
 *
 * <p>执行以下验证检查：
 * <ol>
 *   <li><b>空计划检查</b>：计划不能为 null 且必须包含至少一个节点</li>
 *   <li><b>节点 ID 唯一性</b>：所有节点 ID 必须唯一</li>
 *   <li><b>依赖完整性</b>：所有依赖引用必须指向存在的节点</li>
 *   <li><b>无环检查 (DAG)</b>：依赖图中不能存在环路（Kahn 拓扑排序算法）</li>
 *   <li><b>可达性检查</b>：所有节点必须从根节点可达（无孤立节点）</li>
 *   <li><b>连通性检查</b>：图中不能有断开的子图</li>
 *   <li><b>预算检查</b>：估算完成时间不能超过预算（默认 10 分钟）</li>
 *   <li><b>规模检查</b>：节点数不能超过最大限制（默认 50）</li>
 * </ol>
 * </p>
 *
 * <p><b>设计动机</b>：在任务计划生成后、执行前进行验证，可以提前发现结构性问题，
 * 避免执行时的运行时错误。特别是 DAG 环检测使用 Kahn 算法进行拓扑排序，
 * 可以有效防止无限递归或死锁。</p>
 *
 * @since 2.0
 * @author LyClaw Team
 * @see PlanValidator
 * @see TaskPlan
 * @see TaskNode
 */
@Service
public class PlanValidatorImpl implements PlanValidator {

    /** 默认最大节点数 */
    private static final int DEFAULT_MAX_NODES = 50;

    /** 默认时间预算（毫秒，默认 10 分钟） */
    private static final long DEFAULT_TIME_BUDGET_MS = 600_000L;

    /**
     * 验证任务计划的完整性和可行性。
     *
     * <p>按顺序执行所有检查。一旦发现环或根本性结构错误，立即返回失败结果。
     * 非致命错误（如超预算）会累积在错误列表中但不会立即中断。</p>
     *
     * @param plan 要验证的任务计划
     * @return 验证结果，包含是否有效及错误列表
     */
    @Override
    public ValidationResult validate(TaskPlan plan) {
        List<String> errors = new ArrayList<>();

        // 1. 空计划检查
        if (plan == null) {
            return ValidationResult.invalid("TaskPlan is null");
        }

        List<TaskNode> nodes = plan.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            return ValidationResult.invalid("TaskPlan has no nodes — plan must contain at least one node");
        }

        // 2. 节点 ID 唯一性检查
        Set<String> seenIds = new HashSet<>();
        for (TaskNode node : nodes) {
            if (node.getNodeId() == null || node.getNodeId().isBlank()) {
                errors.add("Node has null or blank nodeId — all nodes must have unique IDs");
            } else if (!seenIds.add(node.getNodeId())) {
                errors.add("Duplicate nodeId detected: " + node.getNodeId());
            }
        }
        if (!errors.isEmpty()) {
            return buildResult(errors);
        }

        // 构建索引
        Set<String> allNodeIds = new HashSet<>();
        Map<String, TaskNode> nodeIndex = new HashMap<>();
        for (TaskNode node : nodes) {
            allNodeIds.add(node.getNodeId());
            nodeIndex.put(node.getNodeId(), node);
        }

        // 构建邻接表（用于拓扑排序和环检测）
        Map<String, List<String>> adjacency = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (TaskNode node : nodes) {
            adjacency.putIfAbsent(node.getNodeId(), new ArrayList<>());
            inDegree.putIfAbsent(node.getNodeId(), 0);
        }

        // 3. 依赖完整性检查 + 构建图
        List<String> rootCandidates = new ArrayList<>();
        for (TaskNode node : nodes) {
            List<String> deps = node.getDependencies();
            if (deps == null || deps.isEmpty()) {
                rootCandidates.add(node.getNodeId());
                continue;
            }
            for (String dep : deps) {
                if (dep == null || dep.isBlank()) {
                    errors.add("Node " + node.getNodeId()
                            + " has null/blank dependency reference");
                    continue;
                }
                if (!allNodeIds.contains(dep)) {
                    errors.add("Node " + node.getNodeId()
                            + " depends on non-existent node: " + dep);
                } else {
                    // dep → node 是边方向（dep 完成后 node 才能执行）
                    adjacency.computeIfAbsent(dep, k -> new ArrayList<>()).add(node.getNodeId());
                    inDegree.merge(node.getNodeId(), 1, Integer::sum);
                }
            }
        }
        if (!errors.isEmpty()) {
            return buildResult(errors);
        }

        // 4. 无环检查 (Kahn 拓扑排序)
        if (!isAcyclic(adjacency, inDegree, allNodeIds, errors)) {
            return buildResult(errors);
        }

        // 5. 根节点检查
        if (rootCandidates.isEmpty()) {
            errors.add("No root nodes found — every DAG must have at least one root node (node with no dependencies)");
            return buildResult(errors);
        }

        // 6. 可达性检查（所有节点从根节点可达）
        Set<String> reachable = computeReachable(adjacency, rootCandidates);
        for (String nodeId : allNodeIds) {
            if (!reachable.contains(nodeId)) {
                errors.add("Unreachable node detected: " + nodeId
                        + " — not reachable from any root node");
            }
        }

        // 7. 连通性：BFS 沿双向边遍历
        for (String rootId : rootCandidates) {
            Set<String> fullReachable = computeFullReachable(adjacency, nodeIndex, rootId);
            if (fullReachable.size() < allNodeIds.size()) {
                Set<String> missing = new HashSet<>(allNodeIds);
                missing.removeAll(fullReachable);
                if (!missing.isEmpty()) {
                    errors.add("Disconnected subgraph detected — nodes isolated from root '"
                            + rootId + "': " + missing);
                }
                break;
            }
        }

        // 8. 预算检查
        long estimatedTime = plan.getEstimatedCompletionTime();
        if (estimatedTime > DEFAULT_TIME_BUDGET_MS) {
            errors.add(String.format(
                    "Estimated completion time (%d ms) exceeds budget (%d ms)",
                    estimatedTime, DEFAULT_TIME_BUDGET_MS));
        }

        // 9. 规模检查
        if (nodes.size() > DEFAULT_MAX_NODES) {
            errors.add(String.format(
                    "Plan has %d nodes, exceeding maximum allowed (%d)",
                    nodes.size(), DEFAULT_MAX_NODES));
        }

        return buildResult(errors);
    }

    /**
     * Kahn 算法进行拓扑排序以检测环。
     *
     * @param adjacency  邻接表（出边：dep → dependent nodes）
     * @param inDegree   入度表
     * @param allNodeIds 所有节点 ID 集合
     * @param errors     错误收集列表
     * @return true 表示无环
     */
    private boolean isAcyclic(Map<String, List<String>> adjacency,
                               Map<String, Integer> inDegree,
                               Set<String> allNodeIds,
                               List<String> errors) {
        Deque<String> queue = new ArrayDeque<>();
        Map<String, Integer> inDegreeCopy = new HashMap<>(inDegree);

        for (String nodeId : allNodeIds) {
            if (inDegreeCopy.getOrDefault(nodeId, 0) == 0) {
                queue.add(nodeId);
            }
        }

        int visitedCount = 0;
        while (!queue.isEmpty()) {
            String current = queue.poll();
            visitedCount++;

            List<String> neighbors = adjacency.getOrDefault(current, List.of());
            for (String neighbor : neighbors) {
                int newDegree = inDegreeCopy.merge(neighbor, -1, Integer::sum);
                if (newDegree == 0) {
                    queue.add(neighbor);
                }
            }
        }

        if (visitedCount != allNodeIds.size()) {
            Set<String> cycleNodes = new HashSet<>();
            for (Map.Entry<String, Integer> entry : inDegreeCopy.entrySet()) {
                if (entry.getValue() > 0) {
                    cycleNodes.add(entry.getKey());
                }
            }
            errors.add("Circular dependency detected — plan must be a DAG. "
                    + "Nodes in cycle: " + cycleNodes);
            return false;
        }

        return true;
    }

    /**
     * BFS 计算从根节点集合沿出边可达的所有节点。
     */
    private Set<String> computeReachable(Map<String, List<String>> adjacency,
                                          List<String> roots) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(roots);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) continue;

            List<String> neighbors = adjacency.getOrDefault(current, List.of());
            for (String neighbor : neighbors) {
                if (!visited.contains(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }

        return visited;
    }

    /**
     * 全向 BFS —— 同时沿出边和入边遍历，检测连通分量。
     *
     * <p>出边 = 依赖方向（谁依赖我）；入边 = 我所依赖的节点。
     * 双向遍历可以发现从根节点出发，沿着任意方向能到达的所有节点，
     * 以此检测是否有完全断开的孤立子图。</p>
     */
    private Set<String> computeFullReachable(Map<String, List<String>> adjacency,
                                              Map<String, TaskNode> nodeIndex,
                                              String rootId) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) continue;

            // 前进方向（出边：我依赖的节点 → 我）
            List<String> forward = adjacency.getOrDefault(current, List.of());
            for (String next : forward) {
                if (!visited.contains(next)) {
                    queue.add(next);
                }
            }

            // 后退方向（入边：我所依赖的节点）
            TaskNode node = nodeIndex.get(current);
            if (node != null && node.getDependencies() != null) {
                for (String dep : node.getDependencies()) {
                    if (!visited.contains(dep)) {
                        queue.add(dep);
                    }
                }
            }
        }

        return visited;
    }

    /**
     * 从错误列表构建 ValidationResult。
     *
     * <p>如果有错误，返回 invalid 结果并携带所有错误信息；
     * 如果没有错误，返回 valid 结果。</p>
     */
    private ValidationResult buildResult(List<String> errors) {
        if (errors.isEmpty()) {
            return ValidationResult.valid();
        }
        ValidationResult result = new ValidationResult(false);
        for (String error : errors) {
            result.addError(error);
        }
        return result;
    }
}
