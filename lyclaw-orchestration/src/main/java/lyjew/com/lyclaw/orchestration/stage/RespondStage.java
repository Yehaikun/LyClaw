package lyjew.com.lyclaw.orchestration.stage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.action.ToolExecuteRequest;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.chat.ChatModel;
import lyjew.com.lyclaw.chat.RoutingDecision;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.feign.ActionFeignClient;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.pipeline.PipelineContext;
import lyjew.com.lyclaw.autoconfigure.processor.InteractionModeProcessor;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.react.ToolExecutor;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import org.springframework.http.codec.ServerSentEvent;
import lyjew.com.lyclaw.annotation.PipelineStage;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 响应生成阶段 — 集成 ReAct 工具调用循环。
 *
 * <p>流式优先：先以 stream=true 调用 LLM，在流中检测 tool_calls。
 * 若无 tool_calls 则直接透传 chunk 给前端（真流式）；
 * 若检测到 tool_calls 则收集碎片合并后重启非流式 ReAct 循环。</p>
 */
@Slf4j
@PipelineStage(name = "Respond", after = ReflectionStage.class, group = "POSTPROCESSING")
public class RespondStage extends PipelineStageBase {

    private final ChatFacade chatFacade;
    private final ActionFeignClient actionFeignClient;
    private final InteractionModeProcessor interactionModeProcessor;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造响应生成阶段实例。
     *
     * <p>通过 Spring 依赖注入接收 ChatFacade、ActionFeignClient 和 InteractionModeProcessor。
     * ChatFacade 负责 LLM 模型的路由决策和调用，ActionFeignClient 负责获取工具列表，
     * InteractionModeProcessor 负责按名称解析交互模式引擎（由 @InteractionMode 注解驱动）。
     * ChatFacade 和 InteractionModeProcessor 均为 Nullable，无 LLM 能力时退化为降级响应。</p>
     *
     * @param chatFacade               聊天门面，可为 null
     * @param actionFeignClient        动作服务 Feign 客户端
     * @param interactionModeProcessor 交互模式处理器，可为 null
     */
    public RespondStage(@org.springframework.lang.Nullable ChatFacade chatFacade,
                        ActionFeignClient actionFeignClient,
                        @org.springframework.lang.Nullable InteractionModeProcessor interactionModeProcessor) {
        this.chatFacade = chatFacade;
        this.actionFeignClient = actionFeignClient;
        this.interactionModeProcessor = interactionModeProcessor;
    }

    @Override
    public Flux<ServerSentEvent<String>> execute(ChatContext context, PipelineContext pipelineCtx) {
        if (pipelineCtx.isTerminated() || !pipelineCtx.isPipelineOk()) {
            return Flux.empty();
        }

        return Flux.defer(() -> {
            String traceId = context.getTracing().getTraceId();
            int sc = pipelineCtx.getSuccessCount().get();
            int fc = pipelineCtx.getFailCount().get();
            ReflectionReport report = pipelineCtx.getReportRef().get();
            List<String> toolResults = pipelineCtx.getToolResults();

            log.info("\n\n══════════════════════════════════");
            log.info("  [阶段 4/6] 响应生成 - ReAct循环(LLM推理+工具调用) [RESPOND]");
            log.info("══════════════════════════════════");
            log.info(logJson("INFO", "stage_start", "RESPOND", traceId,
                    "Starting response generation", null));
            pipelineCtx.getCurrentStage().set("RESPOND");
            context.getTracing().beginStage("RESPOND");

            List<ToolDefinition> toolDefs;
            try {
                toolDefs = actionFeignClient.listTools();
                log.info(logJson("INFO", "tools_fetched", "RESPOND", traceId,
                        toolDefs.size() + " tools from action service: " +
                                toolDefs.stream().map(ToolDefinition::getName).toList(), null));
            } catch (Exception e) {
                log.error("获取工具列表失败，将无工具可用: {}", e.getMessage(), e);
                toolDefs = Collections.emptyList();
            }

            // 从 InteractionModeProcessor 获取默认引擎（由 @InteractionMode 注解驱动发现）
            ReActEngine reActEngine = interactionModeProcessor != null
                    ? interactionModeProcessor.getDefault() : null;

            Flux<ServerSentEvent<String>> bodyFlux;
            if (chatFacade != null && reActEngine != null && !toolDefs.isEmpty()) {
                bodyFlux = reactWithReActEngine(reActEngine, context, traceId, toolDefs);
            } else if (chatFacade != null) {
                bodyFlux = simpleChatStream(context, traceId);
            } else {
                String fallback = buildFinalResponse(sc, fc, toolResults, report);
                bodyFlux = Flux.just(sseEvent("message", fallback));
            }

            return Flux.just(sseEvent("respond_start", "Generating AI response"))
                    .concatWith(bodyFlux)
                    .onErrorResume(err -> {
                        log.error(logJson("ERROR", "stage_error", "RESPOND", traceId,
                                "Response generation failed: " + err.getMessage(), null), err);
                        String fallback = buildFinalResponse(sc, fc, toolResults, report);
                        return Flux.just(
                                sseEvent("message", fallback),
                                sseEvent("done", "{\"status\":\"completed\",\"fallback\":true}")
                        );
                    });
        });
    }

