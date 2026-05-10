package lyjew.com.lyclaw.agent;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.event.Event;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface AgentCoordinator {

    CompletableFuture<AgentResult> dispatch(ChatContext context, AgentTask task);
    boolean cancel(String agentId);
    AgentState getState(String agentId);
    List<AgentChannel> getChannels(String agentId);
    void broadcast(Event event);
}
