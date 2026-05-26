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
import lyjew.com.lyclaw.react.HookRegistry;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.react.sse.SseEventFactory;
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
@PipelineStage(name = "Respond", after = SecurityCheckStage.class, group = "POSTPROCESSING")
public class RespondStage extends PipelineStageBase {

    private final ChatFacade chatFacade;
    private final ToolRegistry toolRegistry;
    private final ReActEngine reActEngine;
    private final HookRegistry hookRegistry;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public RespondStage(@org.springframework.lang.Nullable ChatFacade chatFacade,
                        ToolRegistry toolRegistry,
                        @org.springframework.lang.Nullable ReActEngine reActEngine,
                        @org.springframework.lang.Nullable HookRegistry hookRegistry) {
        this.chatFacade = chatFacade;
        this.toolRegistry = toolRegistry;
        this.reActEngine = reActEngine;
        this.hookRegistry = hookRegistry;
    }

    @Override
    public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
        if (ctx.isTerminated()) {
            log.info("[响应生成] 管线已终止，跳过");
            return Flux.empty();
        }
        if (!ctx.isPipelineOk()) {
            log.warn("[响应生成] 管线状态异常(pipelineOk=false)，跳过");
            return Flux.empty();
        }

        // 分发 beforeRequest 钩子
        if (hookRegistry != null) {
            hookRegistry.dispatchBeforeRequest(ctx);
        }

        return Flux.defer(() -> {
            String traceId = ctx.getTracing().getTraceId();
            int sc = ctx.getSuccessCount().get();
            int fc = ctx.getFailCount().get();
            List<String> toolResults = ctx.getToolResults();

            log.info("\n\n========== [阶段 2] 响应生成 - ReAct循环 [RESPOND] ==========");
            log.info(logJson("INFO", "stage_start", "RESPOND", traceId,
                    "开始响应生成", null));
            log.info("[响应生成] 开始 | sessionId={} | 成功任务={} | 失败任务={}", ctx.getSessionId(), sc, fc);
            ctx.getCurrentStage().set("RESPOND");
            ctx.getTracing().beginStage("RESPOND");

            List<ToolDefinition> toolDefs;
            try {
                lyjew.com.lyclaw.model.ChatRequest req = ctx.getChatRequest();
                if (req != null) {
                    toolDefs = toolRegistry.getAllDefinitions(req, Map.of("agentContext", ctx));
                } else {
                    toolDefs = toolRegistry.getAllDefinitions();
                }
                log.info(logJson("INFO", "tools_fetched", "RESPOND", traceId,
                        "获取到 " + toolDefs.size() + " 个工具", null));
                log.info("[响应生成] 工具列表获取完成 | 可用工具数={}", toolDefs.size());
            } catch (Exception e) {
                log.error("[FAIL] [响应生成] 获取工具列表失败: {}", e.getMessage(), e);
                toolDefs = Collections.emptyList();
            }

            Flux<ServerSentEvent<String>> bodyFlux;
            if (chatFacade != null && reActEngine != null && !toolDefs.isEmpty()) {
                log.info("[响应生成] 使用ReAct引擎 + 工具调用模式");
                bodyFlux = reactWithReActEngine(ctx, traceId, toolDefs);
            } else if (chatFacade != null) {
                log.info("[响应生成] 使用简单流式聊天模式（无工具）");
                bodyFlux = simpleChatStream(ctx, traceId);
            } else {
                log.warn("[WARN] [响应生成] 无ChatFacade可用，使用回退响应");
                String fallback = buildFallbackResponse(sc, fc, toolResults);
                bodyFlux = Flux.just(sseEvent("message", fallback));
            }

            Flux<ServerSentEvent<String>> resultFlux = Flux.just(sseEvent("respond_start", "正在生成AI响应"))
                    .concatWith(bodyFlux)
                    .doFinally(signalType -> {
                        if (hookRegistry != null) {
                            hookRegistry.dispatchAgentEnd(ctx);
                        }
                    })
                    .onErrorResume(err -> {
                        log.error(logJson("ERROR", "stage_error", "RESPOND", traceId,
                                "响应生成失败: " + err.getMessage(), null), err);
                        log.error("[FAIL] [响应生成] 阶段异常 | error={}", err.getMessage());
                        String fallback = buildFallbackResponse(sc, fc, toolResults);
                        return Flux.just(
                                sseEvent("message", fallback),
                                SseEventFactory.done("completed", null, true)
                        );
                    });
            return resultFlux;
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
                "通过ReActEngine进行流式+工具检测", null));
        log.info("[响应生成] 启动ReAct引擎流式调用 | 工具数={} | 审批工具数={}",
                toolDefs.size(), toolDefs.stream().filter(def -> !def.isReadOnly()).count());

