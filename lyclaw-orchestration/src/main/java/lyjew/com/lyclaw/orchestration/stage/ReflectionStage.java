package lyjew.com.lyclaw.orchestration.stage;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.feign.ReflectFeignClient;
import lyjew.com.lyclaw.infra.metrics.MetricsCollector;
import lyjew.com.lyclaw.pipeline.PipelineContext;
import lyjew.com.lyclaw.reflect.ReflectRequest;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import org.springframework.http.codec.ServerSentEvent;
import lyjew.com.lyclaw.annotation.PipelineStage;
import reactor.core.publisher.Flux;

/**
 * 反思评估阶段，属于核心（CORE）组，在 PlanExecutionStage 之后执行。
 *
 * <p>职责：收集执行阶段产生的所有工具输出结果，调用远程反思服务进行评估和打分，
 * 生成 ReflectionReport（包含整体评分和质量维度指标）。反思结果会存入 PipelineContext，
 * 供后续 RespondStage 和 MetricsStage 使用。
 *
 * <p>如果反思服务调用失败，会降级处理：设置评分为 0.0，标记流水线正常完成（pipelineOk=true），
 * 保证即使反思不可用也不影响用户收到最终响应。
 *
 * <p>执行顺序：第 3 位（getOrder 返回 3）。
 */
@Slf4j
@PipelineStage(name = "Reflection", after = PlanExecutionStage.class, group = "CORE")
public class ReflectionStage extends PipelineStageBase {

    /** 反思服务 Feign 客户端，用于远程调用评估服务 */
    private final ReflectFeignClient reflectFeignClient;
    /** 指标采集器，用于记录反思阶段耗时 */
    private final MetricsCollector metricsCollector;

    /**
     * 构造反思评估阶段。
     *
     * @param reflectFeignClient 反思服务远程调用客户端
     * @param metricsCollector   指标采集器，可为 null
     */
    public ReflectionStage(ReflectFeignClient reflectFeignClient,
                            @org.springframework.lang.Nullable MetricsCollector metricsCollector) {
        this.reflectFeignClient = reflectFeignClient;
        this.metricsCollector = metricsCollector;
    }

