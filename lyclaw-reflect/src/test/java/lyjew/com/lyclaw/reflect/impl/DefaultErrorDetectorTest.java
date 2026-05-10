package lyjew.com.lyclaw.reflect.impl;

import lyjew.com.lyclaw.reflect.DetectedError;
import lyjew.com.lyclaw.reflect.ToolCallRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 DefaultErrorDetector 的幻觉检测、矛盾检测、工具失败检测
 */
@DisplayName("DefaultErrorDetector 测试")
class DefaultErrorDetectorTest {

    private DefaultErrorDetector detector;

    @BeforeEach
    void setUp() {
        detector = new DefaultErrorDetector();
    }

    @Nested
    @DisplayName("幻觉检测 - UNSUPPORTED_CLAIM_MARKERS")
    class HallucinationUnsupportedClaims {

        @Test
        void testDetectsResearchShows() {
            List<DetectedError> errors = detector.detectHallucination(
                    "research shows that AI is intelligent", Collections.emptyList());
            assertFalse(errors.isEmpty());
            assertTrue(errors.get(0).getDescription().contains("research shows"));
            assertEquals(DetectedError.ErrorType.HALLUCINATION, errors.get(0).getType());
        }

        @Test
        void testDetectsItIsWellKnown() {
            List<DetectedError> errors = detector.detectHallucination(
                    "it is well known that the earth is round", Collections.emptyList());
            assertFalse(errors.isEmpty());
            assertTrue(errors.get(0).getDescription().contains("well known"));
        }

        @Test
        void testDetectsWithoutADoubt() {
            List<DetectedError> errors = detector.detectHallucination(
                    "this is without a doubt the best solution", Collections.emptyList());
            assertFalse(errors.isEmpty());
            assertTrue(errors.get(0).getDescription().contains("without a doubt"));
        }

        @Test
        void testDetectsExpertsAgree() {
            List<DetectedError> errors = detector.detectHallucination(
                    "experts agree this approach works", Collections.emptyList());
            assertFalse(errors.isEmpty());
        }

        @Test
        void testDetectsItHasBeenDemonstrated() {
            List<DetectedError> errors = detector.detectHallucination(
                    "it has been demonstrated that this works", Collections.emptyList());
            assertFalse(errors.isEmpty());
        }

        @Test
        void testOnlyFirstMarkerAppears() {
            List<DetectedError> errors = detector.detectHallucination(
                    "research shows and obviously this is true", Collections.emptyList());
            assertEquals(1, errors.size());
        }
    }

    @Nested
    @DisplayName("幻觉检测 - HIGH_CONFIDENCE_MARKERS")
    class HallucinationHighConfidence {

        @Test
        void testDetectsDefinitely() {
            List<DetectedError> errors = detector.detectHallucination(
                    "this is definitely the right answer", Collections.emptyList());
            assertFalse(errors.isEmpty());
            assertTrue(errors.get(0).getDescription().contains("definitely"));
        }

        @Test
        void testDetectsAbsolutely() {
            List<DetectedError> errors = detector.detectHallucination(
                    "this is absolutely correct", Collections.emptyList());
            assertFalse(errors.isEmpty());
        }

        @Test
        void testDetectsWithoutException() {
            List<DetectedError> errors = detector.detectHallucination(
                    "without exception this rule applies", Collections.emptyList());
            assertFalse(errors.isEmpty());
        }

        @Test
        void testDetectsGuaranteed() {
            List<DetectedError> errors = detector.detectHallucination(
                    "this is guaranteed to work", Collections.emptyList());
            assertFalse(errors.isEmpty());
        }

        @Test
        void testConfidenceRange() {
            List<DetectedError> errors = detector.detectHallucination(
                    "this is definitely true", Collections.emptyList());
            // 应至少有一个 UNS + 一个 HC，或各自独自产生
            assertFalse(errors.isEmpty());
            for (DetectedError e : errors) {
                assertTrue(e.getConfidence() >= 0.0 && e.getConfidence() <= 1.0,
                        "confidence 应在 [0,1] 范围内: " + e.getConfidence());
            }
        }
    }

    @Nested
    @DisplayName("幻觉检测 - knownFacts 矛盾")
    class HallucinationKnownFacts {

