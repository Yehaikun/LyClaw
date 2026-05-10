package lyjew.com.lyclaw.reflect;

/**
 * 质量评估器 —— 评估 AI 输出的各个质量维度。
 *
 * @since 2.0
 */
public interface QualityEvaluator {

    double evaluateAccuracy(String output, String expected);

    double evaluateCompleteness(String output, String taskDescription);

    double evaluateSafety(String output);

    double evaluateUserExperience(String output);
}