    /**
     * 执行反思评估流程。
     *
     * <p>将所有工具执行结果拼接为文本，发送给远程反思服务进行评估。
     * 如果 toolResults 为空，则回退使用原始用户消息作为评估输入。
     * 反思完成后将报告和评分存入 PipelineContext，并标记流水线正常完成（pipelineOk=true），
     * 同时记录 respondStartMs 供 MetricsStage 计算响应耗时。
     *
     * @param context     当前聊天上下文，包含 sessionId 和追踪信息
     * @param pipelineCtx 流水线上下文，包含工具执行结果，并接收反思报告和评分
     * @return SSE 事件流，包含反思进度和评分结果
     */
    @Override
    public Flux<ServerSentEvent<String>> execute(ChatContext context, PipelineContext pipelineCtx) {
        // 流水线已被上游终止，跳过本阶段
        if (pipelineCtx.isTerminated()) return Flux.empty();

        return Flux.create(sink -> {
            String traceId = context.getTracing().getTraceId();
            try {
                String sessionId = context.getRequest().getSessionId();
                String userMessage = context.getRequest().getLastUserMessage();
                java.util.List<String> toolResults = pipelineCtx.getToolResults();

                pipelineCtx.getCurrentStage().set("REFLECT");
                context.getTracing().beginStage("REFLECT");
                long t5 = System.currentTimeMillis();

                log.info("\n\n══════════════════════════════════");
                log.info("  [阶段 3/6] 反思评估 - 评估计划质量并生成调整建议 [REFLECT]");
                log.info("══════════════════════════════════");
                log.info(logJson("INFO", "stage_start", "REFLECT", traceId,
                        "Reflecting on execution results", null));
                sink.next(sseEvent("reflect_start", "Reflecting on execution results"));

                // 将所有工具输出拼接为一个字符串，空时回退使用用户消息
                String combinedOutput = String.join("\n", toolResults);
                ReflectRequest reflectReq = ReflectRequest.builder()
                        .sessionId(sessionId)
                        .output(combinedOutput.isEmpty() ? userMessage : combinedOutput)
                        .context("Orchestration pipeline execution - " + pipelineCtx.getNodes().size() + " tasks processed")
                        .build();
                long reflectCallStart = System.currentTimeMillis();
                ReflectionReport r = reflectFeignClient.reflect(reflectReq);
                long reflectCallDuration = System.currentTimeMillis() - reflectCallStart;
                log.info(logJson("INFO", "feign_call", "REFLECT", traceId,
                        "reflectFeignClient.reflect completed", reflectCallDuration));

                // 将反思报告和评分存入流水线上下文，供后续阶段使用
                pipelineCtx.getReportRef().set(r);
                double score = r != null ? r.getOverallScore() : 0.0;
                pipelineCtx.getReflectScoreRef().set(score);

                log.info(logJson("INFO", "reflect_result", "REFLECT", traceId,
                        "Reflection complete: score=" + score, null));
                sink.next(sseEvent("reflect_complete",
                        "{\"score\":" + score + ",\"reflectionId\":\""
                                + (r != null ? r.getReflectionId() : "N/A") + "\"}"));

                long stage5Duration = System.currentTimeMillis() - t5;
                context.getTracing().endStage("REFLECT");
                log.info(logJson("INFO", "stage_complete", "REFLECT", traceId,
                        "Reflection complete, score=" + score, stage5Duration));
                if (metricsCollector != null) {
                    metricsCollector.recordPipelineStage("REFLECT", stage5Duration);
                }

                // 记录响应开始时间戳，标记流水线正常完成，通知后续阶段可以开始响应生成
                pipelineCtx.getRespondStartMs().set(System.currentTimeMillis());
                pipelineCtx.setPipelineOk(true);
                pipelineCtx.getCurrentStage().set("pipeline_done");

                sink.complete();
            } catch (Exception e) {
                // 反思服务异常：降级处理，评分为 0，不阻塞后续流程
                log.warn(logJson("WARN", "stage_error", "REFLECT", traceId,
                        "Reflection failed, continuing degraded: " + e.getMessage(), null));
                pipelineCtx.getReportRef().set(null);
                pipelineCtx.getReflectScoreRef().set(0.0);
                pipelineCtx.setPipelineOk(true);
                pipelineCtx.getCurrentStage().set("pipeline_done");
                pipelineCtx.getRespondStartMs().set(System.currentTimeMillis());
                sink.next(sseEvent("reflect_complete", "{\"score\":0.0,\"reflectionId\":\"degraded\"}"));
                sink.complete();
            }
        });
    }

    /**
     * 返回本阶段在管线中的执行顺序编号。
     *
     * <p>返回值为 3，表示 ReflectionStage 是编排管线中的第四个阶段，
     * 排在 ContextBuildStage(0)、SecurityCheckStage(1) 和 PlanExecutionStage(2)
     * 之后。作为 CORE 组的成员之一，它在任务规划完成后对执行结果进行质量评估，
     * 其输出（ReflectionReport 和评分）将被后续的 RespondStage(4)
     * 和 MetricsStage(5) 使用。</p>
     *
     * @return 阶段顺序编号，固定为 3
     */
    @Override
    public int getOrder() { return 3; }

    /**
     * 返回本阶段的名称标识。
     *
     * <p>返回固定字符串 "Reflection"，作为本阶段在编排管线中的唯一标识符。
     * 该名称用于 PipelineStage 注解中的 name 属性和 after 依赖声明中的引用
     * （如 RespondStage 声明 after = ReflectionStage.class），日志输出中的
     * "REFLECT" 子阶段标签，Tracing 追踪中的 span 名称，以及指标采集中的
     * "REFLECT" 阶段标识。</p>
     *
     * @return 阶段名称，固定为 "Reflection"
     */
    @Override
    public String getStageName() { return "Reflection"; }
}
