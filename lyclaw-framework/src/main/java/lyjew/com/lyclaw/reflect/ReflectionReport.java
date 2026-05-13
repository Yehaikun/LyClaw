package lyjew.com.lyclaw.reflect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 反思报告实体，汇总反思引擎的一次完整评估结果。
 *
 * <p><b>在反思系统中的角色</b>：ReflectionReport 是反思链路的最终输出产物，
 * 由 {@link ReflectionEngine} 生成，包含对模型一次执行结果的全面诊断。
 * 它是反思系统中连接"诊断阶段"和"决策阶段"的关键数据桥梁：
 * {@link StrategyAdjuster} 读取 ReflectionReport 中的诊断信息，
 * 生成对应的 {@link StrategyAdjustment} 策略调整方案。</p>
 *
 * <p><b>报告包含的六大信息模块</b>：
 * <ul>
 *   <li><b>reflectionId（反思标识）</b> — 此次反思会话的唯一标识符，
 *       用于追溯和关联反思记录。通常使用 UUID 生成。
 *       在多轮迭代的反思-执行循环中，reflectionId 用于形成反思链。</li>
 *   <li><b>sessionId（会话标识）</b> — 关联的对话会话 ID，
 *       将反思报告与原始对话绑定。同一个会话可能产生多次反思报告
 *       （例如每轮对话后进行一次反思），通过时间戳区分不同反思。</li>
 *   <li><b>quality（质量评估）</b> — {@link QualityAssessment} 对象，
 *       包含准确性、完整性、安全性、用户体验四个维度的分项评分和综合总分。
 *       这是反思报告中最核心的量化指标，决定了后续调整的激进程度。</li>
 *   <li><b>errors（错误列表）</b> — {@link DetectedError} 对象列表，
 *       记录反思引擎检测到的所有具体质量问题。每个错误都带有类型、
 *       描述、位置和修正建议，为策略调整器提供了精确的修正方向。</li>
 *   <li><b>suggestion（策略建议）</b> — {@link StrategyAdjustment} 对象，
 *       反思引擎初步生成的策略调整建议。该字段可能为 null，
 *       表示反思引擎尚未生成建议（将由独立的 StrategyAdjuster 组件生成）。</li>
 *   <li><b>overallScore（综合评分）</b> — 整体执行质量的概括性评分（0.0~1.0），
 *       与 quality.overall 可能不同（overallScore 可能额外考虑执行时间、
 *       资源消耗等非质量维度因素）。</li>
 *   <li><b>timestamp（时间戳）</b> — 反思报告生成的时间（Unix 毫秒），
 *       用于追踪反思的时间线、计算反思耗时、以及判断反思信息的时效性。</li>
 * </ul>
 *
 * <p><b>反思报告的典型生命周期</b>：</p>
 * <ol>
 *   <li>Agent 执行完成 → 输出结果和工具调用记录</li>
 *   <li>ReflectionEngine.reflect() 被调用 → 质量评估 + 错误检测</li>
 *   <li>生成 ReflectionReport → 质量评分 + 错误列表 + 时间戳</li>
 *   <li>StrategyAdjuster.adjust(report) 被调用 → 读取报告中的诊断信息</li>
 *   <li>生成 StrategyAdjustment → 决定下一步的操作（重试/重写/切换策略/人工介入）</li>
 *   <li>报告归档 → 用于后续的统计分析、模型评估和持续优化</li>
 * </ol>
 *
 * @see ReflectionEngine 反思引擎，生成此报告的组件
 * @see QualityAssessment 质量评估，报告的核心子组件
 * @see DetectedError 检测到的错误，组成了错误列表
 * @see StrategyAdjustment 策略调整方案，基于此报告生成
 * @see StrategyAdjuster 策略调整器，消费此报告
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReflectionReport {
    /** 反思会话的唯一标识 */
    private String reflectionId;
    /** 关联的对话会话标识 */
    private String sessionId;
    /** 质量评估结果，包含四个维度的分项评分 */
    private QualityAssessment quality;
    /** 检测到的所有错误列表 */
    private List<DetectedError> errors;
    /** 策略调整建议（可能为空） */
    private StrategyAdjustment suggestion;
    /** 综合评分（0.0~1.0） */
    private double overallScore;
    /** 报告生成时间戳（Unix 毫秒） */
    private long timestamp;
}
