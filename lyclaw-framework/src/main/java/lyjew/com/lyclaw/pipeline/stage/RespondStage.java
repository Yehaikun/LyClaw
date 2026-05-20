package lyjew.com.lyclaw.pipeline.stage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.chat.ChatModel;
import lyjew.com.lyclaw.chat.RoutingDecision;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.react.ToolExecutor;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.annotation.PipelineStage;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * 响应生成阶段，order=3。内嵌 ReAct 循环，LLM 推理 + 工具调用。
 */
@Slf4j
@PipelineStage(name = "Respond", after = PlanExecutionStage.class, group = "POSTPROCESSING")
public class RespondStage extends PipelineStageBase {

    private final ChatFacade chatFacade;
    private final ToolRegistry toolRegistry;
    private final ReActEngine reActEngine;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public RespondStage(@org.springframework.lang.Nullable ChatFacade chatFacade,
                        ToolRegistry toolRegistry,
                        @org.springframework.lang.Nullable ReActEngine reActEngine) {
        this.chatFacade = chatFacade;
        this.toolRegistry = toolRegistry;
        this.reActEngine = reActEngine;
    }

    @Override
    public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
        if (ctx.isTerminated() || !ctx.isPipelineOk()) {
            return Flux.empty();
        }

        return Flux.defer(() -> {
            String traceId = ctx.getTracing().getTraceId();
            int sc = ctx.getSuccessCount().get();
            int fc = ctx.getFailCount().get();
            List<String> toolResults = ctx.getToolResults();

            log.info("\n\n========== [阶段 3/5] 响应生成 - ReAct循环 [RESPOND] ==========");
            log.info(logJson("INFO", "stage_start", "RESPOND", traceId,
                    "Starting response generation", null));
            ctx.getCurrentStage().set("RESPOND");
            ctx.getTracing().beginStage("RESPOND");

            List<ToolDefinition> toolDefs;
            try {
                toolDefs = toolRegistry.getAllDefinitions();
                log.info(logJson("INFO", "tools_fetched", "RESPOND", traceId,
                        toolDefs.size() + " tools available", null));
            } catch (Exception e) {
                log.error("获取工具列表失败: {}", e.getMessage(), e);
                toolDefs = Collections.emptyList();
            }

            Flux<ServerSentEvent<String>> bodyFlux;
            if (chatFacade != null && reActEngine != null && !toolDefs.isEmpty()) {
                bodyFlux = reactWithReActEngine(ctx, traceId, toolDefs);
            } else if (chatFacade != null) {
                bodyFlux = simpleChatStream(ctx, traceId);
            } else {
                String fallback = buildFallbackResponse(sc, fc, toolResults);
                bodyFlux = Flux.just(sseEvent("message", fallback));
            }

            return Flux.just(sseEvent("respond_start", "Generating AI response"))
                    .concatWith(bodyFlux)
                    .onErrorResume(err -> {
                        log.error(logJson("ERROR", "stage_error", "RESPOND", traceId,
                                "Response generation failed: " + err.getMessage(), null), err);
                        String fallback = buildFallbackResponse(sc, fc, toolResults);
                        return Flux.just(
                                sseEvent("message", fallback),
                                sseEvent("done", Map.of("status", "completed", "fallback", true))
                        );
                    });
        });
    }

    private Flux<ServerSentEvent<String>> reactWithReActEngine(AgentContext ctx, String traceId,
                                                                List<ToolDefinition> toolDefs) {
        lyjew.com.lyclaw.model.ChatRequest req = ctx.getChatRequest();
        if (req == null) {
            req = lyjew.com.lyclaw.model.ChatRequest.builder()
                    .messages(new ArrayList<>(List.of(lyjew.com.lyclaw.model.Message.user(ctx.getUserMessage()))))
                    .stream(true)
                    .build();
            ctx.setChatRequest(req);
        }
        final lyjew.com.lyclaw.model.ChatRequest request = req;
        request.setTools(toolDefs);
        request.setToolChoice("auto");
        request.setStream(true);

        log.info(logJson("INFO", "llm_stream_detect", "RESPOND", traceId,
                "Streaming with tool detection via ReActEngine", null));

        ToolExecutor toolExecutor = (toolName, toolCallId, arguments) -> {
            Map<String, Object> args;
            try {
                if (arguments != null && !arguments.isEmpty()) {
                    args = objectMapper.readValue(arguments, new TypeReference<Map<String, Object>>() {});
                } else {
                    args = Collections.emptyMap();
                }
            } catch (Exception e) {
                log.error("工具参数JSON解析失败: tool={} error={}", toolName, e.getMessage(), e);
                args = Collections.emptyMap();
            }

            SandboxLevel level = ctx.getSandboxLevel() != null ? ctx.getSandboxLevel() : SandboxLevel.DIRECT;

            try {
                ToolCall toolCall = ToolCall.builder()
                        .toolCallId(toolCallId).name(toolName).arguments(arguments).build();
                ToolExecutionResult result = ctx.getToolRegistry().execute(toolCall, null);
                if (!result.isSuccess()) {
                    result = ctx.getToolRegistry().executeByName(toolName, toolCallId, arguments, request);
                }
                ctx.addToolResult(result.isSuccess() ? result.getResult() : result.getError());
                if (result.isSuccess()) {
                    ctx.getSuccessCount().incrementAndGet();
                } else {
                    ctx.getFailCount().incrementAndGet();
                }
                log.info(logJson("INFO", "tool_executed", "RESPOND", traceId,
                        "tool=" + toolName + " success=" + result.isSuccess(), null));
                return result.isSuccess() ? result.getResult() : "Error: " + result.getError();
            } catch (Exception e) {
                log.error("工具执行失败: tool={} error={}", toolName, e.getMessage(), e);
                ctx.getFailCount().incrementAndGet();
                return "Tool error: " + e.getMessage();
            }
        };

        Set<String> approvalTools = toolDefs.stream()
                .filter(def -> !def.isReadOnly())
                .map(ToolDefinition::getName)
                .collect(java.util.stream.Collectors.toSet());
        reActEngine.setApprovalRequired(approvalTools);

        return reActEngine.executeStream(chatFacade, request, toolExecutor);
    }

    private Flux<ServerSentEvent<String>> simpleChatStream(AgentContext ctx, String traceId) {
        return Flux.defer(() -> {
            lyjew.com.lyclaw.model.ChatRequest request = ctx.getChatRequest();
            if (request == null) {
                request = lyjew.com.lyclaw.model.ChatRequest.builder()
                        .messages(new ArrayList<>(List.of(lyjew.com.lyclaw.model.Message.user(ctx.getUserMessage()))))
                        .stream(true)
                        .build();
                ctx.setChatRequest(request);
            }
            request.setTools(null);
            request.setToolChoice(null);

            RoutingDecision decision = chatFacade.route(request, null);
            ChatModel model = chatFacade.resolveModel(decision);
            log.info(logJson("INFO", "llm_stream", "RESPOND", traceId,
                    "Streaming via " + decision.provider() + ":" + decision.model(), null));

            return model.stream(request)
                    .handle((response, sink) -> {
                        // Phase 2: emit thinking events for reasoning_content
                        String thinking = response.getThinking();
                        if (thinking != null && !thinking.isEmpty()) {
                            sink.next(sseEvent("thinking", thinking));
                        }
                        String text = response.getContent() != null ? response.getContent() : "";
                        if (!text.isEmpty()) {
                            sink.next(sseEvent("message", text));
                        }
                    });
        });
    }

    private String buildFallbackResponse(int successCount, int failCount, List<String> toolResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("Orchestration completed.\n");
        sb.append("Tasks executed: ").append(successCount + failCount)
                .append(" (success: ").append(successCount)
                .append(", failed: ").append(failCount).append(")\n");
        if (!toolResults.isEmpty()) {
            sb.append("\nResults summary:\n");
            for (int i = 0; i < Math.min(toolResults.size(), 5); i++) {
                String result = toolResults.get(i);
                sb.append("  [").append(i + 1).append("] ")
                        .append(result.length() > 200 ? result.substring(0, 200) + "..." : result)
                        .append("\n");
            }
        }
        return sb.toString();
    }

    @Override
    public int getOrder() { return 3; }

    @Override
    public String getStageName() { return "Respond"; }
}
