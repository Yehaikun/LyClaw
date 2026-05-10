package lyjew.com.lyclaw.orchestration.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.AgentRegistry;
import lyjew.com.lyclaw.agent.AgentState;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DefaultAgentRegistry implements AgentRegistry {

    private final ConcurrentHashMap<String, AgentHandle> agentStore = new ConcurrentHashMap<>();

    @Override
    public void register(AgentHandle agent) {
        if (agent == null || agent.getAgentId() == null) {
            log.warn("[AgentRegistry] Cannot register null agent or agent with null ID");
            return;
        }

        AgentHandle existing = agentStore.put(agent.getAgentId(), agent);
        if (existing != null) {
            log.info("[AgentRegistry] Replaced agent: agentId={}, oldState={}, newState={}",
                    agent.getAgentId(), existing.getState(), agent.getState());
        } else {
            log.info("[AgentRegistry] Registered agent: agentId={}, name={}, capabilities={}",
                    agent.getAgentId(), agent.getName(), agent.getCapabilities());
        }
    }

    @Override
    public Optional<AgentHandle> lookup(String agentId) {
        if (agentId == null || agentId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(agentStore.get(agentId));
    }

    @Override
    public List<AgentHandle> findByCapability(String capability) {
        if (capability == null || capability.isEmpty()) {
            return Collections.emptyList();
        }

        return agentStore.values().stream()
                .filter(a -> a.getCapabilities() != null
                        && a.getCapabilities().stream()
                                .anyMatch(c -> c.equalsIgnoreCase(capability)))
                .sorted(Comparator.comparingLong(AgentHandle::getCreatedAt))
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public List<AgentHandle> findByState(AgentState state) {
        if (state == null) {
            return Collections.emptyList();
        }

        return agentStore.values().stream()
                .filter(a -> a.getState() == state)
                .sorted(Comparator.comparingLong(AgentHandle::getCreatedAt))
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public List<AgentHandle> findAvailable(List<String> requiredCapabilities) {
        if (requiredCapabilities == null || requiredCapabilities.isEmpty()) {
            return findByState(AgentState.IDLE);
        }

        return agentStore.values().stream()
                .filter(a -> a.getState() == AgentState.IDLE)
                .filter(a -> {
                    if (a.getCapabilities() == null || a.getCapabilities().isEmpty()) {
                        return false;
                    }
                    Set<String> caps = a.getCapabilities().stream()
                            .map(String::toLowerCase)
                            .collect(Collectors.toSet());
                    return requiredCapabilities.stream()
                            .map(String::toLowerCase)
                            .allMatch(caps::contains);
                })
                .sorted(Comparator.comparingDouble(AgentHandle::getHistoricalAccuracy).reversed())
                .collect(Collectors.toUnmodifiableList());
    }

    public int size() {
        return agentStore.size();
    }

    public AgentHandle unregister(String agentId) {
        AgentHandle removed = agentStore.remove(agentId);
        if (removed != null) {
            log.info("[AgentRegistry] Unregistered agent: agentId={}, name={}", agentId, removed.getName());
        }
        return removed;
    }

    public List<AgentHandle> listAll() {
        return List.copyOf(agentStore.values());
    }

    public Map<AgentState, Long> getStateDistribution() {
        return agentStore.values().stream()
                .collect(Collectors.groupingBy(AgentHandle::getState, Collectors.counting()));
    }
}
