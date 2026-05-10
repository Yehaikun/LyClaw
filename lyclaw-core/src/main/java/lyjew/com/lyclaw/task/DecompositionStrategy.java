package lyjew.com.lyclaw.task;

/**
 * 任务分解策略 —— 决定如何将根任务拆分为子任务 DAG。
 *
 * @since 2.0
 */
public enum DecompositionStrategy {

    /** 顺序分解: A → B → C */
    SEQUENTIAL,

    /** 按领域分解 */
    BY_DOMAIN,

    /** 按阶段分解: 分析→设计→实现→验证 */
    BY_PHASE,

    /** 独立并行 */
    PARALLEL_INDEPENDENT,

    /** LLM驱动: 让模型自己决定如何分解 */
    LLM_DRIVEN,

    /** 树形递归分解 */
    TREE
}
