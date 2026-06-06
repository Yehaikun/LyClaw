package lyjew.com.lyclaw.mesh;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent Mesh 可观测性指标收集器 —— 记录 Agent 调用、耗时、错误等指标。
 *
 * <p>这是 SPI 接口，用户可以实现自定义的指标收集方式（如对接 Micrometer、
 * Prometheus、OpenTelemetry）。默认实现 {@link DefaultAgentMeshMetrics}
 * 使用 ConcurrentHashMap 做进程内计数。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * AgentMeshMetrics metrics = new DefaultAgentMeshMetrics();
 *
 * // 记录一次 Agent 调用
 * metrics.recordCall("code-reviewer", true, 1500);
 *
 * // 获取统计
 * MetricsSnapshot snapshot = metrics.snapshot();
 * System.out.println("总调用: " + snapshot.totalCalls);
 * }</pre>
 */
public interface AgentMeshMetrics {

    /** 记录一次 Agent 调用 */
    void recordCall(String agentId, boolean success, long durationMs);

    /** 记录当前活跃请求数 */
    void recordActiveRequests(String agentId, int count);

    /** 记录错误 */
    void recordError(String agentId, String errorType);

    /** 获取当前快照 */
    MetricsSnapshot snapshot();

    /** 获取指定 Agent 的指标 */
    AgentMetrics getAgentMetrics(String agentId);

    /** 重置所有指标 */
    void reset();

    /** 指标快照 */
    class MetricsSnapshot {
        public final long totalCalls;
        public final long totalErrors;
        public final long totalDurationMs;
        public final int agentCount;
        public final Map<String, AgentMetrics> agents;
        public final long timestamp;

        public MetricsSnapshot(long totalCalls, long totalErrors, long totalDurationMs,
                                int agentCount, Map<String, AgentMetrics> agents) {
            this.totalCalls = totalCalls;
            this.totalErrors = totalErrors;
            this.totalDurationMs = totalDurationMs;
            this.agentCount = agentCount;
            this.agents = agents;
            this.timestamp = System.currentTimeMillis();
        }
    }

    /** 单个 Agent 的指标 */
    class AgentMetrics {
        public final String agentId;
        public final AtomicLong totalCalls = new AtomicLong();
        public final AtomicLong successfulCalls = new AtomicLong();
        public final AtomicLong failedCalls = new AtomicLong();
        public final AtomicLong totalDurationMs = new AtomicLong();
        public final AtomicLong minDurationMs = new AtomicLong(Long.MAX_VALUE);
        public final AtomicLong maxDurationMs = new AtomicLong();
        public final AtomicInteger activeRequests = new AtomicInteger();
        public final AtomicLong lastCallTimestamp = new AtomicLong();
        public final ConcurrentHashMap<String, AtomicLong> errors = new ConcurrentHashMap<>();

        public AgentMetrics(String agentId) { this.agentId = agentId; }

        public double avgDurationMs() {
            long calls = totalCalls.get();
            return calls > 0 ? (double) totalDurationMs.get() / calls : 0;
        }

        public double successRate() {
            long total = totalCalls.get();
            return total > 0 ? (double) successfulCalls.get() / total * 100 : 0;
        }
    }
}
