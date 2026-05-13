package lyjew.com.lyclaw.task;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * 任务计划接口，定义任务计划的通用契约，是规划系统的核心抽象。
 *
 * <p><b>在规划系统中的角色</b>：TaskPlan 是所有任务计划实现类必须遵循的统一接口。
 * 它抽象了计划的基本能力：获取节点列表、查询依赖关系、估算完成时间和判断就绪状态。
 * 通过接口抽象，不同规划策略产生的不同计划结构（线性计划、DAG 计划、层次化计划）
 * 都可以通过统一的 API 进行访问和操作。</p>
 *
 * <p><b>设计动机</b>：任务规划系统中存在多种规划策略（DAG、CoT、ReAct、层次化），
 * 每种策略可能产生不同内部表示的计划对象。通过 TaskPlan 接口将计划的使用方
 * （如 Agent 执行器、进度追踪器、计划校验器）与具体的计划实现解耦，
 * 使得新增规划策略时无需修改执行引擎的代码。
 * 这是面向接口编程原则在规划层的具体体现。</p>
 *
 * <p><b>四个核心契约方法</b>：
 * <ul>
 *   <li><b>getNodes()</b> — 获取计划中的所有任务节点。这是最基本的信息获取接口，
 *       执行引擎通过迭代节点列表来逐个或并行执行任务。</li>
 *   <li><b>getDependencies(String nodeId)</b> — 查询指定节点的前置依赖。
 *       执行引擎在执行每个节点前必须通过此方法检查依赖是否已全部完成，
 *       这是保证任务执行正确顺序的关键。</li>
 *   <li><b>getEstimatedCompletionTime()</b> — 预估完成时间。
 *       用于前端展示预计完成时间、资源预留规划、以及 SLA 监控。</li>
 *   <li><b>isReady()</b> — 判断计划是否处于可执行状态。
 *       空计划（没有节点）返回 false，有至少一个节点返回 true。</li>
 * </ul>
 *
 * <p><b>JSON 反序列化</b>：通过 {@code @JsonDeserialize(as = SimpleTaskPlan.class)}
 * 注解，Jackson 在反序列化 TaskPlan 类型的 JSON 数据时，
 * 会自动使用 {@link SimpleTaskPlan} 作为具体实现类。
 * 这意味着 REST API 的请求体和响应体中的 TaskPlan 字段可以直接序列化和反序列化，
 * 无需额外的类型适配器。</p>
 *
 * <p><b>扩展点</b>：虽然当前只有 SimpleTaskPlan 一个实现，
 * 但接口的设计支持未来的扩展方向：
 * <ul>
 *   <li><b>流式计划 (StreamingPlan)</b> — 支持边规划边执行，
 *       而非等全部规划完成再执行</li>
 *   <li><b>条件计划 (ConditionalPlan)</b> — 支持 if-else 条件分支的节点路径</li>
 *   <li><b>动态计划 (DynamicPlan)</b> — 支持运行时根据执行结果动态添加或删除节点</li>
 *   <li><b>嵌套计划 (NestedPlan)</b> — 支持计划中嵌套子计划，实现递归分解</li>
 * </ul>
 *
 * @see SimpleTaskPlan 默认实现，基于扁平节点列表
 * @see TaskNode 任务节点，计划的组成元素
 * @see TaskPlanner 任务规划器接口，生产 TaskPlan 对象的工厂
 */
@JsonDeserialize(as = SimpleTaskPlan.class)
public interface TaskPlan {

    /**
     * 获取计划中所有的任务节点列表。
     *
     * <p>返回的列表顺序不一定代表执行顺序。执行顺序由各节点的依赖关系
     * (dependencies) 决定，而非列表中的位置。实现应保证返回的列表是不可变的
     * 或在每次调用时返回新的防御性拷贝，防止外部修改影响计划的一致性。</p>
     *
     * @return 任务节点列表，如果计划为空则返回空列表而非 {@code null}
     */
    List<TaskNode> getNodes();

    /**
     * 获取指定节点的前置依赖节点 ID 列表。
     *
     * <p>返回的 ID 列表中的每个 ID 都是该节点执行前必须完成的节点标识。
     * 如果指定节点 ID 在计划中不存在，应返回空列表而非抛异常，
     * 以简化调用方的错误处理逻辑。</p>
     *
     * @param nodeId 要查询依赖的节点 ID，不能为 {@code null}
     * @return 依赖节点 ID 列表，无依赖或节点不存在时返回空列表
     */
    List<String> getDependencies(String nodeId);

    /**
     * 估算完成整个计划所需的预计时间（毫秒）。
     *
     * <p>注意：这是预估值而非精确值。不同实现有不同估算策略：
     * 简单实现可能直接求和所有节点的超时时间，
     * 高级实现可能基于关键路径 (critical path) 进行更精确的估算。
     * 该值主要用于 UI 展示和资源规划，不应作为执行超时的硬性限制。</p>
     *
     * @return 预估完成时间（毫秒），空计划应返回 0
     */
    long getEstimatedCompletionTime();

    /**
     * 判断当前计划是否已就绪，可开始执行。
     *
     * <p>基础的就绪条件：节点列表非空。
     * 高级实现可能增加额外的就绪条件，如所有资源已就绪、环境已配置等。</p>
     *
     * @return {@code true} 如果计划就绪可执行，{@code false} 否则
     */
    boolean isReady();
}
