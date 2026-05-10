package lyjew.com.lyclaw.plan;

import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.plan.impl.ReActPlanner;
import java.lang.reflect.Field;
import lyjew.com.lyclaw.task.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReActPlannerTest {

    private ReActPlanner planner;

    @BeforeEach
    void setUp() throws Exception {
        planner = new ReActPlanner();
        // Set maxCycles via reflection (not injected without Spring context)
        java.lang.reflect.Field f = ReActPlanner.class.getDeclaredField("maxCycles");
        f.setAccessible(true);
        f.set(planner, 5);
    }

    @Test
    @DisplayName("Plan should produce Thought→Action→Observation cycles")
    void planBasic() {
        TaskPlan plan = planner.plan(null, "debug and fix the authentication error");
        assertNotNull(plan);
        List<TaskNode> nodes = plan.getNodes();
        assertTrue(nodes.size() >= 3, "ReAct plan should have at least 3 nodes per cycle");

        // Check structure: THOUGHT, ACTION, OBSERVATION pattern
        List<String> types = nodes.stream().map(TaskNode::getType).toList();
        assertTrue(types.contains("THOUGHT"));
        assertTrue(types.contains("ACTION"));
        assertTrue(types.contains("OBSERVATION"));
    }

    @Test
    @DisplayName("Plan short intent should have minimum 2 cycles")
    void planShortIntent() {
        TaskPlan plan = planner.plan(null, "test");
        List<TaskNode> nodes = plan.getNodes();
        // Minimum 2 cycles × 3 nodes = 6 nodes
        assertEquals(6, nodes.size());
    }

    @Test
    @DisplayName("Plan long intent should have more cycles")
    void planLongIntent() {
        String longIntent = "A".repeat(200);
        TaskPlan plan = planner.plan(null, longIntent);
        List<TaskNode> nodes = plan.getNodes();
        // Longer intent → more cycles
        assertTrue(nodes.size() >= 6);
    }

    @Test
    @DisplayName("Plan should have correct cross-cycle dependencies")
    void planCrossCycleDep() {
        TaskPlan plan = planner.plan(null, "investigate and resolve the memory leak issue");
        List<TaskNode> nodes = plan.getNodes();
        assertFalse(nodes.isEmpty());

        // Each THOUGHT in cycle 1+ should depend on previous cycle's OBSERVATION
        for (int i = 3; i < nodes.size(); i += 3) { // step 3, 6, 9... are THOUGHT of cycles 1+
            TaskNode thought = nodes.get(i);
            if (i < nodes.size()) {
                List<String> deps = thought.getDependencies();
                assertFalse(deps.isEmpty(), "Thought at index " + i + " should have dependencies");
            }
        }
    }

    @Test
    @DisplayName("Plan last cycle should have FINAL markers in descriptions")
    void planFinalCycle() {
        TaskPlan plan = planner.plan(null, "search for information about Spring Boot");
        List<TaskNode> nodes = plan.getNodes();
        // Last 3 should have FINAL in description
        for (int i = nodes.size() - 3; i < nodes.size(); i++) {
            String desc = nodes.get(i).getDescription();
            assertTrue(desc.toLowerCase().contains("final"),
                    "Last cycle nodes should have FINAL marker, got: " + desc);
        }
    }

    @Test
    @DisplayName("Revise with low quality should add extra cycle")
    void reviseLowQuality() {
        TaskNode t = new TaskNode("react-t0", "THOUGHT", "Think", List.of(), List.of(), 1000);
        TaskNode a = new TaskNode("react-a0", "ACTION", "Act", List.of(), List.of("react-t0"), 1000);
        TaskNode o = new TaskNode("react-o0", "OBSERVATION", "Observe", List.of(), List.of("react-a0"), 1000);
        TaskPlan original = new SimpleTaskPlan(List.of(t, a, o));

        ReflectionFeedback feedback = ReflectionFeedback.builder()
                .qualityScore(0.2).build();

        TaskPlan revised = planner.revise(original, feedback);
        assertNotNull(revised);
        assertTrue(revised.getNodes().size() > original.getNodes().size(),
                "Low quality should add extra cycle, got " + revised.getNodes().size()
                        + " vs " + original.getNodes().size());
    }

    @Test
    @DisplayName("Revise with premature stop should add more cycles")
    void revisePremature() {
        TaskNode t = new TaskNode("r1", "THOUGHT", "Think", List.of(), List.of(), 1000);
        TaskNode a = new TaskNode("r2", "ACTION", "Act", List.of(), List.of("r1"), 1000);
        TaskNode o = new TaskNode("r3", "OBSERVATION", "Observe", List.of(), List.of("r2"), 1000);
        TaskPlan original = new SimpleTaskPlan(List.of(t, a, o));

        ReflectionFeedback feedback = ReflectionFeedback.builder()
                .qualityScore(0.5)
                .detectedErrors(List.of("premature stop", "incomplete analysis"))
                .build();

        TaskPlan revised = planner.revise(original, feedback);
        assertNotNull(revised);
        assertTrue(revised.getNodes().size() > original.getNodes().size());
    }

    @Test
    @DisplayName("Optimize should create single ReAct cycle")
    void optimize() {
        AgentResult result = new AgentResult("test-agent", "completed", "ReAct loop completed successfully", null, 1000L);

        TaskPlan plan = planner.optimize(result);
        assertNotNull(plan);
        List<TaskNode> nodes = plan.getNodes();
        assertEquals(3, nodes.size());
        assertEquals("THOUGHT", nodes.get(0).getType());
        assertEquals("ACTION", nodes.get(1).getType());
        assertEquals("OBSERVATION", nodes.get(2).getType());
    }

    @Test
    @DisplayName("Decompose should create ReAct cycles subgraph")
    void decompose() {
        TaskNode root = new TaskNode("root-react", "ROOT",
                "Investigate and fix the database connection timeout",
                List.of(), List.of(), 30000);

        PlanGraph graph = planner.decompose(root, DecompositionStrategy.SEQUENTIAL);
        assertNotNull(graph);
        assertTrue(graph.getNodeMap().size() > 1);
    }

    @Test
    @DisplayName("Action nodes should have tools in non-final cycles")
    void actionTools() {
        TaskPlan plan = planner.plan(null, "search and compare different cloud providers");
        List<TaskNode> nodes = plan.getNodes();
        // Non-final ACTION nodes should have tool requirements
        for (int i = 0; i < nodes.size() - 3; i++) {
            TaskNode node = nodes.get(i);
            if ("ACTION".equals(node.getType())) {
                assertFalse(node.getRequiredTools().isEmpty(),
                        "Non-final ACTION node should require tools");
            }
        }
    }

    @Test
    @DisplayName("Final cycle ACTION should have no tools")
    void finalActionNoTools() {
        TaskPlan plan = planner.plan(null, "answer a simple question");
        List<TaskNode> nodes = plan.getNodes();
        // Last ACTION node should have empty tools
        for (int i = nodes.size() - 1; i >= 0; i--) {
            if ("ACTION".equals(nodes.get(i).getType())) {
                assertTrue(nodes.get(i).getRequiredTools().isEmpty(),
                        "Final ACTION node should have no tool requirements");
                break;
            }
        }
    }
}
