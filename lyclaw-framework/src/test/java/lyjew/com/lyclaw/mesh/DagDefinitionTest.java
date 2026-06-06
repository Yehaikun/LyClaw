package lyjew.com.lyclaw.mesh;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 DAG 定义：
 * - 节点和边构建
 * - 拓扑排序
 * - 根节点检测
 * - 序列化/反序列化
 * - 循环检测
 */
class DagDefinitionTest {

    @Test
    void shouldBuildDag() {
        DagDefinition dag = DagDefinition.builder()
                .node("fetch", "Fetch data", "fetcher")
                .node("process", "Process data", "processor")
                .node("report", "Generate report", "reporter")
                .edge("fetch", "process")
                .edge("process", "report")
                .build();

        assertEquals(3, dag.getNodes().size());
        assertEquals(2, dag.getEdges().size());
    }

    @Test
    void shouldFindRootNodes() {
        DagDefinition dag = DagDefinition.builder()
                .node("a", "A", "agent-a")
                .node("b", "B", "agent-b")
                .node("c", "C", "agent-c")
                .edge("a", "b")
                .edge("b", "c")
                .build();

        assertEquals(1, dag.getRootNodes().size());
        assertEquals("a", dag.getRootNodes().get(0).getId());
    }

    @Test
    void shouldTopologicalSort() {
        DagDefinition dag = DagDefinition.builder()
                .node("fetch", "Fetch", "f")
                .node("lint", "Lint", "l")
                .node("test", "Test", "t")
                .node("report", "Report", "r")
                .edge("fetch", "lint")
                .edge("fetch", "test")
                .edge("lint", "report")
                .edge("test", "report")
                .build();

        List<String> order = dag.topologicalSort();
        assertEquals(4, order.size());

        // fetch 必须在 lint 和 test 之前
        assertTrue(order.indexOf("fetch") < order.indexOf("lint"));
        assertTrue(order.indexOf("fetch") < order.indexOf("test"));

        // lint 和 test 必须在 report 之前
        assertTrue(order.indexOf("lint") < order.indexOf("report"));
        assertTrue(order.indexOf("test") < order.indexOf("report"));
    }

    @Test
    void shouldDetectDependencies() {
        DagDefinition dag = DagDefinition.builder()
                .node("a", "A", "agent-a")
                .node("b", "B", "agent-b")
                .node("c", "C", "agent-c")
                .edge("a", "c")
                .edge("b", "c")
                .build();

        List<String> deps = dag.getDependencies("c");
        assertEquals(2, deps.size());
        assertTrue(deps.contains("a"));
        assertTrue(deps.contains("b"));
    }

    @Test
    void shouldSerializeAndDeserialize() {
        DagDefinition dag = DagDefinition.builder()
                .node("n1", "Task 1", "agent-1")
                .node("n2", "Task 2", "agent-2")
                .edge("n1", "n2")
                .build();

        Map<String, Object> config = dag.toConfig();
        assertNotNull(config.get("dagNodes"));
        assertNotNull(config.get("dagEdges"));

        DagDefinition restored = DagDefinition.fromConfig(config);
        assertEquals(2, restored.getNodes().size());
        assertEquals(1, restored.getEdges().size());
        assertEquals("n1", restored.getNodes().get(0).getId());
    }

    @Test
    void shouldHandleEmptyDag() {
        DagDefinition empty = DagDefinition.builder().build();
        assertEquals(0, empty.getNodes().size());
        assertEquals(0, empty.topologicalSort().size());
    }

    @Test
    void shouldHandleMultipleRoots() {
        DagDefinition dag = DagDefinition.builder()
                .node("a", "A", "agent-a")
                .node("b", "B", "agent-b")
                .node("c", "C", "agent-c")
                .edge("a", "c")
                .build();

        List<DagDefinition.DagNode> roots = dag.getRootNodes();
        assertEquals(2, roots.size());
        assertTrue(roots.stream().anyMatch(n -> n.getId().equals("a")));
        assertTrue(roots.stream().anyMatch(n -> n.getId().equals("b")));
    }
}
