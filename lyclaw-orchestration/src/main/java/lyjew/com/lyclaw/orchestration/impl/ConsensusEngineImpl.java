package lyjew.com.lyclaw.orchestration.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.communication.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 共识引擎实现。
 *
 * 支持三种多 Agent 协作决策机制：
 * 1. hasConsensus：检查是否达成共识（超过 67% 同意）
 * 2. resolve：解析共识结果，选出最佳 Agent 的回答
 * 3. vote：加权投票，综合能力权重(40%)、历史准确率(35%)、置信度(25%)
 *
 * 内容一致性判断使用 Jaccard 相似度 >= 0.5 作为"同意"阈值。
 */
@Slf4j
@Service
public class ConsensusEngineImpl implements ConsensusEngine {

    /** 共识阈值：至少有 67% 的人同意才算达成共识 */
    private static final double CONSENSUS_THRESHOLD = 0.67;
    /** 加权评分权重：能力匹配 40% */
    private static final double WEIGHT_CAPABILITY = 0.40;
    /** 加权评分权重：历史准确率 35% */
    private static final double WEIGHT_ACCURACY = 0.35;
    /** 加权评分权重：置信度 25% */
    private static final double WEIGHT_CONFIDENCE = 0.25;

    /**
     * 判断各 Agent 的响应是否达成了共识。
     * 使用 Jaccard 相似度 >= 0.5 判定两个回答"同意"。
     * 如果同意人数达到 CONSENSUS_THRESHOLD(67%)，则共识达成。
     *
     * @param responses 各 Agent 的响应列表
     * @return true 表示达成共识
     */
    @Override
    public boolean hasConsensus(List<PeerResponse> responses) {
        // 0 或 1 个响应时，只有 1 个响应算共识
        if (responses == null || responses.size() < 2) {
            return responses != null && responses.size() == 1;
        }

        int requiredVotes = (int) Math.ceil(responses.size() * CONSENSUS_THRESHOLD);

        // 两两比较：如果某个回答获得了足够的同意票，则达成共识
        for (int i = 0; i < responses.size(); i++) {
            int agreementCount = 1;  // 自己同意自己
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

    /**
     * 解析所有响应并生成共识结果。
     * 如果不满足共识条件，仍会选出得分最高的最佳 Agent 作为多数意见。
     *
     * @param responses 各 Agent 响应
     * @param round     当前轮次
     * @return ConsensusResult 包含是否达成共识、最佳决策、同意率等
     */
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

        // 选出得分最高的 Agent（综合能力、准确率、置信度）
        PeerResponse best = responses.stream()
                .max(Comparator.comparingDouble(this::computeScore)
                        .thenComparingDouble(PeerResponse::getConfidence)
                        .thenComparingDouble(PeerResponse::getHistoricalAccuracy))
                .orElse(responses.get(0));

        // 计算有多少 Agent 同意最佳回答
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

    /**
     * 加权投票机制。
     * 综合候选 Agent 的能力权重、历史准确率和当前置信度计算加权分数，
     * 得分最高者当选。结果按得分降序排列。
     *
     * @param candidates 候选 Agent 响应列表
     * @param voters     投票者句柄列表（用于获取更准确的准确率数据）
     * @return VoteResult 包含获胜者和票数分布
     */
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

        // 构建投票者映射
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
            // 优先使用注册表中的准确率，否则用候选者自报的准确率，兜底 0.5
            double accuracy = candidate.getHistoricalAccuracy();
            AgentHandle voterHandle = voterMap.get(candidate.getAgentId());
            if (voterHandle != null) {
                accuracy = voterHandle.getHistoricalAccuracy();
            } else if (accuracy <= 0) {
                accuracy = 0.5;
            }

            // 加权计算：能力*40% + 准确率*35% + 置信度*25%
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

        // 按得分降序排序
        Map<String, Double> sortedDistribution = voteDistribution.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new));

        // 兜底：如果没有任何候选者，取第一个
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

    /**
     * 计算 PeerResponse 的综合得分。
     */
    private double computeScore(PeerResponse response) {
        return computeWeightedScore(
                response.getCapabilityWeight(),
                response.getHistoricalAccuracy(),
                response.getConfidence());
    }

    /**
     * 加权评分公式：能力*0.40 + 准确率*0.35 + 置信度*0.25
     */
    private double computeWeightedScore(double capabilityWeight, double historicalAccuracy, double confidence) {
        return capabilityWeight * WEIGHT_CAPABILITY
                + historicalAccuracy * WEIGHT_ACCURACY
                + confidence * WEIGHT_CONFIDENCE;
    }

    /**
     * 判断两个回答内容是否"同意"。
     * 使用 Jaccard 相似度（交集/并集）>= 0.5 作为阈值。
     * 比较时对文本进行分词处理，仅保留字母数字组成的长度 > 1 的词。
     */
    private boolean contentsAgree(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a.isEmpty() && b.isEmpty()) return true;
        if (a.equals(b)) return true;  // 完全相同的快速通道

        Set<String> wordsA = tokenize(a);
        Set<String> wordsB = tokenize(b);

        if (wordsA.isEmpty() && wordsB.isEmpty()) return true;
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false;

        // 计算 Jaccard 相似度
        Set<String> intersection = new HashSet<>(wordsA);
        intersection.retainAll(wordsB);

        Set<String> union = new HashSet<>(wordsA);
        union.addAll(wordsB);

        double jaccard = (double) intersection.size() / union.size();
        return jaccard >= 0.5;
    }

    /**
     * 将文本分词为单词集合（小写，仅保留字母数字，长度 > 1）。
     */
    private Set<String> tokenize(String text) {
        if (text == null || text.isEmpty()) return Collections.emptySet();
        return Arrays.stream(text.toLowerCase()
                        .replaceAll("[^a-z0-9\\s]", " ")     // 非字母数字替换为空格
                        .split("\\s+"))                       // 按空白分割
                .filter(w -> w.length() > 1)                  // 过滤单字符
                .collect(Collectors.toSet());
    }
}
