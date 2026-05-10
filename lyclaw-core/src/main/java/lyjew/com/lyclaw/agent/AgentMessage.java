package lyjew.com.lyclaw.agent;

import java.time.Instant;

/**
 * Agent 通信消息体 —— 包含发送方、接收方、消息类型、内容和时间戳。
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class AgentMessage {

    private final String from;
    private final String to;
    private final String type;
    private final String content;
    private final Instant timestamp;

    public AgentMessage(String from, String to, String type,
                        String content, Instant timestamp) {
        this.from = from;
        this.to = to;
        this.type = type;
        this.content = content;
        this.timestamp = timestamp;
    }

    public String getFrom() { return from; }
    public String getTo() { return to; }
    public String getType() { return type; }
    public String getContent() { return content; }
    public Instant getTimestamp() { return timestamp; }
}