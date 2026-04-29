package lyjew.com.lyclaw.skill.impl;

import lyjew.com.lyclaw.skill.SkillGraph;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 技能依赖图实现 —— 使用邻接表存储有向图，支持拓扑排序和循环依赖检测。
 *
 * <p><b>设计动机</b>：技能之间的依赖必须是无环有向图（DAG），否则无法确定执行顺序。
 * SkillGraphImpl 在每次添加/移除依赖后维护图结构，提供拓扑排序和环检测。
 * 如果检测到环，resolveExecutionOrder() 抛出异常阻止执行。</p>
 *
 * <p><b>拓扑排序算法</b>：Kahn 算法（BFS 入度法）。
 * 每次从图中移除入度为 0 的节点加入结果列表，直到所有节点被移除。
 * 如果仍有剩余节点，说明存在环。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see SkillGraph
 */
@Component
public class SkillGraphImpl implements SkillGraph {

    /**
     * 邻接表 —— key 是技能 ID，value 是它依赖的技能 ID 列表。
     *
     * <p>例如：adjacency.get("A") = ["B", "C"] 表示 A 依赖 B 和 C，
     * 执行顺序：B -> C -> A。</p>
     */
    private final ConcurrentHashMap<String, List<String>> adjacency = new ConcurrentHashMap<>();

    /**
     * 反向邻接表 —— key 是技能 ID，value 是依赖它的技能 ID 列表。
     *
     * <p>用于快速查找"谁依赖我"（被依赖查询）。</p>
     */
    private final ConcurrentHashMap<String, List<String>> reverseAdjacency = new ConcurrentHashMap<>();

    /**
     * 添加依赖关系。
     *
     * @param skillId     依赖方（如 "A"）
     * @param dependsOn   被依赖方（如 "B"，A 依赖 B）
     */
    @Override
    public void addDependency(String skillId, String dependsOn) {
        // 更新邻接表
        adjacency.computeIfAbsent(skillId, k -> new ArrayList<>())
                .add(dependsOn);
        // 更新反向邻接表
        reverseAdjacency.computeIfAbsent(dependsOn, k -> new ArrayList<>())
                .add(skillId);
    }

    /**
     * 移除依赖关系。
     *
     * @param skillId     依赖方
     * @param dependsOn   被依赖方
     */
    @Override
    public void removeDependency(String skillId, String dependsOn) {
        // 从邻接表中移除
        adjacency.computeIfPresent(skillId, (k, v) -> {
            v.remove(dependsOn);
            return v.isEmpty() ? null : v;
        });
        // 从反向邻接表中移除
        reverseAdjacency.computeIfPresent(dependsOn, (k, v) -> {
            v.remove(skillId);
            return v.isEmpty() ? null : v;
        });
    }

    /**
     * 获取指定技能依赖的所有技能列表。
     *
     * @param skillId 技能 ID
     * @return 依赖的技能 ID 列表，无依赖时返回空列表
     */
    @Override
    public List<String> getDependencies(String skillId) {
        return adjacency.getOrDefault(skillId, Collections.emptyList());
    }

    /**
     * 获取所有依赖指定技能的技能列表。
     *
     * @param skillId 技能 ID
     * @return 依赖此技能的技能 ID 列表
     */
    @Override
    public List<String> getDependents(String skillId) {
        return reverseAdjacency.getOrDefault(skillId, Collections.emptyList());
    }

    /**
     * 获取拓扑排序后的执行顺序。
     *
     * <p>使用 Kahn 算法：
     * <ol>
     *   <li>统计每个节点的入度</li>
     *   <li>将入度为 0 的节点加入队列</li>
     *   <li>逐一出队，将其邻居的入度减 1</li>
     *   <li>如果新的节点入度变为 0，加入队列</li>
     *   <li>如果结果列表大小不等于节点总数，说明存在环</li>
     * </ol>
     * </p>
     *
     * @return 按依赖顺序排列的技能 ID 列表（依赖的先执行）
     * @throws IllegalStateException 如果存在环
     */
    @Override
    public List<String> getExecutionOrder() {
        // 1. 收集所有节点（邻接表 key + 所有依赖项）
        Set<String> allNodes = new HashSet<>(adjacency.keySet());
        adjacency.values().forEach(allNodes::addAll);

        // 2. 统计入度并初始化队列
        Map<String, Integer> inDegree = new HashMap<>();
        for (String node : allNodes) {
            inDegree.put(node, 0);
        }
        for (List<String> deps : adjacency.values()) {
            for (String dep : deps) {
                inDegree.merge(dep, 1, Integer::sum);
            }
        }

        // 3. 入度为 0 的节点入队
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        // 4. BFS 拓扑排序
        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String node = queue.poll();
            result.add(node);

            // 将当前节点的邻居入度减 1
            List<String> neighbors = reverseAdjacency.get(node);
            if (neighbors != null) {
                for (String neighbor : neighbors) {
                    inDegree.merge(neighbor, -1, Integer::sum);
                    if (inDegree.get(neighbor) == 0) {
                        queue.offer(neighbor);
                    }
                }
            }
        }

        // 5. 检查是否存在环
        if (result.size() != allNodes.size()) {
            throw new IllegalStateException(
                    "循环依赖检测到！剩余节点数: "
                            + (allNodes.size() - result.size()));
        }

        return result;
    }

    /**
     * 判断图中是否存在环。
     *
     * <p>通过尝试拓扑排序，如果结果列表大小小于节点总数则说明有环。</p>
     *
     * @return true 表示存在环
     */
    @Override
    public boolean hasCycle() {
        try {
            getExecutionOrder();
            return false;
        } catch (IllegalStateException e) {
            return true;
        }
    }
}