package lyjew.com.lyclaw.action.agent;

import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.AgentHandle.HealthStatus;
import lyjew.com.lyclaw.agent.AgentRegistrationEvent;
import lyjew.com.lyclaw.agent.AgentRegistrationEvent.Type;
import lyjew.com.lyclaw.agent.AgentRegistrationListener;
import lyjew.com.lyclaw.agent.AgentRegistry;
import lyjew.com.lyclaw.agent.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Component
public class DefaultAgentRegistry implements AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentRegistry.class);
    private static final long HEARTBEAT_TIMEOUT_SECONDS = 60;

    private final ConcurrentHashMap<String, AgentHandle> agents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> capabilityIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastHeartbeat = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<AgentRegistrationListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void register(AgentHandle agent) {
        if (agent.getAgentId() == null || agent.getAgentId().isEmpty()) {
            throw new IllegalArgumentException("agentId must not be empty");
        }
        agents.put(agent.getAgentId(), agent);
        indexCapabilities(agent);
        recordHeartbeat(agent.getAgentId());
        log.info("Agent registered: {} ({})", agent.getAgentId(), agent.getName());
        notifyListeners(new AgentRegistrationEvent(agent.getAgentId(), agent, Type.REGISTERED, null, agent.getState()));
    }

    @Override
    public void unregister(String agentId) {
        AgentHandle removed = agents.remove(agentId);
        if (removed != null) {
            deindexCapabilities(removed);
            lastHeartbeat.remove(agentId);
            log.info("Agent unregistered: {}", agentId);
            notifyListeners(new AgentRegistrationEvent(agentId, removed, Type.UNREGISTERED, removed.getState(), AgentState.UNREGISTERED));
        }
    }

    @Override
    public Optional<AgentHandle> lookup(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    @Override
    public List<AgentHandle> findByCapability(String capability) {
        Set<String> ids = capabilityIndex.getOrDefault(capability, Collections.emptySet());
        return ids.stream()
                .map(agents::get)
                .filter(h -> h != null)
                .collect(Collectors.toList());
    }

    @Override
    public List<AgentHandle> findByState(AgentState state) {
        return agents.values().stream()
                .filter(h -> h.getState() == state)
                .collect(Collectors.toList());
    }

    @Override
    public List<AgentHandle> findAvailable(List<String> requiredCapabilities) {
        return agents.values().stream()
                .filter(h -> h.getState() == AgentState.IDLE || h.getState() == AgentState.RUNNING)
                .filter(h -> hasAllCapabilities(h, requiredCapabilities))
                .filter(h -> getHealth(h.getAgentId()) != HealthStatus.DOWN)
                .collect(Collectors.toList());
    }

    @Override
    public List<AgentHandle> getAllAgents() {
        return new ArrayList<>(agents.values());
    }

    @Override
    public void updateState(String agentId, AgentState newState) {
        agents.computeIfPresent(agentId, (id, handle) -> {
            AgentState oldState = handle.getState();
            handle.setState(newState);
            if (newState == AgentState.RUNNING || newState == AgentState.COMPLETED) {
                handle.setLastActiveAt(LocalDateTime.now());
            }
            notifyListeners(new AgentRegistrationEvent(agentId, handle, Type.STATE_CHANGED, oldState, newState));
            return handle;
        });
    }

    @Override
    public void recordHeartbeat(String agentId) {
        lastHeartbeat.put(agentId, System.currentTimeMillis());
    }

    @Override
    public HealthStatus getHealth(String agentId) {
        Long lastBeat = lastHeartbeat.get(agentId);
        if (lastBeat == null) return HealthStatus.UNKNOWN;
        long elapsed = (System.currentTimeMillis() - lastBeat) / 1000;
        if (elapsed < HEARTBEAT_TIMEOUT_SECONDS) return HealthStatus.UP;
        if (elapsed < HEARTBEAT_TIMEOUT_SECONDS * 3) return HealthStatus.DEGRADED;
        return HealthStatus.DOWN;
    }

    @Override
    public void addListener(AgentRegistrationListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(AgentRegistrationListener listener) {
        listeners.remove(listener);
    }

    public void performHealthCheck() {
        for (String agentId : agents.keySet()) {
            HealthStatus health = getHealth(agentId);
            AgentHandle handle = agents.get(agentId);
            if (handle != null) {
                HealthStatus prev = handle.getHealth();
                handle.setHealth(health);
                if (prev != health) {
                    notifyListeners(new AgentRegistrationEvent(agentId, handle, Type.HEALTH_CHANGED, null, null));
                    if (health == HealthStatus.DOWN) {
                        log.warn("Agent {} health is DOWN, marking as DEGRADED", agentId);
                        if (handle.getState() == AgentState.IDLE || handle.getState() == AgentState.RUNNING) {
                            updateState(agentId, AgentState.DEGRADED);
                        }
                    }
                }
            }
        }
    }

    public int getAgentCount() {
        return agents.size();
    }

    private void indexCapabilities(AgentHandle agent) {
        if (agent.getCapabilities() != null) {
            for (String cap : agent.getCapabilities()) {
                capabilityIndex.computeIfAbsent(cap, k -> ConcurrentHashMap.newKeySet()).add(agent.getAgentId());
            }
        }
    }

    private void deindexCapabilities(AgentHandle agent) {
        if (agent.getCapabilities() != null) {
            for (String cap : agent.getCapabilities()) {
                Set<String> ids = capabilityIndex.get(cap);
                if (ids != null) {
                    ids.remove(agent.getAgentId());
                    if (ids.isEmpty()) {
                        capabilityIndex.remove(cap);
                    }
                }
            }
        }
    }

    private boolean hasAllCapabilities(AgentHandle agent, List<String> required) {
        if (required == null || required.isEmpty()) return true;
        if (agent.getCapabilities() == null) return false;
        return new HashSet<>(agent.getCapabilities()).containsAll(required);
    }

    private void notifyListeners(AgentRegistrationEvent event) {
        for (AgentRegistrationListener listener : listeners) {
            try {
                listener.onAgentEvent(event);
            } catch (Exception e) {
                log.warn("AgentRegistrationListener threw exception", e);
            }
        }
    }
}
