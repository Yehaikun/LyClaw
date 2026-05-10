package lyjew.com.lyclaw.reflect.impl;

import lyjew.com.lyclaw.reflect.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 DefaultStrategyAdjuster 的 9 种策略优先级
 */
@DisplayName("DefaultStrategyAdjuster 测试")
class DefaultStrategyAdjusterTest {

    private DefaultStrategyAdjuster adjuster;

    @BeforeEach
    void setUp() {
        adjuster = new DefaultStrategyAdjuster();
    }

    @Nested
    @DisplayName("工具失败 (PRIORITY=1.0) - 最高优先")
    class ToolFailure {

        @Test
        void testToolFailureTriggersAddToolCall() {
            DetectedError error = DetectedError.builder()
                    .type(DetectedError.ErrorType.TOOL_FAILURE_PATTERN)
                    .description("Tool \"calc\" failed 3 consecutive times")
                    .build();
            ReflectionReport report = ReflectionReport.builder()
                    .quality(createGoodQuality())
                    .errors(List.of(error))
                    .overallScore(0.8)
                    .build();

            StrategyAdjustment adj = adjuster.adjust(report);
            assertEquals(1.0, adj.getPriority());
            assertEquals(StrategyAdjustment.AdjustmentType.SWITCH_PLAN_STRATEGY, adj.getType());
            assertTrue(adj.getParameters().containsKey("reasoningStrategy"));
        }

        @Test
        void testSystemicFailureTriggersHumanIntervention() {
            DetectedError error = DetectedError.builder()
                    .type(DetectedError.ErrorType.TOOL_FAILURE_PATTERN)
                    .description("All 5 tool calls failed - possible systemic issue")
                    .build();
            ReflectionReport report = ReflectionReport.builder()
                    .quality(createGoodQuality())
                    .errors(List.of(error))
                    .overallScore(0.3)
                    .build();

            StrategyAdjustment adj = adjuster.adjust(report);
            assertEquals(1.0, adj.getPriority());
            assertEquals(StrategyAdjustment.AdjustmentType.ADD_TOOL_CALL, adj.getType());
            assertTrue(adj.getParameters().containsKey("checkConnectivity"));
        }
    }

    @Nested
    @DisplayName("幻觉检测 (PRIORITY=0.95)")
    class Hallucination {

        @Test
        void testSingleHallucination() {
            DetectedError error = DetectedError.builder()
                    .type(DetectedError.ErrorType.HALLUCINATION)
                    .description("Unsupported claim detected")
                    .build();
            ReflectionReport report = ReflectionReport.builder()
                    .quality(createGoodQuality())
                    .errors(List.of(error))
                    .overallScore(0.8)
                    .build();

            StrategyAdjustment adj = adjuster.adjust(report);
            assertEquals(0.95, adj.getPriority());
            assertEquals(StrategyAdjustment.AdjustmentType.REDUCE_TEMPERATURE, adj.getType());
            assertTrue(adj.getParameters().containsKey("temperature"));
            assertEquals(0.3, (double) adj.getParameters().get("temperature"));
        }

        @Test
        void testMultipleHallucinations() {
            DetectedError e1 = DetectedError.builder()
                    .type(DetectedError.ErrorType.HALLUCINATION).build();
            DetectedError e2 = DetectedError.builder()
                    .type(DetectedError.ErrorType.HALLUCINATION).build();
            ReflectionReport report = ReflectionReport.builder()
                    .quality(createGoodQuality())
                    .errors(List.of(e1, e2))
                    .overallScore(0.7)
                    .build();

            StrategyAdjustment adj = adjuster.adjust(report);
            assertEquals(0.95, adj.getPriority());
            assertTrue(adj.getReason().contains("2 hallucinations"));
        }
    }

    @Nested
    @DisplayName("安全违规 (PRIORITY=0.90)")
    class Safety {

        @Test
        void testLowSafetyTriggersHumanIntervention() {
            QualityAssessment quality = QualityAssessment.builder()
                    .accuracy(0.9).completeness(0.9)
                    .safety(0.3).userExperience(0.8)
                    .overall(0.7).build();
            ReflectionReport report = ReflectionReport.builder()
                    .quality(quality).errors(List.of()).overallScore(0.7).build();

            StrategyAdjustment adj = adjuster.adjust(report);
            assertEquals(0.90, adj.getPriority());
            assertEquals(StrategyAdjustment.AdjustmentType.TRIGGER_HUMAN_INTERVENTION, adj.getType());
            assertTrue(adj.getParameters().containsKey("requireHumanReview"));
        }

