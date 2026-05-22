package lyjew.com.lyclaw.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.chat.ChatModel;
import lyjew.com.lyclaw.config.AgentProperties;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;
import lyjew.com.lyclaw.model.ToolCall;

import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * ReAct 引擎默认实现，提供 LLM 多轮推理-行动循环。
 *
 * <p>支持两种调用模式：
 * <ul>
 *   <li>{@link #execute(ChatFacade, ChatRequest, ToolExecutor)} — 非流式 ReAct，
 *       直接对 messages 列表原地追加 assistant/tool 消息，直到 LLM 返回纯文本</li>
 *   <li>{@link #executeStream(ChatFacade, ChatRequest, ToolExecutor)} — 流式 ReAct，
 *       先尝试 stream=true 探测，纯文本直接透传，检测到 tool_calls 则收集碎片后
 *       重启非流式循环</li>
 * </ul>
 *
 * <p>通过 {@link InteractionMode} 注解标记为默认的 "react" 交互模式。
 * 工具执行通过 {@link ToolExecutor} 函数式接口委托给调用方，引擎本身不依赖
 * 任何具体的工具执行机制。
 */
@Slf4j
@InteractionMode(name = "react", description = "Reasoning-Acting loop with tool execution", isDefault = true)
public class DefaultReActEngine implements ReActEngine {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Phase 2: thinking level → thinking budget mapping (token counts)
    private static final int THINKING_BUDGET_LOW = 1024;
    private static final int THINKING_BUDGET_MEDIUM = 4096;
    private static final int THINKING_BUDGET_HIGH = 16384;

    private final ApprovalStore approvalStore;
    private final int maxToolRounds;
    private final long approvalTimeoutSeconds;

    /** 需要用户审批的工具名集合（通常是 readonly=false 的工具） */
    private final Set<String> approvalRequired = ConcurrentHashMap.newKeySet();

    public DefaultReActEngine(ApprovalStore approvalStore, AgentProperties agentProperties) {
        this.approvalStore = approvalStore;
        this.maxToolRounds = agentProperties.getMaxToolRounds();
        this.approvalTimeoutSeconds = agentProperties.getApprovalStoreTimeoutSeconds();
    }

    /**
     * 设置需要用户审批的工具名集合。RespondStage 根据工具定义中的 readonly 标记调用此方法。
     */
    @Override
    public void setApprovalRequired(Set<String> toolNames) {
        this.approvalRequired.clear();
        if (toolNames != null) {
            this.approvalRequired.addAll(toolNames);
        }
    }

    // ── 非流式 ReAct ────────────────────────────────────────────────

    /**
     * Phase 2: apply thinking level from ChatRequest to thinkingEnabled/thinkingBudget.
     * Mapping: "off"→disabled, "low"→1024, "medium"→4096, "high"→16384,
     * "minimal"→512, "xhigh"→32768, "adaptive"/"max"→16384.
     * Falls back to ChatRequest.thinkingEnabled if already set.
     */
    private static void applyThinkingLevel(ChatRequest request) {
        if (request.isThinkingEnabled() || request.getThinkingBudget() != null) {
            return; // already explicitly configured, don't override
        }
        String level = request.getThinkingLevel();
        if (level == null || level.isEmpty() || "off".equalsIgnoreCase(level)) {
            request.setThinkingEnabled(false);
            return;
        }
        int budget;
        switch (level.toLowerCase()) {
            case "minimal":  budget = 512;   break;
            case "low":      budget = THINKING_BUDGET_LOW;    break;
            case "medium":   budget = THINKING_BUDGET_MEDIUM; break;
            case "high":     budget = THINKING_BUDGET_HIGH;   break;
            case "xhigh":
            case "max":
            case "adaptive": budget = THINKING_BUDGET_HIGH;   break;
            default:         budget = THINKING_BUDGET_MEDIUM; break;
        }
        request.setThinkingEnabled(true);
        request.setThinkingBudget(budget);
    }

    @Override
    public String execute(ChatFacade chatFacade, ChatRequest request, ToolExecutor toolExecutor) {
        applyThinkingLevel(request);
        List<Message> messages = request.getMessages();
        log.info("🧠 [ReAct引擎] 非流式ReAct启动 | 最大轮数={} | 有工具执行器={}", maxToolRounds, toolExecutor != null);

        // 无工具执行器时退化为单次 LLM 调用
        if (toolExecutor == null) {
            log.info("ℹ️ [ReAct引擎] 无工具执行器，退化为单次LLM调用");
            try {
                ModelResponse response = chatFacade.chat(request);
                String content = response.getContent() != null ? response.getContent() : "";
                messages.add(Message.assistant(content));
                log.info("✅ [ReAct引擎] 单次LLM调用完成 | 响应长度={}", content.length());
                return content;
            } catch (Exception e) {
                log.error("❌ [ReAct引擎] LLM调用失败（无工具）: {}", e.getMessage(), e);
                return "[LLM调用失败: " + e.getMessage() + "]";
            }
        }

        for (int round = 0; round < maxToolRounds; round++) {
            log.info("🔄 [ReAct引擎] 第{}/{}轮推理开始", round + 1, maxToolRounds);
            ModelResponse response;
            try {
                response = chatFacade.chat(request);
            } catch (Exception e) {
                log.error("❌ [ReAct引擎] 第{}轮LLM调用失败: {}", round, e.getMessage(), e);
                return "[LLM调用失败: " + e.getMessage() + "]";
            }

            if (!response.hasToolCalls()) {
                String content = response.getContent() != null ? response.getContent() : "";
                messages.add(Message.builder()
                        .role("assistant")
                        .content(content)
                        .thinking(response.getThinking() != null ? response.getThinking() : "")
                        .build());
                log.info("✅ [ReAct引擎] 第{}轮完成（纯文本响应）| 响应长度={}", round + 1, content.length());
                return content;
            }

            log.info("🔧 [ReAct引擎] 第{}轮检测到{}个工具调用: {}",
                    round + 1, response.getToolCalls().size(),
                    response.getToolCalls().stream().map(ModelResponse.ToolCallRequest::getName).toList());

            // 追加 assistant 消息（含工具调用列表）
            messages.add(Message.builder()
                    .role("assistant")
                    .content(response.getContent() != null ? response.getContent() : "")
                    .thinking(response.getThinking() != null ? response.getThinking() : "")
                    .toolCalls(toMessageToolCalls(response))
                    .build());

            // 执行每个工具调用，追加 tool 消息
            for (ModelResponse.ToolCallRequest req : response.getToolCalls()) {
                log.info("🔨 [ReAct引擎] 执行工具: {} | toolCallId={}", req.getName(), req.getId());
                try {
                    String toolOutput = toolExecutor.execute(
                            req.getName(), req.getId(),
                            req.getArguments() != null ? req.getArguments() : "{}");
                    messages.add(Message.tool(req.getId(), toolOutput));
                    log.info("✅ [ReAct引擎] 工具执行完成: {} | 输出长度={}", req.getName(),
                            toolOutput != null ? toolOutput.length() : 0);
                } catch (Exception e) {
                    log.error("❌ [ReAct引擎] 工具执行异常: name={} error={}", req.getName(), e.getMessage(), e);
                    messages.add(Message.tool(req.getId(), "工具错误: " + e.getMessage()));
                }
            }
        }

        log.warn("⚠️ [ReAct引擎] 已达最大工具调用轮数({})", maxToolRounds);
        return "[已达最大工具调用轮数(" + maxToolRounds + ")]";
    }

    // ── 流式 ReAct（工具检测）────────────────────────────────────────

    @Override
    public Flux<ServerSentEvent<String>> executeStream(ChatFacade chatFacade, ChatRequest request,
                                                       ToolExecutor toolExecutor) {
        applyThinkingLevel(request);
        // 无工具执行器时退化为简单流式
        if (toolExecutor == null) {
            log.info("ℹ️ [ReAct流式] 无工具执行器，退化为简单流式");
            return simpleStream(chatFacade, request);
        }

        ChatModel model = chatFacade.resolveModel(chatFacade.route(request, null));
        log.info("🌊 [ReAct流式] 启动流式+工具检测 | provider={} | model={} | 最大轮数={}",
                model.provider(), model.model(), maxToolRounds);

        // 状态: 0=buffering(思考), 1=relaying(纯文本), 2=tools_detected
        int[] state = {0};
        List<ModelResponse> buffer = new ArrayList<>();
        StringBuilder contentCollector = new StringBuilder();
        StringBuilder thinkingCollector = new StringBuilder();

        return model.stream(request)
                .<ServerSentEvent<String>>handle((chunk, sink) -> {
                    boolean hasContent = chunk.getContent() != null && !chunk.getContent().isEmpty();
                    boolean hasToolCalls = chunk.getToolCalls() != null && !chunk.getToolCalls().isEmpty();
                    boolean hasThinking = chunk.getThinking() != null && !chunk.getThinking().isEmpty();

                    // Phase 2: emit thinking events for reasoning_content
                    if (hasThinking) {
                        sink.next(sseEvent("thinking", chunk.getThinking()));
                    }

                    if (state[0] == 2) { // 已在收集模式
                        buffer.add(chunk);
                        return;
                    }

                    if (state[0] == 1) { // 已在透传模式
                        if (hasToolCalls) {
                            state[0] = 2;
                            buffer.add(chunk);
                            log.warn("⚠️ [ReAct流式] 工具调用在文本流式传输后出现（异常情况）");
                            return;
                        }
                        if (hasContent) {
                            contentCollector.append(chunk.getContent());
                            sink.next(sseEvent("message", chunk.getContent()));
                        }
                        if (hasThinking) {
                            thinkingCollector.append(chunk.getThinking());
                        }
                        return;
                    }

                    // state[0] == 0: 缓冲思考阶段
                    if (hasToolCalls) {
                        state[0] = 2;
                        buffer.add(chunk);
                        sink.next(sseEvent("status", "Executing tool call..."));
                        return;
                    }
                    if (hasContent) {
                        state[0] = 1;
                        contentCollector.append(chunk.getContent());
                        sink.next(sseEvent("message", chunk.getContent()));
                        return;
                    }
                    buffer.add(chunk); // 思考内容或空 chunk
                })
                .concatWith(Flux.<ServerSentEvent<String>>defer(() -> {
                    if (state[0] == 2) {
                        request.setStream(false);
                        ModelResponse merged = model.mergeChunks(buffer);
                        log.info("🔧 [ReAct流式] 检测到工具调用: {}",
                                merged.getToolCalls() != null
                                        ? merged.getToolCalls().stream()
                                                .map(ModelResponse.ToolCallRequest::getName).toList()
                                        : "[]");
                        return multiRoundReActFlux(chatFacade, request, toolExecutor, merged)
                                .subscribeOn(Schedulers.boundedElastic());
                    }
                    if (state[0] == 1) {
                        // 真流式文本已逐token发出 → 写入消息历史
                        String content = contentCollector.toString();
                        String thinking = thinkingCollector.length() > 0 ? thinkingCollector.toString() : null;
                        if (!content.isEmpty()) {
                            request.getMessages().add(Message.builder()
                                    .role("assistant")
                                    .content(content)
                                    .thinking(thinking)
                                    .build());
                        }
                        return Flux.empty();
                    }
                    if (state[0] == 0) {
                        // 全程思考模式 → 合并返回 + 写入消息历史
                        ModelResponse merged = model.mergeChunks(buffer);
                        String content = merged.getContent() != null ? merged.getContent() : "";
                        if (!content.isEmpty()) {
                            request.getMessages().add(Message.builder()
                                    .role("assistant")
                                    .content(content)
                                    .thinking(merged.getThinking() != null ? merged.getThinking() : "")
                                    .build());
                            return Flux.just(sseEvent("message", content));
                        }
                    }
                    return Flux.empty();
                }));
    }

    // ── 内部方法 ─────────────────────────────────────────────────────

    /**
     * 从流式检测到的首轮响应启动 Flux 化多轮 ReAct 循环。
     * <p>首轮工具调用前发出 tool_call executing 事件，执行后发出 done 事件，
     * 后续轮次委托 {@link #continueReActRounds} 处理。</p>
     */
    private Flux<ServerSentEvent<String>> multiRoundReActFlux(ChatFacade chatFacade, ChatRequest request,
                                                               ToolExecutor toolExecutor, ModelResponse firstResponse) {
        List<Message> messages = request.getMessages();
        boolean hasToolCalls = firstResponse.hasToolCalls();
        String content = firstResponse.getContent() != null ? firstResponse.getContent() : "";

        if (!hasToolCalls) {
            messages.add(Message.assistant(content));
            return splitIntoEvents(content);
        }

        // 工具调用前若有文本内容，先以 message 事件发出
        Flux<ServerSentEvent<String>> textFlux = content.isEmpty()
                ? Flux.empty()
                : splitIntoEvents(content);

        messages.add(Message.builder()
                .role("assistant")
                .content(content)
                .thinking(firstResponse.getThinking() != null ? firstResponse.getThinking() : "")
                .toolCalls(toMessageToolCalls(firstResponse))
                .build());

        return textFlux
                .concatWith(emitRoundToolCallEvents(firstResponse.getToolCalls(), toolExecutor, messages, request))
                .concatWith(continueReActRounds(chatFacade, request, toolExecutor, 1));
    }

    /** 对工具调用列表逐项执行并发出 tool_call SSE 事件（executing → done）。
     *  <p>需用户审批的工具先发 tool_approval 事件，等待用户响应后再执行。</p>
     *  <p>工具执行（含阻塞 Feign 调用）通过 boundedElastic 隔离，避免阻塞
     *  WebClient 的 epoll/netty 事件循环线程。</p> */
    private Flux<ServerSentEvent<String>> emitRoundToolCallEvents(
            List<ModelResponse.ToolCallRequest> toolCalls, ToolExecutor toolExecutor,
            List<Message> messages, ChatRequest request) {
        return Flux.fromIterable(toolCalls)
                .concatMap(req -> {
                    String toolArgs = req.getArguments() != null ? req.getArguments() : "{}";
                    // 需要用户审批时走审批流程
                    if (approvalRequired.contains(req.getName())) {
                        return emitApprovalFlow(req, toolExecutor, messages, toolArgs,
                                request.getSessionId(), request.getAgentId());
                    }
                    // 无需审批：直接执行
                    log.info("🔨 [ReAct流式] 直接执行工具（无需审批）: {} | toolCallId={}", req.getName(), req.getId());
                    String execJson = toolCallEventJson(req.getId(), req.getName(),
                            "executing", "正在执行 " + req.getName() + "...", toolArgs, null, true);
                    Mono<ServerSentEvent<String>> doneEvent = Mono.fromCallable(() -> {
                        String output;
                        boolean success;
                        try {
                            output = toolExecutor.execute(req.getName(), req.getId(), toolArgs);
                            success = true;
                        } catch (Exception e) {
                            log.error("❌ [ReAct流式] 工具执行失败: name={} error={}",
                                    req.getName(), e.getMessage(), e);
                            output = "工具错误: " + e.getMessage();
                            success = false;
                        }
                        messages.add(Message.tool(req.getId(), output));
                        String doneJson = toolCallEventJson(req.getId(), req.getName(),
                                "done", req.getName() + " 完成", toolArgs, output, success);
                        log.info("{} [ReAct流式] 工具执行完成: {} | 成功={}",
                                success ? "✅" : "❌", req.getName(), success);
                        return sseEvent("tool_call", doneJson);
                    }).subscribeOn(Schedulers.boundedElastic());
                    return Flux.just(sseEvent("tool_call", execJson)).concatWith(doneEvent);
                });
    }

    /** 审批流程：先创建 future 注册到 ApprovalStore → 发 tool_approval 事件 →
     *  发 tool_call executing 事件 → 等待用户响应 → 执行或拒绝 → 发 tool_call done 事件。
     *
     *  <p>关键：future 必须在 Flux 返回之前创建，否则前端可能在 create() 之前就调用
     *  approve()，导致匹配不到 pending future（竞态条件）。</p> */
    private Flux<ServerSentEvent<String>> emitApprovalFlow(
            ModelResponse.ToolCallRequest req, ToolExecutor toolExecutor,
            List<Message> messages, String toolArgs, String sessionId, String agentId) {
        // 必须在 Flux 返回前创建 future，消除竞态：保证前端 approve() 时 future 已就绪
        CompletableFuture<Boolean> future = approvalStore.create(
                req.getId(), sessionId, agentId, req.getName(), toolArgs);
        log.info("🛡️ [ReAct流式] 创建审批请求: toolCallId={} | 待审批数={} | toolName={}",
                req.getId(), approvalStore.pendingCount(), req.getName());

        String approvalJson = toolApprovalEventJson(req.getId(), req.getName(), toolArgs);
        ServerSentEvent<String> approvalEvent = sseEvent("tool_approval", approvalJson);

        String execJson = toolCallEventJson(req.getId(), req.getName(),
                "executing", "正在执行 " + req.getName() + "...", toolArgs, null, true);

        Mono<ServerSentEvent<String>> doneEvent = Mono.fromCallable(() -> {
            boolean approved;
            try {
                approved = future.get(approvalTimeoutSeconds, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("⏰ [ReAct流式] 审批超时或异常: toolCallId={} error={}", req.getId(), e.getMessage(), e);
                approved = false;
            }
            log.info("{} [ReAct流式] 审批结果: toolCallId={} approved={}",
                    approved ? "✅" : "❌", req.getId(), approved);
            String output;
            boolean success;
            if (approved) {
                try {
                    output = toolExecutor.execute(req.getName(), req.getId(), toolArgs);
                    success = true;
                } catch (Exception e) {
                    log.error("Tool execution failed: name={} error={}", req.getName(), e.getMessage(), e);
                    output = "Tool error: " + e.getMessage();
                    success = false;
                }
            } else {
                output = "用户拒绝了工具执行";
                success = false;
            }
            messages.add(Message.tool(req.getId(), output));
            String doneJson = toolCallEventJson(req.getId(), req.getName(),
                    "done", req.getName() + " 完成", toolArgs, output, success);
            return sseEvent("tool_call", doneJson);
        }).subscribeOn(Schedulers.boundedElastic());

        return Flux.just(approvalEvent, sseEvent("tool_call", execJson)).concatWith(doneEvent);
    }

    /** 构建 tool_approval SSE 事件的 JSON */
    private String toolApprovalEventJson(String toolCallId, String name, String arguments) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("toolCallId", toolCallId);
            event.put("toolName", name);
            event.put("arguments", arguments);
            event.put("message", "AI 请求执行 " + name);
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("Failed to serialize tool approval JSON", e);
            return "{\"error\":\"json\"}";
        }
    }

    /**
     * 后续 ReAct 轮次（第 1..maxToolRounds-1 轮）。
     *
     * <p>使用 stream=true 调用 LLM 进行工具检测：若检测到 tool_calls 则处理并继续；
     * 若无工具调用，文本通过真流式逐 token 推送给前端，不再走 splitIntoEvents 模拟。</p>
     */
    private Flux<ServerSentEvent<String>> continueReActRounds(
            ChatFacade chatFacade, ChatRequest request, ToolExecutor toolExecutor, int round) {
        if (round >= maxToolRounds) {
            return Flux.just(sseEvent("message", "[已达最大工具调用轮数(" + maxToolRounds + ")]"));
        }

        return Flux.defer(() -> {
            log.info("🔄 [ReAct流式] 第{}/{}轮推理开始", round + 1, maxToolRounds);
            request.setStream(true);
            ChatModel model = chatFacade.resolveModel(chatFacade.route(request, null));

            int[] state = {0};
            List<ModelResponse> buffer = new ArrayList<>();
            StringBuilder contentCollector = new StringBuilder();

            return model.stream(request)
                    .<ServerSentEvent<String>>handle((chunk, sink) -> {
                        boolean hasContent = chunk.getContent() != null && !chunk.getContent().isEmpty();
                        boolean hasToolCalls = chunk.getToolCalls() != null && !chunk.getToolCalls().isEmpty();
                        boolean hasThinking = chunk.getThinking() != null && !chunk.getThinking().isEmpty();

                        // Phase 2: emit thinking events for reasoning_content
                        if (hasThinking) {
                            sink.next(sseEvent("thinking", chunk.getThinking()));
                        }

                        if (state[0] == 2) { // 已在收集模式
                            buffer.add(chunk);
                            return;
                        }
                        if (state[0] == 1) { // 已在真流式透传
                            if (hasToolCalls) {
                                state[0] = 2;
                                buffer.add(chunk);
                                return;
                            }
                            if (hasContent) {
                                contentCollector.append(chunk.getContent());
                                sink.next(sseEvent("message", chunk.getContent()));
                            }
                            return;
                        }
                        // state[0] == 0: 缓冲思考
                        if (hasToolCalls) {
                            state[0] = 2;
                            buffer.add(chunk);
                            return;
                        }
                        if (hasContent) {
                            state[0] = 1;
                            contentCollector.append(chunk.getContent());
                            sink.next(sseEvent("message", chunk.getContent()));
                            return;
                        }
                        buffer.add(chunk);
                    })
                    .concatWith(Flux.<ServerSentEvent<String>>defer(() -> {
                        if (state[0] == 2) {
                            // 检测到工具调用 → 合并碎片，发 tool_call 事件，继续下一轮
                            request.setStream(false);
                            ModelResponse merged = model.mergeChunks(buffer);
                            String textContent = merged.getContent() != null ? merged.getContent() : "";
                            Flux<ServerSentEvent<String>> preTextFlux = textContent.isEmpty()
                                    ? Flux.empty()
                                    : Flux.just(sseEvent("message", textContent));

                            request.getMessages().add(Message.builder()
                                    .role("assistant")
                                    .content(textContent)
                                    .thinking(merged.getThinking() != null ? merged.getThinking() : "")
                                    .toolCalls(toMessageToolCalls(merged))
                                    .build());

                            return preTextFlux
                                    .concatWith(emitRoundToolCallEvents(merged.getToolCalls(), toolExecutor, request.getMessages(), request))
                                    .concatWith(continueReActRounds(chatFacade, request, toolExecutor, round + 1));
                        }
                        if (state[0] == 1) {
                            // 真流式文本已逐 token 发出 → 写入消息历史
                            String content = contentCollector.toString();
                            if (!content.isEmpty()) {
                                request.getMessages().add(Message.builder()
                                        .role("assistant")
                                        .content(content)
                                        .build());
                            }
                            return Flux.empty();
                        }
                        // state[0] == 0: 仅思考内容 → 合并后若有文本则单次发出
                        ModelResponse merged = model.mergeChunks(buffer);
                        String content = merged.getContent() != null ? merged.getContent() : "";
                        if (!content.isEmpty()) {
                            request.getMessages().add(Message.builder()
                                    .role("assistant")
                                    .content(content)
                                    .thinking(merged.getThinking() != null ? merged.getThinking() : "")
                                    .build());
                            return Flux.just(sseEvent("message", content));
                        }
                        return Flux.empty();
                    }));
        });
    }

    /** 构建 tool_call SSE 事件的 JSON 数据。done 事件携带 result/success 供前端持久化展示。 */
    private String toolCallEventJson(String toolCallId, String name, String status,
                                      String message, String arguments, String result, boolean success) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("toolCallId", toolCallId);
            event.put("name", name);
            event.put("status", status);
            event.put("message", message);
            if (arguments != null && !arguments.isEmpty()) {
                event.put("arguments", arguments);
            }
            if (result != null && !result.isEmpty()) {
                event.put("result", result);
            }
            event.put("success", success);
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("Failed to serialize tool call JSON", e);
            return "{\"error\":\"json\"}";
        }
    }

    /** 无工具时的简单流式，逐 token 推送 */
    private Flux<ServerSentEvent<String>> simpleStream(ChatFacade chatFacade, ChatRequest request) {
        request.setTools(null);
        request.setToolChoice(null);
        applyThinkingLevel(request);
        ChatModel model = chatFacade.resolveModel(chatFacade.route(request, null));
        log.info("🌊 [ReAct流式] 简单流式模式 | provider={} | model={}", model.provider(), model.model());
        return model.stream(request)
                .handle((chunk, sink) -> {
                    // Phase 2: emit thinking events for reasoning_content
                    String thinking = chunk.getThinking();
                    if (thinking != null && !thinking.isEmpty()) {
                        sink.next(sseEvent("thinking", thinking));
                    }
                    String text = chunk.getContent() != null ? chunk.getContent() : "";
                    if (!text.isEmpty()) {
                        sink.next(sseEvent("message", text));
                    }
                });
    }

    /** 将 ModelResponse 的 toolCalls 转换为 Message 的 ToolCall 列表 */
    private List<ToolCall> toMessageToolCalls(ModelResponse response) {
        return response.getToolCalls().stream()
                .<ToolCall>map(req -> ToolCall.builder()
                        .toolCallId(req.getId())
                        .name(req.getName())
                        .arguments(req.getArguments())
                        .build())
                .toList();
    }

    /** 将文本按自然边界拆分为 SSE 事件，模拟流式输出。保留换行以保证 Markdown 渲染正确。 */
    private Flux<ServerSentEvent<String>> splitIntoEvents(String text) {
        if (text == null || text.isEmpty()) {
            return Flux.empty();
        }
        List<ServerSentEvent<String>> events = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            buf.append(c);
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '；') {
                events.add(sseEvent("message", buf.toString()));
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            events.add(sseEvent("message", buf.toString()));
        }
        return Flux.fromIterable(events);
    }

    private static ServerSentEvent<String> sseEvent(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }
}
