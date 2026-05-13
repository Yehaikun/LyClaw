package lyjew.com.lyclaw.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 计划请求实体，封装触发任务规划所需的全部信息。
 *
 * <p><b>在规划系统中的角色</b>：PlanRequest 是规划链路的标准输入对象，
 * 由调用方（如 REST 控制器 {@code PlanController}、Agent 执行器、
 * 或定时任务调度器）构造，传递给 {@link TaskPlanner#plan(ChatContext, String)}
 * 或其实现类。它包含了规划器完成一次完整的任务计划生成所需的全部参数。</p>
 *
 * <p><b>四大请求参数</b>：
 * <ul>
 *   <li><b>sessionId（会话标识）</b> — 标识此次规划请求所属的对话会话。
 *       sessionId 是跨请求关联上下文的关键字段，规划器通过它从记忆系统中
 *       检索相关的对话历史、短期记忆和实体信息，用于辅助规划决策。
 *       在多轮对话中，同一个 sessionId 下的多个规划请求共享相同的上下文。</li>
 *   <li><b>userIntent（用户意图）</b> — 用户通过自然语言表达的原始任务意图。
 *       这是规划器最主要的信息输入，描述了用户希望完成的目标。
 *       意图文本的复杂度直接影响规划器生成的计划粒度：
 *       简单意图（如"查询今天的天气"）产生单节点计划，
 *       复杂意图（如"将整个项目从 MySQL 迁移到 PostgreSQL"）产生
 *       多层级的 DAG 计划。</li>
 *   <li><b>strategy（分解策略）</b> — 指定用于分解任务的策略名称。
 *       可选值包括 "dag"（默认 DAG 规划）、"cot"（链式思考规划）、
 *       "react"（ReAct 推理-行动循环）、"hierarchical"（层次化规划）。
 *       如果为 null 或空字符串，规划器使用其默认策略。
 *       不同策略适用于不同类型的任务：DAG 适合结构化任务，
 *       CoT 适合推理密集型任务，ReAct 适合需要工具交互的任务，
 *       Hierarchical 适合大型复杂项目。</li>
 *   <li><b>context（附加上下文）</b> — 键值对形式的额外上下文信息，
 *       可以包含特定领域的约束条件、用户偏好设置、环境变量、
 *       以及之前执行的结果摘要等。规划器将其作为辅助信息融入计划生成逻辑。
 *       该字段为可选字段，大多数场景下可以为 null。</li>
 * </ul>
 *
 * <p><b>与 ChatContext 的关系</b>：PlanRequest 是原始的请求对象，
 * 在进入规划器处理之前，通常会先被转换为更完整的 {@code ChatContext} 对象。
 * {@code ChatContext} 通过从记忆系统加载对话历史、从实体存储加载用户偏好、
 * 以及装配拦截器链和聊天门面等方式，将 PlanRequest 中的基础信息丰富化为
 * 规划器所需的完整上下文。</p>
 *
 * @see TaskPlanner 任务规划器接口，接收此类或其衍生信息进行规划
 * @see TaskPlan 任务计划，规划器处理后的输出
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanRequest {
    /** 会话标识，关联此次规划请求与特定会话 */
    private String sessionId;
    /** 用户意图文本，规划的核心输入 */
    private String userIntent;
    /** 分解策略名称（dag/cot/react/hierarchical），为空时使用默认策略 */
    private String strategy;
    /** 附加的上下文字段，键值对形式的辅助信息（可选） */
    private Map<String, Object> context;
}
