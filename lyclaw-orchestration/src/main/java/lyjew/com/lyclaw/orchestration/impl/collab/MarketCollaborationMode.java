package lyjew.com.lyclaw.orchestration.impl.collab;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.collab.*;
import lyjew.com.lyclaw.agent.communication.ConsensusEngine;
import lyjew.com.lyclaw.agent.communication.PeerResponse;
import lyjew.com.lyclaw.agent.communication.VoteResult;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.orchestration.OrchestrationContext;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 市场（拍卖）协作模式。
 *
 * 模拟拍卖流程：第一个 Agent 充当拍卖师(auctioneer)，其余为竞拍者(bidder)。
 * 竞拍者提交投标(bid)，拍卖师通过共识引擎的加权投票机制选出优胜者。
 * 采用星型(Star)拓扑，拍卖师负责广播任务和收集投标结果。
 * 支持动态扩缩容和取消操作。
 */
@Slf4j
@Component
public class MarketCollaborationMode implements CollaborationMode {

    public static final String MODE_ID = "market";

    private final ConsensusEngine consensusEngine;
    /** 拍卖结果缓存：collabId -> VoteResult */
    private final ConcurrentHashMap<String, VoteResult> auctionResults = new ConcurrentHashMap<>();
    /** 取消标记 */
    private final ConcurrentHashMap<String, Boolean> cancelMap = new ConcurrentHashMap<>();
    /** 进度追踪 */
    private final ConcurrentHashMap<String, Double> progressMap = new ConcurrentHashMap<>();

    public MarketCollaborationMode(ConsensusEngine consensusEngine) {
        this.consensusEngine = consensusEngine;
    }

    /**
     * 获取市场协作模式的唯一标识符。
     *
     * <p>返回固定字符串 "market"，作为该协作模式在系统中的全局唯一 ID。
     * 该标识符用于 CollaborationHub 中的模式注册和查找（如通过 ModeRegistry
     * 按 modeId 获取对应的协作模式实例）、编排上下文（OrchestrationContext）中
     * collaborationModeId 字段的赋值、日志输出中的模式标签，以及前端 API 中
     * 指定协作模式时的参数值。每个 CollaborationMode 实现类必须返回唯一且不可变的 modeId。</p>
     *
     * @return 模式标识符，固定为 "market"
     */
    @Override
    public String getModeId() {
        return MODE_ID;
    }

    /**
     * 获取市场协作模式偏好的网络拓扑类型。
     *
     * <p>返回 TopologyType.STAR（星型拓扑）。在市场（拍卖）模式中，第一个 Agent
     * 担任拍卖师（中心节点），其余 Agent 为竞拍者（叶节点），所有通信以拍卖师为中心：
     * 拍卖师向所有竞拍者广播任务，竞拍者向拍卖师提交投标。星型拓扑天然适合这种
     * 集中式协调场景，消息路由简单高效，无需在全网范围内维护全连接状态。
     * StarAgentChannel 会根据此偏好设置当前拓扑类型为 STAR，从而影响
     * send() 和 broadcast() 等消息路由行为。</p>
     *
     * @return 偏好的拓扑类型，固定为 TopologyType.STAR
     */
    @Override
    public TopologyType getPreferredTopology() {
        return TopologyType.STAR;
    }

