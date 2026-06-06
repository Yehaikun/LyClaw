package lyjew.com.lyclaw.mesh;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 AgentCallHistory 调用链追踪：
 * - 记录子调用
 * - 完成子调用
 * - 查询待处理和已完成
 * - 格式化调用树
 */
class AgentCallHistoryTest {

    @Test
    void shouldRecordChildCall() {
        AgentCallHistory history = new AgentCallHistory("parent-agent");
        history.recordCall("code-reviewer", "Review PR #42", "call-001", 300_000);

        assertEquals(1, history.getAllCalls().size());
        assertEquals(1, history.getPendingCalls().size());
        assertEquals(0, history.getCompletedCalls().size());
    }

    @Test
    void shouldCompleteChildCall() {
        AgentCallHistory history = new AgentCallHistory("parent-agent");
        history.recordCall("code-reviewer", "Review PR #42", "call-001", 300_000);

        AgentMessage response = AgentMessage.builder()
                .type(MessageType.RESPONSE)
                .correlationId("call-001")
                .payload("Found 3 issues")
                .build();

        history.completeCall("call-001", response);

        assertEquals(0, history.getPendingCalls().size());
        assertEquals(1, history.getCompletedCalls().size());
        assertEquals("COMPLETED", history.getAllCalls().get(0).getStatus());
    }

    @Test
    void shouldTrackFailedCall() {
        AgentCallHistory history = new AgentCallHistory("parent-agent");
        history.recordCall("linter", "Run lint", "call-002", 300_000);

        AgentMessage error = AgentMessage.builder()
                .type(MessageType.ERROR)
                .correlationId("call-002")
                .payload("Tool unavailable")
                .build();

        history.completeCall("call-002", error);

        assertEquals("FAILED", history.getAllCalls().get(0).getStatus());
        assertEquals("Tool unavailable", history.getAllCalls().get(0).getResultPayload());
    }

    @Test
    void shouldFormatCallTree() {
        AgentCallHistory history = new AgentCallHistory("parent");
        history.recordCall("reviewer", "Review code", "c1", 300_000);

        AgentMessage resp = AgentMessage.builder()
                .type(MessageType.RESPONSE)
                .correlationId("c1")
                .payload("Done")
                .build();
        history.completeCall("c1", resp);

        String tree = history.formatCallTree();
        assertTrue(tree.contains("当前 Agent 的调用状态"));
        assertTrue(tree.contains("reviewer"));
        assertTrue(tree.contains("COMPLETED"));
    }

    @Test
    void shouldReportPendingCount() {
        AgentCallHistory history = new AgentCallHistory("parent");
        history.recordCall("a", "task-a", "c1", 300_000);
        history.recordCall("b", "task-b", "c2", 300_000);

        assertEquals(2, history.pendingCount());
        assertTrue(history.hasPendingCalls());

        history.completeCall("c1", AgentMessage.builder()
                .type(MessageType.RESPONSE).correlationId("c1").build());

        assertEquals(1, history.pendingCount());
        assertTrue(history.hasPendingCalls());
    }

    @Test
    void shouldGetCallByCorrelationId() {
        AgentCallHistory history = new AgentCallHistory("parent");
        history.recordCall("agent-x", "task", "call-005", 300_000);

        AgentCallHistory.ChildCall call = history.getCall("call-005");
        assertNotNull(call);
        assertEquals("agent-x", call.getChildAgentId());
        assertEquals("task", call.getTask());

        assertNull(history.getCall("nonexistent"));
    }
}
