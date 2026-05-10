package lyjew.com.lyclaw.agent;

public interface AgentChannel {

    void send(AgentMessage message);
    void receive(String agentId);
}
