package lyjew.com.lyclaw.react;

import lyjew.com.lyclaw.action.ActionResult;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.reflect.ReflectionEngine;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import lyjew.com.lyclaw.task.ReflectionFeedback;
import lyjew.com.lyclaw.task.TaskNode;
import lyjew.com.lyclaw.task.TaskPlan;
import lyjew.com.lyclaw.task.SimpleTaskPlan;
import lyjew.com.lyclaw.task.TaskPlanner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReflexionLoop 测试：自校正循环的完整流程。
 */
@DisplayName("ReflexionLoop 自校正循环")
class ReflexionLoopTest {

    private ReflectionEngine engine;
    private TaskPlanner planner;
    private ReflexionLoop loop;
    private ChatContext context;

    @BeforeEach
    void setUp() {
        engine = new StubReflectionEngine();
        planner = new StubTaskPlanner();
        loop = new ReflexionLoop(engine, planner, 2, 0.7);
        Session session = new Session();
        session.setSessionId("test-session");
        context = new ChatContext(ChatRequest.builder().build(), session, List.of(), null, null);
    }

    @Test
    @DisplayName("首次尝试成功不触发重试")
    void shouldNotRetryWhenFirstAttemptSucceeds() {
        TaskPlan plan = new SimpleTaskPlan(List.of(
                new TaskNode("n1", "EXECUTE", "test", List.of(), List.of(), 1000L)));

        AtomicInteger executionCount = new AtomicInteger(0);
        ReflexionLoop.StepExecutor executor = (nodeId, desc) -> {
            executionCount.incrementAndGet();
            return ActionResult.builder()
                    .nodeId(nodeId).success(true).output("done").durationMs(5).build();
        };

        ReflexionResult result = loop.execute(plan, context, executor);

        assertThat(result.getTotalAttempts()).isEqualTo(1);
        assertThat(result.isSuccess()).isTrue();
        assertThat(executionCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("失败触发重试直到质量达标或达上限")
    void shouldRetryOnFailure() {
        TaskPlan plan = new SimpleTaskPlan(List.of(
                new TaskNode("n1", "EXECUTE", "test", List.of(), List.of(), 1000L)));

        AtomicInteger executionCount = new AtomicInteger(0);
        ReflexionLoop.StepExecutor executor = (nodeId, desc) -> {
            executionCount.incrementAndGet();
            boolean success = executionCount.get() > 1; // 第一次失败，后续成功
            return ActionResult.builder()
                    .nodeId(nodeId).success(success)
                    .output(success ? "retry ok" : "fail")
                    .durationMs(5).build();
        };

        ReflexionResult result = loop.execute(plan, context, executor);

        assertThat(result.getTotalAttempts()).isGreaterThan(1);
        assertThat(executionCount.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("超过最大重试次数后停止")
    void shouldStopAfterMaxRetries() {
        // maxRetries=1: 总共2次尝试
        ReflexionLoop limitedLoop = new ReflexionLoop(engine, planner, 1, 0.9);

        TaskPlan plan = new SimpleTaskPlan(List.of(
                new TaskNode("n1", "EXECUTE", "test", List.of(), List.of(), 1000L)));

        AtomicInteger executionCount = new AtomicInteger(0);
        ReflexionLoop.StepExecutor executor = (nodeId, desc) -> {
            executionCount.incrementAndGet();
            return ActionResult.builder()
                    .nodeId(nodeId).success(false).output("always fail").durationMs(5).build();
        };

        ReflexionResult result = limitedLoop.execute(plan, context, executor);

        // maxRetries=1 → 2次尝试（初始 + 1次重试）
        assertThat(result.getTotalAttempts()).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("getBestAttempt 返回最高质量分尝试")
    void shouldReturnBestAttempt() {
        TaskPlan plan = new SimpleTaskPlan(List.of(
                new TaskNode("n1", "EXECUTE", "test", List.of(), List.of(), 1000L)));

        ReflexionResult result = loop.execute(plan, context, (nodeId, desc) ->
                ActionResult.builder().nodeId(nodeId).success(true)
                        .output("ok").durationMs(5).build());

        var best = result.getBestAttempt();
        assertThat(best).isNotNull();
        assertThat(best.qualityScore()).isGreaterThanOrEqualTo(0.0);
    }

    // ── Stubs ──

    private static class StubReflectionEngine implements ReflectionEngine {
        @Override
        public ReflectionReport reflect(ChatContext context, ActionResult result) {
            return ReflectionReport.builder()
                    .reflectionId("r-1")
                    .overallScore(result.isSuccess() ? 0.8 : 0.2)
                    .build();
        }

        @Override
        public lyjew.com.lyclaw.reflect.QualityAssessment assessQuality(
                String output, lyjew.com.lyclaw.reflect.QualityCriteria criteria) {
            return null;
        }

        @Override
        public List<lyjew.com.lyclaw.reflect.DetectedError> detectErrors(
                String output, List<String> groundTruth) {
            return List.of();
        }

        @Override
        public lyjew.com.lyclaw.reflect.StrategyAdjustment suggestAdjustment(
                ReflectionReport report) {
            return null;
        }
    }

    private static class StubTaskPlanner implements TaskPlanner {
        @Override
        public TaskPlan plan(ChatContext context) { return plan(context, null); }

        @Override
        public TaskPlan plan(ChatContext context, String userIntent) {
            return new SimpleTaskPlan(List.of(
                    new TaskNode("n1", "EXECUTE", "test", List.of(), List.of(), 1000L)));
        }

        @Override
        public TaskPlan revise(TaskPlan original, ReflectionFeedback feedback) {
            return original;
        }

        @Override
        public TaskPlan optimize(lyjew.com.lyclaw.dto.AgentResult previousResult) {
            return null;
        }

        @Override
        public lyjew.com.lyclaw.task.PlanGraph decompose(
                TaskNode rootTask, lyjew.com.lyclaw.task.DecompositionStrategy strategy) {
            return new lyjew.com.lyclaw.task.PlanGraph();
        }
    }
}
