package lyjew.com.lyclaw.agent;

@FunctionalInterface
public interface AgentRegistrationListener {
    void onAgentEvent(AgentRegistrationEvent event);
}
