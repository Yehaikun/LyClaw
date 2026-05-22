package lyjew.com.lyclaw.config;

import java.util.*;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.annotation.Extension;

/**
 * 完全解析的 Agent 配置 —— 3层深度合并的不可变输出。
 * 解析优先级: @Agent 注解 > lyclaw.agent.defaults.* > AgentSystemDefaults
 */
public class ResolvedAgentConfig {

    private final String agentId;
    private final String agentName;
    private final String description;
    private final String version;
    private final boolean defaultAgent;
    private final String workspaceDir;
    private final String agentDir;
    private final String systemPromptOverride;
    private final String model;
    private final String provider;
    private final List<String> fallbacks;
    private final String thinkingDefault;
    private final String verboseDefault;
    private final String reasoningDefault;
    private final boolean fastModeDefault;
    private final int contextTokens;
    private final String contextInjection;
    private final int bootstrapMaxChars;
    private final int bootstrapTotalMaxChars;
    private final List<String> skills;
    private final String delegationMode;
    private final List<String> allowAgents;
    private final int maxSpawnDepth;
    private final int maxChildrenPerAgent;
    private final String sandbox;
    private final Map<String, String> extensions;

    private ResolvedAgentConfig(Builder builder) {
        this.agentId = builder.agentId;
        this.agentName = builder.agentName;
        this.description = builder.description;
        this.version = builder.version;
        this.defaultAgent = builder.defaultAgent;
        this.workspaceDir = builder.workspaceDir;
        this.agentDir = builder.agentDir;
        this.systemPromptOverride = builder.systemPromptOverride;
        this.model = builder.model;
        this.provider = builder.provider;
        this.fallbacks = Collections.unmodifiableList(builder.fallbacks);
        this.thinkingDefault = builder.thinkingDefault;
        this.verboseDefault = builder.verboseDefault;
        this.reasoningDefault = builder.reasoningDefault;
        this.fastModeDefault = builder.fastModeDefault;
        this.contextTokens = builder.contextTokens;
        this.contextInjection = builder.contextInjection;
        this.bootstrapMaxChars = builder.bootstrapMaxChars;
        this.bootstrapTotalMaxChars = builder.bootstrapTotalMaxChars;
        this.skills = Collections.unmodifiableList(builder.skills);
        this.delegationMode = builder.delegationMode;
        this.allowAgents = Collections.unmodifiableList(builder.allowAgents);
        this.maxSpawnDepth = builder.maxSpawnDepth;
        this.maxChildrenPerAgent = builder.maxChildrenPerAgent;
        this.sandbox = builder.sandbox;
        this.extensions = Collections.unmodifiableMap(builder.extensions);
    }

    // ── Getters ──────────────────────────────────────────────

    public String getAgentId() { return agentId; }
    public String getAgentName() { return agentName; }
    public String getDescription() { return description; }
    public String getVersion() { return version; }
    public boolean isDefaultAgent() { return defaultAgent; }
    public String getWorkspaceDir() { return workspaceDir; }
    public String getAgentDir() { return agentDir; }
    public String getSystemPromptOverride() { return systemPromptOverride; }
    public String getModel() { return model; }
    public String getProvider() { return provider; }
    public List<String> getFallbacks() { return fallbacks; }
    public String getThinkingDefault() { return thinkingDefault; }
    public String getVerboseDefault() { return verboseDefault; }
    public String getReasoningDefault() { return reasoningDefault; }
    public boolean isFastModeDefault() { return fastModeDefault; }
    public int getContextTokens() { return contextTokens; }
    public String getContextInjection() { return contextInjection; }
    public int getBootstrapMaxChars() { return bootstrapMaxChars; }
    public int getBootstrapTotalMaxChars() { return bootstrapTotalMaxChars; }
    public List<String> getSkills() { return skills; }
    public String getDelegationMode() { return delegationMode; }
    public List<String> getAllowAgents() { return allowAgents; }
    public int getMaxSpawnDepth() { return maxSpawnDepth; }
    public int getMaxChildrenPerAgent() { return maxChildrenPerAgent; }
    public String getSandbox() { return sandbox; }
    public Map<String, String> getExtensions() { return extensions; }

