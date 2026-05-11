package lyjew.com.lyclaw.agent.scaling;

import java.util.concurrent.CompletableFuture;

/**
 * 自动扩缩容接口，根据代理池负载情况动态调整代理实例数量。
 *
 * AutoScaler 是代理池弹性伸缩的策略引擎。工作流程为两阶段：首先通过
 * evaluate 方法分析代理池快照数据，产生一个扩缩容决策（扩展、缩减或
 * 不变）；然后通过 apply 方法将决策付诸实践，创建或销毁代理实例。
 * 这种"评估-执行"的分离设计允许在决策和实际执行之间插入审批、审计
 * 或额外校验步骤，也便于对扩缩容策略进行独立的单元测试。
 *
 * @see AgentPoolSnapshot
 * @see ScalingDecision
 * @see ScalingResult
 */
public interface AutoScaler {

    /**
     * 根据代理池快照评估是否需要扩缩容，产生决策。
     *
     * @param snapshot 代理池的当前状态快照
     * @return 扩缩容决策（扩展、缩减或不变）
     */
    ScalingDecision evaluate(AgentPoolSnapshot snapshot);

    /**
     * 执行扩缩容决策，创建或销毁代理实例。
     *
     * @param decision 由 evaluate 产生的扩缩容决策
     * @return 异步返回扩缩容执行结果
     */
    CompletableFuture<ScalingResult> apply(ScalingDecision decision);
}
