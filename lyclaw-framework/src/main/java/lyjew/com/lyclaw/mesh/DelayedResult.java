package lyjew.com.lyclaw.mesh;

/**
 * 延迟结果 —— 当父 Agent 在子 Agent 完成前已结束时，结果暂存于此。
 *
 * <p>用于解决"先有结果后有消费者"的问题：
 * 子 Agent 异步执行，父 Agent 可能先结束（如 Flux 完成）。
 * 结果存入延迟队列，父 Agent 下次启动时可取回。</p>
 */
public class DelayedResult {

    private final String agentId;
    private final String correlationId;
    private final AgentMessage result;
    private final long storedAt;
    private final long ttlMs;

    public DelayedResult(String agentId, String correlationId,
                          AgentMessage result, long ttlMs) {
        this.agentId = agentId;
        this.correlationId = correlationId;
        this.result = result;
        this.storedAt = System.currentTimeMillis();
        this.ttlMs = ttlMs;
    }

    public String getAgentId() { return agentId; }
    public String getCorrelationId() { return correlationId; }
    public AgentMessage getResult() { return result; }
    public long getStoredAt() { return storedAt; }

    public boolean isExpired() {
        return ttlMs > 0 && (System.currentTimeMillis() - storedAt) > ttlMs;
    }
}
