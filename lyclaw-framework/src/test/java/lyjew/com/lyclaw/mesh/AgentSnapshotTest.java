package lyjew.com.lyclaw.mesh;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import lyjew.com.lyclaw.mesh.impl.DefaultAgentMesh;

/**
 * 测试 AgentSnapshot 快照：
 * - 从 AgentInstance 创建快照
 * - 快照包含正确的元数据
 * - 调用历史格式化
 * - Spec 重建
 */
class AgentSnapshotTest {

    @Test
    void shouldCreateSnapshotFromInstance() {
        DefaultAgentMesh mesh = new DefaultAgentMesh();
        mesh.register(AgentSpec.builder()
                .agentId("snapshot-test")
                .name("Snapshot Agent")
                .capability("test")
                .build());

        AgentSnapshot snapshot = mesh.snapshot("snapshot-test");
        assertNotNull(snapshot);
        assertEquals("snapshot-test", snapshot.getAgentId());
    }

    @Test
    void snapshotShouldContainSpec() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("spec-test")
                .model("deepseek-v4")
                .systemPrompt("You are a test agent")
                .build();

        DefaultAgentMesh mesh = new DefaultAgentMesh();
        mesh.register(spec);

        AgentSnapshot snapshot = mesh.snapshot("spec-test");
        assertNotNull(snapshot.getSpec());
        assertEquals("deepseek-v4", snapshot.getSpec().getModel());
        assertEquals("You are a test agent", snapshot.getSpec().getSystemPrompt());
    }

    @Test
    void snapshotShouldBuildSpecForRestore() {
        AgentSpec original = AgentSpec.builder()
                .agentId("restore-test")
                .name("Restorable")
                .build();

        DefaultAgentMesh mesh = new DefaultAgentMesh();
        mesh.register(original);

        AgentSnapshot snapshot = mesh.snapshot("restore-test");
        AgentSpec restored = snapshot.toSpec();

        assertNotNull(restored);
        assertEquals("restore-test", restored.getAgentId());
    }

    @Test
    void snapshotShouldHandleNullInstance() {
        DefaultAgentMesh mesh = new DefaultAgentMesh();
        AgentSnapshot snapshot = mesh.snapshot("nonexistent");
        assertNull(snapshot);
    }

    @Test
    void snapshotAllShouldReturnAllAgents() {
        DefaultAgentMesh mesh = new DefaultAgentMesh();
        mesh.register(AgentSpec.builder().agentId("agent-1").build());
        mesh.register(AgentSpec.builder().agentId("agent-2").build());

        assertEquals(2, mesh.snapshotAll().size());
    }

    @Test
    void snapshotShouldCaptureCallHistory() {
        AgentCallHistory history = new AgentCallHistory("test-agent");
        history.recordCall("child", "do something", "cid-1", 300_000);

        AgentCallHistory.ChildCall call = history.getCall("cid-1");
        assertNotNull(call);
        assertEquals("child", call.getChildAgentId());
        assertEquals("RUNNING", call.getStatus());
    }

    @Test
    void snapshotFormatShouldHandleEmptyHistory() {
        AgentCallHistory history = new AgentCallHistory("test-agent");
        String formatted = history.formatCallTree();
        assertEquals("", formatted);  // 空历史返回空字符串
    }
}
