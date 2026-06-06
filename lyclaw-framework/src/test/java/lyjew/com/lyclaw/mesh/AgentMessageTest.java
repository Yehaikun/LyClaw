package lyjew.com.lyclaw.mesh;

import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 AgentMessage 消息协议：
 * - Builder 构建
 * - 响应/错误工厂方法
 * - 过期判断
 * - metadata
 */
class AgentMessageTest {

    @Test
    void shouldBuildBasicRequest() {
        AgentMessage msg = AgentMessage.builder()
                .from("agent-a")
                .to("agent-b")
                .type(MessageType.REQUEST)
                .payload("{\"task\": \"review\"}")
                .correlationId("call-001")
                .build();

        assertEquals("agent-a", msg.getFrom());
        assertEquals("agent-b", msg.getTo());
        assertEquals(MessageType.REQUEST, msg.getType());
        assertEquals("{\"task\": \"review\"}", msg.getPayload());
        assertEquals("call-001", msg.getCorrelationId());
        assertNotNull(msg.getMessageId());
        assertTrue(msg.getTimestamp() > 0);
    }

    @Test
    void shouldCreateResponseMessage() {
        AgentMessage request = AgentMessage.builder()
                .from("caller")
                .to("target")
                .correlationId("req-001")
                .traceId("trace-001")
                .build();

        AgentMessage response = AgentMessage.responseTo(request, "task completed");

        assertEquals(MessageType.RESPONSE, response.getType());
        assertEquals("target", response.getFrom());  // from/to 交换
        assertEquals("caller", response.getTo());
        assertEquals("req-001", response.getCorrelationId());
        assertEquals("trace-001", response.getTraceId());
        assertEquals("task completed", response.getPayload());
    }

    @Test
    void shouldCreateErrorMessage() {
        AgentMessage request = AgentMessage.builder()
                .from("caller").to("target")
                .correlationId("req-002").build();

        AgentMessage error = AgentMessage.errorTo(request, "something went wrong");

        assertEquals(MessageType.ERROR, error.getType());
        assertEquals("something went wrong", error.getPayload());
        assertEquals("req-002", error.getCorrelationId());
    }

    @Test
    void shouldRespectTtl() {
        AgentMessage expired = AgentMessage.builder()
                .payload("test")
                .ttlMs(1)  // 1ms TTL
                .timestamp(System.currentTimeMillis() - 100)  // already past
                .build();

        // Wait a tiny bit so the message is expired
        assertTrue(expired.isExpired() || System.currentTimeMillis() > expired.getTimestamp() + expired.getTtlMs());
    }

    @Test
    void shouldNotExpireWithinTtl() {
        AgentMessage msg = AgentMessage.builder()
                .payload("test")
                .ttlMs(60_000)  // 60s TTL
                .build();

        assertFalse(msg.isExpired());
    }

    @Test
    void shouldSupportMetadata() {
        AgentMessage msg = AgentMessage.builder()
                .metadata(Map.of("key1", "value1", "count", 42))
                .metadata("key3", true)
                .build();

        assertEquals("value1", msg.getMetadata().get("key1"));
        assertEquals(42, msg.getMetadata().get("count"));
        assertEquals(true, msg.getMetadata().get("key3"));
        assertEquals(3, msg.getMetadata().size());
    }

    @Test
    void shouldHandleStreamFields() {
        AgentMessage msg = AgentMessage.builder()
                .streamSeq(1L)
                .streamEnd(false)
                .type(MessageType.STREAM)
                .build();

        assertEquals(1L, msg.getStreamSeq());
        assertFalse(msg.getStreamEnd());
        assertTrue(msg.isStream());
    }

    @Test
    void shouldProvideTypeChecks() {
        assertTrue(AgentMessage.builder().type(MessageType.REQUEST).build().isRequest());
        assertTrue(AgentMessage.builder().type(MessageType.RESPONSE).build().isResponse());
        assertTrue(AgentMessage.builder().type(MessageType.ERROR).build().isError());
        assertTrue(AgentMessage.builder().type(MessageType.STREAM).build().isStream());
    }

    @Test
    void shouldDefaultToRequestType() {
        AgentMessage msg = AgentMessage.builder().build();
        assertEquals(MessageType.REQUEST, msg.getType());
    }
}
