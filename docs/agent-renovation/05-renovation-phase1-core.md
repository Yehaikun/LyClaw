# Phase 1: Agent Core Enhancement — Renovation Plan

> **Target**: Bring LyClaw's agent config, runtime, and hook system to OpenClaw parity.
> **Status**: Draft
> **Dependencies**: None (this is the foundation phase)

---

## Overview

LyClaw is a Java/Spring Boot multi-agent framework. Its agent system currently has:

| Component | Current State | Target |
|---|---|---|
| `@Agent` annotation | 6 basic fields (name, description, version, model, provider, extensions) | ~30 fields with full OpenClaw parity |
| `AgentConfig` | Flat POJO with 4 core + extensions map | 4-tier hierarchy (defaults / annotation / yaml / runtime) |
| `AgentConfigResolver` | Priority-based merge from sources | Deep-merge resolver with ResolvedAgentConfig |
| `AgentContext` | Flat POJO with ~12 fields | Rich context with ~25 fields + snapshot/restore |
| `AgentHook` SPI | 5 methods + getOrder() | 36-method lifecycle SPI |
| `AgentInvocationHandler` | JDK dynamic proxy with 5-hook dispatch | Full hook lifecycle dispatch |
| `AgentProxyFactory` | Simple constructor + create(Class) | Config-aware factory with runtime-type support |
| Pipeline | 6-stage SSE streaming | Same stages, enriched with hook events |
| Runtime mode | EMBEDDED only (ReAct) | EMBEDDED + ACP dual-mode |

---

## 1.1 AgentConfig System Restructuring

### 1.1.1 Problem

The current `@Agent` annotation carries only 6 fields. Per-agent configuration like thinking level, sandbox setting, subagent delegation, context injection behavior, bootstrap limits, etc. are shoved into opaque `Extension[]` key-value pairs. This makes the config typeless, undiscoverable, and error-prone. There is also no concept of "global defaults" that agents inherit from.

### 1.1.2 Design: Expanded `@Agent` Annotation

```java
package lyjew.com.lyclaw.annotation;

import java.lang.annotation.*;
import org.springframework.stereotype.Component;

/**
 * AI Agent declaration annotation — expanded to full OpenClaw parity.
 *
 * <p>Fields are resolved with priority: agent-level > global defaults
 * ({@code lyclaw.agent.defaults.*}) > system built-in defaults.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@Documented
public @interface Agent {

    // ── Identity ──────────────────────────────────────────────
    /** Unique agent identifier. When empty, derived from the class simple name (lowerCamelCase). */
    String id() default "";

    /** Whether this agent is the default agent (used when no specific agent is requested). */
    boolean defaultAgent() default false;          // was: (missing)

    /** Human-readable display name. When empty, derived from id. */
    String name() default "";

    /** Description shown in UIs and used for agent-selection routing. */
    String description() default "";

    /** Semantic version string (SemVer). */
    String version() default "1.0.0";

    // ── Workspace ─────────────────────────────────────────────
    /** Workspace root directory for this agent. Empty means use global workspace. */
    String workspace() default "";

    /** Agent-specific subdirectory under workspace. Empty means use agent id. */
    String agentDir() default "";

    // ── System prompt override ────────────────────────────────
    /** Override the system prompt that would otherwise be bootstrapped from AGENTS.md etc. */
    String systemPromptOverride() default "";

    // ── Model ──────────────────────────────────────────────────
    /** Model name (e.g. "deepseek-v4-flash"). Empty = use defaults. */
    String model() default "";

    /** Provider key (e.g. "deepseek", "openai"). Empty = use defaults. */
    String provider() default "";

    /** Ordered fallback model keys, tried in sequence when the primary model fails. */
    String[] fallbacks() default {};

    // ── Skills ─────────────────────────────────────────────────
    /** Skill identifiers to attach to this agent (e.g. "web-search", "code-interpreter"). */
    String[] skills() default {};

    // ── Thinking / Verbose / Reasoning ─────────────────────────
    /**
     * Default thinking level.
     * Valid values: off, minimal, low, medium, high, xhigh, adaptive, max.
     * Empty means use global default.
     */
    String thinkingDefault() default "";

    /** Default verbose level. Empty = use global default. */
    String verboseDefault() default "";

    /** Default reasoning level. Empty = use global default. */
    String reasoningDefault() default "";

    /** Fast mode: skip expensive pre-processing when true. */
    boolean fastModeDefault() default false;

    // ── Context limits ─────────────────────────────────────────
    /** Max context window tokens to reserve for this agent. 0 = use global default. */
    int contextTokens() default 0;

    /** Max characters to load from individual bootstrap files (e.g. AGENTS.md). */
    int bootstrapMaxChars() default 20000;

    /** Total max characters across all bootstrap files. */
    int bootstrapTotalMaxChars() default 150000;

    /**
     * When to inject AGENTS.md / CLAUDE.md content into the system prompt.
     * Valid: always, continuation-skip, never.
     */
    String contextInjection() default "always";

    // ── Subagent delegation ────────────────────────────────────
    /**
     * Delegation mode for subagent spawning.
     *   suggest — agent suggests subagent delegation, user confirms
     *   prefer  — agent prefers to delegate, less user friction
     */
    String delegationMode() default "suggest";

    /** Allowlist of agent ids this agent is permitted to spawn. Empty = unrestricted. */
    String[] allowAgents() default {};

    /** Maximum nesting depth for spawned children. */
    int maxSpawnDepth() default 1;

    /** Maximum number of children this agent can spawn at one level. */
    int maxChildrenPerAgent() default 5;

    // ── Sandbox ────────────────────────────────────────────────
    /**
     * Sandbox mode: none, docker, podman.
     * Empty = use global default.
     */
    String sandbox() default "";

    // ── Extensions (backward-compatible escape hatch) ──────────
    /**
     * Arbitrary key-value pairs for framework plugins.
     * Prefer typed fields above; use extensions only for plugin-specific
     * config that has no typed equivalent.
     */
    Extension[] extensions() default {};
}
```

### 1.1.3 AgentDefaultsConfig (Global Defaults)

This class binds to `lyclaw.agent.defaults.*` in `application.yml` and provides
the fallback layer that every agent inherits when its annotation-level field is empty.

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import java.util.List;
import java.util.Map;

/**
 * Global agent defaults, bound from {@code lyclaw.agent.defaults.*}.
 *
 * <p>Every field in this class has an agent-level override in @Agent.
 * Resolution order: agent annotation > lyclaw.agent.defaults > hard-coded system defaults.
 */
@ConfigurationProperties(prefix = "lyclaw.agent.defaults")
public class AgentDefaultsConfig {

    // ── Model defaults ─────────────────────────────────────────
    /** Default model name (e.g. "deepseek-v4-flash"). */
    private String model;                    // system default: "deepseek-v4-flash"

    /** Default provider key. */
    private String provider;                 // system default: "deepseek"

    /** Default ordered fallback model keys. */
    private List<String> fallbacks = List.of();

    // ── Thinking / Verbose / Reasoning ─────────────────────────
    /** Default thinking level: off|minimal|low|medium|high|xhigh|adaptive|max. */
    private String thinkingDefault;          // system default: "off"

    /** Default verbose level. */
    private String verboseDefault;           // system default: ""

    /** Default reasoning level. */
    private String reasoningDefault;         // system default: ""

    /** Whether fast mode is on by default. */
    private boolean fastModeDefault;         // system default: false

    // ── Context ────────────────────────────────────────────────
    /** When to inject bootstrap content: always|continuation-skip|never. */
    private String contextInjection = "always";

    /** Max chars per individual bootstrap file. */
    private int bootstrapMaxChars = 20000;

    /** Max chars total across all bootstrap files. */
    private int bootstrapTotalMaxChars = 150000;

    /** Reserved context window tokens. */
    private int contextTokens = 0;

    // ── Skills ─────────────────────────────────────────────────
    /** Default skills attached to all agents. */
    private List<String> skills = List.of();

    // ── Sandbox ────────────────────────────────────────────────
    /** Default sandbox mode: none|docker|podman. */
    private String sandbox = "none";

    // ── Subagents (delegation defaults) ────────────────────────
    @NestedConfigurationProperty
    private SubagentDefaults subagents = new SubagentDefaults();

    // ── Heartbeat ──────────────────────────────────────────────
    @NestedConfigurationProperty
    private HeartbeatDefaults heartbeat = new HeartbeatDefaults();

    // ── Run retries ────────────────────────────────────────────
    @NestedConfigurationProperty
    private RunRetryDefaults runRetries = new RunRetryDefaults();

    // ── Context limits (tool output trimming) ──────────────────
    @NestedConfigurationProperty
    private ContextLimitsDefaults contextLimits = new ContextLimitsDefaults();

    // ── Workspace ──────────────────────────────────────────────
    /** Default workspace directory. */
    private String workspace;

    // ===== Getters / Setters =====

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public List<String> getFallbacks() { return fallbacks; }
    public void setFallbacks(List<String> fallbacks) { this.fallbacks = fallbacks; }

    public String getThinkingDefault() { return thinkingDefault; }
    public void setThinkingDefault(String thinkingDefault) { this.thinkingDefault = thinkingDefault; }

    public String getVerboseDefault() { return verboseDefault; }
    public void setVerboseDefault(String verboseDefault) { this.verboseDefault = verboseDefault; }

    public String getReasoningDefault() { return reasoningDefault; }
    public void setReasoningDefault(String reasoningDefault) { this.reasoningDefault = reasoningDefault; }

    public boolean isFastModeDefault() { return fastModeDefault; }
    public void setFastModeDefault(boolean fastModeDefault) { this.fastModeDefault = fastModeDefault; }

    public String getContextInjection() { return contextInjection; }
    public void setContextInjection(String contextInjection) { this.contextInjection = contextInjection; }

    public int getBootstrapMaxChars() { return bootstrapMaxChars; }
    public void setBootstrapMaxChars(int bootstrapMaxChars) { this.bootstrapMaxChars = bootstrapMaxChars; }

    public int getBootstrapTotalMaxChars() { return bootstrapTotalMaxChars; }
    public void setBootstrapTotalMaxChars(int bootstrapTotalMaxChars) { this.bootstrapTotalMaxChars = bootstrapTotalMaxChars; }

