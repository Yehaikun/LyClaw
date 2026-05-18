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

import java.util.List;
import java.util.Map;

/**
 * 指标记录阶段，order=4，管线最后一个阶段。
 */
@Slf4j
@PipelineStage(name = "Metrics", after = RespondStage.class, group = "POSTPROCESSING")
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
        if (ctx.isTerminated()) return Flux.empty();
        if (!ctx.isPipelineOk()) return Flux.empty();

        return Flux.defer(() -> {
            String traceId = ctx.getTracing().getTraceId();
            String sessionId = ctx.getSessionId();
            int successCount = ctx.getSuccessCount().get();
            int failCount = ctx.getFailCount().get();
            List<String> toolResults = ctx.getToolResults();
            double score = ctx.getReflectScoreRef().get();
            int taskCount = toolResults.size();
            long now = System.currentTimeMillis();

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
                                "failCount", failCount))
                        .build();
                MemoryEntry entry = memorySystem.ingestPerception(sessionId, perception);
                entry.setUserId("default");
            } catch (Exception e) {
                log.warn(logJson("WARN", "memory_ingest_failed", "METRICS", traceId,
                        "Memory ingest failed (non-critical): " + e.getMessage(), null));
            }

            if (metricsCollector != null) {
                metricsCollector.recordPipelineStage("RESPOND", 0);
            }
            ctx.getTracing().endStage("RESPOND");

            long totalDuration = ctx.getTracing().getTotalDuration();
            ctx.getTracing().markEnd();
            log.info("\n\n══════════════════════════════════");
            log.info("  [阶段 4/5] 指标采集 [METRICS]");
            log.info("══════════════════════════════════");
            log.info(logJson("INFO", "pipeline_complete", "ORCHESTRATION_TOTAL", traceId,
                    "Orchestration completed: " + taskCount + " tasks", totalDuration));

            if (metricsCollector != null) {
                metricsCollector.recordPipelineStage("ORCHESTRATION_TOTAL", totalDuration);
                metricsCollector.recordLlmCall("orchestrator", "llm", 0, 0, totalDuration);
            }

            ctx.getCurrentStage().set("done");

            return Flux.just(
                    sseEvent("respond_complete", "Response generated and memory persisted"),
                    sseEvent("metrics",
                            "{\"totalDurationMs\":" + totalDuration
                                    + ",\"tasksProcessed\":" + taskCount
                                    + ",\"traceId\":\"" + escapeJson(traceId) + "\"}"),
                    sseEvent("done",
                            "{\"status\":\"completed\",\"durationMs\":" + totalDuration
                                    + ",\"traceId\":\"" + escapeJson(traceId) + "\"}")
            );
        });
    }

    @Override
    public int getOrder() { return 4; }

    @Override
    public String getStageName() { return "Metrics"; }
}
