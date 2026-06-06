package lyjew.com.lyclaw.mesh;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import lyjew.com.lyclaw.mesh.impl.DefaultAgentMesh;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 DefaultAgentMesh 核心功能：
 * - Agent 注册与查找
 * - 能力索引
 * - 消息发送与响应
 * - 延迟结果队列
 * - 生命周期管理
 */
class DefaultAgentMeshTest {

    private DefaultAgentMesh mesh;

    @BeforeEach
    void setUp() {
        mesh = new DefaultAgentMesh();
    }

    @Test
    void shouldRegisterAgent() {
        AgentRef ref = mesh.register(AgentSpec.builder()
                .agentId("test-agent")
                .capability("test")
                .build());

        assertNotNull(ref);
        assertEquals("test-agent", ref.getAgentId());
        assertTrue(mesh.lookup("test-agent").isPresent());
    }

    @Test
    void shouldFindAgentByCapability() {
        mesh.register(AgentSpec.builder()
                .agentId("reviewer")
                .capability("code-review")
                .capability("refactor")
                .build());
        mesh.register(AgentSpec.builder()
                .agentId("linter")
                .capability("code-review")
                .build());

        List<AgentRef> reviewers = mesh.findByCapability("code-review");
        assertEquals(2, reviewers.size());

        List<AgentRef> refactors = mesh.findByCapability("refactor");
        assertEquals(1, refactors.size());
        assertEquals("reviewer", refactors.get(0).getAgentId());
    }

    @Test
    void shouldUnregisterAgent() {
        mesh.register(AgentSpec.builder().agentId("temp").build());
        assertTrue(mesh.lookup("temp").isPresent());

        mesh.unregister("temp");
        assertTrue(mesh.lookup("temp").isEmpty());
    }

    @Test
    void shouldSendMessageAndReceiveResponse() {
        mesh.register(AgentSpec.builder()
                .agentId("echo")
                .build());

        AgentMessage request = AgentMessage.builder()
                .to("echo")
                .type(MessageType.REQUEST)
                .payload("hello")
                .correlationId("test-001")
                .build();

        AgentMessage response = mesh.send(request).join();

        assertNotNull(response);
        assertTrue(response.getType() == MessageType.RESPONSE
                || response.getType() == MessageType.ERROR);
    }

    @Test
    void shouldReturnErrorForUnknownTarget() {
        AgentMessage response = mesh.send(AgentMessage.builder()
                .to("nonexistent")
                .payload("test")
                .build()).join();

        assertEquals(MessageType.ERROR, response.getType());
        assertTrue(response.getPayload().contains("No agent found"));
    }

    @Test
    void shouldSupportCapabilityRouting() {
        mesh.register(AgentSpec.builder()
                .agentId("search-agent")
                .capability("search")
                .build());

        AgentMessage request = AgentMessage.builder()
                .capability("search")
                .payload("find something")
                .correlationId("test-002")
                .build();

        AgentMessage response = mesh.send(request).join();
        assertNotNull(response);
    }

    @Test
    void shouldStoreAndPollDelayedResult() {
        AgentMessage result = AgentMessage.builder()
                .type(MessageType.RESPONSE)
                .payload("delayed result")
                .build();

        mesh.storeDelayedResult("parent-agent", "call-001", result);

        DelayedResult polled = mesh.pollDelayedResult("parent-agent", "call-001")
                .orElseThrow(() -> new AssertionError("Expected delayed result"));
        assertEquals("delayed result", polled.getResult().getPayload());

        // Second poll should be empty (consumed)
        assertTrue(mesh.pollDelayedResult("parent-agent", "call-001").isEmpty());
    }

    @Test
    void shouldListAllAgents() {
        assertEquals(0, mesh.getAllAgents().size());

        mesh.register(AgentSpec.builder().agentId("agent-a").build());
        mesh.register(AgentSpec.builder().agentId("agent-b").build());

        assertEquals(2, mesh.getAllAgents().size());
    }

    @Test
    void shouldRejectDuplicateRegistration() {
        mesh.register(AgentSpec.builder().agentId("unique").build());
        assertThrows(IllegalStateException.class, () ->
                mesh.register(AgentSpec.builder().agentId("unique").build()));
    }
}
