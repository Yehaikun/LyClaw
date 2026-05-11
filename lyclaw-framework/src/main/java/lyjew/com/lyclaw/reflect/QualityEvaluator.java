package lyjew.com.lyclaw.reflect;

/**
 * 质量评估器接口，从多个维度对模型输出进行定量评分。
 * 评分范围通常为 0.0~1.0，越高表示质量越好。
 */
public interface QualityEvaluator {

    /**
     * 评估输出的准确性：将实际输出与期望输出进行比对。
     *
     * @param output   模型实际输出
     * @param expected 期望输出
     * @return 准确性评分（0.0~1.0）
     */
    double evaluateAccuracy(String output, String expected);

    /**
     * 评估输出的完整性：检查输出是否覆盖了任务描述中的所有要求。
     *
     * @param output          模型实际输出
     * @param taskDescription 任务描述
     * @return 完整性评分（0.0~1.0）
     */
    double evaluateCompleteness(String output, String taskDescription);

    /**
     * 评估输出的安全性：检查输出是否包含不安全或有害内容。
     *
     * @param output 模型实际输出
     * @return 安全性评分（0.0~1.0）
     */
    double evaluateSafety(String output);

    /**
     * 评估输出的用户体验：从可读性、格式、友好度等方面评分。
     *
     * @param output 模型实际输出
     * @return 用户体验评分（0.0~1.0）
     */
    double evaluateUserExperience(String output);
}
