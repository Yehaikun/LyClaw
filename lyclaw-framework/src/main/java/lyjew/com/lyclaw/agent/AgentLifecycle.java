package lyjew.com.lyclaw.agent;

import lyjew.com.lyclaw.dto.AgentResult;

import java.util.concurrent.CompletableFuture;

/**
 * 代理生命周期接口，定义代理从创建到销毁的完整生命周期管理方法。
 *
 * AgentLifecycle 负责代理实例的创建、任务调度、暂停、恢复和终止等
 * 全生命周期操作。它是代理池管理的核心契约，不同实现可以对应不同
 * 的代理运行时（如本地线程、远程服务、Kubernetes Pod 等）。每个操作
 * 都围绕 agentId 进行，确保代理实例在生命周期各阶段的状态一致性。
 * 创建操作返回 AgentHandle 供注册表登记，调度操作返回 AgentResult
 * 供上层消费。
 */
public interface AgentLifecycle {

    /**
     * 根据规格说明创建一个新的代理实例。
     *
     * @param spec 代理规格，包含名称、能力列表、模型名和配置参数
     * @return 异步返回新创建代理的句柄，包含唯一标识和初始状态
     */
    CompletableFuture<AgentHandle> create(AgentSpec spec);

    /**
     * 为指定代理调度一个任务并异步执行。
     *
     * @param agentId 目标代理的唯一标识
     * @param task    待执行的任务，包含任务类型、目标和负载数据
     * @return 异步返回执行结果
     */
    CompletableFuture<AgentResult> schedule(String agentId, AgentTask task);

    /**
     * 暂停指定代理的运行。代理当前任务会被挂起，可后续恢复。
     *
     * @param agentId 要暂停的代理唯一标识
     * @return 暂停成功返回 true，否则返回 false
     */
    boolean pause(String agentId);

    /**
     * 恢复指定代理的运行，使其从暂停状态继续执行。
     *
     * @param agentId 要恢复的代理唯一标识
     * @return 恢复成功返回 true，否则返回 false
     */
    boolean resume(String agentId);

    /**
     * 终止并销毁指定代理实例，释放相关资源。
     *
     * @param agentId 要终止的代理唯一标识
     * @return 终止成功返回 true，否则返回 false
     */
    boolean terminate(String agentId);

    /**
     * 查询指定代理的当前生命周期状态。
     *
     * @param agentId 代理唯一标识
     * @return 代理当前的状态枚举值
     */
    AgentState getState(String agentId);
}
