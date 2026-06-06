package lyjew.com.lyclaw.mesh;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 消息 —— Agent Mesh 中所有通信的唯一载体。
 *
 * <p>整个 Agent Mesh 架构基于<strong>消息驱动</strong>模式：Agent 之间不直接调用方法，
 * 而是通过发送不可变的 AgentMessage 进行通信。消息通过 correlationId 关联
 * 请求和响应，通过 traceId 串联跨 Agent 的调用链。</p>
 *
 * <p>使用 {@link #builder()} 创建消息实例：</p>
 * <pre>{@code
 * AgentMessage msg = AgentMessage.builder()
 *     .to("code-reviewer")
 *     .type(MessageType.REQUEST)
 *     .payload("{\"task\": \"review PR #42\"}")
 *     .correlationId("call-001")
 *     .ttlMs(300_000)
 *     .build();
 * }</pre>
 */
public class AgentMessage {

    // ── 路由字段 ──
    private final String messageId;
    private final String from;
    private final String to;
    private final String capability;     // 按能力路由时使用

    // ── 调用链追踪 ──
    private final String correlationId;
    private final String traceId;
    private final String parentSpanId;

    // ── 消息内容 ──
    private final MessageType type;
    private final String payload;
    private final Map<String, Object> metadata;

    // ── 控制字段 ──
    private final long timestamp;
    private final long ttlMs;
    private final int priority;

    // ── 流式字段 ──
    private final Long streamSeq;
    private final Boolean streamEnd;

    private AgentMessage(Builder builder) {
        this.messageId = builder.messageId != null ? builder.messageId : UUID.randomUUID().toString();
        this.from = builder.from;
        this.to = builder.to;
        this.capability = builder.capability;
        this.correlationId = builder.correlationId;
        this.traceId = builder.traceId;
        this.parentSpanId = builder.parentSpanId;
        this.type = builder.type != null ? builder.type : MessageType.REQUEST;
        this.payload = builder.payload;
        this.metadata = builder.metadata != null ? Map.copyOf(builder.metadata) : Map.of();
        this.timestamp = builder.timestamp > 0 ? builder.timestamp : System.currentTimeMillis();
        this.ttlMs = builder.ttlMs;
        this.priority = builder.priority;
        this.streamSeq = builder.streamSeq;
        this.streamEnd = builder.streamEnd;
    }

    // ── Getters ──

    public String getMessageId() { return messageId; }
    public String getFrom() { return from; }
    public String getTo() { return to; }
    public String getCapability() { return capability; }
    public String getCorrelationId() { return correlationId; }
    public String getTraceId() { return traceId; }
    public String getParentSpanId() { return parentSpanId; }
    public MessageType getType() { return type; }
    public String getPayload() { return payload; }
    public Map<String, Object> getMetadata() { return metadata; }
    public long getTimestamp() { return timestamp; }
    public long getTtlMs() { return ttlMs; }
    public int getPriority() { return priority; }
    public Long getStreamSeq() { return streamSeq; }
    public Boolean getStreamEnd() { return streamEnd; }

    /** 是否已过期（超过 TTL） */
    public boolean isExpired() {
        return ttlMs > 0 && (System.currentTimeMillis() - timestamp) > ttlMs;
    }

    /** 是否为流式消息 */
    public boolean isStream() {
        return type == MessageType.STREAM;
    }

    /** 是否为请求 */
    public boolean isRequest() {
        return type == MessageType.REQUEST;
    }

    /** 是否为响应 */
    public boolean isResponse() {
        return type == MessageType.RESPONSE;
    }

    /** 是否为错误 */
    public boolean isError() {
        return type == MessageType.ERROR;
    }

    // ── 便捷工厂 ──

    /** 创建 RESPONSE 回复消息 */
    public static AgentMessage responseTo(AgentMessage request, String payload) {
        return AgentMessage.builder()
                .type(MessageType.RESPONSE)
                .to(request.getFrom())
                .from(request.getTo())
                .correlationId(request.getCorrelationId())
                .traceId(request.getTraceId())
                .payload(payload)
                .build();
    }

    /** 创建 ERROR 回复消息 */
    public static AgentMessage errorTo(AgentMessage request, String error) {
        return AgentMessage.builder()
                .type(MessageType.ERROR)
                .to(request.getFrom())
                .from(request.getTo())
                .correlationId(request.getCorrelationId())
                .traceId(request.getTraceId())
                .payload(error)
                .build();
    }

    // ── Builder ──

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String messageId;
        private String from;
        private String to;
        private String capability;
        private String correlationId;
        private String traceId;
        private String parentSpanId;
        private MessageType type;
        private String payload;
        private Map<String, Object> metadata;
        private long timestamp;
        private long ttlMs;
        private int priority;
        private Long streamSeq;
        private Boolean streamEnd;

        public Builder messageId(String v) { this.messageId = v; return this; }
        public Builder from(String v) { this.from = v; return this; }
        public Builder to(String v) { this.to = v; return this; }
        public Builder capability(String v) { this.capability = v; return this; }
        public Builder correlationId(String v) { this.correlationId = v; return this; }
        public Builder traceId(String v) { this.traceId = v; return this; }
        public Builder parentSpanId(String v) { this.parentSpanId = v; return this; }
        public Builder type(MessageType v) { this.type = v; return this; }
        public Builder payload(String v) { this.payload = v; return this; }
        public Builder metadata(Map<String, Object> v) { this.metadata = v; return this; }
        public Builder metadata(String key, Object value) {
            if (this.metadata == null) this.metadata = new LinkedHashMap<>();
            this.metadata.put(key, value);
            return this;
        }
        public Builder timestamp(long v) { this.timestamp = v; return this; }
        public Builder ttlMs(long v) { this.ttlMs = v; return this; }
        public Builder priority(int v) { this.priority = v; return this; }
        public Builder streamSeq(Long v) { this.streamSeq = v; return this; }
        public Builder streamEnd(Boolean v) { this.streamEnd = v; return this; }

        public AgentMessage build() { return new AgentMessage(this); }
    }

    @Override
    public String toString() {
        return "AgentMessage{" + type + " " + from + "→" + to
                + (correlationId != null ? " cid=" + correlationId : "")
                + (capability != null ? " cap=" + capability : "")
                + "}";
    }
}
