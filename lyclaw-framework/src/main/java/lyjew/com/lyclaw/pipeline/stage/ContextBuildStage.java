package lyjew.com.lyclaw.pipeline.stage;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.infra.metrics.MetricsCollector;
import lyjew.com.lyclaw.annotation.PipelineStage;
import lyjew.com.lyclaw.react.AgentContext;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * 上下文构建阶段，order=0，管线第一个阶段。
 * 加载会话、丰富 AgentContext。
 *
 * TODO: 记忆系统重新设计后，在此阶段注入 MemorySystem.retrieve() 检索结果
 */
@Slf4j
@PipelineStage(name = "ContextBuild", group = "PREPROCESSING")
public class ContextBuildStage extends PipelineStageBase {

    private final MetricsCollector metricsCollector;

    public ContextBuildStage(@org.springframework.lang.Nullable MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    @Override
    public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
        if (ctx.isTerminated()) {
            log.info("[上下文构建] 管线已终止，跳过");
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
                        "开始加载会话", null));
                log.info("[上下文构建] 开始加载会话 | sessionId={}", ctx.getSessionId());
                events.add(sseEvent("context_build_start", "正在加载会话"));

                // TODO: 记忆系统重新设计后恢复记忆检索
                // MemoryQuery memoryQuery = MemoryQuery.builder().queryText(ctx.getUserMessage()).topK(10).build();
                // MemoryQueryResult memoryResult = memorySystem.retrieve(memoryQuery);
                // ctx.setAttribute("memoryEntries", memoryResult.getEntries());

                log.info("[上下文构建] 记忆检索已跳过（待重新设计）");
                events.add(sseEvent("context_build_complete", "会话加载完成"));

                long stageDuration = System.currentTimeMillis() - t1;
                ctx.getTracing().endStage("CONTEXT_BUILD");
                log.info(logJson("INFO", "stage_complete", "CONTEXT_BUILD", traceId,
                        "上下文构建完成", stageDuration));
                log.info("[OK] [上下文构建] 阶段完成 | 总耗时={}ms", stageDuration);

                if (metricsCollector != null) {
                    metricsCollector.recordPipelineStage("CONTEXT_BUILD", stageDuration);
                }
            } catch (Exception e) {
                log.warn(logJson("WARN", "stage_error", "CONTEXT_BUILD", traceId,
                        "上下文构建失败，继续执行: " + e.getMessage(), null), e);
                log.warn("[WARN] [上下文构建] 阶段异常（降级继续）| error={}", e.getMessage());
                ctx.getCurrentStage().set("CONTEXT_BUILD");
                events.add(sseEvent("context_build_complete", "上下文构建降级"));
            }
            return Flux.fromIterable(events);
        });
    }

    @Override
    public int getOrder() { return 0; }

    @Override
    public String getStageName() { return "ContextBuild"; }
}
