package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.tool.ToolErrorAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 DefaultToolCallPolicy 的策略控制
 */
@DisplayName("DefaultToolCallPolicy 测试")
class DefaultToolCallPolicyTest {

    private DefaultToolCallPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new DefaultToolCallPolicy();
    }

    @Nested
    @DisplayName("最大轮次控制")
    class MaxRounds {

        @Test
        void testDefaultMaxRounds() {
            assertEquals(10, policy.getMaxRounds());
        }

        @Test
        void testSetMaxRounds() {
            policy.setMaxRounds(5);
            assertEquals(5, policy.getMaxRounds());
        }

        @Test
        void testSetMaxRoundsClamped() {
            policy.setMaxRounds(0);
            assertEquals(1, policy.getMaxRounds());
        }

        @Test
        void testShouldContinueWithinLimit() {
            assertTrue(policy.shouldContinue(null, 9));
        }

        @Test
        void testShouldContinueAtLimit() {
            assertFalse(policy.shouldContinue(null, 10));
        }

        @Test
        void testShouldContinueExceedsLimit() {
            assertFalse(policy.shouldContinue(null, 11));
        }
    }

    @Nested
    @DisplayName("工具调用频率限制")
    class CallFrequency {

        @Test
        void testDefaultMaxCallsPerTool() {
            assertEquals(20, policy.getMaxCallsPerTool());
        }

        @Test
        void testCanExecuteWithinLimit() {
            for (int i = 0; i < 20; i++) {
                assertTrue(policy.canExecute("calc", 0, "session1"));
            }
        }

        @Test
        void testCanExecuteExceedsLimit() {
            for (int i = 0; i < 20; i++) {
                policy.canExecute("calc", 0, "session1");
            }
            assertFalse(policy.canExecute("calc", 0, "session1"));
        }

        @Test
        void testResetAllCallCounters() {
            for (int i = 0; i < 20; i++) {
                policy.canExecute("calc", 0, "session1");
            }
            policy.resetAllCallCounters();
            assertTrue(policy.canExecute("calc", 0, "session1"));
        }

        @Test
        void testResetCallCountersBySession() {
            for (int i = 0; i < 20; i++) {
                policy.canExecute("calc", 0, "session1");
            }
            policy.resetCallCounters("session1");
            assertTrue(policy.canExecute("calc", 0, "session1"));
        }
    }

    @Nested
    @DisplayName("白名单/黑名单")
    class WhitelistBlacklist {

        @Test
        void testWhitelistAllowsOnlyListedTools() {
            policy.allowTool("calc");
            assertTrue(policy.canExecute("calc", 0, "s1"));
            assertFalse(policy.canExecute("cmd", 0, "s1"));
        }

        @Test
        void testEmptyWhitelistAllowsAll() {
            assertTrue(policy.canExecute("calc", 0, "s1"));
            assertTrue(policy.canExecute("cmd", 0, "s1"));
        }

        @Test
        void testBlockTool() {
            policy.blockTool("cmd");
            assertTrue(policy.canExecute("calc", 0, "s1"));
            assertFalse(policy.canExecute("cmd", 0, "s1"));
        }

        @Test
        void testUnblockTool() {
            policy.blockTool("cmd");
            assertFalse(policy.canExecute("cmd", 0, "s1"));
            policy.unblockTool("cmd");
            assertTrue(policy.canExecute("cmd", 0, "s1"));
        }

        @Test
        void testGetAllowedAndBlockedTools() {
            policy.allowTool("calc");
            policy.blockTool("cmd");

            Set<String> allowed = policy.getAllowedTools();
            Set<String> blocked = policy.getBlockedTools();

            assertTrue(allowed.contains("calc"));
            assertTrue(blocked.contains("cmd"));
        }
    }

    @Nested
    @DisplayName("错误处理")
    class ErrorHandling {

        @Test
        void testDefaultErrorAction() {
            assertEquals(ToolErrorAction.ABORT, policy.handleToolError(null, new RuntimeException(), null));
        }

        @Test
        void testSetDefaultErrorAction() {
            policy.setDefaultErrorAction(ToolErrorAction.RETRY);
            assertEquals(ToolErrorAction.RETRY, policy.handleToolError(null, new RuntimeException(), null));
        }

        @Test
        void testDefaultMaxRetries() {
            assertTrue(policy.shouldRetryOnError(null, new RuntimeException(), 0));
            assertTrue(policy.shouldRetryOnError(null, new RuntimeException(), 2));
            assertFalse(policy.shouldRetryOnError(null, new RuntimeException(), 3));
        }
    }
}
