package lyjew.com.lyclaw.reflect;

import lyjew.com.lyclaw.action.ActionResult;
import lyjew.com.lyclaw.context.ChatContext;

import java.util.List;

/**
 * 反思引擎接口，是 LyClaw 反思系统的核心入口和顶层门面。
 *
 * <p><b>在反思系统中的角色</b>：ReflectionEngine 是整个反思子系统的顶层协调者，
 * 它串联了质量评估（由 {@link QualityEvaluator} 提供）、错误检测和策略调整建议
 * （可内置或委托给 {@link StrategyAdjuster}）三个核心环节。
 * 在 LyClaw 的 Agent 执行循环中，每一次对话交互完成后，
 * Agent 执行器会调用 ReflectionEngine 对执行结果进行反思，
 * 决定是否需要修正计划、重试操作或切换到其他策略。</p>
 *
 * <p><b>反思引擎的核心能力</b>：</p>
 * <ol>
 *   <li><b>综合反思 (reflect)</b> — 对一次完整的执行结果进行全面评估，
 *       生成包含质量评分、错误列表和策略建议的综合反思报告。这是最常用的入口方法。</li>
 *   <li><b>质量评估 (assessQuality)</b> — 根据指定的质量标准对模型输出进行
 *       多维度评分，可用于独立的质量监控和统计分析。</li>
 *   <li><b>错误检测 (detectErrors)</b> — 检测模型输出中的各类错误，
 *       使用事实基准 (ground truth) 进行交叉验证。</li>
 *   <li><b>策略建议 (suggestAdjustment)</b> — 基于已生成的反思报告，
 *       进一步细化策略调整建议。</li>
 * </ol>
 *
 * <p><b>在 Agent 执行循环中的典型调用流程</b>：</p>
 * <pre>
 * // 1. Agent 执行任务
 * ActionResult result = agent.execute(task);
 *
 * // 2. 反思执行结果
 * ReflectionReport report = reflectionEngine.reflect(context, result);
 *
 * // 3. 根据反思结果决定下一步
 * if (report.getOverallScore() < 0.3) {
 *     // 质量太差，触发人工介入
 *     triggerHumanIntervention(report);
 * } else if (report.getOverallScore() < 0.7) {
 *     // 质量中等，应用策略调整后重试
 *     StrategyAdjustment adj = report.getSuggestion();
 *     applyAdjustment(adj);
 *     agent.retry(task);
 * } else {
 *     // 质量良好，继续下一个任务
 *     agent.continueNext(task);
 * }
 * </pre>
 *
 * <p><b>与子组件的协作关系</b>：
 * <ul>
 *   <li>内部组合了 {@link QualityEvaluator} 实现，负责从准确性、完整性、
 *       安全性、用户体验四个维度进行量化评分</li>
 *   <li>可选地组合了 {@link StrategyAdjuster} 实现，但也可以在自身内部
 *       直接根据报告生成调整建议</li>
 *   <li>产生的 {@link ReflectionReport} 供下游组件（如 Agent 执行器、
 *       监控仪表板、审计日志系统）消费</li>
 * </ul>
 *
 * <p><b>设计原则</b>：
 * <ul>
 *   <li><b>高内聚</b> — 反思引擎包含了反思所需的全部能力，外部只需调用即可</li>
 *   <li><b>低耦合</b> — 质量评估和策略调整的具体实现是可替换的，
 *       通过依赖注入配置不同的实现类</li>
 *   <li><b>无副作用</b> — 反思操作不应修改传入的上下文或结果对象，
 *       只生成分析报告</li>
 *   <li><b>快速失败</b> — 如果反思过程中出现异常，应返回默认的低分报告
 *       而非抛出异常中断 Agent 循环</li>
 * </ul>
 *
 * @see QualityEvaluator 质量评估器，提供四维评分能力
 * @see StrategyAdjuster 策略调整器，生成调整方案
 * @see ReflectionReport 反思报告，本接口的主要输出产物
 * @see QualityAssessment 质量评估结果，反思报告的核心子组件
 */
public interface ReflectionEngine {

