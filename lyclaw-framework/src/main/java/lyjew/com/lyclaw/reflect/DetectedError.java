package lyjew.com.lyclaw.reflect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 检测到的错误实体，用于反思引擎记录模型输出中的各类质量问题。
 *
 * <p><b>在反思系统中的角色</b>：当 {@link ReflectionEngine} 和 {@link QualityEvaluator}
 * 对模型的执行结果进行分析时，发现的具体问题被封装为 DetectedError 对象。
 * 每个 DetectedError 实例对应一个独立的、可定位的质量缺陷，多个 DetectedError
 * 组成错误列表被汇总在 {@link ReflectionReport} 中，供后续的策略调整器
 * ({@link StrategyAdjuster}) 参考以生成修正方案。</p>
 *
 * <p><b>错误类型体系</b>：通过内部的 {@link ErrorType} 枚举，将模型输出的常见问题
 * 分为六大类别，覆盖了从内容正确性到格式规范性的全方位质量监控：</p>
 * <ul>
 *   <li><b>HALLUCINATION（幻觉）</b> — 模型生成的内容与已知事实、上下文或外部知识不一致。
 *       典型场景包括编造不存在的 API、虚构数据、错误引用文档内容等。
 *       这是 LLM 应用中最需要重点监控的一类错误。</li>
 *   <li><b>LOGIC_CONTRADICTION（逻辑矛盾）</b> — 模型输出中存在自相矛盾的信息。
 *       例如在回答的开头说 A > B，在结尾却说 B > A；或先声明某功能已实现，
 *       后又建议去实现该功能。这类错误破坏了输出的可信度。</li>
 *   <li><b>TOOL_FAILURE_PATTERN（工具调用失败模式）</b> — 模型在工具调用中
 *       出现系统性的使用错误，如调用不存在的工具、传递错误的参数类型、
 *       忽略工具返回的错误信息继续执行等。这类错误需要调整工具描述或调用策略。</li>
 *   <li><b>INCOMPLETE_OUTPUT（输出不完整）</b> — 模型输出被截断、遗漏关键信息、
 *       或在中途意外终止。可能由 token 限制、超时、或模型自身决策不当导致。</li>
 *   <li><b>SAFETY_VIOLATION（安全违规）</b> — 输出包含不安全、有害、或
 *       违反使用策略的内容。包括但不限于：泄露敏感信息、生成恶意代码、
 *       提供危险建议等。这是优先级最高的错误类型，通常会触发人工介入。</li>
 *   <li><b>FORMAT_ERROR（格式错误）</b> — 输出不符合预期的结构化格式要求，
 *       如应该返回 JSON 却返回了纯文本、应该遵循 Markdown 模板却格式混乱、
 *       字段名称拼写错误等。这类错误影响下游组件的自动化解析。</li>
 * </ul>
 *
 * <p><b>字段说明</b>：每个错误实体包含以下诊断信息：
 * <ul>
 *   <li>{@code type} — 错误类型枚举，用于快速分类和路由处理策略</li>
 *   <li>{@code description} — 人类可读的错误描述，详细说明错误的具体表现</li>
 *   <li>{@code location} — 错误在输出文本中的位置标识（如行号、段落、JSON 路径等）</li>
 *   <li>{@code confidence} — 置信度（0.0~1.0），表示检测器对该错误判断的确定性程度</li>
 *   <li>{@code suggestion} — 修正建议，提供具体的修复方向或替代方案</li>
 * </ul>
 * </p>
 *
 * @see ReflectionReport 反思报告，汇总所有检测到的错误
 * @see QualityEvaluator 质量评估器，部分维度与错误类型对应
 * @see StrategyAdjuster 策略调整器，根据错误类型制定调整方案
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DetectedError {

    /**
     * 错误类型枚举，覆盖模型输出的常见质量问题。
     *
     * <p>六种错误类型的详细说明请参考类级别文档。
     * HALLUCINATION - 幻觉，生成内容与事实不符
     * LOGIC_CONTRADICTION - 逻辑矛盾，输出内容自相矛盾
     * TOOL_FAILURE_PATTERN - 工具调用失败模式，工具使用出现异常
     * INCOMPLETE_OUTPUT - 输出不完整，内容被截断或遗漏
     * SAFETY_VIOLATION - 安全违规，输出包含不安全内容
     * FORMAT_ERROR - 格式错误，输出不符合预期格式</p>
     */
    public enum ErrorType {
        HALLUCINATION, LOGIC_CONTRADICTION, TOOL_FAILURE_PATTERN,
        INCOMPLETE_OUTPUT, SAFETY_VIOLATION, FORMAT_ERROR
    }

    private ErrorType type;
    private String description;
    private String location;
    private double confidence;
    private String suggestion;
}
