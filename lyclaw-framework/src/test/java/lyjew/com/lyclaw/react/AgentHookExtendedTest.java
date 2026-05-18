package lyjew.com.lyclaw.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.annotation.agent.SystemMessage;
import lyjew.com.lyclaw.annotation.agent.UserMessage;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.tool.ToolRegistry;

/**
 * AgentHook 扩展方法测试：beforeModel / afterModel / wrapToolCall。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentHook 扩展方法测试")
class AgentHookExtendedTest {

    @Mock private ChatFacade chatFacade;
    @Mock private ReActEngine reActEngine;
    @Mock private ToolRegistry toolRegistry;

    @Agent(name = "test", description = "测试")
    interface TestAgent {
        @SystemMessage("You are a test agent.")
        String chat(@UserMessage String message);
    }

    @BeforeEach
    void setUp() {
        lenient().when(toolRegistry.getAllDefinitions(any(ChatRequest.class))).thenReturn(List.of());
    }

    @Nested
    @DisplayName("beforeModel")
    class BeforeModelTests {

        @Test
        @DisplayName("默认实现返回原消息列表")
        void shouldReturnOriginalMessagesByDefault() {
            when(reActEngine.execute(any(), any(ChatRequest.class), any())).thenReturn("ok");

            TestAgent agent = LyClawAgent.builder(TestAgent.class)
                    .chatFacade(chatFacade).reActEngine(reActEngine).tools(toolRegistry)
                    .hooks(List.of(new AgentHook() { /* 全部默认实现 */ }))
                    .build();

            String result = agent.chat("hello");
            assertThat(result).isEqualTo("ok");
        }

        @Test
        @DisplayName("beforeModel 注入系统消息")
        void shouldInjectSystemMessageViaBeforeModel() {
            when(reActEngine.execute(any(), any(ChatRequest.class), any())).thenReturn("ok");

            AgentHook injectionHook = new AgentHook() {
                @Override
                public int getOrder() { return 10; }

                @Override
                public List<Message> beforeModel(List<Message> messages, AgentContext ctx) {
                    List<Message> enriched = new ArrayList<>(messages);
                    enriched.add(0, Message.system("[Injected: additional context]"));
                    return enriched;
                }
            };

            TestAgent agent = LyClawAgent.builder(TestAgent.class)
                    .chatFacade(chatFacade).reActEngine(reActEngine).tools(toolRegistry)
                    .hooks(List.of(injectionHook))
                    .build();

            String result = agent.chat("hello");
            assertThat(result).isEqualTo("ok");
        }
    }

    @Nested
    @DisplayName("afterModel")
    class AfterModelTests {

        @Test
        @DisplayName("afterModel 修改 LLM 响应")
        void shouldModifyResponseViaAfterModel() {
            when(reActEngine.execute(any(), any(ChatRequest.class), any())).thenReturn("raw response");

            AgentHook filterHook = new AgentHook() {
                @Override
                public int getOrder() { return 90; }

                @Override
                public String afterResult(String result, AgentContext ctx) {
                    return "[filtered] " + result;
                }
            };

            TestAgent agent = LyClawAgent.builder(TestAgent.class)
                    .chatFacade(chatFacade).reActEngine(reActEngine).tools(toolRegistry)
                    .hooks(List.of(filterHook))
                    .build();

            String result = agent.chat("hello");
            assertThat(result).isEqualTo("[filtered] raw response");
        }

        @Test
        @DisplayName("afterResult 按 order 降序执行")
        void shouldExecuteAfterResultInDescendingOrder() {
            when(reActEngine.execute(any(), any(ChatRequest.class), any())).thenReturn("original");

            // order=10 先执行 beforeRequest，后执行 afterResult
            AgentHook early = new AgentHook() {
                @Override
                public int getOrder() { return 10; }

                @Override
                public String afterResult(String result, AgentContext ctx) {
                    return result + " [early-after]";
                }
            };

            // order=90 后执行 beforeRequest，先执行 afterResult
            AgentHook late = new AgentHook() {
                @Override
                public int getOrder() { return 90; }

                @Override
                public String afterResult(String result, AgentContext ctx) {
                    return result + " [late-after]";
                }
            };

            TestAgent agent = LyClawAgent.builder(TestAgent.class)
                    .chatFacade(chatFacade).reActEngine(reActEngine).tools(toolRegistry)
                    .hooks(List.of(early, late))
                    .build();

            String result = agent.chat("hello");
            // afterResult 按 order 降序：90(late) first → 10(early) second
            assertThat(result).isEqualTo("original [late-after] [early-after]");
        }
    }

    @Nested
    @DisplayName("wrapToolCall")
    class WrapToolCallTests {

        @Test
        @DisplayName("默认 wrapToolCall 返回原 ToolCall")
        void shouldReturnOriginalToolCallByDefault() {
            ToolCall original = ToolCall.builder()
                    .toolCallId("call-1").name("test_tool").arguments("{}").build();

            AgentHook hook = new AgentHook() {};
            ToolCall result = hook.wrapToolCall(original,
                    new AgentContext("s1", "msg", "sys", null, null, null));

            assertThat(result).isSameAs(original);
        }

        @Test
        @DisplayName("wrapToolCall 可修改 ToolCall 属性")
        void shouldAllowToolCallModification() {
            ToolCall original = ToolCall.builder()
                    .toolCallId("call-1").name("test_tool").arguments("{\"key\":\"val\"}").build();

            AgentHook auditHook = new AgentHook() {
                @Override
                public ToolCall wrapToolCall(ToolCall toolCall, AgentContext ctx) {
                    return ToolCall.builder()
                            .toolCallId(toolCall.getToolCallId())
                            .name("[audited] " + toolCall.getName())
                            .arguments(toolCall.getArguments())
                            .build();
                }
            };

            ToolCall result = auditHook.wrapToolCall(original,
                    new AgentContext("s1", "msg", "sys", null, null, null));

            assertThat(result.getName()).isEqualTo("[audited] test_tool");
            assertThat(result.getArguments()).isEqualTo("{\"key\":\"val\"}");
        }
    }

    @Nested
    @DisplayName("beforeRequest 兼容性")
    class BeforeRequestCompatTests {

        @Test
        @DisplayName("beforeRequest 可修改 ctx 的 userMessage")
        void shouldModifyUserMessageViaBeforeRequest() {
            when(reActEngine.execute(any(), any(ChatRequest.class), any())).thenReturn("response");

            AgentHook transformHook = new AgentHook() {
                @Override
                public int getOrder() { return 10; }

                @Override
                public void beforeRequest(AgentContext ctx) {
                    ctx.setUserMessage("[transformed] " + ctx.getUserMessage());
                }
            };

            TestAgent agent = LyClawAgent.builder(TestAgent.class)
                    .chatFacade(chatFacade).reActEngine(reActEngine).tools(toolRegistry)
                    .hooks(List.of(transformHook))
                    .build();

            String result = agent.chat("hello");
            assertThat(result).isEqualTo("response");
        }
    }
}
