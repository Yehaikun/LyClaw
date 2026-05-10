package lyjew.com.lyclaw.reflect.controller;

import lyjew.com.lyclaw.reflect.DetectedError;
import lyjew.com.lyclaw.reflect.ErrorDetector;
import lyjew.com.lyclaw.reflect.QualityAssessment;
import lyjew.com.lyclaw.reflect.QualityCriteria;
import lyjew.com.lyclaw.reflect.QualityEvaluator;
import lyjew.com.lyclaw.reflect.ReflectRequest;
import lyjew.com.lyclaw.reflect.ReflectionEngine;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import lyjew.com.lyclaw.reflect.StrategyAdjuster;
import lyjew.com.lyclaw.reflect.StrategyAdjustment;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/api/reflect")
public class ReflectController {

    private final ReflectionEngine reflectionEngine;
    private final QualityEvaluator qualityEvaluator;
    private final ErrorDetector errorDetector;
    private final StrategyAdjuster strategyAdjuster;

    public ReflectController(ReflectionEngine reflectionEngine,
                              QualityEvaluator qualityEvaluator,
                              ErrorDetector errorDetector,
                              StrategyAdjuster strategyAdjuster) {
        this.reflectionEngine = reflectionEngine;
        this.qualityEvaluator = qualityEvaluator;
        this.errorDetector = errorDetector;
        this.strategyAdjuster = strategyAdjuster;
    }

    @PostMapping("/reflect")
    public ReflectionReport reflect(@RequestBody ReflectRequest request) {
        QualityCriteria criteria = QualityCriteria.builder()
                .taskDescription(request.getContext() != null ? request.getContext() : "")
                .expectedOutput(request.getExpectedOutput() != null ? request.getExpectedOutput() : "")
                .checkAccuracy(true)
                .checkCompleteness(true)
                .checkSafety(true)
                .checkUserExperience(true)
                .build();

        QualityAssessment quality = reflectionEngine.assessQuality(
                request.getOutput(), criteria);

        List<DetectedError> errors = new ArrayList<>();
        errors.addAll(errorDetector.detectHallucination(
                request.getOutput(), Collections.emptyList()));
        errors.addAll(errorDetector.detectLogicContradiction(
                request.getOutput()));

        double overall = quality.getOverall();

        ReflectionReport report = ReflectionReport.builder()
                .reflectionId(UUID.randomUUID().toString())
                .sessionId(request.getSessionId())
                .quality(quality)
                .errors(errors)
                .overallScore(overall)
                .timestamp(System.currentTimeMillis())
                .build();

        if (!errors.isEmpty() || overall < 0.6) {
            StrategyAdjustment suggestion = strategyAdjuster.adjust(report);
            report.setSuggestion(suggestion);
        }

        return report;
    }

    @PostMapping("/evaluate")
    public QualityAssessment evaluate(@RequestBody Map<String, Object> body) {
        String output = (String) body.getOrDefault("output", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> criteriaMap = (Map<String, Object>) body.get("criteria");

        QualityCriteria criteria;
        if (criteriaMap != null) {
            criteria = QualityCriteria.builder()
                    .taskDescription((String) criteriaMap.getOrDefault("taskDescription", ""))
                    .expectedOutput((String) criteriaMap.getOrDefault("expectedOutput", ""))
                    .checkAccuracy((Boolean) criteriaMap.getOrDefault("checkAccuracy", true))
                    .checkCompleteness((Boolean) criteriaMap.getOrDefault("checkCompleteness", true))
                    .checkSafety((Boolean) criteriaMap.getOrDefault("checkSafety", true))
                    .checkUserExperience((Boolean) criteriaMap.getOrDefault("checkUserExperience", true))
                    .build();
        } else {
            criteria = QualityCriteria.builder()
                    .taskDescription("")
                    .expectedOutput("")
                    .checkAccuracy(true)
                    .checkCompleteness(true)
                    .checkSafety(true)
                    .checkUserExperience(true)
                    .build();
        }

        return reflectionEngine.assessQuality(output, criteria);
    }

    @PostMapping("/detect-errors")
    public List<DetectedError> detectErrors(@RequestBody Map<String, Object> body) {
        String output = (String) body.getOrDefault("output", "");
        @SuppressWarnings("unchecked")
        List<String> groundTruth = (List<String>) body.getOrDefault("groundTruth", Collections.emptyList());

        if (output == null || output.isBlank()) {
            return Collections.emptyList();
        }

        List<DetectedError> errors = new ArrayList<>();
        errors.addAll(errorDetector.detectHallucination(output, groundTruth));
        errors.addAll(errorDetector.detectLogicContradiction(output));

        return errors;
    }
}
