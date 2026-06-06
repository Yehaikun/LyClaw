package lyjew.com.lyclaw.mesh;

/**
 * Agent 运行时句柄 —— 包含 Agent 的运行时状态和健康度信息。
 *
 * <p>与 {@link AgentRef} 的区别：
 * AgentRef 是身份标识（不可变，可序列化），
 * AgentHandle 是运行时快照（可变，不可序列化）。</p>
 */
public class AgentHandle {

    private volatile AgentLifecycleState state;
    private volatile int activeRequestCount;
    private volatile long lastActiveTime;
    private volatile String lastError;
    private volatile int totalRequestsHandled;
    private volatile int totalErrors;
    private volatile HealthStatus health;

    public AgentHandle() {
        this.state = AgentLifecycleState.PENDING;
        this.health = HealthStatus.UNKNOWN;
        this.lastActiveTime = System.currentTimeMillis();
    }

    public AgentLifecycleState getState() { return state; }
    public void setState(AgentLifecycleState state) { this.state = state; }

    public int getActiveRequestCount() { return activeRequestCount; }
    public void setActiveRequestCount(int count) { this.activeRequestCount = count; }
    public void incrementRequestCount() { this.activeRequestCount++; }
    public void decrementRequestCount() { this.activeRequestCount = Math.max(0, this.activeRequestCount - 1); }

    public long getLastActiveTime() { return lastActiveTime; }
    public void setLastActiveTime(long time) { this.lastActiveTime = time; }

    public String getLastError() { return lastError; }
    public void setLastError(String error) { this.lastError = error; }

    public int getTotalRequestsHandled() { return totalRequestsHandled; }
    public void incrementTotalRequests() { this.totalRequestsHandled++; }

    public int getTotalErrors() { return totalErrors; }
    public void incrementErrors() { this.totalErrors++; }

    public HealthStatus getHealth() { return health; }
    public void setHealth(HealthStatus health) { this.health = health; }

    public enum HealthStatus {
        UP, DOWN, DEGRADED, UNKNOWN
    }

    /** 健康度检查 */
    public boolean isHealthy() {
        return health == HealthStatus.UP;
    }

    /** 是否可处理请求 */
    public boolean canAcceptRequest() {
        return state == AgentLifecycleState.ACTIVE
                || state == AgentLifecycleState.PROGRESS
                || state == AgentLifecycleState.DEGRADED;
    }
}
