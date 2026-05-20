# LyClaw Agent Renovation Phase 2: Subagent Delegation System + Model Management Enhancement

## Table of Contents

1. [Background and Analysis](#1-background-and-analysis)
2. [2.1 Subagent Delegation System](#21-subagent-delegation-system)
   - [2.1.1 SubagentConfig](#211-subagentconfig)
   - [2.1.2 SubagentSpawner](#212-subagentspawner)
   - [2.1.3 Built-in delegate_to_agent Tool](#213-built-in-delegate_to_agent-tool)
   - [2.1.4 Delegation Flow](#214-delegation-flow)
   - [2.1.5 Subagent Session Management](#215-subagent-session-management)
   - [2.1.6 Concurrency Control](#216-concurrency-control)
   - [2.1.7 AgentContext Enhancements for Subagents](#217-agentcontext-enhancements-for-subagents)
   - [2.1.8 Agent Annotation Enhancements for Subagents](#218-agent-annotation-enhancements-for-subagents)
   - [2.1.9 Subagent Hook System](#219-subagent-hook-system)
   - [2.1.10 Subagent Error Handling & Timeout](#2110-subagent-error-handling--timeout)
   - [2.1.11 Configuration (application.yml)](#2111-configuration-applicationyml)
2. [2.2 Model Management Enhancement](#22-model-management-enhancement)
   - [2.2.1 Model Catalog](#221-model-catalog)
   - [2.2.2 Multi-Model Support in AgentDefaultsConfig](#222-multi-model-support-in-agentdefaultsconfig)
   - [2.2.3 Model Selection and Resolution](#223-model-selection-and-resolution)
   - [2.2.4 Thinking / Reasoning / Verbose Controls](#224-thinking--reasoning--verbose-controls)
   - [2.2.5 Provider Discovery](#225-provider-discovery)
   - [2.2.6 Model Fallback Chain Integration](#226-model-fallback-chain-integration)
   - [2.2.7 SSE Events for Thinking](#227-sse-events-for-thinking)
   - [2.2.8 ChatRequest and ChatModel Enhancements](#228-chatrequest-and-chatmodel-enhancements)
   - [2.2.9 Configuration (application.yml)](#229-configuration-applicationyml)
3. [Integration Points Summary](#3-integration-points-summary)
4. [Migration Path](#4-migration-path)

---

## 1. Background and Analysis

### 1.1 Current Architecture Gap

LyClaw currently has two parallel but disconnected worlds:

**World A — Multi-Agent Infrastructure (standalone, unused in core loop):**
- `AgentCoordinator`, `CollaborationHub`, `ConsensusEngine` — multi-agent orchestration
- `AgentCommProtocol`, `AgentChannel` — inter-agent communication
- `AgentRegistry`, `AgentHandle`, `AgentLifecycle` — agent lifecycle management
- `AgentSpec`, `AgentState`, `AgentTask` — agent description and task model
- `AgentPoolSnapshot`, `AutoScaler`, `ScalingDecision` — pool scaling
- `ExternalAgentAdapter`, `AgentCard`, `TaskStatus` — external agent bridging

These exist under `lyclaw-framework/src/main/java/lyjew/com/lyclaw/agent/` but are **never invoked** from the core agent pipeline. They are standalone abstractions designed for a hypothetical multi-agent world that the actual ReAct engine has no concept of.

**World B — Core Agent Loop (what actually runs):**
- `AgentInvocationHandler` → Stage Pipeline (`ContextBuildStage` → `SecurityCheckStage` → `PlanExecutionStage` → `RespondStage` → `ReflectionStage` → `MetricsStage`)
- `RespondStage` delegates to `ReActEngine.executeStream()` (specifically `DefaultReActEngine`)
- `ReActEngine` loops: LLM call → if tool_calls, execute tools via `ToolExecutor` → feed results back → repeat
- `ToolRegistry` provides tool definitions and execution. No "delegate to another agent" tool exists.

**Model Management (basic):**
- `ChatFacade` (implemented by `DefaultChatFacade`) wraps `ChatModelRegistry` + `ModelRouter`
- `FirstAvailableRouter` — always picks the first model from the first provider. No intelligence.
- Three decorators: `CircuitBreakerChatModel`, `FallbackChatModel`, `RetryChatModel`
- `ChatProperties` — YAML-based config with `defaultProvider`, `defaultModel`, `models` map
- `AgentConfig` — merged config from annotations/yml/DB with `model` and `provider` string fields
- `@Agent` annotation has `model()` and `provider()` string fields
- `ChatRequest` has `thinkingEnabled` (boolean) and `thinkingBudget` (Integer) — very basic
- `ModelCapabilities` — streaming, toolCalling, thinking, vision, promptCaching flags

### 1.2 Phase 2 Goals

1. **Integrate subagent delegation into the core agent loop** — when the LLM decides to delegate, a new agent session is spawned, runs its full pipeline independently, and returns the result as a tool observation to the parent.
2. **Enhance model management** — introduce a model catalog, multi-model support (image, audio, video generation models), thinking/reasoning level controls, provider discovery, and model aliases.

---

## 2.1 Subagent Delegation System

### 2.1.1 SubagentConfig

```java
package lyjew.com.lyclaw.react.subagent;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Configuration for subagent spawning, merged from:
 * <ol>
 *   <li>Hardcoded defaults (this class's static defaults)</li>
 *   <li>application.yml (lyclaw.subagent.*)</li>
 *   <li>@Agent annotation extensions (e.g., "subagent.maxConcurrent")</li>
 * </ol>
 *
 * <p>Each parent agent carries a SubagentConfig that governs what children
 * it can spawn and how. When spawnSubagent() is called, the child agent's
 * own @Agent annotation config is resolved first, then overlaid by the
 * parent's SubagentConfig for safety limits (maxSpawnDepth, maxConcurrent
 * are always bounded by parent settings).</p>
 */
public class SubagentConfig {

    // ── Delegation mode ──

    /**
     * Delegation mode for this agent:
     * <ul>
     *   <li>"suggest" — the LLM is told it <i>may</i> delegate but is
     *       not required to. The tool definition includes a description
     *       suggesting optional delegation.</li>
     *   <li>"prefer" — the LLM is told it <i>should</i> delegate when
     *       applicable. The tool description and system prompt are
     *       adjusted to encourage delegation.</li>
     * </ul>
     */
    private String delegationMode = "suggest";

    /**
     * List of agent IDs that this parent is allowed to delegate to.
     * A single-element list with "*" means all registered agents.
     * An empty list disables delegation entirely.
     */
    private List<String> allowAgents = new ArrayList<>(List.of("*"));

    // ── Concurrency & depth ──

    /** Maximum concurrent sub-agent runs per parent agent. Default 1 (serial). */
    private int maxConcurrent = 1;

    /**
     * Maximum spawn depth. 1 means a parent can spawn children but children
     * cannot spawn grandchildren (no recursive spawning). 2 means grandchildren
     * are allowed, and so on. The depth is tracked in
     * AgentContext.runMetadata.subagentDepth.
     */
    private int maxSpawnDepth = 1;

    /** Maximum number of active (not yet archived) children per parent agent. */
    private int maxChildrenPerAgent = 5;

    // ── Session lifecycle ──

    /** Auto-archive subagent sessions after this many minutes of inactivity. */
    private int archiveAfterMinutes = 60;

    // ── Model overrides for sub-agents ──

    /**
     * Optional model name to use for sub-agents. If null, the child agent's
     * own configured model (from @Agent annotation or yml) is used.
     */
    private String model;

    /**
     * Optional thinking/reasoning level for sub-agents.
     * Overrides the child agent's own thinking level.
     */
    private String thinking;

    // ── Timeouts ──

    /** Per-subagent run timeout in seconds. Default 300 (5 minutes). */
    private int runTimeoutSeconds = 300;

    /** Timeout for the parent to wait for a sub-agent's first announce (token). */
    private int announceTimeoutMs = 120_000;

    // ── Identity ──

    /**
     * When true, the parent LLM MUST specify a concrete agentId when calling
     * delegate_to_agent. When false, the parent can omit agentId and the
     * system will attempt auto-matching by capability/description.
     */
    private boolean requireAgentId = false;

    // ── Static defaults ──

    public static SubagentConfig defaults() {
        return new SubagentConfig();
    }

    public static Builder builder() {
        return new Builder();
    }

    // ── Getters / Setters ──

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

    /**
     * Merge another config into this one.  Non-default values from {@code other}
     * overwrite this config's values.  Used to overlay parent config onto child
     * defaults.
     */
    public SubagentConfig merge(SubagentConfig other) {
        if (other == null) return this;
        SubagentConfig merged = new SubagentConfig();
        merged.delegationMode = other.delegationMode != null ? other.delegationMode : this.delegationMode;
        merged.allowAgents = other.allowAgents != null && !other.allowAgents.isEmpty() ? other.allowAgents : this.allowAgents;
        merged.maxConcurrent = other.maxConcurrent > 0 ? other.maxConcurrent : this.maxConcurrent;
        merged.maxSpawnDepth = other.maxSpawnDepth > 0 ? other.maxSpawnDepth : this.maxSpawnDepth;
        merged.maxChildrenPerAgent = other.maxChildrenPerAgent > 0 ? other.maxChildrenPerAgent : this.maxChildrenPerAgent;
        merged.archiveAfterMinutes = other.archiveAfterMinutes > 0 ? other.archiveAfterMinutes : this.archiveAfterMinutes;
        merged.model = other.model != null ? other.model : this.model;
        merged.thinking = other.thinking != null ? other.thinking : this.thinking;
        merged.runTimeoutSeconds = other.runTimeoutSeconds > 0 ? other.runTimeoutSeconds : this.runTimeoutSeconds;
        merged.announceTimeoutMs = other.announceTimeoutMs > 0 ? other.announceTimeoutMs : this.announceTimeoutMs;
        merged.requireAgentId = other.requireAgentId;
        return merged;
    }

    // ── Builder ──

    public static class Builder {
        private final SubagentConfig config = new SubagentConfig();

        public Builder delegationMode(String mode) { config.delegationMode = mode; return this; }
        public Builder allowAgents(List<String> agents) { config.allowAgents = agents; return this; }
        public Builder allowAllAgents() { config.allowAgents = List.of("*"); return this; }
        public Builder maxConcurrent(int n) { config.maxConcurrent = n; return this; }
        public Builder maxSpawnDepth(int n) { config.maxSpawnDepth = n; return this; }
        public Builder maxChildrenPerAgent(int n) { config.maxChildrenPerAgent = n; return this; }
        public Builder archiveAfterMinutes(int m) { config.archiveAfterMinutes = m; return this; }
        public Builder model(String model) { config.model = model; return this; }
        public Builder thinking(String thinking) { config.thinking = thinking; return this; }
        public Builder runTimeoutSeconds(int s) { config.runTimeoutSeconds = s; return this; }
        public Builder announceTimeoutMs(int ms) { config.announceTimeoutMs = ms; return this; }
        public Builder requireAgentId(boolean v) { config.requireAgentId = v; return this; }
        public SubagentConfig build() { return config; }
    }
}
```

### 2.1.2 SubagentSpawner

This is the central orchestrator for spawning and running sub-agents. It is injected into the `ToolRegistry` (or a new `ToolProvider`) so that when the LLM invokes the `delegate_to_agent` tool, execution routes through this class.

```java
package lyjew.com.lyclaw.react.subagent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import lyjew.com.lyclaw.agent.AgentRegistry;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.config.AgentConfig;
import lyjew.com.lyclaw.config.AgentConfigResolver;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.AgentHook;
import lyjew.com.lyclaw.react.AgentInvocationHandler;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.react.ToolExecutor;
import lyjew.com.lyclaw.tool.ToolRegistry;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central orchestrator for spawning and managing sub-agent executions.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>LLM invokes {@code delegate_to_agent} tool → tool executor calls
 *       {@link #spawnSubagent(String, String, Map, AgentContext)}</li>
 *   <li>Validation: check allowAgents whitelist, depth limit, child count limit</li>
 *   <li>Resolve child agent config from AgentConfigResolver</li>
 *   <li>Build isolated AgentContext for the child</li>
 *   <li>Dispatch {@code subagentSpawning} hooks</li>
 *   <li>Run child's full pipeline (ContextBuild → ... → Metrics)</li>
 *   <li>Dispatch {@code subagentSpawned} and {@code subagentEnded} hooks</li>
 *   <li>Return {@link SubagentResult} to parent as tool observation</li>
 * </ol>
 *
 * <h3>Concurrency Model</h3>
 * <p>Each parent agent has a Semaphore(maxConcurrent) to limit concurrent
 * sub-agent runs. The depth is tracked in parent's
 * {@code ctx.runMetadata.subagentDepth}. Active children are tracked in
 * {@code ctx.runMetadata.activeSubagentIds}.</p>
 *
 * @see SubagentConfig
 * @see SubagentResult
 */
public class SubagentSpawner {

    private static final Logger log = LoggerFactory.getLogger(SubagentSpawner.class);

    private final ChatFacade chatFacade;
    private final ReActEngine reActEngine;
    private final ToolRegistry toolRegistry;
    private final AgentRegistry agentRegistry;
    private final AgentConfigResolver agentConfigResolver;
    private final List<ReactivePipelineStage> defaultStages;
    private final List<AgentHook> defaultHooks;

    /**
     * Per-parent-agent semaphore map for concurrency control.
     * Key = parent sessionKey.
     */
    private final Map<String, Semaphore> concurrencySemaphores = new ConcurrentHashMap<>();

    public SubagentSpawner(ChatFacade chatFacade, ReActEngine reActEngine,
                           ToolRegistry toolRegistry, AgentRegistry agentRegistry,
                           AgentConfigResolver agentConfigResolver,
                           List<ReactivePipelineStage> defaultStages,
                           List<AgentHook> defaultHooks) {
        this.chatFacade = chatFacade;
        this.reActEngine = reActEngine;
        this.toolRegistry = toolRegistry;
        this.agentRegistry = agentRegistry;
        this.agentConfigResolver = agentConfigResolver;
        this.defaultStages = defaultStages != null ? List.copyOf(defaultStages) : List.of();
        this.defaultHooks = defaultHooks != null ? List.copyOf(defaultHooks) : List.of();
    }

    /**
     * Spawn a sub-agent to execute the given task.
     *
     * <p>This method is typically called from the tool executor that backs the
     * {@code delegate_to_agent} built-in tool.</p>
     *
     * @param targetAgentId the agent ID to delegate to (may be null if
     *        requireAgentId is false and auto-matching is enabled)
     * @param task the natural-language task description for the sub-agent
     * @param options additional options from the tool call (e.g., mode override)
     * @param parentCtx the parent agent's context
     * @return a Mono that completes with the subagent's result
     */
    public Mono<SubagentResult> spawnSubagent(String targetAgentId, String task,
                                               Map<String, Object> options,
                                               AgentContext parentCtx) {
        Instant startTime = Instant.now();
        String parentSessionKey = parentCtx.getSessionId();

        // ── 1. Resolve parent's SubagentConfig ──
        SubagentConfig parentConfig = resolveSubagentConfig(parentCtx);

        // ── 2. Validate confinements ──
        // 2a. Check delegation is enabled (non-empty allowAgents)
        if (parentConfig.getAllowAgents().isEmpty()) {
            return Mono.just(SubagentResult.error("Delegation is disabled for this agent"));
        }

        // 2b. Check allowAgents whitelist
        if (!parentConfig.getAllowAgents().contains("*")
                && !parentConfig.getAllowAgents().contains(targetAgentId)) {
            return Mono.just(SubagentResult.error(
                    "Agent '" + targetAgentId + "' is not in the allowed delegation list. "
                    + "Allowed: " + parentConfig.getAllowAgents()));
        }

        // 2c. Check maxSpawnDepth
        int parentDepth = parentCtx.getRunMetadata().getSubagentDepth();
        if (parentDepth + 1 > parentConfig.getMaxSpawnDepth()) {
            return Mono.just(SubagentResult.error(
                    "Max spawn depth exceeded. Current depth: " + parentDepth
                    + ", max: " + parentConfig.getMaxSpawnDepth()));
        }

        // 2d. Check maxChildrenPerAgent
        Set<String> activeChildren = parentCtx.getRunMetadata().getActiveSubagentIds();
        if (activeChildren.size() >= parentConfig.getMaxChildrenPerAgent()) {
            return Mono.just(SubagentResult.error(
                    "Max children per agent exceeded. Active: " + activeChildren.size()
                    + ", max: " + parentConfig.getMaxChildrenPerAgent()));
        }

        // 2e. Concurrency semaphore
        Semaphore semaphore = concurrencySemaphores.computeIfAbsent(
                parentSessionKey, k -> new Semaphore(parentConfig.getMaxConcurrent()));

        return Mono.fromCallable(() -> {
            if (!semaphore.tryAcquire()) {
                return SubagentResult.error(
                        "Max concurrent sub-agents reached (" + parentConfig.getMaxConcurrent() + ")");
            }
            return null; // acquired, continue
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(earlyError -> {
            if (earlyError != null) {
                return Mono.just(earlyError);
            }
            try {
                return runSubagent(targetAgentId, task, options, parentCtx, parentConfig, startTime);
            } catch (Exception e) {
                semaphore.release();
                return Mono.just(SubagentResult.error("Subagent launch failed: " + e.getMessage()));
            }
        })
        .doFinally(signalType -> {
            // Always release the semaphore when done
            semaphore.release();
        });
    }

    /**
     * Core execution: build isolated AgentContext, run full pipeline, return result.
     */
    private Mono<SubagentResult> runSubagent(String targetAgentId, String task,
                                              Map<String, Object> options,
                                              AgentContext parentCtx,
                                              SubagentConfig parentConfig,
                                              Instant startTime) {
        String childAgentId = targetAgentId;
        String childSessionKey = parentCtx.getSessionId()
                + "/subagent/" + childAgentId + "/" + UUID.randomUUID().toString().substring(0, 8);

        // ── 3. Resolve child agent config ──
        AgentConfig childAgentConfig = agentConfigResolver.resolve(childAgentId);
        if (childAgentConfig.getName() == null) {
            return Mono.just(SubagentResult.error("Unknown agent: " + childAgentId));
        }

        // ── 4. Build isolated AgentContext for child ──
        // The child gets its own toolRegistry subset, session, and pipeline
        AgentContext childCtx = buildChildContext(childSessionKey, task, childAgentConfig, parentCtx);

        // Set subagent depth in run metadata
        childCtx.getRunMetadata().setSubagentDepth(
                parentCtx.getRunMetadata().getSubagentDepth() + 1);
        childCtx.getRunMetadata().setParentSessionKey(parentCtx.getSessionId());
        childCtx.getRunMetadata().setSubagentTargetAgentId(childAgentId);

        // Track in parent's active subagent set
        parentCtx.getRunMetadata().getActiveSubagentIds().add(childSessionKey);

        // ── 5. Dispatch subagentSpawning hooks ──
        dispatchHooks("subagentSpawning", childCtx, null);

        // ── 6. Run child's pipeline ──
        // Build a lightweight AgentInvocationHandler for the child.
        // The child runs the same pipeline stages but with its own context.
        AgentInvocationHandler childHandler = new AgentInvocationHandler(
                chatFacade, reActEngine, toolRegistry,
                childAgentConfig.getDescription(), // system prompt
                childAgentConfig.getModel(),
                childAgentConfig.getProvider(),
                defaultHooks,
                defaultStages
        );

        return Mono.fromCallable(() -> {
            try {
                // Execute the child's pipeline in blocking mode and collect result
                String result = childHandler.executeBlocking(childCtx);
                Duration elapsed = Duration.between(startTime, Instant.now());

                // ── 7. Build SubagentResult ──
                SubagentResult subagentResult = SubagentResult.success(
                        childSessionKey, childAgentId, result, elapsed.toMillis(),
                        childCtx.getSuccessCount().get(), childCtx.getFailCount().get());

                // ── 8. Dispatch subagentSpawned / subagentEnded hooks ──
                dispatchHooks("subagentSpawned", childCtx, subagentResult);
                dispatchHooks("subagentEnded", childCtx, subagentResult);

                return subagentResult;
            } catch (Exception e) {
                log.error("Subagent '{}' execution failed: {}", childAgentId, e.getMessage(), e);
                Duration elapsed = Duration.between(startTime, Instant.now());
                return SubagentResult.error("Subagent execution failed: " + e.getMessage());
            } finally {
                // Remove from active set
                parentCtx.getRunMetadata().getActiveSubagentIds().remove(childSessionKey);
                // Archive session if configured
                scheduleSessionArchive(childSessionKey, parentConfig.getArchiveAfterMinutes());
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .timeout(Duration.ofSeconds(parentConfig.getRunTimeoutSeconds()),
                 Mono.just(SubagentResult.error(
                         "Subagent timed out after " + parentConfig.getRunTimeoutSeconds() + "s")),
                 Schedulers.boundedElastic());
    }

    /**
     * Build an isolated AgentContext for the child subagent.
     */
    private AgentContext buildChildContext(String sessionKey, String task,
                                            AgentConfig childConfig,
                                            AgentContext parentCtx) {
        // The child gets a distinct sessionId and userMessage = the task.
        // The system prompt comes from the child's agent description.
        AgentContext childCtx = AgentContext.sessionScoped(
                sessionKey,
                task,  // user message = the delegating task
                childConfig.getDescription(),  // system prompt from child's @Agent
                toolRegistry,
                parentCtx.getMethod(),  // method is null/placeholder for subagents
                new Object[0]
        );

        // Build a ChatRequest with just the task message
        ChatRequest request = ChatRequest.builder()
                .sessionId(sessionKey)
                .messages(new java.util.ArrayList<>(List.of(Message.user(task))))
                .stream(true)
                .build();

        // If child config has model override, apply it
        if (childConfig.getModel() != null && !childConfig.getModel().isEmpty()) {
            request.setModel(childConfig.getModel());
        }

        // Set tools from the parent's tool registry (or a scoped subset)
        List<ToolDefinition> tools = toolRegistry.getAllDefinitions(request);
        request.setTools(tools);
        request.setToolChoice("auto");

        childCtx.setChatRequest(request);
        childCtx.setSandboxLevel(parentCtx.getSandboxLevel());

        // Set thinking level from child config
        String thinkingLevel = childConfig.getExtension("thinking.level", null);
        if (thinkingLevel != null) {
            childCtx.getRunMetadata().setThinkingLevel(thinkingLevel);
        }

        return childCtx;
    }

    /**
     * Resolve SubagentConfig from parent AgentContext.
     * Priority: AgentConfig extensions > application.yml > hardcoded defaults.
     */
    private SubagentConfig resolveSubagentConfig(AgentContext ctx) {
        SubagentConfig config = SubagentConfig.defaults();

        // Overlay from AgentContext attributes (set by AgentInvocationHandler
        // after resolving @Agent annotation extensions)
        @SuppressWarnings("unchecked")
        Map<String, String> extensions = ctx.getAttribute("agentExtensions");
        if (extensions != null) {
            if (extensions.containsKey("subagent.delegationMode"))
                config.setDelegationMode(extensions.get("subagent.delegationMode"));
            if (extensions.containsKey("subagent.allowAgents"))
                config.setAllowAgents(List.of(extensions.get("subagent.allowAgents").split(",")));
            if (extensions.containsKey("subagent.maxConcurrent"))
                config.setMaxConcurrent(Integer.parseInt(extensions.get("subagent.maxConcurrent")));
            if (extensions.containsKey("subagent.maxSpawnDepth"))
                config.setMaxSpawnDepth(Integer.parseInt(extensions.get("subagent.maxSpawnDepth")));
            if (extensions.containsKey("subagent.maxChildrenPerAgent"))
                config.setMaxChildrenPerAgent(Integer.parseInt(extensions.get("subagent.maxChildrenPerAgent")));
            if (extensions.containsKey("subagent.archiveAfterMinutes"))
                config.setArchiveAfterMinutes(Integer.parseInt(extensions.get("subagent.archiveAfterMinutes")));
            if (extensions.containsKey("subagent.model"))
                config.setModel(extensions.get("subagent.model"));
            if (extensions.containsKey("subagent.thinking"))
                config.setThinking(extensions.get("subagent.thinking"));
            if (extensions.containsKey("subagent.runTimeoutSeconds"))
                config.setRunTimeoutSeconds(Integer.parseInt(extensions.get("subagent.runTimeoutSeconds")));
        }

        return config;
    }

    /**
     * Dispatch lifecycle events to all registered hooks that implement SubagentHook.
     */
    private void dispatchHooks(String lifecycleEvent, AgentContext childCtx,
                                SubagentResult result) {
        for (AgentHook hook : defaultHooks) {
            if (hook instanceof SubagentHook subagentHook) {
                try {
                    switch (lifecycleEvent) {
                        case "subagentSpawning":
                            subagentHook.subagentSpawning(childCtx);
                            break;
                        case "subagentSpawned":
                            subagentHook.subagentSpawned(childCtx, result);
                            break;
                        case "subagentEnded":
                            subagentHook.subagentEnded(childCtx, result);
                            break;
                    }
                } catch (Exception e) {
                    log.warn("SubagentHook '{}' threw on {}: {}",
                            hook.getClass().getSimpleName(), lifecycleEvent, e.getMessage());
                }
            }
        }
    }

    private void scheduleSessionArchive(String sessionKey, int afterMinutes) {
        // Delegate to the session store to archive this session after inactivity.
        // Implementation: register a delayed task that checks if the session is
        // still active and, if not, moves it to cold storage.
        log.debug("Scheduled archive for subagent session {} after {} minutes",
                sessionKey, afterMinutes);
    }

    /**
     * Returns the tool definition for the built-in delegate_to_agent tool.
     * This is registered automatically by the framework.
     */
    public static ToolDefinition buildDelegateToolDefinition(SubagentConfig config) {
        // Build JSON Schema programmatically
        Map<String, Object> properties = new java.util.LinkedHashMap<>();

        // agentId parameter
        Map<String, Object> agentIdSchema = new java.util.LinkedHashMap<>();
        agentIdSchema.put("type", "string");
        agentIdSchema.put("description", "The ID of the specialized agent to delegate to");
        properties.put("agentId", agentIdSchema);

        // task parameter
        Map<String, Object> taskSchema = new java.util.LinkedHashMap<>();
        taskSchema.put("type", "string");
        taskSchema.put("description", "The detailed task description for the sub-agent");
        properties.put("task", taskSchema);

        // mode parameter (optional override)
        Map<String, Object> modeSchema = new java.util.LinkedHashMap<>();
        modeSchema.put("type", "string");
        modeSchema.put("enum", List.of("suggest", "prefer"));
        modeSchema.put("description", "Delegation mode override for this call");
        properties.put("mode", modeSchema);

        // Build full parameter schema
        Map<String, Object> parameters = new java.util.LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);

        // Required fields depend on config
        List<String> required = new java.util.ArrayList<>();
        required.add("task");
        if (config.isRequireAgentId()) {
            required.add("agentId");
        }
        parameters.put("required", required);

        // Build function definition
        Map<String, Object> function = new java.util.LinkedHashMap<>();
        function.put("name", "delegate_to_agent");
        function.put("description",
                config.getDelegationMode().equals("prefer")
                        ? "Delegate a task to another specialized agent. "
                          + "You SHOULD use this whenever another agent specializes in the task."
                        : "Delegate a task to another specialized agent. "
                          + "You may use this when another agent specializes in the task.");
        function.put("parameters", parameters);

        return ToolDefinition.builder()
                .name("delegate_to_agent")
                .type("function")
                .function(function)
                .build();
    }
}
```

### 2.1.3 Built-in delegate_to_agent Tool

The `delegate_to_agent` tool is registered as a built-in tool via a `ToolProvider`, not a static `@Tool` annotation, because it needs runtime access to the `AgentContext` (which static tools don't have).

```java
package lyjew.com.lyclaw.react.subagent;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lyjew.com.lyclaw.tool.ToolProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * ToolProvider that injects the built-in {@code delegate_to_agent} tool into
 * every agent's tool set. This is how the LLM discovers subagent delegation.
 *
 * <p>When the LLM calls this tool, execution routes through the
 * {@link SubagentSpawner}, which spawns a new agent session, runs it to
 * completion, and returns the result as the tool's output.</p>
 */
public class DelegateToAgentToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(DelegateToAgentToolProvider.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final SubagentSpawner spawner;
    private final boolean enabled;

    public DelegateToAgentToolProvider(SubagentSpawner spawner, boolean enabled) {
        this.spawner = spawner;
        this.enabled = enabled;
    }

    @Override
    public boolean isEnabled(ChatRequest request) {
        return enabled;
    }

    @Override
    public List<ToolDefinition> getDefinitions(ChatRequest request) {
        if (!enabled) return List.of();
        // Build the tool definition dynamically based on the parent agent's config
        SubagentConfig config = SubagentConfig.defaults(); // will be resolved from context at runtime
        return List.of(SubagentSpawner.buildDelegateToolDefinition(config));
    }

    @Override
    public ToolExecutionResult execute(ToolCall toolCall, ChatRequest request, Object context) {
        if (!"delegate_to_agent".equals(toolCall.getName())) {
            return ToolExecutionResult.error("Unknown tool: " + toolCall.getName());
        }

        if (!(context instanceof ToolProviderContext ctx)) {
            return ToolExecutionResult.error("Missing ToolProviderContext");
        }

        AgentContext agentCtx = ctx.getAgentContext();

        // Parse arguments
        Map<String, Object> args;
        try {
            if (toolCall.getArguments() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) toolCall.getArguments();
                args = m;
            } else {
                String argsStr = toolCall.getArguments() != null
                        ? toolCall.getArguments().toString() : "{}";
                args = objectMapper.readValue(argsStr,
                        new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            return ToolExecutionResult.error("Failed to parse delegate_to_agent arguments: " + e.getMessage());
        }

        String targetAgentId = (String) args.getOrDefault("agentId", "");
        String task = (String) args.get("task");
        if (task == null || task.isEmpty()) {
            return ToolExecutionResult.error("Task is required for delegate_to_agent");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) args.getOrDefault("options", Map.of());

        // Execute synchronously (blocking) because tool execution is synchronous
        // in the current ReAct loop. The spawner internally uses reactive types
        // but we block here for compatibility.
        try {
            SubagentResult result = spawner.spawnSubagent(targetAgentId, task, options, agentCtx)
                    .block(java.time.Duration.ofSeconds(spawner.resolveSubagentConfig(agentCtx).getRunTimeoutSeconds()));

            if (result == null) {
                return ToolExecutionResult.error("Subagent returned null (possible timeout)");
            }

            String output = formatSubagentOutput(result);
            return ToolExecutionResult.success(output);
        } catch (Exception e) {
            log.error("delegate_to_agent execution failed: {}", e.getMessage(), e);
            return ToolExecutionResult.error("Subagent delegation failed: " + e.getMessage());
        }
    }

    private String formatSubagentOutput(SubagentResult result) {
        StringBuilder sb = new StringBuilder();
        if (result.isSuccess()) {
            sb.append("## Subagent Result (success)\n\n");
            sb.append("**Agent:** ").append(result.getAgentId()).append("\n");
            sb.append("**Duration:** ").append(result.getDurationMs()).append("ms\n");
            sb.append("**Tools:** ").append(result.getSuccessTools())
              .append(" succeeded, ").append(result.getFailedTools()).append(" failed\n\n");
            sb.append("### Output\n\n").append(result.getOutput());
        } else {
            sb.append("## Subagent Result (failed)\n\n");
            sb.append("**Agent:** ").append(result.getAgentId()).append("\n");
            sb.append("**Error:** ").append(result.getError()).append("\n");
        }
        return sb.toString();
    }
}
```

### 2.1.4 Delegation Flow

The complete flow, step by step:

```
┌──────────────────────────────────────────────────────────────────┐
│ PARENT AGENT: AgentInvocationHandler                             │
│   Stage Pipeline: ContextBuild → SecurityCheck → PlanExecution   │
│   → RespondStage → ReflectionStage → MetricsStage                │
│                                                                  │
│ RespondStage:                                                    │
│   ├─ ReActEngine.executeStream(chatFacade, request, toolExecutor)│
│   │                                                              │
│   │   ┌─ LLM Call (with tools including "delegate_to_agent")     │
│   │   │                                                          │
│   │   │   LLM decides: "I should delegate this code review to    │
│   │   │   the code-reviewer agent."                              │
│   │   │                                                          │
│   │   │   → toolCall: delegate_to_agent(                        │
│   │   │       agentId="code-reviewer",                           │
│   │   │       task="Review the changes in PR #342...",           │
│   │   │       mode="suggest"                                     │
│   │   │     )                                                    │
│   │   │                                                          │
│   │   ├─ ToolExecutor.execute("delegate_to_agent", ...)          │
│   │   │                                                          │
│   │   │   ┌───────────────────────────────────────────────────┐ │
│   │   │   │ SubagentSpawner.spawnSubagent()                   │ │
│   │   │   │                                                   │ │
│   │   │   │   1. Validate allowAgents whitelist               │ │
│   │   │   │   2. Check maxSpawnDepth (parent depth + 1 < max) │ │
│   │   │   │   3. Check maxChildrenPerAgent                     │ │
│   │   │   │   4. Acquire concurrency semaphore                 │ │
│   │   │   │   5. Resolve child AgentConfig                     │ │
│   │   │   │   6. Build isolated AgentContext for child         │ │
│   │   │   │   7. Dispatch subagentSpawning hooks               │ │
│   │   │   │   8. Run child's full pipeline:                    │ │
│   │   │   │      ContextBuild → SecurityCheck →                │ │
│   │   │   │      PlanExecution → Respond(ReAct) →              │ │
│   │   │   │      Reflection → Metrics                          │ │
│   │   │   │   9. Dispatch subagentSpawned, subagentEnded       │ │
│   │   │   │  10. Release semaphore                             │ │
│   │   │   │  11. Return SubagentResult                         │ │
│   │   │   └───────────────────────────────────────────────────┘ │
│   │   │                                                          │
│   │   ├─ Tool result returned as observation to parent LLM       │
│   │   │                                                          │
│   │   └─ Parent LLM continues with the subagent's result         │
│   │       and produces final reply                               │
│   │                                                              │
│   └─ Final SSE events to client                                  │
└──────────────────────────────────────────────────────────────────┘
```

### 2.1.5 Subagent Session Management

Subagent sessions follow a hierarchical session key scheme:

```
Parent session key:  "abc12345"
Child session key:   "abc12345/subagent/code-reviewer/a1b2c3d4"
Grandchild key:      "abc12345/subagent/code-reviewer/a1b2c3d4/subagent/tester/e5f6g7h8"
```

This enables:
- **Hierarchical tracing**: any subagent's output can be traced back to the root session
- **Auto-archiving**: the session store can archive all sessions under a parent key when the parent is archived
- **Cleanup cascading**: terminating a parent session can terminate all descendant subagent sessions

```java
package lyjew.com.lyclaw.react.subagent;

import java.util.List;

import lyjew.com.lyclaw.model.Session;

/**
 * Session management for subagent runs.
 *
 * <p>Each subagent run creates a new {@link Session} with a hierarchical
 * sessionKey (parentKey + "/subagent/" + agentId + "/" + uuidFragment).
 * Sessions are stored in the same session store as the parent.</p>
 */
public class SubagentSessionManager {

    private final lyjew.com.lyclaw.persistence.SessionStore sessionStore;

    public SubagentSessionManager(lyjew.com.lyclaw.persistence.SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    /**
     * Create a new subagent session under the given parent session key.
     */
    public Session createSubagentSession(String parentSessionKey, String agentId,
                                          String systemPrompt) {
        String sessionId = parentSessionKey + "/subagent/" + agentId
                + "/" + java.util.UUID.randomUUID().toString().substring(0, 8);

        Session session = Session.builder()
                .sessionId(sessionId)
                .name("subagent:" + agentId)
                .model(null)  // resolved later from AgentConfig
                .build();

        sessionStore.save(session);
        return session;
    }

    /**
     * Archive a subagent session and all its descendant sessions.
     */
    public void archiveSession(String sessionKey, int afterMinutes) {
        // Find all sessions whose key starts with sessionKey
        List<Session> descendants = sessionStore.findByPrefix(sessionKey);
        for (Session s : descendants) {
            s.setAttribute("archived", "true");
            s.setAttribute("archivedAt", String.valueOf(System.currentTimeMillis()));
            sessionStore.save(s);
        }
    }

    /**
     * Terminate all active subagent sessions under a parent key.
     * Called when the parent session is terminated or cancelled.
     */
    public void terminateDescendants(String parentSessionKey) {
        List<Session> descendants = sessionStore.findByPrefix(parentSessionKey);
        for (Session s : descendants) {
            if (!"true".equals(s.getAttribute("archived"))) {
                s.setAttribute("terminated", "true");
                s.setAttribute("terminatedAt", String.valueOf(System.currentTimeMillis()));
                sessionStore.save(s);
            }
        }
    }
}
```

### 2.1.6 Concurrency Control

```java
package lyjew.com.lyclaw.react;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Run-time metadata attached to AgentContext for tracking subagent state.
 *
 * <p>This is stored in AgentContext.attributes under the key "runMetadata"
 * but we expose it as a typed class for type safety.</p>
 */
public class RunMetadata {

    /**
     * Depth of this agent in the subagent spawn tree.
     * 0 = root agent (no parent).  1 = directly spawned by root.
     * 2 = spawned by a level-1 subagent, etc.
     */
    private int subagentDepth = 0;

    /**
     * If this is a subagent, the session key of its parent.
     * null for root agents.
     */
    private String parentSessionKey;

    /**
     * If this is a subagent, the agentId it was spawned as.
     * null for root agents.
     */
    private String subagentTargetAgentId;

    /**
     * Set of session keys of currently active sub-agents spawned by this agent.
     * Used to enforce maxChildrenPerAgent.
     */
    private final Set<String> activeSubagentIds = ConcurrentHashMap.newKeySet();

    /**
     * Thinking/reasoning level for model calls in this context.
     * "off" | "low" | "medium" | "high". null means use model default.
     */
    private String thinkingLevel;

    /**
     * Model name override for this context (resolved from AgentConfig + defaults).
     */
    private String resolvedModel;

    /**
     * Provider name override for this context.
     */
    private String resolvedProvider;

    /**
     * The model specifically configured for image understanding.
     */
    private String imageModel;

    /**
     * Session key for the archive store.
     */
    private String archiveSessionKey;


    // ── Constructors ──

    public RunMetadata() {}

    public static RunMetadata root() {
        return new RunMetadata();
    }

    public static RunMetadata childOf(RunMetadata parent, String childAgentId) {
        RunMetadata child = new RunMetadata();
        child.subagentDepth = parent.subagentDepth + 1;
        child.parentSessionKey = null; // set later by spawner
        child.subagentTargetAgentId = childAgentId;
        return child;
    }

    // ── Getters / Setters ──

    public int getSubagentDepth() { return subagentDepth; }
    public void setSubagentDepth(int depth) { this.subagentDepth = depth; }

    public String getParentSessionKey() { return parentSessionKey; }
    public void setParentSessionKey(String key) { this.parentSessionKey = key; }

    public String getSubagentTargetAgentId() { return subagentTargetAgentId; }
    public void setSubagentTargetAgentId(String id) { this.subagentTargetAgentId = id; }

    public Set<String> getActiveSubagentIds() { return activeSubagentIds; }

    public String getThinkingLevel() { return thinkingLevel; }
    public void setThinkingLevel(String level) { this.thinkingLevel = level; }

    public String getResolvedModel() { return resolvedModel; }
    public void setResolvedModel(String model) { this.resolvedModel = model; }

    public String getResolvedProvider() { return resolvedProvider; }
    public void setResolvedProvider(String provider) { this.resolvedProvider = provider; }

    public String getImageModel() { return imageModel; }
    public void setImageModel(String model) { this.imageModel = model; }

    public String getArchiveSessionKey() { return archiveSessionKey; }
    public void setArchiveSessionKey(String key) { this.archiveSessionKey = key; }

    /** Whether this agent is a subagent (has a parent). */
    public boolean isSubagent() {
        return parentSessionKey != null || subagentDepth > 0;
    }

    /** Whether this agent is the root of the spawn tree. */
    public boolean isRoot() {
        return subagentDepth == 0 && parentSessionKey == null;
    }
}
```

### 2.1.7 AgentContext Enhancements for Subagents

The existing `AgentContext` class needs a `RunMetadata` field:

```java
// ── Addition to AgentContext ──

/** Run-time metadata including subagent depth, thinking level, model resolution */
private final RunMetadata runMetadata = new RunMetadata();

public RunMetadata getRunMetadata() { return runMetadata; }


// ── Also add to AgentContext.toSnapshot() ──

public Map<String, Object> toSnapshot() {
    Map<String, Object> snapshot = new HashMap<>();
    // ... existing fields ...
    snapshot.put("subagentDepth", runMetadata.getSubagentDepth());
    snapshot.put("parentSessionKey", runMetadata.getParentSessionKey());
    snapshot.put("thinkingLevel", runMetadata.getThinkingLevel());
    snapshot.put("resolvedModel", runMetadata.getResolvedModel());
    return snapshot;
}


// ── Also add to AgentContext.restoreFromSnapshot() ──

public void restoreFromSnapshot(Map<String, Object> snapshot) {
    if (snapshot == null) return;
    // ... existing fields ...

    if (snapshot.get("subagentDepth") instanceof Number n)
        runMetadata.setSubagentDepth(n.intValue());
    if (snapshot.get("parentSessionKey") instanceof String s)
        runMetadata.setParentSessionKey(s);
    if (snapshot.get("thinkingLevel") instanceof String s)
        runMetadata.setThinkingLevel(s);
    if (snapshot.get("resolvedModel") instanceof String s)
        runMetadata.setResolvedModel(s);
}
```

### 2.1.8 Agent Annotation Enhancements for Subagents

The `@Agent` annotation's `extensions` already supports key-value pairs. We add well-known extension keys for subagent configuration:

```
@Agent(
    name = "chat",
    description = "General-purpose chat assistant",
    extensions = {
        @Extension(key = "subagent.delegationMode", value = "prefer"),
        @Extension(key = "subagent.allowAgents", value = "code-reviewer,tester,data-analyst"),
        @Extension(key = "subagent.maxConcurrent", value = "3"),
        @Extension(key = "subagent.maxSpawnDepth", value = "2"),
        @Extension(key = "subagent.maxChildrenPerAgent", value = "10"),
        @Extension(key = "subagent.requireAgentId", value = "true"),
        @Extension(key = "subagent.model", value = "deepseek-v4-flash"),
        @Extension(key = "subagent.thinking", value = "medium"),
        @Extension(key = "subagent.runTimeoutSeconds", value = "600"),
        @Extension(key = "thinking.level", value = "high"),
        @Extension(key = "model.image", value = "openai/dall-e-3"),
        @Extension(key = "model.pdf", value = "openai/gpt-4o"),
        @Extension(key = "model.videoGeneration", value = "openai/sora"),
    }
)
public interface SuperChatAgent {
    @SystemMessage("You are a coordinating assistant...")
    String chat(@UserMessage String message);
}
```

### 2.1.9 Subagent Hook System

A new sub-interface of `AgentHook` for subagent lifecycle events:

```java
package lyjew.com.lyclaw.react.subagent;

import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.AgentHook;

/**
 * Extended hook SPI for sub-agent lifecycle events.
 *
 * <p>Any AgentHook implementation may also implement this interface to receive
 * subagent-specific lifecycle callbacks. The methods are called in the
 * SubagentSpawner at the appropriate lifecycle points.</p>
 *
 * <p>Execution context: these methods execute on boundedElastic schedulers
 * within the subagent spawner. Throwing an exception logs a warning but
 * does not interrupt the subagent pipeline.</p>
 */
public interface SubagentHook extends AgentHook {

    /**
     * Called before the child agent's pipeline begins execution.
     * The childCtx has been fully prepared (ChatRequest, tools, system prompt set).
     * Modifications to childCtx at this point will affect the child's run.
     *
     * @param childCtx the child agent's context, fully prepared
     */
    default void subagentSpawning(AgentContext childCtx) {}

    /**
     * Called after the child agent's pipeline has completed and produced a result,
     * but before the result is returned to the parent as a tool observation.
     * The result can be modified (e.g., filter sensitive info, add metadata).
     *
     * @param childCtx the child agent's context (pipeline completed)
     * @param result   the subagent result (mutable; may be replaced by returning a new one)
     */
    default void subagentSpawned(AgentContext childCtx, SubagentResult result) {}

    /**
     * Called after the result has been recorded and before the child session
     * is archived. Use for cleanup, auditing, or logging.
     *
     * @param childCtx the child agent's context
     * @param result   the final subagent result
     */
    default void subagentEnded(AgentContext childCtx, SubagentResult result) {}
}
```

### 2.1.10 Subagent Error Handling and Timeout

```java
package lyjew.com.lyclaw.react.subagent;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.ArrayList;

/**
 * The result of a subagent delegation call.
 * Returned to the parent LLM as a tool observation string (via toString/format),
 * but also available programmatically for hooks and metrics.
 */
@Data
@Builder
public class SubagentResult {

    /** Whether the subagent completed successfully. */
    private boolean success;

    /** Session key of the subagent run. */
    private String sessionKey;

    /** The agent ID that handled the delegation. */
    private String agentId;

    /** The final text output from the subagent (LLM's final response). */
    private String output;

    /** Error message if success == false. */
    private String error;

    /** Duration of the subagent run in milliseconds. */
    private long durationMs;

    /** Number of tool calls that succeeded. */
    private int successTools;

    /** Number of tool calls that failed. */
    private int failedTools;

    /** If the subagent called delegate_to_agent itself, these are the results. */
    @Builder.Default
    private List<SubagentResult> childResults = new ArrayList<>();

    /** The subagent's reflection score (from ReflectionStage), if any. */
    private Double reflectionScore;

    /** Total tokens consumed by this subagent. */
    private int totalTokens;


    // ── Factory methods ──

    public static SubagentResult success(String sessionKey, String agentId,
                                          String output, long durationMs,
                                          int successTools, int failedTools) {
        return SubagentResult.builder()
                .success(true)
                .sessionKey(sessionKey)
                .agentId(agentId)
                .output(output)
                .durationMs(durationMs)
                .successTools(successTools)
                .failedTools(failedTools)
                .build();
    }

    public static SubagentResult error(String error) {
        return SubagentResult.builder()
                .success(false)
                .agentId("unknown")
                .error(error)
                .build();
    }

    public static SubagentResult timeout(String agentId, long timeoutSeconds) {
        return SubagentResult.builder()
                .success(false)
                .agentId(agentId)
                .error("Subagent timed out after " + timeoutSeconds + " seconds")
                .durationMs(timeoutSeconds * 1000)
                .build();
    }

    public static SubagentResult rejected(String agentId, String reason) {
        return SubagentResult.builder()
                .success(false)
                .agentId(agentId)
                .error("Subagent delegation rejected: " + reason)
                .build();
    }

    /**
     * Format as a human-readable tool observation for the parent LLM.
     */
    public String formatAsObservation() {
        StringBuilder sb = new StringBuilder();
        sb.append("[Subagent Result] ");
        sb.append("agent=").append(agentId).append(" ");
        if (success) {
            sb.append("status=success ");
            sb.append("durationMs=").append(durationMs).append(" ");
            sb.append("toolsSucceeded=").append(successTools).append(" ");
            sb.append("toolsFailed=").append(failedTools).append("\n");
            sb.append("Output:\n").append(output);
        } else {
            sb.append("status=failed\n");
            sb.append("Error: ").append(error);
        }
        if (reflectionScore != null) {
            sb.append("\nReflection score: ").append(String.format("%.2f", reflectionScore));
        }
        return sb.toString();
    }
}
```

### 2.1.11 Configuration (application.yml)

```yaml
lyclaw:
  # Global subagent defaults
  subagent:
    enabled: true
    delegation-mode: suggest           # "suggest" or "prefer"
    allow-agents: "*"                  # "*" or comma-separated agent IDs
    max-concurrent: 1
    max-spawn-depth: 1                 # 1 = no recursive spawning
    max-children-per-agent: 5
    archive-after-minutes: 60
    run-timeout-seconds: 300
    announce-timeout-ms: 120000
    require-agent-id: false
    model:                             # optional model override for sub-agents
    thinking:                          # optional thinking level for sub-agents

  agent:
    # Default ReAct settings (existing)
    max-tool-rounds: 30

  # Example per-agent override via extensions (in AgentConfig or yml agent config)
  agents:
    chat:
      name: chat
      description: "General chat assistant with subagent delegation"
      model: deepseek-v4-flash
      provider: deepseek
      extensions:
        subagent.delegation-mode: prefer
        subagent.allow-agents: "code-reviewer,tester,data-analyst"
        subagent.max-concurrent: 3
        subagent.max-spawn-depth: 2
        subagent.max-children-per-agent: 10
        subagent.require-agent-id: true
        thinking.level: high            # Phase 2.2 - thinking level
        model.image: "openai/dall-e-3"
        model.pdf: "openai/gpt-4o"

    code-reviewer:
      name: code-reviewer
      description: "Specialized code review agent"
      model: deepseek-v4-flash
      provider: deepseek
      extensions:
        subagent.max-spawn-depth: 0    # This agent cannot spawn sub-agents
        subagent.max-concurrent: 0
        thinking.level: medium
```

---

## 2.2 Model Management Enhancement

### 2.2.1 Model Catalog

A structured catalog of all available models, replacing the current implicit model discovery.

```java
package lyjew.com.lyclaw.chat.catalog;

import java.util.List;
import java.util.Map;

/**
 * A structured entry in the model catalog.
 *
 * <p>Each entry represents one available model from a specific provider.
 * The catalog is built at startup from:
 * <ol>
 *   <li>Static configuration (application.yml lyclaw.chat.models.*)</li>
 *   <li>@ChatModel annotated beans (auto-discovered)</li>
 *   <li>ProviderDiscovery responses (auto-probed if enabled)</li>
 * </ol>
 *
 * <p>The ID is the canonical reference string: "provider/modelName"
 * e.g., "openai/gpt-4o", "deepseek/deepseek-v4-flash", "anthropic/claude-sonnet-4-5".
 */
public class ModelCatalogEntry {

    // ── Identity ──

    /** Full canonical reference: "openai/gpt-4o" */
    private String id;

    /** Model name: "gpt-4o" */
    private String name;

    /** Provider name: "openai" */
    private String provider;

    /** Optional short alias for convenience: "gpt4" */
    private String alias;

    /** Human-readable display name */
    private String displayName;

    /** Free-text description of this model */
    private String description;

    // ── Capabilities ──

    /** Maximum context window in tokens */
    private int contextWindow;

    /** Override for context tokens sent to the API (for providers that
     *  reserve part of the context window for internal use) */
    private int contextTokens;

    /** Whether this model supports extended reasoning/thinking */
    private boolean reasoning;

    /** Maximum output tokens this model can generate */
    private int maxOutputTokens;

    // ── Input modalities ──

    /** Input types this model accepts */
    private List<ModelInputType> input;

    // ── Pricing (informational) ──

    /** USD per 1M input tokens */
    private double pricePerMillionInput;

    /** USD per 1M output tokens */
    private double pricePerMillionOutput;

    // ── Compatibility config ──

    /** Provider-specific compatibility overrides */
    private ModelCompatConfig compat;

    // ── Status ──

    /** Whether this model is currently available (verified via health check) */
    private boolean available = true;

    /** Whether this is a beta/preview model */
    private boolean beta;

    /** When this model was deprecated (epoch millis), 0 = not deprecated */
    private long deprecatedAt;


    // ── Builder ──

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final ModelCatalogEntry entry = new ModelCatalogEntry();
        public Builder id(String id) { entry.id = id; return this; }
        public Builder name(String name) { entry.name = name; return this; }
        public Builder provider(String provider) { entry.provider = provider; return this; }
        public Builder alias(String alias) { entry.alias = alias; return this; }
        public Builder displayName(String name) { entry.displayName = name; return this; }
        public Builder description(String desc) { entry.description = desc; return this; }
        public Builder contextWindow(int tokens) { entry.contextWindow = tokens; return this; }
        public Builder contextTokens(int tokens) { entry.contextTokens = tokens; return this; }
        public Builder reasoning(boolean v) { entry.reasoning = v; return this; }
        public Builder maxOutputTokens(int tokens) { entry.maxOutputTokens = tokens; return this; }
        public Builder input(List<ModelInputType> input) { entry.input = input; return this; }
        public Builder priceInput(double price) { entry.pricePerMillionInput = price; return this; }
        public Builder priceOutput(double price) { entry.pricePerMillionOutput = price; return this; }
        public Builder compat(ModelCompatConfig compat) { entry.compat = compat; return this; }
        public Builder available(boolean v) { entry.available = v; return this; }
        public Builder beta(boolean v) { entry.beta = v; return this; }
        public Builder deprecatedAt(long ts) { entry.deprecatedAt = ts; return this; }
        public ModelCatalogEntry build() { return entry; }
    }

    // ── Getters ──

    public String getId() { return id; }
    public String getName() { return name; }
    public String getProvider() { return provider; }
    public String getAlias() { return alias; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public int getContextWindow() { return contextWindow; }
    public int getContextTokens() { return contextTokens; }
    public boolean isReasoning() { return reasoning; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public List<ModelInputType> getInput() { return input; }
    public double getPricePerMillionInput() { return pricePerMillionInput; }
    public double getPricePerMillionOutput() { return pricePerMillionOutput; }
    public ModelCompatConfig getCompat() { return compat; }
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean v) { this.available = v; }
    public boolean isBeta() { return beta; }
    public long getDeprecatedAt() { return deprecatedAt; }

    /**
     * Build a canonical ID from provider and model name.
     */
    public static String canonicalId(String provider, String name) {
        return provider + "/" + name;
    }
}
```

```java
package lyjew.com.lyclaw.chat.catalog;

/**
 * Input types that a model can accept.
 */
public enum ModelInputType {
    /** Plain text */
    TEXT,

    /** Image files (png, jpg, gif, webp) */
    IMAGE,

    /** Audio files (mp3, wav, ogg) */
    AUDIO,

    /** Video files (mp4, mov) */
    VIDEO,

    /** Documents (pdf, docx, txt) */
    DOCUMENT
}
```

```java
package lyjew.com.lyclaw.chat.catalog;

import java.util.HashMap;
import java.util.Map;

/**
 * Provider-specific compatibility configuration.
 *
 * <p>Different providers use different field names, header formats,
 * and API conventions. This config captures those differences so that
 * the model resolution service can build correct native requests.</p>
 */
public class ModelCompatConfig {

    /** Whether this provider requires the model name in a specific field
     *  (e.g., some providers use "model" while others use "model_id") */
    private String modelFieldName = "model";

    /** Whether the provider emits SSE events with "data: " prefix */
    private boolean sseDataPrefix = true;

    /** Whether the SSE stream uses "\n\n" as delimiter */
    private boolean sseDoubleNewline = true;

    /** Whether tool call streaming is supported for this provider */
    private boolean supportsToolCallStreaming;

    /** Whether thinking/reasoning is in a separate field or inline */
    private String thinkingField = "reasoning_content";

    /** Whether thinking is combined with content or separate in streaming */
    private boolean thinkingInline;

    /** Provider-specific HTTP headers */
    private final Map<String, String> headers = new HashMap<>();

    /** Extra query parameters to append to the API URL */
    private final Map<String, String> queryParams = new HashMap<>();

    /** Whether this provider supports system message as top-level field
     *  (OpenAI style) or as a message with role="system" */
    private boolean systemMessageAsField = true;

    /** Maximum image size in bytes for vision models */
    private long maxImageBytes = 20 * 1024 * 1024; // 20MB

    /** Whether to auto-resize images before sending */
    private boolean autoResizeImages = true;

    /** Maximum image dimensions for auto-resize */
    private int maxImageWidth = 2048;
    private int maxImageHeight = 2048;

    // ── Getters / Setters ──

    public String getModelFieldName() { return modelFieldName; }
    public void setModelFieldName(String v) { this.modelFieldName = v; }

    public boolean isSseDataPrefix() { return sseDataPrefix; }
    public void setSseDataPrefix(boolean v) { this.sseDataPrefix = v; }

    public boolean isSseDoubleNewline() { return sseDoubleNewline; }
    public void setSseDoubleNewline(boolean v) { this.sseDoubleNewline = v; }

    public boolean isSupportsToolCallStreaming() { return supportsToolCallStreaming; }
    public void setSupportsToolCallStreaming(boolean v) { this.supportsToolCallStreaming = v; }

    public String getThinkingField() { return thinkingField; }
    public void setThinkingField(String v) { this.thinkingField = v; }

    public boolean isThinkingInline() { return thinkingInline; }
    public void setThinkingInline(boolean v) { this.thinkingInline = v; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeader(String key, String value) { headers.put(key, value); }

    public Map<String, String> getQueryParams() { return queryParams; }

    public boolean isSystemMessageAsField() { return systemMessageAsField; }
    public void setSystemMessageAsField(boolean v) { this.systemMessageAsField = v; }

    public long getMaxImageBytes() { return maxImageBytes; }
    public void setMaxImageBytes(long v) { this.maxImageBytes = v; }

    public boolean isAutoResizeImages() { return autoResizeImages; }
    public void setAutoResizeImages(boolean v) { this.autoResizeImages = v; }

    public int getMaxImageWidth() { return maxImageWidth; }
    public void setMaxImageWidth(int v) { this.maxImageWidth = v; }

    public int getMaxImageHeight() { return maxImageHeight; }
    public void setMaxImageHeight(int v) { this.maxImageHeight = v; }

    /** OpenAI-compatible defaults */
    public static ModelCompatConfig openAiDefaults() {
        ModelCompatConfig c = new ModelCompatConfig();
        c.modelFieldName = "model";
        c.sseDataPrefix = true;
        c.sseDoubleNewline = true;
        c.thinkingField = "reasoning_content";
        c.systemMessageAsField = false; // messages[0].role=system
        return c;
    }

    /** Anthropic-specific defaults */
    public static ModelCompatConfig anthropicDefaults() {
        ModelCompatConfig c = new ModelCompatConfig();
        c.modelFieldName = "model";
        c.sseDataPrefix = true;
        c.sseDoubleNewline = true;
        c.supportsToolCallStreaming = false;
        c.thinkingField = "thinking";
        c.thinkingInline = false;
        c.systemMessageAsField = true; // top-level system field
        return c;
    }
}
```

### 2.2.2 Multi-Model Support in AgentDefaultsConfig

We introduce a new `AgentDefaultsConfig` to replace the single-model assumption:

```java
package lyjew.com.lyclaw.chat.config;

/**
 * Per-agent or global default configuration for model selection by modality.
 *
 * <p>This replaces the single "model" concept with modality-specific models.
 * Each field can be either a canonical ID ("openai/gpt-4o") or an alias ("gpt-4o").
 * Fields set to null inherit from the global defaults in application.yml.</p>
 */
public class AgentModelConfig {

    /** Primary chat/text generation model */
    private String chatModel;

    /** Model used for image understanding (vision) */
    private String imageModel;

    /** Model used for image generation (DALL-E, etc.) */
    private String imageGenerationModel;

    /** Model used for video generation (Sora, etc.) */
    private String videoGenerationModel;

    /** Model used for music/sound generation */
    private String musicGenerationModel;

    /** Model used for PDF reading and understanding */
    private String pdfModel;

    // ── PDF limits ──

    /** Max PDF file size in MB */
    private int pdfMaxBytesMb = 10;

    /** Max pages to read from a PDF */
    private int pdfMaxPages = 20;

    // ── Generation settings ──

    /** Auto-fallback to another provider if the primary image gen model fails */
    private boolean mediaGenerationAutoProviderFallback = true;

    // ── Getters / Setters ──

    public String getChatModel() { return chatModel; }
    public void setChatModel(String model) { this.chatModel = model; }

    public String getImageModel() { return imageModel; }
    public void setImageModel(String model) { this.imageModel = model; }

    public String getImageGenerationModel() { return imageGenerationModel; }
    public void setImageGenerationModel(String model) { this.imageGenerationModel = model; }

    public String getVideoGenerationModel() { return videoGenerationModel; }
    public void setVideoGenerationModel(String model) { this.videoGenerationModel = model; }

    public String getMusicGenerationModel() { return musicGenerationModel; }
    public void setMusicGenerationModel(String model) { this.musicGenerationModel = model; }

    public String getPdfModel() { return pdfModel; }
    public void setPdfModel(String model) { this.pdfModel = model; }

    public int getPdfMaxBytesMb() { return pdfMaxBytesMb; }
    public void setPdfMaxBytesMb(int mb) { this.pdfMaxBytesMb = mb; }

    public int getPdfMaxPages() { return pdfMaxPages; }
    public void setPdfMaxPages(int pages) { this.pdfMaxPages = pages; }

    public boolean isMediaGenerationAutoProviderFallback() { return mediaGenerationAutoProviderFallback; }
    public void setMediaGenerationAutoProviderFallback(boolean v) { this.mediaGenerationAutoProviderFallback = v; }

    /**
     * Resolve the effective chat model, falling back to global defaults.
     */
    public String resolveChatModel(String globalDefault) {
        return chatModel != null ? chatModel : globalDefault;
    }
}
```

### 2.2.3 Model Selection and Resolution

```java
package lyjew.com.lyclaw.chat.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import lyjew.com.lyclaw.chat.ChatModel;
import lyjew.com.lyclaw.chat.ChatModelRegistry;
import lyjew.com.lyclaw.chat.RoutingDecision;
import lyjew.com.lyclaw.chat.RoutingTier;
import lyjew.com.lyclaw.chat.catalog.ModelCatalogEntry;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.RunMetadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central service for resolving which model to use for a given agent + session.
 *
 * <h3>Resolution Order</h3>
 * <ol>
 *   <li>Check AgentContext.runMetadata for an override (set by subagent spawner)</li>
 *   <li>Check AgentConfig.model / AgentConfig.provider from annotation/yml</li>
 *   <li>Check agent extensions: thinking.level, model.image, model.pdf, etc.</li>
 *   <li>Fall back to global defaults (ChatProperties.defaultProvider/defaultModel)</li>
 *   <li>Fall back to FirstAvailableRouter if nothing is configured</li>
 * </ol>
 *
 * <h3>Alias Resolution</h3>
 * <p>Aliases are short names like "gpt-4o" that resolve to "openai/gpt-4o".
 * The alias map is populated from ModelCatalogEntry.alias fields.</p>
 */
public class ModelResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ModelResolutionService.class);

    private final ChatModelRegistry registry;
    private final ModelCatalog modelCatalog;
    private final Map<String, String> aliasMap = new ConcurrentHashMap<>();

    /** Default fallback chain (canonical IDs in priority order) */
    private final List<String> defaultFallbackChain;

    public ModelResolutionService(ChatModelRegistry registry,
                                   ModelCatalog modelCatalog,
                                   List<String> defaultFallbackChain) {
        this.registry = registry;
        this.modelCatalog = modelCatalog;
        this.defaultFallbackChain = defaultFallbackChain != null
                ? List.copyOf(defaultFallbackChain) : List.of();
        buildAliasMap();
    }

    /**
     * Resolve the effective (provider, model) for this agent context's
     * primary chat model.
     */
    public ModelRef resolveEffectiveModel(AgentContext ctx) {
        RunMetadata meta = ctx.getRunMetadata();

        // 1. Override from runMetadata
        if (meta.getResolvedModel() != null && meta.getResolvedProvider() != null) {
            return new ModelRef(meta.getResolvedProvider(), meta.getResolvedModel());
        }

        // 2. From ChatRequest (set by AgentInvocationHandler from @Agent annotation)
        ChatRequest request = ctx.getChatRequest();
        if (request != null && request.getModel() != null && !request.getModel().isEmpty()) {
            // model field may be a canonical ID "deepseek/deepseek-v4-flash"
            // or just a model name paired with the request's implicit provider
            ModelRef ref = parseModelRef(request.getModel());
            if (ref != null) return ref;
        }

        // 3. From AgentConfig extensions (set by AgentConfigResolver)
        @SuppressWarnings("unchecked")
        Map<String, String> extensions = ctx.getAttribute("agentExtensions");
        if (extensions != null) {
            String configModel = extensions.get("model");
            String configProvider = extensions.get("provider");
            if (configModel != null) {
                return new ModelRef(
                        configProvider != null ? configProvider : "deepseek",
                        configModel);
            }
        }

        // 4. Fallback to first available model
        return resolveFirstAvailable();
    }

    /**
     * Resolve the model to use for image understanding (vision).
     */
    public ModelRef resolveImageModel(AgentContext ctx) {
        @SuppressWarnings("unchecked")
        Map<String, String> extensions = ctx.getAttribute("agentExtensions");
        if (extensions != null && extensions.containsKey("model.image")) {
            return parseModelRef(extensions.get("model.image"));
        }
        // Fall back to the primary model (most modern models support vision)
        return resolveEffectiveModel(ctx);
    }

    /**
     * Resolve the effective fallback chain for this context.
     * Operator overrides > agent config > global defaults.
     */
    public List<String> resolveEffectiveFallbacks(AgentContext ctx) {
        @SuppressWarnings("unchecked")
        Map<String, String> extensions = ctx.getAttribute("agentExtensions");
        if (extensions != null && extensions.containsKey("fallback.chain")) {
            return List.of(extensions.get("fallback.chain").split(","));
        }
        return defaultFallbackChain;
    }

    /**
     * Resolve an alias to its canonical ID.
     * e.g., "gpt-4o" → "openai/gpt-4o"
     */
    public String resolveAlias(String alias) {
        if (alias == null) return null;
        if (alias.contains("/")) return alias; // already canonical
        return aliasMap.getOrDefault(alias, alias);
    }

    /**
     * Auto-fallback probe: test whether a model works for a given session.
     * Returns probe config if fallback is needed, null if primary model works.
     */
    public AutoFallbackProbe resolveAutoFallbackProbe(String sessionKey,
                                                        String primaryProvider,
                                                        String primaryModel) {
        // Check if the model has recently failed health checks
        if (!modelCatalog.isAvailable(primaryProvider, primaryModel)) {
            // Find the first working fallback
            for (String fallbackId : defaultFallbackChain) {
                ModelRef ref = parseModelRef(fallbackId);
                if (ref != null && modelCatalog.isAvailable(ref.provider, ref.model)) {
                    return new AutoFallbackProbe(sessionKey, primaryProvider, primaryModel,
                            ref.provider, ref.model, "primary_unavailable");
                }
            }
        }
        return null; // primary model works, no fallback needed
    }

    /**
     * Parse a model reference string.
     * Accepts: "provider/model", "model" (provider derived from context), or alias.
     */
    public ModelRef parseModelRef(String ref) {
        if (ref == null || ref.isEmpty()) return null;

        // Try alias first
        String resolved = resolveAlias(ref);

        int slash = resolved.indexOf('/');
        if (slash > 0) {
            return new ModelRef(resolved.substring(0, slash), resolved.substring(slash + 1));
        }
        // No provider specified: use default provider
        return new ModelRef("deepseek", resolved);
    }

    private ModelRef resolveFirstAvailable() {
        Map<String, List<ChatModel>> all = registry.getAll();
        for (Map.Entry<String, List<ChatModel>> entry : all.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                ChatModel first = entry.getValue().get(0);
                return new ModelRef(first.provider(), first.model());
            }
        }
        throw new IllegalStateException("No available AI models. Configure at least one provider.");
    }

    private void buildAliasMap() {
        for (ModelCatalogEntry entry : modelCatalog.getAll()) {
            if (entry.getAlias() != null && !entry.getAlias().isEmpty()) {
                aliasMap.put(entry.getAlias(), entry.getId());
            }
            // Also register name-only as alias when unambiguous
            aliasMap.putIfAbsent(entry.getProvider() + "/" + entry.getName(), entry.getId());
        }
    }

    // ── Inner types ──

    /**
     * A resolved (provider, model) pair.
     */
    public record ModelRef(String provider, String model) {
        public String canonicalId() {
            return provider + "/" + model;
        }
    }

    /**
     * Information about an auto-fallback probe.
     * When the primary model is unavailable, this tells the system
     * which fallback to use instead.
     */
    public record AutoFallbackProbe(String sessionKey,
                                     String primaryProvider, String primaryModel,
                                     String fallbackProvider, String fallbackModel,
                                     String reason) {}
}
```

### 2.2.4 Thinking / Reasoning / Verbose Controls

```java
package lyjew.com.lyclaw.chat.config;

/**
 * Thinking/reasoning level that controls how much the model "thinks" before
 * producing output. Mapped to provider-specific API parameters.
 *
 * <h3>Levels</h3>
 * <ul>
 *   <li><b>OFF</b> — thinking/reasoning disabled. Fastest, lowest cost.</li>
 *   <li><b>LOW</b> — brief reasoning. Good for simple tool-use tasks.</li>
 *   <li><b>MEDIUM</b> — moderate reasoning. Balanced for most tasks.</li>
 *   <li><b>HIGH</b> — extensive reasoning. For complex multi-step problems.</li>
 *   <li><b>MAX</b> — maximum reasoning budget. Highest quality, highest cost/latency.</li>
 * </ul>
 *
 * <h3>Provider Mapping</h3>
 * <ul>
 *   <li>DeepSeek: "thinking" parameter with "enabled" + "thinking_budget"</li>
 *   <li>OpenAI o-series: "reasoning_effort": low/medium/high</li>
 *   <li>Anthropic: "thinking" block with "budget_tokens"</li>
 *   <li>Gemini: "thinking_config" with "thinking_level"</li>
 * </ul>
 */
public enum ThinkingLevel {

    OFF(0, 0, "off"),
    LOW(1, 1024, "low"),
    MEDIUM(2, 4096, "medium"),
    HIGH(3, 16384, "high"),
    MAX(4, 32768, "max");

    private final int ordinal;
    private final int defaultBudgetTokens;
    private final String label;

    ThinkingLevel(int ordinal, int defaultBudgetTokens, String label) {
        this.ordinal = ordinal;
        this.defaultBudgetTokens = defaultBudgetTokens;
        this.label = label;
    }

    public int getDefaultBudgetTokens() { return defaultBudgetTokens; }
    public String getLabel() { return label; }

    /** Parse from string (case-insensitive): "off", "low", "medium", "high", "max" */
    public static ThinkingLevel fromString(String s) {
        if (s == null) return OFF;
        return switch (s.toLowerCase()) {
            case "off", "none", "disabled" -> OFF;
            case "low", "minimal" -> LOW;
            case "medium", "moderate", "balanced" -> MEDIUM;
            case "high", "extensive" -> HIGH;
            case "max", "maximum", "full" -> MAX;
            default -> OFF;
        };
    }

    /** Convert to DeepSeek API thinking parameter value */
    public String toDeepSeekThinking() {
        if (this == OFF) return null; // omit thinking block
        return "enabled";
    }

    /** Convert to DeepSeek thinking_budget tokens */
    public int toDeepSeekBudget() {
        return defaultBudgetTokens;
    }

    /** Convert to OpenAI reasoning_effort */
    public String toOpenAiReasoningEffort() {
        return switch (this) {
            case OFF -> null;
            case LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH, MAX -> "high";
        };
    }
}
```

The thinking level is resolved and injected into `ChatRequest` at pipeline start:

```java
// ── In AgentInvocationHandler.invoke(), before stage execution ──

// Resolve thinking level from annotation/yml
String thinkingStr = resolveThinkingLevel(method, args);
ctx.getRunMetadata().setThinkingLevel(thinkingStr);

// Apply to ChatRequest
ThinkingLevel level = ThinkingLevel.fromString(thinkingStr);
if (level != ThinkingLevel.OFF) {
    request.setThinkingEnabled(true);
    request.setThinkingBudget(level.getDefaultBudgetTokens());
}
```

### 2.2.5 Provider Discovery

```java
package lyjew.com.lyclaw.chat.catalog;

import java.util.List;

import reactor.core.publisher.Mono;

/**
 * SPI for auto-discovering available models from a provider's API.
 *
 * <p>Providers that support a /models endpoint (OpenAI, DeepSeek, etc.)
 * implement this to populate the ModelCatalog at startup. This replaces
 * hardcoded model lists and enables dynamic model availability tracking.</p>
 */
public interface ProviderDiscovery {

    /**
     * Discover all available models from the provider's API.
     *
     * @param provider the provider name (e.g., "openai")
     * @param apiKey the API key for authentication
     * @return a Mono that completes with the list of discovered model entries
     */
    Mono<List<ModelCatalogEntry>> discoverModels(String provider, String apiKey);

    /**
     * Validate that a specific model is available and responsive.
     * Typically sends a minimal request (e.g., 1-token completion) to verify.
     *
     * @param provider the provider name
     * @param model the model name
     * @param apiKey the API key
     * @return true if the model responds successfully
     */
    Mono<Boolean> validateModel(String provider, String model, String apiKey);

    /**
     * Get the provider's supported features (streaming, tool calling, thinking, etc.)
     * from the /models/{model} endpoint.
     */
    Mono<ModelCompatConfig> probeCapabilities(String provider, String model, String apiKey);

    /**
     * Returns true if this discovery implementation supports the given provider.
     */
    boolean supportsProvider(String provider);
}
```

A default implementation for OpenAI-compatible APIs:

```java
package lyjew.com.lyclaw.chat.catalog;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lyjew.com.lyclaw.chat.ChatProperties;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * OpenAI-compatible provider discovery via the /v1/models endpoint.
 *
 * <p>Works with OpenAI, DeepSeek, Groq, and any provider that implements
 * the OpenAI /v1/models API. Falls back gracefully if the endpoint is
 * not available or returns non-standard responses.</p>
 */
public class OpenAICompatibleProviderDiscovery implements ProviderDiscovery {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient httpClient;

    public OpenAICompatibleProviderDiscovery() {
        this.httpClient = HttpClient.create();
    }

    @Override
    public boolean supportsProvider(String provider) {
        // All providers using openai-protocol are supported
        return true;  // ChatProperties determines actual protocol
    }

    @Override
    public Mono<List<ModelCatalogEntry>> discoverModels(String provider, String apiKey) {
        // Use ChatProperties to find the provider's baseUrl
        ChatProperties.ModelProperties props = /* resolve from ChatProperties */ null;

        String url = (props != null ? props.getBaseUrl() : "https://api.openai.com") + "/v1/models";

        return httpClient
                .headers(h -> h.set("Authorization", "Bearer " + apiKey))
                .get()
                .uri(url)
                .responseSingle((response, body) -> body.asString())
                .map(json -> {
                    try {
                        JsonNode root = mapper.readTree(json);
                        JsonNode data = root.get("data");
                        if (data == null || !data.isArray()) return List.<ModelCatalogEntry>of();

                        List<ModelCatalogEntry> entries = new java.util.ArrayList<>();
                        for (JsonNode node : data) {
                            String id = node.get("id").asText();
                            String ownedBy = provider;
                            if (node.has("owned_by")) ownedBy = node.get("owned_by").asText();

                            ModelCatalogEntry entry = ModelCatalogEntry.builder()
                                    .id(ModelCatalogEntry.canonicalId(provider, id))
                                    .name(id)
                                    .provider(provider)
                                    .displayName(id)
                                    .available(true)
                                    .build();
                            entries.add(entry);
                        }
                        return entries;
                    } catch (Exception e) {
                        return List.<ModelCatalogEntry>of();
                    }
                })
                .onErrorReturn(List.of());
    }

    @Override
    public Mono<Boolean> validateModel(String provider, String model, String apiKey) {
        // Send a minimal chat completion request with max_tokens=1
        return Mono.just(true);  // Simplified; real impl would make a test call
    }

    @Override
    public Mono<ModelCompatConfig> probeCapabilities(String provider, String model, String apiKey) {
        return Mono.just(ModelCompatConfig.openAiDefaults());
    }
}
```

### 2.2.6 Model Fallback Chain Integration

The fallback chain from `ModelResolutionService` is integrated into the existing `FallbackChatModel` decorator:

```java
package lyjew.com.lyclaw.chat;

import java.util.List;

import lyjew.com.lyclaw.chat.config.ModelResolutionService;
import lyjew.com.lyclaw.chat.config.ModelResolutionService.ModelRef;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ModelResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enhanced fallback model that uses the ModelResolutionService to dynamically
 * resolve fallback candidates instead of a static hardcoded list.
 *
 * <p>Integrates with the existing FallbackChatModel decorator pattern but
 * adds model-catalog-aware resolution.</p>
 */
public class DynamicFallbackChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(DynamicFallbackChatModel.class);

    private final ChatModel primary;
    private final ModelResolutionService resolutionService;
    private final ChatModelRegistry registry;

    /** Fallback chain as canonical IDs, or null to use resolution service */
    private final List<String> staticFallbackChain;

    public DynamicFallbackChatModel(ChatModel primary,
                                      ModelResolutionService resolutionService,
                                      ChatModelRegistry registry,
                                      List<String> staticFallbackChain) {
        this.primary = primary;
        this.resolutionService = resolutionService;
        this.registry = registry;
        this.staticFallbackChain = staticFallbackChain;
    }

    @Override
    public String provider() { return primary.provider(); }

    @Override
    public String model() { return primary.model(); }

    @Override
    public ModelCapabilities capabilities() { return primary.capabilities(); }

    @Override
    public Flux<ModelResponse> stream(ChatRequest request) {
        return primary.stream(request)
                .onErrorResume(error -> {
                    log.warn("Primary model {}/{} failed: {}. Attempting fallback...",
                            primary.provider(), primary.model(), error.getMessage());

                    // Try each fallback in order
                    return tryFallbacks(request, 0);
                });
    }

    private Flux<ModelResponse> tryFallbacks(ChatRequest request, int attemptIndex) {
        List<String> chain = staticFallbackChain != null
                ? staticFallbackChain
                : List.of(); // will use dynamic resolution

        if (attemptIndex >= chain.size() && staticFallbackChain != null) {
            return Flux.error(new RuntimeException(
                    "All fallback models exhausted for " + primary.provider() + "/" + primary.model()));
        }

        String fallbackId = staticFallbackChain != null
                ? chain.get(attemptIndex)
                : null;

        if (fallbackId == null) {
            // Dynamic fallback resolution - find any working model
            ModelRef ref = resolutionService.parseModelRef(
                    primary.provider() + "/" + primary.model());
            if (ref == null) {
                return Flux.error(new RuntimeException("No fallback available"));
            }
            fallbackId = ref.canonicalId();
        }

        ModelRef ref = resolutionService.parseModelRef(fallbackId);
        if (ref == null) {
            return Flux.error(new RuntimeException("Invalid fallback ID: " + fallbackId));
        }

        ChatModel fallback = registry.resolve(ref.provider(), ref.model());
        if (fallback == null) {
            return tryFallbacks(request, attemptIndex + 1);
        }

        log.info("Falling back to {}/{} (attempt {})", ref.provider(), ref.model(), attemptIndex + 1);

        return fallback.stream(request)
                .onErrorResume(err -> {
                    log.warn("Fallback model {}/{} also failed: {}",
                            ref.provider(), ref.model(), err.getMessage());
                    return tryFallbacks(request, attemptIndex + 1);
                });
    }

    @Override
    public int countTokens(String text) { return primary.countTokens(text); }

    @Override
    public Mono<Boolean> validate() { return primary.validate(); }
}
```

### 2.2.7 SSE Events for Thinking

The `DefaultReActEngine` already handles thinking content via `ModelResponse.getThinking()`. We enhance this with structured SSE events:

```java
// ── Addition to DefaultReActEngine ──

/**
 * SSE event types for thinking/reasoning streaming.
 *
 * <p>Events emitted during streaming when thinking is enabled:
 * <ul>
 *   <li>{@code thinking_start} — emitted once when the model starts thinking
 *       (before any content is produced)</li>
 *   <li>{@code thinking_delta} — emitted for each thinking token/chunk</li>
 *   <li>{@code thinking_end} — emitted when the model stops thinking and
 *       begins producing content</li>
 * </ul>
 */
private static final String SSE_THINKING_START = "thinking_start";
private static final String SSE_THINKING_DELTA = "thinking_delta";
private static final String SSE_THINKING_END = "thinking_end";

// In the stream handle() callback, detect thinking vs. content:

// ...inside .handle((chunk, sink) -> { ...

if (chunk.getThinking() != null && !chunk.getThinking().isEmpty()) {
    // Emit thinking events instead of message events
    if (!thinkingStarted.get()) {
        thinkingStarted.set(true);
        sink.next(sseEvent(SSE_THINKING_START, ""));
    }
    sink.next(sseEvent(SSE_THINKING_DELTA, chunk.getThinking()));
    return;
}

if (thinkingStarted.get() && chunk.getContent() != null) {
    // Transition: thinking → content
    thinkingStarted.set(false);
    sink.next(sseEvent(SSE_THINKING_END, ""));
}
```

### 2.2.8 ChatRequest and ChatModel Enhancements

**ChatRequest additions for multi-model support:**

```java
// ── New fields in ChatRequest ──

/** Thinking/reasoning level (off/low/medium/high/max) */
private String thinkingLevel;

/** Override model for image understanding (separate from primary text model) */
private String imageModel;

/** Override model for PDF reading */
private String pdfModel;

/** When true, media generation requests will auto-fallback to
 *  alternative providers if the primary model fails */
@Builder.Default
private boolean mediaGenerationAutoFallback = true;
```

**ChatModel additions for thinking support:**

```java
// ── New method on ChatModel interface ──

/**
 * Whether this model supports thinking/reasoning at a specific level.
 * Models that don't support thinking will silently ignore the parameter.
 */
default boolean supportsThinkingLevel(ThinkingLevel level) {
    return capabilities().isThinking();
}

/**
 * Whether this model supports image input (vision).
 */
default boolean supportsVision() {
    return capabilities().isVision();
}
```

**ModelCapabilities enhancements:**

```java
// ── New fields in ModelCapabilities ──

/** Whether this model supports image generation */
private boolean imageGeneration;

/** Whether this model supports video generation */
private boolean videoGeneration;

/** Whether this model supports music generation */
private boolean musicGeneration;

/** Whether this model supports PDF reading */
private boolean pdfReading;

/** Maximum supported thinking effort level */
private ThinkingLevel maxThinkingLevel = ThinkingLevel.OFF;

// ... with getters/setters and builder methods ...
```

### 2.2.9 Configuration (application.yml)

```yaml
lyclaw:
  chat:
    default-provider: deepseek
    default-model: deepseek-v4-flash

    # Global model catalog (populated from annotations + this config)
    catalog:
      # Auto-discover models from provider APIs
      auto-discover: true
      # Cache discovered models for this many minutes
      discovery-cache-minutes: 60

      # Static catalog entries (not discovered, always available)
      entries:
        - id: openai/gpt-4o
          alias: gpt-4o
          display-name: "GPT-4o"
          context-window: 128000
          reasoning: true
          max-output-tokens: 16384
          input: [TEXT, IMAGE, DOCUMENT]
          price-million-input: 2.50
          price-million-output: 10.00

        - id: openai/gpt-4.1
          alias: gpt-4.1
          display-name: "GPT-4.1"
          context-window: 1000000
          reasoning: true
          max-output-tokens: 32768
          input: [TEXT, IMAGE, DOCUMENT]
          price-million-input: 2.00
          price-million-output: 8.00

        - id: openai/gpt-5.0-flash
          alias: gpt-5-flash
          display-name: "GPT-5.0 Flash"
          context-window: 256000
          reasoning: true
          max-output-tokens: 16384
          input: [TEXT, IMAGE, DOCUMENT]
          beta: false
          price-million-input: 1.50
          price-million-output: 6.00

        - id: deepseek/deepseek-v4-flash
          alias: deepseek-v4-flash
          display-name: "DeepSeek V4 Flash"
          context-window: 262144
          reasoning: true
          max-output-tokens: 8192
          input: [TEXT]
          price-million-input: 0.28
          price-million-output: 1.10

        - id: anthropic/claude-opus-4-5
          alias: claude-opus-4-5
          display-name: "Claude Opus 4.5"
          context-window: 200000
          reasoning: true
          max-output-tokens: 32000
          input: [TEXT, IMAGE, DOCUMENT]
          price-million-input: 15.00
          price-million-output: 75.00

        - id: openai/dall-e-3
          alias: dall-e-3
          display-name: "DALL-E 3"
          context-window: 0
          reasoning: false
          max-output-tokens: 0
          input: [TEXT]
          price-million-input: 0
          price-million-output: 40.00  # per image

    # Global fallback chain (canonical IDs in priority order)
    fallback-chain:
      - deepseek/deepseek-v4-flash
      - openai/gpt-5.0-flash
      - openai/gpt-4.1

    # Per-provider model configurations (existing, enhanced)
    models:
      deepseek:
        provider: deepseek
        base-url: https://api.deepseek.com
        api-key: ${DEEPSEEK_API_KEY}
        model: deepseek-v4-flash
        retry:
          max-attempts: 3
          backoff: exponential
          base-delay-ms: 1000
        fallback:
          - openai/gpt-5.0-flash
        options:
          thinking.level: medium

      openai:
        provider: openai
        base-url: https://api.openai.com
        api-key: ${OPENAI_API_KEY}
        model: gpt-4o
        options:
          thinking.level: high

    # Global thinking defaults
    thinking:
      default-level: medium     # off | low | medium | high | max
      fallback-level: low       # used when primary model doesn't support thinking

  # Agent-level model overrides (via AgentConfig)
  agent:
    default-mode: react
    max-tool-rounds: 30

  # Subagent defaults (repeated for clarity)
  subagent:
    enabled: true
    max-concurrent: 1
    max-spawn-depth: 1
    archive-after-minutes: 60
```

---

## 3. Integration Points Summary

### 3.1 The SubagentSpawner integrates into the existing system at these points:

| Integration Point | Description |
|---|---|
| **ToolRegistry / ToolProvider** | `DelegateToAgentToolProvider` registers `delegate_to_agent` as a built-in tool. It's a `ToolProvider` (not a static `@Tool`), giving it access to the `AgentContext` to spawn sub-agents. |
| **AgentInvocationHandler** | Resolves `SubagentConfig` from `@Agent` annotation extensions and injects it into `AgentContext.runMetadata`. The existing `agentExtensions` map in `AgentConfig` already supports this pattern. |
| **AgentContext** | Gains a `RunMetadata` field with `subagentDepth`, `parentSessionKey`, `activeSubagentIds`, `thinkingLevel`. This is set during context construction and carried through the pipeline. |
| **ReActEngine / DefaultReActEngine** | No API change needed. The `delegate_to_agent` tool appears as a regular tool in the tool list. When the LLM calls it, `ToolExecutor.execute()` routes through `DelegateToAgentToolProvider`, which blocks on `SubagentSpawner.spawnSubagent()`. |
| **RespondStage** | No change needed. The `registerToolProvider()` call on `ToolRegistry` (or `getAllDefinitions()` override) injects the delegate tool into every pipeline invocation. |
| **Pipeline Stages** | All stages (`ContextBuildStage`, `SecurityCheckStage`, `PlanExecutionStage`, `RespondStage`, `ReflectionStage`, `MetricsStage`) run identically for sub-agents. The only difference is that sub-agents have a nested `sessionKey` and `subagentDepth > 0`. |
| **AgentRegistry** | Used by `SubagentSpawner` to look up child agent configurations. The existing `lookup()`, `findByCapability()`, `findAvailable()` methods support this. |
| **AgentConfigResolver** | Used to resolve child agent's `model`, `provider`, `systemPrompt`, and `extensions` (including subagent limits). The existing multi-source resolution (annotation > yml > DB) applies. |
| **SessionStore** | Used by `SubagentSessionManager` for hierarchical session key storage and archival. |
| **AgentHook** | Extended with `SubagentHook` sub-interface for subagent lifecycle callbacks (`subagentSpawning`, `subagentSpawned`, `subagentEnded`). |

### 3.2 Model Management integrates at these points:

| Integration Point | Description |
|---|---|
| **ChatFacade / DefaultChatFacade** | Gains `ModelResolutionService` dependency. `route()` delegates to it for intelligent model selection. `resolveModel()` uses the catalog for alias resolution. |
| **ChatModelRegistry** | Populated from `ModelCatalog` entries at startup. The catalog entries come from static YAML config + `@ChatModel` annotations + `ProviderDiscovery` auto-probing. |
| **ChatModel** interface | Gains `supportsThinkingLevel()`, `supportsVision()` default methods. Existing implementations need no changes. |
| **ChatRequest** | Gains `thinkingLevel`, `imageModel`, `pdfModel`, `mediaGenerationAutoFallback` fields. |
| **ModelCapabilities** | Gains `imageGeneration`, `videoGeneration`, `musicGeneration`, `pdfReading`, `maxThinkingLevel` fields. |
| **AgentContext.runMetadata** | Gains `thinkingLevel`, `resolvedModel`, `resolvedProvider` fields for per-invocation model resolution. |
| **AgentInvocationHandler** | Resolves `thinkingLevel` from `@Agent` annotations, sets `ChatRequest.thinkingLevel` and `thinkingBudget` before invocation. |
| **DefaultReActEngine** | Emits `thinking_start`, `thinking_delta`, `thinking_end` SSE events during streaming when thinking is enabled. |
| **AbstractChatModel** | Subclasses can read `ChatRequest.thinkingLevel` and map it to provider-specific API parameters (e.g., DeepSeek "thinking" block, OpenAI "reasoning_effort"). |
| **ProviderDiscovery** | New SPI. `OpenAICompatibleProviderDiscovery` is the default implementation. Auto-populates `ModelCatalog` at startup. |
| **FirstAvailableRouter** | Replaced by `ModelResolutionService.resolveFirstAvailable()` for default routing, but kept as a fallback. |

---

## 4. Migration Path

### 4.1 Phase 2a: Model Management (non-breaking)

1. **Add `ModelCatalogEntry`, `ModelCompatConfig`, `ModelInputType`** — new classes, no existing code changes.
2. **Add `ThinkingLevel` enum** — new class.
3. **Extend `ModelCapabilities`** — additive fields only, default to false/0 (backward compatible).
4. **Add `thinkingLevel` to `ChatRequest`** — new field with default null (backward compatible).
5. **Add `ModelResolutionService`** — new class, replaces nothing yet.
6. **Add `ProviderDiscovery` SPI + `OpenAICompatibleProviderDiscovery`** — new, no changes to existing.
7. **Add `AgentModelConfig`** — new class for modality-specific model resolution.
8. **Extend `@Agent` annotation extensions** — no code change needed, just document the new extension keys in `@Extension` values.

### 4.2 Phase 2b: Subagent System (additive, initially disabled)

1. **Add `SubagentConfig`, `SubagentSpawner`, `SubagentSessionManager`** — new classes.
2. **Add `RunMetadata`** — new class. Add a `runMetadata` field to `AgentContext` (non-breaking, the field starts with default values).
3. **Add `SubagentResult`, `SubagentHook`** — new classes.
4. **Add `DelegateToAgentToolProvider`** — new class. Register conditionally via auto-configuration (disabled by default until `lyclaw.subagent.enabled=true`).
5. **Extend `AgentHook`** — add `SubagentHook` sub-interface (non-breaking, existing hooks ignore the new callbacks).

### 4.3 Phase 2c: Integration (feature-flagged)

1. **Wire `SubagentSpawner` into auto-configuration** — only if `lyclaw.subagent.enabled=true`.
2. **Wire `DelegateToAgentToolProvider` into `ToolRegistry`** — via `ToolProvider` SPI.
3. **Wire `ModelResolutionService` into `DefaultChatFacade`** — replace direct `router.route()` calls with `resolutionService.resolveEffectiveModel()`, but keep `FirstAvailableRouter` as fallback.
4. **Add thinking SSE events to `DefaultReActEngine`** — backward compatible (new event types, existing clients ignore unknown events).
5. **Enable subagents for specific agents via `@Agent` extension keys** — per-agent opt-in.

### 4.4 Rollback Strategy

- All new classes are in separate packages (`lyclaw.react.subagent`, `lyclaw.chat.catalog`, `lyclaw.chat.config`), making them easy to remove.
- Feature flags in application.yml control all new behavior:
  - `lyclaw.subagent.enabled=false` disables delegation entirely
  - `lyclaw.chat.catalog.auto-discover=false` disables provider discovery
  - Thinking level defaults to OFF (no change in behavior)
- The existing `FirstAvailableRouter` continues to work as the default when no model catalog is configured.

---

## Appendix: File Manifest

All new files created in Phase 2:

```
lyclaw-framework/src/main/java/lyjew/com/lyclaw/
├── react/
│   ├── subagent/
│   │   ├── SubagentConfig.java          (new)
│   │   ├── SubagentSpawner.java         (new)
│   │   ├── SubagentResult.java          (new)
│   │   ├── SubagentHook.java            (new)
│   │   ├── SubagentSessionManager.java  (new)
│   │   ├── DelegateToAgentToolProvider.java (new)
│   │   └── ToolProviderContext.java     (new)
│   └── RunMetadata.java                 (new)
├── chat/
│   ├── catalog/
│   │   ├── ModelCatalogEntry.java       (new)
│   │   ├── ModelInputType.java          (new)
│   │   ├── ModelCompatConfig.java       (new)
│   │   ├── ModelCatalog.java            (new, interface)
│   │   ├── InMemoryModelCatalog.java    (new)
│   │   ├── ProviderDiscovery.java       (new, SPI)
│   │   └── OpenAICompatibleProviderDiscovery.java (new)
│   ├── config/
│   │   ├── AgentModelConfig.java        (new)
│   │   ├── ThinkingLevel.java           (new)
│   │   └── ModelResolutionService.java  (new)
│   └── DynamicFallbackChatModel.java    (new)

Modified existing files:
├── react/
│   └── AgentContext.java                (add runMetadata field, toSnapshot/restore)
├── model/
│   └── ChatRequest.java                 (add thinkingLevel, imageModel, pdfModel)
├── chat/
│   ├── ChatModel.java                   (add supportsThinkingLevel, supportsVision)
│   ├── ModelCapabilities.java           (add imageGeneration, videoGeneration, etc.)
│   └── DefaultChatFacade.java           (integrate ModelResolutionService)
├── annotation/
│   └── Agent.java                       (document new extension keys)
└── config/
    └── ChatProperties.java              (add catalog, thinking, subagent sections)

lyclaw-autoconfigure/src/main/java/lyjew/com/lyclaw/autoconfigure/autoconfigure/
└── SubagentAutoConfiguration.java       (new, conditional on lyclaw.subagent.enabled)
```
