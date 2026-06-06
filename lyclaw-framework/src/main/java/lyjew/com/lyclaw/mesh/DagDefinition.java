package lyjew.com.lyclaw.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DAG 定义 —— 描述有向无环图的节点和边。
 *
 * <p>用于 {@link OrchestrationPattern#DAG} 模式。
 * 用户通过 {@link #builder()} 构建 DAG，通过 {@link OrchestrationSpec#getConfig()}
 * 传入编排引擎。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * DagDefinition dag = DagDefinition.builder()
 *     .node("fetch-diff", "Fetch PR diff", "github-tool")
 *     .node("run-linter", "Run ESLint", "linter-tool")
 *     .node("run-tests", "Run tests", "ci-tool")
 *     .node("summarize", "Summarize findings", "reviewer")
 *     .edge("fetch-diff", "run-linter")     // linter 需要 diff
 *     .edge("fetch-diff", "run-tests")       // tests 需要 diff
 *     .edge("run-linter", "summarize")       // summarize 需要 lint 结果
 *     .edge("run-tests", "summarize")        // summarize 需要 test 结果
 *     .build();
 *
 * OrchestrationSpec spec = OrchestrationSpec.builder()
 *     .pattern(OrchestrationPattern.DAG)
 *     .config("dag", dag.toConfig())
 *     .build();
 * }</pre>
 */
public class DagDefinition {

    private final List<DagNode> nodes;
    private final List<DagEdge> edges;

    private DagDefinition(Builder builder) {
        this.nodes = builder.nodes != null ? List.copyOf(builder.nodes) : List.of();
        this.edges = builder.edges != null ? List.copyOf(builder.edges) : List.of();
    }

    public List<DagNode> getNodes() { return nodes; }
    public List<DagEdge> getEdges() { return edges; }

    /** 获取指定节点的前置依赖 ID 列表 */
    public List<String> getDependencies(String nodeId) {
        return edges.stream()
                .filter(e -> e.getTo().equals(nodeId))
                .map(DagEdge::getFrom)
                .toList();
    }

    /** 获取根节点（无前置依赖） */
    public List<DagNode> getRootNodes() {
        return nodes.stream()
                .filter(n -> edges.stream().noneMatch(e -> e.getTo().equals(n.getId())))
                .toList();
    }

    /** 获取拓扑排序 */
    public List<String> topologicalSort() {
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> adjacency = new LinkedHashMap<>();

        for (DagNode node : nodes) {
            inDegree.put(node.getId(), 0);
            adjacency.put(node.getId(), new ArrayList<>());
        }
        for (DagEdge edge : edges) {
            inDegree.merge(edge.getTo(), 1, Integer::sum);
            adjacency.get(edge.getFrom()).add(edge.getTo());
        }

        List<String> sorted = new ArrayList<>();
        List<String> queue = new ArrayList<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }

        while (!queue.isEmpty()) {
            String node = queue.remove(0);
            sorted.add(node);
            for (String neighbor : adjacency.getOrDefault(node, List.of())) {
                inDegree.merge(neighbor, -1, Integer::sum);
                if (inDegree.get(neighbor) == 0) {
                    queue.add(neighbor);
                }
            }
        }

        return sorted;
    }

    /** 序列化为 config Map（用于 OrchestrationSpec） */
    public Map<String, Object> toConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("dagNodes", nodes.stream().map(n -> Map.of(
                "id", n.getId(), "agentId", n.getAgentId(),
                "task", n.getTask() != null ? n.getTask() : ""
        )).toList());
        config.put("dagEdges", edges.stream()
                .map(e -> Map.of("from", e.getFrom(), "to", e.getTo()))
                .toList());
        return config;
    }

    /** 从 config Map 反序列化 */
    @SuppressWarnings("unchecked")
    public static DagDefinition fromConfig(Map<String, Object> config) {
        if (config == null) return new DagDefinition(new Builder());
        Builder builder = builder();
        Object nodesObj = config.get("dagNodes");
        if (nodesObj instanceof List<?> nodeList) {
            for (Object n : nodeList) {
                if (n instanceof Map<?, ?> m) {
                    builder.node(
                            str(m.get("id")),
                            str(m.get("task")),
                            str(m.get("agentId"))
                    );
                }
            }
        }
        Object edgesObj = config.get("dagEdges");
        if (edgesObj instanceof List<?> edgeList) {
            for (Object e : edgeList) {
                if (e instanceof Map<?, ?> m) {
                    builder.edge(str(m.get("from")), str(m.get("to")));
                }
            }
        }
        return builder.build();
    }

    private static String str(Object v) { return v != null ? v.toString() : ""; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final List<DagNode> nodes = new ArrayList<>();
        private final List<DagEdge> edges = new ArrayList<>();

        public Builder node(String id, String task, String agentId) {
            nodes.add(new DagNode(id, task, agentId));
            return this;
        }

        public Builder edge(String from, String to) {
            edges.add(new DagEdge(from, to));
            return this;
        }

        public DagDefinition build() { return new DagDefinition(this); }
    }

    /** DAG 节点 */
    public static class DagNode {
        private final String id;
        private final String task;
        private final String agentId;

        public DagNode(String id, String task, String agentId) {
            this.id = id;
            this.task = task;
            this.agentId = agentId;
        }

        public String getId() { return id; }
        public String getTask() { return task; }
        public String getAgentId() { return agentId; }
    }

    /** DAG 边 */
    public static class DagEdge {
        private final String from;
        private final String to;

        public DagEdge(String from, String to) {
            this.from = from;
            this.to = to;
        }

        public String getFrom() { return from; }
        public String getTo() { return to; }
    }
}
