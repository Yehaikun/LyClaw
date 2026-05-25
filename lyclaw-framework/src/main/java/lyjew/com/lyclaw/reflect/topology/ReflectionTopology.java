package lyjew.com.lyclaw.reflect.topology;

import java.util.*;

/**
 * 拓扑纯数据对象——描述反思架构的图结构。
 * 可序列化为 JSON/YAML，可存储、传输、运行时生成。
 */
public class ReflectionTopology {
    private String topologyId;
    private String name;
    private Map<String, NodeDef> nodes = new LinkedHashMap<>();
    private List<Edge> edges = new ArrayList<>();
    private String entryNodeId;
    private Set<String> exitNodeIds = new LinkedHashSet<>();
    private int maxIterations = 3;
    private int maxRecursionDepth = 3;
    private long defaultTimeoutMs = 30_000L;
    private long maxTokensPerTopology;
    private Map<String, String> metadata = new LinkedHashMap<>();

    public ReflectionTopology() {}

    public String getTopologyId() { return topologyId; }
    public void setTopologyId(String v) { this.topologyId = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public Map<String, NodeDef> getNodes() { return nodes; }
    public void setNodes(Map<String, NodeDef> v) { this.nodes = v; }
    public List<Edge> getEdges() { return edges; }
    public void setEdges(List<Edge> v) { this.edges = v; }
    public String getEntryNodeId() { return entryNodeId; }
    public void setEntryNodeId(String v) { this.entryNodeId = v; }
    public Set<String> getExitNodeIds() { return exitNodeIds; }
    public void setExitNodeIds(Set<String> v) { this.exitNodeIds = v; }
    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int v) { this.maxIterations = v; }
    public int getMaxRecursionDepth() { return maxRecursionDepth; }
    public void setMaxRecursionDepth(int v) { this.maxRecursionDepth = v; }
    public long getDefaultTimeoutMs() { return defaultTimeoutMs; }
    public void setDefaultTimeoutMs(long v) { this.defaultTimeoutMs = v; }
    public long getMaxTokensPerTopology() { return maxTokensPerTopology; }
    public void setMaxTokensPerTopology(long v) { this.maxTokensPerTopology = v; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> v) { this.metadata = v; }

    public NodeDef getNode(String nodeId) { return nodes.get(nodeId); }
    public void addNode(NodeDef node) { nodes.put(node.getNodeId(), node); }
    public void addEdge(Edge edge) { edges.add(edge); }

    /** 根据源节点查找出边 */
    public List<Edge> outgoingEdges(String fromNodeId) {
        List<Edge> result = new ArrayList<>();
        for (Edge e : edges) {
            if (e.getFrom().contains(fromNodeId)) result.add(e);
        }
        return result;
    }

