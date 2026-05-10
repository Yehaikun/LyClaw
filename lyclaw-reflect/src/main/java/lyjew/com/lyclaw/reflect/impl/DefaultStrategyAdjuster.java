package lyjew.com.lyclaw.reflect.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.reflect.DetectedError;
import lyjew.com.lyclaw.reflect.QualityAssessment;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import lyjew.com.lyclaw.reflect.StrategyAdjuster;
import lyjew.com.lyclaw.reflect.StrategyAdjustment;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class DefaultStrategyAdjuster implements StrategyAdjuster {

    private static final double MAJOR_REPLAN_THRESHOLD = 0.5;
    private static final double LOW_ACCURACY_THRESHOLD = 0.4;
    private static final double LOW_COMPLETENESS_THRESHOLD = 0.4;
    private static final double LOW_SAFETY_THRESHOLD = 0.5;
    private static final double LOW_UX_THRESHOLD = 0.3;
    private static final int HIGH_ERROR_COUNT_THRESHOLD = 3;

    public static final String PARAM_TEMPERATURE = "temperature";
    public static final String PARAM_REASONING_STRATEGY = "reasoningStrategy";
    public static final String PARAM_VERIFY_STEPS = "verifyIntermediateSteps";
    public static final String PARAM_DECOMPOSE = "decomposeFurther";
    public static final String PARAM_CONTEXT_ENRICH = "enrichContext";

    private static final double PRIORITY_TOOL_FAILURE = 1.0;
    private static final double PRIORITY_HALLUCINATION = 0.95;
    private static final double PRIORITY_SAFETY = 0.90;
    private static final double PRIORITY_LOGIC_ERROR = 0.80;
    private static final double PRIORITY_LOW_ACCURACY = 0.70;
    private static final double PRIORITY_LOW_COMPLETENESS = 0.60;
    private static final double PRIORITY_LOW_UX = 0.50;
    private static final double PRIORITY_LOW_OVERALL = 0.40;

    private static final StrategyAdjustment NO_ADJUSTMENT = StrategyAdjustment.builder()
            .type(StrategyAdjustment.AdjustmentType.REWRITE_PROMPT)
            .reason("No significant issues detected. Maintaining current strategy.")
            .parameters(Map.of("noChange", true))
            .priority(0.0)
            .build();

    @Override
    public StrategyAdjustment adjust(ReflectionReport report) {
        if (report == null) return NO_ADJUSTMENT;

        List<DetectedError> errors = report.getErrors();
        QualityAssessment quality = report.getQuality();

        if (errors != null && !errors.isEmpty()) {
            StrategyAdjustment toolAdjustment = checkToolFailures(errors);
            if (toolAdjustment != null) return toolAdjustment;
        }

        if (errors != null && !errors.isEmpty()) {
            StrategyAdjustment hallucinationAdjustment = checkHallucinations(errors);
            if (hallucinationAdjustment != null) return hallucinationAdjustment;
        }

        if (quality != null && quality.getSafety() < LOW_SAFETY_THRESHOLD) {
            return buildSafetyAdjustment(quality.getSafety());
        }

        if (errors != null && !errors.isEmpty()) {
            StrategyAdjustment logicAdjustment = checkLogicErrors(errors);
            if (logicAdjustment != null) return logicAdjustment;
        }

        if (quality != null && quality.getAccuracy() < LOW_ACCURACY_THRESHOLD) {
            return buildToolAugmentationAdjustment(quality.getAccuracy());
        }

        if (quality != null && quality.getCompleteness() < LOW_COMPLETENESS_THRESHOLD) {
            return buildDecomposeAdjustment(quality.getCompleteness());
        }

        if (quality != null && quality.getUserExperience() < LOW_UX_THRESHOLD) {
            return buildUXAdjustment(quality.getUserExperience());
        }

        if (errors != null && errors.size() > HIGH_ERROR_COUNT_THRESHOLD) {
            return buildStrategyChangeAdjustment(errors.size());
        }

        if (report.getOverallScore() < MAJOR_REPLAN_THRESHOLD) {
            return buildMajorReplanAdjustment(report.getOverallScore());
        }

        return NO_ADJUSTMENT;
    }

    private StrategyAdjustment checkToolFailures(List<DetectedError> errors) {
        for (DetectedError error : errors) {
            if (error.getType() == DetectedError.ErrorType.TOOL_FAILURE_PATTERN) {
                Map<String, Object> params = new HashMap<>();
                params.put(PARAM_CONTEXT_ENRICH, true);

                if (error.getDescription() != null && error.getDescription().contains("All")) {
                    params.put("checkConnectivity", true);
                    params.put("verifyAuth", true);
                    return StrategyAdjustment.builder()
                            .type(StrategyAdjustment.AdjustmentType.ADD_TOOL_CALL)
                            .reason("Systemic tool failure detected: " + error.getDescription()
                                  + ". Switching to alternative tools and verifying infrastructure.")
                            .parameters(params)
                            .priority(PRIORITY_TOOL_FAILURE)
                            .build();
                }

                params.put(PARAM_REASONING_STRATEGY, "switch_tool");
                return StrategyAdjustment.builder()
                        .type(StrategyAdjustment.AdjustmentType.SWITCH_PLAN_STRATEGY)
                        .reason("Consecutive tool failure detected: " + error.getDescription()
                              + ". Switching to alternative tool or approach.")
                        .parameters(params)
                        .priority(PRIORITY_TOOL_FAILURE)
                        .build();
            }
        }
        return null;
    }

    private StrategyAdjustment checkHallucinations(List<DetectedError> errors) {
        long hallucinationCount = errors.stream()
                .filter(e -> e.getType() == DetectedError.ErrorType.HALLUCINATION)
                .count();

        if (hallucinationCount > 0) {
            Map<String, Object> params = new HashMap<>();
            params.put(PARAM_TEMPERATURE, 0.3);
            params.put("addGroundTruthContext", true);
            params.put(PARAM_CONTEXT_ENRICH, true);

            String reason = hallucinationCount == 1
                    ? "Hallucination detected. Lowering temperature and adding ground truth context."
                    : hallucinationCount + " hallucinations detected. Lowering temperature and enriching context to improve factuality.";

            return StrategyAdjustment.builder()
                    .type(StrategyAdjustment.AdjustmentType.REDUCE_TEMPERATURE)
                    .reason(reason)
                    .parameters(params)
                    .priority(PRIORITY_HALLUCINATION)
                    .build();
        }
        return null;
    }

    private StrategyAdjustment checkLogicErrors(List<DetectedError> errors) {
        long logicErrors = errors.stream()
                .filter(e -> e.getType() == DetectedError.ErrorType.LOGIC_CONTRADICTION)
                .count();

        if (logicErrors > 0) {
            Map<String, Object> params = new HashMap<>();
            params.put(PARAM_REASONING_STRATEGY, "chain_of_thought");
            params.put(PARAM_VERIFY_STEPS, true);
            params.put(PARAM_TEMPERATURE, 0.5);

            return StrategyAdjustment.builder()
                    .type(StrategyAdjustment.AdjustmentType.REWRITE_PROMPT)
                    .reason("Logic contradictions detected (" + logicErrors
                          + "). Switching to Chain-of-Thought reasoning with intermediate step verification.")
                    .parameters(params)
                    .priority(PRIORITY_LOGIC_ERROR)
                    .build();
        }
        return null;
    }

    private StrategyAdjustment buildToolAugmentationAdjustment(double accuracy) {
        Map<String, Object> params = new HashMap<>();
        params.put("augmentWithTools", true);
        params.put(PARAM_REASONING_STRATEGY, "tool_augmented");
        params.put(PARAM_VERIFY_STEPS, true);

        return StrategyAdjustment.builder()
                .type(StrategyAdjustment.AdjustmentType.ADD_TOOL_CALL)
                .reason(String.format("Low accuracy score (%.2f). Augmenting with additional tools and verification steps.", accuracy))
                .parameters(params)
                .priority(PRIORITY_LOW_ACCURACY)
                .build();
    }

    private StrategyAdjustment buildDecomposeAdjustment(double completeness) {
        Map<String, Object> params = new HashMap<>();
        params.put(PARAM_DECOMPOSE, true);
        params.put(PARAM_VERIFY_STEPS, true);
        params.put("addCompletenessCheck", true);

        return StrategyAdjustment.builder()
                .type(StrategyAdjustment.AdjustmentType.SWITCH_PLAN_STRATEGY)
                .reason(String.format("Low completeness score (%.2f). Decomposing task into smaller sub-tasks with explicit verification steps.", completeness))
                .parameters(params)
                .priority(PRIORITY_LOW_COMPLETENESS)
                .build();
    }

    private StrategyAdjustment buildUXAdjustment(double uxScore) {
        Map<String, Object> params = new HashMap<>();
        params.put(PARAM_REASONING_STRATEGY, "structured_output");
        params.put("addFormattingInstructions", true);
        params.put("requireHeadings", true);

        return StrategyAdjustment.builder()
                .type(StrategyAdjustment.AdjustmentType.REWRITE_PROMPT)
                .reason(String.format("Low user experience score (%.2f). Adding formatting instructions to improve output structure and clarity.", uxScore))
                .parameters(params)
                .priority(PRIORITY_LOW_UX)
                .build();
    }

    private StrategyAdjustment buildSafetyAdjustment(double safetyScore) {
        return StrategyAdjustment.builder()
                .type(StrategyAdjustment.AdjustmentType.TRIGGER_HUMAN_INTERVENTION)
                .reason(String.format("Safety violation detected (score: %.2f). Triggering human review before proceeding.", safetyScore))
                .parameters(Map.of("requireHumanReview", true, "blockAutoExecution", true))
                .priority(PRIORITY_SAFETY)
                .build();
    }

    private StrategyAdjustment buildStrategyChangeAdjustment(int errorCount) {
        Map<String, Object> params = new HashMap<>();
        params.put(PARAM_REASONING_STRATEGY, "retry_with_different_approach");
        params.put(PARAM_CONTEXT_ENRICH, true);
        params.put("fallbackToHuman", true);

        return StrategyAdjustment.builder()
                .type(StrategyAdjustment.AdjustmentType.SWITCH_PLAN_STRATEGY)
                .reason(String.format("High error count (%d). Switching to a fundamentally different strategy.", errorCount))
                .parameters(params)
                .priority(PRIORITY_LOW_OVERALL + 0.05)
                .build();
    }

    private StrategyAdjustment buildMajorReplanAdjustment(double overallScore) {
        Map<String, Object> params = new HashMap<>();
        params.put("majorReplan", true);
        params.put("reanalyzeTaskFromScratch", true);
        params.put("considerAlternativeApproach", true);

        return StrategyAdjustment.builder()
                .type(StrategyAdjustment.AdjustmentType.SWITCH_PLAN_STRATEGY)
                .reason(String.format("Overall score critically low (%.2f). Initiating major re-plan from scratch.", overallScore))
                .parameters(params)
                .priority(PRIORITY_LOW_OVERALL)
                .build();
    }
}
