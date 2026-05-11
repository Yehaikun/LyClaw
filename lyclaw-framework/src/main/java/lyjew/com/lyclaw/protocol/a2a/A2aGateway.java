package lyjew.com.lyclaw.protocol.a2a;

import lyjew.com.lyclaw.dto.AgentResult;

import java.util.concurrent.CompletableFuture;

public interface A2aGateway {

    CompletableFuture<A2aAgentCard> getAgentCard(String agentUrl);
    CompletableFuture<AgentResult> sendTask(String agentUrl, A2aTaskSpec task);
    CompletableFuture<A2aArtifact> getArtifact(String agentUrl, String taskId, String artifactId);
    boolean cancelTask(String agentUrl, String taskId);
    void registerLocalAgent(A2aAgentCard card);
}
