package lyjew.com.lyclaw.reflect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 策略调整实体，描述反思引擎对当前执行策略的具体调整建议。
 *
 * <p><b>在反思系统中的角色</b>：StrategyAdjustment 是反思链路中"决策"阶段的输出产物，
 * 由 {@link StrategyAdjuster} 根据 {@link ReflectionReport} 中的诊断信息生成。
 * 它封装了一个具体的、可执行的调整动作，包含做什么、为什么做、怎么做、以及优先做。
 * 调用方（如 Agent 执行器）根据此对象的指示实施实际的策略变更。</p>
 *
 * <p><b>七种调整类型及适用场景</b>：
 * <ul>
 *   <li><b>REWRITE_PROMPT（重写提示词）</b> — 当检测到输出存在系统性的理解偏差、
 *       格式错误或遗漏关键步骤时，调整器会生成新的提示词文本并通过 parameters
 *       传递。新提示词通常会增强约束描述、添加格式示例、或明确禁止特定行为。
 *       这是最常用的调整类型，适用于大多数质量问题。</li>
 *   <li><b>SWITCH_PLAN_STRATEGY（切换计划策略）</b> — 当当前的任务规划策略
 *       （如 SEQUENTIAL 串行）被证明效率低下或产生了错误的任务依赖关系时，
 *       切换到另一种策略（如改为 BY_PHASE 按阶段分解、或 PARALLEL_INDEPENDENT
 *       并行独立分解）。parameters 中包含新策略的名称。</li>
 *   <li><b>ADD_TOOL_CALL（增加工具调用）</b> — 当检测到输出缺乏关键信息、
 *       或存在幻觉（HALLUCINATION）时，建议增加特定工具的调用以获取事实验证。
 *       例如建议增加 web_search 来验证事实、增加 file_read 来读取相关代码等。
 *       parameters 中包含建议添加的工具名称和调用参数。</li>
 *   <li><b>REDUCE_TEMPERATURE（降低温度）</b> — 当输出过于发散、包含幻觉、
 *       或格式不稳定时，适当降低 LLM 的温度参数（如从 0.7 降到 0.3），
 *       使输出更加确定和一致。适用于需要精确结果的任务场景。</li>
 *   <li><b>INCREASE_TEMPERATURE（提高温度）</b> — 当输出过于保守、缺乏创意、
 *       或始终给出相同的模式化回复时，适当提高 LLM 的温度参数（如从 0.3 升到 0.7），
 *       增加输出的多样性和创造性。适用于头脑风暴、创意生成等开放任务。</li>
 *   <li><b>TRIGGER_HUMAN_INTERVENTION（触发人工介入）</b> — 当检测到安全违规
 *       （SAFETY_VIOLATION）、持续的高置信度幻觉、或质量评分极低且自动修正无效时，
 *       触发人工审核和处理流程。这是优先级最高的调整类型，通常会将任务挂起
 *       等待人工决策。</li>
 *   <li><b>RETRY_WITH_CONTEXT（携带上下文重试）</b> — 当输出不完整或未达到预期
 *       但质量尚可时，将当前执行的结果和诊断信息作为附加上下文字段重新提交给 LLM，
 *       请求其在已有基础上继续完善。parameters 中包含附加上下文的文本。</li>
 * </ul>
 *
 * <p><b>字段说明</b>：
 * <ul>
 *   <li>{@code type} — 调整类型枚举，决定了后续执行的动作类别</li>
 *   <li>{@code reason} — 人类可读的调整原因说明，记录为什么需要此调整，
 *       便于日志追踪和人工审核</li>
 *   <li>{@code parameters} — 调整动作所需的附加参数键值对，
 *       如新提示词文本、新策略名称、工具调用参数、温度值等</li>
 *   <li>{@code priority} — 调整优先级（0.0~1.0），数值越高表示越需要立即执行。
 *       TRIGGER_HUMAN_INTERVENTION 类型的调整通常具有最高优先级（接近 1.0）</li>
 * </ul>
 *
 * @see StrategyAdjuster 策略调整器接口，生成此实体的组件
 * @see ReflectionReport 反思报告，提供调整决策依据
 * @see DetectedError 检测到的错误，错误类型影响调整类型的选择
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyAdjustment {

    /**
     * 策略调整类型枚举，定义反思系统支持的七种具体调整动作。
     *
     * <p>每种类型的详细说明和适用场景请参考类级别文档。</p>
     * <ul>
     *   <li>REWRITE_PROMPT - 重写提示词</li>
     *   <li>SWITCH_PLAN_STRATEGY - 切换计划策略（如从串行改为并行）</li>
     *   <li>ADD_TOOL_CALL - 增加工具调用</li>
     *   <li>REDUCE_TEMPERATURE - 降低温度参数（使输出更确定）</li>
     *   <li>INCREASE_TEMPERATURE - 提高温度参数（增加创意性）</li>
     *   <li>TRIGGER_HUMAN_INTERVENTION - 触发人工介入</li>
     *   <li>RETRY_WITH_CONTEXT - 携带上下文重试</li>
     * </ul>
     */
    public enum AdjustmentType {
        REWRITE_PROMPT, SWITCH_PLAN_STRATEGY, ADD_TOOL_CALL,
        REDUCE_TEMPERATURE, INCREASE_TEMPERATURE,
        TRIGGER_HUMAN_INTERVENTION, RETRY_WITH_CONTEXT
    }

    /** 调整类型，决定执行何种调整动作 */
    private AdjustmentType type;
    /** 调整原因的说明文本 */
    private String reason;
    /** 调整动作所需的附加参数键值对 */
    private Map<String, Object> parameters;
    /** 调整优先级（0.0~1.0），数值越高越紧急 */
    private double priority;
}
