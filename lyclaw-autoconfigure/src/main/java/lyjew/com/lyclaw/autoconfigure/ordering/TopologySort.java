package lyjew.com.lyclaw.autoconfigure.ordering;

import java.util.*;

/**
 * 拓扑排序工具类。
 *
 * <p>基于Kahn算法实现的有向无环图（DAG）拓扑排序。
 * 通过给定的依赖解析函数确定每个元素的依赖关系，
 * 返回按拓扑顺序排列的列表，确保每个元素排在其所有依赖项之后。</p>
 *
 * <p>算法流程：
 * <ol>
 *   <li>构建邻接表（graph）记录每个节点的后继节点</li>
 *   <li>计算每个节点的入度（inDegree）</li>
 *   <li>将入度为0的节点加入队列</li>
 *   <li>依次弹出节点，将其后继节点入度减1，入度为0则入队</li>
 *   <li>若结果数量不等于输入数量，说明存在循环依赖，抛出异常</li>
 * </ol>
 * </p>
 *
 * <p>该类主要用于编排管道阶段的执行顺序，确保各阶段按照依赖关系正确地串行或并行执行。</p>
 *
 * @param <T> 需要排序的元素类型
 * @author lyjew
 */
public class TopologySort {

    /**
     * 对给定集合进行拓扑排序。
     *
     * <p>使用BFS方式（Kahn算法）遍历DAG图，确保每个元素排在其所有依赖项之后。
     * 当检测到循环依赖时，会输出涉及循环的节点信息并抛出异常。</p>
     *
     * @param <T>                元素类型
     * @param items              待排序的元素集合
     * @param dependencyResolver 依赖解析函数，返回某个元素的所有直接依赖项
     * @return 按拓扑顺序排列的列表
     * @throws IllegalStateException 检测到循环依赖时抛出
     */
    public static <T> List<T> sort(Collection<T> items,
                                    java.util.function.Function<T, Collection<T>> dependencyResolver) {
        // 存储每个节点的入度（有多少节点依赖它之前必须先完成）
        Map<T, Integer> inDegree = new HashMap<>();
        // 邻接表：存储每个节点的后继节点列表
        Map<T, List<T>> graph = new HashMap<>();

        // 第一步：构建图结构和入度表
        for (T item : items) {
            inDegree.putIfAbsent(item, 0);
            for (T dep : dependencyResolver.apply(item)) {
                // dep → item：dep是item的依赖项，所以item的入度+1
                graph.computeIfAbsent(dep, k -> new ArrayList<>()).add(item);
                inDegree.merge(item, 1, Integer::sum);
            }
        }

        // 第二步：将所有入度为0的节点加入队列（无依赖项，可立即执行）
        Queue<T> queue = new LinkedList<>();
        for (Map.Entry<T, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }

        // 第三步：BFS遍历，依次输出节点并更新后继节点的入度
        List<T> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            T current = queue.poll();
            result.add(current);
            // 遍历当前节点的所有后继节点
            for (T neighbor : graph.getOrDefault(current, List.of())) {
                // 后继节点入度减1
                int newDegree = inDegree.merge(neighbor, -1, Integer::sum);
                // 入度变为0时加入队列
                if (newDegree == 0) queue.add(neighbor);
            }
        }

        // 第四步：循环检测——如果结果数量不等于输入数量，说明存在环
        if (result.size() != items.size()) {
            // 输出未被排序的节点（循环依赖的节点）
            Set<T> sorted = new HashSet<>(result);
            items.stream().filter(i -> !sorted.contains(i))
                .forEach(i -> System.err.println("[TopologySort] Cycle involves: " + i));
            throw new IllegalStateException("Circular dependency detected in pipeline stages");
        }

        return result;
    }
}
