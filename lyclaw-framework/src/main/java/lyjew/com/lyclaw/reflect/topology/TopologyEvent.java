package lyjew.com.lyclaw.reflect.topology;

import java.util.*;

/**
 * 执行器遍历过程中发出的事件，供上层消费并转换为 SSE。
 *
 * <p>推荐使用 {@link #builder()} 创建事件，但仍保留静态工厂方法
 * 以保证向后兼容，其内部已委托给 Builder。
 */
public class TopologyEvent {
    private TopologyEventType type;
    private String nodeId;
    private String primitiveType;
    private int iteration;
    private long durationMs;
    private Map<String, Object> data = new LinkedHashMap<>();

    public TopologyEvent() {}
    public TopologyEvent(TopologyEventType type) { this.type = type; }

    // ── Builder ──

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private TopologyEventType type;
        private String nodeId;
        private String primitiveType;
        private int iteration;
        private long durationMs;
        private final Map<String, Object> data = new LinkedHashMap<>();

        public Builder type(TopologyEventType t) { this.type = t; return this; }
        public Builder nodeId(String id) { this.nodeId = id; return this; }
        public Builder primitiveType(String pt) { this.primitiveType = pt; return this; }
        public Builder iteration(int i) { this.iteration = i; return this; }
        public Builder durationMs(long d) { this.durationMs = d; return this; }
        public Builder data(String key, Object val) { this.data.put(key, val); return this; }
        public Builder dataAll(Map<String, Object> map) { this.data.putAll(map); return this; }

