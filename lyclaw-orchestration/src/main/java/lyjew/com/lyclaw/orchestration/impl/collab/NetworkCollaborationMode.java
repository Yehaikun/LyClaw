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

@Slf4j
@Component
public class NetworkCollaborationMode implements CollaborationMode {

    public static final String MODE_ID = "network";

    private final ConsensusEngine consensusEngine;
    private final ConcurrentHashMap<String, Map<String, Object>> sharedContexts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> progressMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> cancelMap = new ConcurrentHashMap<>();

    public NetworkCollaborationMode(ConsensusEngine consensusEngine) {
        this.consensusEngine = consensusEngine;
    }

    @Override
    public String getModeId() {
        return MODE_ID;
    }

    @Override
    public TopologyType getPreferredTopology() {
        return TopologyType.MESH;
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

        for (int i = 0; i < availableAgents.size(); i++) {
            AgentHandle agent = availableAgents.get(i);
            assignments.add(AssignmentPlan.Assignment.builder()
                    .agentId(agent.getAgentId())
                    .taskNodeId("shared-context")
                    .role("peer")
                    .priority(i + 1)
                    .build());
        }

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

                while (round < maxRounds && !cancelMap.getOrDefault(collabId, false)) {
                    round++;
                    final int currentRound = round;
                    log.info("[NetworkCollab] Round {}/{}: {} peers voting...",
                            round, maxRounds, participants.size());

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

                    CompletableFuture.allOf(responseFutures.toArray(new CompletableFuture[0])).join();
                    allResponses.addAll(roundResponses);

                    updateProgress(collabId, (double) round / maxRounds);

                    if (roundResponses.size() >= 2) {
                        if (consensusEngine.hasConsensus(new ArrayList<>(roundResponses))) {
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
