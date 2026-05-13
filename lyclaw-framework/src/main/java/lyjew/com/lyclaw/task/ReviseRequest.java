package lyjew.com.lyclaw.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 计划修订请求实体，封装触发计划修订操作所需的全部信息。
 *
 * <p><b>在规划系统中的角色</b>：ReviseRequest 是计划修订链路的标准输入对象，
 * 由反思系统或外部调用方（如 REST 控制器 {@code PlanController}、质量监控系统、
 * 或人工审核界面）构造，传递给 {@link TaskPlanner#revise(TaskPlan, ReflectionFeedback)}。
 * 它将"当前需要修改的计划"、"修改原因"和"修改方向"三个核心信息封装在一起，
 * 使修订操作具备完整的决策依据。</p>
 *
 * <p><b>三大核心字段</b>：
 * <ul>
 *   <li><b>currentPlan（当前计划）</b> — 需要被修订的原始任务计划对象。
 *       这是修订操作的直接操作对象。修订器会分析此计划的结构，
 *       并根据反馈和原因决定如何修改节点、调整依赖关系或变更策略。</li>
 *   <li><b>feedback（反馈文本）</b> — 描述当前计划存在的问题或改进方向。
 *       这是修订操作的核心驱动力，通常来源于反思引擎的质量评估结果
 *       或用户的直接反馈。反馈文本可以包含具体的修改建议
 *       （如"步骤3和步骤4的顺序需要调换"），也可以包含抽象的改进方向
 *       （如"计划过于简单，需要增加验证步骤"）。
 *       在 PlanController 的实现中，此字段被映射到
 *       {@link ReflectionFeedback#getSuggestedStrategy()} 和
 *       {@link ReflectionFeedback#getAdjustedPrompt()}。</li>
 *   <li><b>reason（修订原因）</b> — 解释为什么要进行修订的补充说明文本。
 *       与 feedback 不同，reason 侧重于解释"为什么需要修订"而非"如何修订"。
 *       例如："上一轮执行中步骤2的工具调用失败了"、
 *       "用户反馈输出结果与预期不符"、"反思引擎评分为0.2，质量不达标"。
 *       在 PlanController 的实现中，此字段被映射到
 *       {@link ReflectionFeedback#getAdjustedPrompt()}。</li>
 * </ul>
 *
 * <p><b>典型的修订工作流</b>：</p>
 * <pre>
 * // 1. 反思引擎评估执行结果
 * ReflectionReport report = reflectionEngine.reflect(context, result);
 *
 * // 2. 构建修订请求
 * ReviseRequest request = ReviseRequest.builder()
 *     .currentPlan(originalPlan)
 *     .feedback("需要增加验证步骤并调整步骤顺序")
 *     .reason("质量评分 0.35，存在逻辑矛盾错误")
 *     .build();
 *
 * // 3. 执行修订
 * TaskPlan revised = taskPlanner.revise(request.getCurrentPlan(), feedback);
 * </pre>
 *
 * <p><b>与 ReflectionFeedback 的关系</b>：ReviseRequest 是外部 API 层的请求对象，
 * {@link ReflectionFeedback} 是规划器内部使用的修订输入对象。
 * 在 PlanController 中，ReviseRequest 的 feedback 和 reason 字段被映射到
 * ReflectionFeedback 的 suggestedStrategy 和 adjustedPrompt 字段，
 * 完成从 API 层到业务层的转换。</p>
 *
 * @see TaskPlanner#revise(TaskPlan, ReflectionFeedback) 修订方法，接收此类信息执行修订
 * @see ReflectionFeedback 反思反馈，规划器内部用的修订输入对象
 * @see TaskPlan 任务计划，被修订的目标对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviseRequest {
    /** 需要被修订的当前任务计划 */
    private TaskPlan currentPlan;
    /** 反馈文本，描述存在的问题或改进方向 */
    private String feedback;
    /** 修订原因，解释为什么需要进行修改 */
    private String reason;
}
