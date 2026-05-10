package lyjew.com.lyclaw.orchestration.impl;

import lyjew.com.lyclaw.agent.scaling.AgentPoolSnapshot;
import lyjew.com.lyclaw.agent.scaling.ScalingDecision;
import lyjew.com.lyclaw.agent.scaling.ScalingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AutoScalerImplTest {

    private AutoScalerImpl autoScaler;

    @BeforeEach
    void setUp() {
        autoScaler = new AutoScalerImpl();
        autoScaler.setCurrentAgentCount(3);
    }

    // ========== evaluate ==========

    @Nested
    @DisplayName("evaluate")
    class Evaluate {

        @Test
        @DisplayName("Null snapshot returns NONE")
        void nullSnapshotReturnsNone() {
            ScalingDecision dec = autoScaler.evaluate(null);
            assertThat(dec.getAction()).isEqualTo(ScalingDecision.Action.NONE);
            assertThat(dec.getDelta()).isEqualTo(0);
            assertThat(dec.getReason()).contains("Null snapshot");
        }

        @Test
        @DisplayName("Queue overflow triggers SCALE_UP")
        void queueOverflowScalesUp() {
            // maxQueueDepth=5, queuedTasks=10 -> overflow of 5
            AgentPoolSnapshot snapshot = AgentPoolSnapshot.builder()
                    .totalAgents(3).idleAgents(2).runningAgents(1)
                    .queuedTasks(10).maxQueueDepth(5).targetIdleRatio(0.25)
                    .build();

            ScalingDecision dec = autoScaler.evaluate(snapshot);

            assertThat(dec.getAction()).isEqualTo(ScalingDecision.Action.SCALE_UP);
            assertThat(dec.getDelta()).isGreaterThanOrEqualTo(1)
                    .isLessThanOrEqualTo(2); // SCALE_UP_STEP
            assertThat(dec.getReason()).contains("Queue overflow");
        }

        @Test
        @DisplayName("Idle below TARGET_IDLE=3 triggers SCALE_UP")
        void lowIdleScalesUp() {
            // idle=1, no queue overflow
            AgentPoolSnapshot snapshot = AgentPoolSnapshot.builder()
                    .totalAgents(5).idleAgents(1).runningAgents(4)
                    .queuedTasks(0).maxQueueDepth(10).targetIdleRatio(0.25)
                    .build();

            ScalingDecision dec = autoScaler.evaluate(snapshot);

            assertThat(dec.getAction()).isEqualTo(ScalingDecision.Action.SCALE_UP);
            assertThat(dec.getReason()).contains("Idle agents below target");
        }

        @Test
        @DisplayName("Idle above high watermark (TARGET_IDLE*2=6) triggers SCALE_DOWN")
        void highIdleScalesDown() {
            // idle=8 > 6 (TARGET_IDLE*2)
            AgentPoolSnapshot snapshot = AgentPoolSnapshot.builder()
                    .totalAgents(10).idleAgents(8).runningAgents(2)
                    .queuedTasks(0).maxQueueDepth(10).targetIdleRatio(0.25)
                    .build();

            ScalingDecision dec = autoScaler.evaluate(snapshot);

            assertThat(dec.getAction()).isEqualTo(ScalingDecision.Action.SCALE_DOWN);
            assertThat(dec.getDelta()).isGreaterThanOrEqualTo(1)
                    .isLessThanOrEqualTo(1); // SCALE_DOWN_STEP
            assertThat(dec.getReason()).contains("Idle agents above high watermark");
        }

        @Test
        @DisplayName("Idle within range [3,6] returns NONE")
        void idleInRangeReturnsNone() {
            AgentPoolSnapshot snapshot = AgentPoolSnapshot.builder()
                    .totalAgents(8).idleAgents(4).runningAgents(4)
                    .queuedTasks(0).maxQueueDepth(10).targetIdleRatio(0.25)
                    .build();

            ScalingDecision dec = autoScaler.evaluate(snapshot);

            assertThat(dec.getAction()).isEqualTo(ScalingDecision.Action.NONE);
            assertThat(dec.getDelta()).isEqualTo(0);
            assertThat(dec.getReason()).contains("acceptable range");
        }

        @Test
        @DisplayName("maxQueueDepth=0, queue overflow condition not triggered")
        void zeroMaxQueueNoOverflow() {
            AgentPoolSnapshot snapshot = AgentPoolSnapshot.builder()
                    .totalAgents(5).idleAgents(1).runningAgents(4)
                    .queuedTasks(5).maxQueueDepth(0).targetIdleRatio(0.25)
                    .build();

            ScalingDecision dec = autoScaler.evaluate(snapshot);

            // maxQueueDepth=0 -> overflow condition is false, falls through to low idle check
            assertThat(dec.getAction()).isEqualTo(ScalingDecision.Action.SCALE_UP);
            assertThat(dec.getReason()).contains("Idle agents below target");
        }
    }

    // ========== apply ==========

    @Nested
    @DisplayName("apply")
    class Apply {

        @Test
        @DisplayName("Null decision -> NONE, no change")
        void nullDecisionNoChange() throws Exception {
            autoScaler.setCurrentAgentCount(5);
            CompletableFuture<ScalingResult> future = autoScaler.apply(null);
            ScalingResult result = future.get(5, TimeUnit.SECONDS);

            assertThat(result.getPreviousCount()).isEqualTo(5);
            assertThat(result.getNewCount()).isEqualTo(5);
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("SCALE_UP increases agent count")
        void scaleUpIncreasesCount() throws Exception {
            autoScaler.setCurrentAgentCount(3);
            ScalingDecision dec = ScalingDecision.builder()
                    .action(ScalingDecision.Action.SCALE_UP).delta(2).reason("test up")
                    .build();

            CompletableFuture<ScalingResult> future = autoScaler.apply(dec);
            ScalingResult result = future.get(5, TimeUnit.SECONDS);

            assertThat(result.getPreviousCount()).isEqualTo(3);
            assertThat(result.getNewCount()).isEqualTo(5);
            assertThat(result.isSuccess()).isTrue();
            assertThat(autoScaler.getCurrentAgentCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("SCALE_DOWN decreases but never below 1")
        void scaleDownMinimumOne() throws Exception {
            autoScaler.setCurrentAgentCount(2);
            ScalingDecision dec = ScalingDecision.builder()
                    .action(ScalingDecision.Action.SCALE_DOWN).delta(3).reason("test down")
                    .build();

            CompletableFuture<ScalingResult> future = autoScaler.apply(dec);
            ScalingResult result = future.get(5, TimeUnit.SECONDS);

            assertThat(result.getNewCount()).isEqualTo(1); // Math.max(1, 2-3)
            assertThat(autoScaler.getCurrentAgentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("SCALE_DOWN from 1 stays at 1")
        void scaleDownAtMinimumStaysAt1() throws Exception {
            autoScaler.setCurrentAgentCount(1);
            ScalingDecision dec = ScalingDecision.builder()
                    .action(ScalingDecision.Action.SCALE_DOWN).delta(2).reason("test down")
                    .build();

            CompletableFuture<ScalingResult> future = autoScaler.apply(dec);
            ScalingResult result = future.get(5, TimeUnit.SECONDS);

            assertThat(result.getNewCount()).isEqualTo(1);
            assertThat(result.getPreviousCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("NONE action keeps count unchanged")
        void noneActionKeepsCount() throws Exception {
            autoScaler.setCurrentAgentCount(7);
            ScalingDecision dec = ScalingDecision.builder()
                    .action(ScalingDecision.Action.NONE).delta(0).reason("stable")
                    .build();

            CompletableFuture<ScalingResult> future = autoScaler.apply(dec);
            ScalingResult result = future.get(5, TimeUnit.SECONDS);

            assertThat(result.getNewCount()).isEqualTo(7);
        }
    }

    // ========== manual set ==========

    @Test
    @DisplayName("setCurrentAgentCount clamps to minimum 1")
    void setCountClampsToMinimum() {
        autoScaler.setCurrentAgentCount(-5);
        assertThat(autoScaler.getCurrentAgentCount()).isEqualTo(1);

        autoScaler.setCurrentAgentCount(0);
        assertThat(autoScaler.getCurrentAgentCount()).isEqualTo(1);

        autoScaler.setCurrentAgentCount(10);
        assertThat(autoScaler.getCurrentAgentCount()).isEqualTo(10);
    }
}
