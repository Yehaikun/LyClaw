package lyjew.com.lyclaw.orchestration.pipeline;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.action.ToolExecuteRequest;
import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.feign.ActionFeignClient;
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
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Sinks;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 工具调用循环阶段（同步管线，order=2）。
 *
 * 这是管线的核心阶段，负责 LLM 与工具之间的交互循环。
 * 最多执行 MAX_ROUNDS=6 轮，每轮包括：
 * 1. 调用 LLM（流式或同步）
 * 2. 解析响应中的工具调用请求
 * 3. 执行工具（优先通过 ActionFeignClient 远程调用，失败则回退到本地 ToolRegistry）
 * 4. 将工具结果作为 tool 角色消息追加到对话中
 *
 * 流式模式下使用 CountDownLatch 等待流完成，超时时间为 90 秒。
 * 工具执行结果通过 Sinks.Many 实时推送到 SSE 通道。
 */
@Slf4j
@Component
public class ToolCallLoopStage implements PipelineStage {

    /** 最大工具调用轮次，防止无限循环 */
    private static final int MAX_ROUNDS = 6;
    /** 流式完成等待超时（毫秒） */
    private static final long STREAM_COMPLETION_TIMEOUT_MS = 90_000;

    private final ModelProvider modelProvider;
    private final ToolRegistry toolRegistry;
    private final ToolCallPolicy toolCallPolicy;
    private final ActionFeignClient actionFeignClient;

    public ToolCallLoopStage(ModelProvider modelProvider,
                             @org.springframework.lang.Nullable ToolRegistry toolRegistry,
                             @org.springframework.lang.Nullable ToolCallPolicy toolCallPolicy,
                             ActionFeignClient actionFeignClient) {
        this.modelProvider = modelProvider;
        this.toolRegistry = toolRegistry;
        this.toolCallPolicy = toolCallPolicy;
        this.actionFeignClient = actionFeignClient;
        log.info("[ToolCallLoopStage] Initialized: provider={}, toolRegistry={}",
                modelProvider.getClass().getSimpleName(),
                toolRegistry != null ? toolRegistry.getClass().getSimpleName() : "none");
    }

