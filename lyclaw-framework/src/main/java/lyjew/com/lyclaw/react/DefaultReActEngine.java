package lyjew.com.lyclaw.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.chat.ChatModel;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;
import lyjew.com.lyclaw.model.ToolCall;

import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private static final int MAX_TOOL_ROUNDS = 30;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ── 非流式 ReAct ────────────────────────────────────────────────

    @Override
    public String execute(ChatFacade chatFacade, ChatRequest request, ToolExecutor toolExecutor) {
        List<Message> messages = request.getMessages();

        // 无工具执行器时退化为单次 LLM 调用
        if (toolExecutor == null) {
            try {
                ModelResponse response = chatFacade.chat(request);
                String content = response.getContent() != null ? response.getContent() : "";
                messages.add(Message.assistant(content));
                return content;
            } catch (Exception e) {
                log.error("ReAct LLM call failed (no tools): {}", e.getMessage(), e);
                return "[LLM调用失败: " + e.getMessage() + "]";
            }
        }

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            log.debug("ReAct round {}/{}", round + 1, MAX_TOOL_ROUNDS);
            ModelResponse response;
            try {
                response = chatFacade.chat(request);
            } catch (Exception e) {
                log.error("ReAct LLM call failed in round {}: {}", round, e.getMessage(), e);
                return "[LLM调用失败: " + e.getMessage() + "]";
            }

            if (!response.hasToolCalls()) {
                String content = response.getContent() != null ? response.getContent() : "";
                messages.add(Message.builder()
                        .role("assistant")
                        .content(content)
                        .thinking(response.getThinking() != null ? response.getThinking() : "")
                        .build());
                return content;
            }

            // 追加 assistant 消息（含工具调用列表）
            messages.add(Message.builder()
                    .role("assistant")
                    .content(response.getContent() != null ? response.getContent() : "")
                    .thinking(response.getThinking() != null ? response.getThinking() : "")
                    .toolCalls(toMessageToolCalls(response))
                    .build());

            // 执行每个工具调用，追加 tool 消息
            for (ModelResponse.ToolCallRequest req : response.getToolCalls()) {
                log.debug("ReAct executing tool: name={} id={}", req.getName(), req.getId());
                try {
                    String toolOutput = toolExecutor.execute(
                            req.getName(), req.getId(),
                            req.getArguments() != null ? req.getArguments() : "{}");
                    messages.add(Message.tool(req.getId(), toolOutput));
                } catch (Exception e) {
                    log.error("Tool execution failed: name={} error={}", req.getName(), e.getMessage(), e);
                    messages.add(Message.tool(req.getId(), "Tool error: " + e.getMessage()));
                }
            }
        }

        return "[已达最大工具调用轮数(" + MAX_TOOL_ROUNDS + ")]";
    }

    // ── 流式 ReAct（工具检测）────────────────────────────────────────

    @Override
    public Flux<ServerSentEvent<String>> executeStream(ChatFacade chatFacade, ChatRequest request,
                                                       ToolExecutor toolExecutor) {
        // 无工具执行器时退化为简单流式
        if (toolExecutor == null) {
            return simpleStream(chatFacade, request);
        }

        ChatModel model = chatFacade.resolveModel(chatFacade.route(request, null));
        log.debug("ReAct stream-detect via {}:{}", model.provider(), model.model());

        // 状态: 0=buffering(思考), 1=relaying(纯文本), 2=tools_detected
        int[] state = {0};
        List<ModelResponse> buffer = new ArrayList<>();

        return model.stream(request)
                .<ServerSentEvent<String>>handle((chunk, sink) -> {
                    boolean hasContent = chunk.getContent() != null && !chunk.getContent().isEmpty();
                    boolean hasToolCalls = chunk.getToolCalls() != null && !chunk.getToolCalls().isEmpty();

                    if (state[0] == 2) { // 已在收集模式
                        buffer.add(chunk);
                        return;
                    }

                    if (state[0] == 1) { // 已在透传模式
                        if (hasToolCalls) {
                            state[0] = 2;
                            buffer.add(chunk);
                            log.warn("Tool call appeared after content streaming began - unusual");
                            return;
                        }
                        if (hasContent) {
                            sink.next(sseEvent("message", chunk.getContent()));
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
                        sink.next(sseEvent("message", chunk.getContent()));
                        return;
                    }
                    buffer.add(chunk); // 思考内容或空 chunk
                })
                .concatWith(Flux.<ServerSentEvent<String>>defer(() -> {
                    if (state[0] == 2) {
                        request.setStream(false);
                        ModelResponse merged = model.mergeChunks(buffer);
                        log.info("ReAct tools detected in stream: {}",
                                merged.getToolCalls() != null
                                        ? merged.getToolCalls().stream()
                                                .map(ModelResponse.ToolCallRequest::getName).toList()
                                        : "[]");
                        return multiRoundReActFlux(chatFacade, request, toolExecutor, merged)
                                .subscribeOn(Schedulers.boundedElastic());
                    }
                    if (state[0] == 0) {
                        // 全程思考模式 → 合并返回
                        ModelResponse merged = model.mergeChunks(buffer);
                        String content = merged.getContent() != null ? merged.getContent() : "";
                        if (!content.isEmpty()) {
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
                .concatWith(emitRoundToolCallEvents(firstResponse.getToolCalls(), toolExecutor, messages))
                .concatWith(continueReActRounds(chatFacade, request, toolExecutor, 1));
    }

    /** 对工具调用列表逐项执行并发出 tool_call SSE 事件（executing → done） */
    private Flux<ServerSentEvent<String>> emitRoundToolCallEvents(
            List<ModelResponse.ToolCallRequest> toolCalls, ToolExecutor toolExecutor,
            List<Message> messages) {
        return Flux.fromIterable(toolCalls)
                .concatMap(req -> {
                    String toolArgs = req.getArguments() != null ? req.getArguments() : "{}";
                    String execJson = toolCallEventJson(req.getId(), req.getName(),
                            "executing", "正在执行 " + req.getName() + "...", toolArgs, null, true);
                    return Flux.just(sseEvent("tool_call", execJson))
                            .concatWith(Flux.defer(() -> {
                                String output;
                                boolean success;
                                try {
                                    output = toolExecutor.execute(req.getName(), req.getId(), toolArgs);
                                    success = true;
                                } catch (Exception e) {
                                    log.error("Tool execution failed: name={} error={}",
                                            req.getName(), e.getMessage(), e);
                                    output = "Tool error: " + e.getMessage();
                                    success = false;
                                }
                                messages.add(Message.tool(req.getId(), output));
                                String doneJson = toolCallEventJson(req.getId(), req.getName(),
                                        "done", req.getName() + " 完成", toolArgs, output, success);
                                return Flux.just(sseEvent("tool_call", doneJson));
                            }));
                });
    }

    /**
     * 后续 ReAct 轮次（第 1..MAX_TOOL_ROUNDS-1 轮）。
     *
     * <p>使用 stream=true 调用 LLM 进行工具检测：若检测到 tool_calls 则处理并继续；
     * 若无工具调用，文本通过真流式逐 token 推送给前端，不再走 splitIntoEvents 模拟。</p>
     */
    private Flux<ServerSentEvent<String>> continueReActRounds(
            ChatFacade chatFacade, ChatRequest request, ToolExecutor toolExecutor, int round) {
        if (round >= MAX_TOOL_ROUNDS) {
            return Flux.just(sseEvent("message", "[已达最大工具调用轮数(" + MAX_TOOL_ROUNDS + ")]"));
        }

        return Flux.defer(() -> {
            log.debug("ReAct stream round {}/{}", round + 1, MAX_TOOL_ROUNDS);
            request.setStream(true);
            ChatModel model = chatFacade.resolveModel(chatFacade.route(request, null));

            int[] state = {0};
            List<ModelResponse> buffer = new ArrayList<>();
            StringBuilder contentCollector = new StringBuilder();

            return model.stream(request)
                    .<ServerSentEvent<String>>handle((chunk, sink) -> {
                        boolean hasContent = chunk.getContent() != null && !chunk.getContent().isEmpty();
                        boolean hasToolCalls = chunk.getToolCalls() != null && !chunk.getToolCalls().isEmpty();

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
                                    .concatWith(emitRoundToolCallEvents(merged.getToolCalls(), toolExecutor, request.getMessages()))
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
            return "{\"error\":\"json\"}";
        }
    }

    /** 无工具时的简单流式，逐 token 推送 */
    private Flux<ServerSentEvent<String>> simpleStream(ChatFacade chatFacade, ChatRequest request) {
        request.setTools(null);
        request.setToolChoice(null);
        ChatModel model = chatFacade.resolveModel(chatFacade.route(request, null));
        log.debug("ReAct simple stream via {}:{}", model.provider(), model.model());
        return model.stream(request)
                .handle((chunk, sink) -> {
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
