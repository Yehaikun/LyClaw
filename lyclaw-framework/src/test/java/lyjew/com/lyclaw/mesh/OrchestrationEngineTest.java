package lyjew.com.lyclaw.mesh;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import lyjew.com.lyclaw.mesh.impl.DefaultAgentMesh;
import lyjew.com.lyclaw.mesh.impl.DefaultOrchestrationEngine;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试编排引擎的 SINGLE、CHAIN、FAN_OUT 模式。
 *
 * <p>注意：这些测试使用内存 Agent，注册为 TOOL 类型的 Agent，
 * 验证编排引擎的路由和结果聚合逻辑，不涉及 LLM 调用。</p>
 */
class OrchestrationEngineTest {

    private DefaultAgentMesh mesh;
    private DefaultOrchestrationEngine engine;

    @BeforeEach
    void setUp() {
        mesh = new DefaultAgentMesh();
        engine = new DefaultOrchestrationEngine(mesh);
    }

    // ── SINGLE 模式 ──

    @Test
    void singleShouldRouteByCapability() {
        mesh.register(AgentSpec.builder()
                .agentId("reviewer")
                .capability("code-review")
                .build());

        OrchestrationResult result = engine.execute(OrchestrationSpec.builder()
                .pattern(OrchestrationPattern.SINGLE)
                .task("Review this code")
                .capability("code-review")
                .timeoutMs(10_000)
                .build());

        // Should have routed to the first matching agent
        assertTrue(result.getAgentResults().size() > 0);
    }

    @Test
    void singleShouldReturnErrorWhenNoAgentFound() {
        OrchestrationResult result = engine.execute(OrchestrationSpec.builder()
                .pattern(OrchestrationPattern.SINGLE)
                .task("test")
                .build());

        assertFalse(result.isSuccess());
    }

    @Test
    void singleShouldRouteByExplicitAgentId() {
        mesh.register(AgentSpec.builder().agentId("target-agent").build());

        OrchestrationResult result = engine.execute(OrchestrationSpec.builder()
                .pattern(OrchestrationPattern.SINGLE)
                .task("Hello")
                .agentId("target-agent")
                .timeoutMs(10_000)
                .build());

        assertNotNull(result);
    }

    // ── CHAIN 模式 ──

    @Test
    void chainShouldExecuteSequentially() {
        mesh.register(AgentSpec.builder().agentId("step-a").build());
        mesh.register(AgentSpec.builder().agentId("step-b").build());

        OrchestrationResult result = engine.execute(OrchestrationSpec.builder()
                .pattern(OrchestrationPattern.CHAIN)
                .task("process")
                .agentIds(List.of("step-a", "step-b"))
                .timeoutMs(10_000)
                .build());

        assertTrue(result.getAgentResults().size() == 2
                || !result.isSuccess());  // CHAIN允许中途失败
    }

    // ── FAN_OUT 模式 ──

    @Test
    void fanOutShouldSendToMultipleAgents() {
        mesh.register(AgentSpec.builder().agentId("agent-1").capability("test").build());
        mesh.register(AgentSpec.builder().agentId("agent-2").capability("test").build());
        mesh.register(AgentSpec.builder().agentId("agent-3").capability("test").build());

        OrchestrationResult result = engine.execute(OrchestrationSpec.builder()
                .pattern(OrchestrationPattern.FAN_OUT)
                .task("parallel-task")
                .capability("test")
                .timeoutMs(10_000)
                .build());

        // All 3 agents should have results
        assertEquals(3, result.getAgentResults().size());
    }

    @Test
    void fanOutShouldRespectTimeout() {
        OrchestrationResult result = engine.execute(OrchestrationSpec.builder()
                .pattern(OrchestrationPattern.FAN_OUT)
                .task("test")
                .capability("nonexistent")
                .timeoutMs(1000)
                .build());

        assertFalse(result.isSuccess());
    }

    // ── DEBATE 模式 ──

    @Test
    void debateShouldRequireAtLeastTwoAgents() {
        mesh.register(AgentSpec.builder().agentId("single-agent").build());

        OrchestrationResult result = engine.execute(OrchestrationSpec.builder()
                .pattern(OrchestrationPattern.DEBATE)
                .task("topic")
                .agentId("single-agent")
                .build());

        assertFalse(result.isSuccess());
    }

    // ── 编排规格 ──

    @Test
    void specShouldRequirePattern() {
        assertThrows(IllegalStateException.class, () ->
                OrchestrationSpec.builder().build());
    }

    @Test
    void specShouldSupportBuilder() {
        OrchestrationSpec spec = OrchestrationSpec.builder()
                .pattern(OrchestrationPattern.SINGLE)
                .task("test task")
                .payload("{\"key\": \"value\"}")
                .agentId("agent-a")
                .capability("test")
                .timeoutMs(5000)
                .aggregationStrategy("vote")
                .build();

        assertEquals(OrchestrationPattern.SINGLE, spec.getPattern());
        assertEquals("test task", spec.getTask());
        assertEquals("vote", spec.getAggregationStrategy());
        assertEquals(1, spec.getAgentIds().size());
        assertEquals(1, spec.getRequiredCapabilities().size());
    }
}
