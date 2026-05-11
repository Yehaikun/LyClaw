package lyjew.com.lyclaw.agent;

import java.time.Instant;

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
