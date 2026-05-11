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

/**
 * 反思引擎核心实现，协调质量评估、错误检测和策略调整三大组件。
 *
 * <p>工作流程：</p>
 * <ol>
 *   <li>从上下文和ActionResult中提取输出、会话ID和任务描述</li>
 *   <li>调用 {@link QualityEvaluator} 进行多维度质量评估（准确率、完整性、安全性、用户体验）</li>
 *   <li>调用 {@link ErrorDetector} 检测幻觉、逻辑矛盾和工具调用异常</li>
 *   <li>计算加权综合评分（权重：准确率0.35 + 完整性0.30 + 安全性0.20 + UX 0.15）</li>
 *   <li>当存在错误或综合评分低于阈值(0.6)时，调用 {@link StrategyAdjuster} 生成策略调整建议</li>
 * </ol>
 *
 * <p>设计理念：参考控制论中的"感知-分析-调整"循环，使AI系统具备自我反思和自我改进能力。
 * 模板方法模式的变体：{@link #reflect} 定义了固定的反思流程框架，
 * 具体检测和评估逻辑委托给注入的组件实现。</p>
 *
 * @see QualityEvaluator
 * @see ErrorDetector
 * @see StrategyAdjuster
 */
@Slf4j
@Service
public class ReflectionEngineImpl implements ReflectionEngine {

    /** 准确率在综合评分中的权重 */
    private static final double ACCURACY_WEIGHT = 0.35;
    /** 完整性在综合评分中的权重 */
    private static final double COMPLETENESS_WEIGHT = 0.30;
    /** 安全性在综合评分中的权重 */
    private static final double SAFETY_WEIGHT = 0.20;
    /** 用户体验在综合评分中的权重，权重最低因为可通过后续格式化改进 */
    private static final double USER_EXPERIENCE_WEIGHT = 0.15;
    /** 触发策略调整的质量阈值，低于此值将生成调整建议 */
    private static final double QUALITY_THRESHOLD = 0.6;

    /** 质量评估器 */
    private final QualityEvaluator qualityEvaluator;
    /** 错误检测器 */
    private final ErrorDetector errorDetector;
    /** 策略调整器 */
    private final StrategyAdjuster strategyAdjuster;

    /**
     * 构造反思引擎，注入三大核心组件。
     *
     * @param qualityEvaluator  质量评估器
     * @param errorDetector     错误检测器
     * @param strategyAdjuster  策略调整器
     */
    public ReflectionEngineImpl(QualityEvaluator qualityEvaluator,
                                 ErrorDetector errorDetector,
                                 StrategyAdjuster strategyAdjuster) {
        this.qualityEvaluator = qualityEvaluator;
        this.errorDetector = errorDetector;
        this.strategyAdjuster = strategyAdjuster;
    }