    public int getContextTokens() { return contextTokens; }
    public void setContextTokens(int contextTokens) { this.contextTokens = contextTokens; }

    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills; }

    public String getSandbox() { return sandbox; }
    public void setSandbox(String sandbox) { this.sandbox = sandbox; }

    public SubagentDefaults getSubagents() { return subagents; }
    public void setSubagents(SubagentDefaults subagents) { this.subagents = subagents; }

    public HeartbeatDefaults getHeartbeat() { return heartbeat; }
    public void setHeartbeat(HeartbeatDefaults heartbeat) { this.heartbeat = heartbeat; }

    public RunRetryDefaults getRunRetries() { return runRetries; }
    public void setRunRetries(RunRetryDefaults runRetries) { this.runRetries = runRetries; }

    public ContextLimitsDefaults getContextLimits() { return contextLimits; }
    public void setContextLimits(ContextLimitsDefaults contextLimits) { this.contextLimits = contextLimits; }

    public String getWorkspace() { return workspace; }
    public void setWorkspace(String workspace) { this.workspace = workspace; }

    // ===== Nested config classes =====

    /** Subagent delegation defaults. */
    public static class SubagentDefaults {
        /** Default delegation mode: suggest|prefer. */
        private String delegationMode = "suggest";

        /** Allowlist of agent ids. Empty = all allowed. */
        private List<String> allowAgents = List.of();

        /** Default max spawn depth. */
        private int maxSpawnDepth = 1;

        /** Default max children per agent. */
        private int maxChildrenPerAgent = 5;

        // getters/setters omitted for brevity
        public String getDelegationMode() { return delegationMode; }
        public void setDelegationMode(String m) { this.delegationMode = m; }
        public List<String> getAllowAgents() { return allowAgents; }
        public void setAllowAgents(List<String> a) { this.allowAgents = a; }
        public int getMaxSpawnDepth() { return maxSpawnDepth; }
        public void setMaxSpawnDepth(int d) { this.maxSpawnDepth = d; }
        public int getMaxChildrenPerAgent() { return maxChildrenPerAgent; }
        public void setMaxChildrenPerAgent(int c) { this.maxChildrenPerAgent = c; }
    }

    /** Heartbeat configuration. */
    public static class HeartbeatDefaults {
        /** Whether heartbeat is enabled (periodic "you are still alive" prompts). */
        private boolean enabled = false;

        /** Interval in seconds between heartbeat checks. */
        private long intervalSeconds = 60;

        /** Max idle time in seconds before heartbeat fires. */
        private long maxIdleSeconds = 300;

        // getters/setters
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public long getIntervalSeconds() { return intervalSeconds; }
        public void setIntervalSeconds(long s) { this.intervalSeconds = s; }
        public long getMaxIdleSeconds() { return maxIdleSeconds; }
        public void setMaxIdleSeconds(long s) { this.maxIdleSeconds = s; }
    }

    /** Run retry configuration. */
    public static class RunRetryDefaults {
        /** Max retry attempts on model failure. */
        private int maxAttempts = 3;

        /** Base delay between retries in milliseconds. */
        private long baseDelayMs = 1000;

        /** Backoff strategy: fixed|exponential. */
        private String backoff = "exponential";

        // getters/setters
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int n) { this.maxAttempts = n; }
        public long getBaseDelayMs() { return baseDelayMs; }
        public void setBaseDelayMs(long d) { this.baseDelayMs = d; }
        public String getBackoff() { return backoff; }
        public void setBackoff(String b) { this.backoff = b; }
    }

    /** Context limits (tool output trimming / memory limits). */
    public static class ContextLimitsDefaults {
        /** Max chars to include from memory retrieval. */
        private int memoryGetMaxChars = 50000;

        /** Max chars of a single tool result to include in context. */
        private int toolResultMaxChars = 80000;

        /** Max total chars for all tool results combined. */
        private int toolResultTotalMaxChars = 200000;

        // getters/setters
        public int getMemoryGetMaxChars() { return memoryGetMaxChars; }
        public void setMemoryGetMaxChars(int c) { this.memoryGetMaxChars = c; }
        public int getToolResultMaxChars() { return toolResultMaxChars; }
        public void setToolResultMaxChars(int c) { this.toolResultMaxChars = c; }
        public int getToolResultTotalMaxChars() { return toolResultTotalMaxChars; }
        public void setToolResultTotalMaxChars(int c) { this.toolResultTotalMaxChars = c; }
    }
}
```

### 1.1.4 System Defaults (Hard-coded Fallback)

When neither the annotation nor `lyclaw.agent.defaults` provides a value, the system
uses these built-in constants. They are defined as a static inner class or a constants
file:

```java
package lyjew.com.lyclaw.config;

/**
 * Hard-coded system defaults — the lowest-priority fallback layer.
 * Used when neither agent annotation nor lyclaw.agent.defaults supplies a value.
 */
public final class AgentSystemDefaults {

    private AgentSystemDefaults() {}

    public static final String MODEL            = "deepseek-v4-flash";
    public static final String PROVIDER         = "deepseek";
    public static final String THINKING_DEFAULT = "off";
    public static final String VERBOSE_DEFAULT  = "";
    public static final String REASONING_DEFAULT = "";
    public static final boolean FAST_MODE       = false;
    public static final String CONTEXT_INJECTION = "always";
    public static final int BOOTSTRAP_MAX_CHARS = 20000;
    public static final int BOOTSTRAP_TOTAL_MAX_CHARS = 150000;
    public static final int CONTEXT_TOKENS      = 0;
    public static final String SANDBOX          = "none";
    public static final String DELEGATION_MODE  = "suggest";
    public static final int MAX_SPAWN_DEPTH     = 1;
    public static final int MAX_CHILDREN        = 5;
    public static final int MEMORY_GET_MAX_CHARS = 50000;
    public static final int TOOL_RESULT_MAX_CHARS = 80000;
    public static final int TOOL_RESULT_TOTAL_MAX_CHARS = 200000;
}
```

### 1.1.5 ResolvedAgentConfig (Output of Resolution)

The resolver produces a fully-resolved, deeply-merged, read-only config object.

```java
package lyjew.com.lyclaw.config;

import java.util.*;

/**
 * Fully resolved agent configuration — the output of the 3-layer deep merge.
 *
 * <p>Every field here has been resolved through:
 *   agent annotation > lyclaw.agent.defaults.* > AgentSystemDefaults
 *
 * <p>This class is immutable after construction to prevent accidental mutation
 * during the agent run lifecycle.
 */
public class ResolvedAgentConfig {

    // ── Identity ──
    private final String agentId;
    private final String agentName;
    private final String description;
    private final String version;
    private final boolean defaultAgent;

    // ── Workspace ──
    private final String workspaceDir;
    private final String agentDir;

    // ── System prompt ──
    private final String systemPromptOverride;

    // ── Model ──
    private final String model;
    private final String provider;
    private final List<String> fallbacks;

    // ── Thinking / Verbose / Reasoning ──
    private final String thinkingDefault;
    private final String verboseDefault;
    private final String reasoningDefault;
    private final boolean fastModeDefault;

    // ── Context ──
    private final int contextTokens;
    private final String contextInjection;
    private final int bootstrapMaxChars;
    private final int bootstrapTotalMaxChars;

    // ── Skills ──
    private final List<String> skills;

    // ── Delegation ──
    private final String delegationMode;
    private final List<String> allowAgents;
    private final int maxSpawnDepth;
    private final int maxChildrenPerAgent;

    // ── Sandbox ──
    private final String sandbox;

    // ── Extensions (remaining key-value pairs from @Extension[]) ──
    private final Map<String, String> extensions;

    // ── Runtime config (copied from defaults) ──
    private final AgentDefaultsConfig.HeartbeatDefaults heartbeat;
    private final AgentDefaultsConfig.RunRetryDefaults runRetries;
    private final AgentDefaultsConfig.ContextLimitsDefaults contextLimits;

    // Private constructor — use Builder via AgentConfigResolver
    private ResolvedAgentConfig(Builder builder) {
        this.agentId              = builder.agentId;
        this.agentName            = builder.agentName;
        this.description          = builder.description;
        this.version              = builder.version;
        this.defaultAgent         = builder.defaultAgent;
        this.workspaceDir         = builder.workspaceDir;
        this.agentDir             = builder.agentDir;
        this.systemPromptOverride = builder.systemPromptOverride;
        this.model                = builder.model;
        this.provider             = builder.provider;
        this.fallbacks            = List.copyOf(builder.fallbacks);
        this.thinkingDefault      = builder.thinkingDefault;
        this.verboseDefault       = builder.verboseDefault;
        this.reasoningDefault     = builder.reasoningDefault;
        this.fastModeDefault      = builder.fastModeDefault;
        this.contextTokens        = builder.contextTokens;
        this.contextInjection     = builder.contextInjection;
        this.bootstrapMaxChars    = builder.bootstrapMaxChars;
        this.bootstrapTotalMaxChars = builder.bootstrapTotalMaxChars;
        this.skills               = List.copyOf(builder.skills);
        this.delegationMode       = builder.delegationMode;
        this.allowAgents          = List.copyOf(builder.allowAgents);
        this.maxSpawnDepth        = builder.maxSpawnDepth;
        this.maxChildrenPerAgent  = builder.maxChildrenPerAgent;
        this.sandbox              = builder.sandbox;
        this.extensions           = Collections.unmodifiableMap(new HashMap<>(builder.extensions));
        this.heartbeat            = builder.heartbeat;
        this.runRetries           = builder.runRetries;
        this.contextLimits        = builder.contextLimits;
    }

    // ===== Getters =====

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
    public AgentDefaultsConfig.HeartbeatDefaults getHeartbeat() { return heartbeat; }
    public AgentDefaultsConfig.RunRetryDefaults getRunRetries() { return runRetries; }
    public AgentDefaultsConfig.ContextLimitsDefaults getContextLimits() { return contextLimits; }

    // ===== Builder =====

    public static class Builder {
        private String agentId = "";
        private String agentName = "";
        private String description = "";
        private String version = "1.0.0";
        private boolean defaultAgent = false;
        private String workspaceDir = "";
        private String agentDir = "";
        private String systemPromptOverride = "";
        private String model = "";
        private String provider = "";
        private List<String> fallbacks = List.of();
        private String thinkingDefault = "";
        private String verboseDefault = "";
        private String reasoningDefault = "";
        private boolean fastModeDefault = false;
        private int contextTokens = 0;
        private String contextInjection = "always";
        private int bootstrapMaxChars = 20000;
        private int bootstrapTotalMaxChars = 150000;
        private List<String> skills = List.of();
        private String delegationMode = "suggest";
        private List<String> allowAgents = List.of();
        private int maxSpawnDepth = 1;
        private int maxChildrenPerAgent = 5;
        private String sandbox = "none";
        private Map<String, String> extensions = new HashMap<>();
        private AgentDefaultsConfig.HeartbeatDefaults heartbeat = new AgentDefaultsConfig.HeartbeatDefaults();
        private AgentDefaultsConfig.RunRetryDefaults runRetries = new AgentDefaultsConfig.RunRetryDefaults();
        private AgentDefaultsConfig.ContextLimitsDefaults contextLimits = new AgentDefaultsConfig.ContextLimitsDefaults();

        // (setters for each field — omitted for brevity, follow the pattern:)

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
        public Builder fallbacks(List<String> v) { this.fallbacks = v; return this; }
        public Builder thinkingDefault(String v) { this.thinkingDefault = v; return this; }
        public Builder verboseDefault(String v) { this.verboseDefault = v; return this; }
        public Builder reasoningDefault(String v) { this.reasoningDefault = v; return this; }
        public Builder fastModeDefault(boolean v) { this.fastModeDefault = v; return this; }
        public Builder contextTokens(int v) { this.contextTokens = v; return this; }
        public Builder contextInjection(String v) { this.contextInjection = v; return this; }
        public Builder bootstrapMaxChars(int v) { this.bootstrapMaxChars = v; return this; }
        public Builder bootstrapTotalMaxChars(int v) { this.bootstrapTotalMaxChars = v; return this; }
        public Builder skills(List<String> v) { this.skills = v; return this; }
        public Builder delegationMode(String v) { this.delegationMode = v; return this; }
        public Builder allowAgents(List<String> v) { this.allowAgents = v; return this; }
        public Builder maxSpawnDepth(int v) { this.maxSpawnDepth = v; return this; }
        public Builder maxChildrenPerAgent(int v) { this.maxChildrenPerAgent = v; return this; }
        public Builder sandbox(String v) { this.sandbox = v; return this; }
        public Builder extensions(Map<String, String> v) { this.extensions.clear(); this.extensions.putAll(v); return this; }
        public Builder heartbeat(AgentDefaultsConfig.HeartbeatDefaults v) { this.heartbeat = v; return this; }
        public Builder runRetries(AgentDefaultsConfig.RunRetryDefaults v) { this.runRetries = v; return this; }
        public Builder contextLimits(AgentDefaultsConfig.ContextLimitsDefaults v) { this.contextLimits = v; return this; }

        public ResolvedAgentConfig build() {
            return new ResolvedAgentConfig(this);
        }
    }
}
```

### 1.1.6 AgentConfigResolver Enhancement

The resolver is enhanced with a 3-layer deep merge, list-agent support, and
workspace-dir resolution.

```java
package lyjew.com.lyclaw.config;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.annotation.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Agent config resolver — performs 3-layer deep merge:
 *   Layer 1: AgentSystemDefaults (hard-coded)
 *   Layer 2: AgentDefaultsConfig (lyclaw.agent.defaults.*)
 *   Layer 3: @Agent annotation (agent-level)
 *
 * <p>Each field uses the first non-empty/non-default value from the highest layer.
 * Lists are replaced, not merged (annotation wins completely if non-empty).
 * Maps (extensions) are merged additively (annotation wins on key conflict).
 */
