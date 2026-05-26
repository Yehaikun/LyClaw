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
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
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
 *   <li>Create child session in-memory</li>
 *   <li>Acquire a per-parent-session concurrency semaphore</li>
 *   <li>Build an isolated child {@link AgentContext} with correct {@link RunMetadata}</li>
 *   <li>Dispatch {@link SubagentHook} lifecycle callbacks</li>
 *   <li>Run the child agent via {@link ReActEngine}</li>
 *   <li>Package the result as a {@link SubagentResult} and release resources</li>
 * </ol>
 *
 * <p>Uses {@link SessionFactory} interface to avoid direct dependency on
 * {@code SessionManager} in {@code lyclaw-web}, following the Dependency
 * Inversion Principle (DIP).</p>
 */
public class SubagentSpawner {

    private static final Logger log = LoggerFactory.getLogger(SubagentSpawner.class);

    public static final String ATTR_SUBAGENT_CONFIG = "subagentConfig";
    public static final String ATTR_AGENT_EXTENSIONS = "agentExtensions";
    /** Attribute key for the child Session stored on AgentContext. */
    public static final String ATTR_CHILD_SESSION = "childSession";

    static final long DEFAULT_RUN_TIMEOUT_SECONDS = 300;
    static final int DEFAULT_MAX_SPAWN_DEPTH = 3;
    static final int DEFAULT_MAX_CHILDREN_PER_AGENT = 5;

    private final ChatFacade chatFacade;
    private final ReActEngine reActEngine;
    private final ToolRegistry toolRegistry;
    private final AgentConfigResolver configResolver;
    private final List<ReactivePipelineStage> defaultStages;
    private final List<AgentHook> defaultHooks;

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

