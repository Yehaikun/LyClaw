package lyjew.com.lyclaw.pipeline.stage;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.infra.metrics.MetricsCollector;
import lyjew.com.lyclaw.memory.MemoryEntry;
import lyjew.com.lyclaw.memory.MemorySystem;
import lyjew.com.lyclaw.memory.PerceptionData;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.annotation.PipelineStage;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标记录阶段，order=4，管线最后一个阶段。
 */
@Slf4j
@PipelineStage(name = "Metrics", after = ReflectionStage.class, group = "POSTPROCESSING")
public class MetricsStage extends PipelineStageBase {

    private final MemorySystem memorySystem;
    private final MetricsCollector metricsCollector;

    public MetricsStage(MemorySystem memorySystem,
                        @org.springframework.lang.Nullable MetricsCollector metricsCollector) {
        this.memorySystem = memorySystem;
        this.metricsCollector = metricsCollector;
    }

    @Override
    public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
        if (ctx.isTerminated()) {
            log.info("[指标采集] 管线已终止，跳过");
            return Flux.empty();
        }
        if (!ctx.isPipelineOk()) {
            log.warn("[指标采集] 管线状态异常(pipelineOk=false)，跳过");
            return Flux.empty();
        }

        return Flux.defer(() -> {
            String traceId = ctx.getTracing().getTraceId();
            String sessionId = ctx.getSessionId();
            int successCount = ctx.getSuccessCount().get();
            int failCount = ctx.getFailCount().get();
            List<String> toolResults = ctx.getToolResults();
            double score = ctx.getReflectScoreRef().get();
            int taskCount = toolResults.size();
            long now = System.currentTimeMillis();

            log.info("\n\n========== [阶段 4] 指标采集 [METRICS] ==========");
            log.info("[指标采集] 开始 | sessionId={} | 任务数={} | 成功={} | 失败={} | 评分={}",
                    sessionId, taskCount, successCount, failCount, String.format("%.2f", score));

            try {
                log.info("[指标采集] 摄入记忆...");
                PerceptionData perception = PerceptionData.builder()
                        .role("assistant")
                        .content("编排完成 | 任务: " + taskCount
                                + " | 成功: " + successCount
                                + " | 失败: " + failCount
                                + " | 反思评分: " + score)
                        .timestamp(now)
                        .metadata(Map.of("sessionId", sessionId != null ? sessionId : "",
                                "taskCount", taskCount,
                                "successCount", successCount,
                                "failCount", failCount))
                        .build();
                MemoryEntry entry = memorySystem.ingestPerception(sessionId, perception);
                entry.setUserId("default");
                log.info("[OK] [指标采集] 记忆摄入完成");
            } catch (Exception e) {
                log.warn(logJson("WARN", "memory_ingest_failed", "METRICS", traceId,
                        "记忆摄入失败（非关键）: " + e.getMessage(), null), e);
                log.warn("[WARN] [指标采集] 记忆摄入失败（非关键）| error={}", e.getMessage());
            }

            if (metricsCollector != null) {
                metricsCollector.recordPipelineStage("RESPOND", 0);
            }
            ctx.getTracing().endStage("RESPOND");

            long totalDuration = ctx.getTracing().getTotalDuration();
            ctx.getTracing().markEnd();
            log.info(logJson("INFO", "pipeline_complete", "ORCHESTRATION_TOTAL", traceId,
                    "编排完成: " + taskCount + " 个任务", totalDuration));
            log.info("[指标采集] 管线全部完成 | 总耗时={}ms | 任务数={} | traceId={}",
                    totalDuration, taskCount, traceId);

            if (metricsCollector != null) {
                metricsCollector.recordPipelineStage("ORCHESTRATION_TOTAL", totalDuration);
                metricsCollector.recordLlmCall("orchestrator", "llm", 0, 0, totalDuration);
            }

            ctx.getCurrentStage().set("done");
            log.info("══════════ 管线执行全部完成 ══════════");

            Map<String, Object> metricsData = new LinkedHashMap<>();
            metricsData.put("totalDurationMs", totalDuration);
            metricsData.put("tasksProcessed", taskCount);
            metricsData.put("traceId", traceId);

            Map<String, Object> doneData = new LinkedHashMap<>();
            doneData.put("status", "completed");
            doneData.put("durationMs", totalDuration);
            doneData.put("traceId", traceId);

            return Flux.just(
                    sseEvent("respond_complete", "响应已生成，记忆已持久化"),
                    sseEvent("metrics", metricsData),
                    sseEvent("done", doneData)
            );
        });
    }

    @Override
    public int getOrder() { return 5; }

    @Override
    public String getStageName() { return "Metrics"; }
}
