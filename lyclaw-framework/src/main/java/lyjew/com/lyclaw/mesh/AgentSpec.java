package lyjew.com.lyclaw.mesh;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lyjew.com.lyclaw.model.ToolDefinition;

/**
 * Agent 创建蓝图。
 *
 * <p>ephemeral=true 的 Agent 是运行时动态创建的临时 Agent，
 * 执行完任务后自动销毁，不需要用户手动清理。</p>
 */
public class AgentSpec {

    private final String agentId;
    private final String name;
    private final String description;
    private final List<String> capabilities;
    private final AgentRef.AgentType type;
    private final String model;
    private final String provider;
    private final String systemPrompt;
    private final List<ToolDefinition> tools;
    private final boolean ephemeral;
    private final long ttlMs;
    private final String parentId;
    private final List<String> allowAgents;
    private final SupervisionStrategy supervisionStrategy;
    private final int maxRetries;
    private final Map<String, Object> config;

    private AgentSpec(Builder builder) {
        this.agentId = builder.agentId;
        this.name = builder.name;
        this.description = builder.description;
        this.capabilities = builder.capabilities != null ? List.copyOf(builder.capabilities) : List.of();
        this.type = builder.type != null ? builder.type : AgentRef.AgentType.LLM;
        this.model = builder.model;
        this.provider = builder.provider;
        this.systemPrompt = builder.systemPrompt;
        this.tools = builder.tools != null ? List.copyOf(builder.tools) : List.of();
        this.ephemeral = builder.ephemeral;
        this.ttlMs = builder.ttlMs;
        this.parentId = builder.parentId;
        this.allowAgents = builder.allowAgents != null ? List.copyOf(builder.allowAgents) : List.of();
        this.supervisionStrategy = builder.supervisionStrategy;
        this.maxRetries = builder.maxRetries;
        this.config = builder.config != null ? Map.copyOf(builder.config) : Map.of();
    }

    public String getAgentId() { return agentId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public List<String> getCapabilities() { return capabilities; }
    public AgentRef.AgentType getType() { return type; }
    public String getModel() { return model; }
    public String getProvider() { return provider; }
    public String getSystemPrompt() { return systemPrompt; }
    public List<ToolDefinition> getTools() { return tools; }
    public boolean isEphemeral() { return ephemeral; }
    public long getTtlMs() { return ttlMs; }
    public String getParentId() { return parentId; }
    public List<String> getAllowAgents() { return allowAgents; }
    public SupervisionStrategy getSupervisionStrategy() { return supervisionStrategy; }
    public int getMaxRetries() { return maxRetries; }
    public Map<String, Object> getConfig() { return config; }

    public AgentRef toRef() {
        return new AgentRef(agentId, type, Set.copyOf(capabilities));
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String agentId;
        private String name;
        private String description;
        private List<String> capabilities;
        private AgentRef.AgentType type;
        private String model;
        private String provider;
        private String systemPrompt;
        private List<ToolDefinition> tools;
        private boolean ephemeral;
        private long ttlMs;
        private String parentId;
        private List<String> allowAgents;
        private SupervisionStrategy supervisionStrategy;
        private int maxRetries;
        private Map<String, Object> config;

        public Builder agentId(String v) { this.agentId = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder capability(String v) {
            if (this.capabilities == null) this.capabilities = new ArrayList<>();
            this.capabilities.add(v); return this;
        }
        public Builder capabilities(List<String> v) { this.capabilities = v; return this; }
        public Builder type(AgentRef.AgentType v) { this.type = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder provider(String v) { this.provider = v; return this; }
        public Builder systemPrompt(String v) { this.systemPrompt = v; return this; }
        public Builder tool(ToolDefinition v) {
            if (this.tools == null) this.tools = new ArrayList<>();
            this.tools.add(v); return this;
        }
        public Builder tools(List<ToolDefinition> v) { this.tools = v; return this; }
        public Builder ephemeral(boolean v) { this.ephemeral = v; return this; }
        public Builder ttlMs(long v) { this.ttlMs = v; return this; }
        public Builder parentId(String v) { this.parentId = v; return this; }
        public Builder allowAgents(List<String> v) { this.allowAgents = v; return this; }
        public Builder supervisionStrategy(SupervisionStrategy v) { this.supervisionStrategy = v; return this; }
        public Builder maxRetries(int v) { this.maxRetries = v; return this; }
        public Builder config(String key, Object value) {
            if (this.config == null) this.config = new LinkedHashMap<>();
            this.config.put(key, value); return this;
        }
        public Builder config(Map<String, Object> v) { this.config = v; return this; }
        public AgentSpec build() {
            if (agentId == null || agentId.isBlank())
                throw new IllegalStateException("agentId must not be empty");
            return new AgentSpec(this);
        }
    }
}
