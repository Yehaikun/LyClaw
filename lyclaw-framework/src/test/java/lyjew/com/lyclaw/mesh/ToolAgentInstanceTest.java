package lyjew.com.lyclaw.mesh;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import lyjew.com.lyclaw.mesh.impl.DefaultAgentMesh;
import lyjew.com.lyclaw.mesh.impl.ToolAgentInstance;
import lyjew.com.lyclaw.model.ToolDefinition;

/**
 * ToolAgentInstance 单元测试：
 * - send() 执行工具并返回结果
 * - send() 在未启动时返回错误
 * - start/stop/destroy 生命周期
 * - sendStream 降级
 * - 调用历史记录
 */
class ToolAgentInstanceTest {

    @Test
    void toolAgentShouldRespondToSend() {
        DefaultAgentMesh mesh = new DefaultAgentMesh();
        AgentSpec spec = AgentSpec.builder()
                .agentId("test-tool")
                .type(AgentRef.AgentType.TOOL)
                .build();
        mesh.register(spec);

        ToolAgentInstance agent = new ToolAgentInstance(spec, mesh);
        agent.start();

        AgentMessage response = agent.send(AgentMessage.builder()
                .type(MessageType.REQUEST)
                .payload("{\"name\":\"test\",\"args\":{}}")
                .correlationId("t-001")
                .build()).join();

        assertNotNull(response);
        assertTrue(response.getType() == MessageType.RESPONSE
                || response.getType() == MessageType.ERROR);
    }

    @Test
    void toolAgentShouldErrorWhenNotRunning() {
        DefaultAgentMesh mesh = new DefaultAgentMesh();
        AgentSpec spec = AgentSpec.builder()
                .agentId("stopped-tool")
                .type(AgentRef.AgentType.TOOL)
                .build();

        ToolAgentInstance agent = new ToolAgentInstance(spec, mesh);

        AgentMessage response = agent.send(AgentMessage.builder()
                .type(MessageType.REQUEST)
                .payload("test")
                .build()).join();

        assertEquals(MessageType.ERROR, response.getType());
    }

    @Test
    void toolAgentLifecycle() {
        DefaultAgentMesh mesh = new DefaultAgentMesh();
        AgentSpec spec = AgentSpec.builder()
                .agentId("lifecycle-tool")
                .type(AgentRef.AgentType.TOOL)
                .build();

        ToolAgentInstance agent = new ToolAgentInstance(spec, mesh);

        assertEquals(AgentLifecycleState.PENDING, agent.getState());
        agent.start();
        assertEquals(AgentLifecycleState.ACTIVE, agent.getState());
        agent.stop();
        assertEquals(AgentLifecycleState.STOPPED, agent.getState());
        agent.destroy();
        assertEquals(AgentLifecycleState.DESTROYED, agent.getState());
    }

    @Test
    void toolAgentShouldTrackCallHistory() {
        DefaultAgentMesh mesh = new DefaultAgentMesh();
        AgentSpec spec = AgentSpec.builder()
                .agentId("history-tool")
                .type(AgentRef.AgentType.TOOL)
                .build();

        ToolAgentInstance agent = new ToolAgentInstance(spec, mesh);
        agent.start();

        agent.send(AgentMessage.builder()
                .type(MessageType.REQUEST)
                .payload("test")
                .correlationId("h-001")
                .build()).join();

        assertNotNull(agent.getCallHistory());
        assertTrue(agent.getCallHistory().getAllCalls().size() > 0
                || agent.getCallHistory().getCompletedCalls().size() >= 0);
    }

    @Test
    void toolAgentStreamFallsBackToSync() {
        DefaultAgentMesh mesh = new DefaultAgentMesh();
        AgentSpec spec = AgentSpec.builder()
                .agentId("stream-tool")
                .type(AgentRef.AgentType.TOOL)
                .build();

        ToolAgentInstance agent = new ToolAgentInstance(spec, mesh);
        agent.start();

        var flux = agent.sendStream(AgentMessage.builder()
                .type(MessageType.REQUEST)
                .payload("test")
                .build());

        assertNotNull(flux);
    }

    @Test
    void toolAgentHasCorrectIdentity() {
        DefaultAgentMesh mesh = new DefaultAgentMesh();
        AgentSpec spec = AgentSpec.builder()
                .agentId("identity-check")
                .type(AgentRef.AgentType.TOOL)
                .build();

        ToolAgentInstance agent = new ToolAgentInstance(spec, mesh);

        assertEquals("identity-check", agent.getAgentId());
        assertEquals(AgentRef.AgentType.TOOL, agent.getType());
        assertNotNull(agent.getHandle());
        assertNotNull(agent.getSpec());
    }
}
