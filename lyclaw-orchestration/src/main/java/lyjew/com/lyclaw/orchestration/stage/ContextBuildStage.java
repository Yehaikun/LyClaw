package lyjew.com.lyclaw.orchestration.stage;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.feign.MemoryFeignClient;
import lyjew.com.lyclaw.infra.metrics.MetricsCollector;
import lyjew.com.lyclaw.memory.MemoryQuery;
import lyjew.com.lyclaw.memory.MemoryQueryResult;
import lyjew.com.lyclaw.pipeline.PipelineContext;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Slf4j
@Component
public class ContextBuildStage extends PipelineStageBase {

    private final MemoryFeignClient memoryFeignClient;
    private final MetricsCollector metricsCollector;

    public ContextBuildStage(MemoryFeignClient memoryFeignClient,
                             @org.springframework.lang.Nullable MetricsCollector metricsCollector) {
        this.memoryFeignClient = memoryFeignClient;
        this.metricsCollector = metricsCollector;
    }

    @Override
    public Flux<ServerSentEvent<String>> execute(ChatContext context, PipelineContext pipelineCtx) {
        if (pipelineCtx.isTerminated()) return Flux.empty();

        return Flux.create(sink -> {
            String traceId = context.getTracing().getTraceId();
            try {
                String userMessage = context.getRequest().getLastUserMessage();

                pipelineCtx.getCurrentStage().set("CONTEXT_BUILD");
                context.getTracing().beginStage("CONTEXT_BUILD");
                long t1 = System.currentTimeMillis();

                log.info(logJson("INFO", "stage_start", "CONTEXT_BUILD", traceId,
                        "Loading session and retrieving memories", null));
                sink.next(sseEvent("context_build_start", "Loading session and retrieving memories"));

                long memCallStart = System.currentTimeMillis();
                MemoryQuery memoryQuery = MemoryQuery.builder()
                        .queryText(userMessage)
                        .topK(10)
                        .build();
                MemoryQueryResult memoryResult = memoryFeignClient.retrieve(memoryQuery);
                long memCallDuration = System.currentTimeMillis() - memCallStart;
                int memoryHits = memoryResult != null ? memoryResult.getTotalHits() : 0;

                log.info(logJson("INFO", "feign_call", "CONTEXT_BUILD", traceId,
                        "memoryFeignClient.retrieve completed: " + memoryHits + " entries",
                        memCallDuration));
                sink.next(sseEvent("context_build_complete",
                        "Loaded session, retrieved " + memoryHits + " memory entries"));

                if (metricsCollector != null) {
                    metricsCollector.recordMemoryRetrieval(
                            memoryResult != null ? memoryResult.getQueryTimeMs() : 0, memoryHits);
                }

                long stage1Duration = System.currentTimeMillis() - t1;
                context.getTracing().endStage("CONTEXT_BUILD");
                log.info(logJson("INFO", "stage_complete", "CONTEXT_BUILD", traceId,
                        "Context build complete, " + memoryHits + " memory entries retrieved",
                        stage1Duration));

                if (metricsCollector != null) {
                    metricsCollector.recordPipelineStage("CONTEXT_BUILD", stage1Duration);
                }

                sink.complete();
            } catch (Exception e) {
                log.warn(logJson("WARN", "stage_error", "CONTEXT_BUILD", traceId,
                        "Context build failed, continuing without memory: " + e.getMessage(), null));
                pipelineCtx.getCurrentStage().set("CONTEXT_BUILD");
                sink.next(sseEvent("context_build_complete", "Context build degraded (memory unavailable)"));
                sink.complete();
            }
        });
    }

    @Override
    public int getOrder() { return 0; }

    @Override
    public String getStageName() { return "ContextBuild"; }
}
