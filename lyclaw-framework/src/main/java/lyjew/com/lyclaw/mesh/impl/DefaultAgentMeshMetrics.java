package lyjew.com.lyclaw.mesh.impl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lyjew.com.lyclaw.mesh.AgentMeshMetrics;

/**
 * 默认 Agent Mesh 指标收集器 —— 基于 ConcurrentHashMap 的进程内实现。
 *
 * <p>使用 {@link AgentMeshMetrics} SPI 可以替换为对接 Micrometer 等监控系统。</p>
 */
public class DefaultAgentMeshMetrics implements AgentMeshMetrics {

    private final ConcurrentHashMap<String, AgentMetrics> metricsMap = new ConcurrentHashMap<>();

    @Override
    public void recordCall(String agentId, boolean success, long durationMs) {
        AgentMetrics m = getOrCreate(agentId);
        m.totalCalls.incrementAndGet();
        if (success) {
            m.successfulCalls.incrementAndGet();
        } else {
            m.failedCalls.incrementAndGet();
        }
        m.totalDurationMs.addAndGet(durationMs);
        m.minDurationMs.updateAndGet(current -> Math.min(current, durationMs));
        m.maxDurationMs.updateAndGet(current -> Math.max(current, durationMs));
        m.lastCallTimestamp.set(System.currentTimeMillis());
    }

    @Override
    public void recordActiveRequests(String agentId, int count) {
        getOrCreate(agentId).activeRequests.set(count);
    }

    @Override
    public void recordError(String agentId, String errorType) {
        AgentMetrics m = getOrCreate(agentId);
        m.errors.computeIfAbsent(errorType, k -> new java.util.concurrent.atomic.AtomicLong())
                .incrementAndGet();
    }

    @Override
    public MetricsSnapshot snapshot() {
        long totalCalls = 0, totalErrors = 0, totalDuration = 0;
        Map<String, AgentMetrics> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, AgentMetrics> e : metricsMap.entrySet()) {
            AgentMetrics m = e.getValue();
            totalCalls += m.totalCalls.get();
            totalErrors += m.failedCalls.get();
            totalDuration += m.totalDurationMs.get();
            snapshot.put(e.getKey(), m);
        }
        return new MetricsSnapshot(totalCalls, totalErrors, totalDuration,
                metricsMap.size(), snapshot);
    }

    @Override
    public AgentMetrics getAgentMetrics(String agentId) {
        return getOrCreate(agentId);
    }

    @Override
    public void reset() {
        metricsMap.clear();
    }

    private AgentMetrics getOrCreate(String agentId) {
        return metricsMap.computeIfAbsent(agentId, AgentMetrics::new);
    }
}
