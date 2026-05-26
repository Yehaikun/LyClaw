package lyjew.com.lyclaw.agent;

import java.util.List;
import java.util.Optional;

public interface AgentRegistry {

    void register(AgentHandle agent);

    void unregister(String agentId);

    Optional<AgentHandle> lookup(String agentId);

    List<AgentHandle> findByCapability(String capability);

    List<AgentHandle> findByState(AgentState state);

    List<AgentHandle> findAvailable(List<String> requiredCapabilities);

    List<AgentHandle> getAllAgents();

    void updateState(String agentId, AgentState state);

    void recordHeartbeat(String agentId);

    AgentHandle.HealthStatus getHealth(String agentId);

    void addListener(AgentRegistrationListener listener);

    void removeListener(AgentRegistrationListener listener);
}
