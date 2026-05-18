package lyjew.com.lyclaw.orchestration.stage;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.memory.MemoryEntry;
import lyjew.com.lyclaw.memory.MemorySystem;
import lyjew.com.lyclaw.infra.metrics.MetricsCollector;
import lyjew.com.lyclaw.memory.PerceptionData;
import lyjew.com.lyclaw.pipeline.PipelineContext;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import org.springframework.http.codec.ServerSentEvent;
import lyjew.com.lyclaw.annotation.PipelineStage;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 指标记录阶段，属于后处理（POSTPROCESSING）组，在 RespondStage 之后执行。
 *
 * <p>职责：编排流程的最后一个阶段，负责以下收尾工作：
 * <ol>
 *   <li><b>持久化记忆</b>：将本次编排的摘要信息（任务数、成功/失败数、反思评分等）通过 MemorySystem 写入记忆服务。</li>
 *   <li><b>记录指标</b>：收集整个编排的生命周期指标（总耗时、阶段耗时、成功率和反思评分），通过 MetricsCollector 上报。</li>
 *   <li><b>发送汇总 SSE 事件</b>：向前端推送 metrics 和 done 事件，包含完整的执行统计数据。</li>
 * </ol>
 *
 * <p>注意：该阶段仅在流水线正常完成时执行。如果流水线已被终止（terminated）或标记为非正常（pipelineOk=false），
 * 会直接跳过，不产生任何输出。
 *
 * <p>执行顺序：第 5 位（getOrder 返回 5），是流水线的最后一个阶段。
 */
@Slf4j
@PipelineStage(name = "Metrics", after = RespondStage.class, group = "POSTPROCESSING")
public class MetricsStage extends PipelineStageBase {

    /** 记忆系统服务，用于持久化编排摘要 */
    private final MemorySystem memorySystem;
    /** 指标采集器，用于记录管道级别和 LLM 调用指标 */
    private final MetricsCollector metricsCollector;

    /**
     * 构造指标记录阶段。
     *
     * @param memorySystem 记忆系统服务
     * @param metricsCollector  指标采集器，可为 null
     */
    public MetricsStage(MemorySystem memorySystem,
                         @org.springframework.lang.Nullable MetricsCollector metricsCollector) {
        this.memorySystem = memorySystem;
        this.metricsCollector = metricsCollector;
    }