public class AgentConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigResolver.class);

    private final AgentDefaultsConfig defaults;

    /** Cache: agentId → ResolvedAgentConfig. Invalidation on config refresh. */
    private final Map<String, ResolvedAgentConfig> cache = new ConcurrentHashMap<>();

    /** Registered agent entries: agentId → @Agent annotation(from class). */
    private final Map<String, Agent> agentRegistry = new ConcurrentHashMap<>();

    public AgentConfigResolver(AgentDefaultsConfig defaults) {
        this.defaults = defaults;
    }

    /**
     * Register an agent class for later resolution.
     * Called by AgentInterfaceProcessor during BFPP scan.
     */
    public void registerAgent(String agentId, Agent ann) {
        agentRegistry.put(agentId, ann);
    }

    /**
     * Resolve the full merged config for a given agent.
     *
     * Resolution for each field:
     *   1. If @Agent field is set (non-empty string, non-zero int, non-false boolean, non-empty list),
     *      use it.
     *   2. Else if AgentDefaultsConfig has a non-default value, use it.
     *   3. Else use AgentSystemDefaults.
     */
    public ResolvedAgentConfig resolveAgentConfig(String agentId) {
        return cache.computeIfAbsent(agentId, id -> {
            Agent ann = agentRegistry.get(id);
            ResolvedAgentConfig.Builder b = new ResolvedAgentConfig.Builder();

            // ── Identity ──
            b.agentId(id);
            b.agentName(resolveString(
                    ann != null ? ann.name() : "", defaultsField(null, "name"), id));
            b.description(resolveString(
                    ann != null ? ann.description() : "", "", ""));
            b.version(resolveString(
                    ann != null ? ann.version() : "", "1.0.0", "1.0.0"));
            b.defaultAgent(ann != null && ann.defaultAgent());

            // ── Workspace ──
            b.workspaceDir(resolveString(
                    ann != null ? ann.workspace() : "",
                    defaults.getWorkspace(), ""));
            b.agentDir(resolveString(
                    ann != null ? ann.agentDir() : "", "", id));

            // ── System prompt ──
            b.systemPromptOverride(resolveString(
                    ann != null ? ann.systemPromptOverride() : "", "", ""));

            // ── Model ──
            b.model(resolveString(
                    ann != null ? ann.model() : "",
                    defaults.getModel(), AgentSystemDefaults.MODEL));
            b.provider(resolveString(
                    ann != null ? ann.provider() : "",
                    defaults.getProvider(), AgentSystemDefaults.PROVIDER));
            b.fallbacks(resolveList(
                    ann != null ? List.of(ann.fallbacks()) : List.of(),
                    defaults.getFallbacks()));

            // ── Thinking / Verbose / Reasoning ──
            b.thinkingDefault(resolveString(
                    ann != null ? ann.thinkingDefault() : "",
                    defaults.getThinkingDefault(), AgentSystemDefaults.THINKING_DEFAULT));
            b.verboseDefault(resolveString(
                    ann != null ? ann.verboseDefault() : "",
                    defaults.getVerboseDefault(), AgentSystemDefaults.VERBOSE_DEFAULT));
            b.reasoningDefault(resolveString(
                    ann != null ? ann.reasoningDefault() : "",
                    defaults.getReasoningDefault(), AgentSystemDefaults.REASONING_DEFAULT));
            b.fastModeDefault(
                    (ann != null && ann.fastModeDefault())
                            || (!(ann != null && ann.fastModeDefault()) && defaults.isFastModeDefault()));

            // ── Context ──
            b.contextTokens(resolveInt(
                    ann != null ? ann.contextTokens() : 0,
                    defaults.getContextTokens(), AgentSystemDefaults.CONTEXT_TOKENS));
            b.contextInjection(resolveString(
                    ann != null ? ann.contextInjection() : "",
                    defaults.getContextInjection(), AgentSystemDefaults.CONTEXT_INJECTION));
            b.bootstrapMaxChars(resolveInt(
                    ann != null ? ann.bootstrapMaxChars() : 0,
                    defaults.getBootstrapMaxChars(), AgentSystemDefaults.BOOTSTRAP_MAX_CHARS));
            b.bootstrapTotalMaxChars(resolveInt(
                    ann != null ? ann.bootstrapTotalMaxChars() : 0,
                    defaults.getBootstrapTotalMaxChars(), AgentSystemDefaults.BOOTSTRAP_TOTAL_MAX_CHARS));

            // ── Skills ──
            b.skills(resolveList(
                    ann != null ? List.of(ann.skills()) : List.of(),
                    defaults.getSkills()));

            // ── Delegation ──
            b.delegationMode(resolveString(
                    ann != null ? ann.delegationMode() : "",
                    defaults.getSubagents().getDelegationMode(), AgentSystemDefaults.DELEGATION_MODE));
            b.allowAgents(resolveList(
                    ann != null ? List.of(ann.allowAgents()) : List.of(),
                    defaults.getSubagents().getAllowAgents()));
            b.maxSpawnDepth(resolveInt(
                    ann != null ? ann.maxSpawnDepth() : 0,
                    defaults.getSubagents().getMaxSpawnDepth(), AgentSystemDefaults.MAX_SPAWN_DEPTH));
            b.maxChildrenPerAgent(resolveInt(
                    ann != null ? ann.maxChildrenPerAgent() : 0,
                    defaults.getSubagents().getMaxChildrenPerAgent(), AgentSystemDefaults.MAX_CHILDREN));

            // ── Sandbox ──
            b.sandbox(resolveString(
                    ann != null ? ann.sandbox() : "",
                    defaults.getSandbox(), AgentSystemDefaults.SANDBOX));

            // ── Extensions: merge annotation extensions on top of any defaults
            Map<String, String> extMap = new HashMap<>();
            if (ann != null) {
                for (Extension ext : ann.extensions()) {
                    extMap.put(ext.key(), ext.value());
                }
            }
            b.extensions(extMap);

            // ── Runtime config (copied directly from defaults, no annotation override needed) ──
            b.heartbeat(defaults.getHeartbeat());
            b.runRetries(defaults.getRunRetries());
            b.contextLimits(defaults.getContextLimits());

            log.debug("ResolvedAgentConfig for {}: model={} provider={} sandbox={}",
                    id, b.build().getModel(), b.build().getProvider(), b.build().getSandbox());
            return b.build();
        });
    }

    /**
     * List all registered agent IDs.
     */
    public Set<String> listAgentIds() {
        return Collections.unmodifiableSet(agentRegistry.keySet());
    }

    /**
     * List all registered agent entries as (id, name, description) triples.
     */
    public List<AgentEntry> listAgentEntries() {
        return agentRegistry.entrySet().stream()
                .map(e -> new AgentEntry(e.getKey(),
                        e.getValue().name().isEmpty() ? e.getKey() : e.getValue().name(),
                        e.getValue().description()))
                .collect(Collectors.toList());
    }

    /**
     * Resolve the default agent id. Returns the agent with defaultAgent=true,
     * or the first registered agent, or "default".
     */
    public String resolveDefaultAgentId() {
        return agentRegistry.entrySet().stream()
                .filter(e -> e.getValue().defaultAgent())
                .map(Map.Entry::getKey)
                .findFirst()
                .or(() -> agentRegistry.keySet().stream().findFirst())
                .orElse("default");
    }

    /**
     * Resolve the full workspace directory for an agent.
     * Typically: {workspaceRoot}/{agentDir}
     */
    public String resolveAgentWorkspaceDir(ResolvedAgentConfig config) {
        String root = !config.getWorkspaceDir().isEmpty()
                ? config.getWorkspaceDir()
                : defaults.getWorkspace();
        if (root == null || root.isEmpty()) {
            root = System.getProperty("user.dir");
        }
        String dir = !config.getAgentDir().isEmpty() ? config.getAgentDir() : config.getAgentId();
        return root.endsWith("/") ? root + dir : root + "/" + dir;
    }

    /**
     * Invalidate the config cache (called on config refresh events).
     */
    public void invalidate() {
        cache.clear();
    }

    // ===== Private resolution helpers =====

    /** Resolve a nullable Object field: return the first non-null/non-blank value. */
    private String resolveString(String agentVal, String defaultsVal, String systemVal) {
        if (agentVal != null && !agentVal.isEmpty()) return agentVal;
        if (defaultsVal != null && !defaultsVal.isEmpty()) return defaultsVal;
        return systemVal != null ? systemVal : "";
    }

    private int resolveInt(int agentVal, int defaultsVal, int systemVal) {
        if (agentVal != 0) return agentVal;
        if (defaultsVal != 0) return defaultsVal;
        return systemVal;
    }

    /** Resolve a list: use agent-level if non-empty, else defaults. */
    private List<String> resolveList(List<String> agentVal, List<String> defaultsVal) {
        if (agentVal != null && !agentVal.isEmpty()) return agentVal;
        return defaultsVal != null ? defaultsVal : List.of();
    }

    /** Placeholder for field not directly on AgentDefaultsConfig root. */
    private String defaultsField(AgentDefaultsConfig d, String field) {
        if (d == null) return "";
        return switch (field) {
            case "name" -> "";
            default -> "";
        };
    }

    // ===== Data record =====

    public record AgentEntry(String id, String name, String description) {}
}
```

### 1.1.7 YAML Configuration Example

```yaml
# application.yml — agent configuration

lyclaw:
  agent:
    # Global defaults inherited by all agents
    defaults:
      model: "deepseek-v4-flash"
      provider: "deepseek"
      fallbacks:
        - "deepseek-v4-pro"
        - "openai-gpt-4o"
      thinkingDefault: "off"
      verboseDefault: ""
      reasoningDefault: ""
      fastModeDefault: false
      contextInjection: "always"
      bootstrapMaxChars: 20000
      bootstrapTotalMaxChars: 150000
      contextTokens: 0
      skills: []
      sandbox: "none"
      workspace: "/var/lyclaw/workspaces"

      # Subagent delegation defaults
      subagents:
        delegationMode: "suggest"
        allowAgents: []           # empty = allow all
        maxSpawnDepth: 1
        maxChildrenPerAgent: 5

      # Heartbeat: periodic liveness check for long-running agents
      heartbeat:
        enabled: false
        intervalSeconds: 60
        maxIdleSeconds: 300

      # Run retry on model failure
      runRetries:
        maxAttempts: 3
        baseDelayMs: 1000
        backoff: "exponential"

      # Context limits: trim tool output / memory to stay within window
      contextLimits:
        memoryGetMaxChars: 50000
        toolResultMaxChars: 80000
        toolResultTotalMaxChars: 200000

    # Per-agent overrides (legacy path "lyclaw.agents" — kept for backward compat)
    agents:
      code-reviewer:
        systemPromptOverride: "You are an expert code reviewer. Be thorough but concise."
        model: "deepseek-v4-pro"
        thinkingDefault: "high"
        maxToolRounds: 20
```

### 1.1.8 Annotation Usage Example

```java
package com.example.agents;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.annotation.agent.UserMessage;
import lyjew.com.lyclaw.annotation.agent.V;

/**
 * Code reviewer agent — uses a pro model with high thinking for quality output.
 */
@Agent(
    id          = "code-reviewer",
    defaultAgent = false,
    name        = "Code Reviewer",
    description = "Reviews code changes for bugs, style, and security issues",
    version     = "2.0.0",
    model       = "deepseek-v4-pro",
    thinkingDefault = "high",
    sandbox     = "docker",
    skills      = {"code-analysis", "security-scan"},
    delegationMode = "prefer",
    allowAgents = {"tester", "linter"},
    maxSpawnDepth = 2,
    maxChildrenPerAgent = 3,
    contextInjection = "always"
)
public interface CodeReviewerAgent {

    @UserMessage("Review the following code changes:\n\n{{diff}}")
    String review(@V("diff") String diff);

