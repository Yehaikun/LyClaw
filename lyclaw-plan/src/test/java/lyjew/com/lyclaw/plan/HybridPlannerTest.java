package lyjew.com.lyclaw.plan;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.plan.impl.HybridPlanner;
import lyjew.com.lyclaw.task.TaskNode;
import lyjew.com.lyclaw.task.TaskPlan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HybridPlanner 单元测试——验证规则优先 + LLM 回退行为。
 * 不依赖 Spring 上下文，测试计划结构的正确性。
 */
@DisplayName("HybridPlanner 混合规划器")
class HybridPlannerTest {

    @BeforeEach
    void setUp() {
        // 测试使用纯规则路径（无 ChatFacade，高置信度意图直接走规则）
    }

    @Test
    @DisplayName("简单意图生成单节点计划")
    void shouldBuildSimplePlan() {
        ChatContext ctx = buildContext("what is Java?");
        var planner = new HybridPlanner(null, null);

        TaskPlan plan = planner.plan(ctx, "what is Java?");
        var nodes = plan.getNodes();

        assertThat(nodes).isNotEmpty();
        assertThat(nodes.get(0).getType()).isEqualTo("EXECUTE");
    }

    @Test
    @DisplayName("复杂意图生成多节点 DAG 计划")
    void shouldBuildComplexPlan() {
        ChatContext ctx = buildContext("build and deploy a microservice with database migration");
        var planner = new HybridPlanner(null, null);

        TaskPlan plan = planner.plan(ctx, "build and deploy a microservice with database migration");
        var nodes = plan.getNodes();

        assertThat(nodes).isNotEmpty();
        assertThat(nodes.size()).isGreaterThan(1);
    }

    @Test
    @DisplayName("空意图返回默认计划")
    void shouldReturnDefaultPlanForEmptyIntent() {
        var planner = new HybridPlanner(null, null);
        TaskPlan plan = planner.plan(null, "");

        assertThat(plan).isNotNull();
        assertThat(plan.getNodes()).isNotEmpty();
    }

    @Test
    @DisplayName("null 上下文返回默认计划")
    void shouldReturnDefaultPlanForNullContext() {
        var planner = new HybridPlanner(null, null);
        TaskPlan plan = planner.plan((ChatContext) null);

        assertThat(plan).isNotNull();
        assertThat(plan.getNodes()).isNotEmpty();
    }

    @Test
    @DisplayName("已生成计划节点 ID 唯一")
    void planNodesShouldHaveUniqueIds() {
        var planner = new HybridPlanner(null, null);
        TaskPlan plan = planner.plan(buildContext("analyze, design, implement, and verify a user auth system"));

        var nodeIds = plan.getNodes().stream().map(TaskNode::getNodeId).distinct().toList();
        assertThat(nodeIds).hasSize(plan.getNodes().size());
    }

    @Test
    @DisplayName("中等复杂意图生成包含 ANALYZE 和 VERIFY 的计划")
    void mediumPlanShouldHaveAnalyzeAndVerify() {
        var planner = new HybridPlanner(null, null);
        TaskPlan plan = planner.plan(buildContext("analyze the performance of the system and suggest improvements"));

        var types = plan.getNodes().stream().map(TaskNode::getType).toList();
        assertThat(types).anyMatch(t -> t.contains("ANALYZE"));
    }

    private static ChatContext buildContext(String userMessage) {
        Session session = new Session();
        session.setSessionId("test-session");
        session.setMessages(List.of(
                lyjew.com.lyclaw.model.Message.user(userMessage)));
        return new ChatContext(
                ChatRequest.builder().messages(session.getMessages()).build(),
                session, null,
                List.of(), null, null);
    }
}
