package lyjew.com.lyclaw.orchestration.impl.collab;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.collab.*;
import lyjew.com.lyclaw.agent.communication.ConsensusEngine;
import lyjew.com.lyclaw.agent.communication.ConsensusResult;
import lyjew.com.lyclaw.agent.communication.PeerResponse;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.orchestration.OrchestrationContext;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 网络（对等共识）协作模式。
 *
 * 所有 Agent 地位平等(peer)，通过多轮投票达成共识。
 * 采用全连接网状(Mesh)拓扑，每个 Agent 都可以与任意其他 Agent 通信。
 * 每轮收集所有 Agent 的响应，检查是否达成共识（>= 67% 同意），
 * 超时或达到最大轮次后，即使未完全达成共识也会返回最佳结果。
 */
@Slf4j
@Component
public class NetworkCollaborationMode implements CollaborationMode {

    public static final String MODE_ID = "network";

    private final ConsensusEngine consensusEngine;
    /** 共享上下文（可用于跨 Agent 数据共享） */
    private final ConcurrentHashMap<String, Map<String, Object>> sharedContexts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> progressMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> cancelMap = new ConcurrentHashMap<>();

    public NetworkCollaborationMode(ConsensusEngine consensusEngine) {
        this.consensusEngine = consensusEngine;
    }

    /**
     * 获取网络协作模式的唯一标识符。
     *
     * <p>返回固定字符串 "network"，作为该协作模式在系统中的全局唯一 ID。
     * 该标识符用于 CollaborationHub 中的模式注册和查找（如通过 ModeRegistry
     * 按 modeId 获取对应的协作模式实例）、编排上下文（OrchestrationContext）中
     * collaborationModeId 字段的赋值、日志输出中的模式标签，以及前端 API 中
     * 指定协作模式时的参数值。每个 CollaborationMode 实现类必须返回唯一且不可变的 modeId。</p>
     *
     * @return 模式标识符，固定为 "network"
     */
    @Override
    public String getModeId() {
        return MODE_ID;
    }

    /**
     * 获取网络协作模式偏好的网络拓扑类型。
     *
     * <p>返回 TopologyType.MESH（全连接网状拓扑）。在网络（对等共识）模式中，
     * 所有 Agent 地位平等（peer），每个 Agent 都可以与任意其他 Agent 直接通信。
     * 采用 MESH 拓扑意味着建立 N*(N-1)/2 条双向通道（完全图），每对 Agent 之间
     * 都有独立的通信链路，支持 context_share（上下文共享）、vote（投票）和
     * message（消息）三种通信类型。StarAgentChannel 会根据此偏好设置当前拓扑类型
     * 为 MESH，从而启用 meshRoute() 方法进行全对等消息路由。</p>
     *
     * @return 偏好的拓扑类型，固定为 TopologyType.MESH
     */
    @Override
    public TopologyType getPreferredTopology() {
        return TopologyType.MESH;
    }

