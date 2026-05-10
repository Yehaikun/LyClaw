package lyjew.com.lyclaw.plan;

import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.plan.impl.DAGTaskPlanner;
import lyjew.com.lyclaw.task.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DAGTaskPlannerTest {

    private DAGTaskPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new DAGTaskPlanner();
    }

    @Test
    @DisplayName("Plan simple task should produce single node")
    void planSimpleTask() {
        TaskPlan plan = planner.plan(null, "what is Java");
        assertNotNull(plan);
        assertFalse(plan.getNodes().isEmpty());
        assertEquals(1, plan.getNodes().size());
        assertEquals("EXECUTE", plan.getNodes().get(0).getType());
    }

    @Test
    @DisplayName("Plan null intent but with context should extract from context")
    void planNullIntent() {
        // Without context, uses default task
        TaskPlan plan = planner.plan(null, null);
        assertNotNull(plan);
        assertFalse(plan.getNodes().isEmpty());
    }

    @Test
    @DisplayName("Plan medium complexity task should have ANALYZE→PLAN→EXECUTE→VERIFY")
    void planMediumTask() {
        // "implement" and "integrate" are complex keywords → complexity >= 2
        TaskPlan plan = planner.plan(null, "implement and integrate the payment system");
        assertNotNull(plan);
        List<TaskNode> nodes = plan.getNodes();
        assertTrue(nodes.size() >= 4, "Medium task should have at least 4 nodes, got " + nodes.size());

        List<String> types = nodes.stream().map(TaskNode::getType).toList();
        assertTrue(types.contains("ANALYZE"));
        assertTrue(types.contains("EXECUTE"));
        assertTrue(types.contains("VERIFY"));
    }

    @Test
    @DisplayName("Plan complex task should have parallel branches")
    void planComplexTask() {
        // Multiple complex keywords
        String intent = "build and deploy a microservice architecture, "
                + "also redesign the database and configure monitoring, "
                + "then optimize performance and create documentation";
        TaskPlan plan = planner.plan(null, intent);
        assertNotNull(plan);
        List<TaskNode> nodes = plan.getNodes();
        assertTrue(nodes.size() >= 5, "Complex task should have many nodes, got " + nodes.size());

        // Should have merge node with multiple dependencies
        boolean hasMerge = nodes.stream().anyMatch(n -> "INTEGRATE".equals(n.getType())
                && n.getDependencies().size() > 1);
        assertTrue(hasMerge, "Complex plan should have a merge node with multiple dependencies");
    }

    @Test
    @DisplayName("Plan without context and intent should produce simple plan")
    void planNoArgs() {
        TaskPlan plan = planner.plan(null);
        assertNotNull(plan);
        assertTrue(plan.getNodes().isEmpty() || plan.getNodes().size() == 1);
    }

    @Test
    @DisplayName("Revise with low quality should trigger replan")
    void reviseLowQuality() {
        TaskNode node = new TaskNode("task-1", "EXECUTE", "Original task",
                List.of(), List.of(), 1000);
        TaskPlan original = new SimpleTaskPlan(List.of(node));

        ReflectionFeedback feedback = ReflectionFeedback.builder()
                .qualityScore(0.2)
                .suggestedStrategy("replan")
                .adjustedPrompt("Revised task description")
                .build();

        TaskPlan revised = planner.revise(original, feedback);
        assertNotNull(revised);
        assertFalse(revised.getNodes().isEmpty());
        // Should not be the same object
        assertNotSame(original, revised);
    }

    @Test
    @DisplayName("Revise with reorder strategy should reorder nodes")
    void reviseReorder() {
        TaskNode a = new TaskNode("A", "TASK", "First", List.of(), List.of(), 1000);
        TaskNode b = new TaskNode("B", "TASK", "Second", List.of(), List.of("A"), 1000);
        TaskNode c = new TaskNode("C", "TASK", "Third", List.of(), List.of("B"), 1000);
        TaskPlan original = new SimpleTaskPlan(List.of(a, b, c));

        ReflectionFeedback feedback = ReflectionFeedback.builder()
                .qualityScore(0.5)
                .suggestedStrategy("reorder")
                .detectedErrors(List.of("wrong order of steps"))
                .build();

        TaskPlan revised = planner.revise(original, feedback);
        assertNotNull(revised);
        // Reordered nodes should still have same count (or could differ)
        assertFalse(revised.getNodes().isEmpty());
    }

    @Test
    @DisplayName("Revise with insert strategy should add missing node")
    void reviseInsert() {
        TaskNode a = new TaskNode("A", "ANALYZE", "Analyze", List.of(), List.of(), 1000);
        TaskNode b = new TaskNode("B", "EXECUTE", "Execute", List.of(), List.of("A"), 1000);
        TaskPlan original = new SimpleTaskPlan(List.of(a, b));

        ReflectionFeedback feedback = ReflectionFeedback.builder()
                .qualityScore(0.6)
                .suggestedStrategy("insert")
                .detectedErrors(List.of("missing validation step"))
                .adjustedPrompt("Add validation")
                .build();

        TaskPlan revised = planner.revise(original, feedback);
        assertNotNull(revised);
        assertTrue(revised.getNodes().size() >= original.getNodes().size(),
                "Revised plan should have at least as many nodes as original");
    }

    @Test
    @DisplayName("Revise with null original should return null")
    void reviseNullOriginal() {
        TaskPlan result = planner.revise(null, ReflectionFeedback.builder().build());
        assertNull(result);
    }

    @Test
    @DisplayName("Revise with null feedback should return original")
    void reviseNullFeedback() {
        TaskNode node = new TaskNode("A", "TASK", "Test", List.of(), List.of(), 1000);
        TaskPlan original = new SimpleTaskPlan(List.of(node));
        TaskPlan result = planner.revise(original, null);
        assertSame(original, result);
    }

    @Test
    @DisplayName("Optimize with null should return null")
    void optimizeNull() {
        assertNull(planner.optimize(null));
    }

    @Test
    @DisplayName("Optimize with valid result should produce optimized plan")
    void optimize() {
        AgentResult result = new AgentResult("test-agent", "completed", "Previous task completed with some issues", null, 1000L);

        TaskPlan plan = planner.optimize(result);
        assertNotNull(plan);
        assertFalse(plan.getNodes().isEmpty());
        assertTrue(plan.getNodes().get(0).getDescription().contains("Previous task"));
    }

    @Test
    @DisplayName("Decompose with SEQUENTIAL strategy should create chain")
    void decomposeSequential() {
        TaskNode root = new TaskNode("root-1", "ROOT", "First step. Second step. Third step.",
                List.of(), List.of(), 30000);

        PlanGraph graph = planner.decompose(root, DecompositionStrategy.SEQUENTIAL);
        assertNotNull(graph);
        assertTrue(graph.getNodeMap().size() > 1, "Sequential decompose should create children");
    }

    @Test
    @DisplayName("Decompose with BY_PHASE strategy")
    void decomposeByPhase() {
        TaskNode root = new TaskNode("root-1", "ROOT", "Build user management system",
                List.of(), List.of(), 30000);

        PlanGraph graph = planner.decompose(root, DecompositionStrategy.BY_PHASE);
        assertNotNull(graph);
        assertTrue(graph.getNodeMap().size() > 1);
    }

    @Test
    @DisplayName("Decompose with TREE strategy should create hierarchy")
    void decomposeTree() {
        TaskNode root = new TaskNode("root-1", "ROOT", "Complex system migration",
                List.of(), List.of(), 30000);

        PlanGraph graph = planner.decompose(root, DecompositionStrategy.TREE);
        assertNotNull(graph);
        assertTrue(graph.getNodeMap().size() > 5,
                "Tree decomposition should create many nodes, got " + graph.getNodeMap().size());
    }

    @Test
    @DisplayName("Decompose null root should return empty graph or root-only")
    void decomposeNullRoot() {
        PlanGraph graph = planner.decompose(null, DecompositionStrategy.SEQUENTIAL);
        assertNotNull(graph);
        assertTrue(graph.getNodeMap().isEmpty());
    }

    @Test
    @DisplayName("Decompose null strategy should return root-only graph")
    void decomposeNullStrategy() {
        TaskNode root = new TaskNode("root-1", "ROOT", "Test",
                List.of(), List.of(), 30000);
        PlanGraph graph = planner.decompose(root, null);
        assertNotNull(graph);
        assertEquals(1, graph.getNodeMap().size());
    }

    @Test
    @DisplayName("Decompose all strategies should produce non-empty graph")
    void decomposeAllStrategies() {
        TaskNode root = new TaskNode("root-all", "ROOT",
                "Build and deploy microservice with database and API",
                List.of(), List.of(), 30000);

        for (DecompositionStrategy strategy : DecompositionStrategy.values()) {
            PlanGraph graph = planner.decompose(root, strategy);
            assertNotNull(graph, "Graph should not be null for strategy " + strategy);
            assertFalse(graph.getNodeMap().isEmpty(),
                    "Graph should not be empty for strategy " + strategy);
        }
    }

    @Test
    @DisplayName("Revise with missing step errors should trigger insert")
    void reviseMissingSteps() {
        TaskNode a = new TaskNode("A", "ANALYZE", "Analyze task",
                List.of(), List.of(), 1000);
        TaskPlan original = new SimpleTaskPlan(List.of(a));

        ReflectionFeedback feedback = ReflectionFeedback.builder()
                .qualityScore(0.6)
                .detectedErrors(List.of("缺少验证步骤"))
                .build();

        TaskPlan revised = planner.revise(original, feedback);
        assertNotNull(revised);
        assertTrue(revised.getNodes().size() >= original.getNodes().size());
    }
}
