package lyjew.com.lyclaw.react;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Typed runtime metadata for an agent invocation, extracted from AgentContext.
 *
 * <p>Tracks subagent spawning hierarchy, model resolution overrides,
 * thinking/reasoning configuration, and archiving state. Replaces the
 * unstructured {@code Map<String, Object> runMetadataMap} with strongly-typed
 * fields while retaining the Map for extensible key-value storage.</p>
 *
 * <h3>Subagent hierarchy</h3>
 * <ul>
 *   <li>Root agents have {@code subagentDepth == 0} and {@code parentSessionKey == null}.</li>
 *   <li>Child agents have {@code subagentDepth > 0} indicating their position in the spawn tree.</li>
 *   <li>{@link #activeSubagentIds} tracks session keys of currently-running direct children.</li>
 * </ul>
 */
public class RunMetadata {

    /** Depth in the subagent spawn tree (0 = root agent). */
    private int subagentDepth = 0;

    /** Session key of the parent agent (null for root agents). */
    private String parentSessionKey;

    /** The agent ID this subagent was spawned as (null for root agents). */
    private String subagentTargetAgentId;

    /** Session keys of currently active direct child subagents. */
    private final Set<String> activeSubagentIds = ConcurrentHashMap.newKeySet();

    /**
     * Thinking level override.
     * Valid values: {@code "off"}, {@code "low"}, {@code "medium"}, {@code "high"}.
     * {@code null} means use the model default.
     */
    private String thinkingLevel;

    /** Verbose level override (null = use model default). */
    private String verboseLevel;

    /** Reasoning level override (null = use model default). */
    private String reasoningLevel;

    /** Resolved model name (from config + defaults). */
    private String resolvedModel;

    /** Resolved provider name. */
    private String resolvedProvider;

    /** Image model override (specifically for image understanding tasks). */
    private String imageModel;

    /** Archive session key (for session archival/restore). */
    private String archiveSessionKey;

    // ========================================================================
    // Getters and Setters
    // ========================================================================

    public int getSubagentDepth() {
        return subagentDepth;
    }

    public void setSubagentDepth(int subagentDepth) {
        this.subagentDepth = subagentDepth;
    }

    public String getParentSessionKey() {
        return parentSessionKey;
    }

    public void setParentSessionKey(String parentSessionKey) {
        this.parentSessionKey = parentSessionKey;
    }

    public String getSubagentTargetAgentId() {
        return subagentTargetAgentId;
    }

    public void setSubagentTargetAgentId(String subagentTargetAgentId) {
        this.subagentTargetAgentId = subagentTargetAgentId;
    }

    public Set<String> getActiveSubagentIds() {
        return activeSubagentIds;
    }

    public String getThinkingLevel() {
        return thinkingLevel;
    }

    public void setThinkingLevel(String thinkingLevel) {
        this.thinkingLevel = thinkingLevel;
    }

    public String getVerboseLevel() {
        return verboseLevel;
    }

    public void setVerboseLevel(String verboseLevel) {
        this.verboseLevel = verboseLevel;
    }

    public String getReasoningLevel() {
        return reasoningLevel;
    }

    public void setReasoningLevel(String reasoningLevel) {
        this.reasoningLevel = reasoningLevel;
    }

    public String getResolvedModel() {
        return resolvedModel;
    }

    public void setResolvedModel(String resolvedModel) {
        this.resolvedModel = resolvedModel;
    }

    public String getResolvedProvider() {
        return resolvedProvider;
    }

    public void setResolvedProvider(String resolvedProvider) {
        this.resolvedProvider = resolvedProvider;
    }

    public String getImageModel() {
        return imageModel;
    }

    public void setImageModel(String imageModel) {
        this.imageModel = imageModel;
    }

    public String getArchiveSessionKey() {
        return archiveSessionKey;
    }

    public void setArchiveSessionKey(String archiveSessionKey) {
        this.archiveSessionKey = archiveSessionKey;
    }

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Returns {@code true} if this metadata belongs to a subagent
     * (has a parent session key or a depth greater than zero).
     */
    public boolean isSubagent() {
        return parentSessionKey != null || subagentDepth > 0;
    }

    /**
     * Returns {@code true} if this metadata belongs to a root agent
     * (depth 0 and no parent session key).
     */
    public boolean isRoot() {
        return subagentDepth == 0 && parentSessionKey == null;
    }

    /**
     * Creates a new {@code RunMetadata} for a root agent
     * (depth 0, no parent, no target agent).
     */
    public static RunMetadata root() {
        return new RunMetadata();
    }

    /**
     * Creates a child {@code RunMetadata} with depth incremented by one
     * from the parent. The caller must set {@link #parentSessionKey}
     * on the returned instance if the parent's session key is known.
     *
     * @param parent         the parent agent's metadata
     * @param childAgentId   the agent ID the child is being spawned as
     * @return a new RunMetadata instance with depth = parent.depth + 1
     */
    public static RunMetadata childOf(RunMetadata parent, String childAgentId) {
        RunMetadata child = new RunMetadata();
        child.subagentDepth = parent.subagentDepth + 1;
        child.subagentTargetAgentId = childAgentId;
        return child;
    }
}