    public String getExtension(String key) { return extensions.get(key); }
    public String getExtension(String key, String defaultValue) { return extensions.getOrDefault(key, defaultValue); }

    public static Builder builder() { return new Builder(); }

    /**
     * 从 @Agent 注解直接构建配置，无 YAML 层。
     * 优先级: @Agent 注解 > AgentSystemDefaults 系统默认值。
     * 用于非Spring环境（configResolver 不可用时）的降级路径。
     */
    public static ResolvedAgentConfig fromAnnotation(Agent ann) {
        if (ann == null) return builder().build();
        String agentId = !ann.id().isEmpty() ? ann.id() : decapitalize(ann.name());
        return builder()
            .agentId(agentId)
            .agentName(!ann.name().isEmpty() ? ann.name() : agentId)
            .description(ann.description())
            .version(!ann.version().isEmpty() ? ann.version() : "1.0.0")
            .defaultAgent(ann.defaultAgent())
            .workspaceDir(ann.workspace())
            .agentDir(!ann.agentDir().isEmpty() ? ann.agentDir() : agentId)
            .systemPromptOverride(!ann.systemPromptOverride().isEmpty() ? ann.systemPromptOverride() : null)
            .model(!ann.model().isEmpty() ? ann.model() : AgentSystemDefaults.MODEL)
            .provider(!ann.provider().isEmpty() ? ann.provider() : AgentSystemDefaults.PROVIDER)
            .fallbacks(List.of(ann.fallbacks()))
            .thinkingDefault(!ann.thinkingDefault().isEmpty() ? ann.thinkingDefault() : AgentSystemDefaults.THINKING_DEFAULT)
            .verboseDefault(!ann.verboseDefault().isEmpty() ? ann.verboseDefault() : AgentSystemDefaults.VERBOSE_DEFAULT)
            .reasoningDefault(!ann.reasoningDefault().isEmpty() ? ann.reasoningDefault() : AgentSystemDefaults.REASONING_DEFAULT)
            .fastModeDefault(ann.fastModeDefault())
            .contextTokens(ann.contextTokens() != 0 ? ann.contextTokens() : AgentSystemDefaults.CONTEXT_TOKENS)
            .contextInjection(!ann.contextInjection().isEmpty() ? ann.contextInjection() : AgentSystemDefaults.CONTEXT_INJECTION)
            .bootstrapMaxChars(ann.bootstrapMaxChars() != 0 ? ann.bootstrapMaxChars() : AgentSystemDefaults.BOOTSTRAP_MAX_CHARS)
            .bootstrapTotalMaxChars(ann.bootstrapTotalMaxChars() != 0 ? ann.bootstrapTotalMaxChars() : AgentSystemDefaults.BOOTSTRAP_TOTAL_MAX_CHARS)
            .skills(List.of(ann.skills()))
            .delegationMode(!ann.delegationMode().isEmpty() ? ann.delegationMode() : AgentSystemDefaults.DELEGATION_MODE)
            .allowAgents(List.of(ann.allowAgents()))
            .maxSpawnDepth(ann.maxSpawnDepth() != 0 ? ann.maxSpawnDepth() : AgentSystemDefaults.MAX_SPAWN_DEPTH)
            .maxChildrenPerAgent(ann.maxChildrenPerAgent() != 0 ? ann.maxChildrenPerAgent() : AgentSystemDefaults.MAX_CHILDREN)
            .sandbox(!ann.sandbox().isEmpty() ? ann.sandbox() : AgentSystemDefaults.SANDBOX)
            .extensions(toExtensionMap(ann.extensions()))
            .build();
    }

