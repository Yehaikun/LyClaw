package lyjew.com.lyclaw.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务节点，表示任务计划中的单个可执行单元，是任务计划图 (PlanGraph) 的基本构成元素。
 *
 * <p><b>在规划系统中的角色</b>：TaskNode 是计划系统中最小粒度的执行单元。
 * 一个完整的任务计划由多个 TaskNode 构成，它们通过依赖关系 (dependencies)
 * 形成有向无环图 (DAG) 的拓扑结构。每个 TaskNode 封装了一个独立操作的完整元数据：
 * 要做什么（type + description）、需要什么工具（requiredTools）、
 * 等什么前置条件（dependencies）、以及多久算超时（timeoutMs）。</p>
 *
 * <p><b>六大核心属性</b>：
 * <ul>
 *   <li><b>nodeId（节点唯一标识）</b> — 在计划图中唯一标识该节点的字符串。
 *       通常由前缀 + UUID 截断生成（如 "task-a1b2c3d4"、"hier-L1-0-L2-1-L3-0"）。
 *       节点 ID 被其他节点的 dependencies 列表引用，形成图的边。</li>
 *   <li><b>type（节点类型）</b> — 标识节点执行的操作类别。常见类型包括：
 *       <ul>
 *         <li>{@code ANALYZE} — 分析任务，通常涉及信息收集和需求理解</li>
 *         <li>{@code PLAN / DESIGN} — 规划或设计步骤</li>
 *         <li>{@code EXECUTE / IMPLEMENT} — 实际执行操作</li>
 *         <li>{@code VERIFY / VALIDATE} — 验证和校验结果</li>
 *         <li>{@code INTEGRATE / MERGE} — 合并多个分支的结果</li>
 *         <li>{@code OPTIMIZE} — 优化和性能调整</li>
 *         <li>{@code ATOMIC} — 不可再分的原子操作</li>
 *         <li>{@code DECOMPOSE} — 分解操作用节点</li>
 *         <li>{@code RESEARCH} — 调研和信息检索</li>
 *         <li>{@code PREPARE} — 资源准备和环境配置</li>
 *       </ul>
 *       类型影响调度器的执行策略（如 ANALYZE 可能分配推理型 Agent，EXECUTE 分配行动型 Agent）。</li>
 *   <li><b>description（节点描述）</b> — 自然语言描述该节点要完成的具体任务内容。
 *       这是 Agent 执行时的主要行为指引，也是 LLM 理解任务目标的核心文本。
 *       描述可以包含详细的操作指引、约束条件和期望输出格式。</li>
 *   <li><b>requiredTools（所需工具列表）</b> — 执行该节点时可能需要调用的工具名称列表。
 *       如 "web_search"、"file_read"、"code_executor"、"knowledge_search"、
 *       "validation" 等。Agent 会根据此列表提前加载工具定义，
 *       工具列表为空表示该任务不需要工具调用（纯 LLM 推理）。</li>
 *   <li><b>dependencies（依赖节点列表）</b> — 该节点执行前必须完成的前置节点 ID 列表。
 *       这是构建任务图拓扑结构的关键字段。只有当所有依赖节点都执行完毕后，
 *       该节点才能被调度执行。如果一个节点的所有依赖已完成且自身尚未执行，
 *       则该节点处于"就绪可执行"状态。依赖列表为空表示无前置依赖，可立即执行。</li>
 *   <li><b>timeoutMs（超时时间毫秒）</b> — 该节点允许的最大执行时间。
 *       超过此时间后节点被标记为超时失败，调度器根据配置决定重试或跳过。
 *       不同类型的节点通常有不同的默认超时：原子操作（30 秒）、标准执行（60 秒）、
 *       复杂分析（120 秒）。超时设置需要平衡完成概率和资源占用。</li>
 * </ul>
 *
 * <p><b>节点在计划图中的生命周期</b>：
 * <ol>
 *   <li><b>创建</b> — 由规划器（如 DAGTaskPlanner、HierarchicalPlanner）创建并加入计划图</li>
 *   <li><b>等待</b> — 等待所有依赖节点执行完成（dependencies 列表全部标记为 done）</li>
 *   <li><b>就绪</b> — 所有依赖已完成，等待调度器分配 Agent 执行</li>
 *   <li><b>执行中</b> — Agent 正在执行该节点的操作</li>
 *   <li><b>完成</b> — 执行成功，输出结果被记录</li>
 *   <li><b>失败</b> — 执行超时或发生错误，触发重试或回退策略</li>
 *   <li><b>跳过</b> — 被反思引擎判定为不需要执行（如已通过其他方式满足）</li>
 * </ol>
 *
 * @see TaskPlan 任务计划接口，TaskNode 的集合容器
 * @see PlanGraph 计划图，管理节点之间的拓扑关系
 * @see SimpleTaskPlan 简单任务计划实现，使用节点列表构成计划
 */
@NoArgsConstructor
public class TaskNode {

    /** 节点唯一标识，在计划图中作为主键使用 */
    private String nodeId;
    /** 节点类型，如 "ANALYZE", "EXECUTE", "VERIFY", "ATOMIC" 等 */
    private String type;
    /** 节点描述，详细说明该节点要完成的具体任务内容 */
    private String description;
    /** 执行该节点所需的工具名称列表，为空表示纯推理任务 */
    private List<String> requiredTools = List.of();
    /** 该节点依赖的前置节点ID列表，全部完成后本节点才能执行 */
    private List<String> dependencies = List.of();
    /** 该节点的最大执行时间（毫秒），超时后标记为失败 */
    private long timeoutMs;

    /**
     * JSON反序列化构造函数，所有字段通过 {@code @JsonProperty} 注解映射。
     *
     * <p>构造时对 requiredTools 和 dependencies 进行防御性 null 检查：
     * 如果传入 null，则初始化为空的不可变列表，避免后续操作抛出 NullPointerException。</p>
     *
     * @param nodeId        节点唯一标识
     * @param type          节点类型
     * @param description   节点描述
     * @param requiredTools 所需工具列表，为 null 时自动初始化为空列表
     * @param dependencies  依赖节点列表，为 null 时自动初始化为空列表
     * @param timeoutMs     超时时间（毫秒）
     */
    @JsonCreator
    public TaskNode(@JsonProperty("nodeId") String nodeId,
                    @JsonProperty("type") String type,
                    @JsonProperty("description") String description,
                    @JsonProperty("requiredTools") List<String> requiredTools,
                    @JsonProperty("dependencies") List<String> dependencies,
                    @JsonProperty("timeoutMs") long timeoutMs) {
        this.nodeId = nodeId;
        this.type = type;
        this.description = description;
        // 防御性处理：为null时使用空列表
        this.requiredTools = requiredTools != null ? requiredTools : List.of();
        this.dependencies = dependencies != null ? dependencies : List.of();
        this.timeoutMs = timeoutMs;
    }

    public String getNodeId() { return nodeId; }
    public String getType() { return type; }
    public String getDescription() { return description; }
    public List<String> getRequiredTools() { return requiredTools; }
    public List<String> getDependencies() { return dependencies; }
    public long getTimeoutMs() { return timeoutMs; }
}
