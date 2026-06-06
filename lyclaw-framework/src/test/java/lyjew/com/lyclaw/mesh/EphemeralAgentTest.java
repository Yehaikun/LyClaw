package lyjew.com.lyclaw.mesh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import lyjew.com.lyclaw.mesh.impl.DefaultAgentMesh;
import lyjew.com.lyclaw.mesh.impl.DefaultAgentFactory;

/**
 * Ephemeral Agent 生命周期测试：
 * - ephemeral 标志
 * - 超时自动销毁
 * - 完成后自动清理
 * - parentId 关联
 */
class EphemeralAgentTest {

    private DefaultAgentMesh mesh;

    @BeforeEach
    void setUp() {
        mesh = new DefaultAgentMesh();
        mesh.configureAgentFactory(new DefaultAgentFactory());
    }

    @Test
    void ephemeralAgentShouldBeRegistered() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("temp-agent")
                .ephemeral(true)
                .ttlMs(60000)
                .build();

        mesh.register(spec);
        assertTrue(mesh.lookup("temp-agent").isPresent());
        assertTrue(spec.isEphemeral());
    }

    @Test
    void nonEphemeralShouldDefaultToFalse() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("persistent-agent")
                .build();

        assertFalse(spec.isEphemeral());
    }

    @Test
    void ephemeralAgentShouldBeRemovedAfterCleanup() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("cleanup-me")
                .ephemeral(true)
                .build();

        mesh.register(spec);
        assertTrue(mesh.lookup("cleanup-me").isPresent());

        mesh.checkEphemeralCleanup("cleanup-me");
        assertTrue(mesh.lookup("cleanup-me").isEmpty());
    }

    @Test
    void persistentAgentShouldSurviveCleanup() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("keep-me")
                .ephemeral(false)
                .build();

        mesh.register(spec);
        mesh.checkEphemeralCleanup("keep-me");
        assertTrue(mesh.lookup("keep-me").isPresent());
    }

    @Test
    void ephemeralShouldSupportParentId() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("child-agent")
                .ephemeral(true)
                .parentId("parent-agent")
                .build();

        assertEquals("parent-agent", spec.getParentId());
        assertTrue(spec.isEphemeral());
    }

    @Test
    void ephemeralShouldSupportTtl() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("ttl-agent")
                .ephemeral(true)
                .ttlMs(5000)
                .build();

        assertEquals(5000, spec.getTtlMs());
    }

    @Test
    void builderShouldRejectEmptyAgentId() {
        assertThrows(IllegalStateException.class, () ->
                AgentSpec.builder().ephemeral(true).build());
    }
}
