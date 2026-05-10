package lyjew.com.lyclaw.agent.external;

import lyjew.com.lyclaw.agent.AgentTask;
import lyjew.com.lyclaw.dto.AgentResult;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public interface ExternalAgentAdapter {

    CompletableFuture<AgentCard> discover(String endpointUrl);
    CompletableFuture<AgentResult> sendTask(String agentUrl, AgentTask task, Duration timeout);
    CompletableFuture<TaskStatus> queryTaskStatus(String agentUrl, String taskId);
    CompletableFuture<Boolean> cancelTask(String agentUrl, String taskId);
}
