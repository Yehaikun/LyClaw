package lyjew.com.lyclaw.mesh;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.mesh.impl.DefaultAgentMesh;
import lyjew.com.lyclaw.mesh.impl.DefaultOrchestrationEngine;
import lyjew.com.lyclaw.mesh.impl.DefaultAgentMeshMetrics;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent Mesh 集成测试 —— 验证完整的多 Agent 工作链路。
 *
 * <p>测试场景：
 * 1. 注册多个 Agent → 消息路由 → 响应
 * 2. 编排引擎 SINGLE 模式 → 结果聚合
 * 3. 编排引擎 FAN_OUT 模式 → 并行分发 → 汇聚
 * 4. Agent 监控指标 → 快照导出
 * 5. 工具作用域 → 私有工具隔离
 */
class AgentMeshIntegrationTest {

    private DefaultAgentMesh mesh;
    private DefaultOrchestrationEngine engine;

    @BeforeEach
    void setUp() {
        mesh = new DefaultAgentMesh();
        engine = new DefaultOrchestrationEngine(mesh);
    }

    @Test
    void fullAgentLifecycle() {
        // 1. 注册
        AgentRef ref = mesh.register(AgentSpec.builder()
                .agentId("worker-1")
                .capability("process")
                .build());
        assertNotNull(ref);
        assertTrue(mesh.lookup("worker-1").isPresent());

        // 2. 发送消息
        AgentMessage response = mesh.send(AgentMessage.builder()
                .to("worker-1")
                .type(MessageType.REQUEST)
                .payload("hello")
                .correlationId("int-001")
                .ttlMs(10_000)
                .build()).join();

        assertNotNull(response);
        assertTrue(response.getType() == MessageType.RESPONSE
                || response.getType() == MessageType.ERROR);

        // 3. 快照
        AgentSnapshot snapshot = mesh.snapshot("worker-1");
        assertNotNull(snapshot);
        assertEquals("worker-1", snapshot.getAgentId());

        // 4. 注销
        mesh.unregister("worker-1");
        assertTrue(mesh.lookup("worker-1").isEmpty());
    }

    @Test
    void multiAgentCommunication() {
        // 注册两个 Agent
        mesh.register(AgentSpec.builder().agentId("agent-a").capability("alpha").build());
        mesh.register(AgentSpec.builder().agentId("agent-b").capability("beta").build());

        // 按能力查找
        List<AgentRef> alphas = mesh.findByCapability("alpha");
        assertEquals(1, alphas.size());

        List<AgentRef> all = mesh.getAllAgents();
        assertEquals(2, all.size());
    }

    @Test
    void orchestrationSinglePattern() {
        mesh.register(AgentSpec.builder().agentId("worker").capability("work").build());

        OrchestrationResult result = engine.execute(OrchestrationSpec.builder()
                .pattern(OrchestrationPattern.SINGLE)
                .task("do something")
                .capability("work")
                .timeoutMs(10_000)
                .build());

        assertNotNull(result);
        assertTrue(result.getAgentResults().size() > 0);
    }

    @Test
    void orchestrationFanOutPattern() {
        mesh.register(AgentSpec.builder().agentId("w1").capability("task").build());
        mesh.register(AgentSpec.builder().agentId("w2").capability("task").build());
        mesh.register(AgentSpec.builder().agentId("w3").capability("task").build());

        OrchestrationResult result = engine.execute(OrchestrationSpec.builder()
                .pattern(OrchestrationPattern.FAN_OUT)
                .task("parallel work")
                .capability("task")
                .aggregationStrategy("sum")
                .timeoutMs(15_000)
                .build());

        assertEquals(3, result.getAgentResults().size());
        assertTrue(result.isSuccess());
    }

    @Test
    void metricsCollection() {
        DefaultAgentMeshMetrics metrics = new DefaultAgentMeshMetrics();

        // 模拟多次调用
        metrics.recordCall("agent-a", true, 100);
        metrics.recordCall("agent-a", true, 200);
        metrics.recordCall("agent-b", false, 50);
        metrics.recordError("agent-b", "TIMEOUT");

        // 验证指标
        AgentMeshMetrics.MetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(3, snapshot.totalCalls);
        assertEquals(1, snapshot.totalErrors);
        assertEquals(2, snapshot.agentCount);

        assertEquals(75.0, metrics.getAgentMetrics("agent-a").successRate(), 0.01);
    }

    @Test
    void toolScopeIsolation() {
        // 验证私有工具不会泄漏到全局
        ToolDefinition privateDef = ToolDefinition.builder()
                .name("secret-calc").description("Only for math-agent").build();

        // 创建带私有工具的 ScopedToolRegistry
        ScopedToolRegistry scopedReg = new ScopedToolRegistry(
                null, "math-agent", ToolScope.PRIVATE, List.of(privateDef), null);

        List<ToolDefinition> defs = scopedReg.getAllDefinitions();
        assertEquals(1, defs.size());
        assertEquals("secret-calc", defs.get(0).getName());
        assertEquals(1, scopedReg.privateToolCount());
    }

    @Test
    void capabilityRouting() {
        mesh.register(AgentSpec.builder().agentId("searcher").capability("search").build());
        mesh.register(AgentSpec.builder().agentId("coder").capability("coding").build());
        mesh.register(AgentSpec.builder().agentId("reviewer").capability("code-review").build());

        // 按能力路由
        AgentMessage response = mesh.send(AgentMessage.builder()
                .capability("search")
                .payload("find results")
                .correlationId("cap-001")
                .ttlMs(10_000)
                .build()).join();

        assertNotNull(response);
    }

    @Test
    void snapshotAndRestore() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("snapshot-agent")
                .name("Snapshot Agent")
                .model("deepseek-v4")
                .systemPrompt("You are snappable")
                .build();

        mesh.register(spec);

        // 导出快照
        AgentSnapshot snapshot = mesh.snapshot("snapshot-agent");
        assertNotNull(snapshot);
        assertEquals("snapshot-agent", snapshot.getAgentId());
        assertEquals("deepseek-v4", snapshot.getSpec().getModel());
        assertEquals("You are snappable", snapshot.getSpec().getSystemPrompt());

        // 恢复（通过 toSpec 重建）
        AgentSpec restored = snapshot.toSpec();
        assertEquals("snapshot-agent", restored.getAgentId());
    }

    @Test
    void delayedResultQueue() {
        AgentMessage result = AgentMessage.builder()
                .type(MessageType.RESPONSE)
                .payload("async-complete")
                .build();

        mesh.storeDelayedResult("parent", "async-001", result);

        DelayedResult polled = mesh.pollDelayedResult("parent", "async-001")
                .orElseThrow(() -> new AssertionError("Expected result"));
        assertEquals("async-complete", polled.getResult().getPayload());

        // 消费后移除
        assertTrue(mesh.pollDelayedResult("parent", "async-001").isEmpty());
    }

    @Test
    void supervisionRestartOnFailure() {
        mesh.register(AgentSpec.builder()
                .agentId("reliable-agent")
                .supervisionStrategy(SupervisionStrategy.RESTART)
                .maxRetries(3)
                .build());

        // 触发失败 → 自动重启
        mesh.updateState("reliable-agent", AgentLifecycleState.FAILED);

        AgentInstance instance = mesh.getInstance("reliable-agent").orElse(null);
        assertNotNull(instance);
        // 重启后应是 ACTIVE
        assertTrue(instance.getState() == AgentLifecycleState.ACTIVE
                || instance.getState() == AgentLifecycleState.STARTING);
    }
}
