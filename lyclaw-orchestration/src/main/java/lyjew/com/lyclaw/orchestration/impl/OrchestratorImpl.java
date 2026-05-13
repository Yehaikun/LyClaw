package lyjew.com.lyclaw.orchestration.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.autoconfigure.processor.PipelineStageProcessor;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.orchestration.AgentEvent;
import lyjew.com.lyclaw.orchestration.OrchestrationContext;
import lyjew.com.lyclaw.orchestration.Orchestrator;
import lyjew.com.lyclaw.pipeline.PipelineContext;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import org.slf4j.MDC;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编排器核心实现。
 *
 * 负责接收聊天上下文，构建并执行完整的处理管线（Pipeline）。
 * 管线按顺序执行：上下文构建 -> 安全拦截 -> 工具调用循环 -> 响应构建 -> 指标采集。
 * 同时支持 Agent 协作任务的编排，通过 Flux 发送协作事件流。
 * 提供协作任务的取消和进度查询能力。
 */
@Slf4j
@Service
public class OrchestratorImpl implements Orchestrator {

    private final PipelineStageProcessor pipelineStageProcessor;

    /** 协作取消标记，键为 collaborationId */
    private final ConcurrentHashMap<String, Boolean> cancellationFlags = new ConcurrentHashMap<>();
    /** 协作进度追踪，键为 collaborationId */
    private final ConcurrentHashMap<String, Double> progressTracker = new ConcurrentHashMap<>();

    public OrchestratorImpl(PipelineStageProcessor pipelineStageProcessor) {
        this.pipelineStageProcessor = pipelineStageProcessor;
    }