    /**
     * 全连接网状分配：每个 Agent 都是 peer，两两之间建立双向通信通道。
     * 通道数量 = N*(N-1)/2 对，即 N 个 Agent 的完全图。
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

        // 每个 Agent 分配 peer 角色
        for (int i = 0; i < availableAgents.size(); i++) {
            AgentHandle agent = availableAgents.get(i);
            assignments.add(AssignmentPlan.Assignment.builder()
                    .agentId(agent.getAgentId())
                    .taskNodeId("shared-context")  // 共享同一个上下文节点
                    .role("peer")
                    .priority(i + 1)
                    .build());
        }

        // 建立全连接：每对 Agent 之间建立双向通道
        for (int i = 0; i < availableAgents.size(); i++) {
            for (int j = i + 1; j < availableAgents.size(); j++) {
                String ai = availableAgents.get(i).getAgentId();
                String aj = availableAgents.get(j).getAgentId();
                channels.put(ai + "->" + aj, List.of("context_share", "vote", "message"));
                channels.put(aj + "->" + ai, List.of("context_share", "vote", "message"));
            }
        }

        log.info("[NetworkCollab] Assignment: {} peers in full mesh ({} channels)",
                availableAgents.size(), channels.size());

        return AssignmentPlan.builder()
                .assignments(assignments)
                .communicationChannels(channels)
                .build();
    }

    /**
     * 执行多轮网络共识流程。
     *
     * 每轮收集所有 peer 的响应，通过共识引擎判断是否达成共识。
     * 如果达成共识则提前结束；否则继续下一轮，直到达到最大轮次。
     * 最终即使不完全达成共识也会返回最佳决策。
     *
     * @param ctx 协作上下文
     * @return 异步结果
     */
    @Override
    public CompletableFuture<AgentResult> execute(CollaborationContext ctx) {
        String collabId = ctx.getCollaborationId();
        List<AgentHandle> participants = ctx.getParticipants();
        int maxRounds = ctx.getMaxRounds() > 0 ? ctx.getMaxRounds() : 3;

        log.info("[NetworkCollab] Executing collaboration: collabId={}, peers={}, maxRounds={}",
                collabId, participants != null ? participants.size() : 0, maxRounds);

        return CompletableFuture.supplyAsync(() -> {
            try {
                if (participants == null || participants.isEmpty()) {
                    return new AgentResult(collabId, "FAILED",
                            "No participants for network collaboration", "", 0);
                }

                long startMs = System.currentTimeMillis();
                int round = 0;
                List<PeerResponse> allResponses = new ArrayList<>();
                ConsensusResult finalConsensus = null;

                // 多轮共识循环：直到达成共识或达到最大轮次
                while (round < maxRounds && !cancelMap.getOrDefault(collabId, false)) {
                    round++;
                    final int currentRound = round;
                    log.info("[NetworkCollab] Round {}/{}: {} peers voting...",
                            round, maxRounds, participants.size());

                    // 收集本轮所有 Agent 的响应（并发）
                    List<PeerResponse> roundResponses = Collections.synchronizedList(new ArrayList<>());
                    List<CompletableFuture<Void>> responseFutures = new ArrayList<>();

                    for (int i = 0; i < participants.size(); i++) {
                        AgentHandle agent = participants.get(i);
                        int peerIndex = i;
                        CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                            if (cancelMap.getOrDefault(collabId, false)) return;

                            PeerResponse response = PeerResponse.builder()
                                    .agentId(agent.getAgentId())
                                    .content("Peer " + agent.getAgentId()
                                            + " round " + currentRound + " response for: "
                                            + ctx.getSharedState().getOrDefault("query", "unknown task"))
                                    .confidence(0.5 + ThreadLocalRandom.current().nextDouble() * 0.5)
                                    .capabilityWeight(agent.getCapabilities() != null
                                            && !agent.getCapabilities().isEmpty() ? 0.8 : 0.5)
                                    .historicalAccuracy(agent.getHistoricalAccuracy())
                                    .build();
                            roundResponses.add(response);
                        });
                        responseFutures.add(f);
                    }

                    // 等待所有 Agent 本轮响应完成
                    CompletableFuture.allOf(responseFutures.toArray(new CompletableFuture[0])).join();
                    allResponses.addAll(roundResponses);

                    updateProgress(collabId, (double) round / maxRounds);

                    // 检查本轮是否达成共识（至少需要2个响应）
                    if (roundResponses.size() >= 2) {
                        if (consensusEngine.hasConsensus(new ArrayList<>(roundResponses))) {
                            // 达成共识，提前结束
                            finalConsensus = consensusEngine.resolve(
                                    new ArrayList<>(roundResponses), round);
                            log.info("[NetworkCollab] Consensus reached at round {}: decision={}, agreementRate={}",
                                    round, finalConsensus.getDecision(),
                                    finalConsensus.getAgreementRate());
                            break;
                        }
                        log.info("[NetworkCollab] Round {}: no consensus yet, continuing...", round);
                    }
                }

                // 未达成共识时，用全部历史响应做最终解析
                if (finalConsensus == null && !allResponses.isEmpty()) {
                    finalConsensus = consensusEngine.resolve(allResponses, round);
                    log.info("[NetworkCollab] Final resolution (no full consensus): decision={}",
                            finalConsensus.getDecision());
                }

                long elapsed = System.currentTimeMillis() - startMs;
                progressMap.put(collabId, 1.0);

                String detail = finalConsensus != null
                        ? "Consensus: " + finalConsensus.getDecision()
                        + " (agreement=" + String.format("%.0f%%", finalConsensus.getAgreementRate() * 100)
                        + ", rounds=" + finalConsensus.getRoundsTaken() + ")"
                        : "No consensus reached after " + round + " rounds";

                log.info("[NetworkCollab] Completed: collabId={}, rounds={}, consensus={}, durationMs={}",
                        collabId, round, finalConsensus != null && finalConsensus.isConsensusReached(), elapsed);

                return new AgentResult("network-" + collabId,
                        finalConsensus != null && finalConsensus.isConsensusReached()
                                ? "COMPLETED" : "COMPLETED_NO_CONSENSUS",
                        "Network collaboration finished in " + round + " round(s)",
                        detail, elapsed);
            } catch (Exception e) {
                log.error("[NetworkCollab] Collaboration failed: collabId={}, error={}",
                        collabId, e.getMessage(), e);
                return new AgentResult(collabId, "FAILED",
                        "Network collaboration failed: " + e.getMessage(),
                        e.toString(), 0);
            }
        });
    }

    @Override
    public boolean cancel(String collaborationId) {
        cancelMap.put(collaborationId, true);
        progressMap.remove(collaborationId);
        log.info("[NetworkCollab] Cancelled collaboration: {}", collaborationId);
        return true;
    }

    @Override
    public double getProgress(String collaborationId) {
        return progressMap.getOrDefault(collaborationId, 0.0);
    }

    @Override
    public boolean supportsDynamicScaling() {
        return true;
    }

    private void updateProgress(String collabId, double progress) {
        progressMap.put(collabId, Math.min(1.0, Math.max(0.0, progress)));
    }
}
