package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.action.tool.ToolResult;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 测试 ToolSandboxImpl 的 5 级沙箱隔离
 */
@DisplayName("ToolSandboxImpl 5级沙箱隔离测试")
class ToolSandboxImplTest {

    private ToolSandboxImpl sandbox;

    @BeforeEach
    void setUp() {
        sandbox = new ToolSandboxImpl();
    }

    // 用完立即销毁，避免 daemon 线程残留影响后续测试
    private void destroySandbox() {
        sandbox.destroy();
    }

    @Nested
    @DisplayName("沙箱健康状态")
    class Health {

        @Test
        void testDefaultHealthy() {
            try {
                assertTrue(sandbox.isHealthy());
            } finally {
                destroySandbox();
            }
        }

        @Test
        void testDestroyMakesUnhealthy() {
            sandbox.destroy();
            assertFalse(sandbox.isHealthy());
        }

        @Test
        void testExecuteOnUnhealthySandbox() {
            sandbox.destroy();
            Tool mockTool = mock(Tool.class);
            lenient().when(mockTool.getName()).thenReturn("calc");
            ToolResult result = sandbox.execute(mockTool, Map.of(), SandboxLevel.NONE);
            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("沙箱不可用"));
        }
    }

    @Nested
    @DisplayName("NONE 级别")
    class NoneLevel {

        @Test
        void testNoneLevelExecutesDirectly() {
            try {
                Tool mockTool = mock(Tool.class);
                when(mockTool.getName()).thenReturn("calculator");
                when(mockTool.execute(any(), nullable(lyjew.com.lyclaw.context.ChatContext.class)))
                        .thenReturn(new lyjew.com.lyclaw.tool.ToolResult(true, "42", null, 5, 0));

                ToolResult result = sandbox.execute(mockTool, Map.of(), SandboxLevel.NONE);
                assertTrue(result.isSuccess());
                assertEquals("42", result.getOutput());
                assertTrue(result.getDurationMs() >= 0);
            } finally {
                destroySandbox();
            }
        }
    }

    @Nested
    @DisplayName("READ_ONLY 级别")
    class ReadOnlyLevel {

        @Test
        void testReadOnlyAllowsBuiltinReadTools() {
            try {
                Tool calc = mock(Tool.class);
                when(calc.getName()).thenReturn("calculator");
                when(calc.execute(any(), nullable(lyjew.com.lyclaw.context.ChatContext.class)))
                        .thenReturn(new lyjew.com.lyclaw.tool.ToolResult(true, "ok", null, 5, 0));

                ToolResult result = sandbox.execute(calc, Map.of(), SandboxLevel.READ_ONLY);
                assertTrue(result.isSuccess());
            } finally {
                destroySandbox();
            }
        }

        @Test
        void testReadOnlyBlocksCommandTool() {
            try {
                Tool cmd = mock(Tool.class);
                when(cmd.getName()).thenReturn("command");
                ToolResult result = sandbox.execute(cmd, Map.of(), SandboxLevel.READ_ONLY);
                assertFalse(result.isSuccess());
                assertTrue(result.getErrorMessage().contains("不允许在 READ_ONLY"));
            } finally {
                destroySandbox();
            }
        }

        @Test
        void testReadOnlyAllowsCurrentTime() {
            try {
                Tool time = mock(Tool.class);
                when(time.getName()).thenReturn("current_time");
                when(time.execute(any(), nullable(lyjew.com.lyclaw.context.ChatContext.class)))
                        .thenReturn(new lyjew.com.lyclaw.tool.ToolResult(true, "2025-01-01", null, 5, 0));

                ToolResult result = sandbox.execute(time, Map.of(), SandboxLevel.READ_ONLY);
                assertTrue(result.isSuccess());
            } finally {
                destroySandbox();
            }
        }

        @Test
        void testReadOnlyAllowsWebSearch() {
            try {
                Tool search = mock(Tool.class);
                when(search.getName()).thenReturn("web_search");
                when(search.execute(any(), nullable(lyjew.com.lyclaw.context.ChatContext.class)))
                        .thenReturn(new lyjew.com.lyclaw.tool.ToolResult(true, "results", null, 5, 0));

                ToolResult result = sandbox.execute(search, Map.of(), SandboxLevel.READ_ONLY);
                assertTrue(result.isSuccess());
            } finally {
                destroySandbox();
            }
        }
    }

    @Nested
    @DisplayName("RESTRICTED 级别")
    class RestrictedLevel {

        @Test
        void testRestrictedHandlesToolError() {
            try {
                Tool calc = mock(Tool.class);
                when(calc.getName()).thenReturn("calculator");
                when(calc.execute(any(), nullable(lyjew.com.lyclaw.context.ChatContext.class)))
                        .thenThrow(new RuntimeException("test error"));

                ToolResult result = sandbox.execute(calc, Map.of(), SandboxLevel.RESTRICTED);
                assertFalse(result.isSuccess());
                assertTrue(result.getErrorMessage().contains("受限沙箱执行异常"));
            } finally {
                destroySandbox();
            }
        }
    }

    @Nested
    @DisplayName("CONTAINER/ISOLATED 级别 (回退到 RESTRICTED)")
    class ContainerAndIsolated {

        @Test
        void testContainerFallsBackToRestrictedForNonCommand() {
            try {
                Tool calc = mock(Tool.class);
                when(calc.getName()).thenReturn("calculator");
                when(calc.execute(any(), nullable(lyjew.com.lyclaw.context.ChatContext.class)))
                        .thenReturn(new lyjew.com.lyclaw.tool.ToolResult(true, "42", null, 5, 0));

                ToolResult result = sandbox.execute(calc, Map.of(), SandboxLevel.CONTAINER);
                assertTrue(result.isSuccess());
            } finally {
                destroySandbox();
            }
        }

        @Test
        void testIsolatedFallsBackToRestrictedForNonCommand() {
            try {
                Tool calc = mock(Tool.class);
                when(calc.getName()).thenReturn("calculator");
                when(calc.execute(any(), nullable(lyjew.com.lyclaw.context.ChatContext.class)))
                        .thenReturn(new lyjew.com.lyclaw.tool.ToolResult(true, "42", null, 5, 0));

                ToolResult result = sandbox.execute(calc, Map.of(), SandboxLevel.ISOLATED);
                assertTrue(result.isSuccess());
            } finally {
                destroySandbox();
            }
        }
    }

    @Nested
    @DisplayName("级别覆盖")
    class LevelCoverage {

        @Test
        void testNullLevelDefaultsToNone() {
            try {
                Tool calc = mock(Tool.class);
                when(calc.getName()).thenReturn("calculator");
                when(calc.execute(any(), nullable(lyjew.com.lyclaw.context.ChatContext.class)))
                        .thenReturn(new lyjew.com.lyclaw.tool.ToolResult(true, "42", null, 5, 0));

                ToolResult result = sandbox.execute(calc, Map.of(), null);
                assertTrue(result.isSuccess(), "null级别应回退到NONE并成功执行: " + result.getErrorMessage());
            } finally {
                destroySandbox();
            }
        }
    }
}
