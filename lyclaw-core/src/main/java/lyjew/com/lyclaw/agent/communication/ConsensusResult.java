package lyjew.com.lyclaw.agent.communication;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ConsensusResult {
    private boolean consensusReached;
    private String decision;
    private double agreementRate;
    private int roundsTaken;
    private String majorityAgentId;
}
