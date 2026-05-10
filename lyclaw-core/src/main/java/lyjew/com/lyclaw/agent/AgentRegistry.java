package lyjew.com.lyclaw.agent;

import java.util.List;
import java.util.Optional;

public interface AgentRegistry {

    void register(AgentHandle agent);
    Optional<AgentHandle> lookup(String agentId);
    List<AgentHandle> findByCapability(String capability);
    List<AgentHandle> findByState(AgentState state);
    List<AgentHandle> findAvailable(List<String> requiredCapabilities);
}
