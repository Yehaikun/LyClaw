package lyjew.com.lyclaw.pipeline.stage;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.infra.metrics.MetricsCollector;
import lyjew.com.lyclaw.memory.MemoryQuery;
import lyjew.com.lyclaw.memory.MemoryQueryResult;
import lyjew.com.lyclaw.memory.MemorySystem;
import lyjew.com.lyclaw.annotation.PipelineStage;
import lyjew.com.lyclaw.react.AgentContext;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * 上下文构建阶段，order=0，管线第一个阶段。
 * 加载会话、检索记忆，丰富 AgentContext。
 */
@Slf4j
@PipelineStage(name = "ContextBuild", group = "PREPROCESSING")
public class ContextBuildStage extends PipelineStageBase {

    private final MemorySystem memorySystem;
    private final MetricsCollector metricsCollector;

    public ContextBuildStage(MemorySystem memorySystem,
                             @org.springframework.lang.Nullable MetricsCollector metricsCollector) {
        this.memorySystem = memorySystem;
        this.metricsCollector = metricsCollector;
    }

    @Override
    public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
        if (ctx.isTerminated()) return Flux.empty();

        return Flux.create(sink -> {
            String traceId = ctx.getTracing().getTraceId();
            try {
                ctx.getCurrentStage().set("CONTEXT_BUILD");
                ctx.getTracing().beginStage("CONTEXT_BUILD");
                long t1 = System.currentTimeMillis();

                log.info("\n\n========== [阶段 0/5] 上下文构建 [CONTEXT_BUILD] ==========");
                log.info(logJson("INFO", "stage_start", "CONTEXT_BUILD", traceId,
                        "Loading session and retrieving memories", null));
                sink.next(sseEvent("context_build_start", "Loading session and retrieving memories"));

                long memCallStart = System.currentTimeMillis();
                MemoryQuery memoryQuery = MemoryQuery.builder()
                        .queryText(ctx.getUserMessage())
                        .topK(10)
                        .build();
                MemoryQueryResult memoryResult = memorySystem.retrieve(memoryQuery);
                long memCallDuration = System.currentTimeMillis() - memCallStart;
                int memoryHits = memoryResult != null ? memoryResult.getTotalHits() : 0;

                // 存储记忆结果到 AgentContext，供下游 Stage 使用
                if (memoryResult != null && memoryResult.getEntries() != null) {
                    ctx.setAttribute("memoryEntries", memoryResult.getEntries());
                }

                log.info(logJson("INFO", "memory_call", "CONTEXT_BUILD", traceId,
                        "memorySystem.retrieve completed: " + memoryHits + " entries", memCallDuration));
                sink.next(sseEvent("context_build_complete",
                        "Loaded session, retrieved " + memoryHits + " memory entries"));

                if (metricsCollector != null) {
                    metricsCollector.recordMemoryRetrieval(
                            memoryResult != null ? memoryResult.getQueryTimeMs() : 0, memoryHits);
                }

                long stageDuration = System.currentTimeMillis() - t1;
                ctx.getTracing().endStage("CONTEXT_BUILD");
                log.info(logJson("INFO", "stage_complete", "CONTEXT_BUILD", traceId,
                        "Context build complete", stageDuration));

                if (metricsCollector != null) {
                    metricsCollector.recordPipelineStage("CONTEXT_BUILD", stageDuration);
                }

                sink.complete();
            } catch (Exception e) {
                log.warn(logJson("WARN", "stage_error", "CONTEXT_BUILD", traceId,
                        "Context build failed, continuing: " + e.getMessage(), null));
                ctx.getCurrentStage().set("CONTEXT_BUILD");
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