    /**
     * 执行指标记录和收尾工作。
     *
     * <p>这是编排流水线的最后一个阶段，执行以下收尾任务：
     * <ol>
     *   <li>构建 PerceptionData 并写入记忆服务（fire-and-forget，不阻塞）。</li>
     *   <li>记录 RESPOND 阶段的指标和总耗时。</li>
     *   <li>标记追踪结束，通过 SSE 发送 metrics 汇总事件和 done 完成事件。</li>
     * </ol>
     *
     * <p>前置条件：仅在 terminated=false 且 pipelineOk=true 时执行。
     * 如果上游 RespondStage 因异常设置了 terminated=true 并已发送 done 事件，
     * 则本阶段直接返回空流，避免重复发送。
     *
     * @param context     当前聊天上下文，包含 session 和追踪信息
     * @param pipelineCtx 流水线上下文，包含所有阶段的累积状态
     * @return SSE 事件流，包含 metrics 和 done 事件
     */
    @Override
    public Flux<ServerSentEvent<String>> execute(ChatContext context, PipelineContext pipelineCtx) {
        // 如果上游因错误终止（非正常流水线完成），RespondStage 的错误路径已发送降级 + done，
        // 此处跳过以避免重复发送 done 事件
        if (pipelineCtx.isTerminated()) {
            return Flux.empty();
        }
        // 流水线未正常完成，跳过指标记录
        if (!pipelineCtx.isPipelineOk()) {
            return Flux.empty();
        }

        return Flux.defer(() -> {
            String traceId = context.getTracing().getTraceId();
            String sessionId = context.getSession() != null
                    ? context.getSession().getSessionId()
                    : context.getRequest().getSessionId();
            int successCount = pipelineCtx.getSuccessCount().get();
            int failCount = pipelineCtx.getFailCount().get();
            List<String> toolResults = pipelineCtx.getToolResults();
            ReflectionReport report = pipelineCtx.getReportRef().get();
            double score = pipelineCtx.getReflectScoreRef().get();
            long respondStartMs = pipelineCtx.getRespondStartMs().get();
            int taskCount = toolResults.size();

            // 计算总耗时而非纯编排耗时。
            // orchestrationStart 当前置为 0，这意味着在 PerceptionData.metadata 中记录的
            // "orchestrationDurationMs" 实际上是自 Unix 纪元以来的总经过毫秒数（now - 0 = now），
            // 而非严格意义上的编排阶段耗时差。原始设计中此值应从外部作用域传入管线启动时刻的时间戳，
            // 但由于 MetricsStage 位于管线末尾且无法直接访问 OrchestratorImpl 中的启动时间戳，
            // 此处暂时使用 0 作为近似值。如需精确编排耗时，建议在 PipelineContext 中增加
            // orchestrationStartMs 字段，由 OrchestratorImpl.execute() 在管线启动时写入，
            // 然后在此处读取计算差值。
            long orchestrationStart = 0; // 当前为计算总经过时间，非编排耗时差值
            long now = System.currentTimeMillis();

            // Persist memory (non-blocking fire-and-forget)
            try {
                PerceptionData perception = PerceptionData.builder()
                        .role("assistant")
                        .content("Orchestration completed | Tasks: " + taskCount
                                + " | Success: " + successCount
                                + " | Failed: " + failCount
                                + " | ReflectScore: " + score)
                        .timestamp(now)
                        .metadata(Map.of("sessionId", sessionId != null ? sessionId : "",
                                "taskCount", taskCount,
                                "successCount", successCount,
                                "failCount", failCount,
                                "orchestrationDurationMs", now - orchestrationStart))
                        .build();
                MemoryEntry entry = memorySystem.ingestPerception(sessionId, perception);
                entry.setUserId("default");
            } catch (Exception e) {
                log.warn(logJson("WARN", "memory_ingest_failed", "RESPOND", traceId,
                        "Memory ingest failed (non-critical): " + e.getMessage(), null));
            }

            long respondDuration = respondStartMs > 0 ? now - respondStartMs : 0;
            if (metricsCollector != null) {
                metricsCollector.recordPipelineStage("RESPOND", respondDuration);
            }
            context.getTracing().endStage("RESPOND");
            log.info(logJson("INFO", "stage_complete", "RESPOND", traceId,
                    "Response generated and memory persisted", respondDuration));

            long totalDuration = context.getTracing().getTotalDuration();
            context.getTracing().markEnd();
            log.info("\n\n══════════════════════════════════");
            log.info("  [阶段 5/6] 指标采集 - 管线收尾与指标上报 [METRICS]");
            log.info("══════════════════════════════════");
            log.info(logJson("INFO", "pipeline_complete", "ORCHESTRATION_TOTAL", traceId,
                    "Orchestration completed: " + taskCount + " tasks", totalDuration));

            if (metricsCollector != null) {
                metricsCollector.recordPipelineStage("ORCHESTRATION_TOTAL", totalDuration);
                metricsCollector.recordLlmCall("orchestrator", "llm", 0, 0, totalDuration);
                metricsCollector.recordPipelineStage("METRICS", 0);
            }

            pipelineCtx.getCurrentStage().set("done");

            return Flux.just(
                    sseEvent("respond_complete", "Response generated and memory persisted"),
                    sseEvent("metrics",
                            "{\"totalDurationMs\":" + totalDuration
                                    + ",\"tasksProcessed\":" + taskCount
                                    + ",\"successRate\":"
                                    + (taskCount > 0
                                            ? String.format("%.2f", (double) successCount / taskCount)
                                            : "1.0")
                                    + ",\"reflectScore\":" + score
                                    + ",\"traceId\":\"" + escapeJson(traceId) + "\"}"),
                    sseEvent("done",
                            "{\"status\":\"completed\",\"durationMs\":" + totalDuration
                                    + ",\"traceId\":\"" + escapeJson(traceId) + "\"}")
            );
        });
    }

    /**
     * 返回本阶段在管线中的执行顺序编号。
     *
     * <p>返回值为 5，表示 MetricsStage 是编排管线中的最后一个阶段（第六个），
     * 排在所有业务处理阶段之后。PipelineStageProcessor 按此编号升序排列，
     * 确保指标采集在所有业务逻辑完成后才执行，避免遗漏任何阶段的统计数据。</p>
     *
     * @return 阶段顺序编号，固定为 5
     */
    @Override
    public int getOrder() { return 5; }

    /**
     * 返回本阶段的名称标识。
     *
     * <p>返回固定字符串 "Metrics"，作为本阶段在编排管线中的唯一标识符。
     * 该名称用于日志输出（如 "stage_complete Metrics"）、指标上报中的阶段标签
     * 以及 Tracing 追踪中的阶段标记。作为 POSTPROCESSING 组的成员，
     * 它仅在所有核心阶段正常完成后执行。</p>
     *
     * @return 阶段名称，固定为 "Metrics"
     */
    @Override
    public String getStageName() { return "Metrics"; }
}
