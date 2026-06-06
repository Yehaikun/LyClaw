package lyjew.com.lyclaw.mesh;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 执行事件 —— 记录 Agent 执行过程中的每个步骤。
 *
 * <p>由 {@link AgentInstance} 和 {@link DefaultOrchestrationEngine} 产生，
 * 通过 {@link AgentExecutionStore} 存储，通过 SSE 推送给前端。</p>
 *
 * <p>用户可以通过这些事件实时看到 Agent 在做什么：</p>
 * <ul>
 *   <li>STARTED — Agent 开始执行任务</li>
 *   <li>STAGE — 进入某个阶段（如"代码生成"、"代码审查"）</li>
 *   <li>TOOL_CALL — 调用工具</li>
 *   <li>SUBAGENT_SPAWN — 委托子 Agent</li>
 *   <li>PROGRESS — 进度更新</li>
 *   <li>COMPLETED — 执行完成</li>
 *   <li>FAILED — 执行失败</li>
 * </ul>
 */
public class AgentExecutionEvent {

    private final String eventId;
    private final String agentId;
    private final String taskId;
    private final String parentTaskId;
    private final EventType type;
    private final String stage;
    private final String message;
    private final int progress;
    private final long timestamp;
    private final Map<String, Object> metadata;

    private AgentExecutionEvent(Builder builder) {
        this.eventId = builder.eventId != null ? builder.eventId : UUID.randomUUID().toString().substring(0, 12);
        this.agentId = builder.agentId;
        this.taskId = builder.taskId;
        this.parentTaskId = builder.parentTaskId;
        this.type = builder.type;
        this.stage = builder.stage;
        this.message = builder.message;
        this.progress = builder.progress;
        this.timestamp = builder.timestamp > 0 ? builder.timestamp : System.currentTimeMillis();
        this.metadata = builder.metadata != null ? Map.copyOf(builder.metadata) : Map.of();
    }

    public String getEventId() { return eventId; }
    public String getAgentId() { return agentId; }
    public String getTaskId() { return taskId; }
    public String getParentTaskId() { return parentTaskId; }
    public EventType getType() { return type; }
    public String getStage() { return stage; }
    public String getMessage() { return message; }
    public int getProgress() { return progress; }
    public long getTimestamp() { return timestamp; }
    public Map<String, Object> getMetadata() { return metadata; }

    /** 序列化为 JSON（用于 SSE 推送） */
    public String toJson() {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("eventId", eventId);
            map.put("agentId", agentId);
            map.put("taskId", taskId);
            map.put("type", type.name());
            map.put("stage", stage);
            map.put("message", message);
            map.put("progress", progress);
            map.put("timestamp", timestamp);
            if (metadata != null && !metadata.isEmpty()) map.put("metadata", metadata);
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"error\":\"serialization failed\"}";
        }
    }

    public enum EventType {
        STARTED, STAGE, TOOL_CALL, SUBAGENT_SPAWN, PROGRESS, COMPLETED, FAILED
    }

    // ── 静态工厂 ──

    public static AgentExecutionEvent started(String agentId, String taskId, String message) {
        return builder().agentId(agentId).taskId(taskId).type(EventType.STARTED)
                .progress(0).message(message).build();
    }

    public static AgentExecutionEvent stage(String agentId, String taskId, String stage, String message, int progress) {
        return builder().agentId(agentId).taskId(taskId).type(EventType.STAGE)
                .stage(stage).message(message).progress(progress).build();
    }

    public static AgentExecutionEvent toolCall(String agentId, String taskId, String toolName, String message) {
        return builder().agentId(agentId).taskId(taskId).type(EventType.TOOL_CALL)
                .stage("tool_call").message(message).metadata("toolName", toolName).build();
    }

    public static AgentExecutionEvent subagentSpawn(String agentId, String taskId, String childAgentId, String message) {
        return builder().agentId(agentId).taskId(taskId).type(EventType.SUBAGENT_SPAWN)
                .stage("subagent").message(message).metadata("childAgentId", childAgentId).build();
    }

    public static AgentExecutionEvent progress(String agentId, String taskId, String message, int progress) {
        return builder().agentId(agentId).taskId(taskId).type(EventType.PROGRESS)
                .progress(progress).message(message).build();
    }

    public static AgentExecutionEvent completed(String agentId, String taskId, String message) {
        return builder().agentId(agentId).taskId(taskId).type(EventType.COMPLETED)
                .progress(100).message(message).build();
    }

    public static AgentExecutionEvent failed(String agentId, String taskId, String error) {
        return builder().agentId(agentId).taskId(taskId).type(EventType.FAILED)
                .message(error).build();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String eventId;
        private String agentId;
        private String taskId;
        private String parentTaskId;
        private EventType type;
        private String stage;
        private String message;
        private int progress;
        private long timestamp;
        private Map<String, Object> metadata;

        public Builder eventId(String v) { this.eventId = v; return this; }
        public Builder agentId(String v) { this.agentId = v; return this; }
        public Builder taskId(String v) { this.taskId = v; return this; }
        public Builder parentTaskId(String v) { this.parentTaskId = v; return this; }
        public Builder type(EventType v) { this.type = v; return this; }
        public Builder stage(String v) { this.stage = v; return this; }
        public Builder message(String v) { this.message = v; return this; }
        public Builder progress(int v) { this.progress = v; return this; }
        public Builder timestamp(long v) { this.timestamp = v; return this; }
        public Builder metadata(String key, Object value) {
            if (this.metadata == null) this.metadata = new LinkedHashMap<>();
            this.metadata.put(key, value);
            return this;
        }
        public Builder metadata(Map<String, Object> v) { this.metadata = v; return this; }
        public AgentExecutionEvent build() { return new AgentExecutionEvent(this); }
    }
}
