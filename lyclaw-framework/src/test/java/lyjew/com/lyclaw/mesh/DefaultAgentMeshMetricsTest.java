package lyjew.com.lyclaw.mesh;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import lyjew.com.lyclaw.mesh.impl.DefaultAgentMeshMetrics;

/**
 * DefaultAgentMeshMetrics 单元测试：
 * - 记录调用/成功/失败/耗时
 * - 多 Agent 指标隔离
 * - 快照生成
 * - 重置
 * - 错误分类统计
 */
class DefaultAgentMeshMetricsTest {

    @Test
    void shouldRecordSuccessfulCall() {
        DefaultAgentMeshMetrics metrics = new DefaultAgentMeshMetrics();
        metrics.recordCall("agent-a", true, 100);

        AgentMeshMetrics.AgentMetrics m = metrics.getAgentMetrics("agent-a");
        assertEquals(1, m.totalCalls.get());
        assertEquals(1, m.successfulCalls.get());
        assertEquals(0, m.failedCalls.get());
        assertEquals(100, m.totalDurationMs.get());
    }

    @Test
    void shouldRecordFailedCall() {
        DefaultAgentMeshMetrics metrics = new DefaultAgentMeshMetrics();
        metrics.recordCall("agent-a", false, 50);

        AgentMeshMetrics.AgentMetrics m = metrics.getAgentMetrics("agent-a");
        assertEquals(1, m.totalCalls.get());
        assertEquals(0, m.successfulCalls.get());
        assertEquals(1, m.failedCalls.get());
    }

    @Test
    void shouldTrackMinMaxDuration() {
        DefaultAgentMeshMetrics metrics = new DefaultAgentMeshMetrics();
        metrics.recordCall("agent-a", true, 500);
        metrics.recordCall("agent-a", true, 100);
        metrics.recordCall("agent-a", true, 300);

        assertEquals(100, metrics.getAgentMetrics("agent-a").minDurationMs.get());
        assertEquals(500, metrics.getAgentMetrics("agent-a").maxDurationMs.get());
    }

    @Test
    void shouldCalculateAverageDuration() {
        DefaultAgentMeshMetrics metrics = new DefaultAgentMeshMetrics();
        metrics.recordCall("agent-a", true, 100);
        metrics.recordCall("agent-a", true, 300);

        assertEquals(200.0, metrics.getAgentMetrics("agent-a").avgDurationMs(), 0.01);
    }

    @Test
    void shouldCalculateSuccessRate() {
        DefaultAgentMeshMetrics metrics = new DefaultAgentMeshMetrics();
        metrics.recordCall("agent-a", true, 100);
        metrics.recordCall("agent-a", true, 100);
        metrics.recordCall("agent-a", false, 50);

        assertEquals(66.67, metrics.getAgentMetrics("agent-a").successRate(), 0.1);
    }

    @Test
    void shouldRecordErrors() {
        DefaultAgentMeshMetrics metrics = new DefaultAgentMeshMetrics();
        metrics.recordError("agent-a", "TIMEOUT");
        metrics.recordError("agent-a", "TIMEOUT");
        metrics.recordError("agent-a", "RATE_LIMIT");

        assertEquals(2, metrics.getAgentMetrics("agent-a").errors.get("TIMEOUT").get());
        assertEquals(1, metrics.getAgentMetrics("agent-a").errors.get("RATE_LIMIT").get());
    }

    @Test
    void shouldRecordActiveRequests() {
        DefaultAgentMeshMetrics metrics = new DefaultAgentMeshMetrics();
        metrics.recordActiveRequests("agent-a", 5);
        assertEquals(5, metrics.getAgentMetrics("agent-a").activeRequests.get());
    }

    @Test
    void shouldGenerateSnapshot() {
        DefaultAgentMeshMetrics metrics = new DefaultAgentMeshMetrics();
        metrics.recordCall("agent-a", true, 100);
        metrics.recordCall("agent-b", false, 50);
        metrics.recordError("agent-b", "ERROR");

        AgentMeshMetrics.MetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(2, snapshot.totalCalls);
        assertEquals(1, snapshot.totalErrors);
        assertEquals(150, snapshot.totalDurationMs);
        assertEquals(2, snapshot.agentCount);
    }

    @Test
    void shouldReset() {
        DefaultAgentMeshMetrics metrics = new DefaultAgentMeshMetrics();
        metrics.recordCall("agent-a", true, 100);
        metrics.reset();

        assertEquals(0, metrics.snapshot().totalCalls);
    }

    @Test
    void shouldIsolatePerAgentMetrics() {
        DefaultAgentMeshMetrics metrics = new DefaultAgentMeshMetrics();
        metrics.recordCall("agent-a", true, 100);
        metrics.recordCall("agent-b", false, 50);

        assertEquals(1, metrics.getAgentMetrics("agent-a").totalCalls.get());
        assertEquals(1, metrics.getAgentMetrics("agent-b").totalCalls.get());
    }

    @Test
    void zeroCallsShouldReturnZeroAverage() {
        DefaultAgentMeshMetrics metrics = new DefaultAgentMeshMetrics();
        assertEquals(0.0, metrics.getAgentMetrics("new-agent").avgDurationMs(), 0.01);
        assertEquals(0.0, metrics.getAgentMetrics("new-agent").successRate(), 0.01);
    }
}
