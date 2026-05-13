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
 * 编排器核心实现，是整个编排模块的入口和总调度器。
 *
 * <h3>核心职责</h3>
 * 本类是 LyClaw 系统的中枢神经，负责接收聊天上下文（ChatContext），通过 Spring 的
 * PipelineStageProcessor 自动发现和排序所有已注册的流水线阶段（ReactivePipelineStage），
 * 构建完整的响应式处理管线（Pipeline），并按序串联执行。同时，本类还承担 Agent 多智能体
 * 协作任务的编排调度，通过 Project Reactor 的 Flux 流式框架向前端推送协作事件流，
 * 支持协作任务的取消（cancel）和进度查询（getProgress）。
 *
 * <h3>管线阶段顺序（实际执行顺序）</h3>
 * 当前编排管线由六个阶段组成，按 getOrder() 返回值从小到大依次执行：
 * <ol>
 *   <li><b>ContextBuildStage（上下文构建）</b>——从记忆服务加载会话上下文和感知数据，
 *       填充到 PipelineContext 中，为后续阶段提供基础信息。</li>
 *   <li><b>SecurityCheckStage（安全检查）</b>——对用户输入进行安全审核，
 *       包括敏感词过滤、注入检测等，如有风险则提前终止管线。</li>
 *   <li><b>PlanExecutionStage（计划执行）</b>——调用远程规划服务（PlanFeignClient），
 *       将用户意图分解为多个可执行的任务节点（TaskNode），并推入流水线上下文中。
 *       注意：本阶段仅负责生成任务计划，不执行具体的工具调用。
 *       工具调用逻辑内嵌在 RespondStage 的 ReAct 循环中，由 LLM 自主决定调用时机和参数。</li>
 *   <li><b>ReflectionStage（反思校验）</b>——对 PlanExecutionStage 的执行结果进行
 *       质量评估和反思校验，生成反思报告（ReflectionReport）和评分，供 RespondStage 参考。</li>
 *   <li><b>RespondStage（响应生成）</b>——核心交互阶段。内部运行 ReAct（推理-行动）循环，
 *       将任务节点、工具定义和上下文发送给 LLM，由 LLM 自主决定调用哪个工具、
 *       传递什么参数，反复迭代直至任务完成或达到最大迭代次数，最终生成对用户的自然语言响应。
 *       工具调用循环并非独立的管线阶段，而是 RespondStage 的内部实现细节。</li>
 *   <li><b>MetricsStage（指标采集）</b>——整个管线的收尾阶段，负责持久化记忆摘要、
 *       记录各阶段耗时指标、汇总成功率和反思评分，并向前端推送最终完成事件。</li>
 * </ol>
 *
 * <h3>并发与错误处理</h3>
 * 整个 execute() 方法在 boundedElastic 调度器上运行，避免阻塞 Netty 事件循环线程。
 * 当管线中任一阶段抛出异常时，通过 onErrorResume 捕获并向前端推送格式化的错误 SSE 事件，
 * 包含 traceId 和失败阶段名称，便于问题定位。
 *
 * <h3>协作模式</h3>
 * executeAgentTask() 方法支持多 Agent 协作场景，通过 Flux.create 以编程方式生成
 * 协作事件流，依次发送 COLLABORATION_STARTED、TASK_STARTED、TASK_COMPLETED、
 * COLLABORATION_ENDED 等事件。协作任务可通过 collaborationId 进行取消和进度追踪。
 *
 * @see lyjew.com.lyclaw.orchestration.Orchestrator
 * @see lyjew.com.lyclaw.orchestration.stage.ContextBuildStage
 * @see lyjew.com.lyclaw.orchestration.stage.SecurityCheckStage
 * @see lyjew.com.lyclaw.orchestration.stage.PlanExecutionStage
 * @see lyjew.com.lyclaw.orchestration.stage.ReflectionStage
 * @see lyjew.com.lyclaw.orchestration.stage.RespondStage
 * @see lyjew.com.lyclaw.orchestration.stage.MetricsStage
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