    @UserMessage("Review PR #{{prNumber}} in repository {{repo}}")
    String reviewPullRequest(@V("prNumber") int prNumber, @V("repo") String repo);
}
```

---

## 1.2 AgentContext Enhancement

### 1.2.1 Problem

The current `AgentContext` is a flat POJO with fields like `sessionId`, `userMessage`,
`systemPrompt`, `toolRegistry`, `method`, `args`, plus some pipeline-state atomics.
It has no knowledge of the agent's resolved config, no workspace paths, no runtime-type
awareness, and no subagent tracking.

### 1.2.2 Enhanced AgentContext

```java
package lyjew.com.lyclaw.react;

import lyjew.com.lyclaw.config.ResolvedAgentConfig;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.task.TaskNode;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.tracing.TraceContext;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;

/**
 * Enhanced AgentContext — the unified data bus for all hook, stage, and runtime operations.
 *
 * <h3>New fields (Phase 1 additions in bold):</h3>
 * <ul>
 *   <li><b>agentId, agentName</b> — from ResolvedAgentConfig</li>
 *   <li><b>workspaceDir, agentDir</b> — resolved filesystem paths</li>
 *   <li><b>resolvedConfig</b> — fully merged ResolvedAgentConfig</li>
 *   <li><b>bootstrapContent</b> — loaded AGENTS.md, CLAUDE.md content</li>
 *   <li><b>contextLimits</b> — memory/tool result size caps</li>
 *   <li><b>thinkingLevel, verboseLevel, reasoningLevel</b> — effective levels</li>
 *   <li><b>delegationMode, allowAgents, maxSpawnDepth, maxChildrenPerAgent</b></li>
 *   <li><b>activeSubagentIds</b> — track spawned children</li>
 *   <li><b>runtimeType</b> — EMBEDDED or ACP</li>
 *   <li><b>runMetadata</b> — runId, jobId, trigger, channelId</li>
 * </ul>
 */
public class AgentContext {

    public enum Lifecycle { TRANSIENT, SESSION, PERSISTENT }

    /**
     * Which runtime engine backs this agent invocation.
     */
    public enum AgentRuntimeType {
        /** LyClaw's built-in ReAct engine. */
        EMBEDDED,
        /** External agent backend via Agent Communication Protocol. */
        ACP
    }

    // ==================== Agent Identity (NEW) ====================

    private final String agentId;
    private final String agentName;
    private final ResolvedAgentConfig resolvedConfig;

    // ==================== Workspace (NEW) ====================

    private final String workspaceDir;
    private final String agentDir;

    // ==================== Bootstrap Content (NEW) ====================

    /**
     * Content loaded from AGENTS.md, CLAUDE.md, system.md etc.
     * Key = filename, Value = file content (truncated to bootstrapMaxChars).
     */
    private final Map<String, Object> bootstrapContent = new LinkedHashMap<>();

    // ==================== Context Limits (NEW) ====================

    /** Max chars for memory retrieval. */
    private int memoryGetMaxChars = 50000;
    /** Max chars for a single tool result. */
    private int toolResultMaxChars = 80000;
    /** Max total chars for all tool results. */
    private int toolResultTotalMaxChars = 200000;

    // ==================== Thinking / Verbose / Reasoning (NEW) ====================

    private String thinkingLevel = "off";
    private String verboseLevel = "";
    private String reasoningLevel = "";

    // ==================== Subagent Delegation (NEW) ====================

    private String delegationMode = "suggest";
    private List<String> allowAgents = List.of();
    private int maxSpawnDepth = 1;
    private int maxChildrenPerAgent = 5;

    /** Track ids of currently-active subagents spawned by this agent. */
    private final List<String> activeSubagentIds = new CopyOnWriteArrayList<>();

    // ==================== Runtime Type (NEW) ====================

    private AgentRuntimeType runtimeType = AgentRuntimeType.EMBEDDED;

    // ==================== Run Metadata (NEW) ====================

    /**
     * Arbitrary metadata about the run: runId, jobId, trigger (e.g., "webhook"),
     * channelId (e.g., Slack channel), etc.
     */
    private final Map<String, Object> runMetadata = new LinkedHashMap<>();

    // ==================== Legacy fields (unchanged) ====================

    private final String sessionId;
    private String userMessage;
    private String systemPrompt;
    private ChatRequest chatRequest;
    private final ToolRegistry toolRegistry;
    private final Method method;
    private final Object[] args;
    private SandboxLevel sandboxLevel;
    private Lifecycle lifecycle = Lifecycle.TRANSIENT;

    private final TraceContext tracing;

    private final List<String> toolResults = new CopyOnWriteArrayList<>();
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private final List<TaskNode> nodes = new CopyOnWriteArrayList<>();
    private final AtomicReference<Double> reflectScoreRef = new AtomicReference<>(0.0);
    private final AtomicBoolean pipelineOk = new AtomicBoolean(false);
    private final AtomicLong respondStartMs = new AtomicLong();
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private final AtomicReference<String> currentStage = new AtomicReference<>("init");

    private final Map<String, Object> attributes = new HashMap<>();

    // ==================== Constructors ====================

    /**
     * Full constructor with ResolvedAgentConfig.
     */
    public AgentContext(String sessionId, String userMessage, String systemPrompt,
                        ToolRegistry toolRegistry, Method method, Object[] args,
                        ResolvedAgentConfig resolvedConfig) {
        this.sessionId = sessionId;
        this.userMessage = userMessage;
        this.systemPrompt = systemPrompt;
        this.toolRegistry = toolRegistry;
        this.method = method;
        this.args = args;
        this.tracing = new TraceContext();

        // Populate from resolved config
        this.resolvedConfig = resolvedConfig;
        this.agentId = resolvedConfig.getAgentId();
        this.agentName = resolvedConfig.getAgentName();
        this.workspaceDir = resolvedConfig.getWorkspaceDir();
        this.agentDir = resolvedConfig.getAgentDir();
        this.thinkingLevel = resolvedConfig.getThinkingDefault();
        this.verboseLevel = resolvedConfig.getVerboseDefault();
        this.reasoningLevel = resolvedConfig.getReasoningDefault();
        this.delegationMode = resolvedConfig.getDelegationMode();
        this.allowAgents = resolvedConfig.getAllowAgents();
        this.maxSpawnDepth = resolvedConfig.getMaxSpawnDepth();
        this.maxChildrenPerAgent = resolvedConfig.getMaxChildrenPerAgent();

        if (resolvedConfig.getContextLimits() != null) {
            this.memoryGetMaxChars = resolvedConfig.getContextLimits().getMemoryGetMaxChars();
            this.toolResultMaxChars = resolvedConfig.getContextLimits().getToolResultMaxChars();
            this.toolResultTotalMaxChars = resolvedConfig.getContextLimits().getToolResultTotalMaxChars();
        }
    }

    /** Backward-compatible constructor (no ResolvedAgentConfig). */
    public AgentContext(String sessionId, String userMessage, String systemPrompt,
                        ToolRegistry toolRegistry, Method method, Object[] args) {
        this(sessionId, userMessage, systemPrompt, toolRegistry, method, args, null);
    }

    // ==================== New Getters/Setters ====================

    public String getAgentId() { return agentId; }
    public String getAgentName() { return agentName; }
    public ResolvedAgentConfig getResolvedConfig() { return resolvedConfig; }
    public String getWorkspaceDir() { return workspaceDir; }
    public String getAgentDir() { return agentDir; }

    public Map<String, Object> getBootstrapContent() { return bootstrapContent; }
    public void addBootstrapContent(String filename, Object content) {
        this.bootstrapContent.put(filename, content);
    }

    public int getMemoryGetMaxChars() { return memoryGetMaxChars; }
    public void setMemoryGetMaxChars(int v) { this.memoryGetMaxChars = v; }
    public int getToolResultMaxChars() { return toolResultMaxChars; }
    public void setToolResultMaxChars(int v) { this.toolResultMaxChars = v; }
    public int getToolResultTotalMaxChars() { return toolResultTotalMaxChars; }
    public void setToolResultTotalMaxChars(int v) { this.toolResultTotalMaxChars = v; }

    public String getThinkingLevel() { return thinkingLevel; }
    public void setThinkingLevel(String v) { this.thinkingLevel = v; }
    public String getVerboseLevel() { return verboseLevel; }
    public void setVerboseLevel(String v) { this.verboseLevel = v; }
    public String getReasoningLevel() { return reasoningLevel; }
    public void setReasoningLevel(String v) { this.reasoningLevel = v; }

    public String getDelegationMode() { return delegationMode; }
    public void setDelegationMode(String v) { this.delegationMode = v; }
    public List<String> getAllowAgents() { return allowAgents; }
    public void setAllowAgents(List<String> v) { this.allowAgents = v; }
    public int getMaxSpawnDepth() { return maxSpawnDepth; }
    public void setMaxSpawnDepth(int v) { this.maxSpawnDepth = v; }
    public int getMaxChildrenPerAgent() { return maxChildrenPerAgent; }
    public void setMaxChildrenPerAgent(int v) { this.maxChildrenPerAgent = v; }

    public List<String> getActiveSubagentIds() { return activeSubagentIds; }
    public void addActiveSubagentId(String id) { this.activeSubagentIds.add(id); }
    public void removeActiveSubagentId(String id) { this.activeSubagentIds.remove(id); }

    public AgentRuntimeType getRuntimeType() { return runtimeType; }
    public void setRuntimeType(AgentRuntimeType v) { this.runtimeType = v; }

    public Map<String, Object> getRunMetadata() { return runMetadata; }
    public void setRunMetadata(String key, Object value) { this.runMetadata.put(key, value); }
    @SuppressWarnings("unchecked")
    public <T> T getRunMetadata(String key) { return (T) runMetadata.get(key); }

    // ==================== Legacy Getters (unchanged) ====================

