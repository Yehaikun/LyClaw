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

/**
 * 默认策略调整器，根据反思报告中的错误类型和质量评分自动推荐调整措施。
 *
 * <p>决策优先级（从高到低）：</p>
 * <ol>
 *   <li>工具故障 (1.00) — 切换到备用工具或检查基础设施</li>
 *   <li>幻觉 (0.95) — 降低温度、添加基准事实上下文</li>
 *   <li>安全问题 (0.90) — 触发人工审核</li>
 *   <li>逻辑错误 (0.80) — 切换到链式推理模式</li>
 *   <li>准确性低 (0.70) — 增加工具调用和验证步骤</li>
 *   <li>完整性低 (0.60) — 分解任务为子任务</li>
 *   <li>UX 低 (0.50) — 添加格式化和结构调整</li>
 *   <li>整体分低 (0.40) — 触发全面重新规划</li>
 * </ol>
 *
 * <p>设计灵感来自控制论中的负反馈回路：系统监测输出质量 →
 * 识别偏差 → 调整策略参数 → 重新执行 → 再次监测。</p>
 */
@Slf4j
@Component
public class DefaultStrategyAdjuster implements StrategyAdjuster {

    /** 触发全面重规划的总体评分阈值 */
    private static final double MAJOR_REPLAN_THRESHOLD = 0.5;
    /** 低准度阈值 */
    private static final double LOW_ACCURACY_THRESHOLD = 0.4;
    /** 低完整性阈值 */
    private static final double LOW_COMPLETENESS_THRESHOLD = 0.4;
    /** 低安全分阈值 */
    private static final double LOW_SAFETY_THRESHOLD = 0.5;
    /** 低UX评分阈值 */
    private static final double LOW_UX_THRESHOLD = 0.3;
    /** 高错误数量阈值 */
    private static final int HIGH_ERROR_COUNT_THRESHOLD = 3;

    /** 调整参数键名常量 */
    public static final String PARAM_TEMPERATURE = "temperature";
    public static final String PARAM_REASONING_STRATEGY = "reasoningStrategy";
    public static final String PARAM_VERIFY_STEPS = "verifyIntermediateSteps";
    public static final String PARAM_DECOMPOSE = "decomposeFurther";
    public static final String PARAM_CONTEXT_ENRICH = "enrichContext";

    /** 各问题类型的优先级权重 */
    private static final double PRIORITY_TOOL_FAILURE = 1.0;
    private static final double PRIORITY_HALLUCINATION = 0.95;
    private static final double PRIORITY_SAFETY = 0.90;
    private static final double PRIORITY_LOGIC_ERROR = 0.80;
    private static final double PRIORITY_LOW_ACCURACY = 0.70;
    private static final double PRIORITY_LOW_COMPLETENESS = 0.60;
    private static final double PRIORITY_LOW_UX = 0.50;
    private static final double PRIORITY_LOW_OVERALL = 0.40;

    /** 无调整建议的哨兵对象 */
    private static final StrategyAdjustment NO_ADJUSTMENT = StrategyAdjustment.builder()
            .type(StrategyAdjustment.AdjustmentType.REWRITE_PROMPT)
            .reason("No significant issues detected. Maintaining current strategy.")
            .parameters(Map.of("noChange", true))
            .priority(0.0)
            .build();

    /**
     * 根据反思报告推荐策略调整。
     *
     * <p>按优先级依次检查：工具故障 → 幻觉 → 安全 → 逻辑 → 准确率 → 完整性 → UX → 错误数量 → 整体分。</p>
     *
     * @param report 反思报告（包含错误列表和质量评估）
     * @return 推荐的策略调整，无问题时返回 NO_ADJUSTMENT
     */
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

    /** 检查工具故障错误，区分单独连续失败和系统性全部失败。 */
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

    /** 检查幻觉错误，建议降低温度并增加基准事实上下文。 */
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

    /** 检查逻辑矛盾错误，建议切换到链式推理并启用中间步骤验证。 */
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

    /** 构建工具增强调整：添加额外的工具调用和验证步骤。 */
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

    /** 构建任务分解调整：将大任务拆分为子任务并增加验证步骤。 */
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

    /** 构建用户体验改善调整：添加格式化和结构化输出指令。 */
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

    /** 构建安全调整：触发人工审核并阻断自动执行。 */
    private StrategyAdjustment buildSafetyAdjustment(double safetyScore) {
        return StrategyAdjustment.builder()
                .type(StrategyAdjustment.AdjustmentType.TRIGGER_HUMAN_INTERVENTION)
                .reason(String.format("Safety violation detected (score: %.2f). Triggering human review before proceeding.", safetyScore))
                .parameters(Map.of("requireHumanReview", true, "blockAutoExecution", true))
                .priority(PRIORITY_SAFETY)
                .build();
    }

    /** 构建策略变更调整：错误过多时切换到完全不同的方法。 */
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

    /** 构建全面重规划调整：整体分严重低于阈值时从头重新规划。 */
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
