package lyjew.com.lyclaw.agent;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.event.Event;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 协调器接口 —— 负责 Agent 的创建、调度、取消、状态查询和事件广播。
 *
 * <p>AgentCoordinator 是引擎中多 Agent 协作的核心管理者。
 * 接收上层（Engine / TaskPlan）的 AgentTask，分派给合适的 Agent 执行，
 * 协调多个 Agent 之间的通信和状态同步。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see AgentChannel
 * @see AgentState
 */
public interface AgentCoordinator {

    CompletableFuture<AgentResult> dispatch(ChatContext context, AgentTask task);

    boolean cancel(String agentId);

    AgentState getState(String agentId);

    List<AgentChannel> getChannels(String agentId);

    void broadcast(Event event);
}