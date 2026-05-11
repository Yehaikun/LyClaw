package lyjew.com.lyclaw.agent;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AgentHandle {
    private String agentId;
    private String name;
    private AgentState state;
    private List<String> capabilities;
    private long createdAt;
    private double historicalAccuracy;
}
