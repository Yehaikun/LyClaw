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

@Slf4j
@Component
public class MarketCollaborationMode implements CollaborationMode {

    public static final String MODE_ID = "market";

    private final ConsensusEngine consensusEngine;
    private final ConcurrentHashMap<String, VoteResult> auctionResults = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> cancelMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> progressMap = new ConcurrentHashMap<>();

    public MarketCollaborationMode(ConsensusEngine consensusEngine) {
        this.consensusEngine = consensusEngine;
    }

    @Override
    public String getModeId() {
        return MODE_ID;
    }

    @Override
    public TopologyType getPreferredTopology() {
        return TopologyType.STAR;
    }

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

        AgentHandle auctioneer = availableAgents.get(0);
        assignments.add(AssignmentPlan.Assignment.builder()
                .agentId(auctioneer.getAgentId())
                .taskNodeId("auction_root")
                .role("auctioneer")
                .priority(1)
                .build());

        for (int i = 1; i < availableAgents.size(); i++) {
            AgentHandle bidder = availableAgents.get(i);
            assignments.add(AssignmentPlan.Assignment.builder()
                    .agentId(bidder.getAgentId())
                    .taskNodeId("bid_" + i)
                    .role("bidder")
                    .priority(5 + i)
                    .build());
        }

        for (int i = 1; i < availableAgents.size(); i++) {
            String bidderId = availableAgents.get(i).getAgentId();
            channels.put(auctioneer.getAgentId() + "->" + bidderId,
                    List.of("task_broadcast", "result_notification"));
            channels.put(bidderId + "->" + auctioneer.getAgentId(),
                    List.of("bid_submission", "status_report"));
        }

        log.info("[MarketCollab] Assignment: auctioneer={}, bidders={}",
                auctioneer.getAgentId(), availableAgents.size() - 1);

        return AssignmentPlan.builder()
                .assignments(assignments)
                .communicationChannels(channels)
                .build();
    }

    @Override
    public CompletableFuture<AgentResult> execute(CollaborationContext ctx) {
        String collabId = ctx.getCollaborationId();
        List<AgentHandle> participants = ctx.getParticipants();

        log.info("[MarketCollab] Starting auction: collabId={}, bidders={}",
                collabId, participants != null ? participants.size() - 1 : 0);

        return CompletableFuture.supplyAsync(() -> {
            try {
                if (participants == null || participants.size() < 2) {
                    return new AgentResult(collabId, "FAILED",
                            "Need at least 2 participants (1 auctioneer + 1 bidder)", "", 0);
                }

                long startMs = System.currentTimeMillis();
                AgentHandle auctioneer = participants.get(0);
                List<AgentHandle> bidders = participants.subList(1, participants.size());

                updateProgress(collabId, 0.1);
                log.info("[MarketCollab] Phase 1: Collecting bids from {} bidder(s)...", bidders.size());

                List<PeerResponse> bids = Collections.synchronizedList(new ArrayList<>());
                List<CompletableFuture<Void>> bidFutures = new ArrayList<>();

                for (AgentHandle bidder : bidders) {
                    CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                        if (cancelMap.getOrDefault(collabId, false)) return;

                        double capability = bidder.getCapabilities() != null
                                && !bidder.getCapabilities().isEmpty() ? 0.8 : 0.4;
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

                CompletableFuture.allOf(bidFutures.toArray(new CompletableFuture[0])).join();
                updateProgress(collabId, 0.4);

                if (bids.isEmpty()) {
                    return new AgentResult(collabId, "FAILED",
                            "No bids received", "", System.currentTimeMillis() - startMs);
                }

                log.info("[MarketCollab] Phase 2: Voting on {} bids...", bids.size());
                updateProgress(collabId, 0.6);

                VoteResult voteResult = consensusEngine.vote(
                        new ArrayList<>(bids), bidders);
                String winnerId = voteResult.getWinnerAgentId();

                log.info("[MarketCollab] Winner: agentId={}, score={}, votes={}",
                        winnerId, voteResult.getWinnerScore(), voteResult.getTotalVoters());

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

    @Override
    public boolean cancel(String collaborationId) {
        cancelMap.put(collaborationId, true);
        progressMap.remove(collaborationId);
        auctionResults.remove(collaborationId);
        log.info("[MarketCollab] Cancelled auction: {}", collaborationId);
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
