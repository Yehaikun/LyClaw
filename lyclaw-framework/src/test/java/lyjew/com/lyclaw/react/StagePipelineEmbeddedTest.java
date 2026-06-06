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
import org.springframework.http.codec.ServerSentEvent;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.annotation.agent.SystemMessage;
import lyjew.com.lyclaw.annotation.agent.UserMessage;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.tool.ToolRegistry;
import reactor.core.publisher.Flux;

/**
 * Stage 管线嵌入测试：验证 Stage 在 AgentInvocationHandler 中按序执行。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Stage 管线嵌入测试")
class StagePipelineEmbeddedTest {

    @Mock private ChatFacade chatFacade;
    @Mock private ReActEngine reActEngine;
    @Mock private ToolRegistry toolRegistry;

    @Agent(name = "pipetest", description = "管线测试代理")
    interface PipeAgent {
        @SystemMessage("You are a test agent.")
        String chat(@UserMessage String message);
    }

    @BeforeEach
    void setUp() {
        lenient().when(toolRegistry.getAllDefinitions(any(ChatRequest.class))).thenReturn(List.of());
    }

    @Nested
    @DisplayName("Stage 顺序执行")
    class StageOrderingTests {

        @Test
        @DisplayName("Stage 按 order 升序逐个执行")
        void shouldExecuteStagesInOrder() {
            List<String> executionLog = new ArrayList<>();

            ReactivePipelineStage stage1 = new ReactivePipelineStage() {
                @Override public int getOrder() { return 0; }
                @Override public String getStageName() { return "stage-1"; }
                @Override
                public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
                    executionLog.add("stage-1");
                    ctx.setAttribute("finalResponse", "result-from-stage-1");
                    return Flux.just(ServerSentEvent.<String>builder().event("message").data("ok").build());
                }
            };

            ReactivePipelineStage stage2 = new ReactivePipelineStage() {
                @Override public int getOrder() { return 1; }
                @Override public String getStageName() { return "stage-2"; }
                @Override
                public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
                    executionLog.add("stage-2");
                    return Flux.just(ServerSentEvent.<String>builder().event("message").data("done").build());
                }
            };

            // 反序注册，验证内部排序
            PipeAgent agent = LyClawAgent.builder(PipeAgent.class)
                    .chatFacade(chatFacade).reActEngine(reActEngine).tools(toolRegistry)
                    .stages(List.of(stage2, stage1))
                    .build();

            String result = agent.chat("hello");

            assertThat(executionLog).containsExactly("stage-1", "stage-2");
        }

        @Test
        @DisplayName("Stage 设置 finalResponse → 返回给调用者")
        void shouldReturnFinalResponseFromStages() {
            ReactivePipelineStage stage = new ReactivePipelineStage() {
                @Override public int getOrder() { return 0; }
                @Override public String getStageName() { return "echo"; }
                @Override
                public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
                    ctx.setAttribute("finalResponse", "Hello from stage pipeline!");
                    return Flux.just(ServerSentEvent.<String>builder().event("message")
                            .data("Hello from stage pipeline!").build());
                }
            };

            PipeAgent agent = LyClawAgent.builder(PipeAgent.class)
                    .chatFacade(chatFacade).reActEngine(reActEngine).tools(toolRegistry)
                    .stages(List.of(stage))
                    .build();

            String result = agent.chat("hello");

            assertThat(result).isEqualTo("Hello from stage pipeline!");
        }
    }

    @Nested
    @DisplayName("终止上下文跳过后续 Stage")
    class TerminationTests {

        @Test
        @DisplayName("Stage 自身检查 isTerminated() 并返回空 Flux 以跳过")
        void shouldSelfCheckTermination() {
            List<String> executionLog = new ArrayList<>();

            ReactivePipelineStage guard = new ReactivePipelineStage() {
                @Override public int getOrder() { return 0; }
                @Override public String getStageName() { return "guard"; }
                @Override
                public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
                    executionLog.add("guard");
                    ctx.setTerminated(true);
                    ctx.setAttribute("finalResponse", "Blocked by guard");
                    return Flux.just(ServerSentEvent.<String>builder().event("message")
                            .data("Blocked by guard").build());
                }
            };

            ReactivePipelineStage selfChecking = new ReactivePipelineStage() {
                @Override public int getOrder() { return 1; }
                @Override public String getStageName() { return "self-checking"; }
                @Override
                public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
                    if (ctx.isTerminated()) {
                        executionLog.add("self-checking-skipped");
                        return Flux.empty();
                    }
                    executionLog.add("self-checking");
                    return Flux.just(ServerSentEvent.<String>builder().event("message")
                            .data("should not appear").build());
                }
            };

            PipeAgent agent = LyClawAgent.builder(PipeAgent.class)
                    .chatFacade(chatFacade).reActEngine(reActEngine).tools(toolRegistry)
                    .stages(List.of(guard, selfChecking))
                    .build();

            String result = agent.chat("hello");

            assertThat(result).isEqualTo("Blocked by guard");
            assertThat(executionLog).containsExactly("guard", "self-checking-skipped");
        }
    }

    @Nested
    @DisplayName("空 Stage 降级")
    class FallbackTests {

        @Test
        @DisplayName("无 Stage → 降级到 ReActEngine")
        void shouldFallbackToReActEngineWhenNoStages() {
            when(reActEngine.execute(any(), any(ChatRequest.class), any())).thenReturn("fallback response");

            PipeAgent agent = LyClawAgent.builder(PipeAgent.class)
                    .chatFacade(chatFacade).reActEngine(reActEngine).tools(toolRegistry)
                    .stages(List.of())
                    .build();

            String result = agent.chat("hello");

            assertThat(result).isEqualTo("fallback response");
        }

        @Test
        @DisplayName("无 Stage 无 ReActEngine → 返回空字符串")
        void shouldReturnEmptyWhenNoStagesAndNoEngine() {
            PipeAgent agent = LyClawAgent.builder(PipeAgent.class)
                    .chatFacade(chatFacade)
                    .reActEngine(new ReActEngine() {
                        @Override
                        public String execute(ChatFacade chat, ChatRequest req, ToolExecutor toolExecutor, java.util.function.Consumer<lyjew.com.lyclaw.mesh.AgentExecutionEvent> cb) {
                            return "direct engine";
                        }

                        @Override
                        public Flux<ServerSentEvent<String>> executeStream(ChatFacade chat, ChatRequest req, ToolExecutor toolExecutor) {
                            return Flux.empty();
                        }
                    })
                    .tools(toolRegistry)
                    .stages(List.of())
                    .build();

            String result = agent.chat("hello");

            assertThat(result).isEqualTo("direct engine");
        }
    }

    @Nested
    @DisplayName("多 Stage 数据传递")
    class DataFlowTests {

        @Test
        @DisplayName("Stage 通过 AgentContext 传递数据")
        void shouldPassDataViaAgentContext() {
            ReactivePipelineStage producer = new ReactivePipelineStage() {
                @Override public int getOrder() { return 0; }
                @Override public String getStageName() { return "producer"; }
                @Override
                public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
                    ctx.setAttribute("shared_key", "shared_value");
                    return Flux.empty();
                }
            };

            ReactivePipelineStage consumer = new ReactivePipelineStage() {
                @Override public int getOrder() { return 1; }
                @Override public String getStageName() { return "consumer"; }
                @Override
                public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
                    String shared = ctx.getAttribute("shared_key");
                    ctx.setAttribute("finalResponse", "consumed: " + shared);
                    return Flux.just(ServerSentEvent.<String>builder().event("message")
                            .data("consumed: " + shared).build());
                }
            };

            PipeAgent agent = LyClawAgent.builder(PipeAgent.class)
                    .chatFacade(chatFacade).reActEngine(reActEngine).tools(toolRegistry)
                    .stages(List.of(producer, consumer))
                    .build();

            String result = agent.chat("hello");

            assertThat(result).isEqualTo("consumed: shared_value");
        }
    }
}
