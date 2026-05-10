package lyjew.com.lyclaw.agent;

import lyjew.com.lyclaw.dto.AgentResult;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 生命周期管理器 —— 管理 Agent 的创建、调度、暂停、恢复和终止。
 *
 * <p>状态机: CREATED → INITIALIZING → IDLE → RUNNING ⇄ WAITING
 *          RUNNING → PAUSED → RUNNING | → COMPLETED/FAILED/CANCELLED → TERMINATED</p>
 *
 * @since 2.0
 */
public interface AgentLifecycle {

    CompletableFuture<AgentHandle> create(AgentSpec spec);

    CompletableFuture<AgentResult> schedule(String agentId, AgentTask task);

    boolean pause(String agentId);

    boolean resume(String agentId);

    boolean terminate(String agentId);

    AgentState getState(String agentId);
}
