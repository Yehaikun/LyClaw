package lyjew.com.lyclaw.agent.impl;

import lyjew.com.lyclaw.agent.AgentChannel;
import lyjew.com.lyclaw.agent.AgentMessage;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * 星型拓扑 Agent 通信频道 —— 中心化消息中转。
 *
 * <p>所有 Agent 通过此 Channel 收发消息。有三种消息路由方式：
 * <ul>
 *   <li>点对点：send(agentId, message) → 直接写入目标 Agent 的消息队列</li>
 *   <li>广播：broadcast(message) → 所有 Agent 都能收到</li>
 *   <li>订阅：subscribe(agentId, consumer) → Agent 注册自己的消费者</li>
 * </ul>
 * </p>
 *
 * <p><b>设计动机</b>：如果不使用中心化的 Channel，Agent 之间需要互相知道
 * 对方的地址和通信方式，形成网状拓扑，耦合度高。星型拓扑将所有 Agent
 * 连接到中心 Channel，新增 Agent 只需注册到 Channel，零改动现有 Agent。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see AgentChannel
 * @see AgentMessage
 */
@Component
public class StarAgentChannel implements AgentChannel {

    /**
     * Agent 的消息队列映射 —— key 是 agentId，value 是阻塞队列。
     *
     * <p>send() 时往目标 agent 的队列投递消息。
     * receive() 时从自己的队列取出消息。</p>
     */
    private final ConcurrentHashMap<String, BlockingQueue<AgentMessage>> queues = new ConcurrentHashMap<>();

    /**
     * Agent 的消息消费者映射 —— key 是 agentId，value 是消费者列表。
     *
     * <p>subscribe() 时注册。send() 时除了写入队列，也会调用消费者的 accept()。</p>
     */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<AgentMessage>>> consumers = new ConcurrentHashMap<>();

    /**
     * 全局广播订阅者列表。
     */
    private final CopyOnWriteArrayList<Consumer<AgentMessage>> globalConsumers = new CopyOnWriteArrayList<>();

    /**
     * 发送消息给指定 Agent。
     *
     * <p>消息会被写入目标 Agent 的阻塞队列，同时通知其消费者。</p>
     *
     * @param message 要发送的消息
     */
    @Override
    public void send(AgentMessage message) {
        // 1. 写入目标 Agent 的消息队列
        if (message.getTo() != null) {
            BlockingQueue<AgentMessage> queue = queues
                    .computeIfAbsent(message.getTo(), k -> new LinkedBlockingQueue<>());
            queue.offer(message);

            // 2. 通知目标 Agent 的消费者
            CopyOnWriteArrayList<Consumer<AgentMessage>> list = consumers.get(message.getTo());
            if (list != null) {
                for (Consumer<AgentMessage> consumer : list) {
                    consumer.accept(message);
                }
            }
        }

        // 3. 通知全局广播订阅者
        for (Consumer<AgentMessage> consumer : globalConsumers) {
            consumer.accept(message);
        }
    }

    /**
     * 接收消息（从自己的消息队列中取出一条）。
     *
     * @param agentId Agent ID
     */
    @Override
    public void receive(String agentId) {
        BlockingQueue<AgentMessage> queue = queues.get(agentId);
        if (queue != null) {
            // poll() 非阻塞取出消息
            AgentMessage message = queue.poll();
            if (message != null) {
                // 消息已取出，由调用方自行处理
            }
        }
    }

    /**
     * 广播消息给所有 Agent。
     *
     * @param message 要广播的消息
     */
    public void broadcast(AgentMessage message) {
        // 遍历所有队列，将消息写入每个 Agent 的队列
        for (BlockingQueue<AgentMessage> queue : queues.values()) {
            queue.offer(message);
        }
        // 通知所有消费者
        for (Consumer<AgentMessage> consumer : globalConsumers) {
            consumer.accept(message);
        }
    }

    /**
     * 订阅消息。注册消息消费者，当有消息到达时自动被调用。
     *
     * @param agentId  要订阅的 Agent ID
     * @param consumer 消息消费者
     */
    public void subscribe(String agentId, Consumer<AgentMessage> consumer) {
        consumers.computeIfAbsent(agentId, k -> new CopyOnWriteArrayList<>())
                .add(consumer);
    }
}