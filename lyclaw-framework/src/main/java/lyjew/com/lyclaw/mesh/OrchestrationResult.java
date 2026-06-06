package lyjew.com.lyclaw.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排结果 —— 一次多 Agent 协作的最终输出。
 *
 * <p>包含聚合后的文本结果、每个参与 Agent 的单独结果、执行元数据等。</p>
 */
public class OrchestrationResult {

    private final boolean success;
    private final String result;
    private final String error;
    private final OrchestrationPattern pattern;
    private final List<AgentResult> agentResults;
    private final long durationMs;
    private final Map<String, Object> metadata;

    private OrchestrationResult(Builder builder) {
        this.success = builder.success;
        this.result = builder.result;
        this.error = builder.error;
        this.pattern = builder.pattern;
        this.agentResults = builder.agentResults != null
                ? List.copyOf(builder.agentResults) : List.of();
        this.durationMs = builder.durationMs;
        this.metadata = builder.metadata != null ? Map.copyOf(builder.metadata) : Map.of();
    }

    public boolean isSuccess() { return success; }
    public String getResult() { return result; }
    public String getError() { return error; }
    public OrchestrationPattern getPattern() { return pattern; }
    public List<AgentResult> getAgentResults() { return agentResults; }
    public long getDurationMs() { return durationMs; }
    public Map<String, Object> getMetadata() { return metadata; }

    /** 获取某个 Agent 的单独结果 */
    public AgentResult getAgentResult(String agentId) {
        return agentResults.stream()
                .filter(r -> agentId.equals(r.getAgentId()))
                .findFirst().orElse(null);
    }

    /** 获取成功结果数 */
    public int successCount() {
        return (int) agentResults.stream().filter(AgentResult::isSuccess).count();
    }

    /** 获取失败结果数 */
    public int failureCount() {
        return (int) agentResults.stream().filter(r -> !r.isSuccess()).count();
    }

    public static Builder builder() { return new Builder(); }

    public static OrchestrationResult success(String result, OrchestrationPattern pattern,
                                               List<AgentResult> agentResults, long durationMs) {
        return builder().success(true).result(result).pattern(pattern)
                .agentResults(agentResults).durationMs(durationMs).build();
    }

    public static OrchestrationResult failure(String error, OrchestrationPattern pattern,
                                               List<AgentResult> agentResults) {
        return builder().success(false).error(error).pattern(pattern)
                .agentResults(agentResults).build();
    }

    /**
     * 单个 Agent 的执行结果。
     */
    public static class AgentResult {
        private final String agentId;
        private final boolean success;
        private final String result;
        private final String error;
        private final long durationMs;
        private final Map<String, Object> metadata;

        public AgentResult(String agentId, boolean success, String result,
                           String error, long durationMs, Map<String, Object> metadata) {
            this.agentId = agentId;
            this.success = success;
            this.result = result;
            this.error = error;
            this.durationMs = durationMs;
            this.metadata = metadata;
        }

        public String getAgentId() { return agentId; }
        public boolean isSuccess() { return success; }
        public String getResult() { return result; }
        public String getError() { return error; }
        public long getDurationMs() { return durationMs; }
        public Map<String, Object> getMetadata() { return metadata; }
    }

    public static class Builder {
        private boolean success;
        private String result;
        private String error;
        private OrchestrationPattern pattern;
        private List<AgentResult> agentResults;
        private long durationMs;
        private Map<String, Object> metadata;

        public Builder success(boolean v) { this.success = v; return this; }
        public Builder result(String v) { this.result = v; return this; }
        public Builder error(String v) { this.error = v; return this; }
        public Builder pattern(OrchestrationPattern v) { this.pattern = v; return this; }
        public Builder agentResults(List<AgentResult> v) { this.agentResults = v; return this; }
        public Builder agentResult(AgentResult v) {
            if (this.agentResults == null) this.agentResults = new ArrayList<>();
            this.agentResults.add(v);
            return this;
        }
        public Builder durationMs(long v) { this.durationMs = v; return this; }
        public Builder metadata(Map<String, Object> v) { this.metadata = v; return this; }
        public OrchestrationResult build() { return new OrchestrationResult(this); }
    }
}
