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
 * 流水线（Pipeline）协作模式。
 *
 * Agent 按顺序串行执行，形成多阶段处理流水线。
 * 每个 Agent 的输出作为下一个 Agent 的输入，类似工厂流水线。
 * 采用层级(Hierarchical)拓扑，数据单向流动（只向下游传递）。
 * 不支持动态扩缩容（流水线阶段数是固定的）。
 */
@Slf4j
@Component
public class PipelineCollaborationMode implements CollaborationMode {

    public static final String MODE_ID = "pipeline";

    private final ConcurrentHashMap<String, Double> progressMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> cancelMap = new ConcurrentHashMap<>();
    /** 保存每个阶段的输出结果 */
    private final ConcurrentHashMap<String, List<String>> stageResults = new ConcurrentHashMap<>();

    @Override
    public String getModeId() {
        return MODE_ID;
    }

    @Override
    public TopologyType getPreferredTopology() {
        return TopologyType.HIERARCHICAL;
    }

    /**
     * 分配流水线角色：每个 Agent 按序分配 stage-1 到 stage-N，
     * 通道仅从前一阶段指向后一阶段（单向数据流）。
     */
    @Override
    public AssignmentPlan assign(List<AgentHandle> availableAgents, OrchestrationContext ctx) {
        if (availableAgents == null || availableAgents.isEmpty()) {
            return AssignmentPlan.builder()
                    .assignments(Collections.emptyList())
                    .communicationChannels(Collections.emptyMap())
                    .build();
        }

        List<AssignmentPlan.Assignment> assignments = new ArrayList<>();
        Map<String, List<String>> channels = new HashMap<>();

        // 每个 Agent 对应流水线的一个阶段
        for (int i = 0; i < availableAgents.size(); i++) {
            AgentHandle agent = availableAgents.get(i);
            assignments.add(AssignmentPlan.Assignment.builder()
                    .agentId(agent.getAgentId())
                    .taskNodeId("stage-" + (i + 1))
                    .role("stage_processor")
                    .priority(i + 1)  // 越靠前优先级越高
                    .build());
        }

        // 建立单向通道：Stage i -> Stage i+1
        for (int i = 0; i < availableAgents.size() - 1; i++) {
            String from = availableAgents.get(i).getAgentId();
            String to = availableAgents.get(i + 1).getAgentId();
            channels.put(from + "->" + to, List.of("output_to_next_stage", "data_handoff"));
        }

        log.info("[PipelineCollab] Assignment: {} stages in pipeline", availableAgents.size());

        return AssignmentPlan.builder()
                .assignments(assignments)
                .communicationChannels(channels)
                .build();
    }

    /**
     * 串行执行流水线各阶段。
     *
     * 从初始输入开始，每个阶段 Agent 处理上游输出并将结果传递给下游。
     * 进度 = 当前阶段序号 / 总阶段数。
     * 支持中游取消，取消时返回已完成的阶段信息。
     *
     * @param ctx 协作上下文
     * @return 异步结果
     */
    @Override
    public CompletableFuture<AgentResult> execute(CollaborationContext ctx) {
        String collabId = ctx.getCollaborationId();
        List<AgentHandle> participants = ctx.getParticipants();

        log.info("[PipelineCollab] Executing pipeline: collabId={}, stages={}",
                collabId, participants != null ? participants.size() : 0);

        return CompletableFuture.supplyAsync(() -> {
            try {
                if (participants == null || participants.isEmpty()) {
                    return new AgentResult(collabId, "FAILED",
                            "No participants for pipeline", "", 0);
                }

                long startMs = System.currentTimeMillis();
                List<String> stageOutputs = new ArrayList<>();
                // 从共享状态获取初始输入
                String previousOutput = String.valueOf(
                        ctx.getSharedState().getOrDefault("initialInput", "pipeline input"));

                // 逐阶段串行处理
                for (int stageIdx = 0; stageIdx < participants.size(); stageIdx++) {
                    if (cancelMap.getOrDefault(collabId, false)) {
                        return new AgentResult(collabId, "CANCELLED",
                                "Pipeline cancelled at stage " + (stageIdx + 1), "", 0);
                    }

                    AgentHandle agent = participants.get(stageIdx);
                    long stageStart = System.currentTimeMillis();

                    // 当前阶段的输入 = 上一阶段的输出
                    final String stageInput = previousOutput;
                    final int stageNum = stageIdx + 1;

                    log.info("[PipelineCollab] Stage {}/{}: agent={}", stageNum, participants.size(), agent.getAgentId());

                    // 生成阶段输出（模拟处理：带上游输入信息）
                    String stageOutput = "Stage " + stageNum + " output by " + agent.getAgentId()
                            + " (input: " + (stageInput.length() > 50
                                    ? stageInput.substring(0, 50) + "..." : stageInput) + ")";

                    long stageElapsed = System.currentTimeMillis() - stageStart;
                    stageOutputs.add(stageOutput);
                    previousOutput = stageOutput;  // 输出传递给下一阶段

                    updateProgress(collabId, (double) stageNum / participants.size());
                    log.info("[PipelineCollab] Stage {} completed in {}ms", stageNum, stageElapsed);
                }

                stageResults.put(collabId, List.copyOf(stageOutputs));

                long totalMs = System.currentTimeMillis() - startMs;
                progressMap.put(collabId, 1.0);

                // 最终输出 = 最后一阶段的输出
                String finalOutput = stageOutputs.isEmpty() ? "pipeline empty" : stageOutputs.get(stageOutputs.size() - 1);
                log.info("[PipelineCollab] Pipeline completed: collabId={}, stages={}, durationMs={}",
                        collabId, participants.size(), totalMs);

                return new AgentResult("pipeline-" + collabId, "COMPLETED",
                        "Pipeline completed in " + participants.size() + " stage(s)",
                        finalOutput, totalMs);
            } catch (Exception e) {
                log.error("[PipelineCollab] Pipeline failed: collabId={}, error={}",
                        collabId, e.getMessage(), e);
                return new AgentResult(collabId, "FAILED",
                        "Pipeline failed: " + e.getMessage(),
                        e.toString(), 0);
            }
        });
    }

    @Override
    public boolean cancel(String collaborationId) {
        cancelMap.put(collaborationId, true);
        progressMap.remove(collaborationId);
        stageResults.remove(collaborationId);
        log.info("[PipelineCollab] Cancelled pipeline: {}", collaborationId);
        return true;
    }

    @Override
    public double getProgress(String collaborationId) {
        return progressMap.getOrDefault(collaborationId, 0.0);
    }

    /** 流水线模式不支持动态扩缩容（阶段数固定） */
    @Override
    public boolean supportsDynamicScaling() {
        return false;
    }

    private void updateProgress(String collabId, double progress) {
        progressMap.put(collabId, Math.min(1.0, Math.max(0.0, progress)));
    }
}