        public TopologyEvent build() {
            TopologyEvent e = new TopologyEvent(type);
            e.nodeId = nodeId;
            e.primitiveType = primitiveType;
            e.iteration = iteration;
            e.durationMs = durationMs;
            if (!data.isEmpty()) e.data = new LinkedHashMap<>(data);
            return e;
        }
    }

    // ── Factory methods (delegate to Builder) ──

    public static TopologyEvent nodeStart(String nodeId, String ptype, int iter) {
        return builder().type(TopologyEventType.NODE_START)
                .nodeId(nodeId).primitiveType(ptype).iteration(iter).build();
    }

    public static TopologyEvent nodeEnd(String nodeId, long dur, Map<String, Object> d) {
        return builder().type(TopologyEventType.NODE_END)
                .nodeId(nodeId).durationMs(dur).dataAll(d).build();
    }

    public static TopologyEvent message(String chunk) {
        return builder().type(TopologyEventType.MESSAGE).data("text", chunk).build();
    }

    public static TopologyEvent evaluation(Map<String, Object> d) {
        return builder().type(TopologyEventType.EVALUATION).dataAll(d).build();
    }

    public static TopologyEvent reflection(String text) {
        return builder().type(TopologyEventType.REFLECTION).data("text", text).build();
    }

    public static TopologyEvent topologyEnd(Map<String, Object> d) {
        return builder().type(TopologyEventType.TOPOLOGY_END).dataAll(d).build();
    }

    public static TopologyEvent topologyStart(String topologyName, int maxIter) {
        return builder().type(TopologyEventType.TOPOLOGY_START)
                .data("topologyName", topologyName).data("maxIterations", maxIter).build();
    }

    public static TopologyEvent iterationStart(int iter, int maxIter) {
        return builder().type(TopologyEventType.ITERATION_START)
                .iteration(iter).data("maxIterations", maxIter).build();
    }

    public static TopologyEvent actorOutput(String nodeId, String output, int iter, long dur) {
        return builder().type(TopologyEventType.ACTOR_OUTPUT)
                .nodeId(nodeId).iteration(iter).durationMs(dur)
                .data("chars", output != null ? output.length() : 0)
                .data("output", output != null ? output : "").build();
    }

    public static TopologyEvent evaluatorComplete(String nodeId, double score, boolean success,
                                                   List<Map<String, String>> issues, int iter, long dur) {
        Builder b = builder().type(TopologyEventType.EVALUATOR_COMPLETE)
                .nodeId(nodeId).iteration(iter).durationMs(dur)
                .data("score", score).data("success", success);
        if (issues != null && !issues.isEmpty()) b.data("issues", issues);
        return b.build();
    }

    public static TopologyEvent routerDecision(String nodeId, String decision, String reason, int iter) {
        Builder b = builder().type(TopologyEventType.ROUTER_DECISION)
                .nodeId(nodeId).iteration(iter).data("decision", decision);
        if (reason != null) b.data("reason", reason);
        return b.build();
    }

    public static TopologyEvent reflectorComplete(String nodeId, String reflection, int iter, long dur) {
        return builder().type(TopologyEventType.REFLECTOR_COMPLETE)
                .nodeId(nodeId).iteration(iter).durationMs(dur)
                .data("reflection", reflection != null ? reflection : "").build();
    }

    public static TopologyEvent synthesisComplete(String nodeId, String output, int iter, long dur) {
        return builder().type(TopologyEventType.SYNTHESIS_COMPLETE)
                .nodeId(nodeId).iteration(iter).durationMs(dur)
                .data("output", output != null ? output : "").build();
    }

    public static TopologyEvent memoryStore(String nodeId, String summary, int iter) {
        return builder().type(TopologyEventType.MEMORY_STORE)
                .nodeId(nodeId).iteration(iter).data("summary", summary).build();
    }

    public static TopologyEvent forkStart(String nodeId, int branchCount, int iter) {
        return builder().type(TopologyEventType.FORK_START)
                .nodeId(nodeId).iteration(iter).data("branchCount", branchCount).build();
    }

    public static TopologyEvent joinComplete(String nodeId, int branchCount, int iter, long dur) {
        return builder().type(TopologyEventType.JOIN_COMPLETE)
                .nodeId(nodeId).iteration(iter).durationMs(dur)
                .data("branchCount", branchCount).build();
    }

    public static TopologyEvent nodeError(String nodeId, String error, int iter) {
        return builder().type(TopologyEventType.NODE_ERROR)
                .nodeId(nodeId).iteration(iter).data("error", error).build();
    }

    public static TopologyEvent actorChunk(String nodeId, String eventType, String data, int iter) {
        return builder().type(TopologyEventType.ACTOR_CHUNK)
                .nodeId(nodeId).iteration(iter)
                .data("chunkType", eventType).data("text", data != null ? data : "").build();
    }

    public static TopologyEvent actorToolCall(String nodeId, String toolName, String status,
                                               String arguments, String result, Boolean success, int iter) {
        Builder b = builder().type(TopologyEventType.ACTOR_TOOL_CALL)
                .nodeId(nodeId).iteration(iter)
                .data("toolName", toolName).data("status", status);
        if (arguments != null) b.data("arguments", arguments);
        if (result != null) b.data("result", result);
        if (success != null) b.data("success", success);
        return b.build();
    }

    public static TopologyEvent reflectorChunk(String nodeId, String text, int iter) {
        return builder().type(TopologyEventType.REFLECTOR_CHUNK)
                .nodeId(nodeId).iteration(iter).data("text", text != null ? text : "").build();
    }

    public static TopologyEvent evaluatorChunk(String nodeId, String text, int iter) {
        return builder().type(TopologyEventType.EVALUATOR_CHUNK)
                .nodeId(nodeId).iteration(iter).data("text", text != null ? text : "").build();
    }

    public static TopologyEvent routerChunk(String nodeId, String text, int iter) {
        return builder().type(TopologyEventType.ROUTER_CHUNK)
                .nodeId(nodeId).iteration(iter).data("text", text != null ? text : "").build();
    }

    public static TopologyEvent synthesizerChunk(String nodeId, String text, int iter) {
        return builder().type(TopologyEventType.SYNTHESIZER_CHUNK)
                .nodeId(nodeId).iteration(iter).data("text", text != null ? text : "").build();
    }

    /** 序列化为JSONL字段，用于持久化反射事件以便Session重载时重放 */
    public Map<String, Object> toJsonlFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "reflect_event");
        fields.put("eventType", this.type.name());
        if (this.nodeId != null) fields.put("nodeId", this.nodeId);
        fields.put("iteration", this.iteration);
        if (this.durationMs > 0) fields.put("durationMs", this.durationMs);
        if (this.primitiveType != null) fields.put("primitiveType", this.primitiveType);
        if (this.data != null && !this.data.isEmpty()) fields.putAll(this.data);
        fields.put("timestamp", System.currentTimeMillis());
        return fields;
    }

    // ── Getters / Setters ──

    public TopologyEventType getType() { return type; }
    public void setType(TopologyEventType v) { this.type = v; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String v) { this.nodeId = v; }
    public String getPrimitiveType() { return primitiveType; }
    public void setPrimitiveType(String v) { this.primitiveType = v; }
    public int getIteration() { return iteration; }
    public void setIteration(int v) { this.iteration = v; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long v) { this.durationMs = v; }
    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> v) { this.data = v; }
}
