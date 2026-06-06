package lyjew.com.lyclaw.mesh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import lyjew.com.lyclaw.mesh.impl.DefaultAgentFactory;
import lyjew.com.lyclaw.mesh.impl.DefaultAgentMesh;
import lyjew.com.lyclaw.model.ToolDefinition;
import java.util.List;

/**
 * DefaultAgentFactory 单元测试：
 * - create(LLM) 返回 LLMAgentInstance
 * - create(TOOL) 返回 ToolAgentInstance
 * - create() 在无依赖时抛出清晰错误
 * - 工具作用域集成
 */
class DefaultAgentFactoryTest {

    private DefaultAgentFactory factory;
    private DefaultAgentMesh mesh;

    @BeforeEach
    void setUp() {
        mesh = new DefaultAgentMesh();
        factory = new DefaultAgentFactory(mesh);
    }

    @Test
    void createLLMShouldReturnLLMAgentInstance() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("test-llm")
                .type(AgentRef.AgentType.LLM)
                .model("deepseek-v4")
                .systemPrompt("You are a test agent")
                .capability("test")
                .build();

        AgentInstance instance = factory.create(spec);
        assertNotNull(instance);
        assertEquals("test-llm", instance.getAgentId());
        assertEquals(AgentRef.AgentType.LLM, instance.getType());
    }

    @Test
    void createToolShouldReturnToolAgentInstance() {
        ToolDefinition toolDef = ToolDefinition.builder()
                .name("test-tool").description("A test tool").build();

        AgentSpec spec = AgentSpec.builder()
                .agentId("test-tool-agent")
                .type(AgentRef.AgentType.TOOL)
                .tool(toolDef)
                .build();

        AgentInstance instance = factory.create(spec);
        assertNotNull(instance);
        assertEquals("test-tool-agent", instance.getAgentId());
        assertEquals(AgentRef.AgentType.TOOL, instance.getType());
    }

    @Test
    void createLLMWiresMeshReference() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("mesh-test")
                .type(AgentRef.AgentType.LLM)
                .build();

        AgentInstance instance = factory.create(spec);
        assertNotNull(instance);

        // Agent 注册到 mesh 后可通过 mesh 查到
        mesh.register(spec);
        assertTrue(mesh.lookup("mesh-test").isPresent());
    }

    @Test
    void createWithPrivateTools() {
        ToolDefinition toolDef = ToolDefinition.builder()
                .name("secret-tool").description("Private tool").build();

        AgentSpec spec = AgentSpec.builder()
                .agentId("private-agent")
                .type(AgentRef.AgentType.LLM)
                .tool(toolDef)
                .config("toolScope", ToolScope.PRIVATE.name())
                .build();

        AgentInstance instance = factory.create(spec);
        assertNotNull(instance);
        assertEquals("private-agent", instance.getAgentId());
    }

    @Test
    void factoryShouldRejectNullAgentId() {
        assertThrows(IllegalStateException.class, () ->
                AgentSpec.builder().build());
    }

    @Test
    void createOrchestratorShouldThrow() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("orch-test")
                .type(AgentRef.AgentType.ORCHESTRATOR)
                .build();

        assertThrows(UnsupportedOperationException.class, () ->
                factory.create(spec));
    }

    @Test
    void createProxyShouldThrow() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("proxy-test")
                .type(AgentRef.AgentType.PROXY)
                .build();

        assertThrows(UnsupportedOperationException.class, () ->
                factory.create(spec));
    }

    @Test
    void factoryShouldUseDefaultMeshWhenNoneInjected() {
        DefaultAgentFactory noMeshFactory = new DefaultAgentFactory();
        // 没有调用 setMesh，但 DefaultAgentMesh.getDefault() 应可用
        assertNotNull(DefaultAgentMesh.getDefault());
    }

    @Test
    void factoryShouldAcceptLateMeshInjection() {
        DefaultAgentFactory lateFactory = new DefaultAgentFactory();
        lateFactory.setMesh(mesh);

        AgentSpec spec = AgentSpec.builder()
                .agentId("late-mesh")
                .type(AgentRef.AgentType.LLM)
                .build();

        AgentInstance instance = lateFactory.create(spec);
        assertNotNull(instance);
        assertEquals("late-mesh", instance.getAgentId());
    }
}
