package lyjew.com.lyclaw.mesh;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import lyjew.com.lyclaw.model.ToolDefinition;
import java.util.List;

/**
 * AgentSpec 单元测试：
 * - Builder 构建
 * - toRef 转换
 * - 能力管理
 * - 工具绑定
 * - 空 agentId 校验
 */
class AgentSpecTest {

    @Test
    void shouldBuildBasicSpec() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("test-agent")
                .name("Test Agent")
                .description("A test agent")
                .build();

        assertEquals("test-agent", spec.getAgentId());
        assertEquals("Test Agent", spec.getName());
        assertEquals("A test agent", spec.getDescription());
    }

    @Test
    void shouldBuildFullSpec() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("full-agent")
                .type(AgentRef.AgentType.TOOL)
                .model("deepseek-v4")
                .provider("deepseek")
                .systemPrompt("You are helpful")
                .capability("search")
                .capability("fetch")
                .supervisionStrategy(SupervisionStrategy.RESTART)
                .maxRetries(5)
                .build();

        assertEquals("full-agent", spec.getAgentId());
        assertEquals(AgentRef.AgentType.TOOL, spec.getType());
        assertEquals("deepseek-v4", spec.getModel());
        assertEquals(2, spec.getCapabilities().size());
        assertEquals(SupervisionStrategy.RESTART, spec.getSupervisionStrategy());
        assertEquals(5, spec.getMaxRetries());
    }

    @Test
    void toRefShouldPreserveAgentId() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("ref-test")
                .capability("a")
                .capability("b")
                .build();

        AgentRef ref = spec.toRef();
        assertEquals("ref-test", ref.getAgentId());
        assertTrue(ref.hasCapability("a"));
        assertTrue(ref.hasCapability("b"));
    }

    @Test
    void toRefShouldMatchType() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("type-test")
                .type(AgentRef.AgentType.TOOL)
                .build();

        AgentRef ref = spec.toRef();
        assertEquals(AgentRef.AgentType.TOOL, ref.getType());
    }

    @Test
    void shouldSupportTools() {
        ToolDefinition toolDef = ToolDefinition.builder()
                .name("my-tool").description("My tool").build();

        AgentSpec spec = AgentSpec.builder()
                .agentId("tool-agent")
                .tool(toolDef)
                .build();

        assertEquals(1, spec.getTools().size());
        assertEquals("my-tool", spec.getTools().get(0).getName());
    }

    @Test
    void shouldSupportConfig() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("config-agent")
                .config("timeout", 5000)
                .config("retry", true)
                .build();

        assertEquals(5000, spec.getConfig().get("timeout"));
        assertEquals(true, spec.getConfig().get("retry"));
    }

    @Test
    void shouldRejectEmptyAgentId() {
        assertThrows(IllegalStateException.class, () ->
                AgentSpec.builder().build());
    }

    @Test
    void defaultTypeShouldBeLLM() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("default-type")
                .build();
        assertEquals(AgentRef.AgentType.LLM, spec.getType());
    }

    @Test
    void shouldHandleNullCapabilities() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("no-caps")
                .capabilities(null)
                .build();
        assertTrue(spec.getCapabilities().isEmpty());
    }

    @Test
    void shouldSupportImmutableTools() {
        ToolDefinition toolDef = ToolDefinition.builder().name("tool").build();
        List<ToolDefinition> mutableList = new java.util.ArrayList<>();
        mutableList.add(toolDef);

        AgentSpec spec = AgentSpec.builder()
                .agentId("immutable-test")
                .tools(mutableList)
                .build();

        mutableList.add(ToolDefinition.builder().name("extra").build());
        assertEquals(1, spec.getTools().size()); // should not change
    }
}
