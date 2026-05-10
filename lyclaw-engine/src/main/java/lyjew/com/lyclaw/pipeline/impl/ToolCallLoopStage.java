package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.tool.ToolCallPolicy;
import lyjew.com.lyclaw.tool.ToolErrorAction;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Sinks;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Pipeline 第三阶段 —— 工具调用循环阶段。
 *
 * <p>同步和流式模式复用同一个 while 循环结构：
 * <pre>
 * while (round < maxRounds) {
 *     ① 调用模型（同步/流式）
 *     ② 检测工具调用
 *     ③ 无工具 → break
 *     ④ 有工具 → 执行 → 注入消息列表
 *     ⑤ round++
 * }
 * </pre>
 *
 * <p><b>流式模式</b>：操作符链处理 SSE 数据流（转发 + 收集 + 检测）在一条链中完成，
 * 不拆生产者/消费者，不启动后台线程。每轮的 Flux 收集到 allFluxes 列表，最终
 * {@link Flux#concat} 合并。</p>
 *
 * <p><b>同步模式</b>：调用 {@link ModelAdapter#chat} 获取完整响应，检测 tool_calls，
 * 执行工具，循环。和原始一版 {@code ToolCallLoop} 结构一致。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ModelProvider
 * @see ToolRegistry
 * @see ToolCallPolicy
 */
@Slf4j
@Component
public class ToolCallLoopStage implements PipelineStage {

    /** 最大工具调用轮次 */
    private static final int MAX_ROUNDS = 6;

    /** 流式完成等待超时（毫秒） */
    private static final long STREAM_COMPLETION_TIMEOUT_MS = 90_000;

    private final ModelProvider modelProvider;
    private final ToolRegistry toolRegistry;
    private final ToolCallPolicy toolCallPolicy;

    public ToolCallLoopStage(ModelProvider modelProvider,
                             ToolRegistry toolRegistry,
                             ToolCallPolicy toolCallPolicy) {
        this.modelProvider = modelProvider;
        this.toolRegistry = toolRegistry;
        this.toolCallPolicy = toolCallPolicy;
        log.info("  [ToolCallLoopStage] 构造器: provider={}, toolRegistry={}",
                modelProvider.getClass().getSimpleName(),
                toolRegistry.getClass().getSimpleName());
    }

    @Override
    public void process(ChatContext context, Chain chain) {
        ModelAdapter adapter = modelProvider.getConfiguredAdapter();
        List<Message> messages = context.getRequest().getMessages();
        boolean isStream = context.getRequest().isStream();

        log.info("  [ToolCallLoopStage] 入口, mode={}", isStream ? "流式" : "同步");

        List<Flux<String>> allFluxes = new ArrayList<>(4);
        ChatResultHolder syncResult = new ChatResultHolder();

        int round = 0;
        while (round < MAX_ROUNDS) {
            log.info("  [ToolCallLoopStage] 第 {} 轮 {}", round + 1, isStream ? "(流式)" : "(同步)");
            context.setAttribute("__round__", round);

            // 流式的工具检测结果（在 doOnComplete 中设置）
            AtomicReference<List<ModelResponse.ToolCallRequest>> streamCallsRef = new AtomicReference<>();
            AtomicReference<String> streamPlainTextRef = new AtomicReference<>("");
            AtomicReference<String> streamTokenUsageRef = new AtomicReference<>("prompt=0 completion=0 total=0");

            // ────────────────────────────────────────────────────────────
            // ① 调用模型
            // ────────────────────────────────────────────────────────────
            if (isStream) {
                StringBuilder collector = new StringBuilder();

                // ── 使用或创建 Sinks ──
                // DefaultEngine 流式路径已预先创建 Sinks 并存入 context，
                // 以便 Controller 在 pipeline 运行前就拿到 Flux。
                // 非 DefaultEngine 调用或测试场景则本地创建。
                Sinks.Many<String> existingSink = (Sinks.Many<String>) context.getAttribute("__realtime_sink__");
                final Sinks.Many<String> realtimeSink = existingSink != null ? existingSink : Sinks.many().replay().all();
                Flux<String> realtimeFlux = realtimeSink.asFlux();
                context.setAttribute("__realtime_flux__", realtimeFlux);

                Flux<String> rawFlux = adapter.chatStream(context.getRequest());
                CountDownLatch doneLatch = new CountDownLatch(1);

                rawFlux.subscribe(
                    chunk -> {
                        collector.append(chunk).append('\n');
                        // ★ 实时推给 Sinks，Controller 端 subscribe 后能实时收到
                        realtimeSink.tryEmitNext(chunk);
                    },
                    error -> {
                        log.error("  [ToolCallLoopStage] 流式错误: {}", error.getMessage());
                        realtimeSink.tryEmitComplete();
                        doneLatch.countDown();
                    },
                    () -> {
                        realtimeSink.tryEmitComplete();
                        String fullSSE = collector.toString();
                        log.info("  [ToolCallLoopStage] 流式完成, 收集 {} 字节", fullSSE.length());

                        List<ModelResponse.ToolCallRequest> calls = adapter.extractSseToolCalls(fullSSE);
                        streamCallsRef.set(calls);
                        streamPlainTextRef.set(adapter.extractSsePlainText(fullSSE));
                        streamTokenUsageRef.set(adapter.extractSseTokenUsage(fullSSE));

                        // 始终添加 assistant 消息（无工具时存文本，有工具时附带 tool_calls）
                        String text = streamPlainTextRef.get();
                        messages.add(Message.builder()
                                .role("assistant")
                                .content(text != null ? text : "")
                                .model(adapter.getModel())
                                .createdAt(LocalDateTime.now())
                                .build());

                        log.info("  [ToolCallLoopStage] 解析: {} 字, toolCalls={}",
                                streamPlainTextRef.get() != null ? streamPlainTextRef.get().length() : 0,
                                calls.size());
                        doneLatch.countDown();
                    }
                );

                // 等原始 Flux 收完
                try {
                    if (!doneLatch.await(STREAM_COMPLETION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        log.error("  [ToolCallLoopStage] 流式超时 {}ms", STREAM_COMPLETION_TIMEOUT_MS);
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                // realtimeFlux 已通过 context 直接给 Controller，
                // allFluxes 中放一个空 Flux（实际 Controller 走 realtimeFlux）
                allFluxes.add(Flux.empty());

                context.setAttribute("__stream_full_content__", streamPlainTextRef.get());
                context.setAttribute("__stream_token_usage__", streamTokenUsageRef.get());
                context.setAttribute("__stream_consumed__", true);
            } else {
                handleSyncRound(context, adapter, messages, syncResult);
            }

            // ────────────────────────────────────────────────────────────
            // ② 检测工具调用
            // ────────────────────────────────────────────────────────────
            List<ModelResponse.ToolCallRequest> calls;
            if (isStream) {
                calls = streamCallsRef.get();
            } else {
                ModelResponse response = syncResult.response;
                calls = (response != null && response.hasToolCalls()) ? response.getToolCalls() : List.of();
            }

            // ③ 无工具 → break
            if (calls == null || calls.isEmpty()) {
                log.info("  [ToolCallLoopStage] 无工具调用, 结束循环");
                break;
            }

            log.info("  [ToolCallLoopStage] 检测到 {} 个工具调用", calls.size());

            // ────────────────────────────────────────────────────────────
            // ④ 执行工具
            // ────────────────────────────────────────────────────────────
            Flux<String> eventFlux = executeTools(context, adapter, messages, calls, isStream);
            if (eventFlux != null) {
                allFluxes.add(eventFlux);
            }

            round++;
        }

        // ────────────────────────────────────────────────────────────
        // 合并输出
        // ────────────────────────────────────────────────────────────
        if (isStream) {
            Flux<String> merged = allFluxes.isEmpty() ? Flux.empty()
                    : allFluxes.size() == 1 ? allFluxes.get(0)
                    : Flux.concat(allFluxes);
            context.setAttribute("__stream_flux__", merged);
            log.info("  [ToolCallLoopStage] 流式: 合并 {} 个 Flux 片段", allFluxes.size());
        } else {
            log.info("  [ToolCallLoopStage] 同步完成");
        }

        chain.next(context);
    }

    /**
     * 处理一轮同步模型调用。
     */
    private void handleSyncRound(ChatContext context, ModelAdapter adapter,
                                 List<Message> messages, ChatResultHolder syncResult) {
        ModelResponse response = adapter.chat(context.getRequest());

        log.info("  [ToolCallLoopStage] 回复: toolCall={}, contentLen={}",
                response.hasToolCalls(),
                response.getContent() != null ? response.getContent().length() : 0);

        // 追加 assistant 消息（如果有工具调用，后面 ToolDetect 后再追加 tool_calls）
        messages.add(Message.builder()
                .role("assistant")
                .content(response.getContent() != null ? response.getContent() : "")
                .model(adapter.getModel())
                .build());

        syncResult.response = response;
        syncResult.content = response.getContent();
    }

    /**
     * 执行工具调用（同步和流式完全复用同一段逻辑）。
     *
     * @return 工具调用事件的 Flux（仅流式模式下非 null）
     */
    private Flux<String> executeTools(ChatContext context, ModelAdapter adapter,
                                      List<Message> messages,
                                      List<ModelResponse.ToolCallRequest> calls,
                                      boolean isStream) {

        // 追加 tool_calls 到上一条 assistant 消息
        Message lastAssistant = findLastAssistant(messages);
        if (lastAssistant != null && lastAssistant.getToolCalls() == null) {
            // 将 ToolCallRequest 转 ToolCall
            List<ToolCall> toolCalls = new ArrayList<>();
            for (ModelResponse.ToolCallRequest req : calls) {
                toolCalls.add(ToolCall.builder()
                        .toolCallId(req.getId())
                        .name(req.getName())
                        .arguments(req.getArguments())
                        .build());
            }
            lastAssistant.setToolCalls(toolCalls);
        }

        // 执行每个工具调用
        for (ModelResponse.ToolCallRequest req : calls) {
            try {
                ToolCall toolCall = ToolCall.builder()
                        .toolCallId(req.getId())
                        .name(req.getName())
                        .arguments(req.getArguments())
                        .build();

                log.info("  [ToolCallLoopStage] 执行工具: {} {} args={}",
                        toolCall.getName(), toolCall.getToolCallId(), toolCall.getArguments());

                ToolResult result = toolRegistry.execute(toolCall, context);

                // 追加工具结果消息
                messages.add(Message.builder()
                        .role("tool")
                        .toolCallId(req.getId())
                        .content(result.isSuccess() ? result.getResult() : result.getError())
                        .build());

                log.info("  [ToolCallLoopStage] 工具 {} 完成: {}",
                        toolCall.getName(), result.isSuccess() ? "成功" : "失败");
            } catch (Exception e) {
                ToolErrorAction action = toolCallPolicy.handleToolError(null, e, context);
                messages.add(Message.builder()
                        .role("tool")
                        .toolCallId(req.getId())
                        .content("Error: " + e.getMessage())
                        .build());
                log.error("  [ToolCallLoopStage] 工具 {} 执行异常: {}", req.getName(), e.getMessage());

                if (action == ToolErrorAction.ABORT) {
                    context.setAttribute("error", e.getMessage());
                    break;
                }
            }
        }

        // 流式模式：构建工具调用事件，推入 realtimeSink 实时透传给客户端
        if (isStream) {
            Flux<String> toolFlux = buildToolEventFlux(calls);
            Sinks.Many<String> realtimeSink = (Sinks.Many<String>) context.getAttribute("__realtime_sink__");
            if (realtimeSink != null) {
                toolFlux.subscribe(
                    event -> realtimeSink.tryEmitNext(event),
                    error -> log.warn("Tool event push error", error),
                    () -> {} // 不 complete sink，由主流程统一 complete
                );
            }
            return toolFlux;
        }
        return null;
    }

    /**
     * 构建工具调用事件的 SSE Flux。
     *
     * <p>事件格式：
     * <pre>
     * data: {"type":"tool_call","name":"get_weather","status":"executing"}
     * data: {"type":"tool_call","name":"get_weather","status":"done","result":"..."}
     * </pre>
     * </p>
     */
    private Flux<String> buildToolEventFlux(List<ModelResponse.ToolCallRequest> calls) {
        return Flux.create((Consumer<FluxSink<String>>) sink -> {
            for (ModelResponse.ToolCallRequest req : calls) {
                String json = "{\"type\":\"tool_call\",\"name\":\""
                        + escapeJson(req.getName()) + "\",\"status\":\"executing\"}";
                sink.next("data: " + json + "\n\n");
            }
            for (ModelResponse.ToolCallRequest req : calls) {
                String json = "{\"type\":\"tool_call\",\"name\":\""
                        + escapeJson(req.getName()) + "\",\"status\":\"done\"}";
                sink.next("data: " + json + "\n\n");
            }
            sink.complete();
        });
    }

    private Message findLastAssistant(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("assistant".equals(messages.get(i).getRole())) {
                return messages.get(i);
            }
        }
        return null;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    public int getOrder() {
        return 2;
    }

    @Override
    public String getStageName() {
        return "ToolCallLoop";
    }

    /**
     * 同步模式结果持有者（替代 ToolCallLoop 的 ChatResult）。
     */
    private static class ChatResultHolder {
        ModelResponse response;
        String content;
    }
}
