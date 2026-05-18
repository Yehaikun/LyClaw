package lyjew.com.lyclaw.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.annotation.agent.SystemMessage;
import lyjew.com.lyclaw.annotation.agent.UserMessage;
import lyjew.com.lyclaw.annotation.agent.V;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lyjew.com.lyclaw.tool.ToolRegistry;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import org.springframework.http.codec.ServerSentEvent;

/**
 * Agent 动态代理集成测试。
 *
 * 验证从接口定义 → 代理创建 → 方法调用 → ReAct 循环执行的完整链路。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Agent 动态代理集成测试")
class AgentProxyIntegrationTest {

    @Mock
    private ChatFacade chatFacade;

    @Mock
    private ReActEngine reActEngine;

    @Mock
    private ToolRegistry toolRegistry;

    @Captor
    private ArgumentCaptor<ToolExecutor> toolExecutorCaptor;

    // ========== 测试用的 Agent 接口定义 ==========

    @Agent(name = "test-assistant", description = "测试助手")
    interface TestAssistant {
        @SystemMessage("You are a helpful test assistant.")
        String chat(@UserMessage String message);
    }

    /** 没有 @SystemMessage 的接口，用于验证 builder fallback 行为 */
    @Agent(name = "no-system-msg", description = "后备助手")
    interface NoSystemMessageAssistant {
        String chat(@UserMessage String message);
    }

    @Agent(name = "streaming-assistant", description = "流式助手")
    interface StreamingAssistant {
        Flux<String> chatStream(@UserMessage String message);
    }

    @Agent(name = "translator", description = "翻译助手")
    interface Translator {
        @SystemMessage("You translate from {{source}} to {{target}}.")
        @UserMessage("Translate: {{text}}")
        String translate(@V("text") String text, @V("source") String source, @V("target") String target);
    }

    @Agent(name = "fire-forget", description = "")
    interface FireAndForget {
        void send(@UserMessage String message);
    }

    // ========== 测试 ==========

    @Nested
    @DisplayName("AgentProxyFactory")
    class AgentProxyFactoryTests {

        @Test
        @DisplayName("创建代理 — 基本对话")
        void shouldCreateProxyAndCallChat() {
            when(toolRegistry.getAllDefinitions(any(ChatRequest.class))).thenReturn(List.of());
            when(reActEngine.execute(any(), any(ChatRequest.class), any()))
                    .thenReturn("Hello! How can I help?");

            AgentProxyFactory factory = new AgentProxyFactory(chatFacade, reActEngine, toolRegistry);
            TestAssistant agent = factory.create(TestAssistant.class);

            assertThat(Proxy.isProxyClass(agent.getClass())).isTrue();
            String reply = agent.chat("Hi");
            assertThat(reply).isEqualTo("Hello! How can I help?");

            verify(reActEngine).execute(eq(chatFacade), argThat(req ->
                    req.getMessages().get(0).getContent().equals("Hi")
                            && "You are a helpful test assistant.".equals(req.getSystemPrompt())
            ), any());
        }

        @Test
        @DisplayName("创建代理 — 流式对话")
        void shouldCreateStreamingProxy() {
            when(toolRegistry.getAllDefinitions(any(ChatRequest.class))).thenReturn(List.of());
            when(reActEngine.executeStream(any(), any(ChatRequest.class), any()))
                    .thenReturn(Flux.just(
                            ServerSentEvent.<String>builder().event("message").data("Hello").build(),
                            ServerSentEvent.<String>builder().event("message").data(" world").build()
                    ));

            AgentProxyFactory factory = new AgentProxyFactory(chatFacade, reActEngine, toolRegistry);
            StreamingAssistant agent = factory.create(StreamingAssistant.class);

            Flux<String> stream = agent.chatStream("Hi");

            StepVerifier.create(stream)
                    .expectNext("Hello", " world")
                    .verifyComplete();

            verify(reActEngine).executeStream(eq(chatFacade), argThat(req ->
                    req.getMessages().get(0).getContent().equals("Hi")
            ), any());
        }

        @Test
        @DisplayName("创建代理 — 模板替换")
        void shouldSubstituteTemplateVariables() {
            when(toolRegistry.getAllDefinitions(any(ChatRequest.class))).thenReturn(List.of());
            when(reActEngine.execute(any(), any(ChatRequest.class), any()))
                    .thenReturn("Bonjour le monde");

            AgentProxyFactory factory = new AgentProxyFactory(chatFacade, reActEngine, toolRegistry);
            Translator agent = factory.create(Translator.class);

            String reply = agent.translate("hello world", "English", "French");

            verify(reActEngine).execute(eq(chatFacade), argThat(req -> {
                String userMsg = req.getMessages().get(0).getContent();
                return userMsg.equals("Translate: hello world")
                        && "You translate from English to French.".equals(req.getSystemPrompt());
            }), any());
        }

        @Test
        @DisplayName("创建代理 — void 返回类型")
        void shouldHandleVoidReturnType() {
            when(toolRegistry.getAllDefinitions(any(ChatRequest.class))).thenReturn(List.of());
            when(reActEngine.execute(any(), any(ChatRequest.class), any())).thenReturn("ok");

            AgentProxyFactory factory = new AgentProxyFactory(chatFacade, reActEngine, toolRegistry);
            FireAndForget agent = factory.create(FireAndForget.class);

            agent.send("ping"); // 不应抛异常
            verify(reActEngine).execute(any(), any(ChatRequest.class), any());
        }

        @Test
        @DisplayName("@SystemMessage 优先级高于 factory 构造参数")
        void shouldUseAnnotationSystemMessageOverFactoryDefault() {
            when(toolRegistry.getAllDefinitions(any(ChatRequest.class))).thenReturn(List.of());
            when(reActEngine.execute(any(), any(ChatRequest.class), any())).thenReturn("ok");

            // factory 传了 systemPrompt，但方法上 @SystemMessage 优先级更高
            AgentProxyFactory factory = new AgentProxyFactory(chatFacade, reActEngine, toolRegistry,
                    "factory default", null, null);
            TestAssistant agent = factory.create(TestAssistant.class);
            agent.chat("Hi");

            verify(reActEngine).execute(eq(chatFacade), argThat(req ->
                    "You are a helpful test assistant.".equals(req.getSystemPrompt())
            ), any());
        }

        @Test
        @DisplayName("依赖检查 — ChatFacade 为 null 时抛异常")
        void shouldThrowWhenChatFacadeIsNull() {
            AgentProxyFactory factory = new AgentProxyFactory(null, reActEngine, toolRegistry);
            assertThrows(IllegalStateException.class, () -> factory.create(TestAssistant.class));
        }

        @Test
        @DisplayName("依赖检查 — ReActEngine 为 null 时抛异常")
        void shouldThrowWhenReActEngineIsNull() {
            AgentProxyFactory factory = new AgentProxyFactory(chatFacade, null, toolRegistry);
            assertThrows(IllegalStateException.class, () -> factory.create(TestAssistant.class));
        }
    }

    @Nested
    @DisplayName("LyClawAgent Builder")
    class LyClawAgentBuilderTests {

        @Test
        @DisplayName("Builder systemPrompt — 方法无 @SystemMessage 时生效")
        void shouldUseBuilderSystemPromptWhenNoAnnotation() {
            when(toolRegistry.getAllDefinitions(any(ChatRequest.class))).thenReturn(List.of());
            when(reActEngine.execute(any(), any(ChatRequest.class), any()))
                    .thenReturn("Built and working!");

            // NoSystemMessageAssistant 的 chat 方法没有 @SystemMessage
            NoSystemMessageAssistant agent = LyClawAgent.builder(NoSystemMessageAssistant.class)
                    .chatFacade(chatFacade)
                    .reActEngine(reActEngine)
                    .tools(toolRegistry)
                    .systemPrompt("Custom system prompt")
                    .build();

            String reply = agent.chat("test");
            assertThat(reply).isEqualTo("Built and working!");

            verify(reActEngine).execute(eq(chatFacade), argThat(req ->
                    "Custom system prompt".equals(req.getSystemPrompt())
            ), any());
        }

        @Test
        @DisplayName("Builder — model 覆盖")
        void shouldOverrideModel() {
            when(toolRegistry.getAllDefinitions(any(ChatRequest.class))).thenReturn(List.of());
            when(reActEngine.execute(any(), any(ChatRequest.class), any())).thenReturn("ok");

            TestAssistant agent = LyClawAgent.builder(TestAssistant.class)
                    .chatFacade(chatFacade)
                    .reActEngine(reActEngine)
                    .tools(toolRegistry)
                    .model("deepseek-v4-flash")
                    .build();

            agent.chat("test");

            verify(reActEngine).execute(eq(chatFacade), argThat(req ->
                    "deepseek-v4-flash".equals(req.getModel())
            ), any());
        }
    }

    @Nested
    @DisplayName("工具集成")
    class ToolIntegrationTests {

        @Test
        @DisplayName("工具定义注入请求")
        void shouldIncludeToolDefinitionsInRequest() {
            ToolDefinition weatherDef = ToolDefinition.builder()
                    .name("get_weather")
                    .description("获取天气")
                    .build();
            ToolDefinition timeDef = ToolDefinition.builder()
                    .name("get_time")
                    .description("获取时间")
                    .build();

            when(toolRegistry.getAllDefinitions(any(ChatRequest.class))).thenReturn(List.of(weatherDef, timeDef));
            when(reActEngine.execute(any(), any(ChatRequest.class), any())).thenReturn("Sunny");

            AgentProxyFactory factory = new AgentProxyFactory(chatFacade, reActEngine, toolRegistry);
            TestAssistant agent = factory.create(TestAssistant.class);

            agent.chat("天气?");

            verify(reActEngine).execute(eq(chatFacade), argThat(req ->
                    req.getTools() != null
                            && req.getTools().size() == 2
                            && req.getTools().stream().anyMatch(t -> "get_weather".equals(t.getName()))
                            && req.getTools().stream().anyMatch(t -> "get_time".equals(t.getName()))
            ), any());
        }

        @Test
        @DisplayName("ToolExecutor 委托 ToolRegistry.execute 并返回成功结果")
        void shouldDelegateToToolRegistryExecute() throws Exception {
            ToolDefinition def = ToolDefinition.builder()
                    .name("calculator")
                    .description("计算")
                    .build();
            when(toolRegistry.getAllDefinitions(any(ChatRequest.class))).thenReturn(List.of(def));

            ToolExecutionResult successResult = ToolExecutionResult.success("42", "calculator");
            when(toolRegistry.execute(any(ToolCall.class), any())).thenReturn(successResult);

            // 捕获 ToolExecutor 以便手动调用验证
            AtomicReference<ToolExecutor> captured = new AtomicReference<>();
            when(reActEngine.execute(any(), any(ChatRequest.class), any())).thenAnswer(inv -> {
                captured.set(inv.getArgument(2));
                return "答案是 42";
            });

            AgentProxyFactory factory = new AgentProxyFactory(chatFacade, reActEngine, toolRegistry);
            TestAssistant agent = factory.create(TestAssistant.class);

            String reply = agent.chat("1+1=?");
            assertThat(reply).isEqualTo("答案是 42");

            // 验证 ToolExecutor lambda 正确委托到 ToolRegistry
            String toolResult = captured.get().execute("calculator", "call-1", "{\"expr\":\"1+1\"}");
            assertThat(toolResult).isEqualTo("42");
            verify(toolRegistry).execute(any(ToolCall.class), any());
        }

        @Test
        @DisplayName("ToolExecutor — execute 失败时回退到 executeByName")
        void shouldFallbackToExecuteByName() throws Exception {
            ToolDefinition def = ToolDefinition.builder()
                    .name("search").description("搜索").build();
            when(toolRegistry.getAllDefinitions(any(ChatRequest.class))).thenReturn(List.of(def));

            ToolExecutionResult notFound = ToolExecutionResult.failure("not found", "search");
            ToolExecutionResult success = ToolExecutionResult.success("found it", "search");
            when(toolRegistry.execute(any(ToolCall.class), any())).thenReturn(notFound);
            when(toolRegistry.executeByName(any(), any(), any(), any())).thenReturn(success);

            AtomicReference<ToolExecutor> captured = new AtomicReference<>();
            when(reActEngine.execute(any(), any(ChatRequest.class), any())).thenAnswer(inv -> {
                captured.set(inv.getArgument(2));
                return "result";
            });

            AgentProxyFactory factory = new AgentProxyFactory(chatFacade, reActEngine, toolRegistry);
            TestAssistant agent = factory.create(TestAssistant.class);

            agent.chat("search something");

            // 手动调用 ToolExecutor，验证回退到 executeByName
            String toolResult = captured.get().execute("search", "call-1", "{\"q\":\"test\"}");
            assertThat(toolResult).isEqualTo("found it");
            verify(toolRegistry).executeByName(eq("search"), eq("call-1"), eq("{\"q\":\"test\"}"), any(ChatRequest.class));
        }
    }

    @Nested
    @DisplayName("用户消息解析")
    class UserMessageResolutionTests {

        @Test
        @DisplayName("@UserMessage 在参数上")
        void shouldUseAnnotatedParameter() {
            when(toolRegistry.getAllDefinitions(any(ChatRequest.class))).thenReturn(List.of());
            when(reActEngine.execute(any(), any(ChatRequest.class), any())).thenReturn("ok");

            AgentProxyFactory factory = new AgentProxyFactory(chatFacade, reActEngine, toolRegistry);
            TestAssistant agent = factory.create(TestAssistant.class);

            agent.chat("direct message");
            verify(reActEngine).execute(eq(chatFacade), argThat(req ->
                    req.getMessages().get(0).getContent().equals("direct message")
            ), any());
        }

        @Test
        @DisplayName("fallback — 第一个 String 参数")
        void shouldFallbackToFirstStringArg() {
            when(toolRegistry.getAllDefinitions(any(ChatRequest.class))).thenReturn(List.of());
            when(reActEngine.execute(any(), any(ChatRequest.class), any())).thenReturn("ok");

            AgentProxyFactory factory = new AgentProxyFactory(chatFacade, reActEngine, toolRegistry);
            TestAssistant agent = factory.create(TestAssistant.class);

            agent.chat("fallback string");
            verify(reActEngine).execute(eq(chatFacade), argThat(req ->
                    req.getMessages().get(0).getContent().equals("fallback string")
            ), any());
        }
    }
}