    /**
     * 工具调用循环主方法。
     *
     * 核心流程：
     * 1. 调用 LLM（流式通过 subscribe + CountDownLatch，同步直接调用）
     * 2. 从响应中解析工具调用请求
     * 3. 如果没有工具调用则终止循环
     * 4. 执行所有工具调用并将结果追加到消息列表
     * 5. 重复直到 MAX_ROUNDS 或无工具调用
     *
     * @param context 聊天上下文
     * @param chain   管线链
     */
    @Override
    public void process(ChatContext context, Chain chain) {
        ModelAdapter adapter = modelProvider.getConfiguredAdapter();
        List<Message> messages = context.getRequest().getMessages();
        boolean isStream = context.getRequest().isStream();

        log.info("[ToolCallLoopStage] Entry, mode={}", isStream ? "streaming" : "sync");

        // 收集所有 Flux 段，最后合并为一个 Flux
        List<Flux<String>> allFluxes = new ArrayList<>(4);
        ChatResultHolder syncResult = new ChatResultHolder();

        int round = 0;
        while (round < MAX_ROUNDS) {
            log.info("[ToolCallLoopStage] Round {} {}", round + 1, isStream ? "(streaming)" : "(sync)");
            context.setAttribute("__round__", round);

            // 用于跨线程传递流式解析结果
            AtomicReference<List<ModelResponse.ToolCallRequest>> streamCallsRef = new AtomicReference<>();
            AtomicReference<String> streamPlainTextRef = new AtomicReference<>("");
            AtomicReference<String> streamTokenUsageRef = new AtomicReference<>("prompt=0 completion=0 total=0");

            if (isStream) {
                // === 流式模式 ===
                StringBuilder collector = new StringBuilder();

                // 获取或创建实时 Sink（用于推送 SSE 事件到客户端）
                @SuppressWarnings("unchecked")
                Sinks.Many<String> existingSink = (Sinks.Many<String>) context.getAttribute("__realtime_sink__");
                final Sinks.Many<String> realtimeSink = existingSink != null ? existingSink : Sinks.many().replay().all();
                Flux<String> realtimeFlux = realtimeSink.asFlux();
                context.setAttribute("__realtime_flux__", realtimeFlux);

                Flux<String> rawFlux = adapter.chatStream(context.getRequest());
                CountDownLatch doneLatch = new CountDownLatch(1);  // 同步等待流完成

                rawFlux.subscribe(
                    chunk -> {
                        // 每个 chunk 追加到收集器和实时 Sink
                        collector.append(chunk).append('\n');
                        realtimeSink.tryEmitNext(chunk);
                    },
                    error -> {
                        log.error("[ToolCallLoopStage] Stream error: {}", error.getMessage());
                        realtimeSink.tryEmitComplete();
                        doneLatch.countDown();
                    },
                    () -> {
                        // 流完成后解析工具调用和纯文本
                        realtimeSink.tryEmitComplete();
                        String fullSSE = collector.toString();
                        log.info("[ToolCallLoopStage] Stream complete, collected {} bytes", fullSSE.length());

                        // 从 SSE 数据中提取工具调用、纯文本和 token 用量
                        List<ModelResponse.ToolCallRequest> calls = adapter.extractSseToolCalls(fullSSE);
                        streamCallsRef.set(calls);
                        streamPlainTextRef.set(adapter.extractSsePlainText(fullSSE));
                        streamTokenUsageRef.set(adapter.extractSseTokenUsage(fullSSE));

                        // 将 assistant 消息加入对话历史
                        String text = streamPlainTextRef.get();
                        messages.add(Message.builder()
                                .role("assistant")
                                .content(text != null ? text : "")
                                .model(adapter.getModel())
                                .createdAt(LocalDateTime.now())
                                .build());

                        log.info("[ToolCallLoopStage] Parsed: {} chars, toolCalls={}",
                                streamPlainTextRef.get() != null ? streamPlainTextRef.get().length() : 0,
                                calls.size());
                        doneLatch.countDown();
                    }
                );

                // 等待流完成（带超时保护）
                try {
                    if (!doneLatch.await(STREAM_COMPLETION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        log.error("[ToolCallLoopStage] Stream timeout {}ms", STREAM_COMPLETION_TIMEOUT_MS);
                        break;  // 超时则终止循环
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                allFluxes.add(Flux.empty());

                // 将流式结果存入上下文属性，供后续阶段使用
                context.setAttribute("__stream_full_content__", streamPlainTextRef.get());
                context.setAttribute("__stream_token_usage__", streamTokenUsageRef.get());
                context.setAttribute("__stream_consumed__", true);
            } else {
                // === 同步模式 ===
                handleSyncRound(context, adapter, messages, syncResult);
            }

            // 统一获取本轮工具调用列表
            List<ModelResponse.ToolCallRequest> calls;
            if (isStream) {
                calls = streamCallsRef.get();
            } else {
                ModelResponse response = syncResult.response;
                calls = (response != null && response.hasToolCalls()) ? response.getToolCalls() : List.of();
            }

            // 无工具调用则结束循环
            if (calls == null || calls.isEmpty()) {
                log.info("[ToolCallLoopStage] No tool calls, ending loop");
                break;
            }

            log.info("[ToolCallLoopStage] Detected {} tool call(s)", calls.size());

            // 执行工具调用并收集事件 Flux
            Flux<String> eventFlux = executeTools(context, adapter, messages, calls, isStream);
            if (eventFlux != null) {
                allFluxes.add(eventFlux);
            }

            round++;
        }

        // 合并所有 Flux 段为单个流
        if (isStream) {
            Flux<String> merged = allFluxes.isEmpty() ? Flux.empty()
                    : allFluxes.size() == 1 ? allFluxes.get(0)
                    : Flux.concat(allFluxes);  // 按顺序串联多个 Flux
            context.setAttribute("__stream_flux__", merged);
            log.info("[ToolCallLoopStage] Streaming: merged {} Flux segments", allFluxes.size());
        } else {
            log.info("[ToolCallLoopStage] Sync completed");
        }

        chain.next(context);
    }

    /**
     * 同步模式下调用 LLM 并保存响应。
     */
    private void handleSyncRound(ChatContext context, ModelAdapter adapter,
                                 List<Message> messages, ChatResultHolder syncResult) {
        ModelResponse response = adapter.chat(context.getRequest());

        log.info("[ToolCallLoopStage] Response: toolCall={}, contentLen={}",
                response.hasToolCalls(),
                response.getContent() != null ? response.getContent().length() : 0);

        // 将 assistant 消息追加到对话历史
        messages.add(Message.builder()
                .role("assistant")
                .content(response.getContent() != null ? response.getContent() : "")
                .model(adapter.getModel())
                .build());

        syncResult.response = response;
        syncResult.content = response.getContent();
    }

    /**
     * 执行工具调用列表。
     *
     * 流程：
     * 1. 将工具调用绑定到最新的 assistant 消息
     * 2. 逐个执行工具调用（远程优先，本地回退）
     * 3. 将工具执行结果以 tool 角色消息追加到对话历史
     * 4. 流式模式下构建工具事件 Flux 并推送到实时 Sink
     *
     * @return 流式模式下的工具事件 Flux，同步模式返回 null
     */
    private Flux<String> executeTools(ChatContext context, ModelAdapter adapter,
                                      List<Message> messages,
                                      List<ModelResponse.ToolCallRequest> calls,
                                      boolean isStream) {

        // 找到最后一条 assistant 消息并绑定工具调用信息
        Message lastAssistant = findLastAssistant(messages);
        if (lastAssistant != null && lastAssistant.getToolCalls() == null) {
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

        // 逐个执行工具调用
        for (ModelResponse.ToolCallRequest req : calls) {
            try {
                ToolCall toolCall = ToolCall.builder()
                        .toolCallId(req.getId())
                        .name(req.getName())
                        .arguments(req.getArguments())
                        .build();

                log.info("[ToolCallLoopStage] Executing tool: {} {} args={}",
                        toolCall.getName(), toolCall.getToolCallId(), toolCall.getArguments());

                // 优先通过 Feign 远程执行，失败回退到本地 ToolRegistry
                ToolResult result = executeToolViaFeignOrLocal(context, toolCall, req);

                // 将工具结果作为 tool 角色消息追加
                messages.add(Message.builder()
                        .role("tool")
                        .toolCallId(req.getId())
                        .content(result.isSuccess() ? result.getResult() : result.getError())
                        .build());

                log.info("[ToolCallLoopStage] Tool {} completed: {}",
                        toolCall.getName(), result.isSuccess() ? "success" : "failed");
            } catch (Exception e) {
                // 异常处理：根据 ToolCallPolicy 决定 ABORT/SKIP/RETRY
                ToolErrorAction action = toolCallPolicy != null
                        ? toolCallPolicy.handleToolError(null, e, context) : ToolErrorAction.SKIP;
                messages.add(Message.builder()
                        .role("tool")
                        .toolCallId(req.getId())
                        .content("Error: " + e.getMessage())
                        .build());
                log.error("[ToolCallLoopStage] Tool {} execution exception: {}", req.getName(), e.getMessage());

                if (action == ToolErrorAction.ABORT) {
                    context.setAttribute("error", e.getMessage());
                    break;  // 终止本轮所有工具执行
                }
            }
        }

        // 流式模式：构建工具事件并推送到实时通道
        if (isStream) {
            Flux<String> toolFlux = buildToolEventFlux(calls);
            @SuppressWarnings("unchecked")
            Sinks.Many<String> realtimeSink = (Sinks.Many<String>) context.getAttribute("__realtime_sink__");
            if (realtimeSink != null) {
                toolFlux.subscribe(
                    event -> realtimeSink.tryEmitNext(event),
                    error -> log.warn("Tool event push error", error),
                    () -> {}
                );
            }
            return toolFlux;
        }
        return null;
    }

    /**
     * 执行工具调用：优先通过 ActionFeignClient 远程调用，失败回退到本地 ToolRegistry。
     *
     * @param context  聊天上下文
     * @param toolCall 工具调用信息
     * @param req      原始工具调用请求
     * @return ToolResult 执行结果
     */
    private ToolResult executeToolViaFeignOrLocal(ChatContext context, ToolCall toolCall,
                                                   ModelResponse.ToolCallRequest req) {
        // 优先：通过 Feign 远程调用 action 微服务
        if (actionFeignClient != null) {
            try {
                java.util.Map<String, Object> argsMap = new java.util.HashMap<>();
                argsMap.put("arguments", toolCall.getArguments());
                ToolExecuteRequest feignReq = ToolExecuteRequest.builder()
                        .toolName(toolCall.getName())
                        .args(argsMap)
                        .sessionId(context.getRequest().getSessionId())
                        .build();
                lyjew.com.lyclaw.action.tool.ToolResult remoteResult =
                        actionFeignClient.executeTool(feignReq);
                log.debug("[ToolCallLoopStage] ActionFeignClient executed tool: {} success={}",
                        toolCall.getName(), remoteResult.isSuccess());
                if (remoteResult.isSuccess()) {
                    return ToolResult.success(
                            remoteResult.getOutput() != null ? remoteResult.getOutput() : "success");
                } else {
                    return ToolResult.failure(
                            remoteResult.getErrorMessage() != null ? remoteResult.getErrorMessage() : "remote tool failed");
                }
            } catch (Exception feignError) {
                // Feign 调用失败，回退到本地
                log.warn("[ToolCallLoopStage] ActionFeignClient failed, falling back to local ToolRegistry: {}",
                        feignError.getMessage());
            }
        }

        // 回退：本地 ToolRegistry 直接执行
        if (toolRegistry != null) {
            return toolRegistry.execute(toolCall, context);
        }
        return ToolResult.failure("No ToolRegistry or ActionFeignClient available");
    }

    /**
     * 构建工具调用的 SSE 事件 Flux。
     * 每个工具调用发送两个事件：executing 和 done。
     */
    private Flux<String> buildToolEventFlux(List<ModelResponse.ToolCallRequest> calls) {
        return Flux.create((Consumer<FluxSink<String>>) sink -> {
            // 第一遍：发送 executing 状态
            for (ModelResponse.ToolCallRequest req : calls) {
                String json = "{\"type\":\"tool_call\",\"name\":\""
                        + escapeJson(req.getName()) + "\",\"status\":\"executing\"}";
                sink.next("data: " + json + "\n\n");
            }
            // 第二遍：发送 done 状态
            for (ModelResponse.ToolCallRequest req : calls) {
                String json = "{\"type\":\"tool_call\",\"name\":\""
                        + escapeJson(req.getName()) + "\",\"status\":\"done\"}";
                sink.next("data: " + json + "\n\n");
            }
            sink.complete();
        });
    }

    /**
     * 从消息列表末尾查找最后一条 assistant 角色的消息。
     */
    private Message findLastAssistant(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("assistant".equals(messages.get(i).getRole())) {
                return messages.get(i);
            }
        }
        return null;
    }

    /**
     * JSON 字符串转义。
     */
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

    /** 同步模式下的 LLM 响应持有者 */
    private static class ChatResultHolder {
        ModelResponse response;
        String content;
    }
}
