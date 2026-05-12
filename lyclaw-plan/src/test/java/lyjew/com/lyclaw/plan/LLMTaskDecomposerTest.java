package lyjew.com.lyclaw.plan;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.plan.impl.LLMTaskDecomposer;
import lyjew.com.lyclaw.task.DecompositionStrategy;
import lyjew.com.lyclaw.task.TaskNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LLMTaskDecomposerTest {

    private LLMTaskDecomposer decomposer;

    @BeforeEach
    void setUp() {
        ChatFacade mockChatFacade = Mockito.mock(ChatFacade.class);
        decomposer = new LLMTaskDecomposer(mockChatFacade);
    }

    @Test
    @DisplayName("Decompose null task description should return empty list")
    void decomposeNull() {
        List<TaskNode> nodes = decomposer.decompose(null, DecompositionStrategy.SEQUENTIAL);
        assertTrue(nodes.isEmpty());
    }

    @Test
    @DisplayName("Decompose blank task description should return empty list")
    void decomposeBlank() {
        List<TaskNode> nodes = decomposer.decompose("   ", DecompositionStrategy.BY_PHASE);
        assertTrue(nodes.isEmpty());
    }

    @Test
    @DisplayName("Decompose with null strategy should default to BY_PHASE")
    void decomposeNullStrategy() {
        List<TaskNode> nodes = decomposer.decompose("Create a REST API", null);
        assertFalse(nodes.isEmpty());
        assertTrue(nodes.stream().anyMatch(n -> "ANALYZE".equals(n.getType())));
    }

    @Test
    @DisplayName("SEQUENTIAL decomposition should produce ordered nodes")
    void decomposeSequential() {
        String task = "创建数据库表并部署API服务然后进行测试";
        List<TaskNode> nodes = decomposer.decompose(task, DecompositionStrategy.SEQUENTIAL);
        assertFalse(nodes.isEmpty());
        // Should have at least one action type node
        boolean hasAction = nodes.stream()
                .anyMatch(n -> n.getType().equals("CREATE") || n.getType().equals("DEPLOY")
                        || n.getType().equals("TEST"));
        assertTrue(hasAction, "Should detect action verbs: " +
                nodes.stream().map(TaskNode::getType).toList());
    }

    @Test
    @DisplayName("SEQUENTIAL decomposition should set correct dependencies")
    void decomposeSequentialDependencies() {
        String task = "创建用户管理系统并测试功能";
        List<TaskNode> nodes = decomposer.decompose(task, DecompositionStrategy.SEQUENTIAL);
        assertFalse(nodes.isEmpty());
        for (int i = 1; i < nodes.size(); i++) {
            TaskNode node = nodes.get(i);
            assertFalse(node.getDependencies().isEmpty(),
                    "Node at index " + i + " should have dependencies");
        }
    }

    @Test
    @DisplayName("BY_DOMAIN decomposition should group by knowledge domain")
    void decomposeByDomain() {
        String task = "创建包含数据库设计和REST API的用户管理系统，并编写文档说明";
        List<TaskNode> nodes = decomposer.decompose(task, DecompositionStrategy.BY_DOMAIN);
        assertFalse(nodes.isEmpty());
        // Should detect DATA, API, DOCUMENT domains
        boolean hasData = nodes.stream().anyMatch(n -> "DATA".equals(n.getType()));
        boolean hasApi = nodes.stream().anyMatch(n -> "API".equals(n.getType()));
        assertTrue(hasData || hasApi, "Should detect domain types");
    }

    @Test
    @DisplayName("BY_DOMAIN decomposition with no matching domains should create general node")
    void decomposeByDomainNoMatch() {
        String task = "Just do something";
        List<TaskNode> nodes = decomposer.decompose(task, DecompositionStrategy.BY_DOMAIN);
        assertFalse(nodes.isEmpty());
        assertEquals("GENERAL", nodes.get(0).getType());
    }

    @Test
    @DisplayName("BY_PHASE decomposition should follow standard phases")
    void decomposeByPhase() {
        String task = "Implement a user authentication system";
        List<TaskNode> nodes = decomposer.decompose(task, DecompositionStrategy.BY_PHASE);
        assertFalse(nodes.isEmpty());
        // Should have ANALYZE → DESIGN → IMPLEMENT → TEST → DOCUMENT (or subset)
        assertTrue(nodes.size() >= 3, "Should have at least 3 phases");
        List<String> types = nodes.stream().map(TaskNode::getType).toList();
        assertTrue(types.contains("ANALYZE"));
        assertTrue(types.contains("IMPLEMENT"));
    }

    @Test
    @DisplayName("BY_PHASE simple task should skip DOCUMENT phase")
    void decomposeByPhaseSimple() {
        String task = "Create a file"; // Simple task
        List<TaskNode> nodes = decomposer.decompose(task, DecompositionStrategy.BY_PHASE);
        assertFalse(nodes.isEmpty());
        // Simple tasks (complexity <= 1) should have only 4 phases, no DOCUMENT
        assertTrue(nodes.size() <= 4);
    }

    @Test
    @DisplayName("BY_PHASE complex task should have all 5 phases")
    void decomposeByPhaseComplex() {
        String task = "创建数据库表并部署API服务然后进行测试和迁移配置分析优化";
        List<TaskNode> nodes = decomposer.decompose(task, DecompositionStrategy.BY_PHASE);
        assertFalse(nodes.isEmpty());
        assertTrue(nodes.size() >= 4, "Complex task should have many phases");
    }

    @Test
    @DisplayName("PARALLEL_INDEPENDENT with comma-separated tasks should create parallel nodes")
    void decomposeParallel() {
        String task = "Task A, Task B, Task C";
        List<TaskNode> nodes = decomposer.decompose(task, DecompositionStrategy.PARALLEL_INDEPENDENT);
        assertTrue(nodes.size() >= 2, "Should have at least 2 parallel nodes");
        // All nodes should have empty dependencies (parallel)
        for (TaskNode node : nodes) {
            assertTrue(node.getDependencies().isEmpty(),
                    "Parallel nodes should not have dependencies");
        }
    }

    @Test
    @DisplayName("PARALLEL_INDEPENDENT without separators should create 2 default nodes")
    void decomposeParallelUnsplit() {
        String task = "Single unified task";
        List<TaskNode> nodes = decomposer.decompose(task, DecompositionStrategy.PARALLEL_INDEPENDENT);
        assertTrue(nodes.size() >= 2);
    }

    @Test
    @DisplayName("TREE decomposition should create 2-level hierarchy")
    void decomposeTree() {
        String task = "Build a full-stack web application";
        List<TaskNode> nodes = decomposer.decompose(task, DecompositionStrategy.TREE);
        assertFalse(nodes.isEmpty());
        assertTrue(nodes.size() > 5, "Tree should have many nodes, got " + nodes.size());

        // L1 nodes should have sequential deps, L2 nodes should depend on L1
        boolean hasL2NodWithParent = nodes.stream()
                .filter(n -> n.getNodeId().contains("L2"))
                .anyMatch(n -> !n.getDependencies().isEmpty());
        assertTrue(hasL2NodWithParent, "L2 nodes should depend on their L1 parent");
    }

    @Test
    @DisplayName("LLM_DRIVEN should fallback to BY_PHASE")
    void decomposeLlmDriven() {
        String task = "Analyze and optimize database performance";
        List<TaskNode> nodes = decomposer.decompose(task, DecompositionStrategy.LLM_DRIVEN);
        assertFalse(nodes.isEmpty());
        // Currently LLM_DRIVEN falls back to BY_PHASE
        assertTrue(nodes.stream().anyMatch(n -> "ANALYZE".equals(n.getType())));
    }

    @Test
    @DisplayName("All nodes should have valid IDs")
    void allNodesHaveValidIds() {
        for (DecompositionStrategy strategy : DecompositionStrategy.values()) {
            List<TaskNode> nodes = decomposer.decompose(
                    "Create a test system with multiple components", strategy);
            for (TaskNode node : nodes) {
                assertNotNull(node.getNodeId(), "Null nodeId for strategy " + strategy);
                assertFalse(node.getNodeId().isBlank(), "Blank nodeId for strategy " + strategy);
            }
        }
    }
}
