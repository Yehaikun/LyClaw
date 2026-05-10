package lyjew.com.lyclaw.orchestration;

import lyjew.com.lyclaw.context.ChatContext;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

public interface Orchestrator {

    Flux<ServerSentEvent<String>> execute(ChatContext context);
    Flux<AgentEvent> executeAgentTask(OrchestrationContext context);
    boolean cancel(String collaborationId);
    double getProgress(String collaborationId);
}
