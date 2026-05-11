package lyjew.com.lyclaw.reflect;

import lyjew.com.lyclaw.action.ActionResult;
import lyjew.com.lyclaw.context.ChatContext;

import java.util.List;

/**
 * 反思引擎接口，是反思系统的核心入口。
 * 负责对模型执行结果进行反思，包括生成反思报告、质量评估、错误检测和策略调整建议。
 */
public interface ReflectionEngine {

    /**
     * 对一次完整的聊天执行结果进行反思，生成综合反思报告。
     *
     * @param context 聊天上下文
     * @param result  执行结果
     * @return 反思报告，包含质量评估、错误列表和策略建议
     */
    ReflectionReport reflect(ChatContext context, ActionResult result);

    /**
     * 根据指定标准对模型输出进行质量评估。
     *
     * @param output   模型输出文本
     * @param criteria 质量评估标准
     * @return 质量评估结果
     */
    QualityAssessment assessQuality(String output, QualityCriteria criteria);

    /**
     * 检测模型输出中的各类错误，使用 groundTruth 作为事实基准。
     *
     * @param output      模型输出文本
     * @param groundTruth 事实基准列表
     * @return 检测到的错误列表
     */
    List<DetectedError> detectErrors(String output, List<String> groundTruth);

    /**
     * 基于反思报告，生成策略调整建议。
     *
     * @param report 反思报告
     * @return 策略调整建议
     */
    StrategyAdjustment suggestAdjustment(ReflectionReport report);
}
