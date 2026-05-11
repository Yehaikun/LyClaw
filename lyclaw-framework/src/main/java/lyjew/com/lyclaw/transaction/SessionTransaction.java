package lyjew.com.lyclaw.transaction;

import lyjew.com.lyclaw.context.ChatContext;

import java.util.List;

/**
 * 会话事务接口，为会话上下文的上层操作提供事务性保障。
 *
 * <p>定义了一套精简的 begin-commit-rollback 事务模型，用于控制会话状态变更的原子性。
 * 支持创建快照以记录变更历史，确保在出现冲突或异常时能够回滚到安全状态。
 */
public interface SessionTransaction {

    /**
     * 开始一个会话事务，传入 sessionId 和初始上下文。
     *
     * @param sessionId 会话标识
     * @param context   事务初始上下文（通常为序列化的会话状态）
     */
    void begin(String sessionId, String context);

    /**
     * 提交当前会话事务的所有变更。
     *
     * @param sessionId 会话标识
     * @return 提交成功返回 true，否则返回 false
     */
    boolean commit(String sessionId);

    /**
     * 回滚当前会话事务，恢复到事务开始前的状态。
     *
     * @param sessionId 会话标识
     * @return 回滚成功返回 true，否则返回 false
     */
    boolean rollback(String sessionId);

    /**
     * 获取指定会话事务的当前状态。
     *
     * @param sessionId 会话标识
     * @return 事务状态（如 ACTIVE、COMMITTED、ROLLED_BACK）
     */
    String getStatus(String sessionId);

    /**
     * 为指定会话创建一次状态快照，返回变更记录列表。
     *
     * @param sessionId 会话标识
     * @param context   当前对话上下文
     * @return 本次快照发现的变更记录列表
     */
    List<SessionUpdate> createSnapshot(String sessionId, ChatContext context);
}
