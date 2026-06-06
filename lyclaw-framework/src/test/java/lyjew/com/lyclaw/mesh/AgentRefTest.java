package lyjew.com.lyclaw.mesh;

import java.util.Set;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 AgentRef 轻量级引用：
 * - 创建与相等性
 * - 能力匹配
 * - 不可变性
 */
class AgentRefTest {

    @Test
    void shouldCreateRef() {
        AgentRef ref = new AgentRef("agent-a", AgentRef.AgentType.LLM,
                Set.of("code-review", "refactor"));

        assertEquals("agent-a", ref.getAgentId());
        assertEquals(AgentRef.AgentType.LLM, ref.getType());
        assertTrue(ref.hasCapability("code-review"));
        assertTrue(ref.hasCapability("refactor"));
        assertFalse(ref.hasCapability("deploy"));
    }

    @Test
    void shouldCheckAllCapabilities() {
        AgentRef ref = new AgentRef("agent-a", AgentRef.AgentType.TOOL,
                Set.of("search", "fetch"));

        assertTrue(ref.hasAllCapabilities(Set.of("search")));
        assertTrue(ref.hasAllCapabilities(Set.of("search", "fetch")));
        assertFalse(ref.hasAllCapabilities(Set.of("search", "unknown")));
        assertTrue(ref.hasAllCapabilities(null));
        assertTrue(ref.hasAllCapabilities(Set.of()));
    }

    @Test
    void equalityShouldBeBasedOnAgentId() {
        AgentRef ref1 = new AgentRef("agent-a", AgentRef.AgentType.LLM, Set.of("a"));
        AgentRef ref2 = new AgentRef("agent-a", AgentRef.AgentType.TOOL, Set.of("b"));

        assertEquals(ref1, ref2);
        assertEquals(ref1.hashCode(), ref2.hashCode());
    }

    @Test
    void shouldBeImmutable() {
        Set<String> caps = new java.util.LinkedHashSet<>();
        caps.add("review");
        AgentRef ref = new AgentRef("agent-a", AgentRef.AgentType.LLM, caps);

        caps.add("extra");  // modify original
        assertFalse(ref.hasCapability("extra"));  // ref should not be affected
    }

    @Test
    void shouldSupportOfFactory() {
        AgentRef ref = AgentRef.of("test-agent");
        assertEquals("test-agent", ref.getAgentId());
        assertEquals(AgentRef.AgentType.LLM, ref.getType());
        assertTrue(ref.getCapabilities().isEmpty());
    }

    @Test
    void shouldHandleNullAgentId() {
        assertThrows(NullPointerException.class, () -> new AgentRef(null, null, null));
    }
}
