package lyjew.com.lyclaw.orchestration.stage;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.memory.MemorySystem;
import lyjew.com.lyclaw.infra.metrics.MetricsCollector;
import lyjew.com.lyclaw.memory.MemoryQuery;
import lyjew.com.lyclaw.memory.MemoryQueryResult;
import lyjew.com.lyclaw.pipeline.PipelineContext;
import org.springframework.http.codec.ServerSentEvent;
import lyjew.com.lyclaw.annotation.PipelineStage;
import reactor.core.publisher.Flux;

/**
 * 上下文构建阶段，属于预处理（PREPROCESSING）组，是流水线的第一个阶段。
 *
 * <p>职责：在进入核心编排流程之前，加载必要的上下文信息：
 * <ol>
 *   <li><b>获取用户消息</b>：从 ChatContext 中提取用户最新消息。</li>
 *   <li><b>检索记忆</b>：将用户消息作为查询文本，调用 MemorySystem.retrieve 从记忆服务中
 *       检索相关的历史记忆条目（topK=10），为后续任务规划提供背景知识。</li>
 * </ol>
 *
 * <p>如果记忆服务调用失败，会降级继续（不阻塞流水线），前端会收到"degraded"提示。
 *
 * <p>执行顺序：第 0 位（getOrder 返回 0），是最先执行的阶段。
 */
@Slf4j
@PipelineStage(name = "ContextBuild", group = "PREPROCESSING")
public class ContextBuildStage extends PipelineStageBase {

    /** 记忆系统服务，用于检索相关历史记忆 */
    private final MemorySystem memorySystem;
    /** 指标采集器，用于记录记忆检索和阶段耗时指标 */
    private final MetricsCollector metricsCollector;

    /**
     * 构造上下文构建阶段。
     *
     * @param memorySystem 记忆系统服务
     * @param metricsCollector  指标采集器，可为 null
     */
    public ContextBuildStage(MemorySystem memorySystem,
                             @org.springframework.lang.Nullable MetricsCollector metricsCollector) {
        this.memorySystem = memorySystem;
        this.metricsCollector = metricsCollector;
    }

    /**
     * 执行上下文构建流程。
     *
     * <p>以用户最新消息作为查询文本，从记忆服务中检索 topK=10 条最相关的历史记忆条目。
     * 检索结果会记录日志，并通过 SSE 事件通知前端。同时记录记忆检索的指标数据。
     * 如果记忆服务不可用，则降级继续（发送 degraded 通知），不阻塞后续流水线。
     *
     * @param context     当前聊天上下文，包含用户消息和追踪信息
     * @param pipelineCtx 流水线上下文，用于跨阶段状态共享
     * @return SSE 事件流，包含上下文构建的进度和结果
     */
    @Override
    public Flux<ServerSentEvent<String>> execute(ChatContext context, PipelineContext pipelineCtx) {
        // 流水线已被终止，跳过本阶段
        if (pipelineCtx.isTerminated()) return Flux.empty();

        return Flux.create(sink -> {
            String traceId = context.getTracing().getTraceId();
            try {
                String userMessage = context.getRequest().getLastUserMessage();

                // 记录当前阶段名称，开始追踪
                pipelineCtx.getCurrentStage().set("CONTEXT_BUILD");
                context.getTracing().beginStage("CONTEXT_BUILD");
                long t1 = System.currentTimeMillis();

                log.info("\n\n══════════════════════════════════");
                log.info("  [阶段 0/6] 上下文构建 - 加载会话并检索记忆 [CONTEXT_BUILD]");
                log.info("══════════════════════════════════");
                log.info(logJson("INFO", "stage_start", "CONTEXT_BUILD", traceId,
                        "Loading session and retrieving memories", null));
                sink.next(sseEvent("context_build_start", "Loading session and retrieving memories"));

                // 调用记忆服务检索相关历史记忆
                long memCallStart = System.currentTimeMillis();
                MemoryQuery memoryQuery = MemoryQuery.builder()
                        .queryText(userMessage)
                        .topK(10) // 返回最相关的 10 条记忆
                        .build();
                MemoryQueryResult memoryResult = memorySystem.retrieve(memoryQuery);
                long memCallDuration = System.currentTimeMillis() - memCallStart;
                int memoryHits = memoryResult != null ? memoryResult.getTotalHits() : 0;

                log.info(logJson("INFO", "memory_call", "CONTEXT_BUILD", traceId,
                        "memorySystem.retrieve completed: " + memoryHits + " entries",
                        memCallDuration));
                sink.next(sseEvent("context_build_complete",
                        "Loaded session, retrieved " + memoryHits + " memory entries"));

                // 记录记忆检索指标
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
                // 记忆服务异常：降级继续，不阻塞后续流水线
                log.warn(logJson("WARN", "stage_error", "CONTEXT_BUILD", traceId,
                        "Context build failed, continuing without memory: " + e.getMessage(), null));
                pipelineCtx.getCurrentStage().set("CONTEXT_BUILD");
                sink.next(sseEvent("context_build_complete", "Context build degraded (memory unavailable)"));
                sink.complete();
            }
        });
    }

    /**
     * 返回本阶段在管线中的执行顺序编号。
     *
     * <p>返回值为 0，表示 ContextBuildStage 是整个编排管线的第一个阶段。
     * 作为 PREPROCESSING 组的唯一起始阶段，它在所有其他阶段之前执行。
     * PipelineStageProcessor 按此编号升序排列所有阶段，编号 0 意味着
     * 管线启动后 ContextBuildStage 会最先运行。该编号也用于日志输出中的阶段
     * 顺序标识和指标采集中的阶段位置标记。</p>
     *
     * @return 阶段顺序编号，固定为 0
     */
    @Override
    public int getOrder() { return 0; }

    /**
     * 返回本阶段的名称标识。
     *
     * <p>返回固定字符串 "ContextBuild"，作为本阶段在整个编排管线中的唯一标识符。
     * 该名称用于 PipelineStage 注解注册、自动装配时的阶段匹配、日志输出中的
     * 阶段标记（如 "[ContextBuild]"）、SSE 事件流中的阶段来源标注，以及
     * Tracing 追踪系统中与 span 名称对应的阶段标记。PipelineStageProcessor
     * 在排序和查找阶段时也会使用此名称进行匹配。</p>
     *
     * @return 阶段名称，固定为 "ContextBuild"
     */
    @Override
    public String getStageName() { return "ContextBuild"; }
}