    public Mono<SubagentResult> spawnSubagent(String targetAgentId,
                                               String task,
                                               Map<String, Object> options,
                                               AgentContext parentCtx) {
        long startTime = System.currentTimeMillis();
        log.info("[子代理] 开始生成子代理 | targetAgentId={} | task长度={}", targetAgentId,
                task != null ? task.length() : 0);

        if (targetAgentId == null || targetAgentId.isBlank()) {
            log.warn("[WARN] [子代理] targetAgentId为空，拒绝生成");
            return Mono.just(SubagentResult.error("targetAgentId is required"));
        }
        if (task == null || task.isBlank()) {
            log.warn("[WARN] [子代理] task为空，拒绝生成 | targetAgentId={}", targetAgentId);
            return Mono.just(SubagentResult.error("task is required for agent '" + targetAgentId + "'"));
        }

        SubagentConfig config = resolveSubagentConfig(parentCtx);
        Map<String, Object> opts = options != null ? options : Collections.emptyMap();
        log.info("[子代理] 配置解析完成 | 最大深度={} | 最大子代理数={} | 最大并发={} | 超时={}s",
                config.getMaxSpawnDepth(), config.getMaxChildrenPerAgent(),
                config.getMaxConcurrent(), config.getRunTimeoutSeconds());

        // --- Step 2a: Validate whitelist ---
        List<String> allowAgents = config.getAllowAgents();
        if (allowAgents != null && !allowAgents.isEmpty()) {
            boolean wildcard = allowAgents.contains("*");
            boolean explicit = allowAgents.contains(targetAgentId);
            if (!wildcard && !explicit) {
                String msg = String.format(
                        "Agent '%s' is not in the allowed agents list: %s",
                        targetAgentId, allowAgents);
                log.warn("[BLOCKED] [子代理] 白名单拒绝: {}", msg);
                return Mono.just(SubagentResult.rejected(targetAgentId, msg));
            }
            log.info("[OK] [子代理] 白名单校验通过 | targetAgentId={} | allowAgents={}", targetAgentId, allowAgents);
        }

        // --- Step 2b: Validate depth limit ---
        int currentDepth = getCurrentDepth(parentCtx);
        int maxDepth = config.getMaxSpawnDepth() > 0
                ? config.getMaxSpawnDepth() : DEFAULT_MAX_SPAWN_DEPTH;
        if (currentDepth >= maxDepth) {
            String msg = String.format(
                    "Max spawn depth exceeded: current=%d, max=%d, target='%s'",
                    currentDepth, maxDepth, targetAgentId);
            log.warn("[BLOCKED] [子代理] 深度限制拒绝: {}", msg);
            return Mono.just(SubagentResult.rejected(targetAgentId, msg));
        }
        log.info("[子代理] 深度校验通过 | 当前深度={}/{}", currentDepth, maxDepth);

        // --- Step 2c: Validate children limit ---
        int maxChildren = config.getMaxChildrenPerAgent() > 0
                ? config.getMaxChildrenPerAgent() : DEFAULT_MAX_CHILDREN_PER_AGENT;
        int activeChildren = parentCtx.getActiveSubagentCount();
        if (activeChildren >= maxChildren) {
            String msg = String.format(
                    "Max children per agent reached: active=%d, max=%d, target='%s'",
                    activeChildren, maxChildren, targetAgentId);
            log.warn("[BLOCKED] [子代理] 子代理数限制拒绝: {}", msg);
            return Mono.just(SubagentResult.rejected(targetAgentId, msg));
        }
        log.info("[子代理] 子代理数校验通过 | 活跃子代理={}/{}", activeChildren, maxChildren);

        // --- Step 3: Create child session via SessionFactory ---
        String parentSessionId = parentCtx.getSessionId();
        String parentAgentId = parentCtx.getAgentId();
        String childModel = config.getModel();
        Session childSession = createChildSession(parentSessionId, parentAgentId, targetAgentId, childModel);
        log.info("[子代理] 子会话已创建 | sessionId={}", childSession.getSessionId());

        parentCtx.addActiveSubagent(targetAgentId);

        // --- Step 4: Acquire concurrency semaphore ---
        Semaphore semaphore = concurrencySemaphores.computeIfAbsent(parentSessionId,
                k -> new Semaphore(config.getMaxConcurrent() > 0
                        ? config.getMaxConcurrent() : 1, true));

        long timeoutSeconds = resolveRunTimeout(opts, config);
        log.info("[子代理] 等待并发信号量... | 超时={}s", timeoutSeconds);

        return Mono.fromCallable(() -> {
                    boolean acquired = semaphore.tryAcquire(10, TimeUnit.SECONDS);
                    if (!acquired) {
                        log.warn("[TIMEOUT] [子代理] 获取并发槽位超时 | session={} agent={}", parentSessionId, targetAgentId);
                        return SubagentResult.error(
                                "Timed out waiting for concurrency slot (session: "
                                        + parentSessionId + ", agent: " + targetAgentId + ")");
                    }
                    log.info("[OK] [子代理] 并发信号量已获取");
                    return SubagentResult.proceed(targetAgentId);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(proceed -> runSubagent(
                        targetAgentId, task, childSession, config, parentCtx))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .onErrorResume(throwable -> {
                    if (throwable instanceof java.util.concurrent.TimeoutException) {
                        log.warn("[TIMEOUT] [子代理] 执行超时: agentId={} sessionId={} timeout={}s",
                                targetAgentId, childSession.getSessionId(), timeoutSeconds);
                        return Mono.just(SubagentResult.timeout(targetAgentId, timeoutSeconds));
                    }
                    log.error("[FAIL] [子代理] 执行失败: agentId={} sessionId={}",
                            targetAgentId, childSession.getSessionId(), throwable);
                    return Mono.just(SubagentResult.error(
                            "Subagent execution failed: " + throwable.getMessage()));
                })
                .doFinally(signalType -> {
                    semaphore.release();
                    parentCtx.removeActiveSubagent(targetAgentId);
                    log.info("[子代理] 清理完成 | agentId={} | 信号类型={}", targetAgentId, signalType);
                });
    }

    /**
     * Phase 4: spawn subagent with ProgressBus for streaming progress forwarding.
     * Shares validation logic with 4-arg version but uses streaming child execution.
     */
    public Mono<SubagentResult> spawnSubagent(String targetAgentId,
                                               String task,
                                               Map<String, Object> options,
                                               AgentContext parentCtx,
                                               java.util.function.Consumer<ServerSentEvent<String>> progressEmitter) {
        if (targetAgentId == null || targetAgentId.isBlank()) {
            return Mono.just(SubagentResult.error("targetAgentId is required"));
        }
        if (task == null || task.isBlank()) {
            return Mono.just(SubagentResult.error("task is required"));
        }
        long startTime = System.currentTimeMillis();
        SubagentConfig config = resolveSubagentConfig(parentCtx);
        Map<String, Object> opts = options != null ? options : Collections.emptyMap();

        List<String> allowAgents = config.getAllowAgents();
        if (allowAgents != null && !allowAgents.isEmpty()) {
            boolean wildcard = allowAgents.contains("*");
            boolean explicit = allowAgents.contains(targetAgentId);
            if (!wildcard && !explicit) {
                return Mono.just(SubagentResult.rejected(targetAgentId,
                        "Agent not in allowAgents: " + targetAgentId));
            }
        }
        int currentDepth = getCurrentDepth(parentCtx);
        int maxDepth = config.getMaxSpawnDepth() > 0 ? config.getMaxSpawnDepth() : DEFAULT_MAX_SPAWN_DEPTH;
        if (currentDepth >= maxDepth) {
            return Mono.just(SubagentResult.error("Max spawn depth exceeded"));
        }
        int activeCount = parentCtx.getActiveSubagentIds().size();
        int maxChildren = config.getMaxChildrenPerAgent() > 0
                ? config.getMaxChildrenPerAgent() : DEFAULT_MAX_CHILDREN_PER_AGENT;
        if (activeCount >= maxChildren) {
            return Mono.just(SubagentResult.error("Max children per agent reached"));
        }

        String parentSessionId = parentCtx.getSessionId();
        String parentAgentId = parentCtx.getAgentId();
        String childModel = (String) opts.getOrDefault("model",
                config.getModel() != null ? config.getModel() : "");
        String childSessionId = parentSessionId + "/subagent/" + targetAgentId + "/"
                + UUID.randomUUID().toString().substring(0, 8);
        Session childSession = Session.builder()
                .sessionId(childSessionId).model(childModel).build();
        Semaphore semaphore = concurrencySemaphores.computeIfAbsent(parentSessionId,
                k -> new Semaphore(config.getMaxConcurrent() > 0
                        ? config.getMaxConcurrent() : 1));
        parentCtx.addActiveSubagent(targetAgentId);

        return Mono.fromCallable(() -> {
                    if (!semaphore.tryAcquire(10, TimeUnit.SECONDS)) {
                        throw new RuntimeException("Semaphore acquisition timeout");
                    }
                    return runSubagentStreaming(targetAgentId, task, childSession,
                            config, parentCtx, progressEmitter);
                })
                .timeout(Duration.ofSeconds(config.getRunTimeoutSeconds() + 10))
                .onErrorResume(t -> {
                    log.error("[FAIL] [子代理] 流式执行失败: agentId={} error={}",
                            targetAgentId, t.getMessage());
                    return Mono.just(SubagentResult.error(
                            "Subagent execution failed: " + t.getMessage()));
                })
                .doFinally(sig -> {
                    semaphore.release();
                    parentCtx.removeActiveSubagent(targetAgentId);
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("[子代理] 流式清理完成 | agentId={} | 耗时={}ms | signal={}",
                            targetAgentId, elapsed, sig);
                });
    }

    /**
     * Streaming version of runSubagent. Uses executeStream() instead of execute()
     * so child agent events (thinking, tool_call, message) are forwarded to the
     * parent's ProgressBus in real-time.
     */
    private SubagentResult runSubagentStreaming(String targetAgentId, String task,
                                                 Session childSession, SubagentConfig config,
                                                 AgentContext parentCtx,
                                                 java.util.function.Consumer<ServerSentEvent<String>> progressEmitter) {
        long startTime = System.currentTimeMillis();
        AgentContext childCtx = buildChildContext(targetAgentId, task, childSession, config, parentCtx);
        dispatchSubagentSpawning(childCtx);
        ToolExecutor toolExecutor = buildChildToolExecutor(childCtx);
        List<AgentHook> sorted = new ArrayList<>(defaultHooks);
        sorted.sort(java.util.Comparator.comparingInt(AgentHook::getOrder));
        for (AgentHook hook : sorted) {
            toolExecutor = hook.wrapToolExecutor(toolExecutor, childCtx);
        }
        ChatRequest childRequest = childCtx.getChatRequest();
        childRequest.setStream(true);
        StringBuilder outputBuilder = new StringBuilder();

        int[] progressCount = {0};
        reActEngine.executeStream(chatFacade, childRequest, toolExecutor)
                .doOnNext(event -> {
                    String evtType = event.event();
                    String evtData = event.data();
                    if (progressEmitter != null && evtType != null) {
                        String json = buildSubagentEventJson(targetAgentId, event);
                        progressEmitter.accept(
                                lyjew.com.lyclaw.react.sse.SseEventFactory.subagentProgress(
                                        targetAgentId, evtType, json));
                        progressCount[0]++;
                    }
                    if ("message".equals(evtType) && evtData != null) {
                        outputBuilder.append(evtData);
                    }
                })
                .doFinally(sig -> log.info("[子代理流式] 进度事件转发完成 | agentId={} | 转发事件数={} | signal={}",
                        targetAgentId, progressCount[0], sig))
                .blockLast();

        long elapsed = System.currentTimeMillis() - startTime;
        String output = outputBuilder.toString();
        SubagentResult result = SubagentResult.success(
                childSession.getSessionId(), targetAgentId, output, elapsed, 0, 0);
        dispatchSubagentSpawned(childCtx, result);
        dispatchSubagentEnded(childCtx, result);
        return result;
    }

    private String buildSubagentEventJson(String agentId, ServerSentEvent<String> event) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("agentId", agentId);
            map.put("type", event.event());
            map.put("data", event.data());
            return lyjew.com.lyclaw.react.sse.SseEventFactory.getObjectMapper()
                    .writeValueAsString(map);
        } catch (Exception e) {
            return "{\"agentId\":\"" + agentId + "\",\"type\":\"unknown\"}";
        }
    }

