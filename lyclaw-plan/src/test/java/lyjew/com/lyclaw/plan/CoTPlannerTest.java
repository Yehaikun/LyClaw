package lyjew.com.lyclaw.plan;

import lyjew.com.lyclaw.plan.impl.CoTPlanner;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.task.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoTPlannerTest {

    private CoTPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new CoTPlanner();
    }

    @Test
    @DisplayName("Plan with reasoning intent should produce THINK→ACT→OBSERVE chain")
    void planReasoningIntent() {
        TaskPlan plan = planner.plan(null, "why does the system crash when loading large files");
        assertNotNull(plan);
        List<TaskNode> nodes = plan.getNodes();
        assertTrue(nodes.size() >= 3, "CoT plan should have at least 3 steps");

        // First step should be THINK
        assertEquals("THINK", nodes.get(0).getType());
        // Last step should be OBSERVE
        assertEquals("OBSERVE", nodes.get(nodes.size() - 1).getType());
    }

    @Test
    @DisplayName("Plan simple task should have default 3 steps")
    void planSimple() {
        TaskPlan plan = planner.plan(null, "what is the time now");
        List<TaskNode> nodes = plan.getNodes();
        assertEquals(3, nodes.size());
    }

    @Test
    @DisplayName("Plan should have correct dependencies forming a chain")
    void planDependencies() {
        TaskPlan plan = planner.plan(null, "analyze the database performance issue");
        List<TaskNode> nodes = plan.getNodes();
        assertFalse(nodes.isEmpty());

        // First node should have no dependencies
        assertTrue(nodes.get(0).getDependencies().isEmpty());

        // Subsequent nodes should depend on the previous
        for (int i = 1; i < nodes.size(); i++) {
            assertEquals(1, nodes.get(i).getDependencies().size());
        }
    }

    @Test
    @DisplayName("Plan without intent should use context or default")
    void planNoIntent() {
        TaskPlan plan = planner.plan(null, null);
        assertNotNull(plan);
        assertFalse(plan.getNodes().isEmpty());
    }

    @Test
    @DisplayName("Plan without context should use default intent")
    void planNoContext() {
        TaskPlan plan = planner.plan(null);
        assertNotNull(plan);
        assertFalse(plan.getNodes().isEmpty());
    }

    @Test
    @DisplayName("Revise with low quality should regenerate")
    void reviseLowQuality() {
        TaskNode node = new TaskNode("cot-1", "THINK", "Original thought",
                List.of(), List.of(), 1000);
        TaskPlan original = new SimpleTaskPlan(List.of(node));

        ReflectionFeedback feedback = ReflectionFeedback.builder()
                .qualityScore(0.1).build();

        TaskPlan revised = planner.revise(original, feedback);
        assertNotNull(revised);
        assertFalse(revised.getNodes().isEmpty());
    }

    @Test
    @DisplayName("Revise with verify strategy should add verify step")
    void reviseAddVerify() {
        TaskNode t1 = new TaskNode("cot-1", "THINK", "Think about problem",
                List.of(), List.of(), 1000);
        TaskNode t2 = new TaskNode("cot-2", "ACT", "Act on problem",
                List.of(), List.of("cot-1"), 1000);
        TaskNode t3 = new TaskNode("cot-3", "OBSERVE", "Observe result",
                List.of(), List.of("cot-2"), 1000);
        TaskPlan original = new SimpleTaskPlan(List.of(t1, t2, t3));

        ReflectionFeedback feedback = ReflectionFeedback.builder()
                .qualityScore(0.5)
                .suggestedStrategy("verify the chain").build();

        TaskPlan revised = planner.revise(original, feedback);
        assertNotNull(revised);
        assertTrue(revised.getNodes().size() >= original.getNodes().size(),
                "Revised should have at least original count");
    }

    @Test
    @DisplayName("Revise with null should return null/original")
    void reviseNull() {
        assertNull(planner.revise(null, ReflectionFeedback.builder().build()));

        TaskNode node = new TaskNode("x", "THINK", "test", List.of(), List.of(), 1000);
        TaskPlan original = new SimpleTaskPlan(List.of(node));
        assertSame(original, planner.revise(original, null));
    }

    @Test
    @DisplayName("Optimize should produce THINK→ACT→OBSERVE plan")
    void optimize() {
                AgentResult result = new AgentResult("test-agent", "completed", "CoT reasoning completed with average accuracy", null, 1000L);

        TaskPlan plan = planner.optimize(result);
        assertNotNull(plan);
        List<TaskNode> nodes = plan.getNodes();
        assertEquals(3, nodes.size());
        assertEquals("THINK", nodes.get(0).getType());
        assertEquals("ACT", nodes.get(1).getType());
        assertEquals("OBSERVE", nodes.get(2).getType());
    }

    @Test
    @DisplayName("Optimize null should return null")
    void optimizeNull() {
        assertNull(planner.optimize(null));
    }

    @Test
    @DisplayName("Decompose should create CoT chain from root")
    void decompose() {
        TaskNode root = new TaskNode("root-cot", "ROOT",
                "Analyze why the deployment failed step by step",
                List.of(), List.of(), 30000);

        PlanGraph graph = planner.decompose(root, DecompositionStrategy.SEQUENTIAL);
        assertNotNull(graph);
        assertTrue(graph.getNodeMap().size() > 1,
                "CoT decompose should create children nodes");
    }

    @Test
    @DisplayName("ACT steps should require tools (in plan method)")
    void actRequiresTools() {
        TaskPlan plan = planner.plan(null, "solve the complex coding bug in the payment module");
        List<TaskNode> nodes = plan.getNodes();
        // ACT nodes should have tool requirements
        boolean hasTooledAct = nodes.stream()
                .filter(n -> "ACT".equals(n.getType()))
                .anyMatch(n -> !n.getRequiredTools().isEmpty());
        assertTrue(hasTooledAct, "ACT nodes should require tools");
    }
}
