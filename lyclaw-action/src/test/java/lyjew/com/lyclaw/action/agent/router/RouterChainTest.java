package lyjew.com.lyclaw.action.agent.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import lyjew.com.lyclaw.agent.AgentRouter;
import lyjew.com.lyclaw.agent.AgentTask;
import lyjew.com.lyclaw.agent.RoutingContext;
import lyjew.com.lyclaw.agent.RoutingDecision;

@ExtendWith(MockitoExtension.class)
@DisplayName("RouterChain")
class RouterChainTest {

    @Mock
    private AgentRouter highPriorityRouter;

    @Mock
    private AgentRouter lowPriorityRouter;

    @Mock
    private AgentTask task;

    @Mock
    private RoutingContext context;

    @BeforeEach
    void setUp() {
        lenient().when(highPriorityRouter.getOrder()).thenReturn(100);
        lenient().when(highPriorityRouter.routerName()).thenReturn("high");
        lenient().when(lowPriorityRouter.getOrder()).thenReturn(0);
        lenient().when(lowPriorityRouter.routerName()).thenReturn("low");
    }

    @Nested
    @DisplayName("链式执行")
    class ChainExecution {

        @Test
        @DisplayName("DEFINITE 决策短路返回")
        void definiteShortCircuits() {
            when(highPriorityRouter.route(task, context))
                    .thenReturn(RoutingDecision.definite("agent-x", "精确匹配", "high"));
            chain = new RouterChain(List.of(highPriorityRouter, lowPriorityRouter));
            RoutingDecision result = chain.route(task, context);
            assertThat(result.getTargetAgentId()).isEqualTo("agent-x");
        }

        @Test
        @DisplayName("跳过不可路由的决策，尝试下一个路由器")
        void skipsUnroutable() {
            when(highPriorityRouter.route(task, context))
                    .thenReturn(RoutingDecision.fallback("无匹配"));
            when(lowPriorityRouter.route(task, context))
                    .thenReturn(RoutingDecision.high("agent-y", 0.8, "匹配", "low"));
            chain = new RouterChain(List.of(highPriorityRouter, lowPriorityRouter));
            RoutingDecision result = chain.route(task, context);
            assertThat(result.getTargetAgentId()).isEqualTo("agent-y");
        }

        @Test
        @DisplayName("所有路由器都返回 fallback 时返回 fallback")
        void allRoutersFallback() {
            when(highPriorityRouter.route(task, context))
                    .thenReturn(RoutingDecision.fallback("无匹配"));
            when(lowPriorityRouter.route(task, context))
                    .thenReturn(RoutingDecision.fallback("也无匹配"));
            chain = new RouterChain(List.of(highPriorityRouter, lowPriorityRouter));
            RoutingDecision result = chain.route(task, context);
            assertThat(result.isRoutable()).isFalse();
            assertThat(result.getConfidence()).isEqualTo(RoutingDecision.Confidence.FALLBACK);
        }

        @Test
        @DisplayName("路由器抛出异常时跳过，继续下一个")
        void routerExceptionSkipped() {
            when(highPriorityRouter.route(task, context))
                    .thenThrow(new RuntimeException("router crash"));
            when(lowPriorityRouter.route(task, context))
                    .thenReturn(RoutingDecision.high("agent-z", 0.7, "fallback", "low"));
            chain = new RouterChain(List.of(highPriorityRouter, lowPriorityRouter));
            RoutingDecision result = chain.route(task, context);
            assertThat(result.getTargetAgentId()).isEqualTo("agent-z");
        }

        @Test
        @DisplayName("按 order 降序执行（高优先级先执行）")
        void orderedByPriority() {
            when(highPriorityRouter.route(task, context))
                    .thenReturn(RoutingDecision.high("agent-high", 0.9, "high match", "high"));
            when(lowPriorityRouter.route(task, context))
                    .thenReturn(RoutingDecision.high("agent-low", 0.85, "low match", "low"));
            chain = new RouterChain(List.of(lowPriorityRouter, highPriorityRouter));
            RoutingDecision result = chain.route(task, context);
            assertThat(result.getTargetAgentId()).isEqualTo("agent-high");
        }
    }

    @Nested
    @DisplayName("链构造")
    class ChainConstruction {

        private RouterChain emptyChain;

        @Test
        @DisplayName("空路由器列表不抛异常")
        void emptyRouterList() {
            emptyChain = new RouterChain(List.of());
            RoutingDecision result = emptyChain.route(task, context);
            assertThat(result.isRoutable()).isFalse();
        }

        @Test
        @DisplayName("构造时按 order 降序排序")
        void sortedOnConstruction() {
            chain = new RouterChain(List.of(lowPriorityRouter, highPriorityRouter));
            assertThat(chain.getRouters().get(0).routerName()).isEqualTo("high");
            assertThat(chain.getRouters().get(1).routerName()).isEqualTo("low");
        }
    }

    private RouterChain chain;
}
