package lyjew.com.lyclaw.mesh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import lyjew.com.lyclaw.mesh.impl.DefaultAgentMesh;
import lyjew.com.lyclaw.mesh.impl.DefaultAgentFactory;

/**
 * DefaultAgentMesh 扩展测试：
 * - 注册时无 Factory 抛出清晰错误
 * - configureAgentFactory 连接
 * - 消息超时
 * - 生命周期事件传播
 */
class DefaultAgentMeshExtendedTest {

    private DefaultAgentMesh mesh;

    @BeforeEach
    void setUp() {
        mesh = new DefaultAgentMesh();
    }

    @Test
    void registerWithoutFactoryThrowsClearError() {
        // mesh 没有配置 factory（内部 factory 为 null）
        // 创建一个新的 mesh 确保 factory 为 null
        DefaultAgentMesh noFactoryMesh = new DefaultAgentMesh();
        // 不在 auto-config 中，factory 为 null

        Exception exception = assertThrows(IllegalStateException.class, () ->
                noFactoryMesh.register(AgentSpec.builder()
                        .agentId("no-factory-test")
                        .build()));

        assertTrue(exception.getMessage().contains("AgentFactory not configured"));
    }

    @Test
    void configureFactoryEnablesRegistration() {
        DefaultAgentFactory factory = new DefaultAgentFactory();
        mesh.configureAgentFactory(factory);

        AgentRef ref = mesh.register(AgentSpec.builder()
                .agentId("post-config-test")
                .build());

        assertNotNull(ref);
        assertEquals("post-config-test", ref.getAgentId());
    }

    @Test
    void lookupNonexistentReturnsEmpty() {
        assertTrue(mesh.lookup("nonexistent").isEmpty());
    }

    @Test
    void getInstanceNonexistentReturnsEmpty() {
        assertTrue(mesh.getInstance("nonexistent").isEmpty());
    }

    @Test
    void sendToUnknownTargetReturnsError() {
        AgentMessage response = mesh.send(AgentMessage.builder()
                .to("unknown")
                .type(MessageType.REQUEST)
                .payload("test")
                .correlationId("err-001")
                .build()).join();

        assertEquals(MessageType.ERROR, response.getType());
    }

    @Test
    void sendExpiredMessageReturnsError() {
        AgentMessage response = mesh.send(AgentMessage.builder()
                .to("any")
                .type(MessageType.REQUEST)
                .payload("test")
                .ttlMs(1)
                .timestamp(1)
                .build()).join();

        assertEquals(MessageType.ERROR, response.getType());
    }

    @Test
    void updateStateChangesHandle() {
        DefaultAgentFactory factory = new DefaultAgentFactory();
        mesh.configureAgentFactory(factory);
        mesh.register(AgentSpec.builder().agentId("state-test").build());

        mesh.updateState("state-test", AgentLifecycleState.ACTIVE);

        mesh.getInstance("state-test").ifPresent(inst -> {
            assertEquals(AgentLifecycleState.ACTIVE, inst.getState());
        });
    }

    @Test
    void stopAgentChangesState() {
        DefaultAgentFactory factory = new DefaultAgentFactory();
        mesh.configureAgentFactory(factory);
        mesh.register(AgentSpec.builder().agentId("stop-test").build());

        mesh.stopAgent("stop-test");

        mesh.getInstance("stop-test").ifPresent(inst -> {
            assertTrue(inst.getState() == AgentLifecycleState.STOPPED
                    || inst.getState() == AgentLifecycleState.STOPPING);
        });
    }

    @Test
    void startAgentMakesItActive() {
        DefaultAgentFactory factory = new DefaultAgentFactory();
        mesh.configureAgentFactory(factory);
        mesh.register(AgentSpec.builder().agentId("start-test").build());

        mesh.startAgent("start-test");

        mesh.getInstance("start-test").ifPresent(inst -> {
            assertTrue(inst.getState() == AgentLifecycleState.ACTIVE
                    || inst.getState() == AgentLifecycleState.STARTING);
        });
    }
}