    /**
     * 执行完整的编排管线。
     *
     * 构建响应式管线后依次执行各阶段。如果任一阶段出错，
     * 通过 onErrorResume 捕获异常并返回格式化的错误 SSE 事件。
     * 整个执行在 boundedElastic 调度器上运行，避免阻塞 Netty 事件循环。
     *
     * @param context 聊天上下文（包含请求、会话、记忆等）
     * @return SSE 事件流 Flux
     */
    @Override
    public Flux<ServerSentEvent<String>> execute(ChatContext context) {
        return Flux.defer(() -> {
            // 从上下文提取 traceId 并注入 MDC，便于日志关联
            String traceId = context.getTracing().getTraceId();
            MDC.put("traceId", traceId);

            // 创建管线上下文并绑定到 ChatContext
            PipelineContext pipelineCtx = new PipelineContext();
            context.setAttribute("pipelineContext", pipelineCtx);

            // 构建响应式管线
            List<ReactivePipelineStage> stages = pipelineStageProcessor.getSortedStages();
            Flux<ServerSentEvent<String>> pipelineFlux = Flux.empty();
            for (ReactivePipelineStage stage : stages) {
                pipelineFlux = pipelineFlux.concatWith(
                        Flux.defer(() -> stage.execute(context, pipelineCtx))
                );
            }
            return pipelineFlux
                    .onErrorResume(err -> {
                        String failedStage = pipelineCtx.getCurrentStage().get();
                        context.getTracing().endStage(failedStage);
                        log.error("[Orchestrator] Pipeline error at stage {}: {}",
                                failedStage, err.getMessage(), err);
                        return Flux.just(
                                sseEvent("error",
                                        "{\"message\":\"" + escapeJson(err.getMessage())
                                                + "\",\"traceId\":\"" + escapeJson(traceId)
                                                + "\",\"stage\":\"" + escapeJson(failedStage) + "\"}"),
                                sseEvent("done", "{\"status\":\"error\"}")
                        );
                    })
                    .doFinally(signalType -> MDC.remove("traceId"));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 执行 Agent 协作任务。
     *
     * 通过 Flux.create 以编程方式生成协作事件流，依次发送：
     * COLLABORATION_STARTED -> TASK_STARTED（循环）-> TASK_COMPLETED（循环）-> COLLABORATION_ENDED
     *
     * @param context 编排上下文（包含协作模式、任务列表等）
     * @return Agent 事件流 Flux
     */
    @Override
    public Flux<AgentEvent> executeAgentTask(OrchestrationContext context) {
        // 使用已有 collaborationId 或生成新的
        String collabId = context.getCollaborationId() != null
                ? context.getCollaborationId()
                : "collab-" + UUID.randomUUID().toString().substring(0, 8);

        log.info("[Orchestrator] Executing agent task: collabId={}, modeId={}, tasks={}",
                collabId, context.getCollaborationModeId(),
                context.getTasks() != null ? context.getTasks().size() : 0);

        return Flux.create(sink -> {
            try {
                // 发送协作开始事件
                sink.next(AgentEvent.builder()
                        .type(AgentEvent.EventType.COLLABORATION_STARTED)
                        .agentId("orchestrator")
                        .data("Collaboration started: " + collabId)
                        .metadata(Map.of("collaborationId", collabId,
                                "modeId", context.getCollaborationModeId(),
                                "taskCount", context.getTasks() != null ? context.getTasks().size() : 0))
                        .timestamp(System.currentTimeMillis())
                        .build());

                // 发送全局任务开始事件
                sink.next(AgentEvent.builder()
                        .type(AgentEvent.EventType.TASK_STARTED)
                        .agentId("agent-0")
                        .data("Task execution started")
                        .metadata(Map.of("collaborationId", collabId))
                        .timestamp(System.currentTimeMillis())
                        .build());

                // 遍历所有任务，为每个任务发送开始和完成事件
                if (context.getTasks() != null) {
                    for (int i = 0; i < context.getTasks().size(); i++) {
                        var task = context.getTasks().get(i);
                        // 任务开始事件
                        sink.next(AgentEvent.builder()
                                .type(AgentEvent.EventType.TASK_STARTED)
                                .agentId("agent-" + i)
                                .data("Task " + task.getTaskId() + " started: " + task.getType())
                                .metadata(Map.of("taskId", task.getTaskId(),
                                        "type", task.getType(),
                                        "target", task.getTarget() != null ? task.getTarget() : ""))
                                .timestamp(System.currentTimeMillis())
                                .build());

                        // 任务完成事件
                        sink.next(AgentEvent.builder()
                                .type(AgentEvent.EventType.TASK_COMPLETED)
                                .agentId("agent-" + i)
                                .data("Task " + task.getTaskId() + " completed")
                                .metadata(Map.of("taskId", task.getTaskId()))
                                .timestamp(System.currentTimeMillis())
                                .build());
                    }
                }

                // 发送协作结束事件
                sink.next(AgentEvent.builder()
                        .type(AgentEvent.EventType.COLLABORATION_ENDED)
                        .agentId("orchestrator")
                        .data("Collaboration ended: " + collabId)
                        .timestamp(System.currentTimeMillis())
                        .build());
                sink.complete();
            } catch (Exception e) {
                log.error("[Orchestrator] Agent task execution failed: {}", e.getMessage(), e);
                // 异常时发送失败事件
                sink.next(AgentEvent.builder()
                        .type(AgentEvent.EventType.TASK_FAILED)
                        .agentId("orchestrator")
                        .data("Agent task failed: " + e.getMessage())
                        .timestamp(System.currentTimeMillis())
                        .build());
                sink.complete();
            }
        });
    }

    /**
     * 取消指定的协作任务。
     *
     * @param collaborationId 协作 ID
     * @return 如果首次取消返回 true，如果已取消则返回 false
     */
    @Override
    public boolean cancel(String collaborationId) {
        if (collaborationId == null) return false;
        // 使用 put 的原子性检查是否已存在取消标记
        Boolean existing = cancellationFlags.put(collaborationId, true);
        if (existing != null && existing) {
            log.info("[Orchestrator] Cancellation flag already set for: {}", collaborationId);
            return false;  // 已经取消过
        }
        progressTracker.remove(collaborationId);
        log.info("[Orchestrator] Cancellation requested for: {}", collaborationId);
        return true;
    }

    /**
     * 查询协作进度。
     *
     * @param collaborationId 协作 ID
     * @return 0.0 到 1.0 的进度值
     */
    @Override
    public double getProgress(String collaborationId) {
        return progressTracker.getOrDefault(collaborationId, 0.0);
    }

    // --- retained helper methods for error handling in execute() ---

    /**
     * 构建 SSE 事件。
     *
     * @param eventType 事件类型（如 "message"、"error"、"done"）
     * @param payload   事件负载数据
     * @return ServerSentEvent 实例
     */
    private ServerSentEvent<String> sseEvent(String eventType, String payload) {
        return ServerSentEvent.<String>builder().event(eventType).data(payload).build();
    }

    /**
     * 对 JSON 字符串值中的特殊字符进行转义。
     * 防止注入攻击并确保 JSON 格式正确。
     *
     * @param s 原始字符串
     * @return 转义后的字符串
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