        @Test
        void testDetectsFactContradiction() {
            List<String> facts = List.of("the sky is blue");
            List<DetectedError> errors = detector.detectHallucination(
                    "the sky is not blue, it is green", facts);
            assertFalse(errors.isEmpty());
            assertEquals(DetectedError.ErrorType.HALLUCINATION, errors.get(0).getType());
        }

        @Test
        void testNoContradictionWithConsistentFacts() {
            List<String> facts = List.of("the sky is blue");
            List<DetectedError> errors = detector.detectHallucination(
                    "the sky is blue and beautiful", facts);
            assertTrue(errors.isEmpty());
        }

        @Test
        void testEmptyFactsIgnoresFactCheck() {
            List<DetectedError> errors = detector.detectHallucination(
                    "normal text without markers", Collections.emptyList());
            assertTrue(errors.isEmpty());
        }

        @Test
        void testNullFactsIgnoresFactCheck() {
            List<DetectedError> errors = detector.detectHallucination(
                    "normal text without markers", null);
            assertTrue(errors.isEmpty());
        }

        @Test
        void testBlankFactsIgnored() {
            List<DetectedError> errors = detector.detectHallucination(
                    "normal text without markers", List.of("", "  "));
            assertTrue(errors.isEmpty());
        }
    }

    @Nested
    @DisplayName("幻觉检测 - 空输入")
    class HallucinationEmptyInput {

        @Test
        void testNullOutput() {
            assertTrue(detector.detectHallucination(null, List.of()).isEmpty());
        }

        @Test
        void testBlankOutput() {
            assertTrue(detector.detectHallucination("  ", List.of()).isEmpty());
        }
    }

    @Nested
    @DisplayName("逻辑矛盾检测")
    class LogicContradiction {

        @Test
        void testDetectsIncreaseDecrease() {
            List<DetectedError> errors = detector.detectLogicContradiction(
                    "The population shows an increase now. The population shows a decrease recently.");
            assertFalse(errors.isEmpty());
            assertEquals(DetectedError.ErrorType.LOGIC_CONTRADICTION, errors.get(0).getType());
        }

        @Test
        void testDetectsAlwaysNever() {
            List<DetectedError> errors = detector.detectLogicContradiction(
                    "This always works. This never works in practice.");
            assertFalse(errors.isEmpty());
        }

        @Test
        void testDetectsTrueFalse() {
            List<DetectedError> errors = detector.detectLogicContradiction(
                    "This statement is true. This statement is false.");
            assertFalse(errors.isEmpty());
        }

        @Test
        void testDetectsRecommendAvoid() {
            List<DetectedError> errors = detector.detectLogicContradiction(
                    "I recommend this approach. I avoid this approach entirely.");
            assertFalse(errors.isEmpty());
        }

        @Test
        void testDetectsGoodBad() {
            List<DetectedError> errors = detector.detectLogicContradiction(
                    "The results are good. The results are actually quite bad.");
            assertFalse(errors.isEmpty());
        }

        @Test
        void testDetectsCorrectIncorrect() {
            List<DetectedError> errors = detector.detectLogicContradiction(
                    "The answer is correct. The answer is incorrect.");
            assertFalse(errors.isEmpty());
        }

        @Test
        void testDetectsOpenClosed() {
            List<DetectedError> errors = detector.detectLogicContradiction(
                    "The store is open. The store is closed.");
            assertFalse(errors.isEmpty());
        }

        @Test
        void testDetectsSafeDangerous() {
            List<DetectedError> errors = detector.detectLogicContradiction(
                    "This is safe for use. This is dangerous for users.");
            assertFalse(errors.isEmpty());
        }

        @Test
        void testNoContradictionWithoutPairs() {
            List<DetectedError> errors = detector.detectLogicContradiction(
                    "The weather is nice today. It should be sunny tomorrow.");
            assertTrue(errors.isEmpty());
        }

        @Test
        void testSingleSentenceNoContradiction() {
            List<DetectedError> errors = detector.detectLogicContradiction(
                    "The temperature increased and decreased rapidly.");
            assertTrue(errors.isEmpty());
        }

        @Test
        void testEmptyInputDetectLogic() {
            assertTrue(detector.detectLogicContradiction(null).isEmpty());
            assertTrue(detector.detectLogicContradiction("  ").isEmpty());
        }

