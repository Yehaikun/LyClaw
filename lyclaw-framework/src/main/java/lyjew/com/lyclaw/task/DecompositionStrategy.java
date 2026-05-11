package lyjew.com.lyclaw.task;

/**
 * 任务分解策略枚举，定义将复杂任务拆分为子任务的不同方式。
 * SEQUENTIAL - 顺序分解，子任务按线性顺序执行
 * BY_DOMAIN - 按领域分解，根据专业领域划分
 * BY_PHASE - 按阶段分解，将任务按生命周期阶段拆分
 * PARALLEL_INDEPENDENT - 并行独立分解，子任务无依赖可并行执行
 * LLM_DRIVEN - 由LLM驱动的分解，让大模型自主判断如何拆分
 * TREE - 树形分解，形成层次化的任务树结构
 */
public enum DecompositionStrategy {
    SEQUENTIAL,
    BY_DOMAIN,
    BY_PHASE,
    PARALLEL_INDEPENDENT,
    LLM_DRIVEN,
    TREE
}
