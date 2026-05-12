package lyjew.com.lyclaw.util;

import java.util.HashMap;
import java.util.Map;

/**
 * 并查集数据结构，用于快速合并和查找连通分量。
 *
 * <p>常用于依赖关系分析、任务分组、循环检测等场景。
 */
public class UnionFind<T> {

    private final Map<T, T> parent = new HashMap<>();
    private final Map<T, Integer> rank = new HashMap<>();

    /** 添加一个新元素（自成一集合）。 */
    public void add(T element) {
        parent.putIfAbsent(element, element);
        rank.putIfAbsent(element, 0);
    }

    /** 查找元素所属集合的代表元（带路径压缩）。 */
    public T find(T element) {
        T p = parent.get(element);
        if (p == null) {
            add(element);
            return element;
        }
        if (!p.equals(element)) {
            parent.put(element, find(p));
        }
        return parent.get(element);
    }

    /** 合并两个元素所在的集合（按秩合并）。 */
    public void union(T a, T b) {
        T rootA = find(a);
        T rootB = find(b);
        if (rootA.equals(rootB)) return;
        int rankA = rank.getOrDefault(rootA, 0);
        int rankB = rank.getOrDefault(rootB, 0);
        if (rankA < rankB) {
            parent.put(rootA, rootB);
        } else if (rankA > rankB) {
            parent.put(rootB, rootA);
        } else {
            parent.put(rootB, rootA);
            rank.merge(rootA, 1, Integer::sum);
        }
    }

    /** @return 两个元素是否在同一集合中 */
    public boolean connected(T a, T b) {
        return find(a).equals(find(b));
    }
}
