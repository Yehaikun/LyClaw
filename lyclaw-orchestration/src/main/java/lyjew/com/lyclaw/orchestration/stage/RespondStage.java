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
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.pipeline.PipelineContext;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import org.springframework.http.codec.ServerSentEvent;
import lyjew.com.lyclaw.annotation.PipelineStage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
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

    private static final int MAX_TOOL_ROUNDS = 10;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造响应生成阶段实例。
     *
     * <p>通过 Spring 依赖注入接收 ChatFacade（聊天门面）和 ActionFeignClient（动作服务客户端）。
     * ChatFacade 负责 LLM 模型的路由决策和调用，ActionFeignClient 负责获取可用工具定义列表
     * 并执行具体的工具调用。这两个依赖共同支撑 ReAct 循环的完整运行：ChatFacade 提供 LLM 推理能力，
     * ActionFeignClient 通过 listTools() 提供工具清单供 LLM 自主选择，通过 executeTool() 执行
     * LLM 决定的工具调用并将结果返回给 LLM 继续推理。</p>
     *
     * @param chatFacade         聊天门面，封装模型路由和 LLM 调用逻辑
     * @param actionFeignClient  动作服务 Feign 客户端，用于获取工具列表和执行工具调用
     */
    public RespondStage(ChatFacade chatFacade, ActionFeignClient actionFeignClient) {
        this.chatFacade = chatFacade;
        this.actionFeignClient = actionFeignClient;
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

            Flux<ServerSentEvent<String>> bodyFlux;
            if (chatFacade != null && !toolDefs.isEmpty()) {
                bodyFlux = streamWithToolDetection(context, traceId, toolDefs);
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
     * 流式 + 工具检测：始终 stream=true，实时分析每个 chunk。
     *
     * <p>DeepSeek 在工具场景下先输出 reasoning_content（思考），
     * 然后要么输出 content（不调工具）要么输出 tool_calls（调工具），
     * 两者互斥。利用这一特性在流中实时判断：遇 content 即透传，
     * 遇 tool_calls 则收齐碎片合并后重启非流式 ReAct。</p>
     */
    private Flux<ServerSentEvent<String>> streamWithToolDetection(ChatContext context,
                                                                   String traceId,
                                                                   List<ToolDefinition> toolDefs) {
        context.getRequest().setTools(toolDefs);
        context.getRequest().setToolChoice("auto");
        context.getRequest().setStream(true);

        ChatModel model = chatFacade.resolveModel(chatFacade.route(context.getRequest(), null));
        log.info(logJson("INFO", "llm_stream_detect", "RESPOND", traceId,
                "Streaming with tool detection via " + model.provider() + ":" + model.model(), null));

        // 0=buffering(思考阶段), 1=relaying(纯文本流式), 2=tools_detected(等待重启)
        int[] state = {0};
        List<ModelResponse> buffer = new ArrayList<>();

        return model.stream(context.getRequest())
                .<ServerSentEvent<String>>handle((chunk, sink) -> {
                    boolean hasContent = chunk.getContent() != null && !chunk.getContent().isEmpty();
                    boolean hasToolCalls = chunk.getToolCalls() != null && !chunk.getToolCalls().isEmpty();

                    if (state[0] == 2) {
                        buffer.add(chunk);
                        return;
                    }

                    if (state[0] == 1) {
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

                    // state[0] == 0: buffering thinking
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
                    buffer.add(chunk); // thinking or empty
                })
                .concatWith(Flux.<ServerSentEvent<String>>defer(() -> {
                    if (state[0] == 2) {
                        return Mono.fromCallable(() -> {
                            context.getRequest().setStream(false);
                            ModelResponse merged = model.mergeChunks(buffer);
                            log.info(logJson("INFO", "tool_detected_in_stream", "RESPOND", traceId,
                                    "Tools detected in stream, restarting ReAct. tools=" +
                                            (merged.getToolCalls() != null
                                                    ? merged.getToolCalls().stream()
                                                            .map(ModelResponse.ToolCallRequest::getName).toList()
                                                    : "[]"),
                                    null));
                            return runReActLoop(context, traceId, merged);
                        }).subscribeOn(Schedulers.boundedElastic())
                          .flatMapMany(result -> splitIntoEvents(result));
                    }
                    if (state[0] == 0) {
                        ModelResponse merged = model.mergeChunks(buffer);
                        String content = merged.getContent() != null ? merged.getContent() : "";
                        if (!content.isEmpty()) {
                            return Flux.<ServerSentEvent<String>>just(sseEvent("message", content));
                        }
                    }
                    return Flux.empty();
                }));
    }

    /** ReAct 循环，从已获取的首轮响应（可能来自流式检测）开始 */
    private String runReActLoop(ChatContext context, String traceId, ModelResponse firstResponse) {
        List<Message> messages = context.getRequest().getMessages();

        // 首轮：处理已检测到的 tool_calls
        if (firstResponse.hasToolCalls()) {
            List<ToolCall> toolCalls = convertToolCalls(firstResponse);
            messages.add(Message.builder()
                    .role("assistant")
                    .content(firstResponse.getContent() != null ? firstResponse.getContent() : "")
                    .thinking(firstResponse.getThinking() != null ? firstResponse.getThinking() : "")
                    .toolCalls(toolCalls)
                    .build());

            executeToolCalls(context, traceId, firstResponse.getToolCalls(), messages);
        } else {
            // 无工具调用 → 直接返回内容
            String content = firstResponse.getContent() != null ? firstResponse.getContent() : "";
            messages.add(Message.builder().role("assistant").content(content).build());
            return content;
        }

        // 后续轮次（非流式）
        for (int round = 1; round < MAX_TOOL_ROUNDS; round++) {
            log.info(logJson("INFO", "react_round", "RESPOND", traceId,
                    "Round " + (round + 1) + " (non-streaming)", null));

            ModelResponse response;
            try {
                response = chatFacade.chat(context.getRequest());
            } catch (Exception e) {
                log.error("LLM call failed in round {}: {}", round, e.getMessage(), e);
                return "[LLM调用失败: " + e.getMessage() + "]";
            }

            if (!response.hasToolCalls()) {
                String content = response.getContent() != null ? response.getContent() : "";
                messages.add(Message.builder().role("assistant").content(content).build());
                return content;
            }

            List<ToolCall> toolCalls = convertToolCalls(response);
            messages.add(Message.builder()
                    .role("assistant")
                    .content(response.getContent() != null ? response.getContent() : "")
                    .thinking(response.getThinking() != null ? response.getThinking() : "")
                    .toolCalls(toolCalls)
                    .build());

            executeToolCalls(context, traceId, response.getToolCalls(), messages);
        }

        return "[已达最大工具调用轮数(" + MAX_TOOL_ROUNDS + ")]";
    }

    private void executeToolCalls(ChatContext context, String traceId,
                                  List<ModelResponse.ToolCallRequest> reqs,
                                  List<Message> messages) {
        for (ModelResponse.ToolCallRequest req : reqs) {
            log.info(logJson("INFO", "tool_executing", "RESPOND", traceId,
                    "Executing tool: name=" + req.getName() + " id=" + req.getId()
                            + " args=" + req.getArguments(), null));

            Map<String, Object> args;
            try {
                if (req.getArguments() != null && !req.getArguments().isEmpty()) {
                    args = objectMapper.readValue(req.getArguments(),
                            new TypeReference<Map<String, Object>>() {});
                } else {
                    args = Collections.emptyMap();
                }
            } catch (Exception e) {
                log.error("工具参数JSON解析失败: tool={} args={} error={}",
                        req.getName(), req.getArguments(), e.getMessage(), e);
                args = Collections.emptyMap();
            }

            ToolExecuteRequest execReq = ToolExecuteRequest.builder()
                    .toolName(req.getName())
                    .args(args)
                    .sessionId(context.getRequest().getSessionId())
                    .build();

            try {
                ToolExecutionResult result = actionFeignClient.executeTool(execReq);
                String toolOutput = result.isSuccess()
                        ? result.getResult()
                        : "Error: " + result.getError();
                messages.add(Message.builder()
                        .role("tool")
                        .toolCallId(req.getId())
                        .content(toolOutput)
                        .build());
                log.info(logJson("INFO", "tool_executed", "RESPOND", traceId,
                        "tool=" + req.getName() + " success=" + result.isSuccess(), null));
            } catch (Exception e) {
                messages.add(Message.builder()
                        .role("tool")
                        .toolCallId(req.getId())
                        .content("Tool error: " + e.getMessage())
                        .build());
                log.error("工具Feign调用失败: tool={} error={}",
                        req.getName(), e.getMessage(), e);
            }
        }
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

    /** 将文本按自然边界拆分，ReAct 最终结果用此模拟渐进渲染 */
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
                String chunk = buf.toString().stripTrailing();
                if (!chunk.isEmpty()) {
                    events.add(sseEvent("message", chunk));
                }
                buf.setLength(0);
            }
        }
        String last = buf.toString().stripTrailing();
        if (!last.isEmpty()) {
            events.add(sseEvent("message", last));
        }
        return Flux.fromIterable(events);
    }

    private List<ToolCall> convertToolCalls(ModelResponse response) {
        return response.getToolCalls().stream()
                .<ToolCall>map(req -> ToolCall.builder()
                        .toolCallId(req.getId())
                        .name(req.getName())
                        .arguments(req.getArguments())
                        .build())
                .toList();
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
