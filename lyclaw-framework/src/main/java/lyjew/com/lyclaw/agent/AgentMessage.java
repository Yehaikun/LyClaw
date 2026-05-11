package lyjew.com.lyclaw.agent;

import java.time.Instant;

/**
 * 代理消息，封装代理间通信的一条消息。
 *
 * AgentMessage 是 LyClaw 多代理系统中代理间通信的数据载体。每条消息
 * 记录了发送方和接收方的代理标识、消息类型（如"请求"、"响应"、"通知"）、
 * 消息体内容以及精确到毫秒的时间戳。消息对象是不可变的（所有字段为
 * final），确保在并发环境下安全传递。消息类型字段允许接收方根据类型
 * 采取不同的处理策略。
 *
 * @see AgentChannel
 */
public class AgentMessage {

    /** 消息发送方代理的标识 */
    private final String from;
    /** 消息接收方代理的标识 */
    private final String to;
    /** 消息类型，如 "request"、"response"、"notification" */
    private final String type;
    /** 消息内容，通常为 JSON 或纯文本 */
    private final String content;
    /** 消息创建的时间戳 */
    private final Instant timestamp;

    /**
     * 构造一条不可变的代理消息。
     *
     * @param from      发送方代理标识
     * @param to        接收方代理标识
     * @param type      消息类型
     * @param content   消息内容
     * @param timestamp 消息时间戳
     */
    public AgentMessage(String from, String to, String type,
                        String content, Instant timestamp) {
        this.from = from;
        this.to = to;
        this.type = type;
        this.content = content;
        this.timestamp = timestamp;
    }

    /** @return 发送方代理标识 */
    public String getFrom() { return from; }
    /** @return 接收方代理标识 */
    public String getTo() { return to; }
    /** @return 消息类型 */
    public String getType() { return type; }
    /** @return 消息内容 */
    public String getContent() { return content; }
    /** @return 消息创建时间戳 */
    public Instant getTimestamp() { return timestamp; }
}
