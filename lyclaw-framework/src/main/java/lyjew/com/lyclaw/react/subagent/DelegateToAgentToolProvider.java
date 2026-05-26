package lyjew.com.lyclaw.react.subagent;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.DefaultReActEngine;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import org.springframework.http.codec.ServerSentEvent;
import lyjew.com.lyclaw.tool.ToolProvider;
import lyjew.com.lyclaw.tool.ToolProviderRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Built-in {@link ToolProvider} that injects the {@code delegate_to_agent} tool
 * into every agent's tool set, enabling LLM-driven task delegation to subagents.
 *
 * <p>When enabled, this provider adds a {@code delegate_to_agent} function tool
 * definition via {@link #provideTools(ToolProviderRequest)}. When the LLM invokes
 * the tool, {@link #execute(ToolCall, Object)} parses the arguments (handling both
 * {@code Map<String, Object>} and JSON String formats), resolves the target agent
 * configuration, and delegates to {@link SubagentSpawner#spawnSubagent} to run
 * the child agent. The result is formatted as a {@link ToolExecutionResult} for
 * the parent LLM to consume.</p>
 *
 * <p>Usage: register this provider with {@link lyjew.com.lyclaw.tool.ToolRegistry}
 * so that every agent automatically gains the ability to delegate tasks to
 * specialized subagents.</p>
 */
public class DelegateToAgentToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(DelegateToAgentToolProvider.class);

    /** Shared Jackson ObjectMapper for parsing JSON tool call arguments. */
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final SubagentSpawner spawner;
    private final boolean enabled;

    /** Lazily cached tool definition, rebuilt when configuration changes. */
    private volatile ToolDefinition cachedDefinition;

    /**
     * Constructs a DelegateToAgentToolProvider.
     *
     * @param spawner the subagent spawner used to execute delegated tasks
     * @param enabled whether the delegate_to_agent tool should be available
     */
    public DelegateToAgentToolProvider(SubagentSpawner spawner, boolean enabled) {
        this.spawner = spawner;
        this.enabled = enabled;
    }

    // ========================================================================
    // ToolProvider implementation
    // ========================================================================

    /**
     * This provider is not dynamic. The delegate_to_agent tool is a static
     * built-in that does not change between requests.
     */
    @Override
    public boolean isDynamic() {
        return false;
    }

    /**
     * Provides the delegate_to_agent tool definition and its executor.
     *
     * <p>If the provider is disabled, or the request is null, an empty result
     * is returned. Otherwise, the tool definition is built (or retrieved from
     * cache) and paired with an executor that calls {@link #execute(ToolCall, Object)}.</p>
     *
     * @param request the tool provider request containing the current ChatRequest
     * @return a ToolProviderResult containing the delegate tool (or empty if disabled)
     */
    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        ToolProviderResult result = new ToolProviderResult();
        if (!enabled) {
            return result;
        }
        if (request == null || request.getChatRequest() == null) {
            return result;
        }

        // Phase 1: per-Agent delegation control — check config from request extras
        // The AgentInvocationHandler sets "agent.delegation" on ChatRequest.extras
        Map<String, Object> delConfig = getDelegationConfig(request.getChatRequest());
        if (delConfig == null || delConfig.isEmpty()) {
            return result;
        }
        String mode = (String) delConfig.getOrDefault("delegationMode", "none");
        if ("none".equals(mode)) {
            return result;
        }
        @SuppressWarnings("unchecked")
        List<String> allowAgents = (List<String>) delConfig.get("allowAgents");
        if (allowAgents == null || allowAgents.isEmpty()) {
            return result;
        }

        ToolDefinition definition = getOrBuildDefinition(request.getChatRequest());
        if (definition != null) {
            // Extract the AgentContext from request attributes for use in execution
            Object contextAttr = request.getAttribute("agentContext");

            result.add(definition, (toolName, toolCallId, argumentsJson) -> {
                ToolCall toolCall = ToolCall.builder()
                        .toolCallId(toolCallId)
                        .name(toolName)
                        .arguments(argumentsJson)
                        .build();
                ToolExecutionResult execResult = execute(toolCall, contextAttr);
                if (execResult.isSuccess()) {
                    return execResult.getResult() != null ? execResult.getResult() : "";
                }
                return "Error: " + (execResult.getError() != null
                        ? execResult.getError() : "unknown error");
            });
        }

        return result;
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Returns whether this provider is enabled.
     *
     * @return true if delegation is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the tool definitions provided by this provider.
     *
     * @param request the chat request (used to resolve the SubagentConfig for
     *                building the correct tool definition)
     * @return a singleton list containing the delegate_to_agent definition,
     *         or an empty list if disabled
     */
    public List<ToolDefinition> getDefinitions(ChatRequest request) {
        if (!enabled) {
            return Collections.emptyList();
        }
        ToolDefinition def = getOrBuildDefinition(request);
        return def != null ? List.of(def) : Collections.emptyList();
    }

    /**
     * Executes the delegate_to_agent tool call.
     *
     * <p>Parses the tool call arguments (both {@code Map<String, Object>} and JSON
     * String formats are supported), validates required fields, resolves the target
     * agent, and calls {@link SubagentSpawner#spawnSubagent}. The subagent result
     * is blocking-waited and formatted into a {@link ToolExecutionResult}.</p>
     *
     * @param toolCall the tool call containing the delegate_to_agent arguments
     * @param context  the execution context; should be an {@link AgentContext}
     *                 (or a Map/ToolProviderRequest from which one can be resolved)
     * @return the tool execution result
     */
    public ToolExecutionResult execute(ToolCall toolCall, Object context) {
        if (!enabled) {
            return ToolExecutionResult.failure(
                    "Delegate to agent is disabled", "delegate_to_agent");
        }

        long startTime = System.currentTimeMillis();

        // Resolve AgentContext from the context object
        AgentContext agentCtx = resolveAgentContext(context);
        if (agentCtx == null) {
            return ToolExecutionResult.failure(
                    "AgentContext not available for delegate_to_agent execution. "
                    + "The tool was invoked without a valid agent context.",
                    "delegate_to_agent");
        }

        // Parse arguments (handles Map and JSON String)
        Map<String, Object> args;
        try {
            args = parseArguments(toolCall.getArguments());
        } catch (Exception e) {
            log.warn("Failed to parse delegate_to_agent arguments: {}",
                    toolCall.getArguments(), e);
            return ToolExecutionResult.failure(
                    "Invalid arguments for delegate_to_agent: " + e.getMessage(),
                    "delegate_to_agent");
        }

        // Extract fields
        String targetAgentId = getStringArg(args, "agentId");
        String task = getStringArg(args, "task");
        String mode = getStringArg(args, "mode");

        // task is always required
        if (task == null || task.isBlank()) {
            return ToolExecutionResult.failure(
                    "Missing required 'task' argument for delegate_to_agent",
                    "delegate_to_agent");
        }

        // Resolve config and validate agentId requirement
        SubagentConfig config = spawner.resolveSubagentConfig(agentCtx);

        if ((targetAgentId == null || targetAgentId.isBlank()) && config.isRequireAgentId()) {
            List<String> allowAgents = config.getAllowAgents();
            return ToolExecutionResult.failure(
                    "Missing required 'agentId' argument for delegate_to_agent. "
                    + "Available agents: " + (allowAgents != null && !allowAgents.isEmpty()
                            ? String.join(", ", allowAgents) : "none configured"),
                    "delegate_to_agent");
        }

        // Auto-select agentId when not specified but not required
        if (targetAgentId == null || targetAgentId.isBlank()) {
            List<String> allowAgents = config.getAllowAgents();
            if (allowAgents != null && allowAgents.size() == 1) {
                targetAgentId = allowAgents.get(0);
                log.debug("Auto-selected only allowed agent: {}", targetAgentId);
            } else {
                return ToolExecutionResult.failure(
                        "No agentId specified and multiple agents are available. "
                        + "Please specify one of: " + (allowAgents != null
                                ? String.join(", ", allowAgents) : "none configured"),
                        "delegate_to_agent");
            }
        }

        // Build options map for spawner
        Map<String, Object> options = new LinkedHashMap<>();
        if (mode != null && !mode.isBlank()) {
            options.put("mode", mode);
        }
        options.put("runTimeoutSeconds", config.getRunTimeoutSeconds());

        // Execute the subagent
        String finalAgentId = targetAgentId;
        try {
            long timeout = config.getRunTimeoutSeconds() > 0
                    ? config.getRunTimeoutSeconds() + 5
                    : SubagentSpawner.DEFAULT_RUN_TIMEOUT_SECONDS + 5;

            // Phase 4: get the Flux.create emitter from the shared map
            java.util.function.Consumer<ServerSentEvent<String>> progressEmitter = resolveEmitter(agentCtx, toolCall);
            SubagentResult subagentResult;
            if (progressEmitter != null) {
                subagentResult = spawner.spawnSubagent(
                                finalAgentId, task, options, agentCtx, progressEmitter)
                        .block(Duration.ofSeconds(timeout));
            } else {
                // fallback: no ProgressBus (backward compatible)
                subagentResult = spawner.spawnSubagent(
                                finalAgentId, task, options, agentCtx)
                        .block(Duration.ofSeconds(timeout));
            }

            if (subagentResult == null) {
                return ToolExecutionResult.failure(
                        "Subagent execution returned null for agent '" + finalAgentId + "'",
                        "delegate_to_agent");
            }

            // Format the result as an observation string for the parent LLM
            String observation = subagentResult.formatAsObservation();
            long elapsed = System.currentTimeMillis() - startTime;

            return ToolExecutionResult.builder()
                    .success(subagentResult.isSuccess())
                    .result(observation)
                    .error(subagentResult.getError() != null
                            ? subagentResult.getError() : "")
                    .elapsedMs(elapsed)
                    .toolName("delegate_to_agent")
                    .metadata(Map.of(
                            "subagentId", finalAgentId,
                            "subagentSessionKey", subagentResult.getSessionKey() != null
                                    ? subagentResult.getSessionKey() : "",
                            "subagentSuccess", subagentResult.isSuccess(),
                            "subagentDurationMs", subagentResult.getDurationMs()
                    ))
                    .build();

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Subagent execution failed for agentId={}", finalAgentId, e);
            return ToolExecutionResult.builder()
                    .success(false)
                    .error("Subagent execution failed: " + e.getMessage())
                    .elapsedMs(elapsed)
                    .toolName("delegate_to_agent")
                    .metadata(Map.of("subagentId", finalAgentId))
                    .build();
        }
    }

    // ========================================================================
    // Private helpers
    // ========================================================================

    /**
     * Returns the cached or newly-built tool definition.
     *
     * <p>The definition is lazily built and cached. It is built with a default
     * config since the actual enforcement happens in {@link #execute}.</p>
     */
    private ToolDefinition getOrBuildDefinition(ChatRequest request) {
        if (cachedDefinition == null) {
            synchronized (this) {
                if (cachedDefinition == null) {
                    cachedDefinition = SubagentSpawner.buildDelegateToolDefinition(
                            SubagentConfig.defaults());
                }
            }
        }
        return cachedDefinition;
    }

    /**
     * Resolves an {@link AgentContext} from the provided context object.
     *
     * <p>Resolution strategies (tried in order):
     * <ol>
     *   <li>Direct {@code instanceof AgentContext} check</li>
     *   <li>If context is a {@code ToolProviderRequest}, read its "agentContext" attribute</li>
     *   <li>If context is a {@code Map}, look for "agentContext" key</li>
     *   <li>Fallback -- returns null (caller must handle)</li>
     * </ol>
     *
     * <p>TODO: Once ToolProviderContext is introduced as a standardized wrapper,
     * simplify this method to extract AgentContext directly from it.</p>
     *
     * @param context the raw context object from the tool execution call
     * @return the resolved AgentContext, or null if unavailable
     */
    private AgentContext resolveAgentContext(Object context) {
        // Strategy 1: direct instanceof check
        if (context instanceof AgentContext ac) {
            return ac;
        }

        // Strategy 2: ToolProviderRequest
        if (context instanceof ToolProviderRequest tpr) {
            Object attr = tpr.getAttribute("agentContext");
            if (attr instanceof AgentContext ac) {
                return ac;
            }
        }

        // Strategy 3: Map-style context
        if (context instanceof Map<?, ?> map) {
            Object acObj = map.get("agentContext");
            if (acObj instanceof AgentContext ac) {
                return ac;
            }
        }

        // TODO: When a standardized ToolProviderContext wrapper is introduced,
        // add a fourth strategy to extract AgentContext from it directly,
        // eliminating the need for the above heuristic checks.

        log.debug("Unable to resolve AgentContext from context type: {}",
                context != null ? context.getClass().getName() : "null");
        return null;
    }

    /**
     * Parses tool call arguments, handling both {@code Map<String, Object>} and
     * JSON {@code String} formats.
     *
     * @param arguments the raw arguments (Map, JSON String, or null)
     * @return parsed argument map (never null)
     * @throws Exception if JSON parsing fails
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(Object arguments) throws Exception {
        if (arguments == null) {
            return Collections.emptyMap();
        }

        if (arguments instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) arguments);
        }

        if (arguments instanceof String json) {
            if (json.isBlank()) {
                return Collections.emptyMap();
            }
            return objectMapper.readValue(json,
                    new TypeReference<LinkedHashMap<String, Object>>() {});
        }

        // Last resort: serialize to JSON and parse back
        String jsonStr = objectMapper.writeValueAsString(arguments);
        return objectMapper.readValue(jsonStr,
                new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    /**
     * Extracts a string value from the argument map, returning null if the key
     * is missing or the value is not a string.
     */
    /**
     * Resolve the ProgressBus from the shared ConcurrentHashMap by sessionId prefix.
     */
    private java.util.function.Consumer<ServerSentEvent<String>> resolveEmitter(AgentContext agentCtx,
                                                                                  ToolCall toolCall) {
        if (agentCtx == null) return null;
        String sessionId = agentCtx.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) return null;
        if (toolCall != null && toolCall.getToolCallId() != null && !toolCall.getToolCallId().isEmpty()) {
            var emitter = DefaultReActEngine.getEmitter(sessionId, toolCall.getToolCallId());
            if (emitter != null) {
                log.info("resolveEmitter: found by toolCallId | sessionId={} toolCallId={}",
                        sessionId, toolCall.getToolCallId());
                return emitter;
            }
        }
        for (java.util.Map.Entry<String, java.util.function.Consumer<ServerSentEvent<String>>> e :
                DefaultReActEngine.getEmitters().entrySet()) {
            if (e.getKey().startsWith(sessionId + ":")) {
                log.info("resolveEmitter: found by scan | key={}", e.getKey());
                return e.getValue();
            }
        }
        log.warn("resolveEmitter: not found for sessionId={}", sessionId);
        return null;
    }

    private String getStringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof String s) {
            return s;
        }
        return null;
    }

    /**
     * Retrieves per-Agent delegation configuration from the ChatRequest extras.
     * Set by AgentInvocationHandler based on @Agent annotation or YAML config.
     *
     * @param request the chat request containing extras
     * @return delegation config map, or empty map if not configured
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getDelegationConfig(ChatRequest request) {
        if (request == null) return Collections.emptyMap();
        Map<String, Object> extras = request.getExtras();
        if (extras == null || extras.isEmpty()) return Collections.emptyMap();
        Object config = extras.get("agent.delegation");
        if (config instanceof Map) {
            return (Map<String, Object>) config;
        }
        return Collections.emptyMap();
    }
}
