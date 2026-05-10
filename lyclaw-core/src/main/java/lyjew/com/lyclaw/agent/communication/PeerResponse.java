package lyjew.com.lyclaw.agent.communication;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PeerResponse {
    private String agentId;
    private String content;
    private double confidence;
    private double capabilityWeight;
    private double historicalAccuracy;
}
