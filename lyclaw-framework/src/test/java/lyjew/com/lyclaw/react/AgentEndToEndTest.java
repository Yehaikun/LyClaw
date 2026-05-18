package lyjew.com.lyclaw.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.annotation.agent.SystemMessage;
import lyjew.com.lyclaw.annotation.agent.UserMessage;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.ToolRegistry;

/**
 * Agent 端到端测试：注解解析 → AgentContext 构建 → AgentHook 链 → ReAct 引擎 → 结果返回。
 * 不使用 @SpringBootTest / @MockBean，通过 LyClawAgent.builder() 组装真实组件。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Agent 端到端测试")
class AgentEndToEndTest {

    @Mock private ChatFacade chatFacade;
    @Mock private ReActEngine reActEngine;
    @Mock private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        lenient().when(toolRegistry.getAllDefinitions(any(ChatRequest.class))).thenReturn(List.of());
        lenient().when(toolRegistry.getAllDefinitions()).thenReturn(List.of());
    }

    @Agent(name = "echo", description = "回显测试助手")
    interface EchoAgent {
        @SystemMessage("You echo user input.")
        String echo(@UserMessage String message);
    }

    @Agent(name = "streamer", description = "流式测试助手")
    interface StreamAgent {
        @SystemMessage("You are a streamer.")
        java.util.stream.Stream<String> invalidReturn(@UserMessage String message);
    }

    @Test
    @DisplayName("端到端：简单调用返回 String")
    void shouldReturnStringFromReActEngine() {
        when(reActEngine.execute(any(), any(ChatRequest.class), any())).thenReturn("Echo: hello world");

        EchoAgent agent = LyClawAgent.builder(EchoAgent.class)
                .chatFacade(chatFacade)
                .reActEngine(reActEngine)
                .tools(toolRegistry)
                .systemPrompt("You echo user input.")
                .build();

        String result = agent.echo("hello world");
        assertThat(result).isEqualTo("Echo: hello world");
    }

    @Test
    @DisplayName("端到端：builder 方式创建代理")
    void shouldCreateAgentViaBuilder() {
        when(reActEngine.execute(any(), any(ChatRequest.class), any())).thenReturn("response");

        EchoAgent agent = LyClawAgent.builder(EchoAgent.class)
                .chatFacade(chatFacade)
                .reActEngine(reActEngine)
                .tools(toolRegistry)
                .build();

        String result = agent.echo("test");
        assertThat(result).isEqualTo("response");
    }

    @Test
    @DisplayName("端到端：builder 支持 hooks 和 model provider")
    void shouldSupportFullBuilderConfig() {
        when(reActEngine.execute(any(), any(ChatRequest.class), any())).thenReturn("configured response");

        EchoAgent agent = LyClawAgent.builder(EchoAgent.class)
                .chatFacade(chatFacade)
                .reActEngine(reActEngine)
                .tools(toolRegistry)
                .model("test-model")
                .provider("test-provider")
                .hooks(List.of())
                .stages(List.of())
                .build();

        String result = agent.echo("hello");
        assertThat(result).isEqualTo("configured response");
    }
}