        @Test
        void testConfidenceRange() {
            List<DetectedError> errors = detector.detectLogicContradiction(
                    "The value shows an increase. The value shows a decrease.");
            assertFalse(errors.isEmpty());
            assertTrue(errors.get(0).getConfidence() >= 0.0 && errors.get(0).getConfidence() <= 1.0);
        }
    }

    @Nested
    @DisplayName("工具失败模式 - 连续失败")
    class ConsecutiveFailures {

        @Test
        void testThreeConsecutiveFailuresTriggers() {
            List<ToolCallRecord> history = List.of(
                    createFailedRecord("calc"),
                    createFailedRecord("calc"),
                    createFailedRecord("calc")
            );
            List<DetectedError> errors = detector.detectToolFailurePattern(history);
            assertFalse(errors.isEmpty());
            assertTrue(errors.get(0).getDescription().contains("3 consecutive times"));
        }

        @Test
        void testTwoFailuresDoesNotTrigger() {
            List<ToolCallRecord> history = List.of(
                    createFailedRecord("calc"),
                    createFailedRecord("calc")
            );
            List<DetectedError> errors = detector.detectToolFailurePattern(history);
            assertTrue(errors.isEmpty());
        }

        @Test
        void testMixedFailuresResetOnSuccess() {
            List<ToolCallRecord> history = List.of(
                    createFailedRecord("calc"),
                    createFailedRecord("calc"),
                    createSuccessRecord("calc"),
                    createFailedRecord("calc"),
                    createFailedRecord("calc")
            );
            List<DetectedError> errors = detector.detectToolFailurePattern(history);
            assertTrue(errors.isEmpty());
        }
    }

    @Nested
    @DisplayName("工具失败模式 - 系统性失败")
    class SystemicFailure {

        @Test
        void testAllFailuresTriggers() {
            List<ToolCallRecord> history = List.of(
                    createFailedRecord("calc"),
                    createFailedRecord("cmd"),
                    createFailedRecord("search")
            );
            List<DetectedError> errors = detector.detectToolFailurePattern(history);
            assertFalse(errors.isEmpty());
            assertTrue(errors.get(0).getDescription().contains("All"));
            assertEquals(0.90, errors.get(0).getConfidence());
        }

        @Test
        void testLessThanThreeDoesNotTrigger() {
            List<ToolCallRecord> history = List.of(
                    createFailedRecord("calc"),
                    createFailedRecord("cmd")
            );
            List<DetectedError> errors = detector.detectToolFailurePattern(history);
            assertTrue(errors.isEmpty());
        }
    }

    @Nested
    @DisplayName("工具失败模式 - 超时检测")
    class TimeoutDetection {

        @Test
        void testTimeoutDetected() {
            ToolCallRecord timeoutRecord = ToolCallRecord.builder()
                    .toolName("calc").success(false).durationMs(35000).build();
            List<DetectedError> errors = detector.detectToolFailurePattern(List.of(timeoutRecord));
            assertFalse(errors.isEmpty());
            assertTrue(errors.get(0).getDescription().contains("timed out"));
        }

        @Test
        void testNotTimeoutUnderThreshold() {
            ToolCallRecord fastFail = ToolCallRecord.builder()
                    .toolName("calc").success(false).durationMs(1000).build();
            List<DetectedError> errors = detector.detectToolFailurePattern(List.of(fastFail));
            assertTrue(errors.isEmpty());
        }

        @Test
        void testSuccessDoesNotTriggerTimeout() {
            ToolCallRecord successRecord = ToolCallRecord.builder()
                    .toolName("calc").success(true).durationMs(35000).build();
            List<DetectedError> errors = detector.detectToolFailurePattern(List.of(successRecord));
            assertTrue(errors.isEmpty());
        }
    }

    @Nested
    @DisplayName("工具失败模式 - 空历史")
    class EmptyHistory {

        @Test
        void testNullHistory() {
            assertTrue(detector.detectToolFailurePattern(null).isEmpty());
        }

        @Test
        void testEmptyHistory() {
            assertTrue(detector.detectToolFailurePattern(Collections.emptyList()).isEmpty());
        }
    }

    private ToolCallRecord createFailedRecord(String toolName) {
        return ToolCallRecord.builder().toolName(toolName).success(false).durationMs(100).build();
    }

    private ToolCallRecord createSuccessRecord(String toolName) {
        return ToolCallRecord.builder().toolName(toolName).success(true).durationMs(100).build();
    }
}
