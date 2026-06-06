package lyjew.com.lyclaw.mesh;

import org.junit.jupiter.api.Test;

import lyjew.com.lyclaw.mesh.impl.DefaultAgentMeshMetrics;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 AgentMesh 指标收集：
 * - 记录调用和耗时
 * - 记录错误
 * - 快照统计
 * - 单个 Agent 指标
 * - 重置
 */
class AgentMeshMetricsTest {

    @Test
    void shouldRecordCallMetrics() {
        DefaultAgentMeshMetrics metrics = new DefaultAgentMeshMetrics();

        metrics.recordCall("agent-a", true, 100);
        metrics.recordCall("agent-a", true, 200);
        metrics.recordCall("agent-a", false, 50);

        AgentMeshMetrics.AgentMetrics m = metrics.getAgentMetrics("agent-a");
        assertEquals(3, m.totalCalls.get());
        assertEquals(2, m.successfulCalls.get());
        assertEquals(1, m.failedCalls.get());
        assertEquals(350, m.totalDurationMs.get());
    }

    @Test
    void shouldTrackMinMaxDuration() {
        DefaultAgentMeshMetrics metrics = new DefaultAgentMeshMetrics();

        metrics.recordCall("agent-a", true, 500);
        metrics.recordCall("agent-a", true, 100);
        metrics.recordCall("agent-a", true, 300);

        AgentMeshMetrics.AgentMetrics m = metrics.getAgentMetrics("agent-a");
        assertEquals(100, m.minDurationMs.get());
        assertEquals(500, m.maxDurationMs.get());
    }

    @Test
    void shouldCalculateAverageDuration() {
        DefaultAgentMeshMetrics metrics = new DefaultAgentMeshMetrics();

        metrics.recordCall("agent-a", true, 100);
        metrics.recordCall("agent-a", true, 300);

        assertEquals(200.0, metrics.getAgentMetrics("agent-a").avgDurationMs(), 0.01);
    }

    @Test
    void shouldRecordErrors() {
        DefaultAgentMeshMetrics metrics = new DefaultAgentMeshMetrics();

        metrics.recordError("agent-a", "TIMEOUT");
        metrics.recordError("agent-a", "TIMEOUT");
        metrics.recordError("agent-a", "RATE_LIMIT");

        AgentMeshMetrics.AgentMetrics m = metrics.getAgentMetrics("agent-a");
        assertEquals(2, m.errors.get("TIMEOUT").get());
        assertEquals(1, m.errors.get("RATE_LIMIT").get());
    }

    @Test
    void shouldGenerateSnapshot() {
        DefaultAgentMeshMetrics metrics = new DefaultAgentMeshMetrics();

        metrics.recordCall("agent-a", true, 100);
        metrics.recordCall("agent-b", false, 50);

        AgentMeshMetrics.MetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(2, snapshot.totalCalls);
        assertEquals(1, snapshot.totalErrors);
        assertEquals(150, snapshot.totalDurationMs);
        assertEquals(2, snapshot.agentCount);
    }

    @Test
    void shouldRecordActiveRequests() {
        DefaultAgentMeshMetrics metrics = new DefaultAgentMeshMetrics();

        metrics.recordActiveRequests("agent-a", 5);
        assertEquals(5, metrics.getAgentMetrics("agent-a").activeRequests.get());

        metrics.recordActiveRequests("agent-a", 3);
        assertEquals(3, metrics.getAgentMetrics("agent-a").activeRequests.get());
    }

    @Test
    void shouldResetMetrics() {
        DefaultAgentMeshMetrics metrics = new DefaultAgentMeshMetrics();

        metrics.recordCall("agent-a", true, 100);
        metrics.reset();

        assertEquals(0, metrics.snapshot().totalCalls);
        assertEquals(0, metrics.snapshot().agentCount);
    }

    @Test
    void shouldTrackLastCallTimestamp() throws Exception {
        DefaultAgentMeshMetrics metrics = new DefaultAgentMeshMetrics();

        long before = System.currentTimeMillis();
        metrics.recordCall("agent-a", true, 10);
        long after = System.currentTimeMillis();

        long lastCall = metrics.getAgentMetrics("agent-a").lastCallTimestamp.get();
        assertTrue(lastCall >= before && lastCall <= after);
    }

    @Test
    void shouldCalculateSuccessRate() {
        DefaultAgentMeshMetrics metrics = new DefaultAgentMeshMetrics();

        metrics.recordCall("agent-a", true, 100);
        metrics.recordCall("agent-a", true, 100);
        metrics.recordCall("agent-a", true, 100);
        metrics.recordCall("agent-a", false, 50);

        assertEquals(75.0, metrics.getAgentMetrics("agent-a").successRate(), 0.01);
    }
}
