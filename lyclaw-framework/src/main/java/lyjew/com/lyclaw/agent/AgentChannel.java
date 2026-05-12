package lyjew.com.lyclaw.agent;

/**
 * 代理通道接口，定义代理之间一对一消息通信的通道抽象。
 *
 * 在 LyClaw 多代理架构中，AgentChannel 是代理间点对点通信的基础设施。
 * 每个代理实例可拥有多条通道，各自连接到不同的对等代理。通道负责将消息
 * 从发送方可靠地投递到接收方，具体的传输协议（如 HTTP、gRPC、内存队列）
 * 由实现类决定。通道只关心消息的发送与接收，不参与路由或广播逻辑。
 *
 * @see AgentMessage
 */
public interface AgentChannel {

    /**
     * 向通道对端发送一条消息。
     *
     * @param message 待发送的代理消息，包含发送方、接收方、消息类型、内容及时间戳
     */
    void send(AgentMessage message);

    /**
     * 从指定代理接收消息。
     *
     * @param agentId 发送方代理的唯一标识，用于从通道中读取该代理发出的消息
     */
    void receive(String agentId);

    /**
     * 向所有已注册的代理广播消息（排除发送者自身）。
     *
     * @param message 要广播的代理消息
     */
    void broadcast(AgentMessage message);
}
