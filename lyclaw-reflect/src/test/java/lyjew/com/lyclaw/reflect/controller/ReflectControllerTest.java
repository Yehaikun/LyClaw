package lyjew.com.lyclaw.reflect.controller;

import lyjew.com.lyclaw.reflect.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 测试 ReflectController 的 reflect/evaluate/detect-errors 端点
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReflectController 端点测试")
class ReflectControllerTest {

    @Mock
    private ReflectionEngine reflectionEngine;

    @Mock
    private QualityEvaluator qualityEvaluator;

    @Mock
    private ErrorDetector errorDetector;

    @Mock
    private StrategyAdjuster strategyAdjuster;

    @InjectMocks
    private ReflectController controller;

    @Nested
    @DisplayName("/reflect 端点")
    class ReflectEndpoint {

        @Test
        void testReflectSuccess() {
            QualityAssessment quality = QualityAssessment.builder()
                    .accuracy(0.9).completeness(0.85)
                    .safety(1.0).userExperience(0.8)
                    .overall(0.89).build();
            when(reflectionEngine.assessQuality(anyString(), any(QualityCriteria.class)))
                    .thenReturn(quality);
            when(errorDetector.detectHallucination(anyString(), anyList()))
                    .thenReturn(Collections.emptyList());
            when(errorDetector.detectLogicContradiction(anyString()))
                    .thenReturn(Collections.emptyList());

            ReflectRequest request = ReflectRequest.builder()
                    .sessionId("sess-1")
                    .output("test output")
                    .expectedOutput("expected")
                    .context("test context")
                    .build();

            ReflectionReport report = controller.reflect(request);

            assertNotNull(report.getReflectionId());
            assertEquals("sess-1", report.getSessionId());
            assertEquals(0.89, report.getOverallScore());
            assertTrue(report.getErrors().isEmpty());
            assertNull(report.getSuggestion()); // 无错误且质量高于阈值
        }

        @Test
        void testReflectWithErrors() {
            QualityAssessment quality = QualityAssessment.builder()
                    .accuracy(0.5).completeness(0.5)
                    .safety(0.5).userExperience(0.5)
                    .overall(0.5).build();
            when(reflectionEngine.assessQuality(anyString(), any(QualityCriteria.class)))
                    .thenReturn(quality);

            DetectedError error = DetectedError.builder()
                    .type(DetectedError.ErrorType.HALLUCINATION)
                    .description("test error").build();
            when(errorDetector.detectHallucination(anyString(), anyList()))
                    .thenReturn(List.of(error));
            when(errorDetector.detectLogicContradiction(anyString()))
                    .thenReturn(Collections.emptyList());

            StrategyAdjustment adj = StrategyAdjustment.builder()
                    .type(StrategyAdjustment.AdjustmentType.REDUCE_TEMPERATURE)
                    .priority(0.95).build();
            when(strategyAdjuster.adjust(any(ReflectionReport.class))).thenReturn(adj);

            ReflectRequest request = ReflectRequest.builder()
                    .output("test").build();

            ReflectionReport report = controller.reflect(request);

            assertFalse(report.getErrors().isEmpty());
            assertNotNull(report.getSuggestion());
        }

        @Test
        void testReflectNullContextAndExpected() {
            QualityAssessment quality = QualityAssessment.builder()
                    .accuracy(0.9).completeness(0.9)
                    .safety(1.0).userExperience(0.9)
                    .overall(0.9).build();
            when(reflectionEngine.assessQuality(anyString(), any(QualityCriteria.class)))
                    .thenReturn(quality);
            when(errorDetector.detectHallucination(anyString(), anyList()))
                    .thenReturn(Collections.emptyList());
            when(errorDetector.detectLogicContradiction(anyString()))
                    .thenReturn(Collections.emptyList());

            ReflectRequest request = ReflectRequest.builder()
                    .output("test").build();

            ReflectionReport report = controller.reflect(request);

            assertNotNull(report);
        }
    }

    @Nested
    @DisplayName("/evaluate 端点")
    class EvaluateEndpoint {

        @Test
        void testEvaluateWithFullCriteria() {
            QualityAssessment expected = QualityAssessment.builder()
                    .accuracy(0.8).completeness(0.7)
                    .safety(0.9).userExperience(0.6)
                    .overall(0.75).build();
            when(reflectionEngine.assessQuality(anyString(), any(QualityCriteria.class)))
                    .thenReturn(expected);

            Map<String, Object> criteriaMap = Map.of(
                    "taskDescription", "test task",
                    "expectedOutput", "expected",
                    "checkAccuracy", true,
                    "checkCompleteness", true,
                    "checkSafety", false,
                    "checkUserExperience", false
            );
            Map<String, Object> body = Map.of(
                    "output", "test output",
                    "criteria", (Object) criteriaMap
            );

            QualityAssessment result = controller.evaluate(body);

            assertEquals(0.8, result.getAccuracy());
            assertEquals(0.7, result.getCompleteness());
        }

        @Test
        void testEvaluateWithoutCriteria() {
            QualityAssessment expected = QualityAssessment.builder()
                    .accuracy(1.0).completeness(1.0)
                    .safety(1.0).userExperience(1.0)
                    .overall(1.0).build();
            when(reflectionEngine.assessQuality(anyString(), any(QualityCriteria.class)))
                    .thenReturn(expected);

            Map<String, Object> body = Map.of("output", "test");

            QualityAssessment result = controller.evaluate(body);

            assertEquals(1.0, result.getOverall());
        }
    }

    @Nested
    @DisplayName("/detect-errors 端点")
    class DetectErrorsEndpoint {

        @Test
        void testDetectErrors() {
            DetectedError error = DetectedError.builder()
                    .type(DetectedError.ErrorType.LOGIC_CONTRADICTION)
                    .description("contradiction found").build();
            when(errorDetector.detectHallucination(anyString(), anyList()))
                    .thenReturn(Collections.emptyList());
            when(errorDetector.detectLogicContradiction(eq("test content")))
                    .thenReturn(List.of(error));

            Map<String, Object> body = Map.of(
                    "output", "test content",
                    "groundTruth", (Object) List.of("fact1"));

            List<DetectedError> errors = controller.detectErrors(body);

            assertEquals(1, errors.size());
            assertEquals(DetectedError.ErrorType.LOGIC_CONTRADICTION, errors.get(0).getType());
        }

        @Test
        void testDetectErrorsNullOutput() {
            Map<String, Object> body = Map.of("output", "");

            List<DetectedError> errors = controller.detectErrors(body);

            assertTrue(errors.isEmpty());
        }

        @Test
        void testDetectErrorsWithHallucination() {
            DetectedError error = DetectedError.builder()
                    .type(DetectedError.ErrorType.HALLUCINATION)
                    .description("hallucination").build();
            when(errorDetector.detectHallucination(anyString(), anyList()))
                    .thenReturn(List.of(error));
            when(errorDetector.detectLogicContradiction(anyString()))
                    .thenReturn(Collections.emptyList());

            Map<String, Object> body = Map.of("output", "content");

            List<DetectedError> errors = controller.detectErrors(body);

            assertEquals(1, errors.size());
            assertEquals(DetectedError.ErrorType.HALLUCINATION, errors.get(0).getType());
        }
    }
}
