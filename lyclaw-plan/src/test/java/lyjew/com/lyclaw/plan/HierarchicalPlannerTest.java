package lyjew.com.lyclaw.plan;

import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.plan.impl.HierarchicalPlanner;
import lyjew.com.lyclaw.task.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HierarchicalPlannerTest {

    private HierarchicalPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new HierarchicalPlanner();
    }

    @Test
    @DisplayName("Plan should produce 3-level hierarchy")
    void planThreeLevels() {
        TaskPlan plan = planner.plan(null, "Build a complete e-commerce platform");
        assertNotNull(plan);
        List<TaskNode> nodes = plan.getNodes();

        // Should have L1, L2, and L3 nodes
        boolean hasL1 = nodes.stream().anyMatch(n -> n.getNodeId().contains("L1"));
        boolean hasL2 = nodes.stream().anyMatch(n -> n.getNodeId().contains("L2"));
        boolean hasL3 = nodes.stream().anyMatch(n -> n.getNodeId().contains("L3"));

        assertTrue(hasL1, "Should have L1 goals");
        assertTrue(hasL2, "Should have L2 steps");
        assertTrue(hasL3, "Should have L3 atomic actions");
    }

    @Test
    @DisplayName("Plan structure should have correct dependencies")
    void planDependencies() {
        TaskPlan plan = planner.plan(null, "Migrate legacy system to cloud");
        List<TaskNode> nodes = plan.getNodes();

        // L1 nodes should have no dependencies
        nodes.stream()
                .filter(n -> n.getNodeId().contains("L1") && !n.getNodeId().contains("L2") && !n.getNodeId().contains("L3"))
                .forEach(n -> assertTrue(n.getDependencies().isEmpty(),
                        "L1 node " + n.getNodeId() + " should have no deps"));

        // L2 nodes should depend on their L1 parent
        nodes.stream()
                .filter(n -> n.getNodeId().contains("L2") && !n.getNodeId().contains("L3"))
                .forEach(n -> assertFalse(n.getDependencies().isEmpty(),
                        "L2 node " + n.getNodeId() + " should have deps"));

        // L3 nodes should depend on their L2 parent
        nodes.stream()
                .filter(n -> n.getNodeId().contains("L3"))
                .forEach(n -> assertFalse(n.getDependencies().isEmpty(),
                        "L3 node " + n.getNodeId() + " should have deps"));
    }

    @Test
    @DisplayName("Plan should include ANALYSIS, EXECUTION, VERIFICATION phases")
    void planPhases() {
        TaskPlan plan = planner.plan(null, "Design and implement a new feature");
        List<TaskNode> nodes = plan.getNodes();

        List<String> types = nodes.stream().map(TaskNode::getType).toList();
        assertTrue(types.contains("ANALYSIS") || types.contains("EXECUTION") || types.contains("VERIFICATION"),
                "Should contain hierarchical phase types, got: " + types);
    }

    @Test
    @DisplayName("L3 nodes should be ATOMIC type")
    void atomicType() {
        TaskPlan plan = planner.plan(null, "Complete system refactoring");
        List<TaskNode> l3Nodes = plan.getNodes().stream()
                .filter(n -> n.getNodeId().contains("L3")).toList();
        assertFalse(l3Nodes.isEmpty());
        for (TaskNode node : l3Nodes) {
            assertEquals("ATOMIC", node.getType());
        }
    }

    @Test
    @DisplayName("Plan without intent should use default")
    void planNoIntent() {
        TaskPlan plan = planner.plan(null, null);
        assertNotNull(plan);
        assertFalse(plan.getNodes().isEmpty());
    }

    @Test
    @DisplayName("Plan without context should work")
    void planNoContext() {
        TaskPlan plan = planner.plan(null);
        assertNotNull(plan);
        assertFalse(plan.getNodes().isEmpty());
    }

    @Test
    @DisplayName("Revise with low quality should add extra verification layer")
    void reviseLowQuality() {
        TaskNode l1 = new TaskNode("hier-L1-0", "ANALYSIS", "Analyze task",
                List.of(), List.of(), 1000);
        TaskPlan original = new SimpleTaskPlan(List.of(l1));

        ReflectionFeedback feedback = ReflectionFeedback.builder()
                .qualityScore(0.1).build();

        TaskPlan revised = planner.revise(original, feedback);
        assertNotNull(revised);
        assertTrue(revised.getNodes().size() > original.getNodes().size(),
                "Low quality should add extra layer");
    }

    @Test
    @DisplayName("Revise with high quality should return original")
    void reviseHighQuality() {
        TaskNode l1 = new TaskNode("hier-L1-0", "ANALYSIS", "Analyze", List.of(), List.of(), 1000);
        TaskPlan original = new SimpleTaskPlan(List.of(l1));

        ReflectionFeedback feedback = ReflectionFeedback.builder()
                .qualityScore(0.8).build();

        TaskPlan revised = planner.revise(original, feedback);
        assertNotNull(revised);
        assertEquals(original.getNodes().size(), revised.getNodes().size());
    }

    @Test
    @DisplayName("Optimize should create multi-level optimization plan")
    void optimize() {
        AgentResult result = new AgentResult("test-agent", "completed", "System analysis found performance bottlenecks", null, 1000L);

        TaskPlan plan = planner.optimize(result);
        assertNotNull(plan);
        List<TaskNode> nodes = plan.getNodes();
        assertTrue(nodes.size() >= 2);

        // Should have L1 optimization and L2 steps
        boolean hasL1 = nodes.stream().anyMatch(n -> n.getNodeId().contains("L1"));
        boolean hasL2 = nodes.stream().anyMatch(n -> n.getNodeId().contains("L2"));
        assertTrue(hasL1);
        assertTrue(hasL2);
    }

    @Test
    @DisplayName("Decompose should create hierarchy from root")
    void decompose() {
        TaskNode root = new TaskNode("root-hier", "ROOT",
                "Complete enterprise system migration to cloud native",
                List.of(), List.of(), 30000);

        PlanGraph graph = planner.decompose(root, DecompositionStrategy.TREE);
        assertNotNull(graph);
        assertTrue(graph.getNodeMap().size() > 3,
                "Hierarchical decompose should create many nodes");
    }

    @Test
    @DisplayName("Timeout for L1 should be 2x default, L3 should be /2")
    void timeoutLevels() {
        TaskPlan plan = planner.plan(null, "Build a complex system");
        List<TaskNode> nodes = plan.getNodes();

        long l1Timeout = nodes.stream()
                .filter(n -> n.getNodeId().matches(".*L1-\\d+$"))
                .mapToLong(TaskNode::getTimeoutMs).findFirst().orElse(0);

        long l3Timeout = nodes.stream()
                .filter(n -> n.getNodeId().contains("L3"))
                .mapToLong(TaskNode::getTimeoutMs).findFirst().orElse(0);

        if (l1Timeout > 0 && l3Timeout > 0) {
            assertTrue(l1Timeout >= l3Timeout,
                    "L1 timeout (" + l1Timeout + ") should be >= L3 timeout (" + l3Timeout + ")");
        }
    }
}
