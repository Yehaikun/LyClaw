package lyjew.com.lyclaw.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.NoArgsConstructor;

import java.util.*;

/**
 * 简单任务计划实现，由扁平的任务节点列表组成，是 {@link TaskPlan} 接口的默认实现类。
 *
 * <p><b>在规划系统中的角色</b>：SimpleTaskPlan 是使用最广泛的任务计划实现类，
 * 被 JSON 反序列化框架（Jackson）通过 {@code @JsonDeserialize(as = SimpleTaskPlan.class)}
 * 注解指定为 TaskPlan 接口的默认反序列化目标。
 * 无论是 DAG 规划器、CoT 规划器、还是层次化规划器，
 * 最终生成的任务节点列表都会被封装为 SimpleTaskPlan 对象返回。</p>
 *
 * <p><b>核心特性</b>：
 * <ul>
 *   <li><b>不可变节点列表</b> — 内部通过 {@code Collections.unmodifiableList}
 *       包装节点列表，确保计划创建后不会被外部代码意外修改。
 *       这保证了计划在执行过程中的稳定性和线程安全性。</li>
 *   <li><b>节点索引</b> — 除了线性列表外，还维护了一个从节点 ID 到节点对象的
 *       HashMap 索引，使得依赖查询（通过节点 ID 查找其依赖列表）的时间复杂度
 *       为 O(1)，而非遍历全列表的 O(n)。</li>
 *   <li><b>JSON 反序列化支持</b> — 通过 {@code @JsonCreator} 注解的构造函数，
 *       支持从 JSON 数据直接反序列化，适用于 REST API 的请求体解析和
 *       持久化后恢复场景。</li>
 *   <li><b>进度追踪支持</b> — 节点列表天然支持按顺序或并行执行，
 *       通过各节点的依赖关系可以推导出整个计划的执行拓扑顺序。</li>
 * </ul>
 *
 * <p><b>预估完成时间的计算</b>：SimpleTaskPlan 采用所有节点超时时间之和
 * （而非实际执行时间或关键路径长度）作为预估完成时间。这是一种简单但保守的估算策略：
 * <ul>
 *   <li>优点：计算简单（一次流式求和），不需要解析依赖图拓扑</li>
 *   <li>缺点：没有考虑并行执行带来的加速效应，在实际高度并行的场景中估值偏高</li>
 *   <li>适用场景：快速预览、资源预留估算，不适合精确进度预测</li>
 * </ul>
 *
 * <p><b>局限性</b>：SimpleTaskPlan 本身是一个扁平结构，不直接支持嵌套的计划
 * （计划中嵌套子计划）。对于需要多层嵌套的复杂场景，
 * 节点之间的依赖关系（通过 TaskNode.dependencies 字段）充当了隐式的层次结构。
 * 更高级的计划实现可以基于 SimpleTaskPlan 扩展，添加子计划支持和更丰富的拓扑操作。</p>
 *
 * @see TaskPlan 计划接口，定义了计划的通用契约
 * @see TaskNode 任务节点，构成计划的基本执行单元
 */
@NoArgsConstructor
public class SimpleTaskPlan implements TaskPlan {

    /** 不可变的任务节点列表，保证创建后不被外部修改 */
    private List<TaskNode> nodes = List.of();
    /** 节点ID到节点对象的快速索引映射，支持 O(1) 的依赖查询 */
    private Map<String, TaskNode> index = Map.of();

    /**
     * JSON反序列化构造函数，接收节点列表并构建索引。
     *
     * <p>此构造函数通过 {@code @JsonCreator} 和 {@code @JsonProperty} 注解
     * 被 Jackson 框架用于 JSON 反序列化。构造过程中执行以下初始化步骤：</p>
     * <ol>
     *   <li>将传入的节点列表包装为不可变列表，防止外部修改</li>
     *   <li>遍历所有节点，构建节点 ID → 节点对象的快速查找索引</li>
     *   <li>若传入 null，则初始化为空的不可变列表和空的索引映射</li>
     * </ol>
     *
     * @param nodes 任务节点列表，用于初始化计划的节点集合。
     *              可以为 {@code null}，此时计划为空（isReady() 返回 false）
     */
    @JsonCreator
    public SimpleTaskPlan(@JsonProperty("nodes") List<TaskNode> nodes) {
        // 存储为不可变列表，防止外部修改
        this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes != null ? nodes : List.of()));
        this.index = new HashMap<>();
        // 构建节点ID到节点的快速查找索引
        for (TaskNode node : this.nodes) {
            index.put(node.getNodeId(), node);
        }
    }

    @Override
    public List<TaskNode> getNodes() { return nodes; }

    @Override
    public List<String> getDependencies(String nodeId) {
        TaskNode node = index.get(nodeId);
        return node != null ? node.getDependencies() : List.of();
    }

    /**
     * 估算完成所有任务所需的总时间。
     *
     * <p>计算方法：将所有节点的超时时间累加求和。
     * 这是一种保守估算，没有考虑并行执行带来的时间节省。
     * 对于高度并行的计划，实际执行时间通常远小于此估算值。</p>
     *
     * @return 预估总耗时（毫秒），所有节点超时时间的代数和
     */
    @Override
    public long getEstimatedCompletionTime() {
        return nodes.stream().mapToLong(TaskNode::getTimeoutMs).sum();
    }

    /**
     * 判断计划是否已就绪可执行。
     *
     * <p>就绪条件：节点列表非空。只要至少有一个节点就视为可执行。
     * 空计划（刚创建或节点全部完成）返回 false。</p>
     *
     * @return {@code true} 如果节点列表非空
     */
    @Override
    public boolean isReady() { return !nodes.isEmpty(); }
}
