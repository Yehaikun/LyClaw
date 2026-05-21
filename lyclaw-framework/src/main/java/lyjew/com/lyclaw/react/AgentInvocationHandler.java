package lyjew.com.lyclaw.react;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lyjew.com.lyclaw.annotation.agent.SystemMessage;
import lyjew.com.lyclaw.annotation.agent.UserMessage;
import lyjew.com.lyclaw.annotation.agent.V;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.config.ResolvedAgentConfig;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lyjew.com.lyclaw.tool.ToolRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Agent 接口的动态代理 InvocationHandler。
 *
 * <h3>执行流程</h3>
 * <ol>
 *   <li>解析注解 → 构建 AgentContext</li>
 *   <li>beforeRequest hooks（按 order 升序）</li>
 *   <li>Stage 管线（ContextBuild→SecurityCheck→PlanExecution→Respond→Metrics）</li>
 *   <li>afterResult hooks（按 order 降序）</li>
 * </ol>
 *
 * <p>Stage 管线内嵌了完整的 ReAct 循环（RespondStage），
 * AgentHook 提供步级拦截（beforeModel/afterModel/wrapToolCall）。</p>
 */
public class AgentInvocationHandler implements InvocationHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentInvocationHandler.class);

    private final ChatFacade chatFacade;
    private final ReActEngine reActEngine;
    private final ToolRegistry toolRegistry;
    private final String defaultSystemPrompt;
    private final String modelOverride;
    private final String providerOverride;
    private final List<AgentHook> hooks;
    private final List<ReactivePipelineStage> stages;
    private final HookRegistry hookRegistry;
    private final ResolvedAgentConfig resolvedConfig;

    public AgentInvocationHandler(ChatFacade chatFacade, ReActEngine reActEngine,
                                   ToolRegistry toolRegistry, String defaultSystemPrompt,
                                   String modelOverride, String providerOverride,
                                   List<AgentHook> hooks,
                                   List<ReactivePipelineStage> stages) {
        this(chatFacade, reActEngine, toolRegistry, defaultSystemPrompt, modelOverride,
                providerOverride, hooks, stages, null, null);
    }

    /** Constructor with HookRegistry (backward compatible). */
    public AgentInvocationHandler(ChatFacade chatFacade, ReActEngine reActEngine,
                                   ToolRegistry toolRegistry, String defaultSystemPrompt,
                                   String modelOverride, String providerOverride,
                                   List<AgentHook> hooks,
                                   List<ReactivePipelineStage> stages,
                                   HookRegistry hookRegistry) {
        this(chatFacade, reActEngine, toolRegistry, defaultSystemPrompt, modelOverride,
                providerOverride, hooks, stages, hookRegistry, null);
    }

    /** Constructor with ResolvedAgentConfig (used by AgentProxyFactory). */
    public AgentInvocationHandler(ChatFacade chatFacade, ReActEngine reActEngine,
                                   ToolRegistry toolRegistry, String defaultSystemPrompt,
                                   String modelOverride, String providerOverride,
                                   List<AgentHook> hooks,
                                   List<ReactivePipelineStage> stages,
                                   ResolvedAgentConfig resolvedConfig) {
        this(chatFacade, reActEngine, toolRegistry, defaultSystemPrompt, modelOverride,
                providerOverride, hooks, stages, null, resolvedConfig);
    }

    /** Full constructor. */
    public AgentInvocationHandler(ChatFacade chatFacade, ReActEngine reActEngine,
                                   ToolRegistry toolRegistry, String defaultSystemPrompt,
                                   String modelOverride, String providerOverride,
                                   List<AgentHook> hooks,
                                   List<ReactivePipelineStage> stages,
                                   HookRegistry hookRegistry,
                                   ResolvedAgentConfig resolvedConfig) {
        this.chatFacade = chatFacade;
        this.reActEngine = reActEngine;
        this.toolRegistry = toolRegistry;
        this.defaultSystemPrompt = defaultSystemPrompt;
        this.modelOverride = modelOverride;
        this.providerOverride = providerOverride;
        this.hooks = hooks != null ? List.copyOf(hooks) : List.of();
        this.stages = stages != null ? sortedStages(stages) : List.of();
        this.hookRegistry = hookRegistry != null ? hookRegistry : new HookRegistry();
        this.resolvedConfig = resolvedConfig != null ? resolvedConfig : ResolvedAgentConfig.builder().build();
        // 将所有现有 hooks 注册到 HookRegistry
        for (AgentHook hook : this.hooks) {
            this.hookRegistry.register(hook, "agent-hook", hook.getOrder());
        }
    }

    private static List<ReactivePipelineStage> sortedStages(List<ReactivePipelineStage> stages) {
        List<ReactivePipelineStage> sorted = new ArrayList<>(stages);
        sorted.sort(Comparator.comparingInt(ReactivePipelineStage::getOrder));
        return List.copyOf(sorted);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }

        String systemPrompt = resolveSystemMessage(method, args);
        String userMessage = resolveUserMessage(method, args);

        ChatRequest request = buildChatRequest(method, userMessage, systemPrompt);
        String sessionId = request.getSessionId() != null
                ? request.getSessionId() : UUID.randomUUID().toString().substring(0, 8);

        AgentContext ctx = new AgentContext(sessionId, userMessage, systemPrompt,
                toolRegistry, method, args);
        ctx.setChatRequest(request);
        ctx.setPipelineOk(true); // 默认流水线正常，安全阶段可设为 false
        ctx.setRuntimeType(AgentRuntimeType.EMBEDDED);

        // Phase 2: resolve thinking/reasoning/verbose levels from ChatRequest
        // Priority: ChatRequest field > ResolvedAgentConfig default
        String resolvedThinking = resolveLevel(request.getThinkingLevel(), resolvedConfig.getThinkingDefault());
        if (resolvedThinking != null) {
            ctx.setThinkingLevel(resolvedThinking);
            ctx.setRunMetadata("thinkingLevel", resolvedThinking);
            ctx.getRunMetadata().setThinkingLevel(resolvedThinking);
            // Propagate back to ChatRequest so DefaultReActEngine.applyThinkingLevel() can read it
            request.setThinkingLevel(resolvedThinking);
        }

        String resolvedReasoning = resolveLevel(request.getReasoningLevel(), resolvedConfig.getReasoningDefault());
        if (resolvedReasoning != null) {
            ctx.setReasoningLevel(resolvedReasoning);
            ctx.setRunMetadata("reasoningLevel", resolvedReasoning);
            ctx.getRunMetadata().setReasoningLevel(resolvedReasoning);
        }

        String resolvedVerbose = resolveLevel(request.getVerboseLevel(), resolvedConfig.getVerboseDefault());
        if (resolvedVerbose != null) {
            ctx.setVerboseLevel(resolvedVerbose);
            ctx.setRunMetadata("verboseLevel", resolvedVerbose);
            ctx.getRunMetadata().setVerboseLevel(resolvedVerbose);
        }

        // Phase 2: set resolved model/provider on runMetadata
        // Used by ModelResolutionService.resolvedEffectiveModel() as first priority
        if (resolvedConfig.getModel() != null && !resolvedConfig.getModel().isEmpty()) {
            ctx.setRunMetadata("resolvedModel", resolvedConfig.getModel());
            ctx.getRunMetadata().setResolvedModel(resolvedConfig.getModel());
        }
        if (resolvedConfig.getProvider() != null && !resolvedConfig.getProvider().isEmpty()) {
            ctx.setRunMetadata("resolvedProvider", resolvedConfig.getProvider());
            ctx.getRunMetadata().setResolvedProvider(resolvedConfig.getProvider());
        }

        // TODO: Phase 2 - wire delegate_to_agent tool via DelegateToAgentToolProvider
        // into toolRegistry so that tool definitions include delegate_to_agent.
        // The tool registry should be populated with delegate_to_agent when
        // resolvedConfig.getDelegationMode() is not "none" and allowAgents is non-empty.

        // 0. beforeAgentRun hook dispatch (with fully prepared context)
        hookRegistry.dispatchBeforeAgentRun(ctx);

        // 1. beforeRequest hooks（按 order 升序）
        List<AgentHook> sorted = new ArrayList<>(hooks);
        sorted.sort(Comparator.comparingInt(AgentHook::getOrder));
        for (AgentHook hook : sorted) {
            hook.beforeRequest(ctx);
        }

        // 如果 hook 修改了 userMessage 或 systemPrompt，重新构建 request
        if (!userMessage.equals(ctx.getUserMessage())
                || !Objects.equals(systemPrompt, ctx.getSystemPrompt())) {
            request = buildChatRequest(method, ctx.getUserMessage(), ctx.getSystemPrompt());
            // Phase 2: preserve resolved thinking/reasoning/verbose levels on rebuilt request
            if (ctx.getThinkingLevel() != null && !ctx.getThinkingLevel().isEmpty()) {
                request.setThinkingLevel(ctx.getThinkingLevel());
            }
            if (ctx.getReasoningLevel() != null && !ctx.getReasoningLevel().isEmpty()) {
                request.setReasoningLevel(ctx.getReasoningLevel());
            }
            if (ctx.getVerboseLevel() != null && !ctx.getVerboseLevel().isEmpty()) {
                request.setVerboseLevel(ctx.getVerboseLevel());
            }
            ctx.setChatRequest(request);
        }

        Class<?> returnType = method.getReturnType();
        boolean returnsSSE = isFluxOfServerSentEvent(method);

        String result;

        if (!stages.isEmpty()) {
            // 2a. Stage 管线路径
            // 模型调用前 dispatch（stage 管线内嵌 ReAct 循环）
            hookRegistry.dispatchBeforeModelResolve(ctx);
            hookRegistry.dispatchModelCallStarted(ctx);

            if (returnType == Flux.class) {
                Flux<org.springframework.http.codec.ServerSentEvent<String>> stageFlux = executeStages(ctx);
                // 模型调用后 dispatch（Flux 场景在完成时触发）
                stageFlux = stageFlux
                        .doOnComplete(() -> hookRegistry.dispatchModelCallEnded(ctx));
                if (returnsSSE) {
                    return stageFlux;
                }
                return stageFlux
                        .mapNotNull(event -> "message".equals(event.event()) ? event.data() : null);
            }
            result = executeStagesBlocking(ctx);
            // 模型调用后 dispatch（阻塞场景）
            hookRegistry.dispatchModelCallEnded(ctx);
        } else {
            // 2b. 无 Stage 时的降级路径：直接 ReActEngine（向后兼容）
            ToolExecutor toolExecutor = buildToolExecutor(ctx);
            for (AgentHook hook : sorted) {
                toolExecutor = hook.wrapToolExecutor(toolExecutor, ctx);
            }

            // 模型调用前 dispatch
            hookRegistry.dispatchBeforeModelResolve(ctx);
            hookRegistry.dispatchModelCallStarted(ctx);

            if (returnType == Flux.class) {
                Flux<org.springframework.http.codec.ServerSentEvent<String>> reActFlux =
                        reActEngine.executeStream(chatFacade, ctx.getChatRequest(), toolExecutor);
                // 模型调用后 dispatch
                hookRegistry.dispatchModelCallEnded(ctx);
                if (returnsSSE) {
                    return reActFlux;
                }
                return reActFlux
                        .mapNotNull(event -> "message".equals(event.event()) ? event.data() : null);
            }

            result = reActEngine.execute(chatFacade, ctx.getChatRequest(), toolExecutor);
            // 模型调用后 dispatch
            hookRegistry.dispatchModelCallEnded(ctx);
        }

        // 3. afterResult hooks（按 order 降序）
        for (int i = sorted.size() - 1; i >= 0; i--) {
            result = sorted.get(i).afterResult(result, ctx);
        }

        // 4. agentEnd hook dispatch
        hookRegistry.dispatchAgentEnd(ctx);

        if (returnType == Mono.class) {
            return Mono.justOrEmpty(result);
        }

        if (returnType == void.class || returnType == Void.class) {
            return null;
        }

        return result;
    }

    private ToolExecutor buildToolExecutor(AgentContext ctx) {
        return (toolName, toolCallId, argumentsJson) -> {
            // beforeToolCall dispatch
            hookRegistry.dispatchBeforeToolCall(toolName, toolCallId, argumentsJson, ctx);
            try {
                ToolCall toolCall = ToolCall.builder()
                        .toolCallId(toolCallId)
                        .name(toolName)
                        .arguments(argumentsJson)
                        .build();
                ToolExecutionResult result = toolRegistry.execute(toolCall, null);
                if (!result.isSuccess()) {
                    result = toolRegistry.executeByName(toolName, toolCallId, argumentsJson,
                            ctx.getChatRequest(), java.util.Map.of("agentContext", ctx));
                }
                String output;
                if (result.isSuccess()) {
                    output = result.getResult() != null ? result.getResult() : "";
                } else {
                    output = "Error: " + (result.getError() != null ? result.getError() : "unknown");
                }
                // afterToolCall dispatch
                hookRegistry.dispatchAfterToolCall(toolName, toolCallId, output, ctx);
                return output;
            } catch (Exception e) {
                log.error("Tool execution failed: tool={} toolCallId={}", toolName, toolCallId, e);
                String errorOutput = "Error: " + e.getMessage();
                // afterToolCall dispatch (even on error)
                hookRegistry.dispatchAfterToolCall(toolName, toolCallId, errorOutput, ctx);
                return errorOutput;
            }
        };
    }

    private static final int MAX_REFLECTION_RETRIES = 2;
    private static final double REFLECTION_RETRY_THRESHOLD = 0.6;

    /**
     * 流式执行 Stage 管线，返回 SSE 事件流。
     * 包含 Plan→Execute→Reflect→Replan 闭环：ReflectionStage 评分低于阈值时自动重试。
     */
    private Flux<org.springframework.http.codec.ServerSentEvent<String>> executeStages(AgentContext ctx) {
        if (stages.isEmpty()) {
            return Flux.empty();
        }

        int planIdx = indexOfStage("PlanExecution");
        int reflectionIdx = indexOfStage("Reflection");

        if (reflectionIdx < 0 || planIdx < 0) {
            return simpleConcat(ctx);
        }

        List<Flux<org.springframework.http.codec.ServerSentEvent<String>>> allFluxes = new ArrayList<>();

        // 前置阶段（ContextBuild → SecurityCheck）
        for (int i = 0; i < planIdx; i++) {
            allFluxes.add(stages.get(i).execute(ctx));
        }

        // 可重试阶段块：PlanExecution → Respond → ReflectionStage
        // 使用 repeat(condition) 实现重试（repeatWhen 在 Flux.concat 中会导致完成信号丢失）
        AtomicInteger retries = new AtomicInteger(0);
        Flux<org.springframework.http.codec.ServerSentEvent<String>> retryBlock = Flux.defer(() -> {
            List<Flux<org.springframework.http.codec.ServerSentEvent<String>>> innerList = new ArrayList<>();
            for (int i = planIdx; i <= reflectionIdx; i++) {
                innerList.add(stages.get(i).execute(ctx));
            }
            return Flux.concat(innerList);
        }).repeat(() -> {
            double score = ctx.getReflectScoreRef().get();
            int failCount = ctx.getFailCount().get();
            int attempt = retries.incrementAndGet();
            if (score < REFLECTION_RETRY_THRESHOLD && failCount > 0
                    && attempt <= MAX_REFLECTION_RETRIES) {
                log.info("Reflection score {} < threshold {} (failCount={}), retrying ({}/{})",
                        String.format("%.2f", score), REFLECTION_RETRY_THRESHOLD,
                        failCount, attempt, MAX_REFLECTION_RETRIES);
                return true;
            }
            return false;
        });

        allFluxes.add(retryBlock);

        // MetricsStage 在重试块之后
        int metricsIdx = indexOfStage("Metrics");
        if (metricsIdx >= 0) {
            allFluxes.add(stages.get(metricsIdx).execute(ctx));
        }

        return Flux.concat(allFluxes)
                .doOnComplete(() -> hookRegistry.dispatchBeforeAgentFinalize(ctx));
    }

    /**
     * 阻塞执行 Stage 管线，收集最终响应文本。
     * 包含 Plan→Execute→Reflect→Replan 闭环。
     */
    private String executeStagesBlocking(AgentContext ctx) {
        if (stages.isEmpty()) {
            return "";
        }

        int planIdx = indexOfStage("PlanExecution");
        int reflectionIdx = indexOfStage("Reflection");

        if (reflectionIdx < 0 || planIdx < 0) {
            return simpleConcatBlocking(ctx);
        }

        StringBuilder finalResponse = new StringBuilder();
        try {
            // 前置阶段
            for (int i = 0; i < planIdx; i++) {
                stages.get(i).execute(ctx).blockLast();
            }

            // 可重试阶段块
            int retries = 0;
            double score;
            do {
                for (int i = planIdx; i <= reflectionIdx; i++) {
                    stages.get(i).execute(ctx)
                            .doOnNext(event -> {
                                if ("message".equals(event.event()) && event.data() != null) {
                                    if (finalResponse.length() > 0) finalResponse.append("\n");
                                    finalResponse.append(event.data());
                                }
                            })
                            .blockLast();
                }
                score = ctx.getReflectScoreRef().get();
                retries++;
            } while (score < REFLECTION_RETRY_THRESHOLD && ctx.getFailCount().get() > 0
                    && retries < MAX_REFLECTION_RETRIES);

            ctx.setAttribute("finalResponse", finalResponse.toString());

            // beforeAgentFinalize dispatch
            hookRegistry.dispatchBeforeAgentFinalize(ctx);

            // MetricsStage
            int metricsIdx = indexOfStage("Metrics");
            if (metricsIdx >= 0) {
                stages.get(metricsIdx).execute(ctx).blockLast();
            }
        } catch (Exception e) {
            log.error("Stage pipeline execution failed", e);
            return "Error: " + e.getMessage();
        }

        String result = ctx.getAttribute("finalResponse");
        return result != null ? result : finalResponse.toString();
    }

    private int indexOfStage(String stageName) {
        for (int i = 0; i < stages.size(); i++) {
            if (stageName.equals(stages.get(i).getStageName())) {
                return i;
            }
        }
        return -1;
    }

    /** 无 ReflectionStage 时的简单串接（无重试） */
    private Flux<org.springframework.http.codec.ServerSentEvent<String>> simpleConcat(AgentContext ctx) {
        Flux<org.springframework.http.codec.ServerSentEvent<String>> pipeline = stages.get(0).execute(ctx);
        for (int i = 1; i < stages.size(); i++) {
            pipeline = pipeline.concatWith(stages.get(i).execute(ctx));
        }
        return pipeline
                .doOnComplete(() -> hookRegistry.dispatchBeforeAgentFinalize(ctx));
    }

    /** 无 ReflectionStage 时的简单串接（阻塞，无重试） */
    private String simpleConcatBlocking(AgentContext ctx) {
        StringBuilder finalResponse = new StringBuilder();
        try {
            Flux<org.springframework.http.codec.ServerSentEvent<String>> pipeline = stages.get(0).execute(ctx);
            for (int i = 1; i < stages.size(); i++) {
                pipeline = pipeline.concatWith(stages.get(i).execute(ctx));
            }
            pipeline
                    .doOnNext(event -> {
                        if ("message".equals(event.event()) && event.data() != null) {
                            if (finalResponse.length() > 0) finalResponse.append("\n");
                            finalResponse.append(event.data());
                        }
                    })
                    .doOnComplete(() -> ctx.setAttribute("finalResponse", finalResponse.toString()))
                    .blockLast();
        } catch (Exception e) {
            log.error("Stage pipeline execution failed", e);
            return "Error: " + e.getMessage();
        }
        // beforeAgentFinalize dispatch
        hookRegistry.dispatchBeforeAgentFinalize(ctx);
        String result = ctx.getAttribute("finalResponse");
        return result != null ? result : finalResponse.toString();
    }

    // ========== 注解解析 ==========

    private String resolveSystemMessage(Method method, Object[] args) {
        SystemMessage sm = method.getAnnotation(SystemMessage.class);
        if (sm != null && !sm.value().isEmpty()) {
            return substituteTemplates(sm.value(), method, args);
        }
        return defaultSystemPrompt;
    }

    private String resolveUserMessage(Method method, Object[] args) {
        UserMessage methodUM = method.getAnnotation(UserMessage.class);
        if (methodUM != null && !methodUM.value().isEmpty()) {
            return substituteTemplates(methodUM.value(), method, args);
        }

        Parameter[] params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            if (params[i].isAnnotationPresent(UserMessage.class)) {
                Object val = (args != null && i < args.length) ? args[i] : null;
                return val != null ? val.toString() : "";
            }
        }

        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof String s) return s;
            }
            if (args.length > 0 && args[0] != null) return args[0].toString();
        }
        return "";
    }

    private String substituteTemplates(String template, Method method, Object[] args) {
        String result = template;
        Parameter[] params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            V v = params[i].getAnnotation(V.class);
            if (v != null) {
                String replacement = (args != null && i < args.length && args[i] != null)
                        ? args[i].toString() : "";
                result = result.replace("{{" + v.value() + "}}", replacement);
            }
        }
        return result;
    }

    /**
     * 检查方法的返回类型是否为 {@code Flux<ServerSentEvent<String>>}。
     * 若是，则透传所有 SSE 事件类型（message/tool_call/tool_approval 等）；
     * 否则只提取 "message" 事件中的文本。
     */
    private boolean isFluxOfServerSentEvent(Method method) {
        java.lang.reflect.Type genericReturn = method.getGenericReturnType();
        if (genericReturn instanceof ParameterizedType pt) {
            if (pt.getRawType() == Flux.class) {
                java.lang.reflect.Type[] typeArgs = pt.getActualTypeArguments();
                if (typeArgs.length == 1 && typeArgs[0] instanceof ParameterizedType inner) {
                    return inner.getRawType() == org.springframework.http.codec.ServerSentEvent.class;
                }
            }
        }
        return false;
    }

    /**
     * Resolve a level value with priority: request-level override &gt; config-level default.
     * Returns null if neither is set.
     */
    private static String resolveLevel(String requestValue, String configDefault) {
        if (requestValue != null && !requestValue.isEmpty()) {
            return requestValue;
        }
        if (configDefault != null && !configDefault.isEmpty()) {
            return configDefault;
        }
        return null;
    }

    private ChatRequest buildChatRequest(Method method, String userMessage, String systemPrompt) {
        ChatRequest.ChatRequestBuilder builder = ChatRequest.builder()
                .messages(new ArrayList<>(List.of(Message.user(userMessage))))
                .toolChoice("auto");

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            builder.systemPrompt(systemPrompt);
        }

        String model = (modelOverride != null && !modelOverride.isEmpty()) ? modelOverride :
                (resolvedConfig.getModel() != null && !resolvedConfig.getModel().isEmpty()) ? resolvedConfig.getModel() : null;
        if (model != null && !model.isEmpty()) {
            builder.model(model);
        }

        // Phase 3: set agentId from resolved config so ChatRequest carries agent identity
        // Enables AgentRouter to identify which agent is handling a given request
        if (resolvedConfig.getAgentId() != null && !resolvedConfig.getAgentId().isEmpty()) {
            builder.agentId(resolvedConfig.getAgentId());
        }

        Class<?> returnType = method.getReturnType();
        if (returnType == Flux.class) {
            builder.stream(true);
        }

        ChatRequest request = builder.build();
        List<ToolDefinition> tools = toolRegistry.getAllDefinitions(request);
        request.setTools(tools);

        return request;
    }
}