    /**
     * 通过 ReActEngine 执行带工具的流式 ReAct 循环。
     *
     * <p>构建 ToolExecutor 桥接 ActionFeignClient，将工具执行委托给远程 action 服务。
     * 设置 tools/toolChoice/stream 后委托给 ReActEngine 处理流式检测和多轮循环。</p>
     */
    private Flux<ServerSentEvent<String>> reactWithReActEngine(ReActEngine reActEngine,
                                                                ChatContext context, String traceId,
                                                                List<ToolDefinition> toolDefs) {
        lyjew.com.lyclaw.model.ChatRequest request = context.getRequest();
        request.setTools(toolDefs);
        request.setToolChoice("auto");
        request.setStream(true);

        log.info(logJson("INFO", "llm_stream_detect", "RESPOND", traceId,
                "Streaming with tool detection via ReActEngine", null));

        // 构建工具执行器，桥接 ActionFeignClient
        ToolExecutor toolExecutor = (toolName, toolCallId, arguments) -> {
            Map<String, Object> args;
            try {
                if (arguments != null && !arguments.isEmpty()) {
                    args = objectMapper.readValue(arguments,
                            new TypeReference<Map<String, Object>>() {});
                } else {
                    args = Collections.emptyMap();
                }
            } catch (Exception e) {
                log.error("工具参数JSON解析失败: tool={} args={} error={}",
                        toolName, arguments, e.getMessage(), e);
                args = Collections.emptyMap();
            }

            ToolExecuteRequest execReq = ToolExecuteRequest.builder()
                    .toolName(toolName)
                    .args(args)
                    .sessionId(request.getSessionId())
                    .build();

            try {
                ToolExecutionResult result = actionFeignClient.executeTool(execReq);
                log.info(logJson("INFO", "tool_executed", "RESPOND", traceId,
                        "tool=" + toolName + " success=" + result.isSuccess(), null));
                return result.isSuccess() ? result.getResult() : "Error: " + result.getError();
            } catch (Exception e) {
                log.error("工具Feign调用失败: tool={} error={}", toolName, e.getMessage(), e);
                return "Tool error: " + e.getMessage();
            }
        };

        return reActEngine.executeStream(chatFacade, request, toolExecutor);
    }

    /** 简单聊天 — 无工具，真流式逐 token 推送 */
    private Flux<ServerSentEvent<String>> simpleChatStream(ChatContext context, String traceId) {
        return Flux.defer(() -> {
            context.getRequest().setTools(null);
            context.getRequest().setToolChoice(null);

            RoutingDecision decision = chatFacade.route(context.getRequest(), null);
            ChatModel model = chatFacade.resolveModel(decision);
            log.info(logJson("INFO", "llm_stream", "RESPOND", traceId,
                    "Streaming via " + decision.provider() + ":" + decision.model(), null));

            return model.stream(context.getRequest())
                    .handle((response, sink) -> {
                        String text = response.getContent() != null ? response.getContent() : "";
                        if (!text.isEmpty()) {
                            sink.next(sseEvent("message", text));
                        }
                    });
        });
    }

    /**
     * 返回本阶段在管线中的执行顺序编号。
     *
     * <p>返回值为 4，表示 RespondStage 是编排管线中的第五个阶段，
     * 排在 ContextBuildStage(0)、SecurityCheckStage(1)、PlanExecutionStage(2)
     * 和 ReflectionStage(3) 之后。作为管线中最复杂的阶段，它内嵌了完整的
     * ReAct（推理-行动）循环逻辑，负责 LLM 流式响应生成和工具调用的编排。
     * 只有在 ReflectionStage 将 pipelineOk 设为 true 后，本阶段才会执行。</p>
     *
     * @return 阶段顺序编号，固定为 4
     */
    @Override
    public int getOrder() { return 4; }

    /**
     * 返回本阶段的名称标识。
     *
     * <p>返回固定字符串 "Respond"，作为本阶段在编排管线中的唯一标识符。
     * 该名称用于 PipelineStage 注解中的 name 属性和 after 依赖声明（如
     * MetricsStage 声明 after = RespondStage.class），日志输出中的 "RESPOND"
     * 阶段标签，Tracing 追踪中的 span 名称，以及前端 SSE 事件中 respond_start、
     * message、done 等事件的来源标注。</p>
     *
     * @return 阶段名称，固定为 "Respond"
     */
    @Override
    public String getStageName() { return "Respond"; }
}
