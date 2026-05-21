package lyjew.com.lyclaw.pipeline.stage;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.infra.metrics.MetricsCollector;
import lyjew.com.lyclaw.memory.MemoryQuery;
import lyjew.com.lyclaw.memory.MemoryQueryResult;
import lyjew.com.lyclaw.memory.MemorySystem;
import lyjew.com.lyclaw.annotation.PipelineStage;
import lyjew.com.lyclaw.react.AgentContext;
import java.util.ArrayList;
import java.util.List;

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
        if (ctx.isTerminated()) {
            log.info("⏭️ [上下文构建] 管线已终止，跳过");
            return Flux.empty();
        }

        return Flux.defer(() -> {
            String traceId = ctx.getTracing().getTraceId();
            List<ServerSentEvent<String>> events = new ArrayList<>();
            try {
                ctx.getCurrentStage().set("CONTEXT_BUILD");
                ctx.getTracing().beginStage("CONTEXT_BUILD");
                long t1 = System.currentTimeMillis();

                log.info("\n\n========== [阶段 0] 上下文构建 [CONTEXT_BUILD] ==========");
                log.info(logJson("INFO", "stage_start", "CONTEXT_BUILD", traceId,
                        "开始加载会话并检索记忆", null));
                log.info("📋 [上下文构建] 开始加载会话 | sessionId={}", ctx.getSessionId());
                events.add(sseEvent("context_build_start", "正在加载会话并检索记忆"));

                long memCallStart = System.currentTimeMillis();
                log.info("🧠 [上下文构建] 开始检索记忆 | queryText长度={} | topK=10",
                        ctx.getUserMessage() != null ? ctx.getUserMessage().length() : 0);
                MemoryQuery memoryQuery = MemoryQuery.builder()
                        .queryText(ctx.getUserMessage())
                        .topK(10)
                        .build();
                MemoryQueryResult memoryResult = memorySystem.retrieve(memoryQuery);
                long memCallDuration = System.currentTimeMillis() - memCallStart;
                int memoryHits = memoryResult != null ? memoryResult.getTotalHits() : 0;

                if (memoryResult != null && memoryResult.getEntries() != null) {
                    ctx.setAttribute("memoryEntries", memoryResult.getEntries());
                }

                log.info(logJson("INFO", "memory_call", "CONTEXT_BUILD", traceId,
                        "记忆检索完成: " + memoryHits + " 条结果", memCallDuration));
                log.info("🧠 [上下文构建] 记忆检索完成 | 命中={}条 | 耗时={}ms", memoryHits, memCallDuration);
                events.add(sseEvent("context_build_complete",
                        "会话加载完成，检索到 " + memoryHits + " 条记忆"));

                if (metricsCollector != null) {
                    metricsCollector.recordMemoryRetrieval(
                            memoryResult != null ? memoryResult.getQueryTimeMs() : 0, memoryHits);
                }

                long stageDuration = System.currentTimeMillis() - t1;
                ctx.getTracing().endStage("CONTEXT_BUILD");
                log.info(logJson("INFO", "stage_complete", "CONTEXT_BUILD", traceId,
                        "上下文构建完成", stageDuration));
                log.info("✅ [上下文构建] 阶段完成 | 总耗时={}ms | 记忆条目={}",
                        stageDuration, memoryHits);

                if (metricsCollector != null) {
                    metricsCollector.recordPipelineStage("CONTEXT_BUILD", stageDuration);
                }
            } catch (Exception e) {
                log.warn(logJson("WARN", "stage_error", "CONTEXT_BUILD", traceId,
                        "上下文构建失败，继续执行: " + e.getMessage(), null), e);
                log.warn("⚠️ [上下文构建] 阶段异常（降级继续）| error={}", e.getMessage());
                ctx.getCurrentStage().set("CONTEXT_BUILD");
                events.add(sseEvent("context_build_complete", "上下文构建降级（记忆不可用）"));
            }
            return Flux.fromIterable(events);
        });
    }

    @Override
    public int getOrder() { return 0; }

    @Override
    public String getStageName() { return "ContextBuild"; }
}
