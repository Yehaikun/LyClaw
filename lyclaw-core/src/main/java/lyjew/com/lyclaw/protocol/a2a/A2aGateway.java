package lyjew.com.lyclaw.protocol.a2a;

import lyjew.com.lyclaw.dto.AgentResult;
import java.util.concurrent.CompletableFuture;

/**
 * A2A 网关 —— Agent-to-Agent 协议的核心接口。
 *
 * <p>遵循 Google A2A 协议规范, 提供 Agent 间的任务委托和结果获取。</p>
 *
 * @since 2.0
 */
public interface A2aGateway {

    CompletableFuture<A2aAgentCard> getAgentCard(String agentUrl);

    CompletableFuture<AgentResult> sendTask(String agentUrl, A2aTaskSpec task);

    CompletableFuture<A2aArtifact> getArtifact(String agentUrl, String taskId, String artifactId);

    boolean cancelTask(String agentUrl, String taskId);

    void registerLocalAgent(A2aAgentCard card);
}
