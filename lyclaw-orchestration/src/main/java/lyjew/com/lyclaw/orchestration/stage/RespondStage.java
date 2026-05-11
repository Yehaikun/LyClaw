package lyjew.com.lyclaw.orchestration.stage;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.chat.ChatModel;
import lyjew.com.lyclaw.chat.RoutingDecision;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.pipeline.PipelineContext;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import org.springframework.http.codec.ServerSentEvent;
import lyjew.com.lyclaw.framework.annotation.PipelineStage;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 响应生成阶段，属于后处理（POSTPROCESSING）组，在 ReflectionStage 之后执行。
 *
 * <p>职责：根据执行结果和反思评分，通过 LLM（大语言模型）生成面向用户的最终响应。
 * <ol>
 *   <li><b>LLM 流式响应</b>：如果配置了 ModelAdapter，则将工具执行结果组合后发送给 LLM，
 *       通过 SSE 流式逐字推送响应内容给前端。</li>
 *   <li><b>降级文本响应</b>：如果没有配置 LLM 适配器，则使用 buildFinalResponse 方法生成
 *       包含任务统计和反思评分的格式文本。</li>
 *   <li><b>异常恢复</b>：如果 LLM 调用失败，会捕获异常并改用降级文本响应，
 *       同时设置 terminated=true 通知 MetricsStage 使用降级路径。</li>
 * </ol>
 *
 * <p>执行顺序：第 4 位（getOrder 返回 4）。
 */
@Slf4j
@PipelineStage(name = "Respond", after = ReflectionStage.class, group = "POSTPROCESSING")
public class RespondStage extends PipelineStageBase {

    /** 聊天门面，统一入口用于调用 LLM */
    private final ChatFacade chatFacade;

    /**
     * 构造响应生成阶段。
     *
     * @param chatFacade 聊天门面，用于调用 LLM
     */
    public RespondStage(ChatFacade chatFacade) {
        this.chatFacade = chatFacade;
    }

    /**
     * 执行响应生成流程。
     *
     * <p>根据工具执行结果和反思评分生成面向用户的最终响应：
     * <ol>
     *   <li><b>LLM 流式响应</b>：如果 ModelAdapter 已配置，将工具结果构建为 ChatRequest，
     *       调用 LLM 的流式接口，逐字通过 SSE message 事件推送给前端。</li>
     *   <li><b>降级文本响应</b>：如果 ModelAdapter 未配置，使用 buildFinalResponse 生成
     *       包含任务统计和反思评分的纯文本响应。</li>
     *   <li><b>异常恢复</b>：LLM 调用失败时捕获异常，改用降级文本并设置 terminated=true，
     *       通知 MetricsStage 跳过正常的指标发送而改用降级路径。</li>
     * </ol>
     *
     * @param context     当前聊天上下文，包含请求信息和追踪信息
     * @param pipelineCtx 流水线上下文，包含工具执行结果和反思评分
     * @return SSE 事件流，包含 respond_start 和流式 message 事件
     */
    @Override
    public Flux<ServerSentEvent<String>> execute(ChatContext context, PipelineContext pipelineCtx) {
        // 流水线已终止或未正常完成，跳过本阶段
        if (pipelineCtx.isTerminated() || !pipelineCtx.isPipelineOk()) {
            return Flux.empty();
        }

        return Flux.defer(() -> {
            String traceId = context.getTracing().getTraceId();
            int sc = pipelineCtx.getSuccessCount().get();
            int fc = pipelineCtx.getFailCount().get();
            ReflectionReport report = pipelineCtx.getReportRef().get();
            double score = pipelineCtx.getReflectScoreRef().get();
            List<String> toolResults = pipelineCtx.getToolResults();
            // 近似的编排起始时间戳
            long orchestrationStart = System.currentTimeMillis() - 0; // approximate

            log.info(logJson("INFO", "stage_start", "RESPOND", traceId,
                    "Generating AI response", null));
            pipelineCtx.getCurrentStage().set("RESPOND");
            context.getTracing().beginStage("RESPOND");

            // 构建 LLM 请求
            lyjew.com.lyclaw.model.ChatRequest llmRequest = buildLlmRequest(context, toolResults);

            Flux<ServerSentEvent<String>> bodyFlux;
            if (chatFacade != null) {
                // LLM 路径：通过 ChatFacade 路由并调用流式接口
                log.info(logJson("INFO", "llm_call", "RESPOND", traceId,
                        "Calling LLM via ChatFacade: messages=" + llmRequest.getMessageCount(),
                        null));

                // ChatFacade 流式调用 -> Flux<ModelResponse>，直接从 ModelResponse 取文本无需手动 SSE 解析
                lyjew.com.lyclaw.chat.RoutingDecision decision = chatFacade.route(llmRequest, null);
                lyjew.com.lyclaw.chat.ChatModel model = chatFacade.resolveModel(decision);
                bodyFlux = model.stream(llmRequest)
                        .handle((response, sink) -> {
                            String text = response.getContent() != null ? response.getContent() : "";
                            if (!text.isEmpty()) {
                                sink.next(sseEvent("message", text));
                            }
                        });
            } else {
                // 无 ChatFacade 降级路径：使用硬编码文本响应
                log.warn(logJson("WARN", "llm_missing", "RESPOND", traceId,
                        "No ChatFacade available, using hardcoded response", null));
                String responseText = buildFinalResponse(sc, fc, toolResults, report);
                bodyFlux = Flux.just(sseEvent("message", responseText));
            }

            return Flux.just(sseEvent("respond_start", "Generating AI response"))
                    .concatWith(bodyFlux)
                    .onErrorResume(err -> {
                        // LLM 调用异常恢复：使用降级文本，通知 MetricsStage 走降级路径
                        context.getTracing().endStage("RESPOND");
                        long elapsed = System.currentTimeMillis() - orchestrationStart;
                        log.error(logJson("ERROR", "stage_error", "RESPOND", traceId,
                                "LLM call failed: " + err.getMessage(), elapsed), err);
                        String fallback = buildFinalResponse(sc, fc, toolResults, report);
                        pipelineCtx.setTerminated(true); // 通知 MetricsStage 走降级路径
                        return Flux.just(
                                sseEvent("message", fallback),
                                sseEvent("done", "{\"status\":\"completed\",\"fallback\":true}")
                        );
                    });
        });
    }

    @Override
    public int getOrder() { return 4; }

    @Override
    public String getStageName() { return "Respond"; }
}