    public SubagentConfig resolveSubagentConfig(AgentContext ctx) {
        SubagentConfig explicit = ctx.getAttribute(ATTR_SUBAGENT_CONFIG);
        if (explicit != null) {
            return explicit;
        }

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

        return SubagentConfig.defaults();
    }

    public static ToolDefinition buildDelegateToolDefinition(SubagentConfig config) {
        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> agentIdProp = new LinkedHashMap<>();
        agentIdProp.put("type", "string");
        List<String> agents = config.getAllowAgents() != null && !config.getAllowAgents().isEmpty()
                ? config.getAllowAgents()
                : List.of("any");
        agentIdProp.put("description",
                "The ID of the specialized agent to delegate to. "
                        + "Available agents: " + String.join(", ", agents));
        properties.put("agentId", agentIdProp);

        Map<String, Object> taskProp = new LinkedHashMap<>();
        taskProp.put("type", "string");
        taskProp.put("description",
                "The task description for the subagent. Be specific about what to "
                        + "accomplish and the expected output format.");
        properties.put("task", taskProp);

        Map<String, Object> modeProp = new LinkedHashMap<>();
        modeProp.put("type", "string");
        modeProp.put("enum", List.of("suggest", "prefer"));
        modeProp.put("description",
                "Delegation mode: 'suggest' means the subagent may decline, "
                        + "'prefer' means strong preference for delegation. "
                        + "Defaults to 'suggest'.");
        properties.put("mode", modeProp);

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
                .readOnly(true)
                .timeout(config.getRunTimeoutSeconds() * 1000L)
                .build();
    }

