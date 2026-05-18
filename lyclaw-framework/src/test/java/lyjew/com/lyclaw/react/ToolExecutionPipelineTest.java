package lyjew.com.lyclaw.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.tool.DefaultToolExecutionPipeline;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolCallPolicy;
import lyjew.com.lyclaw.tool.ToolExecutionPipeline;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lyjew.com.lyclaw.tool.ToolHook;
import lyjew.com.lyclaw.tool.ToolRegistry;

/**
 * ToolExecutionPipeline 7 步管线测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolExecutionPipeline 测试")
class ToolExecutionPipelineTest {

    @Mock private ToolRegistry toolRegistry;
    @Mock private Tool tool;
    @Mock private ToolCallPolicy toolCallPolicy;

    private AgentContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new AgentContext("s1", "hello", "sys", toolRegistry, null, null);
        ctx.setChatRequest(ChatRequest.builder().messages(List.of()).build());
    }

    private ToolCall buildToolCall(String name, String arguments) {
        return ToolCall.builder()
                .toolCallId("call-1").name(name).arguments(arguments).build();
    }

    @Nested
    @DisplayName("正常执行路径")
    class NormalExecutionTests {

        @Test
        @DisplayName("完整 7 步流程：resolve → policy → beforeHook → invoke → afterHook → format")
        void shouldExecuteFullPipeline() {
            ToolCall toolCall = buildToolCall("get_weather", "{\"city\":\"北京\"}");

            when(toolRegistry.get("get_weather")).thenReturn(tool);
            when(toolCallPolicy.canExecute("get_weather", null)).thenReturn(true);
            when(toolRegistry.execute(toolCall, null))
                    .thenReturn(ToolExecutionResult.success("北京：晴，25°C", "get_weather"));

            ToolExecutionPipeline pipeline = new DefaultToolExecutionPipeline(
                    toolRegistry, toolCallPolicy, List.of());

            String result = pipeline.execute(toolCall, ctx);

            assertThat(result).isEqualTo("北京：晴，25°C");
            assertThat(ctx.getSuccessCount().get()).isEqualTo(1);
            assertThat(ctx.getFailCount().get()).isEqualTo(0);
            assertThat(ctx.getToolResults()).contains("北京：晴，25°C");
        }

        @Test
        @DisplayName("工具返回 null 结果 → 返回空字符串")
        void shouldReturnEmptyOnNullResult() {
            ToolCall toolCall = buildToolCall("echo", "{}");

            when(toolRegistry.get("echo")).thenReturn(tool);
            when(toolCallPolicy.canExecute("echo", null)).thenReturn(true);
            when(toolRegistry.execute(toolCall, null))
                    .thenReturn(ToolExecutionResult.success(null, "echo"));

            ToolExecutionPipeline pipeline = new DefaultToolExecutionPipeline(
                    toolRegistry, toolCallPolicy, List.of());

            String result = pipeline.execute(toolCall, ctx);

            assertThat(result).isEqualTo("");
        }
    }

    @Nested
    @DisplayName("Step 1: Resolve 失败")
    class ResolveFailureTests {

        @Test
        @DisplayName("工具未注册 → 返回错误")
        void shouldReturnErrorWhenToolNotFound() {
            ToolCall toolCall = buildToolCall("unknown_tool", "{}");
            when(toolRegistry.get("unknown_tool")).thenReturn(null);

            ToolExecutionPipeline pipeline = new DefaultToolExecutionPipeline(
                    toolRegistry, toolCallPolicy, List.of());

            String result = pipeline.execute(toolCall, ctx);

            assertThat(result).isEqualTo("Error: tool not found: unknown_tool");
        }
    }

    @Nested
    @DisplayName("Step 2: Policy 拦截")
    class PolicyBlockTests {

        @Test
        @DisplayName("策略拦截 → 返回错误")
        void shouldReturnErrorWhenPolicyBlocks() {
            ToolCall toolCall = buildToolCall("risky_tool", "{}");

            when(toolRegistry.get("risky_tool")).thenReturn(tool);
            when(toolCallPolicy.canExecute("risky_tool", null)).thenReturn(false);

            ToolExecutionPipeline pipeline = new DefaultToolExecutionPipeline(
                    toolRegistry, toolCallPolicy, List.of());

            String result = pipeline.execute(toolCall, ctx);

            assertThat(result).isEqualTo("Error: tool call blocked by policy: risky_tool");
            verify(toolRegistry, never()).execute(any(), any());
        }
    }

    @Nested
    @DisplayName("Step 3/6: ToolHook")
    class ToolHookTests {

        @Test
        @DisplayName("beforeExecution 抛出异常 → onError 返回降级结果")
        void shouldUseOnErrorWhenBeforeExecutionThrows() {
            ToolCall toolCall = buildToolCall("fragile_tool", "{}");

            when(toolRegistry.get("fragile_tool")).thenReturn(tool);
            when(toolCallPolicy.canExecute("fragile_tool", null)).thenReturn(true);

            ToolHook brokenHook = new ToolHook() {
                @Override
                public int getOrder() { return 10; }

                @Override
                public void beforeExecution(ToolCall tc, AgentContext c) {
                    throw new RuntimeException("pre-flight check failed");
                }

                @Override
                public String onError(ToolCall tc, Throwable error, AgentContext c) {
                    return "Error: pre-flight check failed";
                }
            };

            ToolExecutionPipeline pipeline = new DefaultToolExecutionPipeline(
                    toolRegistry, toolCallPolicy, List.of(brokenHook));

            String result = pipeline.execute(toolCall, ctx);

            assertThat(result).isEqualTo("Error: pre-flight check failed");
            verify(toolRegistry, never()).execute(any(), any());
        }

        @Test
        @DisplayName("afterExecution 修改结果")
        void shouldModifyResultViaAfterExecution() {
            ToolCall toolCall = buildToolCall("calc", "{\"expr\":\"1+1\"}");

            when(toolRegistry.get("calc")).thenReturn(tool);
            when(toolCallPolicy.canExecute("calc", null)).thenReturn(true);
            when(toolRegistry.execute(toolCall, null))
                    .thenReturn(ToolExecutionResult.success("2", "calc"));

            ToolHook resultHook = new ToolHook() {
                @Override
                public int getOrder() { return 50; }

                @Override
                public String afterExecution(String result, ToolCall tc, AgentContext c) {
                    return "[formatted] " + result;
                }
            };

            ToolExecutionPipeline pipeline = new DefaultToolExecutionPipeline(
                    toolRegistry, toolCallPolicy, List.of(resultHook));

            String result = pipeline.execute(toolCall, ctx);

            assertThat(result).isEqualTo("[formatted] 2");
        }

        @Test
        @DisplayName("Hook 按 order 升序执行 beforeExecution")
        void shouldExecuteHooksInOrder() {
            ToolCall toolCall = buildToolCall("ordered_tool", "{}");

            when(toolRegistry.get("ordered_tool")).thenReturn(tool);
            when(toolCallPolicy.canExecute("ordered_tool", null)).thenReturn(true);
            when(toolRegistry.execute(toolCall, null))
                    .thenReturn(ToolExecutionResult.success("done", "ordered_tool"));

            StringBuilder orderLog = new StringBuilder();

            ToolHook earlyHook = new ToolHook() {
                @Override
                public int getOrder() { return 10; }

                @Override
                public void beforeExecution(ToolCall tc, AgentContext c) {
                    orderLog.append("early-before;");
                }

                @Override
                public String afterExecution(String result, ToolCall tc, AgentContext c) {
                    orderLog.append("early-after;");
                    return result;
                }
            };

            ToolHook lateHook = new ToolHook() {
                @Override
                public int getOrder() { return 50; }

                @Override
                public void beforeExecution(ToolCall tc, AgentContext c) {
                    orderLog.append("late-before;");
                }

                @Override
                public String afterExecution(String result, ToolCall tc, AgentContext c) {
                    orderLog.append("late-after;");
                    return result;
                }
            };

            ToolExecutionPipeline pipeline = new DefaultToolExecutionPipeline(
                    toolRegistry, toolCallPolicy, List.of(lateHook, earlyHook));

            pipeline.execute(toolCall, ctx);

            assertThat(orderLog.toString())
                    .isEqualTo("early-before;late-before;early-after;late-after;");
        }
    }

    @Nested
    @DisplayName("Step 5: Invoke 异常")
    class InvokeExceptionTests {

        @Test
        @DisplayName("工具执行抛出异常 → 返回错误并增加 failCount")
        void shouldHandleExecutionException() {
            ToolCall toolCall = buildToolCall("buggy", "{}");

            when(toolRegistry.get("buggy")).thenReturn(tool);
            when(toolCallPolicy.canExecute("buggy", null)).thenReturn(true);
            when(toolRegistry.execute(toolCall, null))
                    .thenThrow(new RuntimeException("execution exploded"));

            ToolExecutionPipeline pipeline = new DefaultToolExecutionPipeline(
                    toolRegistry, toolCallPolicy, List.of());

            String result = pipeline.execute(toolCall, ctx);

            assertThat(result).startsWith("Error:");
            assertThat(result).contains("execution exploded");
            assertThat(ctx.getFailCount().get()).isEqualTo(1);
        }

        @Test
        @DisplayName("执行异常 + Hook.onError → 使用 Hook 的降级结果")
        void shouldUseHookOnErrorForExecutionException() {
            ToolCall toolCall = buildToolCall("flaky", "{}");

            when(toolRegistry.get("flaky")).thenReturn(tool);
            when(toolCallPolicy.canExecute("flaky", null)).thenReturn(true);
            when(toolRegistry.execute(toolCall, null))
                    .thenThrow(new RuntimeException("connection reset"));

            ToolHook recoveryHook = new ToolHook() {
                @Override
                public int getOrder() { return 10; }

                @Override
                public String onError(ToolCall tc, Throwable error, AgentContext c) {
                    return "Fallback: service temporarily unavailable";
                }
            };

            ToolExecutionPipeline pipeline = new DefaultToolExecutionPipeline(
                    toolRegistry, toolCallPolicy, List.of(recoveryHook));

            String result = pipeline.execute(toolCall, ctx);

            assertThat(result).isEqualTo("Fallback: service temporarily unavailable");
        }
    }

    @Nested
    @DisplayName("边缘情况")
    class EdgeCaseTests {

        @Test
        @DisplayName("ToolRegistry.execute 返回 failure → executeByName 也返回 failure → 识别错误结果")
        void shouldHandleFailureResult() {
            ToolCall toolCall = buildToolCall("slow_tool", "{}");

            when(toolRegistry.get("slow_tool")).thenReturn(tool);
            when(toolCallPolicy.canExecute("slow_tool", null)).thenReturn(true);
            when(toolRegistry.execute(toolCall, null))
                    .thenReturn(ToolExecutionResult.failure("timeout after 30s", "slow_tool"));
            when(toolRegistry.executeByName("slow_tool", "call-1", "{}", ctx.getChatRequest()))
                    .thenReturn(ToolExecutionResult.failure("timeout after 30s", "slow_tool"));

            ToolExecutionPipeline pipeline = new DefaultToolExecutionPipeline(
                    toolRegistry, toolCallPolicy, List.of());

            String result = pipeline.execute(toolCall, ctx);

            assertThat(result).isEqualTo("Error: timeout after 30s");
            assertThat(ctx.getFailCount().get()).isEqualTo(1);
        }

        @Test
        @DisplayName("空 arguments → 不抛异常")
        void shouldHandleEmptyArguments() {
            ToolCall toolCall = buildToolCall("no_args_tool", null);

            when(toolRegistry.get("no_args_tool")).thenReturn(tool);
            when(toolCallPolicy.canExecute("no_args_tool", null)).thenReturn(true);
            when(toolRegistry.execute(toolCall, null))
                    .thenReturn(ToolExecutionResult.success("ok", "no_args_tool"));

            ToolExecutionPipeline pipeline = new DefaultToolExecutionPipeline(
                    toolRegistry, toolCallPolicy, List.of());

            String result = pipeline.execute(toolCall, ctx);

            assertThat(result).isEqualTo("ok");
        }
    }
}
