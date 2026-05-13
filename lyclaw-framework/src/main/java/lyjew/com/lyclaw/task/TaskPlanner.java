package lyjew.com.lyclaw.task;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.AgentResult;

/**
 * 任务规划器接口，是任务规划系统的核心入口和策略抽象层。
 *
 * <p><b>在 LyClaw 架构中的角色</b>：TaskPlanner 是连接用户意图与 Agent 执行的桥梁。
 * 它位于 LyClaw 的"计划层" (Plan Layer)，上游接收来自 REST 控制器或 Agent 管理器的
 * 规划请求，下游将生成的任务计划传递给执行引擎 (Execution Engine) 进行调度。
 * 规划器的质量直接影响 Agent 系统的执行效率和结果准确性。</p>
 *
 * <p><b>五大核心能力</b>：</p>
 * <ol>
 *   <li><b>计划生成 (plan)</b> — 根据对话上下文和用户意图，自动分析和拆解任务，
 *       生成包含有序任务节点及其依赖关系的执行计划。这是规划器最主要的能力。</li>
 *   <li><b>计划修订 (revise)</b> — 接收反思引擎的反馈，对已有计划进行修改：
 *       重新排序节点、插入缺失步骤、替换不合适的策略等。
 *       这实现了"执行 → 反思 → 修订 → 再执行"的闭环改进机制。</li>
 *   <li><b>计划优化 (optimize)</b> — 基于上一轮执行的实际结果，
 *       对计划进行优化调整。与修订不同，优化关注的是"如何做得更好"而非"哪里错了"。
 *       例如通过分析执行耗时分布来调整并行度，或根据工具调用成功率来替换工具选择。</li>
 *   <li><b>任务分解 (decompose)</b> — 将一个宏观的根任务按指定策略拆分为
 *       有向图结构（PlanGraph）。不同的分解策略适用于不同类型的任务：
 *       DAG 适合结构化任务、链条式思考 (CoT) 适合推理密集型任务、
 *       ReAct 适合交互式任务、层次化适合大型复杂项目。</li>
 * </ol>
 *
 * <p><b>多种实现策略</b>：TaskPlanner 接口支持多种规划策略的实现，
 * Spring 容器中注册了以下实现 Bean：
 * <ul>
 *   <li><b>DAGTaskPlanner</b>（默认） — 基于有向无环图的智能规划器。
 *       根据任务复杂度自动选择单节点/线性/并行策略，支持依赖关系推导。</li>
 *   <li><b>cotPlanner</b>（可选） — 链式思考 (Chain-of-Thought) 规划器。
 *       引导 LLM 逐步推理，生成思考链，适合需要深度推理的复杂问题。</li>
 *   <li><b>reActPlanner</b>（可选） — 推理-行动 (Reasoning-Acting) 循环规划器。
 *       交替进行推理和工具调用，适合需要与外部环境交互的任务。</li>
 *   <li><b>hierarchicalPlanner</b> — 层次化任务网络 (HTN) 规划器。
 *       自顶向下三层分解（目标→步骤→原子操作），适合大规模复杂项目。</li>
 * </ul>
 *
 * <p><b>设计原则</b>：
 * <ul>
 *   <li><b>策略模式 (Strategy Pattern)</b> — 通过接口抽象多种规划算法，
 *       运行时通过依赖注入选择具体实现，新增规划策略无需修改调用方代码</li>
 *   <li><b>幂等性</b> — 相同的输入应产生相同的计划，保证规划结果的可复现性</li>
 *   <li><b>非阻塞</b> — 规划操作应快速完成（毫秒级），不应在规划阶段进行
 *       耗时的 LLM 调用或外部 API 请求</li>
 *   <li><b>容错性</b> — 即使输入信息不完整（如 context 为 null、
 *       userIntent 为空），也应返回一个合理的默认计划而非抛出异常</li>
 * </ul>
 *
 * @see TaskPlan 任务计划，规划器的输出产物
 * @see TaskNode 任务节点，计划的基本组成单元
 * @see PlanGraph 计划图，分解操作的输出载体
 * @see DecompositionStrategy 分解策略枚举，控制任务的拆分方式
 */
