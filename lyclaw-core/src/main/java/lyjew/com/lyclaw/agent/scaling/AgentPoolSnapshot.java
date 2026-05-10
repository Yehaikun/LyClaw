package lyjew.com.lyclaw.agent.scaling;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AgentPoolSnapshot {

    private int totalAgents;
    private int idleAgents;
    private int runningAgents;
    private int queuedTasks;
    private int maxQueueDepth;
    private double targetIdleRatio;
}
