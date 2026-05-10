package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.skill.SkillGraph;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SkillGraphImpl implements SkillGraph {

    private final ConcurrentHashMap<String, List<String>> adjacency = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> reverseAdjacency = new ConcurrentHashMap<>();

    @Override
    public void addDependency(String fromSkillId, String toSkillId) {
        adjacency.computeIfAbsent(fromSkillId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(toSkillId);
        reverseAdjacency.computeIfAbsent(toSkillId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(fromSkillId);
    }

    @Override
    public void removeDependency(String fromSkillId, String toSkillId) {
        adjacency.computeIfPresent(fromSkillId, (k, v) -> {
            v.remove(toSkillId);
            return v.isEmpty() ? null : v;
        });
        reverseAdjacency.computeIfPresent(toSkillId, (k, v) -> {
            v.remove(fromSkillId);
            return v.isEmpty() ? null : v;
        });
    }

    @Override
    public List<String> getDependencies(String skillId) {
        List<String> deps = adjacency.get(skillId);
        return deps != null ? List.copyOf(deps) : Collections.emptyList();
    }

    @Override
    public List<String> getDependents(String skillId) {
        List<String> deps = reverseAdjacency.get(skillId);
        return deps != null ? List.copyOf(deps) : Collections.emptyList();
    }

    @Override
    public List<String> getExecutionOrder() {
        Set<String> allNodes = new HashSet<>(adjacency.keySet());
        adjacency.values().forEach(allNodes::addAll);
        if (allNodes.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Integer> inDegree = new HashMap<>();
        for (String node : allNodes) {
            inDegree.put(node, 0);
        }
        for (Map.Entry<String, List<String>> entry : adjacency.entrySet()) {
            for (String dep : entry.getValue()) {
                inDegree.merge(dep, 1, Integer::sum);
            }
        }

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

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

        if (result.size() != allNodes.size()) {
            Set<String> remaining = new HashSet<>(allNodes);
            remaining.removeAll(result);
            throw new IllegalStateException(
                    "循环依赖检测到！剩余节点: " + remaining);
        }

        return Collections.unmodifiableList(result);
    }

    @Override
    public boolean hasCycle() {
        try {
            getExecutionOrder();
            return false;
        } catch (IllegalStateException e) {
            return true;
        }
    }

    public Set<String> getAllNodes() {
        Set<String> allNodes = new HashSet<>(adjacency.keySet());
        adjacency.values().forEach(allNodes::addAll);
        return Collections.unmodifiableSet(allNodes);
    }
}
