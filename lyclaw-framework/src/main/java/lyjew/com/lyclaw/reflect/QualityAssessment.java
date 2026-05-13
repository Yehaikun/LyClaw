package lyjew.com.lyclaw.reflect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 质量评估结果实体，包含多个维度的评分与综合总分。
 *
 * <p><b>在反思系统中的角色</b>：QualityAssessment 是 {@link QualityEvaluator}
 * 接口各评估方法的输出载体。当反思引擎对模型的一次执行结果进行质量评估时，
 * 会从准确性、完整性、安全性和用户体验四个独立维度进行打分，然后将各维度
 * 的分项评分汇总为该对象，并计算出一个综合总分。</p>
 *
 * <p><b>四维评估体系</b>：每个维度独立评分（范围 0.0~1.0），
 * 分别衡量模型输出的不同质量方面：</p>
 * <ul>
 *   <li><b>accuracy（准确性）</b> — 衡量模型输出与期望输出或事实基准之间的一致性。
 *       评估方法包括：与 ground truth 的文本相似度比较、关键事实的逐条核对、
 *       以及工具调用结果的正确性验证。高准确性意味着模型的输出内容是可信的，
 *       没有编造或歪曲信息。这是四个维度中权重最高的维度。</li>
 *   <li><b>completeness（完整性）</b> — 衡量模型输出是否覆盖了任务描述中
 *       要求的所有方面。评估方法包括：检查任务要求的关键点是否在输出中都有体现、
 *       输出是否包含所有必要的结构段落、以及是否有明显的信息遗漏。
 *       高完整性意味着模型没有遗漏重要信息，用户无需追问补充。</li>
 *   <li><b>safety（安全性）</b> — 衡量模型输出是否包含不安全、有害或违规内容。
 *       这是四个维度中的一个特殊维度，它不衡量"好"的程度而是衡量"无危害"的程度。
 *       评估方法包括：敏感词检测、越狱攻击检测、个人信息泄露检测、以及
 *       危险操作建议的识别。安全评分低（< 0.5）通常会触发人工介入流程。</li>
 *   <li><b>userExperience（用户体验）</b> — 衡量输出在可读性、格式规范性、
 *       语气友好度、以及信息组织合理性方面的表现。评估方法包括：检测输出结构
 *       是否清晰（有标题、段落分隔）、语言是否通顺、格式是否符合预期（JSON/Markdown等）、
 *       以及回复语气是否自然友好。高用户体验意味着用户可以顺畅地消费和利用输出内容。</li>
 * </ul>
 *
 * <p><b>综合评分 (overall)</b>：是对四个分项评分的加权平均或自定义聚合，
 * 代表模型输出质量的总体评估。不同的 {@link QualityEvaluator} 实现可以使用
 * 不同的权重分配策略（如对安全维度给予更高权重、或根据任务类型动态调整各维度权重）。
 * 综合评分也是 {@link StrategyAdjuster} 决定调整激进程度的主要参考指标之一。</p>
 *
 * <p><b>评分范围约定</b>：
 * <ul>
 *   <li>0.0 — 质量极差，完全不可用</li>
 *   <li>0.0~0.3 — 质量低，存在严重问题，建议重新生成或人工介入</li>
 *   <li>0.3~0.7 — 质量中等，基本可用但存在改进空间，适合进行策略微调</li>
 *   <li>0.7~0.9 — 质量良好，可以直接使用或仅需轻微修正</li>
 *   <li>0.9~1.0 — 质量优秀，接近完美的输出</li>
 * </ul>
 * </p>
 *
 * @see QualityEvaluator 质量评估器接口，生成此评估结果的组件
 * @see ReflectionReport 反思报告，将此评估结果作为子组件包含
 * @see StrategyAdjuster 策略调整器，根据评估结果制定调整方案
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QualityAssessment {
    /** 准确性评分（0.0~1.0），衡量输出内容的事实正确性 */
    private double accuracy;
    /** 完整性评分（0.0~1.0），衡量输出对任务要求的覆盖程度 */
    private double completeness;
    /** 安全性评分（0.0~1.0），衡量输出内容的无害程度 */
    private double safety;
    /** 用户体验评分（0.0~1.0），衡量输出的可读性和友好度 */
    private double userExperience;
    /** 综合评分（0.0~1.0），各分项评分的加权聚合结果 */
    private double overall;
}
