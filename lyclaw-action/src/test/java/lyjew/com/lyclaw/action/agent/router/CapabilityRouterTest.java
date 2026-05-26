package lyjew.com.lyclaw.action.agent.router;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import lyjew.com.lyclaw.action.agent.DefaultAgentRegistry;
import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.AgentHandle.HealthStatus;
import lyjew.com.lyclaw.agent.AgentState;
import lyjew.com.lyclaw.agent.AgentTask;
import lyjew.com.lyclaw.agent.RoutingContext;
import lyjew.com.lyclaw.agent.RoutingDecision;

@DisplayName("CapabilityRouter")
class CapabilityRouterTest {

    private DefaultAgentRegistry registry;
    private CapabilityRouter router;

    @BeforeEach
    void setUp() {
        registry = new DefaultAgentRegistry();
        router = new CapabilityRouter(registry);

        registry.register(AgentHandle.builder()
                .agentId("reviewer").name("Code Reviewer").state(AgentState.IDLE)
                .health(HealthStatus.UP)
                .capabilities(List.of("code_review", "java", "security_audit", "quality_assurance"))
                .historicalAccuracy(0.95).build());

        registry.register(AgentHandle.builder()
                .agentId("writer").name("Technical Writer").state(AgentState.IDLE)
                .health(HealthStatus.UP)
                .capabilities(List.of("documentation", "technical_writing", "markdown"))
                .historicalAccuracy(0.85).build());

        registry.register(AgentHandle.builder()
                .agentId("researcher").name("Researcher").state(AgentState.RUNNING)
                .health(HealthStatus.UP)
                .capabilities(List.of("web_search", "research"))
                .historicalAccuracy(0.9).build());
    }

    @Nested
    @DisplayName("能力匹配")
    class CapabilityMatching {

        @Test
        @DisplayName("review 任务路由到 reviewer")
        void reviewRoutesToReviewer() {
            AgentTask task = new AgentTask("1", "review", "file.java",
                    "审查这段 Java 代码", null);
            RoutingDecision d = router.route(task, RoutingContext.builder().build());
            assertThat(d.getTargetAgentId()).isEqualTo("reviewer");
            assertThat(d.isRoutable()).isTrue();
        }

        @Test
        @DisplayName("document 任务路由到 writer")
        void documentRoutesToWriter() {
            AgentTask task = new AgentTask("2", "document", "",
                    "写 API 文档", null);
            RoutingDecision d = router.route(task, RoutingContext.builder().build());
            assertThat(d.getTargetAgentId()).isEqualTo("writer");
        }

        @Test
        @DisplayName("无法推断能力时返回 fallback")
        void unknownTaskReturnsFallback() {
            AgentTask task = new AgentTask("3", "unknown_type", "",
                    "一些无关内容", null);
            RoutingDecision d = router.route(task, RoutingContext.builder().build());
            assertThat(d.isRoutable()).isFalse();
        }

        @Test
        @DisplayName("无可用 Agent 时返回 fallback")
        void noAvailableAgentReturnsFallback() {
            registry.findByState(AgentState.IDLE).forEach(h -> registry.unregister(h.getAgentId()));
            AgentTask task = new AgentTask("4", "review", "", "审查代码", null);
            RoutingDecision d = router.route(task, RoutingContext.builder().build());
            assertThat(d.isRoutable()).isFalse();
        }
    }
}
