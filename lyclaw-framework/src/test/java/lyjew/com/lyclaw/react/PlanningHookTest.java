package lyjew.com.lyclaw.react;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.task.TaskNode;

/**
 * PlanningHook 测试：计划上下文注入和进度跟踪。
 */
@DisplayName("PlanningHook 测试")
class PlanningHookTest {

    private PlanningHook hook;
    private AgentContext ctx;

    @BeforeEach
    void setUp() {
        hook = new PlanningHook();
        ctx = new AgentContext("s1", "hello", "You are a helpful assistant.", null, null, null);
    }

    @Nested
    @DisplayName("beforeModel — 计划注入")
    class BeforeModelTests {

        @Test
        @DisplayName("nodes 为空 → 返回原消息列表")
        void shouldReturnOriginalWhenNoNodes() {
            List<Message> original = List.of(Message.user("hello"));
            List<Message> result = hook.beforeModel(original, ctx);

            assertThat(result).isSameAs(original);
        }

        @Test
        @DisplayName("nodes 非空 → 注入计划系统消息")
        void shouldInjectPlanContextWhenNodesExist() {
            ctx.addNode(new TaskNode("t1", "EXECUTE", "搜索天气信息",
                    List.of("web_search"), List.of(), 30000L));
            ctx.addNode(new TaskNode("t2", "ANALYZE", "分析天气数据",
                    List.of(), List.of("t1"), 30000L));
            ctx.addNode(new TaskNode("t3", "EXECUTE", "生成天气报告",
                    List.of("file_write"), List.of("t2"), 30000L));

            List<Message> original = List.of(Message.user("查一下北京天气"));
            List<Message> result = hook.beforeModel(original, ctx);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getRole()).isEqualTo("system");
            assertThat(result.get(0).getContent())
                    .contains("[Current Plan]")
                    .contains("搜索天气信息")
                    .contains("分析天气数据")
                    .contains("生成天气报告")
                    .contains("Progress: 0/3");
        }

        @Test
        @DisplayName("注入的计划包含工具信息")
        void shouldIncludeRequiredToolsInPlanContext() {
            ctx.addNode(new TaskNode("t1", "EXECUTE", "执行命令",
                    List.of("shell_exec", "file_read"), List.of(), 30000L));

            List<Message> result = hook.beforeModel(List.of(Message.user("run cmd")), ctx);

            assertThat(result.get(0).getContent())
                    .contains("shell_exec")
                    .contains("file_read");
        }

        @Test
        @DisplayName("已完成任务标记为 [done]")
        void shouldMarkCompletedTasks() {
            ctx.addNode(new TaskNode("t1", "EXECUTE", "任务A", List.of(), List.of(), 30000L));
            ctx.addNode(new TaskNode("t2", "EXECUTE", "任务B", List.of(), List.of(), 30000L));
            ctx.addNode(new TaskNode("t3", "EXECUTE", "任务C", List.of(), List.of(), 30000L));

            // 标记 2 个已完成（successCount=2）
            ctx.getSuccessCount().incrementAndGet();
            ctx.getSuccessCount().incrementAndGet();

            List<Message> result = hook.beforeModel(List.of(Message.user("continue")), ctx);

            String content = result.get(0).getContent();
            assertThat(content).contains("[done] 任务A");
            assertThat(content).contains("[done] 任务B");
            assertThat(content).contains("[pending] 任务C");
            assertThat(content).contains("Progress: 2/3");
        }

        @Test
        @DisplayName("含 failCount 的进度显示正确")
        void shouldIncludeFailCountInProgress() {
            ctx.addNode(new TaskNode("t1", "EXECUTE", "任务A", List.of(), List.of(), 30000L));
            ctx.addNode(new TaskNode("t2", "EXECUTE", "任务B", List.of(), List.of(), 30000L));

            ctx.getFailCount().incrementAndGet();

            List<Message> result = hook.beforeModel(List.of(Message.user("continue")), ctx);

            assertThat(result.get(0).getContent()).contains("Progress: 1/2");
        }
    }

    @Nested
    @DisplayName("afterModel — 进度日志")
    class AfterModelTests {

        @Test
        @DisplayName("nodes 为空 → 返回原响应")
        void shouldReturnOriginalWhenNoNodes() {
            String response = "Task completed.";
            String result = hook.afterModel(response, ctx);

            assertThat(result).isSameAs(response);
        }

        @Test
        @DisplayName("nodes 非空 → 不修改响应")
        void shouldNotModifyResponseWhenNodesExist() {
            ctx.addNode(new TaskNode("t1", "EXECUTE", "任务A", List.of(), List.of(), 30000L));
            ctx.getSuccessCount().incrementAndGet();

            String response = "I have completed task A.";
            String result = hook.afterModel(response, ctx);

            assertThat(result).isEqualTo(response);
        }
    }

    @Nested
    @DisplayName("getOrder")
    class OrderTests {

        @Test
        @DisplayName("PlanningHook order = 40")
        void shouldHaveOrder40() {
            assertThat(hook.getOrder()).isEqualTo(40);
        }
    }
}
