package lyjew.com.lyclaw.react.subagent;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime configuration for subagent spawning, built by merging three layers
 * (lowest to highest priority):
 * <ol>
 *   <li>Framework hardcoded defaults ({@link #defaults()})</li>
 *   <li>Global {@code lyclaw.subagent.*} keys in {@code application.yml}</li>
 *   <li>Per-agent {@code @Agent} annotation extension fields</li>
 * </ol>
 *
 * <p>Use {@link #merge(SubagentConfig)} to fold a higher-priority config onto a
 * lower-priority one. Only fields that differ from the hardcoded defaults are
 * carried forward, giving each layer the ability to selectively override.</p>
 *
 * <h3>Typical usage</h3>
 * <pre>{@code
 * SubagentConfig config = SubagentConfig.defaults()
 *         .merge(yamlConfig)
 *         .merge(annotationConfig);
 * }</pre>
 */
public class SubagentConfig {

    // ── Delegation mode ────────────────────────────────────────
    /** Delegation mode: {@code suggest} (recommend, user confirms),
     *  {@code prefer} (delegate eagerly), or {@code off} (disabled). */
    private String delegationMode = "suggest";

    // ── Allow-list ─────────────────────────────────────────────
    /** Allowed target agent IDs. Empty list disables subagent spawning
     *  entirely. {@code "*"} permits any registered agent. */
    private List<String> allowAgents = List.of("*");

    // ── Concurrency & depth ────────────────────────────────────
    /** Maximum concurrent child agents per parent (semaphore ceiling). */
    private int maxConcurrent = 1;

    /** Maximum spawn depth. {@code 1} means no recursive spawning
     *  (children cannot spawn grandchildren). */
    private int maxSpawnDepth = 1;

    /** Maximum direct children a single parent agent may spawn. */
    private int maxChildrenPerAgent = 5;

    // ── Archival ───────────────────────────────────────────────
    /** Minutes after completion before a child session is archived. */
    private int archiveAfterMinutes = 60;

    // ── Model overrides ────────────────────────────────────────
    /** Optional model name override ({@code null} = use child's own config). */
    private String model;

    /** Optional thinking level override ({@code null} = use child's own config). */
    private String thinking;

    // ── Timeouts ───────────────────────────────────────────────
    /** Hard timeout in seconds for the entire subagent run. */
    private int runTimeoutSeconds = 300;

    /** Timeout in milliseconds for the subagent announce phase. */
    private int announceTimeoutMs = 120_000;

    // ── Safety ─────────────────────────────────────────────────
    /** When {@code true}, the child agent must declare an {@code agentId}
     *  explicitly; when {@code false} the framework may auto-assign one. */
    private boolean requireAgentId = false;

    // ================================================================
    // Constructors
    // ================================================================

    public SubagentConfig() {
    }

    private SubagentConfig(Builder builder) {
        this.delegationMode = builder.delegationMode;
        this.allowAgents = builder.allowAgents;
        this.maxConcurrent = builder.maxConcurrent;
        this.maxSpawnDepth = builder.maxSpawnDepth;
        this.maxChildrenPerAgent = builder.maxChildrenPerAgent;
        this.archiveAfterMinutes = builder.archiveAfterMinutes;
        this.model = builder.model;
        this.thinking = builder.thinking;
        this.runTimeoutSeconds = builder.runTimeoutSeconds;
        this.announceTimeoutMs = builder.announceTimeoutMs;
        this.requireAgentId = builder.requireAgentId;
    }

    // ================================================================
    // Static factories
    // ================================================================

    /** Returns a new instance populated with every hardcoded default.
     *  This is the starting point for a config merge chain. */
    public static SubagentConfig defaults() {
        return new SubagentConfig();
    }

    /** Returns a new fluent {@link Builder}. */
    public static Builder builder() {
        return new Builder();
    }

    // ================================================================
    // Merge
    // ================================================================

    /**
     * Merge {@code other} onto this config, returning a new instance.
     *
     * <p>A field from {@code other} is applied only when it differs from the
     * hardcoded default (as returned by {@link #defaults()}). Fields that
     * equal their default are treated as "not set" and the current value
     * on {@code this} is kept.</p>
     *
     * <p>Special rules:</p>
     * <ul>
     *   <li>{@code allowAgents} — an empty or {@code null} list is ignored;
     *       a list equal to {@code List.of("*")} is also treated as default.</li>
     *   <li>Numeric fields — values &le; 0 are ignored (treated as unset).</li>
     *   <li>Nullable string fields ({@code model}, {@code thinking}) —
     *       {@code null} is ignored.</li>
     *   <li>{@code requireAgentId} — only overrides when different from default.</li>
     * </ul>
     *
     * @param other higher-priority config source (may be {@code null})
     * @return merged config
     */
    public SubagentConfig merge(SubagentConfig other) {
        if (other == null) {
            return this;
        }

        SubagentConfig def = defaults();
        SubagentConfig merged = new SubagentConfig();

        // delegationMode
        merged.delegationMode = isNotDefault(other.delegationMode, def.delegationMode)
                ? other.delegationMode : this.delegationMode;

        // allowAgents
        if (other.allowAgents != null && !other.allowAgents.isEmpty()
                && !other.allowAgents.equals(def.allowAgents)) {
            merged.allowAgents = new ArrayList<>(other.allowAgents);
        } else {
            merged.allowAgents = new ArrayList<>(this.allowAgents);
        }

        // numeric (treat <= 0 as unset)
        merged.maxConcurrent = other.maxConcurrent > 0 ? other.maxConcurrent : this.maxConcurrent;
        merged.maxSpawnDepth = other.maxSpawnDepth > 0 ? other.maxSpawnDepth : this.maxSpawnDepth;
        merged.maxChildrenPerAgent = other.maxChildrenPerAgent > 0
                ? other.maxChildrenPerAgent : this.maxChildrenPerAgent;
        merged.archiveAfterMinutes = other.archiveAfterMinutes > 0
                ? other.archiveAfterMinutes : this.archiveAfterMinutes;
        merged.runTimeoutSeconds = other.runTimeoutSeconds > 0
                ? other.runTimeoutSeconds : this.runTimeoutSeconds;
        merged.announceTimeoutMs = other.announceTimeoutMs > 0
                ? other.announceTimeoutMs : this.announceTimeoutMs;

        // nullable strings
        merged.model = other.model != null ? other.model : this.model;
        merged.thinking = other.thinking != null ? other.thinking : this.thinking;

        // boolean
        merged.requireAgentId = other.requireAgentId != def.requireAgentId
                ? other.requireAgentId : this.requireAgentId;

        return merged;
    }

    private static boolean isNotDefault(String value, String defaultValue) {
        return value != null && !value.equals(defaultValue);
    }

    // ================================================================
    // Getters / Setters
    // ================================================================

    public String getDelegationMode() { return delegationMode; }
    public void setDelegationMode(String delegationMode) { this.delegationMode = delegationMode; }

    public List<String> getAllowAgents() { return allowAgents; }
    public void setAllowAgents(List<String> allowAgents) { this.allowAgents = allowAgents; }

    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }

    public int getMaxSpawnDepth() { return maxSpawnDepth; }
    public void setMaxSpawnDepth(int maxSpawnDepth) { this.maxSpawnDepth = maxSpawnDepth; }

    public int getMaxChildrenPerAgent() { return maxChildrenPerAgent; }
    public void setMaxChildrenPerAgent(int maxChildrenPerAgent) { this.maxChildrenPerAgent = maxChildrenPerAgent; }

    public int getArchiveAfterMinutes() { return archiveAfterMinutes; }
    public void setArchiveAfterMinutes(int archiveAfterMinutes) { this.archiveAfterMinutes = archiveAfterMinutes; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getThinking() { return thinking; }
    public void setThinking(String thinking) { this.thinking = thinking; }

    public int getRunTimeoutSeconds() { return runTimeoutSeconds; }
    public void setRunTimeoutSeconds(int runTimeoutSeconds) { this.runTimeoutSeconds = runTimeoutSeconds; }

    public int getAnnounceTimeoutMs() { return announceTimeoutMs; }
    public void setAnnounceTimeoutMs(int announceTimeoutMs) { this.announceTimeoutMs = announceTimeoutMs; }

    public boolean isRequireAgentId() { return requireAgentId; }
    public void setRequireAgentId(boolean requireAgentId) { this.requireAgentId = requireAgentId; }

    // ================================================================
    // Builder
    // ================================================================

    /** Fluent builder for {@link SubagentConfig}. */
    public static class Builder {
        private String delegationMode = "suggest";
        private List<String> allowAgents = List.of("*");
        private int maxConcurrent = 1;
        private int maxSpawnDepth = 1;
        private int maxChildrenPerAgent = 5;
        private int archiveAfterMinutes = 60;
        private String model;
        private String thinking;
        private int runTimeoutSeconds = 300;
        private int announceTimeoutMs = 120_000;
        private boolean requireAgentId = false;

        public Builder delegationMode(String delegationMode) { this.delegationMode = delegationMode; return this; }
        public Builder allowAgents(List<String> allowAgents) { this.allowAgents = allowAgents; return this; }
        public Builder maxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; return this; }
        public Builder maxSpawnDepth(int maxSpawnDepth) { this.maxSpawnDepth = maxSpawnDepth; return this; }
        public Builder maxChildrenPerAgent(int maxChildrenPerAgent) { this.maxChildrenPerAgent = maxChildrenPerAgent; return this; }
        public Builder archiveAfterMinutes(int archiveAfterMinutes) { this.archiveAfterMinutes = archiveAfterMinutes; return this; }
        public Builder model(String model) { this.model = model; return this; }
        public Builder thinking(String thinking) { this.thinking = thinking; return this; }
        public Builder runTimeoutSeconds(int runTimeoutSeconds) { this.runTimeoutSeconds = runTimeoutSeconds; return this; }
        public Builder announceTimeoutMs(int announceTimeoutMs) { this.announceTimeoutMs = announceTimeoutMs; return this; }
        public Builder requireAgentId(boolean requireAgentId) { this.requireAgentId = requireAgentId; return this; }

        public SubagentConfig build() {
            return new SubagentConfig(this);
        }
    }
}