    public String getSessionId() { return sessionId; }
    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String userMessage) { this.userMessage = userMessage; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public ChatRequest getChatRequest() { return chatRequest; }
    public void setChatRequest(ChatRequest chatRequest) { this.chatRequest = chatRequest; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public Method getMethod() { return method; }
    public Object[] getArgs() { return args; }
    public SandboxLevel getSandboxLevel() { return sandboxLevel; }
    public void setSandboxLevel(SandboxLevel sandboxLevel) { this.sandboxLevel = sandboxLevel; }
    public Lifecycle getLifecycle() { return lifecycle; }
    public void setLifecycle(Lifecycle lifecycle) { this.lifecycle = lifecycle; }
    public TraceContext getTracing() { return tracing; }
    public List<String> getToolResults() { return toolResults; }
    public void addToolResult(String result) { toolResults.add(result); }
    public AtomicInteger getSuccessCount() { return successCount; }
    public AtomicInteger getFailCount() { return failCount; }
    public List<TaskNode> getNodes() { return nodes; }
    public void addNode(TaskNode node) { nodes.add(node); }
    public AtomicReference<Double> getReflectScoreRef() { return reflectScoreRef; }
    public AtomicBoolean getPipelineOk() { return pipelineOk; }
    public boolean isPipelineOk() { return pipelineOk.get(); }
    public void setPipelineOk(boolean value) { pipelineOk.set(value); }
    public AtomicLong getRespondStartMs() { return respondStartMs; }
    public AtomicBoolean getTerminated() { return terminated; }
    public boolean isTerminated() { return terminated.get(); }
    public void setTerminated(boolean value) { terminated.set(value); }
    public AtomicReference<String> getCurrentStage() { return currentStage; }
    public <T> T getAttribute(String key) { return (T) attributes.get(key); }
    public void setAttribute(String key, Object value) { attributes.put(key, value); }
    public Map<String, Object> getAttributes() { return attributes; }

    // ==================== Enhanced Snapshot/Restore ====================

    /**
     * Enhanced snapshot — includes all new fields.
     */
    public Map<String, Object> toSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();

        // Legacy
        snapshot.put("sessionId", sessionId);
        snapshot.put("userMessage", userMessage);
        snapshot.put("systemPrompt", systemPrompt);
        snapshot.put("sandboxLevel", sandboxLevel != null ? sandboxLevel.name() : null);
        snapshot.put("lifecycle", lifecycle.name());
        snapshot.put("currentStage", currentStage.get());
        snapshot.put("successCount", successCount.get());
        snapshot.put("failCount", failCount.get());
        snapshot.put("pipelineOk", pipelineOk.get());
        snapshot.put("terminated", terminated.get());
        snapshot.put("reflectScore", reflectScoreRef.get());
        snapshot.put("toolResults", new ArrayList<>(toolResults));
        snapshot.put("tracing", Map.of("traceId", tracing.getTraceId()));

        // New — identity
        snapshot.put("agentId", agentId);
        snapshot.put("agentName", agentName);

        // New — workspace
        snapshot.put("workspaceDir", workspaceDir);
        snapshot.put("agentDir", agentDir);

        // New — levels
        snapshot.put("thinkingLevel", thinkingLevel);
        snapshot.put("verboseLevel", verboseLevel);
        snapshot.put("reasoningLevel", reasoningLevel);

        // New — delegation
        snapshot.put("delegationMode", delegationMode);
        snapshot.put("allowAgents", new ArrayList<>(allowAgents));
        snapshot.put("maxSpawnDepth", maxSpawnDepth);
        snapshot.put("maxChildrenPerAgent", maxChildrenPerAgent);

        // New — context limits
        snapshot.put("memoryGetMaxChars", memoryGetMaxChars);
        snapshot.put("toolResultMaxChars", toolResultMaxChars);
        snapshot.put("toolResultTotalMaxChars", toolResultTotalMaxChars);

        // New — runtime
        snapshot.put("runtimeType", runtimeType.name());
        snapshot.put("activeSubagentIds", new ArrayList<>(activeSubagentIds));
        snapshot.put("runMetadata", new HashMap<>(runMetadata));

        // New — bootstrap
        snapshot.put("bootstrapContent", new HashMap<>(bootstrapContent));

        return snapshot;
    }

    /**
     * Restore from snapshot. Runtime references (toolRegistry, method, args)
     * must be re-injected by the caller.
     */
    @SuppressWarnings("unchecked")
    public void restoreFromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null) return;

        // Legacy
        if (snapshot.get("sandboxLevel") != null)
            this.sandboxLevel = SandboxLevel.valueOf((String) snapshot.get("sandboxLevel"));
        if (snapshot.get("lifecycle") != null)
            this.lifecycle = Lifecycle.valueOf((String) snapshot.get("lifecycle"));
        if (snapshot.get("currentStage") != null)
            this.currentStage.set((String) snapshot.get("currentStage"));
        if (snapshot.get("successCount") != null)
            this.successCount.set(((Number) snapshot.get("successCount")).intValue());
        if (snapshot.get("failCount") != null)
            this.failCount.set(((Number) snapshot.get("failCount")).intValue());
        if (snapshot.get("pipelineOk") != null)
            this.pipelineOk.set((Boolean) snapshot.get("pipelineOk"));
        if (snapshot.get("terminated") != null)
            this.terminated.set((Boolean) snapshot.get("terminated"));
        if (snapshot.get("reflectScore") != null)
            this.reflectScoreRef.set(((Number) snapshot.get("reflectScore")).doubleValue());
        if (snapshot.get("toolResults") instanceof List<?> list) {
            this.toolResults.clear();
            for (Object item : list) this.toolResults.add((String) item);
        }

        // New — identity
        if (snapshot.get("agentId") != null)
            this.setRunMetadata("restoredAgentId", snapshot.get("agentId"));

        // New — levels
        if (snapshot.get("thinkingLevel") != null)
            this.thinkingLevel = (String) snapshot.get("thinkingLevel");
        if (snapshot.get("verboseLevel") != null)
            this.verboseLevel = (String) snapshot.get("verboseLevel");
        if (snapshot.get("reasoningLevel") != null)
            this.reasoningLevel = (String) snapshot.get("reasoningLevel");

        // New — delegation
        if (snapshot.get("delegationMode") != null)
            this.delegationMode = (String) snapshot.get("delegationMode");
        if (snapshot.get("allowAgents") instanceof List<?> al)
            this.allowAgents = al.stream().map(Object::toString).toList();
        if (snapshot.get("maxSpawnDepth") instanceof Number n)
            this.maxSpawnDepth = n.intValue();
        if (snapshot.get("maxChildrenPerAgent") instanceof Number n)
            this.maxChildrenPerAgent = n.intValue();

        // New — context limits
        if (snapshot.get("memoryGetMaxChars") instanceof Number n)
            this.memoryGetMaxChars = n.intValue();
        if (snapshot.get("toolResultMaxChars") instanceof Number n)
            this.toolResultMaxChars = n.intValue();
        if (snapshot.get("toolResultTotalMaxChars") instanceof Number n)
            this.toolResultTotalMaxChars = n.intValue();

        // New — runtime
        if (snapshot.get("runtimeType") != null)
            this.runtimeType = AgentRuntimeType.valueOf((String) snapshot.get("runtimeType"));
        if (snapshot.get("activeSubagentIds") instanceof List<?> sl) {
            this.activeSubagentIds.clear();
            for (Object item : sl) this.activeSubagentIds.add((String) item);
        }
        if (snapshot.get("runMetadata") instanceof Map<?, ?> rm) {
            this.runMetadata.clear();
            for (Map.Entry<?, ?> e : rm.entrySet())
                this.runMetadata.put((String) e.getKey(), e.getValue());
        }
        if (snapshot.get("bootstrapContent") instanceof Map<?, ?> bc) {
            this.bootstrapContent.clear();
            for (Map.Entry<?, ?> e : bc.entrySet())
                this.bootstrapContent.put((String) e.getKey(), e.getValue());
        }
    }

    // ==================== Factory Methods ====================

    public static AgentContext sessionScoped(String sessionId, String userMessage,
                                             String systemPrompt, ToolRegistry toolRegistry,
                                             Method method, Object[] args,
                                             ResolvedAgentConfig resolvedConfig) {
        AgentContext ctx = new AgentContext(sessionId, userMessage, systemPrompt,
                toolRegistry, method, args, resolvedConfig);
        ctx.setLifecycle(Lifecycle.SESSION);
        return ctx;
    }

    public static AgentContext persistentScoped(String sessionId, String userMessage,
                                                 String systemPrompt, ToolRegistry toolRegistry,
                                                 Method method, Object[] args,
                                                 ResolvedAgentConfig resolvedConfig) {
        AgentContext ctx = new AgentContext(sessionId, userMessage, systemPrompt,
                toolRegistry, method, args, resolvedConfig);
        ctx.setLifecycle(Lifecycle.PERSISTENT);
        return ctx;
    }
}
```

---

## 1.3 Hook System Expansion (5 to 36 Hooks)

### 1.3.1 Problem

The current `AgentHook` has only 5 extension points: `beforeRequest`, `beforeModel`,
`afterModel`, `wrapToolCall`, `wrapToolExecutor`, `afterResult`. There is no way to
hook into session lifecycle, agent start/end, subagent spawning, compaction, message
events, or heartbeat contributions.

### 1.3.2 Full Hook Interface

```java
package lyjew.com.lyclaw.react;

import java.util.List;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ToolCall;

/**
 * Full agent lifecycle hook SPI — 36 extension points.
 *
 * <p>All methods are default (no-op) so implementors override only what they need.
 * Hooks are dispatched by {@link AgentInvocationHandler} at the appropriate
 * point in the agent lifecycle.
 *
 * <h3>Execution order</h3>
 * <p>Hooks are sorted by {@link #getOrder()} (ascending) before dispatch.
 * Default order is 100.</p>
 */
public interface AgentHook {

    // =====================================================================
    // EXISTING (kept for backward compatibility)
    // =====================================================================

    /** Before the entire agent invocation pipeline starts.
     *  Throwing an exception aborts the request. */
    default void beforeRequest(AgentContext ctx) {}

    /** Before each LLM call. Can inject planning context or adjust messages. */
    default List<Message> beforeModel(List<Message> messages, AgentContext ctx) {
        return messages;
    }

    /** After each LLM response. Can detect harmful content, log, or transform output. */
    default String afterModel(String response, AgentContext ctx) {
        return response;
    }

    /** Wrap a single tool call (finer-grained than wrapToolExecutor). */
    default ToolCall wrapToolCall(ToolCall toolCall, AgentContext ctx) {
        return toolCall;
    }

    /** Wrap the ToolExecutor, forming a decorator chain. */
    default ToolExecutor wrapToolExecutor(ToolExecutor inner, AgentContext ctx) {
        return inner;
    }

    /** After the final result, before returning to the caller.
     *  Dispatched in reverse order (afterResult hooks run high-to-low). */
    default String afterResult(String result, AgentContext ctx) {
        return result;
    }

    /** Priority. Lower numbers execute first. Default: 100. */
    default int getOrder() { return 100; }

    // =====================================================================
    // NEW — Model Lifecycle
    // =====================================================================

    /** Before model resolution (provider + model selection). */
    default void beforeModelResolve(AgentContext ctx) {}

    /** Called when a model call begins (after routing, before API call). */
    default void modelCallStarted(AgentContext ctx) {}

    /** Called when a model call ends (success or failure). */
    default void modelCallEnded(AgentContext ctx) {}

    /** Raw LLM input (the final assembled prompt sent to the model). */
    default void llmInput(String prompt, AgentContext ctx) {}

    /** Raw LLM output (the complete model response, before parsing). */
    default void llmOutput(String response, AgentContext ctx) {}

    // =====================================================================
    // NEW — Agent Lifecycle
    // =====================================================================

    /** Before an agent run starts (pipeline entry). */
    default void beforeAgentStart(AgentContext ctx) {}

    /**
     * Before the agent's reply is sent back to the caller.
     * @param reply the draft reply text
     * @param ctx agent context
     */
    default void beforeAgentReply(String reply, AgentContext ctx) {}

    /**
     * Before the agent is finalized (after ReAct loop ends, before cleanup).
     * Can return a decision to CONTINUE (default), REVISE (retry with instruction),
     * or FINALIZE (skip revision).
     */
    default AgentFinalizeResult beforeAgentFinalize(AgentContext ctx) {
        return AgentFinalizeResult.continue_();
    }

    /** After the agent run completes (cleanup, metrics, notification). */
    default void agentEnd(AgentContext ctx) {}

    /** Before each individual agent invocation (per-method call on the proxy). */
    default void beforeAgentRun(AgentContext ctx) {}

    // =====================================================================
    // NEW — Tool Lifecycle
    // =====================================================================

    /** Before a tool is invoked. Contains tool name, call id, serialized args. */
    default void beforeToolCall(String toolName, String toolCallId, String args, AgentContext ctx) {}

    /** After a tool completes. Contains the result string (could be error). */
    default void afterToolCall(String toolName, String toolCallId, String result, AgentContext ctx) {}

    /** After tool result is persisted into message history. */
    default void toolResultPersist(String toolName, String result, AgentContext ctx) {}

    // =====================================================================
    // NEW — Session Lifecycle
    // =====================================================================

    /** When a new agent session is created. */
    default void sessionStart(String sessionId, AgentContext ctx) {}

    /** When an agent session ends (clean shutdown or timeout). */
    default void sessionEnd(String sessionId, AgentContext ctx) {}

    // =====================================================================
    // NEW — Subagent Lifecycle
    // =====================================================================

    /** Before a subagent is spawned. Hook can block by throwing. */
    default void subagentSpawning(String childAgentId, String task, AgentContext ctx) {}

    /** After a subagent is successfully spawned and session created. */
    default void subagentSpawned(String childAgentId, String sessionKey, AgentContext ctx) {}

    /** After a subagent completes (success or failure). */
    default void subagentEnded(String childAgentId, String outcome, AgentContext ctx) {}

    // =====================================================================
    // NEW — Compaction
    // =====================================================================

    /** Before message history compaction (context window management). */
    default void beforeCompaction(AgentContext ctx) {}

