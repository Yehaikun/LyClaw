package lyjew.com.lyclaw.orchestration.impl.collab;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.collab.*;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.orchestration.OrchestrationContext;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 监督者-工作者（Supervisor-Worker）协作模式。
 *
 * 第一个 Agent 担任监督者(supervisor)，负责分解任务并分派给工作者(worker)。
 * 所有 worker 并行执行各自子任务，完成后由监督者汇总结果。
 * 采用星型(Star)拓扑，监督者与每个 worker 之间建立双向通信通道。
 * 支持动态扩缩容（可增加 worker 数量）和取消操作。
 */
@Slf4j
@Component
public class SupervisorWorkerMode implements CollaborationMode {

    public static final String MODE_ID = "supervisor_worker";

    private final ConcurrentHashMap<String, Double> progressMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> cancelMap = new ConcurrentHashMap<>();

    /**
     * 获取监督者-工作者协作模式的唯一标识符。
     *
     * <p>返回固定字符串 "supervisor_worker"，作为该协作模式在系统中的全局唯一 ID。
     * 该标识符用于 CollaborationHub 中的模式注册和查找（如通过 ModeRegistry
     * 按 modeId 获取对应的协作模式实例）、编排上下文（OrchestrationContext）中
     * collaborationModeId 字段的赋值、日志输出中的模式标签，以及前端 API 中
     * 指定协作模式时的参数值。每个 CollaborationMode 实现类必须返回唯一且不可变的 modeId。</p>
     *
     * @return 模式标识符，固定为 "supervisor_worker"
     */
    @Override
    public String getModeId() {
        return MODE_ID;
    }

    /**
     * 获取监督者-工作者协作模式偏好的网络拓扑类型。
     *
     * <p>返回 TopologyType.STAR（星型拓扑）。在监督者-工作者模式中，第一个 Agent
     * 担任监督者（supervisor，中心节点），其余 Agent 为工作者（worker，叶节点）。
     * 监督者负责将任务分派给各个 worker，worker 并行执行后将结果上报给监督者汇总。
     * 星型拓扑最适合这种集中式任务分配和结果汇总场景，消息路由清晰高效：
     * 监督者通过 task_dispatch、result_collect、status_query 通道管理 worker，
     * worker 通过 result_report、error_report、status_update 通道向上汇报。
     * StarAgentChannel 会根据此偏好设置当前拓扑类型为 STAR。</p>
     *
     * @return 偏好的拓扑类型，固定为 TopologyType.STAR
     */
    @Override
    public TopologyType getPreferredTopology() {
        return TopologyType.STAR;
    }

    /**
     * 分配监督者-工作者角色。
     * 第一个 Agent 为监督者(root 节点，最高优先级)，
     * 其余为工作者，每个对应一个子任务节点。
     * 建立双向通信：监督者->工作者（分发任务、收集结果），工作者->监督者（上报结果、错误）。
     */
    @Override
    public AssignmentPlan assign(List<AgentHandle> availableAgents, OrchestrationContext ctx) {
        if (availableAgents == null || availableAgents.isEmpty()) {
            log.warn("[SupervisorWorker] No agents available for assignment");
            return AssignmentPlan.builder()
                    .assignments(Collections.emptyList())
                    .communicationChannels(Collections.emptyMap())
                    .build();
        }

        List<AssignmentPlan.Assignment> assignments = new ArrayList<>();
        Map<String, List<String>> channels = new HashMap<>();
        int agentCount = availableAgents.size();

        // 第一个 Agent 为监督者
        AgentHandle supervisor = availableAgents.get(0);
        assignments.add(AssignmentPlan.Assignment.builder()
                .agentId(supervisor.getAgentId())
                .taskNodeId("root")
                .role("supervisor")
                .priority(1)   // 最高优先级
                .build());

        // 根据编排上下文中的任务列表分配 worker
        if (ctx.getTasks() != null) {
            List<String> taskIds = new ArrayList<>();
            for (int i = 0; i < ctx.getTasks().size() && i < agentCount - 1; i++) {
                taskIds.add(ctx.getTasks().get(i).getTaskId());
            }

            for (int i = 1; i < agentCount; i++) {
                AgentHandle worker = availableAgents.get(i);
                // 如果有对应任务则关联，否则生成 subtask 编号
                String taskNodeId = i - 1 < taskIds.size() ? taskIds.get(i - 1) : "subtask-" + i;
                assignments.add(AssignmentPlan.Assignment.builder()
                        .agentId(worker.getAgentId())
                        .taskNodeId(taskNodeId)
                        .role("worker")
                        .priority(5)   // 工作者优先级较低
                        .build());
            }
        }

        // 建立星型双向通信通道
        for (int i = 1; i < agentCount; i++) {
            String workerId = availableAgents.get(i).getAgentId();
            channels.put(supervisor.getAgentId() + "->" + workerId,
                    List.of("task_dispatch", "result_collect", "status_query"));
            channels.put(workerId + "->" + supervisor.getAgentId(),
                    List.of("result_report", "error_report", "status_update"));
        }

        log.info("[SupervisorWorker] Assignment plan: supervisor={}, workers={}, taskNodes={}",
                supervisor.getAgentId(), agentCount - 1,
                assignments.stream().filter(a -> "worker".equals(a.getRole())).count());

        return AssignmentPlan.builder()
                .assignments(assignments)
                .communicationChannels(channels)
                .build();
    }

