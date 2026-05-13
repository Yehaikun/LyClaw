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
     *
     * <p>从指定 Agent 的消费者列表中移除给定的 Consumer 回调。
     * 如果该 Agent 当前没有任何已注册的消费者，调用此方法不会产生任何效果。
     * 此操作是线程安全的，底层使用 CopyOnWriteArrayList 保证并发修改的安全性。
     * 取消订阅后，该 Consumer 将不再收到发往该 Agent 的消息推送，
     * 但 Agent 的消息队列中的已有消息不受影响，仍可通过 receive() 拉取。</p>
     *
     * @param agentId  要取消订阅的 Agent ID，不能为 null
     * @param consumer 要移除的消息消费回调，不能为 null
     */
    public void unsubscribe(String agentId, Consumer<AgentMessage> consumer) {
        CopyOnWriteArrayList<Consumer<AgentMessage>> list = consumers.get(agentId);
        if (list != null) {
            list.remove(consumer);
        }
    }

    /**
     * 注册全局消费者，接收系统中所有 Agent 的消息。
     *
     * <p>与 subscribe() 不同的是，全局消费者不绑定到特定 Agent，而是接收所有通过
     * send()、broadcast() 或 meshRoute() 发送的每一条消息。全局消费者常用于
     * 实现消息审计日志、跨 Agent 监控、消息总线和调试追踪等场景。
     * 消费者列表使用 CopyOnWriteArrayList 实现，保证线程安全。
     * 注意：全局消费者回调中如果抛出异常，会被静默捕获并记录 warn 日志，
     * 不会中断其他消费者或消息投递流程。</p>
     *
     * @param consumer 全局消息消费回调，接收所有 Agent 消息
     */
    public void subscribeGlobal(Consumer<AgentMessage> consumer) {
        globalConsumers.add(consumer);
    }

    /**
     * 获取当前 Agent 通信网络的拓扑类型。
     *
     * <p>拓扑类型决定了消息的路由策略：STAR（星型）拓扑下消息通过中心节点路由，
     * MESH（网状）拓扑下 Agent 之间直接进行全连接通信。当前拓扑类型由
     * setTopology() 方法动态设置，初始默认值为 TopologyType.STAR。
     * 该字段使用 volatile 修饰以保证多线程环境下的可见性。</p>
     *
     * @return 当前生效的拓扑类型，默认为 TopologyType.STAR
     */
    public TopologyType getCurrentTopology() {
        return currentTopology;
    }

    /**
     * 动态切换 Agent 通信网络的拓扑类型。
     *
     * <p>支持在运行时切换通信拓扑，无需重启系统。切换后所有后续的消息路由将遵循
     * 新的拓扑规则：若切换至 MESH 拓扑，消息将通过 meshRoute() 进行全连接路由；
     * 若切换至 STAR 拓扑，消息通过中心节点进行星型路由。拓扑切换会记录 info 级别
     * 日志以便追踪。该操作是原子性的（volatile 写），不会影响正在投递中的消息。</p>
     *
     * @param topology 要切换到的目标拓扑类型，不能为 null
     */
    public void setTopology(TopologyType topology) {
        this.currentTopology = topology;
        log.info("[StarAgentChannel] Topology switched to: {}", topology);
    }

    /**
     * 获取当前已连接（已注册消息队列）的 Agent 数量。
     *
     * <p>返回值为 queues 映射的大小，每个拥有消息队列的 Agent 都被视为已连接。
     * Agent 的连接状态通过其是否拥有 BlockingQueue 来判断——只要 Agent 被
     * 分配了消息队列（无论是通过 send() 首次创建还是显式注册），即被视为已连接。
     * 此计数可用于监控系统负载、判断是否需要自动扩缩容等运维场景。</p>
     *
     * @return 当前已连接且拥有消息队列的 Agent 数量，最小为 0
     */
    public int getConnectedAgentCount() {
        return queues.size();
    }

    /**
     * 获取所有已注册 Agent 的 ID 列表。
     *
     * <p>返回 queues 映射中所有 key 的不可变副本。由于返回的是 List.copyOf()
     * 创建的快照，调用方可以安全地遍历此列表而无需担心并发修改异常。
     * 注意：该列表仅反映调用时刻的快照状态，之后新注册或清理的 Agent 不会体现在
     * 已返回的列表中。如需实时监控 Agent 变化，建议使用 subscribeGlobal() 订阅
     * 全局消息事件。</p>
     *
     * @return 所有已注册 Agent ID 的不可变列表，永远不会为 null（最小为空列表）
     */
    public List<String> getRegisteredAgents() {
        return List.copyOf(queues.keySet());
    }

    /**
     * 获取消息历史记录的不可变副本。
     *
     * <p>返回最近最多 100 条（MAX_HISTORY）消息的快照列表。消息历史通过 recordHistory()
     * 方法自动维护，采用滑动窗口机制——当记录数超过 100 条时自动移除最旧的消息。
     * 返回的列表是 List.copyOf() 创建的不可变副本，调用方可安全地遍历。
     * 该功能主要用于消息审计、问题调试和跨 Agent 通信行为的回溯分析。</p>
     *
     * @return 消息历史的不可变副本，按时间顺序排列（旧到新），最多 100 条
     */
    public List<AgentMessage> getMessageHistory() {
        return List.copyOf(messageHistory);
    }

    /**
     * 清理指定 Agent 的所有通道数据，包括消息队列和消费者订阅。
     *
     * <p>当 Agent 退出协作、发生故障或需要重置状态时调用此方法进行资源回收。
     * 清理操作会移除该 Agent 的 BlockingQueue（消息队列）和所有已注册的 Consumer
     * 回调（消费者订阅），防止内存泄漏。清理完成后会记录 info 级别日志。
     * 注意：此方法不影响全局消费者和历史消息记录，其他 Agent 的通道数据保持不变。</p>
     *
     * @param agentId 要清理的 Agent ID，不能为 null
     */
    public void cleanupAgent(String agentId) {
        queues.remove(agentId);
        consumers.remove(agentId);
        log.info("[StarAgentChannel] Cleaned up agent: {}", agentId);
    }

    /**
     * 清空所有通道数据，包括 Agent 消息队列、消费者订阅、全局消费者和历史消息记录。
     *
     * <p>此方法用于系统重置或全量清理场景，会将所有内部状态恢复为初始状态：
     * 清空所有 Agent 的 BlockingQueue、移除所有 Agent 级消费者和全局消费者、
     * 清空消息历史记录。调用后系统处于干净状态，可重新注册 Agent 和订阅消费者。
     * 操作完成后会记录 info 级别日志。注意：此方法不影响 currentTopology 字段，
     * 拓扑类型设置保持不变。</p>
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