    /**
     * 执行完整的反思流程：评估质量、检测错误、生成调整建议。
     *
     * <p>这是反思引擎的核心入口方法，按固定顺序依次调用各个组件。</p>
     *
     * @param context 对话上下文，包含会话信息和任务描述
     * @param result  行动执行结果，包含输出文本和元数据
     * @return 反思报告，包含质量评分、错误列表和调整建议
     */
    @Override
    public ReflectionReport reflect(ChatContext context, ActionResult result) {
        // 第一步：提取原始数据
        String output = extractOutput(result);
        String sessionId = extractSessionId(context);
        String taskDescription = extractTaskDescription(context);

        // 第二步：构建质量评估标准
        QualityCriteria criteria = buildCriteria(taskDescription, result);
        QualityAssessment quality = assessQuality(output, criteria);

        // 第三步：提取基准真值并进行多类型错误检测
        List<String> groundTruth = extractGroundTruth(result);
        List<DetectedError> errors = new ArrayList<>();
        errors.addAll(detectErrors(output, groundTruth));
        errors.addAll(detectLogicErrors(output));
        errors.addAll(detectToolFailures(result));

        // 第四步：计算加权综合评分
        double overallScore = computeOverallScore(quality);

        // 第五步：组装反思报告
        ReflectionReport report = ReflectionReport.builder()
                .reflectionId(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .quality(quality)
                .errors(errors)
                .overallScore(overallScore)
                .timestamp(System.currentTimeMillis())
                .build();

        // 第六步：存在错误或整体评分低于阈值时，生成策略调整建议
        if (!errors.isEmpty() || overallScore < QUALITY_THRESHOLD) {
            StrategyAdjustment suggestion = suggestAdjustment(report);
            report.setSuggestion(suggestion);
        }

        return report;
    }

    /**
     * 根据质量标准对输出进行多维度质量评估。
     *
     * <p>按需评估四个维度（通过QualityCriteria的开关控制），
     * 空输出返回全零评分（安全性为1.0，因为空内容无安全隐患）。</p>
     *
     * @param output   待评估的AI输出文本
     * @param criteria 质量标准（包含各维度开关和期望输出）
     * @return 包含各维度评分和综合评分的QualityAssessment
     */
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

    /**
     * 检测输出中的幻觉错误。
     *
     * @param output      待检测的AI输出文本
     * @param groundTruth 基准真值列表（已知事实），用于交叉验证
     * @return 检测到的错误列表，空列表表示无幻觉
     */
    @Override
    public List<DetectedError> detectErrors(String output, List<String> groundTruth) {
        if (output == null || output.isBlank()) {
            return Collections.emptyList();
        }
        return errorDetector.detectHallucination(output, groundTruth);
    }

    /**
     * 根据反思报告推荐策略调整。
     *
     * @param report 反思报告
     * @return 推荐的策略调整，为null表示无需调整
     */
    @Override
    public StrategyAdjustment suggestAdjustment(ReflectionReport report) {
        if (report == null) {
            return null;
        }
        return strategyAdjuster.adjust(report);
    }

    /**
     * 从ActionResult中安全提取输出文本。
     *
     * @param result 行动执行结果
     * @return 输出文本，为空时返回空字符串
     */
    private String extractOutput(ActionResult result) {
        if (result == null) return "";
        return result.getOutput() != null ? result.getOutput() : "";
    }

    /**
     * 从ChatContext中提取会话ID。
     *
     * @param context 对话上下文
     * @return 会话ID，上下文或会话为空时返回null
     */
    private String extractSessionId(ChatContext context) {
        if (context == null || context.getSession() == null) return null;
        return context.getSession().getSessionId();
    }

    /**
     * 从ChatContext中提取任务描述。
     *
     * <p>优先级：context属性中的 "taskDescription" > 最后一条消息的内容。</p>
     *
     * @param context 对话上下文
     * @return 任务描述文本，无法提取时返回空字符串
     */
    private String extractTaskDescription(ChatContext context) {
        if (context == null) return "";

        // 优先从上下文的显式属性中获取任务描述
        Object taskAttr = context.getAttribute("taskDescription");
        if (taskAttr instanceof String s && !s.isBlank()) {
            return s;
        }

        // 回退：使用最后一条消息内容作为任务描述
        var messages = context.getMessages();
        if (messages != null && !messages.isEmpty()) {
            var lastMsg = messages.get(messages.size() - 1);
            if (lastMsg.getContent() != null) {
                return lastMsg.getContent();
            }
        }

        return "";
    }

    /**
     * 从ActionResult元数据中提取基准真值(ground truth)列表。
     *
     * <p>用于与AI输出进行交叉验证，检测幻觉。</p>
     *
     * @param result 行动执行结果
     * @return 基准真值字符串列表，无法提取时返回空列表
     */
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
                // 类型不匹配时安全降级
                return Collections.emptyList();
            }
        }
        return Collections.emptyList();
    }

    /**
     * 从ActionResult元数据中提取工具调用历史记录。
     *
     * @param result 行动执行结果
     * @return 工具调用记录列表，无法提取时返回空列表
     */
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
                // 类型不匹配时安全降级
                return Collections.emptyList();
            }
        }
        return Collections.emptyList();
    }

    /**
     * 构建质量评估标准。
     *
     * <p>从ActionResult元数据中提取期望输出(expectedOutput)，与任务描述组合构建QualityCriteria。
     * 所有四个评估维度（准确率、完整性、安全性、UX）默认开启。</p>
     *
     * @param taskDescription 任务描述
     * @param result          行动执行结果（可从中获取期望输出）
     * @return 配置好的质量评估标准
     */
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

    /**
     * 检测输出中的逻辑矛盾。
     *
     * @param output AI输出文本
     * @return 检测到的逻辑矛盾错误列表
     */
    private List<DetectedError> detectLogicErrors(String output) {
        if (output == null || output.isBlank()) return Collections.emptyList();
        return errorDetector.detectLogicContradiction(output);
    }

    /**
     * 检测工具调用失败模式。
     *
     * <p>从ActionResult中提取工具调用历史，传给ErrorDetector进行连续失败、
     * 系统级故障和超时检测。</p>
     *
     * @param result 行动执行结果
     * @return 检测到的工具故障错误列表
     */
    private List<DetectedError> detectToolFailures(ActionResult result) {
        List<ToolCallRecord> history = extractToolHistory(result);
        // 无历史记录时跳过检测
        if (history.isEmpty()) return Collections.emptyList();
        return errorDetector.detectToolFailurePattern(history);
    }

    /**
     * 从QualityAssessment计算加权综合评分。
     *
     * @param quality 质量评估结果
     * @return 加权后的综合评分，范围[0, 1]
     */
    private double computeOverallScore(QualityAssessment quality) {
        if (quality == null) return 0.0;
        return computeWeightedOverall(
                quality.getAccuracy(),
                quality.getCompleteness(),
                quality.getSafety(),
                quality.getUserExperience());
    }

    /**
     * 按加权公式计算综合评分。
     *
     * <p>公式：score = accuracy*0.35 + completeness*0.30 + safety*0.20 + ux*0.15</p>
     *
     * @param accuracy      准确率评分
     * @param completeness  完整性评分
     * @param safety        安全性评分
     * @param userExperience 用户体验评分
     * @return 钳位到[0, 1]的加权综合评分
     */
    private double computeWeightedOverall(double accuracy, double completeness,
                                           double safety, double userExperience) {
        return clamp(accuracy * ACCURACY_WEIGHT
                   + completeness * COMPLETENESS_WEIGHT
                   + safety * SAFETY_WEIGHT
                   + userExperience * USER_EXPERIENCE_WEIGHT);
    }

    /**
     * 将评分钳位到[0, 1]区间。
     *
     * @param score 原始评分
     * @return 钳位后的评分
     */
    private double clamp(double score) {
        return Math.max(0.0, Math.min(1.0, score));
    }
}
