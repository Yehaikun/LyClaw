package lyjew.com.lyclaw.react;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.task.TaskNode;
import lyjew.com.lyclaw.task.TaskPlan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentContext 生命周期测试：toSnapshot / restoreFromSnapshot / 工厂方法。
 */
@DisplayName("AgentContext 生命周期")
class AgentContextLifecycleTest {

    private AgentContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new AgentContext("sess-1", "hello world", "You are helpful",
                null, null, null);
    }

    @Test
    @DisplayName("toSnapshot 包含关键字段")
    void toSnapshotShouldContainKeyFields() {
        ctx.setLifecycle(AgentContext.Lifecycle.SESSION);
        ctx.getCurrentStage().set("PLAN");
        ctx.getSuccessCount().set(3);
        ctx.getFailCount().set(1);
        ctx.setPipelineOk(true);
        ctx.addToolResult("result 1");

        var snapshot = ctx.toSnapshot();

        assertThat(snapshot).containsKeys(
                "sessionId", "userMessage", "systemPrompt",
                "sandboxLevel", "lifecycle", "currentStage",
                "successCount", "failCount", "pipelineOk",
                "terminated", "reflectScore", "toolResults", "tracing");
        assertThat(snapshot.get("sessionId")).isEqualTo("sess-1");
        assertThat(snapshot.get("userMessage")).isEqualTo("hello world");
        assertThat(snapshot.get("lifecycle")).isEqualTo("SESSION");
        assertThat(snapshot.get("currentStage")).isEqualTo("PLAN");
        assertThat(snapshot.get("successCount")).isEqualTo(3);
        assertThat(snapshot.get("failCount")).isEqualTo(1);
        assertThat(snapshot.get("pipelineOk")).isEqualTo(true);
        assertThat(snapshot.get("toolResults")).asList().contains("result 1");
    }

    @Test
    @DisplayName("restoreFromSnapshot 恢复状态")
    void restoreFromSnapshotShouldRestoreState() {
        ctx.getCurrentStage().set("PLAN");
        ctx.getSuccessCount().set(5);
        ctx.setPipelineOk(true);

        var snapshot = ctx.toSnapshot();

        AgentContext restored = new AgentContext("sess-2", "msg", "sys", null, null, null);
        restored.restoreFromSnapshot(snapshot);

        assertThat(restored.getCurrentStage().get()).isEqualTo("PLAN");
        assertThat(restored.getSuccessCount().get()).isEqualTo(5);
        assertThat(restored.isPipelineOk()).isTrue();
    }

    @Test
    @DisplayName("restoreFromSnapshot 对 null 安全")
    void restoreFromSnapshotNullSafe() {
        ctx.restoreFromSnapshot(null);
        assertThat(ctx.isTerminated()).isFalse();
    }

    @Test
    @DisplayName("sessionScoped 工厂创建 SESSION 生命周期")
    void sessionScopedFactory() {
        AgentContext sctx = AgentContext.sessionScoped("s1", "msg", "sys", null, null, null);
        assertThat(sctx.getLifecycle()).isEqualTo(AgentContext.Lifecycle.SESSION);
        assertThat(sctx.getSessionId()).isEqualTo("s1");
    }

    @Test
    @DisplayName("persistentScoped 工厂创建 PERSISTENT 生命周期")
    void persistentScopedFactory() {
        AgentContext pctx = AgentContext.persistentScoped("s2", "msg", "sys", null, null, null);
        assertThat(pctx.getLifecycle()).isEqualTo(AgentContext.Lifecycle.PERSISTENT);
        assertThat(pctx.getSessionId()).isEqualTo("s2");
    }

    @Test
    @DisplayName("setAttribute/getAttribute 存取扩展属性")
    void shouldStoreAndRetrieveAttributes() {
        ctx.setAttribute("memoryEntries", List.of("mem1", "mem2"));
        ctx.setAttribute("tool.dynamicFiltering", false);

        assertThat(ctx.<List<String>>getAttribute("memoryEntries")).containsExactly("mem1", "mem2");
        assertThat(ctx.<Boolean>getAttribute("tool.dynamicFiltering")).isFalse();
    }

    @Test
    @DisplayName("addToolResult 累加工具结果")
    void shouldAccumulateToolResults() {
        ctx.addToolResult("result A");
        ctx.addToolResult("result B");
        assertThat(ctx.getToolResults()).containsExactly("result A", "result B");
    }
}
