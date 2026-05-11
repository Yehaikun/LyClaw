package lyjew.com.lyclaw.autoconfigure.ordering;

import java.util.*;

public class TopologySort {

    public static <T> List<T> sort(Collection<T> items,
                                    java.util.function.Function<T, Collection<T>> dependencyResolver) {
        Map<T, Integer> inDegree = new HashMap<>();
        Map<T, List<T>> graph = new HashMap<>();

        for (T item : items) {
            inDegree.putIfAbsent(item, 0);
            for (T dep : dependencyResolver.apply(item)) {
                graph.computeIfAbsent(dep, k -> new ArrayList<>()).add(item);
                inDegree.merge(item, 1, Integer::sum);
            }
        }

        Queue<T> queue = new LinkedList<>();
        for (Map.Entry<T, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }

        List<T> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            T current = queue.poll();
            result.add(current);
            for (T neighbor : graph.getOrDefault(current, List.of())) {
                int newDegree = inDegree.merge(neighbor, -1, Integer::sum);
                if (newDegree == 0) queue.add(neighbor);
            }
        }

        if (result.size() != items.size()) {
            Set<T> sorted = new HashSet<>(result);
            items.stream().filter(i -> !sorted.contains(i))
                .forEach(i -> System.err.println("[TopologySort] Cycle involves: " + i));
            throw new IllegalStateException("Circular dependency detected in pipeline stages");
        }

        return result;
    }
}
