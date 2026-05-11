package lyjew.com.lyclaw.agent.collab;

import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.orchestration.OrchestrationContext;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface CollaborationMode {

    String getModeId();
    TopologyType getPreferredTopology();
    AssignmentPlan assign(List<AgentHandle> availableAgents, OrchestrationContext ctx);
    CompletableFuture<AgentResult> execute(CollaborationContext ctx);
    boolean cancel(String collaborationId);
    double getProgress(String collaborationId);
    boolean supportsDynamicScaling();
}
