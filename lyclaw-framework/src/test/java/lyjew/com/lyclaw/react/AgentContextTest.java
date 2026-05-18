package lyjew.com.lyclaw.react;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.task.TaskNode;

import java.util.List;

/**
 * AgentContext 测试：验证 Lifecycle 枚举、字段合并、线程安全。
 */
@DisplayName("AgentContext 测试")
class AgentContextTest {

    @Test
    @DisplayName("构造后字段应有默认值")
    void shouldHaveDefaultsAfterConstruction() {
        AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

        assertThat(ctx.getSessionId()).isEqualTo("s1");
        assertThat(ctx.getUserMessage()).isEqualTo("hello");
        assertThat(ctx.getSystemPrompt()).isEqualTo("sys");
        assertThat(ctx.getLifecycle()).isEqualTo(AgentContext.Lifecycle.TRANSIENT);
        assertThat(ctx.getSandboxLevel()).isNull();
        assertThat(ctx.isTerminated()).isFalse();
        assertThat(ctx.isPipelineOk()).isFalse();
        assertThat(ctx.getTracing()).isNotNull();
        assertThat(ctx.getNodes()).isEmpty();
        assertThat(ctx.getToolResults()).isEmpty();
        assertThat(ctx.getSuccessCount().get()).isEqualTo(0);
        assertThat(ctx.getFailCount().get()).isEqualTo(0);
    }

    @Test
    @DisplayName("Lifecycle 枚举值正确")
    void shouldSupportAllLifecycleValues() {
        assertThat(AgentContext.Lifecycle.values()).containsExactly(
                AgentContext.Lifecycle.TRANSIENT,
                AgentContext.Lifecycle.SESSION,
                AgentContext.Lifecycle.PERSISTENT);
    }

    @Test
    @DisplayName("setLifecycle 修改生命周期")
    void shouldUpdateLifecycle() {
        AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);
        ctx.setLifecycle(AgentContext.Lifecycle.PERSISTENT);
        assertThat(ctx.getLifecycle()).isEqualTo(AgentContext.Lifecycle.PERSISTENT);
    }

    @Test
    @DisplayName("addNode 追加 TaskNode")
    void shouldAddTaskNodes() {
        AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);
        TaskNode node = new TaskNode("n1", "EXECUTE", "test task", List.of(), List.of(), 30000L);
        ctx.addNode(node);
        ctx.addNode(node);

        assertThat(ctx.getNodes()).hasSize(2);
        assertThat(ctx.getNodes().get(0).getNodeId()).isEqualTo("n1");
    }

    @Test
    @DisplayName("addToolResult 追加工具执行结果")
    void shouldAddToolResults() {
        AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);
        ctx.addToolResult("result1");
        ctx.addToolResult("result2");

        assertThat(ctx.getToolResults()).containsExactly("result1", "result2");
    }

    @Test
    @DisplayName("setAttribute/getAttribute 存取扩展属性")
    void shouldStoreAndRetrieveAttributes() {
        AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);
        ctx.setAttribute("key", "value");
        ctx.setAttribute("num", 42);

        assertThat(ctx.<String>getAttribute("key")).isEqualTo("value");
        assertThat(ctx.<Integer>getAttribute("num")).isEqualTo(42);
    }

    @Test
    @DisplayName("terminated 标志控制后续阶段跳过")
    void shouldSupportTerminatedFlag() {
        AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);
        assertThat(ctx.isTerminated()).isFalse();

        ctx.setTerminated(true);
        assertThat(ctx.isTerminated()).isTrue();
    }

    @Test
    @DisplayName("sandboxLevel 可设置")
    void shouldSetSandboxLevel() {
        AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);
        ctx.setSandboxLevel(SandboxLevel.DIRECT);
        assertThat(ctx.getSandboxLevel()).isEqualTo(SandboxLevel.DIRECT);

        ctx.setSandboxLevel(SandboxLevel.PROCESS);
        assertThat(ctx.getSandboxLevel()).isEqualTo(SandboxLevel.PROCESS);
    }

    @Test
    @DisplayName("successCount/failCount 原子操作")
    void shouldIncrementCountersAtomically() {
        AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);
        ctx.getSuccessCount().incrementAndGet();
        ctx.getSuccessCount().incrementAndGet();
        ctx.getFailCount().incrementAndGet();

        assertThat(ctx.getSuccessCount().get()).isEqualTo(2);
        assertThat(ctx.getFailCount().get()).isEqualTo(1);
    }

    @Test
    @DisplayName("tracing 自动创建非空 TraceContext")
    void shouldCreateTracingAutomatically() {
        AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);
        assertThat(ctx.getTracing()).isNotNull();
        assertThat(ctx.getTracing().getTraceId()).isNotNull();
        assertThat(ctx.getTracing().getTraceId()).isNotEmpty();
    }
}
