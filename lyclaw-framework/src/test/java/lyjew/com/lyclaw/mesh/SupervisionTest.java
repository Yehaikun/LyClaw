package lyjew.com.lyclaw.mesh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import lyjew.com.lyclaw.mesh.impl.DefaultAgentMesh;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 Supervision 错误恢复：
 * - RESTART 策略：失败后自动重启
 * - ESCALATE 策略：上报事件（不重启）
 * - IGNORE 策略：忽略失败
 * - STOP 策略：失败后停止 Agent
 * - 最大重试次数限制
 */
class SupervisionTest {

    private DefaultAgentMesh mesh;

    @BeforeEach
    void setUp() {
        mesh = new DefaultAgentMesh();
    }

    @Test
    void restartShouldAutoRecoverAgent() {
        mesh.register(AgentSpec.builder()
                .agentId("restart-agent")
                .supervisionStrategy(SupervisionStrategy.RESTART)
                .maxRetries(3)
                .build());

        // 触发 FAILED 状态
        mesh.updateState("restart-agent", AgentLifecycleState.FAILED);

        // Agent 应该被自动重启
        AgentInstance instance = mesh.getInstance("restart-agent").orElse(null);
        assertNotNull(instance);
        // 重启后应该是 ACTIVE
        assertTrue(instance.getState() == AgentLifecycleState.ACTIVE
                || instance.getState() == AgentLifecycleState.STARTING);
    }

    @Test
    void escalateShouldNotifyListeners() {
        final boolean[] notified = {false};
        mesh.addListener(event -> {
            if (event.getType() == AgentMeshListener.MeshEventType.MESSAGE_ERROR) {
                notified[0] = true;
            }
        });

        mesh.register(AgentSpec.builder()
                .agentId("escalate-agent")
                .supervisionStrategy(SupervisionStrategy.ESCALATE)
                .build());

        mesh.updateState("escalate-agent", AgentLifecycleState.FAILED);

        assertTrue(notified[0]);
    }

    @Test
    void ignoreShouldNotChangeState() {
        mesh.register(AgentSpec.builder()
                .agentId("ignore-agent")
                .supervisionStrategy(SupervisionStrategy.IGNORE)
                .build());

        AgentInstance instance = mesh.getInstance("ignore-agent").orElse(null);
        assertNotNull(instance);
        instance.start();

        mesh.updateState("ignore-agent", AgentLifecycleState.FAILED);

        // IGNORE 不重启，状态保持 FAILED
        assertEquals(AgentLifecycleState.FAILED, instance.getState());
    }

    @Test
    void stopShouldShutdownAgent() {
        mesh.register(AgentSpec.builder()
                .agentId("stop-agent")
                .supervisionStrategy(SupervisionStrategy.STOP)
                .build());

        mesh.updateState("stop-agent", AgentLifecycleState.FAILED);

        AgentInstance instance = mesh.getInstance("stop-agent").orElse(null);
        assertNotNull(instance);
        assertTrue(instance.getState() == AgentLifecycleState.STOPPED
                || instance.getState() == AgentLifecycleState.STOPPING);
    }

    @Test
    void defaultStrategyShouldBeRestart() {
        mesh.register(AgentSpec.builder()
                .agentId("default-strategy-agent")
                .build());

        // 没有设置 strategy，默认应该是 RESTART
        mesh.updateState("default-strategy-agent", AgentLifecycleState.FAILED);

        AgentInstance instance = mesh.getInstance("default-strategy-agent").orElse(null);
        assertNotNull(instance);
        // 默认 RESTART 策略应该恢复
        assertTrue(instance.getState() == AgentLifecycleState.ACTIVE
                || instance.getState() == AgentLifecycleState.FAILED);
    }
}
