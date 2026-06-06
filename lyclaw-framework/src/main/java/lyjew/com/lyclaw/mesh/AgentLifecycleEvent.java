package lyjew.com.lyclaw.mesh;

/**
 * Agent 生命周期事件 —— 状态转换时由 AgentMesh 事件总线发布。
 *
 * <p>监听者可以通过 {@link AgentMesh#addListener(AgentMeshListener)}
 * 订阅这些事件，用于监控、日志、自动恢复等场景。</p>
 */
public class AgentLifecycleEvent {

    private final String agentId;
    private final AgentLifecycleState from;
    private final AgentLifecycleState to;
    private final String reason;
    private final long timestamp;

    public AgentLifecycleEvent(String agentId, AgentLifecycleState from,
                                AgentLifecycleState to, String reason) {
        this.agentId = agentId;
        this.from = from;
        this.to = to;
        this.reason = reason;
        this.timestamp = System.currentTimeMillis();
    }

    public String getAgentId() { return agentId; }
    public AgentLifecycleState getFrom() { return from; }
    public AgentLifecycleState getTo() { return to; }
    public String getReason() { return reason; }
    public long getTimestamp() { return timestamp; }

    public static AgentLifecycleEvent of(String agentId, AgentLifecycleState from,
                                          AgentLifecycleState to, String reason) {
        return new AgentLifecycleEvent(agentId, from, to, reason);
    }

    @Override
    public String toString() {
        return "AgentLifecycleEvent{" + agentId + ": " + from + " → " + to
                + (reason != null ? " (" + reason + ")" : "") + "}";
    }
}
