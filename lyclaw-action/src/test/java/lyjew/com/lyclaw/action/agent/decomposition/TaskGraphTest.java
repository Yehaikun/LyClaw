package lyjew.com.lyclaw.action.agent.decomposition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TaskGraph")
class TaskGraphTest {

    private TaskNode nodeA;
    private TaskNode nodeB;
    private TaskNode nodeC;
    private TaskEdge edgeAB;
    private TaskEdge edgeBC;

    @BeforeEach
    void setUp() {
        nodeA = new TaskNode("A", "数据库设计");
        nodeB = new TaskNode("B", "API开发");
        nodeC = new TaskNode("C", "前端开发");
        edgeAB = new TaskEdge("A", "B");
        edgeBC = new TaskEdge("B", "C");
    }

    @Nested
    @DisplayName("DAG 结构与遍历")
    class GraphStructure {

        @Test
        @DisplayName("根节点是无依赖的节点")
        void rootNodes() {
            TaskGraph graph = new TaskGraph(List.of(nodeA, nodeB, nodeC), List.of(edgeAB, edgeBC));
            List<TaskNode> roots = graph.getRootNodes();
            assertThat(roots).hasSize(1);
            assertThat(roots.get(0).getId()).isEqualTo("A");
        }

        @Test
        @DisplayName("多根节点")
        void multipleRoots() {
            TaskNode nodeD = new TaskNode("D", "独立任务");
            TaskGraph graph = new TaskGraph(List.of(nodeA, nodeD), List.of());
            assertThat(graph.getRootNodes()).hasSize(2);
        }

        @Test
        @DisplayName("获取下一批可执行节点（依赖满足的节点）")
        void nextNodes() {
            TaskGraph graph = new TaskGraph(List.of(nodeA, nodeB, nodeC), List.of(edgeAB, edgeBC));
            nodeA.setStatus(TaskNode.Status.COMPLETED);
            List<TaskNode> next = graph.getNextNodes(nodeA);
            assertThat(next).hasSize(1);
            assertThat(next.get(0).getId()).isEqualTo("B");
        }

        @Test
        @DisplayName("当依赖未完成时，下游节点不可执行")
        void nextNodesBlockedByDependency() {
            TaskGraph graph = new TaskGraph(List.of(nodeA, nodeB), List.of(edgeAB));
            // nodeA 还是 PENDING，所以 B 不能被调度
            List<TaskNode> next = graph.getNextNodes(nodeA);
            assertThat(next).isEmpty();
        }

        @Test
        @DisplayName("无依赖的图，所有节点都是根节点")
        void noEdgesAllRoots() {
            TaskGraph graph = new TaskGraph(List.of(nodeA, nodeB), List.of());
            assertThat(graph.getRootNodes()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("完成状态")
    class CompletionStatus {

        @Test
        @DisplayName("所有节点完成时 isComplete 为 true")
        void allCompleted() {
            TaskGraph graph = new TaskGraph(List.of(nodeA, nodeB), List.of(edgeAB));
            nodeA.setStatus(TaskNode.Status.COMPLETED);
            nodeB.setStatus(TaskNode.Status.COMPLETED);
            assertThat(graph.isComplete()).isTrue();
        }

        @Test
        @DisplayName("存在 PENDING 节点时 isComplete 为 false")
        void hasPending() {
            TaskGraph graph = new TaskGraph(List.of(nodeA, nodeB), List.of(edgeAB));
            nodeA.setStatus(TaskNode.Status.COMPLETED);
            nodeB.setStatus(TaskNode.Status.PENDING);
            assertThat(graph.isComplete()).isFalse();
        }

        @Test
        @DisplayName("FAILED 节点也视为完成（终止条件）")
        void failedIsTerminal() {
            TaskGraph graph = new TaskGraph(List.of(nodeA, nodeB), List.of(edgeAB));
            nodeA.setStatus(TaskNode.Status.COMPLETED);
            nodeB.setStatus(TaskNode.Status.FAILED);
            assertThat(graph.isComplete()).isTrue();
        }

        @Test
        @DisplayName("存在 FAILED 节点时 hasFailed 为 true")
        void hasFailedDetectsFailures() {
            TaskGraph graph = new TaskGraph(List.of(nodeA, nodeB), List.of(edgeAB));
            nodeA.setStatus(TaskNode.Status.COMPLETED);
            nodeB.setStatus(TaskNode.Status.FAILED);
            assertThat(graph.hasFailed()).isTrue();
        }
    }

    @Nested
    @DisplayName("节点管理")
    class NodeManagement {

        @Test
        @DisplayName("更新节点状态")
        void updateNodeStatus() {
            TaskGraph graph = new TaskGraph(List.of(nodeA), List.of());
            graph.updateNodeStatus("A", TaskNode.Status.RUNNING);
            assertThat(nodeA.getStatus()).isEqualTo(TaskNode.Status.RUNNING);
        }

        @Test
        @DisplayName("分配节点给 Agent")
        void assignNode() {
            TaskGraph graph = new TaskGraph(List.of(nodeA), List.of());
            graph.assignNode("A", "agent-db");
            assertThat(nodeA.getAssignedAgentId()).isEqualTo("agent-db");
        }

        @Test
        @DisplayName("统计总数和完成数")
        void counts() {
            TaskGraph graph = new TaskGraph(List.of(nodeA, nodeB), List.of(edgeAB));
            assertThat(graph.totalNodes()).isEqualTo(2);
            assertThat(graph.completedNodes()).isEqualTo(0);
            nodeA.setStatus(TaskNode.Status.COMPLETED);
            assertThat(graph.completedNodes()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("TaskNode")
    class TaskNodeTests {

        @Test
        @DisplayName("初始状态为 PENDING")
        void initialState() {
            assertThat(nodeA.getStatus()).isEqualTo(TaskNode.Status.PENDING);
        }

        @Test
        @DisplayName("创建时间戳不为零")
        void createdAt() {
            assertThat(nodeA.getCreatedAt()).isGreaterThan(0);
        }

        @Test
        @DisplayName("添加候选 Agent")
        void addCandidate() {
            nodeA.addCandidate("agent-1");
            nodeA.addCandidate("agent-2");
            assertThat(nodeA.getCandidates()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("TaskEdge")
    class TaskEdgeTests {

        @Test
        @DisplayName("默认条件为 success")
        void defaultCondition() {
            assertThat(edgeAB.getCondition()).isEqualTo("success");
        }

        @Test
        @DisplayName("自定义条件")
        void customCondition() {
            TaskEdge edge = new TaskEdge("A", "B", "score > 0.8");
            assertThat(edge.getCondition()).isEqualTo("score > 0.8");
        }

        @Test
        @DisplayName("toString 包含箭头")
        void toStringContainsArrow() {
            assertThat(edgeAB.toString()).contains("→");
        }
    }
}