        @Test
        void testNormalSafetyDoesNotTrigger() {
            ReflectionReport report = ReflectionReport.builder()
                    .quality(createGoodQuality()).errors(List.of()).overallScore(0.9).build();

            StrategyAdjustment adj = adjuster.adjust(report);
            assertEquals(0.0, adj.getPriority());
            assertTrue(adj.getParameters().containsKey("noChange"));
        }
    }

    @Nested
    @DisplayName("逻辑矛盾 (PRIORITY=0.80)")
    class LogicError {

        @Test
        void testLogicErrorTriggersCOt() {
            DetectedError error = DetectedError.builder()
                    .type(DetectedError.ErrorType.LOGIC_CONTRADICTION)
                    .build();
            ReflectionReport report = ReflectionReport.builder()
                    .quality(createGoodQuality())
                    .errors(List.of(error))
                    .overallScore(0.7)
                    .build();

            StrategyAdjustment adj = adjuster.adjust(report);
            assertEquals(0.80, adj.getPriority());
            assertEquals(StrategyAdjustment.AdjustmentType.REWRITE_PROMPT, adj.getType());
            assertEquals("chain_of_thought", adj.getParameters().get("reasoningStrategy"));
            assertEquals(true, adj.getParameters().get("verifyIntermediateSteps"));
        }
    }

    @Nested
    @DisplayName("低准确率 (PRIORITY=0.70)")
    class LowAccuracy {

        @Test
        void testLowAccuracyTriggersToolAugmentation() {
            QualityAssessment quality = QualityAssessment.builder()
                    .accuracy(0.3).completeness(0.9)
                    .safety(0.9).userExperience(0.8)
                    .overall(0.6).build();
            ReflectionReport report = ReflectionReport.builder()
                    .quality(quality).errors(List.of()).overallScore(0.6).build();

            StrategyAdjustment adj = adjuster.adjust(report);
            assertEquals(0.70, adj.getPriority());
            assertEquals(StrategyAdjustment.AdjustmentType.ADD_TOOL_CALL, adj.getType());
        }
    }

    @Nested
    @DisplayName("低完整度 (PRIORITY=0.60)")
    class LowCompleteness {

        @Test
        void testLowCompletenessTriggersDecompose() {
            QualityAssessment quality = QualityAssessment.builder()
                    .accuracy(0.9).completeness(0.2)
                    .safety(0.9).userExperience(0.8)
                    .overall(0.55).build();
            ReflectionReport report = ReflectionReport.builder()
                    .quality(quality).errors(List.of()).overallScore(0.55).build();

            StrategyAdjustment adj = adjuster.adjust(report);
            assertEquals(0.60, adj.getPriority());
            assertEquals(StrategyAdjustment.AdjustmentType.SWITCH_PLAN_STRATEGY, adj.getType());
            assertTrue((Boolean) adj.getParameters().get("decomposeFurther"));
        }
    }

    @Nested
    @DisplayName("低UX (PRIORITY=0.50)")
    class LowUX {

        @Test
        void testLowUXTriggersStructureRewrite() {
            QualityAssessment quality = QualityAssessment.builder()
                    .accuracy(0.9).completeness(0.9)
                    .safety(0.9).userExperience(0.2)
                    .overall(0.6).build();
            ReflectionReport report = ReflectionReport.builder()
                    .quality(quality).errors(List.of()).overallScore(0.6).build();

            StrategyAdjustment adj = adjuster.adjust(report);
            assertEquals(0.50, adj.getPriority());
            assertEquals(StrategyAdjustment.AdjustmentType.REWRITE_PROMPT, adj.getType());
        }
    }

    @Nested
    @DisplayName("高错误数量 (PRIORITY=0.45)")
    class HighErrorCount {