public interface TaskPlanner {

    /**
     * 根据聊天上下文自动生成任务计划。
     *
     * <p>此重载方法在仅有上下文而无明确用户意图时使用。
     * 规划器从上下文中提取最后一条用户消息作为意图，
     * 然后委托给 {@link #plan(ChatContext, String)} 完成实际规划。</p>
     *
     * @param context 聊天上下文，包含对话历史、会话信息和用户消息。
     *                不能为 {@code null}，否则规划器无法提取任何意图
     * @return 生成的任务计划，至少包含一个默认节点
     */
    TaskPlan plan(ChatContext context);

    /**
     * 根据聊天上下文和指定的用户意图生成任务计划。
     *
     * <p>这是规划器的主要入口方法。规划器根据意图的复杂度评估结果，
     * 自动选择适当的计划粒度：简单意图生成单节点计划，
     * 复杂意图生成多层级的 DAG 计划。</p>
     *
     * @param context    聊天上下文，提供对话历史、会话信息、记忆内容等辅助信息。
     *                   可以为 {@code null}，此时规划器仅基于用户意图规划
     * @param userIntent 用户意图描述，自然语言文本表达的任务目标。
     *                   可以为 {@code null} 或空字符串，此时规划器从上下文中提取意图
     * @return 生成的任务计划，包含至少一个任务节点
     */
    TaskPlan plan(ChatContext context, String userIntent);

    /**
     * 根据反思引擎的反馈修订现有任务计划。
     *
     * <p>修订行为由反馈中的信息决定：
     * <ul>
     *   <li>质量评分过低 → 完全重新规划</li>
     *   <li>检测到顺序错误 → 重排序节点</li>
     *   <li>检测到缺失步骤 → 插入新节点</li>
     * </ul>
     * </p>
     *
     * @param original 原始任务计划，需要被修订的对象。可以为 {@code null}
     * @param feedback 反思反馈信息，提供修改方向和具体建议。可以为 {@code null}
     * @return 修订后的任务计划。如果无需修订（original 或 feedback 为 null、
     *         或反馈未触发任何修订条件），则直接返回原始计划
     */
    TaskPlan revise(TaskPlan original, ReflectionFeedback feedback);

    /**
     * 基于上一次执行的实际结果优化任务计划。
     *
     * <p>与修订不同，优化关注的是效率提升而非错误修正。
     * 例如分析执行耗时来调整并行度、根据工具调用成功率替换工具选择等。</p>
     *
     * @param previousResult 上一次执行的结果，包含输出摘要、状态和耗时等。
     *                       可以为 {@code null}，此时无法优化，返回 {@code null}
     * @return 优化后的任务计划，若无法生成优化计划则返回 {@code null}
     */
    TaskPlan optimize(AgentResult previousResult);

    /**
     * 使用指定分解策略将根任务分解为有向图结构的子任务。
     *
     * <p>支持六种分解策略：
     * <ul>
     *   <li>{@code SEQUENTIAL} — 按顺序 A→B→C 线性链式分解</li>
     *   <li>{@code BY_DOMAIN} — 按知识领域分组，同领域串行不同领域并行</li>
     *   <li>{@code BY_PHASE} — 分析→设计→实现→验证 四阶段流水线</li>
     *   <li>{@code PARALLEL_INDEPENDENT} — 识别独立子任务并全部并行</li>
     *   <li>{@code LLM_DRIVEN} — 委托 LLM 决定分解方式</li>
     *   <li>{@code TREE} — 树形递归分解</li>
     * </ul>
     * </p>
     *
     * @param rootTask 根任务节点，作为分解的起点和所有子节点的祖先。
     *                 不能为 {@code null}
     * @param strategy 分解策略枚举，控制子任务的拆分方式和依赖关系。
     *                 不能为 {@code null}
     * @return 分解后的 PlanGraph，包含根节点和所有子节点及其边关系
     */
    PlanGraph decompose(TaskNode rootTask, DecompositionStrategy strategy);
}
