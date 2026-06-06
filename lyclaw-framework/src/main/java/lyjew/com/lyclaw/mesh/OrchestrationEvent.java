package lyjew.com.lyclaw.mesh;

/**
 * 编排事件 —— 编排引擎执行过程中的进度事件。
 *
 * <p>用于流式编排场景 {@link OrchestrationEngine#executeStream(OrchestrationSpec)}，
 * 让调用方可以实时了解编排进度。</p>
 */
public class OrchestrationEvent {

    private final EventType type;
    private final OrchestrationSpec spec;
    private final OrchestrationResult result;
    private final String stage;
    private final String agentId;
    private final String message;
    private final long timestamp;

    private OrchestrationEvent(EventType type, OrchestrationSpec spec,
                                OrchestrationResult result, String stage,
                                String agentId, String message) {
        this.type = type;
        this.spec = spec;
        this.result = result;
        this.stage = stage;
        this.agentId = agentId;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
    }

    public EventType getType() { return type; }
    public OrchestrationSpec getSpec() { return spec; }
    public OrchestrationResult getResult() { return result; }
    public String getStage() { return stage; }
    public String getAgentId() { return agentId; }
    public String getMessage() { return message; }
    public long getTimestamp() { return timestamp; }

    public static OrchestrationEvent started(OrchestrationSpec spec) {
        return new OrchestrationEvent(EventType.STARTED, spec, null, "start", null,
                "Starting " + spec.getPattern() + " orchestration");
    }

    public static OrchestrationEvent stage(OrchestrationSpec spec, String stage, String agentId) {
        return new OrchestrationEvent(EventType.STAGE, spec, null, stage, agentId, null);
    }

    public static OrchestrationEvent agentCompleted(OrchestrationSpec spec,
                                                     String agentId, String result) {
        return new OrchestrationEvent(EventType.AGENT_COMPLETED, spec, null,
                "agent_completed", agentId, result);
    }

    public static OrchestrationEvent completed(OrchestrationResult result, long durationMs) {
        return new OrchestrationEvent(EventType.COMPLETED, null, result, "completed",
                null, "Completed in " + durationMs + "ms");
    }

    public static OrchestrationEvent failed(OrchestrationSpec spec, String error) {
        return new OrchestrationEvent(EventType.FAILED, spec, null, "failed", null, error);
    }

    public enum EventType {
        STARTED,
        STAGE,
        AGENT_COMPLETED,
        COMPLETED,
        FAILED
    }
}
