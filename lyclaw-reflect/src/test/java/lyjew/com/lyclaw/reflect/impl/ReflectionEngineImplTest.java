package lyjew.com.lyclaw.reflect.impl;

import lyjew.com.lyclaw.action.ActionResult;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.reflect.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 测试 ReflectionEngineImpl 的完整反思流程
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReflectionEngineImpl 完整反思流程测试")
class ReflectionEngineImplTest {

    @Mock
    private QualityEvaluator qualityEvaluator;

    @Mock
    private ErrorDetector errorDetector;

    @Mock
    private StrategyAdjuster strategyAdjuster;

    private ReflectionEngineImpl engine;

    @BeforeEach
    void setUp() {
        engine = new ReflectionEngineImpl(qualityEvaluator, errorDetector, strategyAdjuster);
    }

    @Nested
    @DisplayName("assessQuality 方法")
    class AssessQuality {

        @Test
        void testAssessQualityWithAllChecks() {
            lenient().when(qualityEvaluator.evaluateAccuracy(anyString(), anyString())).thenReturn(0.9);
            lenient().when(qualityEvaluator.evaluateCompleteness(anyString(), anyString())).thenReturn(0.85);
            lenient().when(qualityEvaluator.evaluateSafety(anyString())).thenReturn(1.0);
            lenient().when(qualityEvaluator.evaluateUserExperience(anyString())).thenReturn(0.8);

            QualityCriteria criteria = QualityCriteria.builder()
                    .taskDescription("test task")
                    .expectedOutput("expected")
                    .checkAccuracy(true)
                    .checkCompleteness(true)
                    .checkSafety(true)
                    .checkUserExperience(true)
                    .build();

            QualityAssessment result = engine.assessQuality("output text", criteria);

            assertEquals(0.9, result.getAccuracy());
            assertEquals(0.85, result.getCompleteness());
            assertEquals(1.0, result.getSafety());
            assertEquals(0.8, result.getUserExperience());
            // 0.9*0.35 + 0.85*0.30 + 1.0*0.20 + 0.8*0.15 = 0.315 + 0.255 + 0.20 + 0.12 = 0.89
            assertTrue(result.getOverall() > 0.8);
        }

        @Test
        void testAssessQualityWithDisabledChecks() {
            QualityCriteria criteria = QualityCriteria.builder()
                    .taskDescription("")
                    .expectedOutput("")
                    .checkAccuracy(false)
                    .checkCompleteness(false)
                    .checkSafety(false)
                    .checkUserExperience(false)
                    .build();

            QualityAssessment result = engine.assessQuality("output", criteria);

            assertEquals(1.0, result.getAccuracy());
            assertEquals(1.0, result.getCompleteness());
            assertEquals(1.0, result.getSafety());
            assertEquals(1.0, result.getUserExperience());
            assertEquals(1.0, result.getOverall(), 0.001);
        }

        @Test
        void testAssessQualityNullOutput() {
            QualityCriteria criteria = QualityCriteria.builder().build();
            QualityAssessment result = engine.assessQuality(null, criteria);

            assertEquals(0.0, result.getAccuracy());
            assertEquals(0.0, result.getCompleteness());
            assertEquals(1.0, result.getSafety());
            assertEquals(0.0, result.getUserExperience());
            assertEquals(0.0, result.getOverall());
        }

        @Test
        void testAssessQualityBlankOutput() {
            QualityCriteria criteria = QualityCriteria.builder().build();
            QualityAssessment result = engine.assessQuality("  ", criteria);

            assertEquals(0.0, result.getAccuracy());
            assertEquals(0.0, result.getCompleteness());
            assertEquals(1.0, result.getSafety());
            assertEquals(0.0, result.getUserExperience());
        }
    }

    @Nested
    @DisplayName("detectErrors 方法")
    class DetectErrors {

        @Test
        void testDetectErrorsDelegates() {
            DetectedError error = DetectedError.builder()
                    .type(DetectedError.ErrorType.HALLUCINATION)
                    .description("test").build();
            lenient().when(errorDetector.detectHallucination(anyString(), anyList()))
                    .thenReturn(List.of(error));

            List<DetectedError> errors = engine.detectErrors("output", List.of("fact1"));

            assertEquals(1, errors.size());
            assertEquals(DetectedError.ErrorType.HALLUCINATION, errors.get(0).getType());
        }