        @Test
        void testHighErrorCountTriggersStrategyChange() {
            QualityAssessment quality = QualityAssessment.builder()
                    .accuracy(0.6).completeness(0.6)
                    .safety(0.6).userExperience(0.6)
                    .overall(0.6).build();
            ReflectionReport report = ReflectionReport.builder()
                    .quality(quality)
                    .errors(List.of(
                            DetectedError.builder().type(DetectedError.ErrorType.FORMAT_ERROR).build(),
                            DetectedError.builder().type(DetectedError.ErrorType.INCOMPLETE_OUTPUT).build(),
                            DetectedError.builder().type(DetectedError.ErrorType.FORMAT_ERROR).build(),
                            DetectedError.builder().type(DetectedError.ErrorType.INCOMPLETE_OUTPUT).build()
                    ))
                    .overallScore(0.6)
                    .build();

            StrategyAdjustment adj = adjuster.adjust(report);
            assertEquals(0.45, adj.getPriority());
            assertEquals(StrategyAdjustment.AdjustmentType.SWITCH_PLAN_STRATEGY, adj.getType());
        }
    }

    @Nested
    @DisplayName("极低总分 (PRIORITY=0.40)")
    class MajorReplan {

        @Test
        void testMajorReplan() {
            QualityAssessment quality = QualityAssessment.builder()
                    .accuracy(0.5).completeness(0.5)
                    .safety(0.5).userExperience(0.5)
                    .overall(0.5).build();
            ReflectionReport report = ReflectionReport.builder()
                    .quality(quality).errors(List.of()).overallScore(0.3).build();

            StrategyAdjustment adj = adjuster.adjust(report);
            assertEquals(0.40, adj.getPriority());
            assertEquals(StrategyAdjustment.AdjustmentType.SWITCH_PLAN_STRATEGY, adj.getType());
            assertTrue((Boolean) adj.getParameters().get("majorReplan"));
        }
    }

    @Nested
    @DisplayName("无调整 (PRIORITY=0.0)")
    class NoAdjustment {

        @Test
        void testGoodQualityNoErrors() {
            ReflectionReport report = ReflectionReport.builder()
                    .quality(createGoodQuality()).errors(List.of()).overallScore(0.9).build();

            StrategyAdjustment adj = adjuster.adjust(report);
            assertEquals(0.0, adj.getPriority());
            assertEquals(StrategyAdjustment.AdjustmentType.REWRITE_PROMPT, adj.getType());
            assertTrue(adj.getParameters().containsKey("noChange"));
        }

        @Test
        void testNullReport() {
            StrategyAdjustment adj = adjuster.adjust(null);
            assertEquals(0.0, adj.getPriority());
        }
    }

    @Nested
    @DisplayName("优先级链验证")
    class PriorityChain {

        @Test
        void testToolFailureBeforeSafety() {
            DetectedError toolError = DetectedError.builder()
                    .type(DetectedError.ErrorType.TOOL_FAILURE_PATTERN)
                    .description("Tool failed 3 times").build();
            QualityAssessment quality = QualityAssessment.builder()
                    .accuracy(0.9).completeness(0.9)
                    .safety(0.3).userExperience(0.8)
                    .overall(0.6).build();
            ReflectionReport report = ReflectionReport.builder()
                    .quality(quality).errors(List.of(toolError)).overallScore(0.6).build();

            StrategyAdjustment adj = adjuster.adjust(report);
            assertEquals(1.0, adj.getPriority());
        }

        @Test
        void testHallucinationBeforeLogicError() {
            DetectedError halError = DetectedError.builder()
                    .type(DetectedError.ErrorType.HALLUCINATION).build();
            DetectedError logicError = DetectedError.builder()
                    .type(DetectedError.ErrorType.LOGIC_CONTRADICTION).build();
            ReflectionReport report = ReflectionReport.builder()
                    .quality(createGoodQuality())
                    .errors(List.of(halError, logicError))
                    .overallScore(0.7)
                    .build();

            StrategyAdjustment adj = adjuster.adjust(report);
            assertEquals(0.95, adj.getPriority());
            assertTrue(adj.getReason().contains("Hallucination"));
        }
    }

    private QualityAssessment createGoodQuality() {
        return QualityAssessment.builder()
                .accuracy(0.9).completeness(0.9)
                .safety(0.9).userExperience(0.8)
                .overall(0.875).build();
    }
}
