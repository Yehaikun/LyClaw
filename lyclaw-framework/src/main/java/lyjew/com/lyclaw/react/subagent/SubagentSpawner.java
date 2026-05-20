package lyjew.com.lyclaw.react.subagent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.config.AgentConfigResolver;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.AgentHook;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.react.RunMetadata;
import lyjew.com.lyclaw.react.ToolExecutor;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lyjew.com.lyclaw.tool.ToolRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Core orchestrator for spawning and running subagents within the LyClaw agent system.
 *
 * <p>A {@code SubagentSpawner} handles the full lifecycle of a subagent delegation:</p>
 * <ol>
 *   <li>Resolve the parent's {@link SubagentConfig} from context attributes</li>
 *   <li>Validate against whitelist, depth, and concurrency limits</li>
 *   <li>Acquire a per-parent-session concurrency semaphore</li>
 *   <li>Build an isolated child {@link AgentContext} with correct {@link RunMetadata}</li>
 *   <li>Dispatch {@link SubagentHook} lifecycle callbacks</li>
 *   <li>Run the child agent via {@link AgentInvocationHandler#executeBlocking}</li>
 *   <li>Package the result as a {@link SubagentResult} and release resources</li>
 * </ol>
 *
 * <p>This class is the entry point called by {@link DelegateToAgentToolProvider} when
 * an LLM invokes the {@code delegate_to_agent} tool. It does not depend on
 * {@code AgentRegistry} -- child agent configuration is resolved through the
 * {@link AgentConfigResolver}.</p>
 *
 * <p>Concurrency is gated by per-parent-session semaphores stored in a
 * {@link ConcurrentHashMap}. Each parent session can run at most one subagent
 * at a time by default, configurable via {@link SubagentConfig#getMaxConcurrent()}.</p>
 */
public class SubagentSpawner {

    private static final Logger log = LoggerFactory.getLogger(SubagentSpawner.class);

    /** Attribute key for a pre-built SubagentConfig stored in AgentContext attributes. */
    public static final String ATTR_SUBAGENT_CONFIG = "subagentConfig";

    /** Attribute key for an agent extensions map used to derive SubagentConfig. */
    public static final String ATTR_AGENT_EXTENSIONS = "agentExtensions";

    /** Default subagent run timeout in seconds (5 minutes). */
    static final long DEFAULT_RUN_TIMEOUT_SECONDS = 300;

    /** Default maximum nesting depth for subagent spawning. */
    static final int DEFAULT_MAX_SPAWN_DEPTH = 3;

    /** Default maximum number of direct children per parent agent. */
    static final int DEFAULT_MAX_CHILDREN_PER_AGENT = 5;

    private final ChatFacade chatFacade;
    private final ReActEngine reActEngine;
    private final ToolRegistry toolRegistry;
    private final AgentConfigResolver configResolver;
    private final List<ReactivePipelineStage> defaultStages;
    private final List<AgentHook> defaultHooks;

    /**
     * Per-parent-session concurrency semaphores.
     *
     * <p>Keyed by the parent agent's session ID. The semaphore permit count is derived
     * from {@link SubagentConfig#getMaxConcurrent()}, defaulting to 1. This ensures
     * that a single parent agent does not overwhelm system resources by spawning
     * too many concurrent subagents.</p>
     */
    private final Map<String, Semaphore> concurrencySemaphores = new ConcurrentHashMap<>();

    /**
     * Constructs a SubagentSpawner.
     *
     * @param chatFacade     facade for making chat model calls
     * @param reActEngine    engine for running the ReAct reasoning loop
     * @param toolRegistry   registry for resolving tool definitions and executing tools
     * @param configResolver resolver for looking up agent configurations by ID
     * @param defaultStages  the default pipeline stages applied to every subagent
     * @param defaultHooks   the default hooks applied to every subagent lifecycle
     */
    public SubagentSpawner(ChatFacade chatFacade,
                           ReActEngine reActEngine,
                           ToolRegistry toolRegistry,
                           AgentConfigResolver configResolver,
                           List<ReactivePipelineStage> defaultStages,
                           List<AgentHook> defaultHooks) {
        this.chatFacade = chatFacade;
        this.reActEngine = reActEngine;
        this.toolRegistry = toolRegistry;
        this.configResolver = configResolver;
        this.defaultStages = defaultStages != null
                ? List.copyOf(defaultStages) : List.of();
        this.defaultHooks = defaultHooks != null
                ? List.copyOf(defaultHooks) : List.of();
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Spawns and runs a subagent, returning the result as a {@link Mono}.
     *
     * <p>This is the main entry point. It validates limits, acquires a concurrency
     * semaphore, builds the child context, dispatches hooks, executes the child agent,
     * and packages the result. The semaphore is always released in {@code doFinally()}
     * to prevent resource leaks, even on timeout or error.</p>
     *
     * @param targetAgentId the identifier of the agent to delegate to
     * @param task          the task description for the subagent
     * @param options       optional overrides (e.g. mode, runTimeoutSeconds)
     * @param parentCtx     the parent agent's context
     * @return a Mono that completes with the subagent result (never errors)
     */
    public Mono<SubagentResult> spawnSubagent(String targetAgentId,
                                               String task,
                                               Map<String, Object> options,
                                               AgentContext parentCtx) {
        long startTime = System.currentTimeMillis();

        // --- Guard: required parameters ---
        if (targetAgentId == null || targetAgentId.isBlank()) {
            return Mono.just(SubagentResult.error("targetAgentId is required"));
        }
        if (task == null || task.isBlank()) {
            return Mono.just(SubagentResult.error("task is required for agent '" + targetAgentId + "'"));
        }

        // --- Step 1: Resolve configuration ---
        SubagentConfig config = resolveSubagentConfig(parentCtx);
        Map<String, Object> opts = options != null ? options : Collections.emptyMap();

        // --- Step 2a: Validate whitelist ---
        List<String> allowAgents = config.getAllowAgents();
        if (allowAgents != null && !allowAgents.isEmpty()) {
            boolean wildcard = allowAgents.contains("*");
            boolean explicit = allowAgents.contains(targetAgentId);
            if (!wildcard && !explicit) {
                String msg = String.format(
                        "Agent '%s' is not in the allowed agents list: %s",
                        targetAgentId, allowAgents);
                log.warn("Subagent spawn rejected (whitelist): {}", msg);
                return Mono.just(SubagentResult.rejected(targetAgentId, msg));
            }
        }

        // --- Step 2b: Validate depth limit ---
        int currentDepth = getCurrentDepth(parentCtx);
        int maxDepth = config.getMaxSpawnDepth() > 0
                ? config.getMaxSpawnDepth() : DEFAULT_MAX_SPAWN_DEPTH;
        if (currentDepth >= maxDepth) {
            String msg = String.format(
                    "Max spawn depth exceeded: current=%d, max=%d, target='%s'",
                    currentDepth, maxDepth, targetAgentId);
            log.warn("Subagent spawn rejected (depth): {}", msg);
            return Mono.just(SubagentResult.rejected(targetAgentId, msg));
        }

        // --- Step 2c: Validate children limit ---
        int maxChildren = config.getMaxChildrenPerAgent() > 0
                ? config.getMaxChildrenPerAgent() : DEFAULT_MAX_CHILDREN_PER_AGENT;
        int activeChildren = parentCtx.getActiveSubagentCount();
        if (activeChildren >= maxChildren) {
            String msg = String.format(
                    "Max children per agent reached: active=%d, max=%d, target='%s'",
                    activeChildren, maxChildren, targetAgentId);
            log.warn("Subagent spawn rejected (children): {}", msg);
            return Mono.just(SubagentResult.rejected(targetAgentId, msg));
        }

        // --- Build session key ---
        String parentSessionId = parentCtx.getSessionId();
        String shortUuid = UUID.randomUUID().toString().substring(0, 8);
        String sessionKey = parentSessionId + "/subagent/" + targetAgentId + "/" + shortUuid;

        // --- Register as active subagent on parent ---
        parentCtx.addActiveSubagent(targetAgentId);

        // --- Step 3: Acquire concurrency semaphore (per parent session key) ---
        Semaphore semaphore = concurrencySemaphores.computeIfAbsent(parentSessionId,
                k -> new Semaphore(config.getMaxConcurrent() > 0
                        ? config.getMaxConcurrent() : 1, true));

        long timeoutSeconds = resolveRunTimeout(opts, config);

        return Mono.fromCallable(() -> {
                    boolean acquired = semaphore.tryAcquire(10, TimeUnit.SECONDS);
                    if (!acquired) {
                        return SubagentResult.error(
                                "Timed out waiting for concurrency slot (session: "
                                        + parentSessionId + ", agent: " + targetAgentId + ")");
                    }
                    return null; // proceed signal
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(proceed -> runSubagent(
                        targetAgentId, task, sessionKey, config, parentCtx))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .onErrorResume(throwable -> {
                    if (throwable instanceof java.util.concurrent.TimeoutException) {
                        log.warn("Subagent timed out: agentId={} sessionKey={} timeout={}s",
                                targetAgentId, sessionKey, timeoutSeconds);
                        return Mono.just(SubagentResult.timeout(targetAgentId, timeoutSeconds));
                    }
                    log.error("Subagent execution failed: agentId={} sessionKey={}",
                            targetAgentId, sessionKey, throwable);
                    return Mono.just(SubagentResult.error(
                            "Subagent execution failed: " + throwable.getMessage()));
                })
                .doFinally(signalType -> {
                    semaphore.release();
                    parentCtx.removeActiveSubagent(targetAgentId);
                });
    }

    /**
     * Resolves the parent agent's {@link SubagentConfig} from context attributes.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Look for an explicit {@code SubagentConfig} stored at key
     *       {@value #ATTR_SUBAGENT_CONFIG} in the context attributes</li>
     *   <li>Look for an {@code agentExtensions} map at key
     *       {@value #ATTR_AGENT_EXTENSIONS} and extract relevant fields</li>
     *   <li>Fall back to {@link SubagentConfig#defaults()}</li>
     * </ol>
     *
     * @param ctx the agent context
     * @return the resolved subagent configuration (never null)
     */
    public SubagentConfig resolveSubagentConfig(AgentContext ctx) {
        // Priority 1: explicit SubagentConfig attribute
        SubagentConfig explicit = ctx.getAttribute(ATTR_SUBAGENT_CONFIG);
        if (explicit != null) {
            return explicit;
        }

        // Priority 2: derive from agentExtensions map
        @SuppressWarnings("unchecked")
        Map<String, Object> extensions = ctx.getAttribute(ATTR_AGENT_EXTENSIONS);

        if (extensions != null && !extensions.isEmpty()) {
            SubagentConfig.Builder builder = SubagentConfig.builder();

            if (extensions.get("allowAgents") instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<String> allowList = (List<String>) list;
                builder.allowAgents(allowList);
            }
            if (extensions.get("delegationMode") instanceof String mode) {
                builder.delegationMode(mode);
            }
            if (extensions.get("maxSpawnDepth") instanceof Number n) {
                builder.maxSpawnDepth(n.intValue());
            }
            if (extensions.get("maxChildrenPerAgent") instanceof Number n) {
                builder.maxChildrenPerAgent(n.intValue());
            }
            if (extensions.get("maxConcurrent") instanceof Number n) {
                builder.maxConcurrent(n.intValue());
            }
            if (extensions.get("runTimeoutSeconds") instanceof Number n) {
                builder.runTimeoutSeconds(n.intValue());
            }
            if (extensions.get("requireAgentId") instanceof Boolean b) {
                builder.requireAgentId(b);
            }
            if (extensions.get("model") instanceof String model) {
                builder.model(model);
            }
            if (extensions.get("thinking") instanceof String thinking) {
                builder.thinking(thinking);
            }

            return builder.build();
        }

        // Priority 3: framework defaults
        return SubagentConfig.defaults();
    }

    /**
     * Builds the JSON Schema tool definition for the {@code delegate_to_agent} tool.
     *
     * <p>This static method constructs a {@link ToolDefinition} that describes the
     * {@code delegate_to_agent} function to the LLM. The generated definition includes
     * three parameters:
     * <ul>
     *   <li>{@code agentId} -- the target subagent identifier (required when
     *       {@code config.isRequireAgentId()} is true)</li>
     *   <li>{@code task} -- the task description for the subagent (always required)</li>
     *   <li>{@code mode} -- delegation mode: "suggest" or "prefer" (optional)</li>
     * </ul>
     *
     * @param config the subagent configuration controlling tool behavior
     * @return a fully populated ToolDefinition ready for LLM tool registration
     */
    public static ToolDefinition buildDelegateToolDefinition(SubagentConfig config) {
        Map<String, Object> properties = new LinkedHashMap<>();

        // agentId parameter
        Map<String, Object> agentIdProp = new LinkedHashMap<>();
        agentIdProp.put("type", "string");
        List<String> agents = config.getAllowAgents() != null && !config.getAllowAgents().isEmpty()
                ? config.getAllowAgents()
                : List.of("any");
        agentIdProp.put("description",
                "The ID of the specialized agent to delegate to. "
                        + "Available agents: " + String.join(", ", agents));
        properties.put("agentId", agentIdProp);

        // task parameter
        Map<String, Object> taskProp = new LinkedHashMap<>();
        taskProp.put("type", "string");
        taskProp.put("description",
                "The task description for the subagent. Be specific about what to "
                        + "accomplish and the expected output format.");
        properties.put("task", taskProp);

        // mode parameter
        Map<String, Object> modeProp = new LinkedHashMap<>();
        modeProp.put("type", "string");
        modeProp.put("enum", List.of("suggest", "prefer"));
        modeProp.put("description",
                "Delegation mode: 'suggest' means the subagent may decline, "
                        + "'prefer' means strong preference for delegation. "
                        + "Defaults to 'suggest'.");
        properties.put("mode", modeProp);

        // required fields
        List<String> required = new ArrayList<>();
        if (config.isRequireAgentId()) {
            required.add("agentId");
        }
        required.add("task");

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", required);
        parameters.put("additionalProperties", false);

        return ToolDefinition.builder()
                .name("delegate_to_agent")
                .displayName("Delegate to Agent")
                .description("Delegate a task to a specialized subagent. Use this when a "
                        + "task is better handled by an agent with specific capabilities, "
                        + "or when parallelizing work across multiple agents.")
                .parameters(parameters)
                .source("builtin")
                .readOnly(false)
                .timeout(config.getRunTimeoutSeconds() * 1000L)
                .build();
    }

    // ========================================================================
    // Private Implementation
    // ========================================================================

    /**
     * Core execution: builds the child context, dispatches hooks, runs the
     * child ReAct loop via {@link ReActEngine#execute}, and returns a
     * {@link SubagentResult}.
     */
    private Mono<SubagentResult> runSubagent(String targetAgentId,
                                              String task,
                                              String sessionKey,
                                              SubagentConfig config,
                                              AgentContext parentCtx) {
        long startTime = System.currentTimeMillis();

        return Mono.fromCallable(() -> {
                    // Step 4a: Build isolated child context
                    AgentContext childCtx = buildChildContext(
                            targetAgentId, task, sessionKey, config, parentCtx);

                    // Step 4b: Dispatch subagentSpawning hooks
                    dispatchSubagentSpawning(childCtx);

                    // Step 4c: Build tool executor and wrap with hooks
                    ToolExecutor toolExecutor = buildChildToolExecutor(childCtx);
                    List<AgentHook> sorted = new ArrayList<>(defaultHooks);
                    sorted.sort(java.util.Comparator.comparingInt(AgentHook::getOrder));
                    for (AgentHook hook : sorted) {
                        toolExecutor = hook.wrapToolExecutor(toolExecutor, childCtx);
                    }

                    // Step 4d: Execute ReAct loop (blocking within the Mono)
                    ChatRequest childRequest = childCtx.getChatRequest();
                    String output = reActEngine.execute(chatFacade, childRequest, toolExecutor);

                    long elapsed = System.currentTimeMillis() - startTime;

                    // Step 4e: Build result
                    SubagentResult result = SubagentResult.success(
                            sessionKey, targetAgentId, output, elapsed, 0, 0);

                    // Step 4f: Dispatch post-execution hooks
                    dispatchSubagentSpawned(childCtx, result);
                    dispatchSubagentEnded(childCtx, result);

                    return result;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Builds a {@link ToolExecutor} that delegates to the {@link ToolRegistry}
     * for the child subagent context.
     */
    private ToolExecutor buildChildToolExecutor(AgentContext childCtx) {
        return (toolName, toolCallId, argumentsJson) -> {
            try {
                lyjew.com.lyclaw.model.ToolCall toolCall = lyjew.com.lyclaw.model.ToolCall.builder()
                        .toolCallId(toolCallId)
                        .name(toolName)
                        .arguments(argumentsJson)
                        .build();
                lyjew.com.lyclaw.tool.ToolExecutionResult result = toolRegistry.execute(toolCall, null);
                if (!result.isSuccess()) {
                    result = toolRegistry.executeByName(toolName, toolCallId, argumentsJson, childCtx.getChatRequest());
                }
                if (result.isSuccess()) {
                    return result.getResult() != null ? result.getResult() : "";
                }
                return "Error: " + (result.getError() != null ? result.getError() : "unknown");
            } catch (Exception e) {
                log.error("Subagent tool execution failed: tool={} toolCallId={}", toolName, toolCallId, e);
                return "Error: " + e.getMessage();
            }
        };
    }

    /**
     * Builds an isolated {@link AgentContext} for the child subagent.
     *
     * <p>The child context receives:
     * <ul>
     *   <li>A fresh {@link ChatRequest} containing only the task message</li>
     *   <li>A {@link RunMetadata} with depth incremented and parent session key set</li>
     *   <li>The parent's sandbox level (copied for consistency)</li>
     *   <li>The resolved model and provider from config (if specified)</li>
     * </ul>
     */
    private AgentContext buildChildContext(String targetAgentId,
                                            String task,
                                            String sessionKey,
                                            SubagentConfig config,
                                            AgentContext parentCtx) {
        String systemPrompt = "You are a subagent: " + targetAgentId;
        String childModel = config.getModel() != null && !config.getModel().isEmpty()
                ? config.getModel() : null;
        String childProvider = null;

        // Build a minimal ChatRequest with just the task message
        ChatRequest childRequest = ChatRequest.builder()
                .sessionId(sessionKey)
                .messages(new ArrayList<>(List.of(Message.user(task))))
                .model(childModel != null ? childModel : "")
                .agentId(targetAgentId)
                .stream(false)
                .toolChoice("auto")
                .build();

        // Resolve and attach tool definitions
        List<ToolDefinition> tools = toolRegistry.getAllDefinitions(childRequest);
        childRequest.setTools(tools);

        // Create the child agent context
        AgentContext childCtx = new AgentContext(
                sessionKey, task, systemPrompt,
                toolRegistry, null, null,
                targetAgentId, targetAgentId);

        childCtx.setChatRequest(childRequest);
        childCtx.setLifecycle(AgentContext.Lifecycle.TRANSIENT);

        // --- Set up RunMetadata for subagent hierarchy tracking ---
        RunMetadata parentMeta = parentCtx.getRunMetadata();
        RunMetadata childMeta = RunMetadata.childOf(parentMeta, targetAgentId);
        childMeta.setParentSessionKey(parentCtx.getSessionId());

        // Propagate resolved model / provider from parent or config
        if (childModel != null && !childModel.isEmpty()) {
            childMeta.setResolvedModel(childModel);
        }
        if (childProvider != null && !childProvider.isEmpty()) {
            childMeta.setResolvedProvider(childProvider);
        }

        // Propagate thinking level from config or parent
        String thinkingLevel = config.getThinking() != null && !config.getThinking().isEmpty()
                ? config.getThinking()
                : parentMeta.getThinkingLevel();
        if (thinkingLevel != null) {
            childMeta.setThinkingLevel(thinkingLevel);
        }

        // Set on child context
        childCtx.setRunMetadata("subagentDepth", childMeta.getSubagentDepth());
        childCtx.setRunMetadata("parentSessionKey", childMeta.getParentSessionKey());
        childCtx.setRunMetadata("subagentTargetAgentId", targetAgentId);

        // Copy sandbox level from parent
        SandboxLevel parentSandbox = parentCtx.getSandboxLevel();
        childCtx.setSandboxLevel(parentSandbox != null
                ? parentSandbox : SandboxLevel.SANDBOX);

        // Set workspace directory from parent if available
        String workspaceDir = parentCtx.getWorkspaceDir();
        if (workspaceDir != null && !workspaceDir.isEmpty()) {
            childCtx.setWorkspaceDir(workspaceDir);
        }

        // Set thinking/verbose/reasoning levels
        if (thinkingLevel != null) {
            childCtx.setThinkingLevel(thinkingLevel);
        }
        String verbose = parentCtx.getVerboseLevel();
        if (verbose != null) {
            childCtx.setVerboseLevel(verbose);
        }

        return childCtx;
    }

    /**
     * Dispatches {@link SubagentHook#subagentSpawning(AgentContext)} to all
     * registered hooks that implement the {@link SubagentHook} subinterface.
     */
    private void dispatchSubagentSpawning(AgentContext childCtx) {
        for (AgentHook hook : defaultHooks) {
            if (hook instanceof SubagentHook sh) {
                try {
                    sh.subagentSpawning(childCtx);
                } catch (Exception e) {
                    log.warn("SubagentHook {} threw during subagentSpawning for agentId={}",
                            hook.getClass().getSimpleName(), childCtx.getAgentId(), e);
                }
            }
        }
    }

    /**
     * Dispatches {@link SubagentHook#subagentSpawned(AgentContext, SubagentResult)}
     * to all registered hooks that implement the {@link SubagentHook} subinterface.
     */
    private void dispatchSubagentSpawned(AgentContext childCtx, SubagentResult result) {
        for (AgentHook hook : defaultHooks) {
            if (hook instanceof SubagentHook sh) {
                try {
                    sh.subagentSpawned(childCtx, result);
                } catch (Exception e) {
                    log.warn("SubagentHook {} threw during subagentSpawned for agentId={}",
                            hook.getClass().getSimpleName(), childCtx.getAgentId(), e);
                }
            }
        }
    }

    /**
     * Dispatches {@link SubagentHook#subagentEnded(AgentContext, SubagentResult)}
     * to all registered hooks that implement the {@link SubagentHook} subinterface.
     */
    private void dispatchSubagentEnded(AgentContext childCtx, SubagentResult result) {
        for (AgentHook hook : defaultHooks) {
            if (hook instanceof SubagentHook sh) {
                try {
                    sh.subagentEnded(childCtx, result);
                } catch (Exception e) {
                    log.warn("SubagentHook {} threw during subagentEnded for agentId={}",
                            hook.getClass().getSimpleName(), childCtx.getAgentId(), e);
                }
            }
        }
    }

    /**
     * Calculates the current subagent nesting depth from the parent context's
     * typed {@link RunMetadata}.
     */
    private int getCurrentDepth(AgentContext ctx) {
        return ctx.getRunMetadata().getSubagentDepth();
    }

    /**
     * Resolves the run timeout in seconds, preferring an explicit value in the
     * options map over the config's value, falling back to the default.
     */
    private long resolveRunTimeout(Map<String, Object> options, SubagentConfig config) {
        if (options.containsKey("runTimeoutSeconds")
                && options.get("runTimeoutSeconds") instanceof Number n) {
            return Math.max(1, n.longValue());
        }
        long fromConfig = config.getRunTimeoutSeconds();
        return fromConfig > 0 ? fromConfig : DEFAULT_RUN_TIMEOUT_SECONDS;
    }
}