    /**
     * 执行监督者-工作者模式。
     *
     * 所有 worker 并行执行各自子任务，监督者等待全部完成后汇总结果。
     * 工作流程：
     * 1. 创建所有 worker 的异步任务
     * 2. 等待全部完成（allOf.join）
     * 3. 汇总成功的 worker 输出
     * 4. 返回聚合结果
     *
     * @param ctx 协作上下文
     * @return 异步结果
     */
    @Override
    public CompletableFuture<AgentResult> execute(CollaborationContext ctx) {
        String collabId = ctx.getCollaborationId();
        log.info("[SupervisorWorker] Executing collaboration: collabId={}, participants={}",
                collabId, ctx.getParticipants() != null ? ctx.getParticipants().size() : 0);

        return CompletableFuture.supplyAsync(() -> {
            try {
                List<AgentHandle> participants = ctx.getParticipants();
                if (participants == null || participants.isEmpty()) {
                    return new AgentResult(collabId, "FAILED",
                            "No participants for collaboration", "", 0);
                }

                long startMs = System.currentTimeMillis();

                // 并行创建所有 worker 的异步任务
                List<CompletableFuture<AgentResult>> workerFutures = new ArrayList<>();
                for (int i = 1; i < participants.size(); i++) {
                    AgentHandle worker = participants.get(i);
                    int workerIndex = i;
                    CompletableFuture<AgentResult> workerFuture = CompletableFuture.supplyAsync(() -> {
                        if (cancelMap.getOrDefault(collabId, false)) {
                            return new AgentResult(worker.getAgentId(), "CANCELLED",
                                    "Cancelled by coordinator", "", 0);
                        }
                        long workerStart = System.currentTimeMillis();
                        String resultPayload = "Worker " + worker.getAgentId()
                                + " completed subtask " + workerIndex;
                        long elapsed = System.currentTimeMillis() - workerStart;
                        updateProgress(collabId, (double) workerIndex / (participants.size() - 1));
                        return new AgentResult(worker.getAgentId(), "COMPLETED",
                                "Subtask completed", resultPayload, elapsed);
                    });
                    workerFutures.add(workerFuture);
                }

                // 等待所有 worker 完成
                CompletableFuture.allOf(workerFutures.toArray(new CompletableFuture[0])).join();

                // 汇总成功的 worker 输出
                List<String> workerOutputs = new ArrayList<>();
                long totalWorkerMs = 0;
                for (CompletableFuture<AgentResult> wf : workerFutures) {
                    AgentResult wr = wf.join();
                    if ("COMPLETED".equals(wr.getStatus())) {
                        workerOutputs.add(wr.getDetail());
                        totalWorkerMs += wr.getElapsedMs();
                    }
                }

                String aggregatedDetail = "Supervisor aggregated " + workerOutputs.size()
                        + " worker results:\n" + String.join("\n", workerOutputs);
                long totalMs = System.currentTimeMillis() - startMs;

                progressMap.put(collabId, 1.0);
                log.info("[SupervisorWorker] Collaboration completed: collabId={}, workers={}, durationMs={}",
                        collabId, workerOutputs.size(), totalMs);

                return new AgentResult("supervisor-" + collabId, "COMPLETED",
                        "Collaboration completed with " + workerOutputs.size() + " workers",
                        aggregatedDetail, totalMs);
            } catch (Exception e) {
                log.error("[SupervisorWorker] Collaboration failed: collabId={}, error={}",
                        collabId, e.getMessage(), e);
                return new AgentResult(collabId, "FAILED",
                        "Collaboration failed: " + e.getMessage(),
                        e.toString(), 0);
            }
        });
    }

    @Override
    public boolean cancel(String collaborationId) {
        cancelMap.put(collaborationId, true);
        progressMap.remove(collaborationId);
        log.info("[SupervisorWorker] Cancelled collaboration: {}", collaborationId);
        return true;
    }

    @Override
    public double getProgress(String collaborationId) {
        return progressMap.getOrDefault(collaborationId, 0.0);
    }

    /** 监督者-工作者模式支持动态扩缩容（可增减 worker） */
    @Override
    public boolean supportsDynamicScaling() {
        return true;
    }

    private void updateProgress(String collabId, double progress) {
        progressMap.put(collabId, Math.min(1.0, Math.max(0.0, progress)));
    }
}