        @Test
        void testDetectErrorsNullOutput() {
            List<DetectedError> errors = engine.detectErrors(null, List.of());
            assertTrue(errors.isEmpty());
        }

        @Test
        void testDetectErrorsBlankOutput() {
            List<DetectedError> errors = engine.detectErrors("  ", List.of());
            assertTrue(errors.isEmpty());
        }
    }

    @Nested
    @DisplayName("suggestAdjustment 方法")
    class SuggestAdjustment {

        @Test
        void testSuggestAdjustmentDelegates() {
            StrategyAdjustment expected = StrategyAdjustment.builder()
                    .type(StrategyAdjustment.AdjustmentType.REWRITE_PROMPT)
                    .priority(0.5).build();
            lenient().when(strategyAdjuster.adjust(any(ReflectionReport.class))).thenReturn(expected);

            ReflectionReport report = ReflectionReport.builder().build();
            StrategyAdjustment result = engine.suggestAdjustment(report);

            assertEquals(0.5, result.getPriority());
        }

        @Test
        void testSuggestAdjustmentNullReport() {
            assertNull(engine.suggestAdjustment(null));
        }
    }

    @Nested
    @DisplayName("reflect 完整流程")
    class ReflectFullFlow {

        @Test
        void testReflectWithQualityAboveThreshold() {
            lenient().when(qualityEvaluator.evaluateAccuracy(anyString(), anyString())).thenReturn(0.9);
            lenient().when(qualityEvaluator.evaluateCompleteness(anyString(), anyString())).thenReturn(0.85);
            lenient().when(qualityEvaluator.evaluateSafety(anyString())).thenReturn(1.0);
            lenient().when(qualityEvaluator.evaluateUserExperience(anyString())).thenReturn(0.8);
            lenient().when(errorDetector.detectHallucination(anyString(), anyList()))
                    .thenReturn(Collections.emptyList());
            lenient().when(errorDetector.detectLogicContradiction(anyString()))
                    .thenReturn(Collections.emptyList());

            Session session = new Session();
            session.setSessionId("session-123");
            ChatContext context = mock(ChatContext.class);
            when(context.getSession()).thenReturn(session);
            when(context.getAttribute("taskDescription")).thenReturn("test task");
            lenient().when(context.getMessages()).thenReturn(null);

            ActionResult result = ActionResult.builder()
                    .nodeId("n1").success(true).output("good output").build();

            ReflectionReport report = engine.reflect(context, result);

            assertNotNull(report.getReflectionId());
            assertEquals("session-123", report.getSessionId());
            assertNotNull(report.getQuality());
            assertTrue(report.getOverallScore() > 0.6);
            assertTrue(report.getErrors().isEmpty());
            assertNull(report.getSuggestion());
        }

        @Test
        void testReflectWithQualityBelowThreshold() {
            lenient().when(qualityEvaluator.evaluateAccuracy(anyString(), anyString())).thenReturn(0.3);
            lenient().when(qualityEvaluator.evaluateCompleteness(anyString(), anyString())).thenReturn(0.3);
            lenient().when(qualityEvaluator.evaluateSafety(anyString())).thenReturn(0.5);
            lenient().when(qualityEvaluator.evaluateUserExperience(anyString())).thenReturn(0.3);
            lenient().when(errorDetector.detectHallucination(anyString(), anyList()))
                    .thenReturn(Collections.emptyList());
            lenient().when(errorDetector.detectLogicContradiction(anyString()))
                    .thenReturn(Collections.emptyList());
            StrategyAdjustment expected = StrategyAdjustment.builder()
                    .type(StrategyAdjustment.AdjustmentType.SWITCH_PLAN_STRATEGY)
                    .priority(0.4).build();
            lenient().when(strategyAdjuster.adjust(any(ReflectionReport.class))).thenReturn(expected);

            Session session = new Session();
            session.setSessionId("session-456");
            ChatContext context = mock(ChatContext.class);
            when(context.getSession()).thenReturn(session);
            when(context.getAttribute("taskDescription")).thenReturn("hard task");
            lenient().when(context.getMessages()).thenReturn(null);

            ActionResult result = ActionResult.builder()
                    .nodeId("n1").success(false)
                    .output("bad output")
                    .metadata(Map.of("expectedOutput", "good output"))
                    .build();

            ReflectionReport report = engine.reflect(context, result);

            assertNotNull(report.getSuggestion());
            assertTrue(report.getOverallScore() < 0.6);
        }

