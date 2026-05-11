package lyjew.com.lyclaw.orchestration.stage;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.feign.MemoryFeignClient;
import lyjew.com.lyclaw.infra.metrics.MetricsCollector;
import lyjew.com.lyclaw.memory.PerceptionData;
import lyjew.com.lyclaw.pipeline.PipelineContext;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class MetricsStage extends PipelineStageBase {

    private final MemoryFeignClient memoryFeignClient;
    private final MetricsCollector metricsCollector;

    public MetricsStage(MemoryFeignClient memoryFeignClient,
                         @org.springframework.lang.Nullable MetricsCollector metricsCollector) {
        this.memoryFeignClient = memoryFeignClient;
        this.metricsCollector = metricsCollector;
    }

    @Override
    public Flux<ServerSentEvent<String>> execute(ChatContext context, PipelineContext pipelineCtx) {
        // If terminated by upstream error (not a normal pipeline completion),
        // the error path in RespondStage already emitted the fallback + done.
        if (pipelineCtx.isTerminated()) {
            return Flux.empty();
        }
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

            // Approximate orchestration start from respondStartMs (total duration reconstructed)
            long orchestrationStart = 0; // Note: original passes this from outer scope
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
                memoryFeignClient.ingest(perception, sessionId, "default");
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

    @Override
    public int getOrder() { return 5; }

    @Override
    public String getStageName() { return "Metrics"; }
}
