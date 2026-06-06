package lyjew.com.lyclaw.mesh;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentHandle 单元测试：
 * - 初始状态
 * - 状态转换
 * - 请求计数
 * - 健康状态
 * - 错误追踪
 */
class AgentHandleTest {

    @Test
    void handleShouldStartInPending() {
        AgentHandle handle = new AgentHandle();
        assertEquals(AgentLifecycleState.PENDING, handle.getState());
    }

    @Test
    void handleShouldTrackStateChanges() {
        AgentHandle handle = new AgentHandle();
        handle.setState(AgentLifecycleState.ACTIVE);
        assertEquals(AgentLifecycleState.ACTIVE, handle.getState());
        handle.setState(AgentLifecycleState.PROGRESS);
        assertEquals(AgentLifecycleState.PROGRESS, handle.getState());
        handle.setState(AgentLifecycleState.STOPPED);
        assertEquals(AgentLifecycleState.STOPPED, handle.getState());
    }

    @Test
    void handleShouldTrackRequestCount() {
        AgentHandle handle = new AgentHandle();
        assertEquals(0, handle.getActiveRequestCount());

        handle.incrementRequestCount();
        assertEquals(1, handle.getActiveRequestCount());

        handle.decrementRequestCount();
        assertEquals(0, handle.getActiveRequestCount());
    }

    @Test
    void handleShouldNotGoNegative() {
        AgentHandle handle = new AgentHandle();
        handle.decrementRequestCount();
        assertEquals(0, handle.getActiveRequestCount());
    }

    @Test
    void handleShouldTrackTotalRequests() {
        AgentHandle handle = new AgentHandle();
        handle.incrementTotalRequests();
        handle.incrementTotalRequests();
        handle.incrementTotalRequests();
        assertEquals(3, handle.getTotalRequestsHandled());
    }

    @Test
    void handleShouldTrackErrors() {
        AgentHandle handle = new AgentHandle();
        assertEquals(0, handle.getTotalErrors());
        handle.incrementErrors();
        assertEquals(1, handle.getTotalErrors());
    }

    @Test
    void handleShouldTrackHealth() {
        AgentHandle handle = new AgentHandle();
        assertEquals(AgentHandle.HealthStatus.UNKNOWN, handle.getHealth());

        handle.setHealth(AgentHandle.HealthStatus.UP);
        assertTrue(handle.isHealthy());

        handle.setHealth(AgentHandle.HealthStatus.DOWN);
        assertFalse(handle.isHealthy());
    }

    @Test
    void handleShouldTrackLastError() {
        AgentHandle handle = new AgentHandle();
        handle.setLastError("Something went wrong");
        assertEquals("Something went wrong", handle.getLastError());
    }

    @Test
    void handleAcceptRequestOnlyWhenActive() {
        AgentHandle handle = new AgentHandle();
        assertFalse(handle.canAcceptRequest());

        handle.setState(AgentLifecycleState.ACTIVE);
        assertTrue(handle.canAcceptRequest());

        handle.setState(AgentLifecycleState.STOPPED);
        assertFalse(handle.canAcceptRequest());
    }

    @Test
    void handleShouldTrackLastActiveTime() {
        AgentHandle handle = new AgentHandle();
        long before = System.currentTimeMillis();
        handle.setLastActiveTime(System.currentTimeMillis());
        long after = System.currentTimeMillis();
        assertTrue(handle.getLastActiveTime() >= before
                && handle.getLastActiveTime() <= after);
    }
}
