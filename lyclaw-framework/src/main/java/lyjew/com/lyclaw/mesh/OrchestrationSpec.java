package lyjew.com.lyclaw.mesh;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排规格 —— 声明式定义一次多 Agent 协作的完整计划。
 *
 * <p>用户通过此 API 描述"要做什么"，而不是"怎么做"。
 * {@link OrchestrationEngine} 负责解析规格并执行。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * // 并行审查
 * OrchestrationResult result = engine.execute(OrchestrationSpec.builder()
 *     .pattern(OrchestrationPattern.FAN_OUT)
 *     .task("Review PR #42")
 *     .capabilities(List.of("code-review", "security-audit"))
 *     .aggregationStrategy("vote")
 *     .timeoutMs(120_000)
 *     .build());
 *
 * // 流水线
 * OrchestrationSpec.builder()
 *     .pattern(OrchestrationPattern.CHAIN)
 *     .agentIds(List.of("designer", "implementer", "reviewer"))
 *     .build();
 * }</pre>
 */
public class OrchestrationSpec {

    private final OrchestrationPattern pattern;
    private final String task;
    private final String payload;
    private final List<String> agentIds;
    private final List<String> requiredCapabilities;
    private final long timeoutMs;
    private final boolean waitForAll;
    private final int maxDebateRounds;
    private final String aggregationStrategy;
    private final Map<String, Object> config;

    private OrchestrationSpec(Builder builder) {
        this.pattern = builder.pattern;
        this.task = builder.task;
        this.payload = builder.payload;
        this.agentIds = builder.agentIds != null ? List.copyOf(builder.agentIds) : List.of();
        this.requiredCapabilities = builder.requiredCapabilities != null
                ? List.copyOf(builder.requiredCapabilities) : List.of();
        this.timeoutMs = builder.timeoutMs > 0 ? builder.timeoutMs : 300_000;
        this.waitForAll = builder.waitForAll;
        this.maxDebateRounds = builder.maxDebateRounds > 0 ? builder.maxDebateRounds : 3;
        this.aggregationStrategy = builder.aggregationStrategy != null
                ? builder.aggregationStrategy : "vote";
        this.config = builder.config != null ? Map.copyOf(builder.config) : Map.of();
    }

    public OrchestrationPattern getPattern() { return pattern; }
    public String getTask() { return task; }
    public String getPayload() { return payload; }
    public List<String> getAgentIds() { return agentIds; }
    public List<String> getRequiredCapabilities() { return requiredCapabilities; }
    public long getTimeoutMs() { return timeoutMs; }
    public boolean isWaitForAll() { return waitForAll; }
    public int getMaxDebateRounds() { return maxDebateRounds; }
    public String getAggregationStrategy() { return aggregationStrategy; }
    public Map<String, Object> getConfig() { return config; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private OrchestrationPattern pattern;
        private String task;
        private String payload;
        private List<String> agentIds;
        private List<String> requiredCapabilities;
        private long timeoutMs;
        private boolean waitForAll = true;
        private int maxDebateRounds;
        private String aggregationStrategy;
        private Map<String, Object> config;

        public Builder pattern(OrchestrationPattern v) { this.pattern = v; return this; }
        public Builder task(String v) { this.task = v; return this; }
        public Builder payload(String v) { this.payload = v; return this; }
        public Builder agentId(String v) {
            if (this.agentIds == null) this.agentIds = new ArrayList<>();
            this.agentIds.add(v);
            return this;
        }
        public Builder agentIds(List<String> v) { this.agentIds = v; return this; }
        public Builder capability(String v) {
            if (this.requiredCapabilities == null) this.requiredCapabilities = new ArrayList<>();
            this.requiredCapabilities.add(v);
            return this;
        }
        public Builder capabilities(List<String> v) { this.requiredCapabilities = v; return this; }
        public Builder timeoutMs(long v) { this.timeoutMs = v; return this; }
        public Builder waitForAll(boolean v) { this.waitForAll = v; return this; }
        public Builder maxDebateRounds(int v) { this.maxDebateRounds = v; return this; }
        public Builder aggregationStrategy(String v) { this.aggregationStrategy = v; return this; }
        public Builder config(String key, Object value) {
            if (this.config == null) this.config = new LinkedHashMap<>();
            this.config.put(key, value);
            return this;
        }
        public Builder config(Map<String, Object> v) { this.config = v; return this; }

        public OrchestrationSpec build() {
            if (pattern == null) throw new IllegalStateException("pattern must not be null");
            return new OrchestrationSpec(this);
        }
    }
}
