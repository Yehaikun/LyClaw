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

@Slf4j
@Component
public class ToolCallLoopStage implements PipelineStage {

    private static final int MAX_ROUNDS = 6;
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

    @Override
    public void process(ChatContext context, Chain chain) {
        ModelAdapter adapter = modelProvider.getConfiguredAdapter();
        List<Message> messages = context.getRequest().getMessages();
        boolean isStream = context.getRequest().isStream();

        log.info("[ToolCallLoopStage] Entry, mode={}", isStream ? "streaming" : "sync");

        List<Flux<String>> allFluxes = new ArrayList<>(4);
        ChatResultHolder syncResult = new ChatResultHolder();

        int round = 0;
        while (round < MAX_ROUNDS) {
            log.info("[ToolCallLoopStage] Round {} {}", round + 1, isStream ? "(streaming)" : "(sync)");
            context.setAttribute("__round__", round);

            AtomicReference<List<ModelResponse.ToolCallRequest>> streamCallsRef = new AtomicReference<>();
            AtomicReference<String> streamPlainTextRef = new AtomicReference<>("");
            AtomicReference<String> streamTokenUsageRef = new AtomicReference<>("prompt=0 completion=0 total=0");

            if (isStream) {
                StringBuilder collector = new StringBuilder();

                @SuppressWarnings("unchecked")
                Sinks.Many<String> existingSink = (Sinks.Many<String>) context.getAttribute("__realtime_sink__");
                final Sinks.Many<String> realtimeSink = existingSink != null ? existingSink : Sinks.many().replay().all();
                Flux<String> realtimeFlux = realtimeSink.asFlux();
                context.setAttribute("__realtime_flux__", realtimeFlux);

                Flux<String> rawFlux = adapter.chatStream(context.getRequest());
                CountDownLatch doneLatch = new CountDownLatch(1);

                rawFlux.subscribe(
                    chunk -> {
                        collector.append(chunk).append('\n');
                        realtimeSink.tryEmitNext(chunk);
                    },
                    error -> {
                        log.error("[ToolCallLoopStage] Stream error: {}", error.getMessage());
                        realtimeSink.tryEmitComplete();
                        doneLatch.countDown();
                    },
                    () -> {
                        realtimeSink.tryEmitComplete();
                        String fullSSE = collector.toString();
                        log.info("[ToolCallLoopStage] Stream complete, collected {} bytes", fullSSE.length());

                        List<ModelResponse.ToolCallRequest> calls = adapter.extractSseToolCalls(fullSSE);
                        streamCallsRef.set(calls);
                        streamPlainTextRef.set(adapter.extractSsePlainText(fullSSE));
                        streamTokenUsageRef.set(adapter.extractSseTokenUsage(fullSSE));

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

                try {
                    if (!doneLatch.await(STREAM_COMPLETION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        log.error("[ToolCallLoopStage] Stream timeout {}ms", STREAM_COMPLETION_TIMEOUT_MS);
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                allFluxes.add(Flux.empty());

                context.setAttribute("__stream_full_content__", streamPlainTextRef.get());
                context.setAttribute("__stream_token_usage__", streamTokenUsageRef.get());
                context.setAttribute("__stream_consumed__", true);
            } else {
                handleSyncRound(context, adapter, messages, syncResult);
            }

            List<ModelResponse.ToolCallRequest> calls;
            if (isStream) {
                calls = streamCallsRef.get();
            } else {
                ModelResponse response = syncResult.response;
                calls = (response != null && response.hasToolCalls()) ? response.getToolCalls() : List.of();
            }

            if (calls == null || calls.isEmpty()) {
                log.info("[ToolCallLoopStage] No tool calls, ending loop");
                break;
            }

            log.info("[ToolCallLoopStage] Detected {} tool call(s)", calls.size());

            Flux<String> eventFlux = executeTools(context, adapter, messages, calls, isStream);
            if (eventFlux != null) {
                allFluxes.add(eventFlux);
            }

            round++;
        }

        if (isStream) {
            Flux<String> merged = allFluxes.isEmpty() ? Flux.empty()
                    : allFluxes.size() == 1 ? allFluxes.get(0)
                    : Flux.concat(allFluxes);
            context.setAttribute("__stream_flux__", merged);
            log.info("[ToolCallLoopStage] Streaming: merged {} Flux segments", allFluxes.size());
        } else {
            log.info("[ToolCallLoopStage] Sync completed");
        }

        chain.next(context);
    }

    private void handleSyncRound(ChatContext context, ModelAdapter adapter,
                                 List<Message> messages, ChatResultHolder syncResult) {
        ModelResponse response = adapter.chat(context.getRequest());

        log.info("[ToolCallLoopStage] Response: toolCall={}, contentLen={}",
                response.hasToolCalls(),
                response.getContent() != null ? response.getContent().length() : 0);

        messages.add(Message.builder()
                .role("assistant")
                .content(response.getContent() != null ? response.getContent() : "")
                .model(adapter.getModel())
                .build());

        syncResult.response = response;
        syncResult.content = response.getContent();
    }

    private Flux<String> executeTools(ChatContext context, ModelAdapter adapter,
                                      List<Message> messages,
                                      List<ModelResponse.ToolCallRequest> calls,
                                      boolean isStream) {

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

        for (ModelResponse.ToolCallRequest req : calls) {
            try {
                ToolCall toolCall = ToolCall.builder()
                        .toolCallId(req.getId())
                        .name(req.getName())
                        .arguments(req.getArguments())
                        .build();

                log.info("[ToolCallLoopStage] Executing tool: {} {} args={}",
                        toolCall.getName(), toolCall.getToolCallId(), toolCall.getArguments());

                ToolResult result = executeToolViaFeignOrLocal(context, toolCall, req);

                messages.add(Message.builder()
                        .role("tool")
                        .toolCallId(req.getId())
                        .content(result.isSuccess() ? result.getResult() : result.getError())
                        .build());

                log.info("[ToolCallLoopStage] Tool {} completed: {}",
                        toolCall.getName(), result.isSuccess() ? "success" : "failed");
            } catch (Exception e) {
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
                    break;
                }
            }
        }

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

    private ToolResult executeToolViaFeignOrLocal(ChatContext context, ToolCall toolCall,
                                                   ModelResponse.ToolCallRequest req) {
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
                log.warn("[ToolCallLoopStage] ActionFeignClient failed, falling back to local ToolRegistry: {}",
                        feignError.getMessage());
            }
        }

        if (toolRegistry != null) {
            return toolRegistry.execute(toolCall, context);
        }
        return ToolResult.failure("No ToolRegistry or ActionFeignClient available");
    }

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

    private static class ChatResultHolder {
        ModelResponse response;
        String content;
    }
}