    /**
     * 分配 Agent 角色和通信通道。
     * 第一个 Agent 为拍卖师（高优先级），其余为竞拍者。
     * 建立双向通信：拍卖师 -> 竞拍者（广播任务、通知结果）、
     * 竞拍者 -> 拍卖师（提交投标、报告状态）。
     *
     * @param availableAgents 可用 Agent 列表
     * @param ctx             编排上下文
     * @return 分配计划（包含角色分配和通道映射）
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

        // 第一个 Agent 担任拍卖师
        AgentHandle auctioneer = availableAgents.get(0);
        assignments.add(AssignmentPlan.Assignment.builder()
                .agentId(auctioneer.getAgentId())
                .taskNodeId("auction_root")
                .role("auctioneer")
                .priority(1)   // 最高优先级
                .build());

        // 其余 Agent 为竞拍者
        for (int i = 1; i < availableAgents.size(); i++) {
            AgentHandle bidder = availableAgents.get(i);
            assignments.add(AssignmentPlan.Assignment.builder()
                    .agentId(bidder.getAgentId())
                    .taskNodeId("bid_" + i)
                    .role("bidder")
                    .priority(5 + i)  // 较低优先级
                    .build());
        }

        // 建立双向通信通道
        for (int i = 1; i < availableAgents.size(); i++) {
            String bidderId = availableAgents.get(i).getAgentId();
            channels.put(auctioneer.getAgentId() + "->" + bidderId,
                    List.of("task_broadcast", "result_notification"));  // 拍卖师->竞拍者
            channels.put(bidderId + "->" + auctioneer.getAgentId(),
                    List.of("bid_submission", "status_report"));        // 竞拍者->拍卖师
        }

        log.info("[MarketCollab] Assignment: auctioneer={}, bidders={}",
                auctioneer.getAgentId(), availableAgents.size() - 1);

        return AssignmentPlan.builder()
                .assignments(assignments)
                .communicationChannels(channels)
                .build();
    }

    /**
     * 执行拍卖流程（三阶段）：
     *
     * 阶段1（进度0.1-0.4）：收集竞拍 —— 所有竞拍者异步提交投标
     * 阶段2（进度0.4-0.6）：投票表决 —— 共识引擎加权投票选出优胜者
     * 阶段3（进度0.6-1.0）：优胜者执行 —— 记录结果并完成
     *
     * @param ctx 协作上下文
     * @return 包含 AgentResult 的异步 Future
     */
    @Override
    public CompletableFuture<AgentResult> execute(CollaborationContext ctx) {
        String collabId = ctx.getCollaborationId();
        List<AgentHandle> participants = ctx.getParticipants();

        log.info("[MarketCollab] Starting auction: collabId={}, bidders={}",
                collabId, participants != null ? participants.size() - 1 : 0);

        return CompletableFuture.supplyAsync(() -> {
            try {
                // 需要至少 2 个参与者（1 拍卖师 + 1 竞拍者）
                if (participants == null || participants.size() < 2) {
                    return new AgentResult(collabId, "FAILED",
                            "Need at least 2 participants (1 auctioneer + 1 bidder)", "", 0);
                }

                long startMs = System.currentTimeMillis();
                AgentHandle auctioneer = participants.get(0);
                List<AgentHandle> bidders = participants.subList(1, participants.size());

                // === 阶段1：收集投标 ===
                updateProgress(collabId, 0.1);
                log.info("[MarketCollab] Phase 1: Collecting bids from {} bidder(s)...", bidders.size());

                List<PeerResponse> bids = Collections.synchronizedList(new ArrayList<>());
                List<CompletableFuture<Void>> bidFutures = new ArrayList<>();

                // 每个竞拍者异步生成投标，能力权重根据是否有能力列表决定
                for (AgentHandle bidder : bidders) {
                    CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                        if (cancelMap.getOrDefault(collabId, false)) return;

                        // 有能力列表可得更高权重(0.8)，否则较低(0.4)
                        double capability = bidder.getCapabilities() != null
                                && !bidder.getCapabilities().isEmpty() ? 0.8 : 0.4;
                        // 随机生成置信度（0.5-1.0）
                        double confidence = 0.5 + ThreadLocalRandom.current().nextDouble() * 0.5;

                        PeerResponse bid = PeerResponse.builder()
                                .agentId(bidder.getAgentId())
                                .content("Bid from " + bidder.getAgentId()
                                        + " for task: " + ctx.getSharedState().getOrDefault("task", "unknown"))
                                .confidence(confidence)
                                .capabilityWeight(capability)
                                .historicalAccuracy(bidder.getHistoricalAccuracy())
                                .build();
                        bids.add(bid);
                        log.info("[MarketCollab] Bid received: agent={}, confidence={}, capability={}, accuracy={}",
                                bidder.getAgentId(), confidence, capability, bidder.getHistoricalAccuracy());
                    });
                    bidFutures.add(f);
                }

                // 等待所有竞拍者提交完毕
                CompletableFuture.allOf(bidFutures.toArray(new CompletableFuture[0])).join();
                updateProgress(collabId, 0.4);

                if (bids.isEmpty()) {
                    return new AgentResult(collabId, "FAILED",
                            "No bids received", "", System.currentTimeMillis() - startMs);
                }

                // === 阶段2：加权投票 ===
                log.info("[MarketCollab] Phase 2: Voting on {} bids...", bids.size());
                updateProgress(collabId, 0.6);

                // 共识引擎进行加权投票
                VoteResult voteResult = consensusEngine.vote(
                        new ArrayList<>(bids), bidders);
                String winnerId = voteResult.getWinnerAgentId();

                log.info("[MarketCollab] Winner: agentId={}, score={}, votes={}",
                        winnerId, voteResult.getWinnerScore(), voteResult.getTotalVoters());

                // === 阶段3：优胜者执行 ===
                updateProgress(collabId, 0.8);
                log.info("[MarketCollab] Phase 3: Winner {} executing task...", winnerId);

                String winnerOutput = "Market winner " + winnerId + " executed task successfully"
                        + " (highest score: " + String.format("%.2f", voteResult.getWinnerScore())
                        + " among " + bidders.size() + " bidders)";

                auctionResults.put(collabId, voteResult);
                long totalMs = System.currentTimeMillis() - startMs;
                progressMap.put(collabId, 1.0);

                log.info("[MarketCollab] Auction completed: winner={}, bidders={}, durationMs={}",
                        winnerId, bidders.size(), totalMs);

                return new AgentResult("market-" + collabId, "COMPLETED",
                        "Auction completed: winner is " + winnerId
                                + " (score=" + String.format("%.2f", voteResult.getWinnerScore()) + ")",
                        winnerOutput, totalMs);
            } catch (Exception e) {
                log.error("[MarketCollab] Auction failed: collabId={}, error={}",
                        collabId, e.getMessage(), e);
                return new AgentResult(collabId, "FAILED",
                        "Auction failed: " + e.getMessage(),
                        e.toString(), 0);
            }
        });
    }

    /**
     * 取消拍卖，清除进度和结果缓存。
     */
    @Override
    public boolean cancel(String collaborationId) {
        cancelMap.put(collaborationId, true);
        progressMap.remove(collaborationId);
        auctionResults.remove(collaborationId);
        log.info("[MarketCollab] Cancelled auction: {}", collaborationId);
        return true;
    }

    /**
     * @return 拍卖进度（0.0-1.0）
     */
    @Override
    public double getProgress(String collaborationId) {
        return progressMap.getOrDefault(collaborationId, 0.0);
    }

    /** 市场模式支持动态扩缩容 */
    @Override
    public boolean supportsDynamicScaling() {
        return true;
    }

    /** 更新进度值，限制在 [0.0, 1.0] 范围内 */
    private void updateProgress(String collabId, double progress) {
        progressMap.put(collabId, Math.min(1.0, Math.max(0.0, progress)));
    }
}
