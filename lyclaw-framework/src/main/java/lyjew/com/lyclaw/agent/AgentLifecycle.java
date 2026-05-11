package lyjew.com.lyclaw.agent;

import lyjew.com.lyclaw.dto.AgentResult;

import java.util.concurrent.CompletableFuture;

public interface AgentLifecycle {

    CompletableFuture<AgentHandle> create(AgentSpec spec);
    CompletableFuture<AgentResult> schedule(String agentId, AgentTask task);
    boolean pause(String agentId);
    boolean resume(String agentId);
    boolean terminate(String agentId);
    AgentState getState(String agentId);
}
