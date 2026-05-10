package lyjew.com.lyclaw.agent;

/**
 * Agent 通信渠道接口 —— Agent 之间通过 Channel 发送消息，支持点对点和广播。
 *
 * <p>每个 Agent 可以有多个 Channel（如内部内存队列、外部 MQ 等）。
 * AgentCoordinator 通过 Channel 在 Agent 之间传递 AgentMessage。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see AgentMessage
 */
public interface AgentChannel {

    void send(AgentMessage message);

    void receive(String agentId);
}