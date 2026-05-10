package lyjew.com.lyclaw.agent.external;

import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.agent.AgentTask;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 外部 Agent 适配器 —— 发现和调用外部 A2A Agent。
 *
 * @since 2.0
 */
public interface ExternalAgentAdapter {

    CompletableFuture<AgentCard> discover(String endpointUrl);

    CompletableFuture<AgentResult> sendTask(String agentUrl, AgentTask task, Duration timeout);

    CompletableFuture<TaskStatus> queryTaskStatus(String agentUrl, String taskId);

    CompletableFuture<Boolean> cancelTask(String agentUrl, String taskId);
}