        ToolExecutor toolExecutor = (toolName, toolCallId, arguments) -> {
            // 分发 beforeToolCall 钩子
            if (hookRegistry != null) {
                hookRegistry.dispatchBeforeToolCall(toolName, toolCallId, arguments, ctx);
            }

            Map<String, Object> args;
            try {
                if (arguments != null && !arguments.isEmpty()) {
                    args = objectMapper.readValue(arguments, new TypeReference<Map<String, Object>>() {});
                } else {
                    args = Collections.emptyMap();
                }
            } catch (Exception e) {
                log.error("[FAIL] [响应生成] 工具参数JSON解析失败: tool={} error={}", toolName, e.getMessage(), e);
                args = Collections.emptyMap();
            }

            SandboxLevel level = ctx.getSandboxLevel() != null ? ctx.getSandboxLevel() : SandboxLevel.DIRECT;
            log.debug("[响应生成] 执行工具: {} | toolCallId={} | 沙箱级别={}", toolName, toolCallId, level);

            String resultStr;
            try {
                ToolCall toolCall = ToolCall.builder()
                        .toolCallId(toolCallId).name(toolName).arguments(arguments).build();
                ToolExecutionResult result = ctx.getToolRegistry().execute(toolCall, null);
                if (!result.isSuccess()) {
                    result = ctx.getToolRegistry().executeByName(toolName, toolCallId, arguments, request,
                            Map.of("agentContext", ctx));
                }
                ctx.addToolResult(result.isSuccess() ? result.getResult() : result.getError());
                if (result.isSuccess()) {
                    ctx.getSuccessCount().incrementAndGet();
                } else {
                    ctx.getFailCount().incrementAndGet();
                }
                log.info(logJson("INFO", "tool_executed", "RESPOND", traceId,
                        "工具=" + toolName + " 成功=" + result.isSuccess(), null));
                log.info("{} [响应生成] 工具执行完成: {} | 成功={} | 累计成功={} 失败={}",
                        result.isSuccess() ? "[OK]" : "[FAIL]", toolName, result.isSuccess(),
                        ctx.getSuccessCount().get(), ctx.getFailCount().get());
                resultStr = result.isSuccess() ? result.getResult() : "Error: " + result.getError();
            } catch (Exception e) {
                log.error("[FAIL] [响应生成] 工具执行异常: tool={} error={}", toolName, e.getMessage(), e);
                ctx.getFailCount().incrementAndGet();
                resultStr = "Tool error: " + e.getMessage();
            }

            // 分发 afterToolCall 钩子
            if (hookRegistry != null) {
                hookRegistry.dispatchAfterToolCall(toolName, toolCallId, resultStr, ctx);
            }
            return resultStr;
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
                    "流式调用 " + decision.provider() + ":" + decision.model(), null));
            log.info("[响应生成] 路由决策 | provider={} | model={}", decision.provider(), decision.model());

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
        if (successCount == 0 && failCount > 0) {
            sb.append("工具执行失败，请重试。\n");
        } else if (successCount > 0) {
            sb.append("Orchestration completed.\n");
            sb.append("Tasks executed: ").append(successCount + failCount)
                    .append(" (success: ").append(successCount)
                    .append(", failed: ").append(failCount).append(")\n");
        }
        if (!toolResults.isEmpty()) {
            for (int i = 0; i < Math.min(toolResults.size(), 5); i++) {
                String result = toolResults.get(i);
                if (result != null && !result.isEmpty() && !result.startsWith("Error:")) continue;
                sb.append("  [" + (i + 1) + "] " + (result != null ? result : "empty") + "\n");
            }
        }
        return sb.toString().trim().isEmpty() ? "（无响应）" : sb.toString();
    }

    @Override
    public int getOrder() { return 3; }

    @Override
    public String getStageName() { return "Respond"; }
}