    /** After message history compaction. */
    default void afterCompaction(AgentContext ctx) {}

    // =====================================================================
    // NEW — Message Lifecycle
    // =====================================================================

    /** A message was received from the caller/user. */
    default void messageReceived(Message msg, AgentContext ctx) {}

    /** The agent is about to send a message (before LLM call). */
    default void messageSending(String msg, AgentContext ctx) {}

    /** A message was sent to the caller. */
    default void messageSent(String msg, AgentContext ctx) {}

    // =====================================================================
    // NEW — Heartbeat
    // =====================================================================

    /**
     * Contribute content to the periodic heartbeat prompt sent to the LLM
     * to keep long-running agents alive and aware of their context.
     * @return contribution string (appended to heartbeat prompt), or "" for nothing.
     */
    default String heartbeatPromptContribution(AgentContext ctx) { return ""; }
}
```

### 1.3.3 AgentFinalizeResult

```java
package lyjew.com.lyclaw.react;

/**
 * Returned by {@link AgentHook#beforeAgentFinalize(AgentContext)}.
 * Controls whether the agent run is complete, needs revision, or should
 * finalize immediately.
 */
public class AgentFinalizeResult {

    public enum Action {
        /** Continue normally — proceed to finalize and return result. */
        CONTINUE,
        /** Revise — loop back to respond with retryInstruction. */
        REVISE,
        /** Finalize immediately — skip any remaining revision logic. */
        FINALIZE
    }

    private final Action action;
    private final String reason;
    private final String retryInstruction;
    private final String idempotencyKey;
    private final int maxAttempts;

    private AgentFinalizeResult(Action action, String reason, String retryInstruction,
                                String idempotencyKey, int maxAttempts) {
        this.action = action;
        this.reason = reason;
        this.retryInstruction = retryInstruction;
        this.idempotencyKey = idempotencyKey;
        this.maxAttempts = maxAttempts;
    }

    // ===== Factory methods =====

    public static AgentFinalizeResult continue_() {
        return new AgentFinalizeResult(Action.CONTINUE, null, null, null, 1);
    }

    public static AgentFinalizeResult revise(String reason, String retryInstruction) {
        return new AgentFinalizeResult(Action.REVISE, reason, retryInstruction, null, 3);
    }

    public static AgentFinalizeResult revise(String reason, String retryInstruction,
                                              String idempotencyKey, int maxAttempts) {
        return new AgentFinalizeResult(Action.REVISE, reason, retryInstruction,
                idempotencyKey, maxAttempts);
    }

    public static AgentFinalizeResult finalize(String reason) {
        return new AgentFinalizeResult(Action.FINALIZE, reason, null, null, 1);
    }

    // ===== Getters =====

    public Action getAction() { return action; }
    public String getReason() { return reason; }
    public String getRetryInstruction() { return retryInstruction; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public int getMaxAttempts() { return maxAttempts; }

    public boolean isContinue() { return action == Action.CONTINUE; }
    public boolean isRevise() { return action == Action.REVISE; }
    public boolean isFinalize() { return action == Action.FINALIZE; }
}
```

### 1.3.4 HookDecision (Security / Approval)

```java
package lyjew.com.lyclaw.react;

import java.util.Map;

/**
 * A blocking/approval decision returned by hooks that gate execution.
 * Used by security hooks, approval hooks, etc.
 */
public class HookDecision {

    public enum Outcome {
        /** Allow execution to proceed. */
        PASS,
        /** Block execution. */
        BLOCK
    }

    private final Outcome outcome;
    private final String reason;
    private final String message;       // user-facing message
    private final String category;      // e.g., "security", "approval", "rate-limit"
    private final Map<String, Object> metadata;

    private HookDecision(Outcome outcome, String reason, String message,
                         String category, Map<String, Object> metadata) {
        this.outcome = outcome;
        this.reason = reason;
        this.message = message;
        this.category = category;
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public static HookDecision pass() {
        return new HookDecision(Outcome.PASS, null, null, null, null);
    }

    public static HookDecision block(String reason, String message, String category) {
        return new HookDecision(Outcome.BLOCK, reason, message, category, null);
    }

    public static HookDecision block(String reason, String message, String category,
                                      Map<String, Object> metadata) {
        return new HookDecision(Outcome.BLOCK, reason, message, category, metadata);
    }

    public Outcome getOutcome() { return outcome; }
    public String getReason() { return reason; }
    public String getMessage() { return message; }
    public String getCategory() { return category; }
    public Map<String, Object> getMetadata() { return metadata; }
    public boolean isPass() { return outcome == Outcome.PASS; }
    public boolean isBlock() { return outcome == Outcome.BLOCK; }
}
```

### 1.3.5 HookRegistration (Registry Entry)

```java
package lyjew.com.lyclaw.react;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A registered hook entry in the {@link HookRegistry}.
 *
 * @param pluginId    the plugin/module that registered this hook
 * @param hookName    the hook method name (e.g. "beforeModel", "afterToolCall")
 * @param handler     the handler function (signature varies by hook)
 * @param priority    execution priority (lower = earlier)
 * @param timeoutMs   max execution time before the hook is considered hung (0 = no timeout)
 * @param source      how the hook was registered (annotation, SPI, programmatic)
 */
public record HookRegistration(
        String pluginId,
        String hookName,
        Object handler,          // Function or BiConsumer depending on hook
        int priority,
        long timeoutMs,
        String source            // "annotation", "spi", "programmatic"
) {
    public HookRegistration {
        if (pluginId == null || pluginId.isBlank()) pluginId = "unknown";
        if (hookName == null || hookName.isBlank()) throw new IllegalArgumentException("hookName is required");
        if (handler == null) throw new IllegalArgumentException("handler is required");
        if (timeoutMs < 0) timeoutMs = 0;
        if (source == null || source.isBlank()) source = "programmatic";
    }

    public static HookRegistration of(String pluginId, String hookName,
                                       Object handler, int priority) {
        return new HookRegistration(pluginId, hookName, handler, priority, 0, "programmatic");
    }
}
```

### 1.3.6 HookRegistry

```java
package lyjew.com.lyclaw.react;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central registry for hook management and dispatch.
 *
 * <p>Hooks are grouped by hook name (e.g., "beforeModel", "afterToolCall").
 * At dispatch time, they are sorted by priority (ascending) and invoked in order.
 */
public class HookRegistry {

    private static final Logger log = LoggerFactory.getLogger(HookRegistry.class);

    /** hookName → sorted list of registrations. */
    private final Map<String, List<HookRegistration>> registrations = new ConcurrentHashMap<>();

    /**
     * Register a hook. If the hook name is new, a list is created.
     * Registrations for the same hook name are kept sorted by priority.
     */
    public void register(HookRegistration reg) {
        registrations.compute(reg.hookName(), (k, list) -> {
            if (list == null) list = new CopyOnWriteArrayList<>();
            list.add(reg);
            list.sort(Comparator.comparingInt(HookRegistration::priority));
            return list;
        });
        log.debug("Hook registered: pluginId={} hookName={} priority={}",
                reg.pluginId(), reg.hookName(), reg.priority());
    }

    /**
     * Unregister all hooks from a given plugin.
     */
    public void unregisterPlugin(String pluginId) {
        registrations.forEach((hookName, list) ->
                list.removeIf(reg -> reg.pluginId().equals(pluginId)));
    }

    /**
     * Get all registrations for a hook name, sorted by priority.
     */
    public List<HookRegistration> getHooks(String hookName) {
        return registrations.getOrDefault(hookName, List.of());
    }

    /**
     * Get all registered hook names.
     */
    public Set<String> getHookNames() {
        return Collections.unmodifiableSet(registrations.keySet());
    }

    /**
     * Clear all registrations.
     */
    public void clear() {
        registrations.clear();
    }
}
```

### 1.3.7 AgentInvocationHandler — Hook Dispatch Updates

The existing `AgentInvocationHandler` is updated to dispatch the new hooks at the right
points in the lifecycle:

```java
// Inside AgentInvocationHandler.invoke() — pseudocode for hook dispatch additions:

@Override
public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    // ... (existing setup: resolve messages, build context, create AgentContext) ...

    AgentContext ctx = new AgentContext(sessionId, userMessage, systemPrompt,
            toolRegistry, method, args, resolvedConfig);

    // NEW: dispatch beforeAgentStart + beforeAgentRun
    dispatch("beforeAgentStart", ctx);
    dispatch("beforeAgentRun", ctx);

    // NEW: dispatch sessionStart (once per session)
    dispatch("sessionStart", ctx.getSessionId(), ctx);

    // 1. beforeRequest hooks (legacy, kept for backward compat)
    List<AgentHook> sorted = sortedHooks();
    for (AgentHook hook : sorted) {
        hook.beforeRequest(ctx);
    }

    // ... (existing stage pipeline or ReAct execution) ...

    // Inside the ReAct loop, around each model call:

    // NEW: beforeModelResolve
    dispatch("beforeModelResolve", ctx);

    // NEW: modelCallStarted
    dispatch("modelCallStarted", ctx);

    // LEGACY: beforeModel (kept)
    for (AgentHook hook : sorted) {
        messages = hook.beforeModel(messages, ctx);
    }

    // NEW: llmInput
    dispatch("llmInput", assembledPrompt, ctx);

    // ... (actual LLM call) ...

    // NEW: llmOutput
    dispatch("llmOutput", response, ctx);

    // LEGACY: afterModel (kept)
    for (AgentHook hook : sorted) {
        response = hook.afterModel(response, ctx);
    }

    // NEW: modelCallEnded
    dispatch("modelCallEnded", ctx);

    // Around each tool call in the ReAct loop:

    // NEW: beforeToolCall
    dispatch("beforeToolCall", toolName, toolCallId, argsJson, ctx);

    // ... (actual tool execution) ...

    // NEW: afterToolCall
    dispatch("afterToolCall", toolName, toolCallId, result, ctx);

    // NEW: toolResultPersist
    dispatch("toolResultPersist", toolName, result, ctx);

    // After ReAct loop ends (before returning result):

    // NEW: beforeAgentFinalize — allows REVISE gate
    AgentFinalizeResult finalizeResult = dispatchFinalize(ctx);
    if (finalizeResult.isRevise()) {
        // loop back to ReAct with retryInstruction
    }

    // LEGACY: afterResult (kept, reverse order)
    for (int i = sorted.size() - 1; i >= 0; i--) {
        result = sorted.get(i).afterResult(result, ctx);
    }

    // NEW: agentEnd
    dispatch("agentEnd", ctx);

    // NEW: sessionEnd (if session is ending)
    dispatch("sessionEnd", ctx.getSessionId(), ctx);

    return result;
}
```

The dispatch helpers used within AgentInvocationHandler:

```java
// Generic dispatch by hook name — uses HookRegistry for new hooks
// and direct AgentHook calls for legacy SPI methods.

private void dispatch(String hookName, Object... args) {
    List<HookRegistration> hooks = hookRegistry.getHooks(hookName);
    for (HookRegistration reg : hooks) {
        try {
            // Invoke handler (type-safe dispatch)
            invokeHandler(reg, args);
        } catch (Exception e) {
            log.warn("Hook {} (plugin={}) failed: {}", hookName, reg.pluginId(), e.getMessage());
            // Hook failures are non-fatal by default; SecurityHook can throw to block
        }
    }
}

private AgentFinalizeResult dispatchFinalize(AgentContext ctx) {
    List<HookRegistration> hooks = hookRegistry.getHooks("beforeAgentFinalize");
    for (HookRegistration reg : hooks) {
        try {
            @SuppressWarnings("unchecked")
            Function<AgentContext, AgentFinalizeResult> handler =
                    (Function<AgentContext, AgentFinalizeResult>) reg.handler();
            AgentFinalizeResult result = handler.apply(ctx);
            if (result.isRevise() || result.isFinalize()) {
                return result; // first non-continue short-circuits
            }
        } catch (Exception e) {
            log.warn("Finalize hook {} (plugin={}) failed: {}",
                    reg.hookName(), reg.pluginId(), e.getMessage());
        }
    }
    return AgentFinalizeResult.continue_();
}
```

### 1.3.8 Example: Migrating Existing Hooks

Existing hooks like `SecurityCheckHook`, `ApprovalHook`, `OutputGuardHook`,
`PlanningHook`, `SandboxHook` continue to implement `AgentHook` and work identically.
New hooks targeting specific lifecycle points register via `HookRegistry`:

```java
package com.example.hooks;

import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.HookRegistration;
import lyjew.com.lyclaw.react.HookRegistry;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

/**
 * Example: a compaction logger hook that traces when context compaction happens.
 * Registered programmatically via HookRegistry rather than implementing AgentHook.
 */
@Component
public class CompactionLogger {

