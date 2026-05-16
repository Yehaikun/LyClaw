package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 测试 ToolSandboxImpl 的 3 种执行模式
 */
@DisplayName("ToolSandboxImpl 3模式沙箱测试")
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
            ToolExecutionResult result = sandbox.execute(mockTool, Map.of(), SandboxLevel.DIRECT);
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("沙箱不可用"));
        }
    }

    @Nested
    @DisplayName("DIRECT 模式")
    class DirectMode {

        @Test
        void testDirectExecutesInCurrentThread() {
            try {
                Tool mockTool = mock(Tool.class);
                when(mockTool.getName()).thenReturn("calculator");
                when(mockTool.execute(any(), nullable(lyjew.com.lyclaw.context.ChatContext.class)))
                        .thenReturn(ToolExecutionResult.builder().success(true).result("42").elapsedMs(5).build());

                ToolExecutionResult result = sandbox.execute(mockTool, Map.of(), SandboxLevel.DIRECT);
                assertTrue(result.isSuccess());
                assertEquals("42", result.getResult());
                assertTrue(result.getElapsedMs() >= 0);
            } finally {
                destroySandbox();
            }
        }
    }

    @Nested
    @DisplayName("SANDBOX 模式")
    class SandboxMode {

        @Test
        void testSandboxHandlesToolError() {
            try {
                Tool calc = mock(Tool.class);
                when(calc.getName()).thenReturn("calculator");
                when(calc.execute(any(), nullable(lyjew.com.lyclaw.context.ChatContext.class)))
                        .thenThrow(new RuntimeException("test error"));

                ToolExecutionResult result = sandbox.execute(calc, Map.of(), SandboxLevel.SANDBOX);
                assertFalse(result.isSuccess());
                assertTrue(result.getError().contains("受限沙箱执行异常"));
            } finally {
                destroySandbox();
            }
        }
    }

    @Nested
    @DisplayName("PROCESS 模式 (非command工具降级到 SANDBOX)")
    class ProcessMode {

        @Test
        void testProcessFallsBackToSandboxForNonCommand() {
            try {
                Tool calc = mock(Tool.class);
                when(calc.getName()).thenReturn("calculator");
                when(calc.execute(any(), nullable(lyjew.com.lyclaw.context.ChatContext.class)))
                        .thenReturn(ToolExecutionResult.builder().success(true).result("42").elapsedMs(5).build());

                ToolExecutionResult result = sandbox.execute(calc, Map.of(), SandboxLevel.PROCESS);
                assertTrue(result.isSuccess());
            } finally {
                destroySandbox();
            }
        }
    }

    @Nested
    @DisplayName("null级别回退")
    class NullLevel {

        @Test
        void testNullLevelDefaultsToDirect() {
            try {
                Tool calc = mock(Tool.class);
                when(calc.getName()).thenReturn("calculator");
                when(calc.execute(any(), nullable(lyjew.com.lyclaw.context.ChatContext.class)))
                        .thenReturn(ToolExecutionResult.builder().success(true).result("42").elapsedMs(5).build());

                ToolExecutionResult result = sandbox.execute(calc, Map.of(), null);
                assertTrue(result.isSuccess(), "null级别应回退到DIRECT并成功执行: " + result.getError());
            } finally {
                destroySandbox();
            }
        }
    }
}
