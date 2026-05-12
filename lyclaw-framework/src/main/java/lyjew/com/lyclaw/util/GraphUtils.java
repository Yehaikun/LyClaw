package lyjew.com.lyclaw.util;

import java.util.*;

/**
 * 图算法工具类。
 */
public final class GraphUtils {

    private GraphUtils() {}

    /**
     * Kahn 算法拓扑排序，返回节点的拓扑序。
     *
     * @param nodes      所有节点 ID 列表
     * @param edges      有向边列表，每个元素为 [from, to]
     * @return 拓扑排序后的节点 ID 列表；若存在环则返回空列表
     */
    public static List<String> topologicalSort(Set<String> nodes, List<String[]> edges) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();
        for (String node : nodes) {
            inDegree.put(node, 0);
            adj.put(node, new ArrayList<>());
        }
        for (String[] edge : edges) {
            if (!nodes.contains(edge[0]) || !nodes.contains(edge[1])) continue;
            adj.get(edge[0]).add(edge[1]);
            inDegree.merge(edge[1], 1, Integer::sum);
        }

        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) queue.offer(e.getKey());
        }

        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            result.add(cur);
            for (String neighbor : adj.get(cur)) {
                int deg = inDegree.merge(neighbor, -1, Integer::sum);
                if (deg == 0) queue.offer(neighbor);
            }
        }
        return result.size() == nodes.size() ? result : Collections.emptyList();
    }
}
