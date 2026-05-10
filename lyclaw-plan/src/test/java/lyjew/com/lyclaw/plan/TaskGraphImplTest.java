package lyjew.com.lyclaw.plan;

import lyjew.com.lyclaw.plan.impl.TaskGraphImpl;
import lyjew.com.lyclaw.task.TaskNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TaskGraphImplTest {

    private TaskGraphImpl graph;

    @BeforeEach
    void setUp() {
        graph = new TaskGraphImpl();
    }

    @Test
    @DisplayName("Empty graph should return empty topological order")
    void topologicalOrderEmpty() {
        List<String> order = graph.getTopologicalOrder();
        assertTrue(order.isEmpty());
    }

    @Test
    @DisplayName("Topological order should order nodes by dependency")
    void topologicalOrderLinear() {
        TaskNode a = new TaskNode("A", "TASK", "First", List.of(), List.of(), 1000);
        TaskNode b = new TaskNode("B", "TASK", "Second", List.of(), List.of("A"), 1000);
        TaskNode c = new TaskNode("C", "TASK", "Third", List.of(), List.of("B"), 1000);

        graph.addNode(a); graph.addNode(b); graph.addNode(c);

        List<String> order = graph.getTopologicalOrder();
        assertEquals(3, order.size());
        assertEquals("A", order.get(0));
        assertEquals("B", order.get(1));
        assertEquals("C", order.get(2));
    }

    @Test
    @DisplayName("Topological order for diamond DAG")
    void topologicalOrderDiamond() {
        TaskNode a = new TaskNode("A", "ROOT", "Root", List.of(), List.of(), 1000);
        TaskNode b = new TaskNode("B", "LEFT", "Left", List.of(), List.of("A"), 1000);
        TaskNode c = new TaskNode("C", "RIGHT", "Right", List.of(), List.of("A"), 1000);
        TaskNode d = new TaskNode("D", "MERGE", "Merge", List.of(), List.of("B", "C"), 1000);

        graph.addNode(a); graph.addNode(b); graph.addNode(c); graph.addNode(d);

        List<String> order = graph.getTopologicalOrder();
        assertEquals(4, order.size());
        assertEquals("A", order.get(0));
        // B and C can come in any order, but both before D
        assertTrue(order.indexOf("B") > order.indexOf("A"));
        assertTrue(order.indexOf("C") > order.indexOf("A"));
        assertTrue(order.indexOf("D") > order.indexOf("B"));
        assertTrue(order.indexOf("D") > order.indexOf("C"));
    }

    @Test
    @DisplayName("Critical path for empty graph should return empty")
    void criticalPathEmpty() {
        List<TaskNode> path = graph.getCriticalPath();
        assertTrue(path.isEmpty());
    }

    @Test
    @DisplayName("Critical path for linear chain should be full chain")
    void criticalPathLinear() {
        TaskNode a = new TaskNode("A", "TASK", "A", List.of(), List.of(), 1000);
        TaskNode b = new TaskNode("B", "TASK", "B", List.of(), List.of("A"), 1000);
        TaskNode c = new TaskNode("C", "TASK", "C", List.of(), List.of("B"), 1000);

        graph.addNode(a); graph.addNode(b); graph.addNode(c);

        List<TaskNode> path = graph.getCriticalPath();
        assertEquals(3, path.size());
        assertEquals("A", path.get(0).getNodeId());
        assertEquals("B", path.get(1).getNodeId());
        assertEquals("C", path.get(2).getNodeId());
    }

    @Test
    @DisplayName("Critical path for diamond should take longest branch")
    void criticalPathDiamond() {
        TaskNode a = new TaskNode("A", "ROOT", "Root", List.of(), List.of(), 1000);
        TaskNode b = new TaskNode("B", "SHORT", "Short", List.of(), List.of("A"), 500);
        TaskNode c = new TaskNode("C", "LONG", "Long", List.of(), List.of("A"), 2000);
        TaskNode d = new TaskNode("D", "MERGE", "Merge", List.of(), List.of("B", "C"), 1000);

        // Add with custom weights
        graph.addNode(a, 100);
        graph.addNode(b, 500);
        graph.addNode(c, 2000);
        graph.addNode(d, 100);

        List<TaskNode> path = graph.getCriticalPath();
        assertFalse(path.isEmpty());
        // The longest path should go through C (2000 > 500)
        boolean hasC = path.stream().anyMatch(n -> "C".equals(n.getNodeId()));
        assertTrue(hasC, "Critical path should include node C (the longest branch)");
        // Should not include B
        boolean hasB = path.stream().anyMatch(n -> "B".equals(n.getNodeId()));
        assertFalse(hasB, "Critical path should NOT include node B (the short branch)");
    }

    @Test
    @DisplayName("Max parallelism for linear chain should be 1")
    void maxParallelismLinear() {
        TaskNode a = new TaskNode("A", "TASK", "A", List.of(), List.of(), 1000);
        TaskNode b = new TaskNode("B", "TASK", "B", List.of(), List.of("A"), 1000);

        graph.addNode(a); graph.addNode(b);

        assertEquals(1, graph.getMaxParallelism());
    }

    @Test
    @DisplayName("Max parallelism for parallel branches should be count of parallel nodes")
    void maxParallelismParallel() {
        TaskNode root = new TaskNode("root", "ROOT", "Root", List.of(), List.of(), 1000);
        TaskNode a = new TaskNode("A", "TASK", "A", List.of(), List.of("root"), 1000);
        TaskNode b = new TaskNode("B", "TASK", "B", List.of(), List.of("root"), 1000);
        TaskNode c = new TaskNode("C", "TASK", "C", List.of(), List.of("root"), 1000);

        graph.addNode(root); graph.addNode(a); graph.addNode(b); graph.addNode(c);

        assertEquals(3, graph.getMaxParallelism());
    }

    @Test
    @DisplayName("Empty graph has max parallelism 0")
    void maxParallelismEmpty() {
        assertEquals(0, graph.getMaxParallelism());
    }

    @Test
    @DisplayName("getAncestors should return all upstream dependencies")
    void getAncestors() {
        TaskNode a = new TaskNode("A", "TASK", "A", List.of(), List.of(), 1000);
        TaskNode b = new TaskNode("B", "TASK", "B", List.of(), List.of("A"), 1000);
        TaskNode c = new TaskNode("C", "TASK", "C", List.of(), List.of("B"), 1000);

        graph.addNode(a); graph.addNode(b); graph.addNode(c);

        Set<String> ancestorsC = graph.getAncestors("C");
        assertTrue(ancestorsC.contains("A"));
        assertTrue(ancestorsC.contains("B"));
        assertEquals(2, ancestorsC.size());

        Set<String> ancestorsA = graph.getAncestors("A");
        assertTrue(ancestorsA.isEmpty());
    }

    @Test
    @DisplayName("getDescendants should return all downstream dependents")
    void getDescendants() {
        TaskNode a = new TaskNode("A", "TASK", "A", List.of(), List.of(), 1000);
        TaskNode b = new TaskNode("B", "TASK", "B", List.of(), List.of("A"), 1000);
        TaskNode c = new TaskNode("C", "TASK", "C", List.of(), List.of("B"), 1000);

        graph.addNode(a); graph.addNode(b); graph.addNode(c);

        Set<String> descendantsA = graph.getDescendants("A");
        assertTrue(descendantsA.contains("B"));
        assertTrue(descendantsA.contains("C"));
        assertEquals(2, descendantsA.size());

        Set<String> descendantsC = graph.getDescendants("C");
        assertTrue(descendantsC.isEmpty());
    }

    @Test
    @DisplayName("getProgressDetail should return correct state counts")
    void getProgressDetail() {
        TaskNode a = new TaskNode("A", "TASK", "A", List.of(), List.of(), 1000);
        TaskNode b = new TaskNode("B", "TASK", "B", List.of(), List.of("A"), 1000);

        graph.addNode(a); graph.addNode(b);

        Map<String, Object> detail = graph.getProgressDetail();
        assertEquals(0.0, (double) detail.get("progress"), 1e-6);
        assertEquals(2, (int) detail.get("total"));
        assertNotNull(detail.get("pending"));
    }

    @Test
    @DisplayName("extractSubgraph should create subgraph from root")
    void extractSubgraph() {
        TaskNode a = new TaskNode("A", "ROOT", "Root", List.of(), List.of(), 1000);
        TaskNode b = new TaskNode("B", "LEAF", "Leaf", List.of(), List.of("A"), 1000);
        TaskNode c = new TaskNode("C", "OTHER", "Other", List.of(), List.of(), 1000);

        graph.addNode(a); graph.addNode(b); graph.addNode(c);

        TaskGraphImpl subgraph = graph.extractSubgraph("A");
        assertEquals(2, subgraph.getNodeMap().size());
        assertTrue(subgraph.getNodeMap().containsKey("A"));
        assertTrue(subgraph.getNodeMap().containsKey("B"));
        assertFalse(subgraph.getNodeMap().containsKey("C"));
    }

    @Test
    @DisplayName("extractSubgraph for unknown root should return empty")
    void extractSubgraphUnknown() {
        TaskGraphImpl subgraph = graph.extractSubgraph("nonexistent");
        assertTrue(subgraph.getNodeMap().isEmpty());
    }

    @Test
    @DisplayName("getProgress on fully completed should return 1.0")
    void progressCompleted() {
        TaskNode a = new TaskNode("A", "TASK", "A", List.of(), List.of(), 1000);
        graph.addNode(a);
        assertEquals(0.0, graph.getProgress(), 1e-6);
        graph.markCompleted("A");
        assertEquals(1.0, graph.getProgress(), 1e-6); // single node is COMPLETED
    }

    @Test
    @DisplayName("markFailed should cascade skip")
    void markFailedCascade() {
        TaskNode a = new TaskNode("A", "ROOT", "A", List.of(), List.of(), 1000);
        TaskNode b = new TaskNode("B", "CHILD", "B", List.of(), List.of("A"), 1000);
        graph.addEdge("A", "B"); graph.addEdge("B", "C");
        TaskNode c = new TaskNode("C", "GRANDCHILD", "C", List.of(), List.of("B"), 1000);

        graph.addNode(a); graph.addNode(b); graph.addNode(c);
        graph.markFailed("A");

        assertFalse(graph.isFullyCompleted()); // A is FAILED, not COMPLETED or SKIPPED
        assertEquals(2.0 / 3.0, graph.getProgress(), 1e-6); // 2 SKIPPED out of 3 total
    }

    @Test
    @DisplayName("getDependencySummary should return formatted string")
    void dependencySummary() {
        TaskNode a = new TaskNode("A", "ROOT", "Root task", List.of(), List.of(), 1000);
        graph.addNode(a);

        String summary = graph.getDependencySummary();
        assertTrue(summary.contains("A"));
        assertTrue(summary.contains("ROOT"));
        assertTrue(summary.contains("1 nodes"));
    }

    @Test
    @DisplayName("Cached topological order should be invalidated on mutation")
    void cacheInvalidation() {
        TaskNode a = new TaskNode("A", "TASK", "A", List.of(), List.of(), 1000);
        graph.addNode(a);

        List<String> order1 = graph.getTopologicalOrder();
        assertEquals(1, order1.size());

        // Add another node → cache should be invalidated
        TaskNode b = new TaskNode("B", "TASK", "B", List.of(), List.of("A"), 1000);
        graph.addNode(b);

        List<String> order2 = graph.getTopologicalOrder();
        assertEquals(2, order2.size());
    }
}