    /** 根据目标节点查找入边 */
    public List<Edge> incomingEdges(String toNodeId) {
        List<Edge> result = new ArrayList<>();
        for (Edge e : edges) {
            if (e.getTo().contains(toNodeId)) result.add(e);
        }
        return result;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String topologyId;
        private String name;
        private Map<String, NodeDef> nodes = new LinkedHashMap<>();
        private List<Edge> edges = new ArrayList<>();
        private String entryNodeId;
        private Set<String> exitNodeIds = new LinkedHashSet<>();
        private int maxIterations = 3;
        private int maxRecursionDepth = 3;
        private long defaultTimeoutMs = 30_000L;
        private long maxTokensPerTopology;
        private Map<String, String> metadata = new LinkedHashMap<>();
        private int actorCounter, evalCounter, reflectCounter, routerCounter, memoryCounter, synthCounter;

        public Builder topologyId(String v) { this.topologyId = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder entryNode(String v) { this.entryNodeId = v; return this; }
        public Builder exitNode(String v) { this.exitNodeIds.add(v); return this; }
        public Builder maxIterations(int v) { this.maxIterations = v; return this; }
        public Builder maxRecursionDepth(int v) { this.maxRecursionDepth = v; return this; }
        public Builder defaultTimeoutMs(long v) { this.defaultTimeoutMs = v; return this; }
        public Builder maxTokensPerTopology(long v) { this.maxTokensPerTopology = v; return this; }
        public Builder metadata(Map<String, String> v) { this.metadata = v; return this; }

        public Builder node(String nodeId, PrimitiveType type, String impl) {
            return node(nodeId, type, impl, null);
        }

        public Builder node(String nodeId, PrimitiveType type, String impl, Map<String, Object> config) {
            NodeDef nd = new NodeDef(nodeId, type, impl);
            if (config != null) nd.setConfig(config);
            nodes.put(nodeId, nd);
            return this;
        }

        public Builder compositeNode(String nodeId, ReflectionTopology sub) {
            NodeDef nd = new NodeDef(nodeId, PrimitiveType.COMPOSITE, "composite");
            nd.setSubTopology(sub);
            nodes.put(nodeId, nd);
            return this;
        }

        public Builder edge(String from, String to, EdgeCondition condition) {
            String id = "e-" + edges.size();
            Edge e = new Edge(id, from, to, condition);
            edges.add(e);
            return this;
        }

        public Builder edge(String from, String to) {
            return edge(from, to, EdgeCondition.ALWAYS);
        }

        public Builder forkEdge(String from, List<String> to) {
            Edge e = new Edge();
            e.setEdgeId("fork-" + edges.size());
            e.setEdgeType(EdgeType.FORK);
            e.getFrom().add(from);
            e.setTo(to);
            e.setCondition(EdgeCondition.ALWAYS);
            edges.add(e);
            return this;
        }

        public Builder joinEdge(List<String> from, String to) {
            Edge e = new Edge();
            e.setEdgeId("join-" + edges.size());
            e.setEdgeType(EdgeType.JOIN);
            e.setFrom(from);
            e.getTo().add(to);
            e.setCondition(EdgeCondition.ALWAYS);
            edges.add(e);
            return this;
        }

        public Builder actor(String impl) { return actor(impl, null); }
        public Builder actor(String impl, Map<String, Object> config) {
            String id = "actor-" + (actorCounter++);
            return node(id, PrimitiveType.ACTOR, impl, config);
        }
        public Builder evaluator(String impl) { return evaluator(impl, null); }
        public Builder evaluator(String impl, Map<String, Object> config) {
            String id = "evaluator-" + (evalCounter++);
            return node(id, PrimitiveType.EVALUATOR, impl, config);
        }
        public Builder reflector(String impl) {
            String id = "reflector-" + (reflectCounter++);
            return node(id, PrimitiveType.REFLECTOR, impl);
        }
        public Builder router(String impl) { return router(impl, null); }
        public Builder router(String impl, Map<String, Object> config) {
            String id = "router-" + (routerCounter++);
            return node(id, PrimitiveType.ROUTER, impl, config);
        }
        public Builder memory(String impl) {
            String id = "memory-" + (memoryCounter++);
            return node(id, PrimitiveType.MEMORY, impl);
        }
        public Builder synthesizer(String impl) {
            String id = "synthesizer-" + (synthCounter++);
            return node(id, PrimitiveType.SYNTHESIZER, impl);
        }

        public ReflectionTopology build() {
            ReflectionTopology t = new ReflectionTopology();
            t.topologyId = topologyId != null ? topologyId : UUID.randomUUID().toString().substring(0, 8);
            t.name = name != null ? name : t.topologyId;
            t.nodes = nodes;
            t.edges = edges;
            t.entryNodeId = entryNodeId;
            t.exitNodeIds = exitNodeIds;
            t.maxIterations = maxIterations;
            t.maxRecursionDepth = maxRecursionDepth;
            t.defaultTimeoutMs = defaultTimeoutMs;
            t.maxTokensPerTopology = maxTokensPerTopology;
            t.metadata = metadata;

            TopologyValidator.validate(t);
            return t;
        }
    }
}
