package lyjew.com.lyclaw.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RoutingDecision")
class RoutingDecisionTest {

    @Nested
    @DisplayName("工厂方法")
    class FactoryMethods {

        @Test
        @DisplayName("definite 创建确定路由决策")
        void definite() {
            RoutingDecision d = RoutingDecision.definite("agent-x", "精确匹配", "test-router");
            assertThat(d.getTargetAgentId()).isEqualTo("agent-x");
            assertThat(d.getScore()).isEqualTo(1.0);
            assertThat(d.getConfidence()).isEqualTo(RoutingDecision.Confidence.DEFINITE);
            assertThat(d.isRoutable()).isTrue();
            assertThat(d.isConfident()).isTrue();
            assertThat(d.isRequiresConfirmation()).isFalse();
        }

        @Test
        @DisplayName("high 创建高置信度路由决策")
        void high() {
            RoutingDecision d = RoutingDecision.high("agent-y", 0.85, "高匹配", "test-router");
            assertThat(d.getTargetAgentId()).isEqualTo("agent-y");
            assertThat(d.getScore()).isEqualTo(0.85);
            assertThat(d.getConfidence()).isEqualTo(RoutingDecision.Confidence.HIGH);
            assertThat(d.isRoutable()).isTrue();
            assertThat(d.isConfident()).isTrue();
        }

        @Test
        @DisplayName("medium 创建中置信度路由决策")
        void medium() {
            RoutingDecision d = RoutingDecision.medium("agent-z", 0.5, "部分匹配", "test-router");
            assertThat(d.getConfidence()).isEqualTo(RoutingDecision.Confidence.MEDIUM);
            assertThat(d.isConfident()).isFalse();
            assertThat(d.isRequiresConfirmation()).isTrue();
        }

        @Test
        @DisplayName("low 创建低置信度路由决策")
        void low() {
            RoutingDecision d = RoutingDecision.low("agent-w", "弱匹配", "test-router");
            assertThat(d.getConfidence()).isEqualTo(RoutingDecision.Confidence.LOW);
            assertThat(d.isRoutable()).isFalse();
        }

        @Test
        @DisplayName("fallback 创建兜底决策，不可路由")
        void fallback() {
            RoutingDecision d = RoutingDecision.fallback("无可用 Agent");
            assertThat(d.getTargetAgentId()).isNull();
            assertThat(d.isRoutable()).isFalse();
            assertThat(d.getConfidence()).isEqualTo(RoutingDecision.Confidence.FALLBACK);
        }
    }

    @Nested
    @DisplayName("路由条件判断")
    class RoutingConditions {

        @Test
        @DisplayName("score > 0.3 且 targetAgentId 非空时可路由")
        void routableWhenScoreAboveThreshold() {
            RoutingDecision d = RoutingDecision.medium("agent", 0.4, "reason", "router");
            assertThat(d.isRoutable()).isTrue();
        }

        @Test
        @DisplayName("score <= 0.3 时不可路由")
        void notRoutableWhenScoreLow() {
            RoutingDecision d = RoutingDecision.low("agent", "low score", "router");
            assertThat(d.isRoutable()).isFalse();
        }

        @Test
        @DisplayName("DEFINITE 和 HIGH 为 confident")
        void confidentLevels() {
            assertThat(RoutingDecision.definite("a", "r", "r").isConfident()).isTrue();
            assertThat(RoutingDecision.high("a", 0.8, "r", "r").isConfident()).isTrue();
            assertThat(RoutingDecision.medium("a", 0.5, "r", "r").isConfident()).isFalse();
        }
    }

    @Nested
    @DisplayName("路由信息")
    class RoutingInfo {

        @Test
        @DisplayName("toString 包含关键信息")
        void toStringContainsKeyInfo() {
            RoutingDecision d = RoutingDecision.definite("agent-x", "exact match", "capability");
            String str = d.toString();
            assertThat(str).contains("agent-x");
            assertThat(str).contains("DEFINITE");
            assertThat(str).contains("capability");
        }

        @Test
        @DisplayName("记录使用的路由器名称")
        void recordsRouterName() {
            RoutingDecision d = RoutingDecision.definite("a", "r", "myRouter");
            assertThat(d.getRouterUsed()).isEqualTo("myRouter");
        }
    }
}
