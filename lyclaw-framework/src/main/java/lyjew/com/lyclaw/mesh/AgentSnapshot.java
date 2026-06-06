package lyjew.com.lyclaw.mesh;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 快照 —— 可序列化的 Agent 运行时状态。
 *
 * <p>用于 Agent 的存档、迁移和恢复。快照包含 Agent 的配置、调用历史、
 * 当前状态和元数据，可以序列化为 JSON 存入数据库或文件。</p>
 *
 * <p>使用场景：</p>
 * <ul>
 *   <li><b>持久化</b> —— 将 Agent 状态保存到 SQLite/Redis</li>
 *   <li><b>迁移</b> —— 将一个 JVM 中的 Agent 迁移到另一个 JVM</li>
 *   <li><b>调试</b> —— 导出 Agent 状态用于问题排查</li>
 *   <li><b>恢复</b> —— 进程重启后从快照重建 Agent</li>
 * </ul>
 *
 * <p>快照不包含运行时引用（如网络连接、文件句柄），
 * 只包含可序列化的数据。</p>
 */
public class AgentSnapshot {

    private final String agentId;
    private final AgentRef.AgentType type;
    private final AgentLifecycleState state;
    private final AgentSpec spec;
    private final List<AgentCallHistory.ChildCall> callHistory;
    private final Map<String, Object> variables;
    private final long createdAt;
    private final long lastActiveAt;
    private final int totalCalls;
    private final int totalErrors;

    private AgentSnapshot(Builder builder) {
        this.agentId = builder.agentId;
        this.type = builder.type;
        this.state = builder.state;
        this.spec = builder.spec;
        this.callHistory = builder.callHistory != null ? List.copyOf(builder.callHistory) : List.of();
        this.variables = builder.variables != null ? Map.copyOf(builder.variables) : Map.of();
        this.createdAt = builder.createdAt;
        this.lastActiveAt = builder.lastActiveAt;
        this.totalCalls = builder.totalCalls;
        this.totalErrors = builder.totalErrors;
    }

    public String getAgentId() { return agentId; }
    public AgentRef.AgentType getType() { return type; }
    public AgentLifecycleState getState() { return state; }
    public AgentSpec getSpec() { return spec; }
    public List<AgentCallHistory.ChildCall> getCallHistory() { return callHistory; }
    public Map<String, Object> getVariables() { return variables; }
    public long getCreatedAt() { return createdAt; }
    public long getLastActiveAt() { return lastActiveAt; }
    public int getTotalCalls() { return totalCalls; }
    public int getTotalErrors() { return totalErrors; }

    /** 从 AgentInstance 创建快照 */
    public static AgentSnapshot from(AgentInstance instance) {
        AgentHandle handle = instance.getHandle();
        AgentCallHistory history = instance.getCallHistory();

        return builder()
                .agentId(instance.getAgentId())
                .type(instance.getType())
                .state(instance.getState())
                .spec(instance.getSpec())
                .callHistory(history.getAllCalls())
                .createdAt(handle.getLastActiveTime() - 3600_000) // 估算
                .lastActiveAt(handle.getLastActiveTime())
                .totalCalls(handle.getTotalRequestsHandled())
                .totalErrors(handle.getTotalErrors())
                .build();
    }

    /** 将快照恢复为 AgentSpec（可用于重建 Agent） */
    public AgentSpec toSpec() {
        return spec != null ? spec : AgentSpec.builder().agentId(agentId).build();
    }

    /** 将调用历史格式化为文本（用于 LLM 上下文） */
    public String formatCallHistory() {
        if (callHistory.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("历史调用 (").append(callHistory.size()).append(" 次):\n");
        for (AgentCallHistory.ChildCall call : callHistory) {
            sb.append("  → ").append(call.getChildAgentId())
              .append(": ").append(call.getStatus())
              .append(" (").append(call.elapsed()).append("ms)")
              .append("\n");
        }
        return sb.toString();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String agentId;
        private AgentRef.AgentType type;
        private AgentLifecycleState state;
        private AgentSpec spec;
        private List<AgentCallHistory.ChildCall> callHistory;
        private Map<String, Object> variables;
        private long createdAt;
        private long lastActiveAt;
        private int totalCalls;
        private int totalErrors;

        public Builder agentId(String v) { this.agentId = v; return this; }
        public Builder type(AgentRef.AgentType v) { this.type = v; return this; }
        public Builder state(AgentLifecycleState v) { this.state = v; return this; }
        public Builder spec(AgentSpec v) { this.spec = v; return this; }
        public Builder callHistory(List<AgentCallHistory.ChildCall> v) { this.callHistory = v; return this; }
        public Builder variables(Map<String, Object> v) { this.variables = v; return this; }
        public Builder createdAt(long v) { this.createdAt = v; return this; }
        public Builder lastActiveAt(long v) { this.lastActiveAt = v; return this; }
        public Builder totalCalls(int v) { this.totalCalls = v; return this; }
        public Builder totalErrors(int v) { this.totalErrors = v; return this; }
        public AgentSnapshot build() { return new AgentSnapshot(this); }
    }
}
