package lyjew.com.lyclaw.reflect;

/**
 * 策略调整器接口，位于反思系统的决策层，负责将反思报告的诊断结论转化为可执行的策略调整方案。
 *
 * <p><b>在反思链路中的角色</b>：反思系统的工作流程为
 * "执行 → 质量评估 → 错误检测 → 生成反思报告 → 策略调整"，
 * StrategyAdjuster 是这一链路的最后一环，也是将诊断转化为行动的关键节点。
 * 它的输入是包含质量评分、错误列表、综合评估的 {@link ReflectionReport}，
 * 输出是包含具体调整类型、参数和优先级的 {@link StrategyAdjustment}。</p>
 *
 * <p><b>设计动机</b>：将诊断和决策分离是反思系统中一个重要的架构原则。
 * {@link ReflectionEngine} 和 {@link QualityEvaluator} 负责"发现问题"，
 * StrategyAdjuster 负责"决定如何修正"。这种分离使得：
 * <ul>
 *   <li>诊断逻辑和决策逻辑可以独立演化和测试</li>
 *   <li>不同的智能体或场景可以使用不同的调整策略（如保守型 vs 激进型）</li>
 *   <li>调整策略可以动态切换，而无需修改反思引擎本身</li>
 * </ul>
 * </p>
 *
 * <p><b>调整策略的分类</b>：根据反思报告的严重程度，策略调整通常分为几个层次：
 * <ul>
 *   <li><b>微调 (Fine-tuning)</b> — 报告显示质量尚可但有改进空间时，
 *       可能降低 LLM 温度参数 (REDUCE_TEMPERATURE) 使输出更确定，
 *       或附加补充上下文 (RETRY_WITH_CONTEXT)</li>
 *   <li><b>策略切换 (Strategy Switch)</b> — 当当前规划策略效果不佳时，
 *       可能从串行切换到并行 (SWITCH_PLAN_STRATEGY)，
 *       或切换到层次化分解以提高计划粒度</li>
 *   <li><b>提示重写 (Prompt Rewrite)</b> — 当输出出现系统性偏差时，
 *       重写提示词 (REWRITE_PROMPT) 以更精确地引导 LLM 行为</li>
 *   <li><b>人工介入 (Human Intervention)</b> — 当质量评分极低或检测到安全违规时，
 *       触发人工审核流程 (TRIGGER_HUMAN_INTERVENTION)</li>
 * </ul>
 * </p>
 *
 * <p><b>与反思报告的协作</b>：StrategyAdjuster 在生成调整方案时应综合考量
 * 报告中的多个信号：整体质量分数决定调整的激进程度，
 * 具体错误类型决定调整的方向（如幻觉 → 降低温度 + 增加事实检索工具），
 * 时间戳用于评估是否需要立即干预还是可以延迟处理。</p>
 *
 * @see ReflectionReport 反思报告，提供调整决策所需的诊断信息
 * @see StrategyAdjustment 策略调整方案，包含具体的调整类型和参数
 * @see ReflectionEngine 反思引擎，产生反思报告的上游组件
 */
public interface StrategyAdjuster {

    /**
     * 根据反思报告的结论，分析诊断信息并生成有针对性的策略调整方案。
     *
     * <p>实现此方法的类需要解析反思报告中的多项关键指标：
     * <ul>
     *   <li><b>整体质量评分 (overallScore)</b> — 质量评分越低，调整的激进程度越高。
     *       当评分低于 0.3 时可能需要直接触发人工介入；
     *       评分在 0.3~0.7 之间时适合进行策略切换或提示重写；
     *       评分在 0.7 以上时可能只需要微调参数。</li>
     *   <li><b>错误类型分布 (errors)</b> — 不同错误类型对应不同的调整策略。
     *       如幻觉类错误需要增加事实检索工具调用或降低温度；
     *       格式错误需要重写提示词中的格式约束；
     *       不完整输出需要调整 max_tokens 或增加补充说明提示。</li>
     *   <li><b>各维度质量分项 (quality)</b> — 通过准确性、完整性、安全性、
     *       用户体验四个维度的分项评分识别最薄弱环节，针对性地调整。</li>
     *   <li><b>已有策略建议 (suggestion)</b> — 如果报告本身已经包含初步建议，
     *       调整器应在此基础上进行细化和参数化。</li>
     * </ul>
     * </p>
     *
     * <p><b>幂等性</b>：对于相同的输入反思报告，同一实现应始终生成相同的调整方案。
     * 此方法不应有副作用（如直接修改全局状态或触发外部调用），
     * 其职责仅为计算并返回调整方案，具体的执行由调用方负责。</p>
     *
     * @param report 反思报告，包含一次反思会话的完整诊断信息。
     *               包括质量评估 ({@link QualityAssessment})、
     *               检测到的错误列表 ({@link DetectedError})、
     *               综合评分 ({@code overallScore})、
     *               会话标识 ({@code sessionId}) 和时间戳 ({@code timestamp})。
     *               参数不能为 {@code null}。
     * @return {@link StrategyAdjustment} 策略调整方案对象，包含：
     *         <ul>
     *           <li>{@code type} — 调整类型枚举值（如 REWRITE_PROMPT、SWITCH_PLAN_STRATEGY 等）</li>
     *           <li>{@code reason} — 调整原因的文字说明，便于人工审核和日志追踪</li>
     *           <li>{@code parameters} — 调整的附加参数（如新提示词文本、新策略名称等）</li>
     *           <li>{@code priority} — 调整优先级（0.0~1.0），数值越高表示越需要立即执行</li>
     *         </ul>
     *         如果反思报告显示无需调整，应返回一个 type 为 null 或参数为空的方案对象。
     */
    StrategyAdjustment adjust(ReflectionReport report);
}