    // ========================================================================
    // Private Implementation
    // ========================================================================

    /**
     * Creates a child session in-memory.
     */
    private Session createChildSession(String parentSessionId, String parentAgentId,
                                        String childAgentId, String model) {
        String sid = parentSessionId + "/subagent/" + childAgentId + "/"
                + UUID.randomUUID().toString().substring(0, 8);
        return Session.builder()
                .sessionId(sid)
                .model(model)
                .messages(new ArrayList<>())
                .build();
    }

    private Mono<SubagentResult> runSubagent(String targetAgentId,
                                              String task,
                                              Session childSession,
                                              SubagentConfig config,
                                              AgentContext parentCtx) {
        long startTime = System.currentTimeMillis();
        log.info("[子代理] 开始执行 | agentId={} | sessionId={}", targetAgentId, childSession.getSessionId());

        return Mono.fromCallable(() -> {
                    log.info("[子代理] 构建子上下文...");
                    AgentContext childCtx = buildChildContext(
                            targetAgentId, task, childSession, config, parentCtx);
                    log.info("[OK] [子代理] 子上下文已构建 | 沙箱级别={} | 深度={}",
                            childCtx.getSandboxLevel(), childCtx.getRunMetadata().getSubagentDepth());

                    log.info("[子代理] 派发subagentSpawning钩子...");
                    dispatchSubagentSpawning(childCtx);

                    ToolExecutor toolExecutor = buildChildToolExecutor(childCtx);
                    List<AgentHook> sorted = new ArrayList<>(defaultHooks);
                    sorted.sort(java.util.Comparator.comparingInt(AgentHook::getOrder));
                    for (AgentHook hook : sorted) {
                        toolExecutor = hook.wrapToolExecutor(toolExecutor, childCtx);
                    }
                    log.info("[子代理] 工具执行器已构建 | 钩子包装数={}", sorted.size());

                    log.info("[子代理] 启动ReAct引擎执行...");
                    ChatRequest childRequest = childCtx.getChatRequest();
                    String output = reActEngine.execute(chatFacade, childRequest, toolExecutor);

                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("[OK] [子代理] ReAct引擎执行完成 | 耗时={}ms | 输出长度={}",
                            elapsed, output != null ? output.length() : 0);

                    SubagentResult result = SubagentResult.success(
                            childSession.getSessionId(), targetAgentId, output, elapsed, 0, 0);

                    dispatchSubagentSpawned(childCtx, result);
                    dispatchSubagentEnded(childCtx, result);

                    return result;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

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
                    result = toolRegistry.executeByName(toolName, toolCallId, argumentsJson,
                            childCtx.getChatRequest(), Map.of("agentContext", childCtx));
                }
                if (result.isSuccess()) {
                    return result.getResult() != null ? result.getResult() : "";
                }
                return "Error: " + (result.getError() != null ? result.getError() : "unknown");
            } catch (Exception e) {
                log.error("[FAIL] [子代理] 工具执行失败: tool={} toolCallId={}", toolName, toolCallId, e);
                return "Error: " + e.getMessage();
            }
        };
    }

    private AgentContext buildChildContext(String targetAgentId,
                                            String task,
                                            Session childSession,
                                            SubagentConfig config,
                                            AgentContext parentCtx) {
        String systemPrompt = "You are a subagent: " + targetAgentId;
        String childModel = config.getModel() != null && !config.getModel().isEmpty()
                ? config.getModel() : null;

        ChatRequest childRequest = ChatRequest.builder()
                .sessionId(childSession.getSessionId())
                .messages(new ArrayList<>(List.of(Message.user(task))))
                .model(childModel != null ? childModel : "")
                .agentId(targetAgentId)
                .stream(false)
                .toolChoice("auto")
                .build();

        List<ToolDefinition> tools = toolRegistry.getAllDefinitions(childRequest);
        childRequest.setTools(tools);

        AgentContext childCtx = new AgentContext(
                childSession.getSessionId(), task, systemPrompt,
                toolRegistry, null, null,
                targetAgentId, targetAgentId);

        childCtx.setChatRequest(childRequest);
        childCtx.setLifecycle(AgentContext.Lifecycle.TRANSIENT);
        childCtx.setAttribute(ATTR_CHILD_SESSION, childSession);

        RunMetadata parentMeta = parentCtx.getRunMetadata();
        RunMetadata childMeta = RunMetadata.childOf(parentMeta, targetAgentId);
        childMeta.setParentSessionKey(parentCtx.getSessionId());

        if (childModel != null && !childModel.isEmpty()) {
            childMeta.setResolvedModel(childModel);
        }

        String thinkingLevel = config.getThinking() != null && !config.getThinking().isEmpty()
                ? config.getThinking()
                : parentMeta.getThinkingLevel();
        if (thinkingLevel != null) {
            childMeta.setThinkingLevel(thinkingLevel);
        }

        childCtx.setRunMetadata("subagentDepth", childMeta.getSubagentDepth());
        childCtx.setRunMetadata("parentSessionKey", childMeta.getParentSessionKey());
        childCtx.setRunMetadata("subagentTargetAgentId", targetAgentId);

        SandboxLevel parentSandbox = parentCtx.getSandboxLevel();
        childCtx.setSandboxLevel(parentSandbox != null
                ? parentSandbox : SandboxLevel.SANDBOX);

        String workspaceDir = parentCtx.getWorkspaceDir();
        if (workspaceDir != null && !workspaceDir.isEmpty()) {
            childCtx.setWorkspaceDir(workspaceDir);
        }

        if (thinkingLevel != null) {
            childCtx.setThinkingLevel(thinkingLevel);
        }
        String verbose = parentCtx.getVerboseLevel();
        if (verbose != null) {
            childCtx.setVerboseLevel(verbose);
        }

        return childCtx;
    }

    private void dispatchSubagentSpawning(AgentContext childCtx) {
        for (AgentHook hook : defaultHooks) {
            if (hook instanceof SubagentHook sh) {
                try {
                    sh.subagentSpawning(childCtx);
                } catch (Exception e) {
                    log.warn("[WARN] [子代理] SubagentHook {} 在subagentSpawning中抛出异常 agentId={}",
                            hook.getClass().getSimpleName(), childCtx.getAgentId(), e);
                }
            }
        }
    }

    private void dispatchSubagentSpawned(AgentContext childCtx, SubagentResult result) {
        for (AgentHook hook : defaultHooks) {
            if (hook instanceof SubagentHook sh) {
                try {
                    sh.subagentSpawned(childCtx, result);
                } catch (Exception e) {
                    log.warn("[WARN] [子代理] SubagentHook {} 在subagentSpawned中抛出异常 agentId={}",
                            hook.getClass().getSimpleName(), childCtx.getAgentId(), e);
                }
            }
        }
    }

    private void dispatchSubagentEnded(AgentContext childCtx, SubagentResult result) {
        for (AgentHook hook : defaultHooks) {
            if (hook instanceof SubagentHook sh) {
                try {
                    sh.subagentEnded(childCtx, result);
                } catch (Exception e) {
                    log.warn("[WARN] [子代理] SubagentHook {} 在subagentEnded中抛出异常 agentId={}",
                            hook.getClass().getSimpleName(), childCtx.getAgentId(), e);
                }
            }
        }
    }

    private int getCurrentDepth(AgentContext ctx) {
        return ctx.getRunMetadata().getSubagentDepth();
    }

    private long resolveRunTimeout(Map<String, Object> options, SubagentConfig config) {
        if (options.containsKey("runTimeoutSeconds")
                && options.get("runTimeoutSeconds") instanceof Number n) {
            return Math.max(1, n.longValue());
        }
        long fromConfig = config.getRunTimeoutSeconds();
        return fromConfig > 0 ? fromConfig : DEFAULT_RUN_TIMEOUT_SECONDS;
    }
}
