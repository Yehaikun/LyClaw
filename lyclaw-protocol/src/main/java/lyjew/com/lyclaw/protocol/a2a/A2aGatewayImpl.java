package lyjew.com.lyclaw.protocol.a2a;

import lyjew.com.lyclaw.dto.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class A2aGatewayImpl implements A2aGateway {

    private static final Logger log = LoggerFactory.getLogger(A2aGatewayImpl.class);

    @Override
    public CompletableFuture<A2aAgentCard> getAgentCard(String agentUrl) {
        log.info("Getting agent card from: {}", agentUrl);
        return CompletableFuture.completedFuture(
                A2aAgentCard.builder()
                        .agentId("stub-agent")
                        .name("Stub Agent")
                        .description("A2A gateway stub")
                        .url(agentUrl)
                        .version("0.0.1")
                        .build()
        );
    }

    @Override
    public CompletableFuture<AgentResult> sendTask(String agentUrl, A2aTaskSpec task) {
        log.info("Sending task to: {}", agentUrl);
        return CompletableFuture.completedFuture(
                new AgentResult("stub-agent", "COMPLETED", "task completed", "stub detail", 0)
        );
    }

    @Override
    public CompletableFuture<A2aArtifact> getArtifact(String agentUrl, String taskId, String artifactId) {
        log.info("Getting artifact {} from task {}", artifactId, taskId);
        return CompletableFuture.completedFuture(
                A2aArtifact.builder()
                        .artifactId(artifactId)
                        .taskId(taskId)
                        .content("stub content")
                        .mimeType("text/plain")
                        .createdAt(System.currentTimeMillis())
                        .build()
        );
    }

    @Override
    public boolean cancelTask(String agentUrl, String taskId) {
        log.info("Cancelling task {} on {}", taskId, agentUrl);
        return true;
    }

    @Override
    public void registerLocalAgent(A2aAgentCard card) {
        log.info("Registering local agent: {}", card.getAgentId());
    }
}