    /**
     * 对一次完整的聊天执行结果进行综合反思，生成包含质量评估、错误列表和
     * 策略调整建议的完整反思报告。
     *
     * <p>此方法是反思引擎最主要的工作入口，它内部通常会依次执行以下步骤：
     * <ol>
     *   <li>从执行结果中提取模型输出文本和工具调用记录</li>
     *   <li>调用 {@link QualityEvaluator} 对输出进行四维质量评分</li>
     *   <li>检测输出中的各类错误（幻觉、逻辑矛盾、格式错误等）</li>
     *   <li>聚合所有诊断信息，生成 {@link ReflectionReport}</li>
     *   <li>可选地调用 {@link StrategyAdjuster} 生成初步调整建议</li>
     * </ol>
     * </p>
     *
     * @param context 聊天上下文，包含对话历史、会话信息、用户意图等。
     *                为反思提供判断输出质量的背景信息。不能为 {@code null}
     * @param result  一次 Agent 执行的结果，包含模型输出文本、工具调用记录、
     *                执行状态等。为反思的主要分析对象。不能为 {@code null}
     * @return {@link ReflectionReport} 综合反思报告，包含：
     *         <ul>
     *           <li>质量评估（准确性、完整性、安全性、用户体验四个维度）</li>
     *           <li>检测到的错误列表（含错误类型、描述、位置、修正建议）</li>
     *           <li>策略调整建议（含调整类型、原因、参数、优先级）</li>
     *           <li>综合评分和时间戳</li>
     *         </ul>
     */
    ReflectionReport reflect(ChatContext context, ActionResult result);

    /**
     * 根据指定的质量标准对模型输出文本进行独立的质量评估。
     *
     * <p>与 {@link #reflect(ChatContext, ActionResult)} 不同，
     * 此方法仅执行质量评估，不进行错误检测和策略建议，
     * 适用于只需要质量评分而无需完整反思报告的场景（如批量评估、A/B 测试）。</p>
     *
     * @param output   模型生成的输出文本，评估的原始材料。不能为 {@code null}
     * @param criteria 质量评估标准配置，可以定义各维度的权重、
     *                 评估方法的偏好（如使用语义相似度还是 n-gram 匹配）、
     *                 以及特定领域的评估规则。可以为 {@code null} 使用默认标准
     * @return {@link QualityAssessment} 质量评估结果，包含四个维度的分项评分和综合总分
     */
    QualityAssessment assessQuality(String output, QualityCriteria criteria);

    /**
     * 检测模型输出文本中的各类错误，使用提供的事实基准列表作为验证依据。
     *
     * <p>此方法独立于质量评估，专注于发现和定位具体的质量问题点。
     * 实现时通常使用多种检测策略组合：</p>
     * <ul>
     *   <li>将输出中的关键事实与 groundTruth 列表逐条比对，发现事实不一致</li>
     *   <li>使用正则模式匹配检测格式错误</li>
     *   <li>检查输出内部是否存在逻辑矛盾（前后陈述不一致）</li>
     *   <li>检测输出是否完整（是否在中间被截断）</li>
     * </ul>
     *
     * @param output      模型生成的输出文本，错误检测的扫描对象。不能为 {@code null}
     * @param groundTruth 事实基准文本列表，每条是一个独立的事实陈述。
     *                    用于验证输出中的事实准确性。可以为空列表，
     *                    此时错误检测仅限于格式和逻辑一致性的检查
     * @return 检测到的错误列表，按置信度从高到低排列。如果未检测到任何错误，
     *         返回空列表而非 {@code null}
     */
    List<DetectedError> detectErrors(String output, List<String> groundTruth);

    /**
     * 基于已生成的反思报告，进一步细化策略调整建议。
     *
     * <p>此方法可用于对已有报告的二次分析，或在不重新执行完整反思流程的情况下
     * 仅更新策略调整建议。实现时通常会综合考量报告中的质量评分、错误类型分布、
     * 以及时间戳（判断是否需要立即干预）。</p>
     *
     * @param report 已生成的反思报告，包含质量评估和错误列表等诊断信息。
     *               不能为 {@code null}
     * @return {@link StrategyAdjustment} 策略调整方案，包含调整类型、
     *         原因说明、附加参数和优先级。如果报告显示无需调整，
     *         返回一个 type 为 null 的方案对象
     */
    StrategyAdjustment suggestAdjustment(ReflectionReport report);
}
