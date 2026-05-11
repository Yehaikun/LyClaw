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

/**
 * 星型拓扑 Agent 通信通道。
 *
 * 以星型结构管理 Agent 之间的消息传递。每个 Agent 有一个 BlockingQueue 接收消息，
 * 同时支持消费者（Consumer）回调机制实现实时推送。
 * 支持三种路由模式：点对点发送(send)、广播(broadcast)、网状路由(meshRoute)。
 * 消息历史记录最近 100 条，用于调试和审计。
 */
@Slf4j
@Component
public class StarAgentChannel implements AgentChannel {

    /** Agent 消息队列：agentId -> BlockingQueue */
    private final ConcurrentHashMap<String, BlockingQueue<AgentMessage>> queues = new ConcurrentHashMap<>();
    /** Agent 消费者列表：agentId -> 回调列表 */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<AgentMessage>>> consumers = new ConcurrentHashMap<>();
    /** 全局消费者：接收所有消息 */
    private final CopyOnWriteArrayList<Consumer<AgentMessage>> globalConsumers = new CopyOnWriteArrayList<>();
    /** 消息历史记录（最近 100 条） */
    private final CopyOnWriteArrayList<AgentMessage> messageHistory = new CopyOnWriteArrayList<>();
    private static final int MAX_HISTORY = 100;

    /** 当前拓扑类型（默认星型），可通过 setTopology 切换 */
    private volatile TopologyType currentTopology = TopologyType.STAR;

    /**
     * 发送消息。支持两种投递方式：
     * 1. 定向投递：如果 message.to 非空，放入目标 Agent 的队列并通知其消费者
     * 2. 全局投递：通知所有全局消费者
     *
     * @param message 要发送的 Agent 消息
     */
    @Override
    public void send(AgentMessage message) {
        recordHistory(message);

        // 定向投递：放入目标队列并逐一通知消费者
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

        // 通知所有全局消费者
        for (Consumer<AgentMessage> consumer : globalConsumers) {
            try {
                consumer.accept(message);
            } catch (Exception e) {
                log.warn("[StarAgentChannel] Global consumer error: {}", e.getMessage());
            }
        }
    }

    /**
     * 接收消息（拉取模式）。从 Agent 的队列中取出队首消息。
     * 注意：这是非阻塞的 poll 操作。
     *
     * @param agentId 接收者 Agent ID
     */
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

    /**
     * 广播消息到所有已注册 Agent（排除发送者自身）。
     * 同时通知全局消费者。
     *
     * @param message 要广播的消息
     */
    public void broadcast(AgentMessage message) {
        recordHistory(message);

        // 遍历所有 Agent 队列，排除发送者
        for (Map.Entry<String, BlockingQueue<AgentMessage>> entry : queues.entrySet()) {
            if (message.getFrom() != null && message.getFrom().equals(entry.getKey())) {
                continue;  // 不向发送者自身广播
            }
            entry.getValue().offer(message);
        }

        // 通知全局消费者
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

    /**
     * 网状路由：将消息发送给除发送者外的所有 Agent，并通知各消费者的回调。
     * 用于 MESH 拓扑下的全连接通信。
     *
     * @param message 要路由的消息
     */
    public void meshRoute(AgentMessage message) {
        if (message.getFrom() == null) return;

        recordHistory(message);

        // 所有 Agent 都收到，除了发送者自己
        for (Map.Entry<String, BlockingQueue<AgentMessage>> entry : queues.entrySet()) {
            if (entry.getKey().equals(message.getFrom())) continue;
            entry.getValue().offer(message);

            // 同时触发消费者的实时回调
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

    /**
     * 为指定 Agent 订阅消息消费者（实时推送）。
     *
     * @param agentId  Agent ID
     * @param consumer 消息消费回调
     */
    public void subscribe(String agentId, Consumer<AgentMessage> consumer) {
        consumers.computeIfAbsent(agentId, k -> new CopyOnWriteArrayList<>())
                .add(consumer);
        log.debug("[StarAgentChannel] Agent {} subscribed (total subscribers: {})",
                agentId,
                consumers.get(agentId) != null ? consumers.get(agentId).size() : 0);
    }

    /**
     * 取消指定 Agent 的消费者订阅。
     */
    public void unsubscribe(String agentId, Consumer<AgentMessage> consumer) {
        CopyOnWriteArrayList<Consumer<AgentMessage>> list = consumers.get(agentId);
        if (list != null) {
            list.remove(consumer);
        }
    }

    /**
     * 注册全局消费者，接收所有消息。
     */
    public void subscribeGlobal(Consumer<AgentMessage> consumer) {
        globalConsumers.add(consumer);
    }

    /** @return 当前拓扑类型 */
    public TopologyType getCurrentTopology() {
        return currentTopology;
    }

    /**
     * 动态切换拓扑类型。
     */
    public void setTopology(TopologyType topology) {
        this.currentTopology = topology;
        log.info("[StarAgentChannel] Topology switched to: {}", topology);
    }

    /** @return 当前已连接的 Agent 数量 */
    public int getConnectedAgentCount() {
        return queues.size();
    }

    /** @return 已注册 Agent ID 列表 */
    public List<String> getRegisteredAgents() {
        return List.copyOf(queues.keySet());
    }

    /** @return 消息历史记录的不可变副本 */
    public List<AgentMessage> getMessageHistory() {
        return List.copyOf(messageHistory);
    }

    /**
     * 清理指定 Agent 的所有通道数据。
     */
    public void cleanupAgent(String agentId) {
        queues.remove(agentId);
        consumers.remove(agentId);
        log.info("[StarAgentChannel] Cleaned up agent: {}", agentId);
    }

    /**
     * 清空所有通道数据（队列、消费者、历史）。
     */
    public void clearAll() {
        queues.clear();
        consumers.clear();
        globalConsumers.clear();
        messageHistory.clear();
        log.info("[StarAgentChannel] All data cleared");
    }

    /**
     * 记录消息到历史，保持最多 MAX_HISTORY 条。
     */
    private void recordHistory(AgentMessage message) {
        messageHistory.add(message);
        // 滑动窗口：超出上限时移除最旧的消息
        while (messageHistory.size() > MAX_HISTORY) {
            messageHistory.remove(0);
        }
    }
}
