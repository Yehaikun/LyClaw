package lyjew.com.lyclaw.orchestration.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.agent.AgentChannel;
import lyjew.com.lyclaw.agent.AgentMessage;
import lyjew.com.lyclaw.agent.collab.TopologyType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

@Slf4j
@Component
public class StarAgentChannel implements AgentChannel {

    private final ConcurrentHashMap<String, BlockingQueue<AgentMessage>> queues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<AgentMessage>>> consumers = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<AgentMessage>> globalConsumers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<AgentMessage> messageHistory = new CopyOnWriteArrayList<>();
    private static final int MAX_HISTORY = 100;

    private volatile TopologyType currentTopology = TopologyType.STAR;

    @Override
    public void send(AgentMessage message) {
        recordHistory(message);

        if (message.getTo() != null) {
            BlockingQueue<AgentMessage> queue = queues
                    .computeIfAbsent(message.getTo(), k -> new LinkedBlockingQueue<>());
            queue.offer(message);

            CopyOnWriteArrayList<Consumer<AgentMessage>> list = consumers.get(message.getTo());
            if (list != null) {
                for (Consumer<AgentMessage> consumer : list) {
                    try {
                        consumer.accept(message);
                    } catch (Exception e) {
                        log.warn("[StarAgentChannel] Consumer error for agent {}: {}",
                                message.getTo(), e.getMessage());
                    }
                }
            }
        }

        for (Consumer<AgentMessage> consumer : globalConsumers) {
            try {
                consumer.accept(message);
            } catch (Exception e) {
                log.warn("[StarAgentChannel] Global consumer error: {}", e.getMessage());
            }
        }
    }

    @Override
    public void receive(String agentId) {
        BlockingQueue<AgentMessage> queue = queues.get(agentId);
        if (queue != null) {
            AgentMessage message = queue.poll();
            if (message != null && log.isDebugEnabled()) {
                log.debug("[StarAgentChannel] Agent {} received message from {}: type={}",
                        agentId, message.getFrom(), message.getType());
            }
        }
    }

    public void broadcast(AgentMessage message) {
        recordHistory(message);

        for (Map.Entry<String, BlockingQueue<AgentMessage>> entry : queues.entrySet()) {
            if (message.getFrom() != null && message.getFrom().equals(entry.getKey())) {
                continue;
            }
            entry.getValue().offer(message);
        }

        for (Consumer<AgentMessage> consumer : globalConsumers) {
            try {
                consumer.accept(message);
            } catch (Exception e) {
                log.warn("[StarAgentChannel] Global consumer error on broadcast: {}", e.getMessage());
            }
        }

        log.debug("[StarAgentChannel] Broadcast to {} agents: type={}",
                queues.size(), message.getType());
    }

    public void meshRoute(AgentMessage message) {
        if (message.getFrom() == null) return;

        recordHistory(message);

        for (Map.Entry<String, BlockingQueue<AgentMessage>> entry : queues.entrySet()) {
            if (entry.getKey().equals(message.getFrom())) continue;
            entry.getValue().offer(message);

            CopyOnWriteArrayList<Consumer<AgentMessage>> list = consumers.get(entry.getKey());
            if (list != null) {
                for (Consumer<AgentMessage> consumer : list) {
                    try {
                        consumer.accept(message);
                    } catch (Exception e) {
                        log.warn("[StarAgentChannel] Mesh consumer error: {}", e.getMessage());
                    }
                }
            }
        }
    }

    public void subscribe(String agentId, Consumer<AgentMessage> consumer) {
        consumers.computeIfAbsent(agentId, k -> new CopyOnWriteArrayList<>())
                .add(consumer);
        log.debug("[StarAgentChannel] Agent {} subscribed (total subscribers: {})",
                agentId,
                consumers.get(agentId) != null ? consumers.get(agentId).size() : 0);
    }

    public void unsubscribe(String agentId, Consumer<AgentMessage> consumer) {
        CopyOnWriteArrayList<Consumer<AgentMessage>> list = consumers.get(agentId);
        if (list != null) {
            list.remove(consumer);
        }
    }

    public void subscribeGlobal(Consumer<AgentMessage> consumer) {
        globalConsumers.add(consumer);
    }

    public TopologyType getCurrentTopology() {
        return currentTopology;
    }

    public void setTopology(TopologyType topology) {
        this.currentTopology = topology;
        log.info("[StarAgentChannel] Topology switched to: {}", topology);
    }

    public int getConnectedAgentCount() {
        return queues.size();
    }

    public List<String> getRegisteredAgents() {
        return List.copyOf(queues.keySet());
    }

    public List<AgentMessage> getMessageHistory() {
        return List.copyOf(messageHistory);
    }

    public void cleanupAgent(String agentId) {
        queues.remove(agentId);
        consumers.remove(agentId);
        log.info("[StarAgentChannel] Cleaned up agent: {}", agentId);
    }

    public void clearAll() {
        queues.clear();
        consumers.clear();
        globalConsumers.clear();
        messageHistory.clear();
        log.info("[StarAgentChannel] All data cleared");
    }

    private void recordHistory(AgentMessage message) {
        messageHistory.add(message);
        while (messageHistory.size() > MAX_HISTORY) {
            messageHistory.remove(0);
        }
    }
}
