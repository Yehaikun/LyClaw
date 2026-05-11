package lyjew.com.lyclaw.transaction;

import java.util.List;

/**
 * 会话更新合并策略接口，定义多客户端并发修改同一会话时的冲突解决策略。
 *
 * <p>在分布式或多 Agent 协作场景下，不同的客户端可能同时提交对同一会话的状态变更。
 * 该接口允许接入不同的合并算法（如最后写入胜出、基于操作的转换等），
 * 通过策略模式灵活切换合并逻辑。
 */
public interface SessionUpdateStrategy {

    /**
     * 将新的变更记录合并到已有变更列表中，解决潜在的冲突。
     *
     * @param existing  已有的变更记录列表
     * @param newUpdate 新的变更记录
     * @return 合并后的变更记录列表
     */
    List<SessionUpdate> merge(List<SessionUpdate> existing, SessionUpdate newUpdate);

    /**
     * 获取当前策略的名称。
     *
     * @return 策略名称
     */
    String getStrategyName();
}