    private final HookRegistry hookRegistry;

    public CompactionLogger(HookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry;
    }

    @PostConstruct
    public void registerHooks() {
        hookRegistry.register(HookRegistration.of(
                "compaction-logger",
                "beforeCompaction",
                (java.util.function.Consumer<AgentContext>) ctx -> {
                    // log context size before compaction
                },
                200
        ));

        hookRegistry.register(HookRegistration.of(
                "compaction-logger",
                "afterCompaction",
                (java.util.function.Consumer<AgentContext>) ctx -> {
                    // log context size after compaction
                },
                200
        ));
    }
}
```

---

## 1.4 AgentRuntime Modes

### 1.4.1 Problem

LyClaw currently only supports EMBEDDED mode (the built-in ReAct engine). OpenClaw
supports ACP (Agent Communication Protocol) mode where the agent backend runs in an
external process (e.g., a Node.js Codex CLI instance) and communicates via a
bidirectional protocol. Adding ACP support requires a clean abstraction.

### 1.4.2 AgentRuntimeType Enum

```java
package lyjew.com.lyclaw.react;

/**
 * The runtime mode that backs an agent invocation.
 */
public enum AgentRuntimeType {

    /**
     * Default mode — LyClaw's built-in ReAct engine handles the
     * full reasoning-acting loop internally.
     */
    EMBEDDED,

    /**
     * Agent Communication Protocol mode — the agent backend runs
     * in an external process. LyClaw communicates with it via
     * a bidirectional protocol (events, turns, sessions).
     */
    ACP
}
```

### 1.4.3 AcpRuntime Interface

```java
package lyjew.com.lyclaw.react;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Map;

/**
 * ACP (Agent Communication Protocol) runtime SPI.
 *
 * <p>Implementations manage sessions and turns with external agent backends
 * (e.g., Codex CLI, custom agent servers). The protocol is:
 * <ol>
 *   <li>{@link #ensureSession(AcpRuntimeEnsureInput)} — get or create a session</li>
 *   <li>{@link #startTurn(AcpRuntimeTurnInput)} — start a conversational turn,
 *       receiving a Flux of events (text deltas, tool calls, status updates)</li>
 *   <li>{@link #cancel(AcpRuntimeHandle, String)} — cancel a running turn</li>
 *   <li>{@link #close(AcpRuntimeHandle, String)} — tear down the session</li>
 * </ol>
 */
public interface AcpRuntime {

    /**
     * Ensure a session exists for the given agent + session key.
     * Returns a handle that can be used for subsequent turn/cancel/close calls.
     */
    Mono<AcpRuntimeHandle> ensureSession(AcpRuntimeEnsureInput input);

    /**
     * Start a conversational turn. Returns a Flux of AcpRuntimeEvent:
     * text_delta (streaming tokens), tool_call, tool_result, status, done, error.
     */
    Flux<AcpRuntimeEvent> startTurn(AcpRuntimeTurnInput input);

    /**
     * Query the backend's capabilities (model, tools, features).
     */
    Mono<AcpRuntimeCapabilities> getCapabilities(AcpRuntimeHandle handle);

    /**
     * Cancel an in-progress turn.
     */
    Mono<Void> cancel(AcpRuntimeHandle handle, String reason);

    /**
     * Close (tear down) a session.
     */
    Mono<Void> close(AcpRuntimeHandle handle, String reason);
}
```

### 1.4.4 AcpRuntimeHandle

```java
package lyjew.com.lyclaw.react;

/**
 * Opaque handle to an active ACP session.
 *
 * <p>Contains identifiers needed by the AcpRuntime implementation to route
 * subsequent turn/cancel/close requests to the correct backend session.
 */
public class AcpRuntimeHandle {

    /** The session key used when the session was created. */
    private final String sessionKey;

    /** Which backend this session is on (e.g., "codex-cli", "custom-agent-server"). */
    private final String backend;

    /** The runtime-level session name (may differ from the user-facing session key). */
    private final String runtimeSessionName;

    /** Working directory for this session. */
    private final String cwd;

    /** Backend-specific session identifier (e.g., a process PID or UUID). */
    private final String backendSessionId;

    /** LyClaw-level agent session identifier. */
    private final String agentSessionId;

    public AcpRuntimeHandle(String sessionKey, String backend, String runtimeSessionName,
                            String cwd, String backendSessionId, String agentSessionId) {
        this.sessionKey = sessionKey;
        this.backend = backend;
        this.runtimeSessionName = runtimeSessionName;
        this.cwd = cwd;
        this.backendSessionId = backendSessionId;
        this.agentSessionId = agentSessionId;
    }

    public String getSessionKey() { return sessionKey; }
    public String getBackend() { return backend; }
    public String getRuntimeSessionName() { return runtimeSessionName; }
    public String getCwd() { return cwd; }
    public String getBackendSessionId() { return backendSessionId; }
    public String getAgentSessionId() { return agentSessionId; }
}
```

### 1.4.5 AcpRuntimeEvent

```java
package lyjew.com.lyclaw.react;

import java.util.Map;

/**
 * An event emitted during an ACP turn.
 *
 * <p>Events are streamed as a Flux from {@link AcpRuntime#startTurn(AcpRuntimeTurnInput)}.
 */
public class AcpRuntimeEvent {

    public enum EventType {
        /** A delta of text content (streaming token). */
        TEXT_DELTA,
        /** The backend wants to invoke a tool. */
        TOOL_CALL,
        /** A tool result to send back to the backend. */
        TOOL_RESULT,
        /** Status update (e.g., "thinking", "executing tool"). */
        STATUS,
        /** Turn completed successfully. */
        DONE,
        /** Turn failed with an error. */
        ERROR
    }

    private final EventType type;
    private final String data;
    private final Map<String, Object> metadata;

    public AcpRuntimeEvent(EventType type, String data, Map<String, Object> metadata) {
        this.type = type;
        this.data = data;
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    // ===== Factory methods =====

    public static AcpRuntimeEvent textDelta(String text) {
        return new AcpRuntimeEvent(EventType.TEXT_DELTA, text, null);
    }

    public static AcpRuntimeEvent toolCall(String toolName, String toolCallId,
                                            String arguments, Map<String, Object> metadata) {
        return new AcpRuntimeEvent(EventType.TOOL_CALL,
                toolName,  // data carries the tool name; metadata has id + args
                Map.of("toolCallId", toolCallId, "arguments", arguments));
    }

    public static AcpRuntimeEvent toolResult(String toolCallId, String result, boolean success) {
        return new AcpRuntimeEvent(EventType.TOOL_RESULT, result,
                Map.of("toolCallId", toolCallId, "success", success));
    }

    public static AcpRuntimeEvent status(String status) {
        return new AcpRuntimeEvent(EventType.STATUS, status, null);
    }

    public static AcpRuntimeEvent done(String stopReason) {
        return new AcpRuntimeEvent(EventType.DONE, null,
                Map.of("stopReason", stopReason));
    }

    public static AcpRuntimeEvent error(String errorMessage) {
        return new AcpRuntimeEvent(EventType.ERROR, errorMessage, null);
    }

    // ===== Getters =====

    public EventType getType() { return type; }
    public String getData() { return data; }
    public Map<String, Object> getMetadata() { return metadata; }

    public boolean isTextDelta() { return type == EventType.TEXT_DELTA; }
    public boolean isToolCall() { return type == EventType.TOOL_CALL; }
    public boolean isDone() { return type == EventType.DONE; }
    public boolean isError() { return type == EventType.ERROR; }
}
```

### 1.4.6 Supporting Types

```java
package lyjew.com.lyclaw.react;

import java.util.Map;

/**
 * Input for {@link AcpRuntime#ensureSession(AcpRuntimeEnsureInput)}.
 */
public class AcpRuntimeEnsureInput {
    private String agentId;
    private String sessionKey;
    private String backend;        // which backend implementation to use
    private String workspaceDir;
    private Map<String, Object> env;
    private Map<String, Object> extra;  // backend-specific options

    // constructor, getters, setters omitted for brevity
    public AcpRuntimeEnsureInput() {}

    public String getAgentId() { return agentId; }
    public void setAgentId(String v) { this.agentId = v; }
    public String getSessionKey() { return sessionKey; }
    public void setSessionKey(String v) { this.sessionKey = v; }
    public String getBackend() { return backend; }
    public void setBackend(String v) { this.backend = v; }
    public String getWorkspaceDir() { return workspaceDir; }
    public void setWorkspaceDir(String v) { this.workspaceDir = v; }
    public Map<String, Object> getEnv() { return env; }
    public void setEnv(Map<String, Object> v) { this.env = v; }
    public Map<String, Object> getExtra() { return extra; }
    public void setExtra(Map<String, Object> v) { this.extra = v; }
}
```

```java
package lyjew.com.lyclaw.react;

import java.util.Map;

/**
 * Input for {@link AcpRuntime#startTurn(AcpRuntimeTurnInput)}.
 */
public class AcpRuntimeTurnInput {
    private AcpRuntimeHandle handle;
    private String userMessage;
    private String systemPrompt;
    private Map<String, Object> context;  // additional context

    // getters/setters
    public AcpRuntimeHandle getHandle() { return handle; }
    public void setHandle(AcpRuntimeHandle v) { this.handle = v; }
    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String v) { this.userMessage = v; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String v) { this.systemPrompt = v; }
    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> v) { this.context = v; }
}
```

```java
package lyjew.com.lyclaw.react;

import java.util.List;
import java.util.Map;

/**
 * Backend capabilities reported by an ACP runtime.
 */
public class AcpRuntimeCapabilities {
    private String modelProvider;
    private String modelName;
    private List<String> availableTools;
    private Map<String, Object> features;  // arbitrary feature flags

    // getters/setters
    public String getModelProvider() { return modelProvider; }
    public void setModelProvider(String v) { this.modelProvider = v; }
    public String getModelName() { return modelName; }
    public void setModelName(String v) { this.modelName = v; }
    public List<String> getAvailableTools() { return availableTools; }
    public void setAvailableTools(List<String> v) { this.availableTools = v; }
    public Map<String, Object> getFeatures() { return features; }
    public void setFeatures(Map<String, Object> v) { this.features = v; }
}
```

```java
package lyjew.com.lyclaw.react;

/**
 * Result of a completed ACP turn.
 */
public class AcpRuntimeTurnResult {

    public enum Status {
        COMPLETED,   // turn finished normally
        CANCELLED,   // turn was cancelled by user or system
        FAILED       // turn failed with an error
    }

    private final Status status;
    private final String stopReason;
    private final String error;
    private final String fullText;  // accumulated text output

    public AcpRuntimeTurnResult(Status status, String stopReason, String error, String fullText) {
        this.status = status;
        this.stopReason = stopReason;
        this.error = error;
        this.fullText = fullText;
    }

    public Status getStatus() { return status; }
    public String getStopReason() { return stopReason; }
    public String getError() { return error; }
    public String getFullText() { return fullText; }
}
```

---

## 1.5 AgentProxyFactory Restructuring

### 1.5.1 Problem

The current `AgentProxyFactory` uses a telescoping constructor chain (5 constructors)
that bakes in `modelOverride`/`providerOverride` as flat strings. It has no awareness
of `AgentDefaultsConfig`, no `ResolvedAgentConfig` production, and no concept of
runtime-type selection.

### 1.5.2 Restructured AgentProxyFactory

```java
package lyjew.com.lyclaw.react;