        @Test
        void testReflectWithHasErrorsButAboveThreshold() {
            lenient().when(qualityEvaluator.evaluateAccuracy(anyString(), anyString())).thenReturn(0.9);
            lenient().when(qualityEvaluator.evaluateCompleteness(anyString(), anyString())).thenReturn(0.85);
            lenient().when(qualityEvaluator.evaluateSafety(anyString())).thenReturn(1.0);
            lenient().when(qualityEvaluator.evaluateUserExperience(anyString())).thenReturn(0.8);
            DetectedError error = DetectedError.builder()
                    .type(DetectedError.ErrorType.HALLUCINATION).build();
            lenient().when(errorDetector.detectHallucination(anyString(), anyList()))
                    .thenReturn(List.of(error));
            lenient().when(errorDetector.detectLogicContradiction(anyString()))
                    .thenReturn(Collections.emptyList());
            StrategyAdjustment expected = StrategyAdjustment.builder()
                    .type(StrategyAdjustment.AdjustmentType.REDUCE_TEMPERATURE)
                    .priority(0.95).build();
            lenient().when(strategyAdjuster.adjust(any(ReflectionReport.class))).thenReturn(expected);

            Session session = new Session();
            session.setSessionId("sess");
            ChatContext context = mock(ChatContext.class);
            when(context.getSession()).thenReturn(session);
            when(context.getAttribute("taskDescription")).thenReturn("task");
            lenient().when(context.getMessages()).thenReturn(null);

            ActionResult result = ActionResult.builder()
                    .nodeId("n1").success(true).output("output").build();

            ReflectionReport report = engine.reflect(context, result);

            assertNotNull(report.getSuggestion());
        }

        @Test
        void testReflectNullContext() {
            lenient().when(qualityEvaluator.evaluateAccuracy(anyString(), anyString())).thenReturn(0.5);
            lenient().when(qualityEvaluator.evaluateCompleteness(anyString(), anyString())).thenReturn(0.5);
            lenient().when(qualityEvaluator.evaluateSafety(anyString())).thenReturn(1.0);
            lenient().when(qualityEvaluator.evaluateUserExperience(anyString())).thenReturn(0.5);
            lenient().when(errorDetector.detectHallucination(anyString(), anyList()))
                    .thenReturn(Collections.emptyList());
            lenient().when(errorDetector.detectLogicContradiction(anyString()))
                    .thenReturn(Collections.emptyList());

            ActionResult result = ActionResult.builder()
                    .nodeId("n1").success(true).output("output").build();

            ReflectionReport report = engine.reflect(null, result);

            assertNotNull(report);
            assertNull(report.getSessionId());
        }

        @Test
        void testReflectUsesTaskDescriptionFromMessage() {
            lenient().when(qualityEvaluator.evaluateAccuracy(anyString(), anyString())).thenReturn(0.9);
            lenient().when(qualityEvaluator.evaluateCompleteness(anyString(), anyString())).thenReturn(0.9);
            lenient().when(qualityEvaluator.evaluateSafety(anyString())).thenReturn(1.0);
            lenient().when(qualityEvaluator.evaluateUserExperience(anyString())).thenReturn(0.9);
            lenient().when(errorDetector.detectHallucination(anyString(), anyList()))
                    .thenReturn(Collections.emptyList());
            lenient().when(errorDetector.detectLogicContradiction(anyString()))
                    .thenReturn(Collections.emptyList());

            Session session = new Session();
            session.setSessionId("sess");
            ChatContext context = mock(ChatContext.class);
            when(context.getSession()).thenReturn(session);
            when(context.getAttribute("taskDescription")).thenReturn(null);
            when(context.getMessages()).thenReturn(List.of(Message.user("from message")));

            ActionResult result = ActionResult.builder()
                    .nodeId("n1").success(true).output("output").build();

            ReflectionReport report = engine.reflect(context, result);

            assertNotNull(report);
        }
    }
}
