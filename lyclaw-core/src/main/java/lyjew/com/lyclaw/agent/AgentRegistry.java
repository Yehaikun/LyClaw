package lyjew.com.lyclaw.agent;

import java.util.List;
import java.util.Optional;

/**
 * Agent 注册中心 —— 管理所有 Agent 实例的注册和发现。
 *
 * @since 2.0
 */
public interface AgentRegistry {

    void register(AgentHandle agent);

    Optional<AgentHandle> lookup(String agentId);

    List<AgentHandle> findByCapability(String capability);

    List<AgentHandle> findByState(AgentState state);

    List<AgentHandle> findAvailable(List<String> requiredCapabilities);
}