import java.lang.reflect.Proxy;
import java.util.List;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.config.AgentDefaultsConfig;
import lyjew.com.lyclaw.config.AgentConfigResolver;
import lyjew.com.lyclaw.config.ResolvedAgentConfig;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.tool.ToolRegistry;

/**
 * Agent proxy factory — creates JDK dynamic proxies for @Agent interfaces.
 *
 * <h3>Phase 1 changes:</h3>
 * <ul>
 *   <li>Accepts {@link AgentDefaultsConfig} in constructor</li>
 *   <li>{@code create(Class)} reads @Agent annotation → resolves against defaults
 *       → produces {@link ResolvedAgentConfig}</li>
 *   <li>Passes ResolvedAgentConfig to AgentInvocationHandler</li>
 *   <li>Supports creating agent proxies with different runtime types
 *       (EMBEDDED vs ACP)</li>
 * </ul>
 */
public class AgentProxyFactory {

    private final ChatFacade chatFacade;
    private final ReActEngine reActEngine;
    private final ToolRegistry toolRegistry;
    private final AgentConfigResolver configResolver;
    private final String defaultSystemPrompt;
    private final List<AgentHook> hooks;
    private final List<ReactivePipelineStage> stages;
    private final HookRegistry hookRegistry;

    /**
     * Primary constructor — accepts the full dependency set.
     *
     * @param chatFacade        Chat facade for LLM calls
     * @param reActEngine       ReAct engine for EMBEDDED runtime
     * @param toolRegistry      Tool registry
     * @param configResolver    Agent config resolver (with defaults loaded)
     * @param defaultSystemPrompt Fallback system prompt when none is specified
     * @param hooks             Global agent hooks (applied to all agents)
     * @param stages            Pipeline stages
     * @param hookRegistry      Hook registry for new-style hook dispatch
     */
    public AgentProxyFactory(ChatFacade chatFacade, ReActEngine reActEngine,
                             ToolRegistry toolRegistry,
                             AgentConfigResolver configResolver,
                             String defaultSystemPrompt,
                             List<AgentHook> hooks,
                             List<ReactivePipelineStage> stages,
                             HookRegistry hookRegistry) {
        this.chatFacade = chatFacade;
        this.reActEngine = reActEngine;
        this.toolRegistry = toolRegistry;
        this.configResolver = configResolver;
        this.defaultSystemPrompt = defaultSystemPrompt;
        this.hooks = hooks != null ? List.copyOf(hooks) : List.of();
        this.stages = stages != null ? List.copyOf(stages) : List.of();
        this.hookRegistry = hookRegistry;
    }

    /**
     * Backward-compatible constructor — no standalone config resolver.
     * Creates an inline resolver from the provided defaults.
     */
    public AgentProxyFactory(ChatFacade chatFacade, ReActEngine reActEngine,
                             ToolRegistry toolRegistry,
                             AgentDefaultsConfig defaults,
                             String defaultSystemPrompt,
                             List<AgentHook> hooks,
                             List<ReactivePipelineStage> stages) {
        this(chatFacade, reActEngine, toolRegistry,
                new AgentConfigResolver(defaults),
                defaultSystemPrompt, hooks, stages, new HookRegistry());
    }

    /**
     * Minimal backward-compatible constructor (no defaults, no hooks, no stages).
     */
    public AgentProxyFactory(ChatFacade chatFacade, ReActEngine reActEngine,
                             ToolRegistry toolRegistry) {
        this(chatFacade, reActEngine, toolRegistry,
                new AgentDefaultsConfig(), null, List.of(), List.of());
    }

    /**
     * Create a dynamic proxy for the given @Agent interface.
     *
     * <p>Resolution flow:
     * <ol>
     *   <li>Read @Agent annotation from the interface</li>
     *   <li>Extract agentId, model, provider from annotation</li>
     *   <li>Register agent with configResolver (if not already)</li>
     *   <li>resolveAgentConfig(agentId) → ResolvedAgentConfig</li>
     *   <li>Use resolved model/provider (annotation overrides defaults)</li>
     *   <li>Determine runtimeType from resolved config or system property</li>
     *   <li>Build AgentInvocationHandler with ResolvedAgentConfig</li>
     *   <li>Return proxy</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> agentInterface) {
        if (chatFacade == null) {
            throw new IllegalStateException("ChatFacade must not be null");
        }

        Agent ann = agentInterface.getAnnotation(Agent.class);

        String agentId = resolveAgentId(agentInterface, ann);

        // Resolve system prompt: annotation override > default
        String systemPrompt = defaultSystemPrompt;
        if (ann != null && !ann.description().isEmpty() && defaultSystemPrompt == null) {
            systemPrompt = ann.description();
        }
        if (ann != null && !ann.systemPromptOverride().isEmpty()) {
            systemPrompt = ann.systemPromptOverride();
        }

        // Register agent with config resolver and resolve full config
        if (ann != null) {
            configResolver.registerAgent(agentId, ann);
        }
        ResolvedAgentConfig resolvedConfig = configResolver.resolveAgentConfig(agentId);

        // Model/provider: annotation overrides defaults
        String model = resolvedConfig.getModel();
        String provider = resolvedConfig.getProvider();

        // Determine runtime type
        AgentContext.AgentRuntimeType runtimeType = resolveRuntimeType(resolvedConfig);

        AgentInvocationHandler handler = new AgentInvocationHandler(
                chatFacade, reActEngine, toolRegistry,
                systemPrompt, model, provider,
                hooks, stages, resolvedConfig, hookRegistry, runtimeType);

        return (T) Proxy.newProxyInstance(
                agentInterface.getClassLoader(),
                new Class<?>[]{agentInterface},
                handler);
    }

    /**
     * Create a proxy with explicit runtime type override.
     */
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> agentInterface, AgentContext.AgentRuntimeType runtimeType) {
        T proxy = create(agentInterface);
        // The handler stores the runtimeType; we could also pass it through
        // a setter on the handler after creation
        return proxy;
    }

    // ===== Private helpers =====

    private String resolveAgentId(Class<?> agentInterface, Agent ann) {
        if (ann != null && !ann.id().isEmpty()) {
            return ann.id();
        }
        if (ann != null && !ann.name().isEmpty()) {
            return ann.name();
        }
        String simpleName = agentInterface.getSimpleName();
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    private AgentContext.AgentRuntimeType resolveRuntimeType(ResolvedAgentConfig config) {
        // Check system property override
        String sysProp = System.getProperty("lyclaw.agent.runtime");
        if ("acp".equalsIgnoreCase(sysProp)) {
            return AgentContext.AgentRuntimeType.ACP;
        }
        // Check config extension
        String extVal = config.getExtensions().get("runtimeType");
        if ("acp".equalsIgnoreCase(extVal)) {
            return AgentContext.AgentRuntimeType.ACP;
        }
        return AgentContext.AgentRuntimeType.EMBEDDED;
    }
}
```

### 1.5.3 Updated AgentInterfaceProcessor (FactoryBean)

The `AgentProxyFactoryBean` inner class in `AgentInterfaceProcessor` needs a minor
update to resolve the `AgentProxyFactory` bean and call the new `create()` signature:

```java
// Inside AgentInterfaceProcessor.AgentProxyFactoryBean:

@Override
public Object getObject() {
    DefaultListableBeanFactory registry =
            (DefaultListableBeanFactory) LazyBeanFactoryHolder.getBeanFactory();
    if (registry == null) {
        throw new IllegalStateException(
                "BeanFactory not available for @Agent proxy: " + agentInterface.getName());
    }
    AgentProxyFactory factory = registry.getBean(AgentProxyFactory.class);

    // Phase 1 change: create() now internally resolves config and passes it to handler
    Object proxy = factory.create(agentInterface);

    String beanName = resolveBeanName();
    registry.destroySingleton(beanName);
    registry.registerSingleton(beanName, proxy);
    return proxy;
}
```

### 1.5.4 Updated Autoconfiguration

```java
package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import lyjew.com.lyclaw.autoconfigure.processor.AgentInterfaceProcessor;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.config.AgentDefaultsConfig;
import lyjew.com.lyclaw.config.AgentConfigResolver;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.react.AgentHook;
import lyjew.com.lyclaw.react.AgentProxyFactory;
import lyjew.com.lyclaw.react.HookRegistry;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.tool.ToolRegistry;

@AutoConfiguration
@AutoConfigureAfter({ChatAutoConfiguration.class, ReActAutoConfiguration.class, ToolAutoConfiguration.class})
@ConditionalOnClass({ReActEngine.class, ToolRegistry.class, ChatFacade.class})
@EnableConfigurationProperties(AgentDefaultsConfig.class)
public class AgentProxyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentConfigResolver.class)
    public AgentConfigResolver agentConfigResolver(AgentDefaultsConfig defaults) {
        return new AgentConfigResolver(defaults);
    }

    @Bean
    @ConditionalOnMissingBean(HookRegistry.class)
    public HookRegistry hookRegistry() {
        return new HookRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(AgentProxyFactory.class)
    public AgentProxyFactory agentProxyFactory(
            ChatFacade chatFacade,
            ReActEngine reActEngine,
            ToolRegistry toolRegistry,
            AgentConfigResolver configResolver,
            HookRegistry hookRegistry,
            List<AgentHook> hooks,
            List<ReactivePipelineStage> stages) {
        List<AgentHook> hookList = hooks != null ? hooks : List.of();
        List<ReactivePipelineStage> pipelineStages = stages != null ? stages : List.of();
        return new AgentProxyFactory(chatFacade, reActEngine, toolRegistry,
                configResolver, null, hookList, pipelineStages, hookRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(AgentInterfaceProcessor.class)
    public static AgentInterfaceProcessor agentInterfaceProcessor() {
        return new AgentInterfaceProcessor();
    }
}
```

---

## Summary: Phase 1 Deliverables

| # | Component | Change | Impact |
|---|---|---|---|
| 1.1a | `@Agent` annotation | 6 → ~30 fields | Typed, discoverable agent config |
| 1.1b | `AgentDefaultsConfig` | New class | Global defaults from `application.yml` |
| 1.1c | `AgentSystemDefaults` | New class | Hard-coded fallback constants |
| 1.1d | `ResolvedAgentConfig` | New immutable class | Output of 3-layer deep merge |
| 1.1e | `AgentConfigResolver` | Enhanced | resolveAgentConfig, listAgentIds, workspace dirs |
| 1.2 | `AgentContext` | +15 new fields + enhanced snapshot/restore | Rich runtime data bus |
| 1.3a | `AgentHook` | 5 → 36 methods | Full lifecycle coverage |
| 1.3b | `AgentFinalizeResult` | New class | CONTINUE/REVISE/FINALIZE gate |
| 1.3c | `HookDecision` | New class | PASS/BLOCK with reason + metadata |
| 1.3d | `HookRegistration` | New record | Typed hook registry entry |
| 1.3e | `HookRegistry` | New class | Register, dispatch, unregister hooks |
| 1.4a | `AgentRuntimeType` | New enum | EMBEDDED / ACP |
| 1.4b | `AcpRuntime` | New interface | ensureSession, startTurn, cancel, close |
| 1.4c | `AcpRuntimeHandle/Event/...` | New types | ACP protocol data objects |
| 1.5 | `AgentProxyFactory` | Restructured | Config-aware, runtime-type support |

### Backward Compatibility

- Existing `@Agent` annotation fields (`name`, `description`, `version`, `model`,
  `provider`, `extensions`) are unchanged — all new fields have sensible defaults.
- Existing `AgentHook` methods are kept as-is — new methods are `default` (no-op).
- `AgentContext` constructor overloads maintain the old signature alongside the new
  one that accepts `ResolvedAgentConfig`.
- `AgentProxyFactory` retains backward-compatible constructors.
- `LyClawAgent.Builder` continues to work for non-Spring environments.
