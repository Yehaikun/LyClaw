package lyjew.com.lyclaw.reflect.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.action.ActionResult;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.reflect.DetectedError;
import lyjew.com.lyclaw.reflect.ErrorDetector;
import lyjew.com.lyclaw.reflect.QualityAssessment;
import lyjew.com.lyclaw.reflect.QualityCriteria;
import lyjew.com.lyclaw.reflect.QualityEvaluator;
import lyjew.com.lyclaw.reflect.ReflectionEngine;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import lyjew.com.lyclaw.reflect.StrategyAdjuster;
import lyjew.com.lyclaw.reflect.StrategyAdjustment;
import lyjew.com.lyclaw.reflect.ToolCallRecord;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class ReflectionEngineImpl implements ReflectionEngine {

    private static final double ACCURACY_WEIGHT = 0.35;
    private static final double COMPLETENESS_WEIGHT = 0.30;
    private static final double SAFETY_WEIGHT = 0.20;
    private static final double USER_EXPERIENCE_WEIGHT = 0.15;
    private static final double QUALITY_THRESHOLD = 0.6;

    private final QualityEvaluator qualityEvaluator;
    private final ErrorDetector errorDetector;
    private final StrategyAdjuster strategyAdjuster;

    public ReflectionEngineImpl(QualityEvaluator qualityEvaluator,
                                 ErrorDetector errorDetector,
                                 StrategyAdjuster strategyAdjuster) {
        this.qualityEvaluator = qualityEvaluator;
        this.errorDetector = errorDetector;
        this.strategyAdjuster = strategyAdjuster;
    }

    @Override
    public ReflectionReport reflect(ChatContext context, ActionResult result) {
        String output = extractOutput(result);
        String sessionId = extractSessionId(context);
        String taskDescription = extractTaskDescription(context);

        QualityCriteria criteria = buildCriteria(taskDescription, result);
        QualityAssessment quality = assessQuality(output, criteria);

        List<String> groundTruth = extractGroundTruth(result);
        List<DetectedError> errors = new ArrayList<>();
        errors.addAll(detectErrors(output, groundTruth));
        errors.addAll(detectLogicErrors(output));
        errors.addAll(detectToolFailures(result));

        double overallScore = computeOverallScore(quality);

        ReflectionReport report = ReflectionReport.builder()
                .reflectionId(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .quality(quality)
                .errors(errors)
                .overallScore(overallScore)
                .timestamp(System.currentTimeMillis())
                .build();

        if (!errors.isEmpty() || overallScore < QUALITY_THRESHOLD) {
            StrategyAdjustment suggestion = suggestAdjustment(report);
            report.setSuggestion(suggestion);
        }

        return report;
    }

    @Override
    public QualityAssessment assessQuality(String output, QualityCriteria criteria) {
        if (output == null || output.isBlank()) {
            return QualityAssessment.builder()
                    .accuracy(0.0)
                    .completeness(0.0)
                    .safety(1.0)
                    .userExperience(0.0)
                    .overall(0.0)
                    .build();
        }

        double accuracy = criteria.isCheckAccuracy()
                ? qualityEvaluator.evaluateAccuracy(output, criteria.getExpectedOutput())
                : 1.0;

        double completeness = criteria.isCheckCompleteness()
                ? qualityEvaluator.evaluateCompleteness(output, criteria.getTaskDescription())
                : 1.0;

        double safety = criteria.isCheckSafety()
                ? qualityEvaluator.evaluateSafety(output)
                : 1.0;

        double userExperience = criteria.isCheckUserExperience()
                ? qualityEvaluator.evaluateUserExperience(output)
                : 1.0;

        double overall = computeWeightedOverall(accuracy, completeness, safety, userExperience);

        return QualityAssessment.builder()
                .accuracy(accuracy)
                .completeness(completeness)
                .safety(safety)
                .userExperience(userExperience)
                .overall(overall)
                .build();
    }

    @Override
    public List<DetectedError> detectErrors(String output, List<String> groundTruth) {
        if (output == null || output.isBlank()) {
            return Collections.emptyList();
        }
        return errorDetector.detectHallucination(output, groundTruth);
    }

    @Override
    public StrategyAdjustment suggestAdjustment(ReflectionReport report) {
        if (report == null) {
            return null;
        }
        return strategyAdjuster.adjust(report);
    }

    private String extractOutput(ActionResult result) {
        if (result == null) return "";
        return result.getOutput() != null ? result.getOutput() : "";
    }

    private String extractSessionId(ChatContext context) {
        if (context == null || context.getSession() == null) return null;
        return context.getSession().getSessionId();
    }

    private String extractTaskDescription(ChatContext context) {
        if (context == null) return "";

        Object taskAttr = context.getAttribute("taskDescription");
        if (taskAttr instanceof String s && !s.isBlank()) {
            return s;
        }

        var messages = context.getMessages();
        if (messages != null && !messages.isEmpty()) {
            var lastMsg = messages.get(messages.size() - 1);
            if (lastMsg.getContent() != null) {
                return lastMsg.getContent();
            }
        }

        return "";
    }

    @SuppressWarnings("unchecked")
    private List<String> extractGroundTruth(ActionResult result) {
        if (result == null || result.getMetadata() == null) {
            return Collections.emptyList();
        }
        Object gt = result.getMetadata().get("groundTruth");
        if (gt instanceof List<?> list) {
            try {
                return (List<String>) list;
            } catch (ClassCastException e) {
                return Collections.emptyList();
            }
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private List<ToolCallRecord> extractToolHistory(ActionResult result) {
        if (result == null || result.getMetadata() == null) {
            return Collections.emptyList();
        }
        Object history = result.getMetadata().get("toolCallHistory");
        if (history instanceof List<?> list) {
            try {
                return (List<ToolCallRecord>) list;
            } catch (ClassCastException e) {
                return Collections.emptyList();
            }
        }
        return Collections.emptyList();
    }

    private QualityCriteria buildCriteria(String taskDescription, ActionResult result) {
        String expected = null;
        if (result != null && result.getMetadata() != null) {
            expected = (String) result.getMetadata().get("expectedOutput");
        }

        return QualityCriteria.builder()
                .taskDescription(taskDescription != null ? taskDescription : "")
                .expectedOutput(expected != null ? expected : "")
                .checkAccuracy(true)
                .checkCompleteness(true)
                .checkSafety(true)
                .checkUserExperience(true)
                .build();
    }

    private List<DetectedError> detectLogicErrors(String output) {
        if (output == null || output.isBlank()) return Collections.emptyList();
        return errorDetector.detectLogicContradiction(output);
    }

    private List<DetectedError> detectToolFailures(ActionResult result) {
        List<ToolCallRecord> history = extractToolHistory(result);
        if (history.isEmpty()) return Collections.emptyList();
        return errorDetector.detectToolFailurePattern(history);
    }

    private double computeOverallScore(QualityAssessment quality) {
        if (quality == null) return 0.0;
        return computeWeightedOverall(
                quality.getAccuracy(),
                quality.getCompleteness(),
                quality.getSafety(),
                quality.getUserExperience());
    }

    private double computeWeightedOverall(double accuracy, double completeness,
                                           double safety, double userExperience) {
        return clamp(accuracy * ACCURACY_WEIGHT
                   + completeness * COMPLETENESS_WEIGHT
                   + safety * SAFETY_WEIGHT
                   + userExperience * USER_EXPERIENCE_WEIGHT);
    }

    private double clamp(double score) {
        return Math.max(0.0, Math.min(1.0, score));
    }
}
