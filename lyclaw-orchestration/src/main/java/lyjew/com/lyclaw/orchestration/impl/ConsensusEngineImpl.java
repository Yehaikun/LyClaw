package lyjew.com.lyclaw.orchestration.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.communication.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ConsensusEngineImpl implements ConsensusEngine {

    private static final double CONSENSUS_THRESHOLD = 0.67;
    private static final double WEIGHT_CAPABILITY = 0.40;
    private static final double WEIGHT_ACCURACY = 0.35;
    private static final double WEIGHT_CONFIDENCE = 0.25;

    @Override
    public boolean hasConsensus(List<PeerResponse> responses) {
        if (responses == null || responses.size() < 2) {
            return responses != null && responses.size() == 1;
        }

        int requiredVotes = (int) Math.ceil(responses.size() * CONSENSUS_THRESHOLD);

        for (int i = 0; i < responses.size(); i++) {
            int agreementCount = 1;
            for (int j = 0; j < responses.size(); j++) {
                if (i == j) continue;
                if (contentsAgree(responses.get(i).getContent(), responses.get(j).getContent())) {
                    agreementCount++;
                }
            }
            if (agreementCount >= requiredVotes) {
                log.debug("[ConsensusEngine] Consensus detected: {}/{} agree",
                        agreementCount, responses.size());
                return true;
            }
        }
        return false;
    }

    @Override
    public ConsensusResult resolve(List<PeerResponse> responses, int round) {
        if (responses == null || responses.isEmpty()) {
            return ConsensusResult.builder()
                    .consensusReached(false)
                    .decision("No responses available")
                    .agreementRate(0.0)
                    .roundsTaken(round)
                    .build();
        }

        boolean consensus = hasConsensus(responses);

        PeerResponse best = responses.stream()
                .max(Comparator.comparingDouble(this::computeScore)
                        .thenComparingDouble(PeerResponse::getConfidence)
                        .thenComparingDouble(PeerResponse::getHistoricalAccuracy))
                .orElse(responses.get(0));

        long agreeCount = responses.stream()
                .filter(r -> contentsAgree(r.getContent(), best.getContent()))
                .count();
        double agreementRate = (double) agreeCount / responses.size();

        log.info("[ConsensusEngine] Resolution: consensus={}, agreeRate={}%, bestAgent={}, round={}",
                consensus,
                String.format("%.0f", agreementRate * 100),
                best.getAgentId(), round);

        return ConsensusResult.builder()
                .consensusReached(consensus)
                .decision(best.getContent())
                .agreementRate(agreementRate)
                .roundsTaken(round)
                .majorityAgentId(best.getAgentId())
                .build();
    }

    @Override
    public VoteResult vote(List<PeerResponse> candidates, List<AgentHandle> voters) {
        if (candidates == null || candidates.isEmpty()) {
            return VoteResult.builder()
                    .winnerAgentId("none")
                    .voteDistribution(Collections.emptyMap())
                    .winnerScore(0.0)
                    .totalVoters(0)
                    .build();
        }

        Map<String, AgentHandle> voterMap = new HashMap<>();
        if (voters != null) {
            for (AgentHandle v : voters) {
                voterMap.put(v.getAgentId(), v);
            }
        }

        Map<String, Double> voteDistribution = new LinkedHashMap<>();
        double maxScore = Double.MIN_VALUE;
        String winnerId = null;

        for (PeerResponse candidate : candidates) {
            double accuracy = candidate.getHistoricalAccuracy();
            AgentHandle voterHandle = voterMap.get(candidate.getAgentId());
            if (voterHandle != null) {
                accuracy = voterHandle.getHistoricalAccuracy();
            } else if (accuracy <= 0) {
                accuracy = 0.5;
            }

            double score = computeWeightedScore(
                    candidate.getCapabilityWeight(),
                    accuracy,
                    candidate.getConfidence());

            voteDistribution.put(candidate.getAgentId(), score);
            log.debug("[ConsensusEngine] Vote: agent={}, capability={}, accuracy={}, confidence={}, score={}",
                    candidate.getAgentId(),
                    String.format("%.2f", candidate.getCapabilityWeight()),
                    String.format("%.2f", accuracy),
                    String.format("%.2f", candidate.getConfidence()),
                    String.format("%.4f", score));

            if (score > maxScore) {
                maxScore = score;
                winnerId = candidate.getAgentId();
            }
        }

        Map<String, Double> sortedDistribution = voteDistribution.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new));

        if (winnerId == null) {
            winnerId = candidates.get(0).getAgentId();
            maxScore = computeScore(candidates.get(0));
        }

        log.info("[ConsensusEngine] Vote result: winner={}, score={:.4f}, totalVoters={}",
                winnerId, maxScore, candidates.size());

        return VoteResult.builder()
                .winnerAgentId(winnerId)
                .voteDistribution(sortedDistribution)
                .winnerScore(maxScore)
                .totalVoters(candidates.size())
                .build();
    }

    private double computeScore(PeerResponse response) {
        return computeWeightedScore(
                response.getCapabilityWeight(),
                response.getHistoricalAccuracy(),
                response.getConfidence());
    }

    private double computeWeightedScore(double capabilityWeight, double historicalAccuracy, double confidence) {
        return capabilityWeight * WEIGHT_CAPABILITY
                + historicalAccuracy * WEIGHT_ACCURACY
                + confidence * WEIGHT_CONFIDENCE;
    }

    private boolean contentsAgree(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a.isEmpty() && b.isEmpty()) return true;
        if (a.equals(b)) return true;

        Set<String> wordsA = tokenize(a);
        Set<String> wordsB = tokenize(b);

        if (wordsA.isEmpty() && wordsB.isEmpty()) return true;
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false;

        Set<String> intersection = new HashSet<>(wordsA);
        intersection.retainAll(wordsB);

        Set<String> union = new HashSet<>(wordsA);
        union.addAll(wordsB);

        double jaccard = (double) intersection.size() / union.size();
        return jaccard >= 0.5;
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isEmpty()) return Collections.emptySet();
        return Arrays.stream(text.toLowerCase()
                        .replaceAll("[^a-z0-9\\s]", " ")
                        .split("\\s+"))
                .filter(w -> w.length() > 1)
                .collect(Collectors.toSet());
    }
}
