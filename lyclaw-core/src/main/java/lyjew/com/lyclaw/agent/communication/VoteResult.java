package lyjew.com.lyclaw.agent.communication;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class VoteResult {
    private String winnerAgentId;
    private Map<String, Double> voteDistribution;
    private double winnerScore;
    private int totalVoters;
}
