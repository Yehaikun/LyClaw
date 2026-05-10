package lyjew.com.lyclaw.plan;

import lyjew.com.lyclaw.plan.controller.PlanController;
import lyjew.com.lyclaw.task.*;
import lyjew.com.lyclaw.plan.impl.TaskGraphImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlanControllerTest {

    private TaskPlanner defaultPlanner;
    private TaskPlanner cotPlanner;
    private TaskPlanner reActPlanner;
    private TaskPlanner hierarchicalPlanner;
    private PlanValidator planValidator;
    private PlanController controller;

    @BeforeEach
    void setUp() {
        defaultPlanner = mock(TaskPlanner.class);
        cotPlanner = mock(TaskPlanner.class);
        reActPlanner = mock(TaskPlanner.class);
        hierarchicalPlanner = mock(TaskPlanner.class);
        planValidator = mock(PlanValidator.class);

        PlanValidator.ValidationResult validResult = PlanValidator.ValidationResult.valid();
        when(planValidator.validate(any())).thenReturn(validResult);

        controller = new PlanController(defaultPlanner, cotPlanner, reActPlanner,
                hierarchicalPlanner, planValidator, null, null);
    }

    @Test
    @DisplayName("plan endpoint should return plan with validation")
    void planEndpoint() {
        TaskNode node = new TaskNode("task-1", "EXECUTE", "Test task",
                List.of(), List.of(), 30000);
        TaskPlan plan = new SimpleTaskPlan(List.of(node));
        when(defaultPlanner.plan(any(), any())).thenReturn(plan);

        PlanRequest request = PlanRequest.builder()
                .sessionId("s1").userIntent("test intent").strategy("dag").build();

        ResponseEntity<Map<String, Object>> response = controller.plan(request);
        assertNotNull(response);
        assertTrue(response.getStatusCode().is2xxSuccessful());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(1, (int) body.get("nodeCount"));
        assertEquals(true, body.get("valid"));
    }

    @Test
    @DisplayName("plan endpoint with cot strategy should use CoT planner")
    void planCoTStrategy() {
        TaskNode node = new TaskNode("cot-1", "THINK", "Think step",
                List.of(), List.of(), 30000);
        TaskPlan plan = new SimpleTaskPlan(List.of(node));
        when(cotPlanner.plan(any(), any())).thenReturn(plan);

        PlanRequest request = PlanRequest.builder()
                .sessionId("s1").userIntent("analyze").strategy("cot").build();

        ResponseEntity<Map<String, Object>> response = controller.plan(request);
        assertNotNull(response);
        Map<String, Object> body = response.getBody();
        assertEquals("cot", body.get("strategy"));
    }

    @Test
    @DisplayName("plan endpoint with react strategy should use ReAct planner")
    void planReActStrategy() {
        TaskNode node = new TaskNode("react-1", "THOUGHT", "Think",
                List.of(), List.of(), 30000);
        TaskPlan plan = new SimpleTaskPlan(List.of(node));
        when(reActPlanner.plan(any(), any())).thenReturn(plan);

        PlanRequest request = PlanRequest.builder()
                .sessionId("s1").userIntent("investigate").strategy("react").build();

        ResponseEntity<Map<String, Object>> response = controller.plan(request);
        assertNotNull(response);
        assertEquals("react", response.getBody().get("strategy"));
    }

    @Test
    @DisplayName("plan endpoint with hierarchical strategy")
    void planHierarchicalStrategy() {
        TaskNode node = new TaskNode("hier-1", "ANALYSIS", "Analyze",
                List.of(), List.of(), 30000);
        TaskPlan plan = new SimpleTaskPlan(List.of(node));
        when(hierarchicalPlanner.plan(any(), any())).thenReturn(plan);

        PlanRequest request = PlanRequest.builder()
                .sessionId("s1").userIntent("build system").strategy("hierarchical").build();

        ResponseEntity<Map<String, Object>> response = controller.plan(request);
        assertNotNull(response);
        assertEquals("hierarchical", response.getBody().get("strategy"));
    }

    @Test
    @DisplayName("revise endpoint should return revised plan")
    void reviseEndpoint() {
        TaskNode node = new TaskNode("rev-1", "TASK", "Revised", List.of(), List.of(), 30000);
        TaskPlan revisedPlan = new SimpleTaskPlan(List.of(node));
        when(defaultPlanner.revise(any(), any())).thenReturn(revisedPlan);

        ReviseRequest request = ReviseRequest.builder()
                .feedback("replan").reason("Need better approach")
                .currentPlan(new SimpleTaskPlan(List.of())).build();

        ResponseEntity<TaskPlan> response = controller.revise(request);
        assertNotNull(response);
        assertTrue(response.getStatusCode().is2xxSuccessful());
    }

    @Test
    @DisplayName("decompose endpoint should return graph info")
    void decomposeEndpoint() {
        PlanGraph graph = mock(PlanGraph.class);
        when(graph.getNodeMap()).thenReturn(Map.of());
        when(defaultPlanner.decompose(any(), any())).thenReturn(graph);

        Map<String, Object> request = Map.of(
                "taskDescription", "Decompose task", "strategy", "BY_PHASE");

        ResponseEntity<Map<String, Object>> response = controller.decompose(request);
        assertNotNull(response);
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(0, (int) body.get("nodeCount"));
    }

    @Test
    @DisplayName("validate endpoint should return validation result")
    void validateEndpoint() {
        TaskNode node = new TaskNode("v-1", "TASK", "Test", List.of(), List.of(), 1000);
        TaskPlan plan = new SimpleTaskPlan(List.of(node));

        ResponseEntity<PlanValidator.ValidationResult> response = controller.validate(plan);
        assertNotNull(response);
        assertTrue(response.getBody().isValid());
    }

    @Test
    @DisplayName("strategies endpoint should return all strategies")
    void strategiesEndpoint() {
        ResponseEntity<List<Map<String, String>>> response = controller.listStrategies();
        assertNotNull(response);
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertEquals(6, response.getBody().size());
    }

    @Test
    @DisplayName("progress endpoint should return progress info")
    void progressEndpoint() {
        ResponseEntity<Map<String, Object>> response = controller.progress("plan-123");
        assertNotNull(response);
        Map<String, Object> body = response.getBody();
        assertEquals("plan-123", body.get("planId"));
        assertEquals("RUNNING", body.get("status"));
    }

    @Test
    @DisplayName("graph endpoint should build custom graph")
    void graphEndpoint() {
        Map<String, Object> nodeDef = Map.of(
                "nodeId", "A", "type", "TASK",
                "description", "Task", "tools", List.of(), "timeoutMs", 10000);
        Map<String, String> edgeDef = Map.of("from", "A", "to", "B");
        Map<String, Object> nodeDef2 = Map.of(
                "nodeId", "B", "type", "TASK",
                "description", "Task B", "tools", List.of(), "timeoutMs", 10000);

        Map<String, Object> request = Map.of(
                "nodes", List.of(nodeDef, nodeDef2), "edges", List.of(edgeDef));

        ResponseEntity<TaskGraphImpl> response = controller.buildGraph(request);
        assertNotNull(response);
        assertTrue(response.getStatusCode().is2xxSuccessful());
    }

    @Test
    @DisplayName("plan endpoint with null strategy should use default")
    void planNullStrategy() {
        TaskNode node = new TaskNode("task-1", "EXECUTE", "Test", List.of(), List.of(), 30000);
        TaskPlan plan = new SimpleTaskPlan(List.of(node));
        when(defaultPlanner.plan(any(), any())).thenReturn(plan);

        PlanRequest request = PlanRequest.builder()
                .sessionId("s1").userIntent("test").build();

        ResponseEntity<Map<String, Object>> response = controller.plan(request);
        assertNotNull(response);
        assertEquals("dag", response.getBody().get("strategy")); // default
    }
}
