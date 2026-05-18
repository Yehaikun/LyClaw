package lyjew.com.lyclaw.agent.communication;

import lyjew.com.lyclaw.agent.AgentMessage;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import reactor.core.publisher.Flux;

/**
 * Agent 间通信协议 SPI——定义 Agent 之间的消息传递机制。
 *
 * <p>默认实现 {@code InJvmProtocol} 在同一 JVM 内直接调用。
 * 用户可替换为 RabbitMQ、Kafka、gRPC、Redis pub/sub 等分布式协议。
 *
 * <p>A2A 协议通过 {@link #discoverAgents()} 扩展，允许跨框架 Agent 发现。
 */
public interface AgentCommProtocol {
    /**
     * 向目标 Agent 发送消息并接收响应流。
     *
     * @param targetAgentName 目标 Agent 名称
     * @param message 发送的消息
     * @return 来自目标 Agent 的响应消息流
     */
    Flux<AgentMessage> send(String targetAgentName, AgentMessage message);

    /**
     * 注册消息接收器，当有消息到达指定 Agent 时回调。
     */
    void registerReceiver(String agentName, Consumer<AgentMessage> receiver);

    /** 是否支持 A2A 协议发现 */
    default boolean supportsA2A() { return false; }

    /** 发现远程 Agent（A2A 协议），默认空列表 */
    default List<AgentDefinition> discoverAgents() { return Collections.emptyList(); }
}
