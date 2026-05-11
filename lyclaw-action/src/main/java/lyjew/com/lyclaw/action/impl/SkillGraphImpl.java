package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.skill.SkillGraph;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能依赖图实现，管理技能之间的依赖关系并使用拓扑排序计算执行顺序。
 *
 * <p>内部维护两个邻接表：
 * <ul>
 *   <li>{@code adjacency}: forward -> [toSkillId...] 正向依赖（from 依赖 to）</li>
 *   <li>{@code reverseAdjacency}: to -> [from...] 反向依赖（被谁依赖）</li>
 * </ul>
 * 基于 Kahn 算法进行拓扑排序，若存在循环依赖则抛出异常。</p>
 */
@Component
public class SkillGraphImpl implements SkillGraph {

    /** 正向邻接表：节点 -> 其依赖的节点列表 */
    private final ConcurrentHashMap<String, List<String>> adjacency = new ConcurrentHashMap<>();
    /** 反向邻接表：节点 -> 依赖它的节点列表 */
    private final ConcurrentHashMap<String, List<String>> reverseAdjacency = new ConcurrentHashMap<>();

    /**
     * 添加依赖关系：fromSkillId 依赖 toSkillId。
     *
     * @param fromSkillId 依赖方技能 ID
     * @param toSkillId   被依赖方技能 ID
     */
    @Override
    public void addDependency(String fromSkillId, String toSkillId) {
        adjacency.computeIfAbsent(fromSkillId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(toSkillId);
        reverseAdjacency.computeIfAbsent(toSkillId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(fromSkillId);
    }

    /**
     * 移除依赖关系。
     *
     * @param fromSkillId 依赖方
     * @param toSkillId   被依赖方
     */
    @Override
    public void removeDependency(String fromSkillId, String toSkillId) {
        adjacency.computeIfPresent(fromSkillId, (k, v) -> {
            v.remove(toSkillId);
            return v.isEmpty() ? null : v; // 列表为空时移除节点
        });
        reverseAdjacency.computeIfPresent(toSkillId, (k, v) -> {
            v.remove(fromSkillId);
            return v.isEmpty() ? null : v;
        });
    }

    /**
     * 获取指定技能的所有依赖（即该技能依赖哪些其他技能）。
     *
     * @return 依赖的技能 ID 列表（不可变）
     */
    @Override
    public List<String> getDependencies(String skillId) {
        List<String> deps = adjacency.get(skillId);
        return deps != null ? List.copyOf(deps) : Collections.emptyList();
    }

    /**
     * 获取依赖指定技能的所有技能（即哪些技能依赖它）。
     *
     * @return 反向依赖的技能 ID 列表（不可变）
     */
    @Override
    public List<String> getDependents(String skillId) {
        List<String> deps = reverseAdjacency.get(skillId);
        return deps != null ? List.copyOf(deps) : Collections.emptyList();
    }

    /**
     * 使用 Kahn 算法进行拓扑排序，返回正确的执行顺序。
     *
     * <p>步骤：
     * <ol>
     *   <li>计算所有节点的入度</li>
     *   <li>将入度为 0 的节点入队</li>
     *   <li>依次出队，减少其后继节点的入度，入度为 0 时入队</li>
     *   <li>若结果数不等于总节点数，说明存在循环依赖</li>
     * </ol>
     * </p>
     *
     * @return 拓扑排序后的执行顺序列表
     * @throws IllegalStateException 当检测到循环依赖
     */
    @Override
    public List<String> getExecutionOrder() {
        // 收集所有节点
        Set<String> allNodes = new HashSet<>(adjacency.keySet());
        adjacency.values().forEach(allNodes::addAll);
        if (allNodes.isEmpty()) {
            return Collections.emptyList();
        }

        // 计算每个节点的入度
        Map<String, Integer> inDegree = new HashMap<>();
        for (String node : allNodes) {
            inDegree.put(node, 0);
        }
        for (Map.Entry<String, List<String>> entry : adjacency.entrySet()) {
            for (String dep : entry.getValue()) {
                inDegree.merge(dep, 1, Integer::sum);
            }
        }

        // 入度为 0 的节点入队
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        // BFS 拓扑排序
        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String node = queue.poll();
            result.add(node);
            List<String> dependents = reverseAdjacency.get(node);
            if (dependents != null) {
                for (String dependent : dependents) {
                    int newDegree = inDegree.merge(dependent, -1, Integer::sum);
                    if (newDegree == 0) {
                        queue.offer(dependent);
                    }
                }
            }
        }

        // 循环依赖检测
        if (result.size() != allNodes.size()) {
            Set<String> remaining = new HashSet<>(allNodes);
            remaining.removeAll(result);
            throw new IllegalStateException(
                    "循环依赖检测到！剩余节点: " + remaining);
        }

        return Collections.unmodifiableList(result);
    }

    /**
     * 检测依赖图中是否存在循环依赖。
     *
     * @return true 表示存在循环依赖
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

    /** @return 图中所有节点的不可变集合 */
    public Set<String> getAllNodes() {
        Set<String> allNodes = new HashSet<>(adjacency.keySet());
        adjacency.values().forEach(allNodes::addAll);
        return Collections.unmodifiableSet(allNodes);
    }
}