    private static Map<String, String> toExtensionMap(Extension[] extensions) {
        if (extensions == null || extensions.length == 0) return Collections.emptyMap();
        Map<String, String> map = new LinkedHashMap<>();
        for (Extension ext : extensions) {
            if (ext.key() != null && !ext.key().isEmpty()) {
                map.put(ext.key(), ext.value());
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.length() == 1) return s.toLowerCase();
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    public static class Builder {
        private String agentId = "";
        private String agentName = "";
        private String description = "";
        private String version = "1.0.0";
        private boolean defaultAgent;
        private String workspaceDir = "";
        private String agentDir = "";
        private String systemPromptOverride;
        private String model = AgentSystemDefaults.MODEL;
        private String provider = AgentSystemDefaults.PROVIDER;
        private List<String> fallbacks = new ArrayList<>();
        private String thinkingDefault = AgentSystemDefaults.THINKING_DEFAULT;
        private String verboseDefault = AgentSystemDefaults.VERBOSE_DEFAULT;
        private String reasoningDefault = AgentSystemDefaults.REASONING_DEFAULT;
        private boolean fastModeDefault = AgentSystemDefaults.FAST_MODE;
        private int contextTokens = AgentSystemDefaults.CONTEXT_TOKENS;
        private String contextInjection = AgentSystemDefaults.CONTEXT_INJECTION;
        private int bootstrapMaxChars = AgentSystemDefaults.BOOTSTRAP_MAX_CHARS;
        private int bootstrapTotalMaxChars = AgentSystemDefaults.BOOTSTRAP_TOTAL_MAX_CHARS;
        private List<String> skills = new ArrayList<>();
        private String delegationMode = AgentSystemDefaults.DELEGATION_MODE;
        private List<String> allowAgents = new ArrayList<>();
        private int maxSpawnDepth = AgentSystemDefaults.MAX_SPAWN_DEPTH;
        private int maxChildrenPerAgent = AgentSystemDefaults.MAX_CHILDREN;
        private String sandbox = AgentSystemDefaults.SANDBOX;
        private Map<String, String> extensions = new LinkedHashMap<>();

        public Builder agentId(String v) { this.agentId = v; return this; }
        public Builder agentName(String v) { this.agentName = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder version(String v) { this.version = v; return this; }
        public Builder defaultAgent(boolean v) { this.defaultAgent = v; return this; }
        public Builder workspaceDir(String v) { this.workspaceDir = v; return this; }
        public Builder agentDir(String v) { this.agentDir = v; return this; }
        public Builder systemPromptOverride(String v) { this.systemPromptOverride = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder provider(String v) { this.provider = v; return this; }
        public Builder fallbacks(List<String> v) { this.fallbacks = new ArrayList<>(v); return this; }
        public Builder thinkingDefault(String v) { this.thinkingDefault = v; return this; }
        public Builder verboseDefault(String v) { this.verboseDefault = v; return this; }
        public Builder reasoningDefault(String v) { this.reasoningDefault = v; return this; }
        public Builder fastModeDefault(boolean v) { this.fastModeDefault = v; return this; }
        public Builder contextTokens(int v) { this.contextTokens = v; return this; }
        public Builder contextInjection(String v) { this.contextInjection = v; return this; }
        public Builder bootstrapMaxChars(int v) { this.bootstrapMaxChars = v; return this; }
        public Builder bootstrapTotalMaxChars(int v) { this.bootstrapTotalMaxChars = v; return this; }
        public Builder skills(List<String> v) { this.skills = new ArrayList<>(v); return this; }
        public Builder delegationMode(String v) { this.delegationMode = v; return this; }
        public Builder allowAgents(List<String> v) { this.allowAgents = new ArrayList<>(v); return this; }
        public Builder maxSpawnDepth(int v) { this.maxSpawnDepth = v; return this; }
        public Builder maxChildrenPerAgent(int v) { this.maxChildrenPerAgent = v; return this; }
        public Builder sandbox(String v) { this.sandbox = v; return this; }
        public Builder extensions(Map<String, String> v) { this.extensions = new LinkedHashMap<>(v); return this; }
        public Builder putExtension(String key, String value) { this.extensions.put(key, value); return this; }

        public ResolvedAgentConfig build() {
            return new ResolvedAgentConfig(this);
        }
    }
}
