package lyjew.com.lyclaw.task;

import java.util.List;

/**
 * 任务分解器接口，将自然语言描述的任务拆解为结构化子任务图。
 *
 * <p>实现类可选择不同的分解策略（按阶段、按领域、LLM 驱动等），
 * 返回的 TaskNode 列表包含节点间的依赖关系，可直接用于构建 PlanGraph。
 */
public interface TaskDecomposer {

    /**
     * 使用指定策略将任务描述分解为子任务列表。
     *
     * @param taskDescription 任务描述文本
     * @param strategy        分解策略
     * @return 子任务节点列表，含依赖关系
     */
    List<TaskNode> decompose(String taskDescription, DecompositionStrategy strategy);
}
