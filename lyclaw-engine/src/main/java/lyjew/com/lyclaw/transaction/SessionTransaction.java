package lyjew.com.lyclaw.transaction;

import lyjew.com.lyclaw.context.ChatContext;

import java.util.List;

/**
 * 对话事务抽象接口 —— 确保会话数据的变更要么全部成功，要么全部回滚。
 *
 * <p>对话过程中可能会发生多次会话状态变更（追加消息、更新记忆、修改会话元信息）。
 * 如果中间某一步失败，需要回滚到事务开始时的状态。
 * SessionTransaction 提供 begin/commit/rollback 的 ACID 语义。</p>
 *
 * <p><b>设计动机</b>：没有事务管理的情况下，工具调用链中的某个工具执行成功后
 * 将数据写入会话，但后续工具失败时，已写入的数据无法自动回滚，导致会话状态不一致。
 * SessionTransaction 确保整个请求的处理是一个原子操作。</p>
 *
 * <p><b>事务流程</b>：
 * <ol>
 *   <li>InterceptorStage 在开始前调用 begin()</li>
 *   <li>各 PipelineStage 调用 createSnapshot() 记录变更</li>
 *   <li>全部成功时调用 commit()</li>
 *   <li>任一异常时调用 rollback()</li>
 * </ol>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see TransactionContext
 * @see SessionUpdate
 */
public interface SessionTransaction {

    /**
     * 开始一个对话事务。记录事务开始时的会话状态快照。
     *
     * @param sessionId 要开启事务的会话 ID
     * @param context   上下文描述（如请求摘要），便于日志追踪
     */
    void begin(String sessionId, String context);

    /**
     * 提交事务，将所有变更持久化。
     *
     * @param sessionId 会话 ID
     * @return true 表示提交成功
     */
    boolean commit(String sessionId);

    /**
     * 回滚事务，恢复到事务开始时的状态。
     *
     * @param sessionId 会话 ID
     * @return true 表示回滚成功
     */
    boolean rollback(String sessionId);

    /**
     * 获取事务当前状态。
     *
     * @param sessionId 会话 ID
     * @return 状态描述，如 "ACTIVE"、"COMMITTED"、"ROLLED_BACK"
     */
    String getStatus(String sessionId);

    /**
     * 创建当前会话的快照。记录在当前事务上下文中。
     * <b>改动点</b>：设计文档返回的是单个 SessionUpdate，改为返回 List，
     * 因为一次快照可能包含多条变更记录（消息追加 + 记忆更新同时发生）。
     *
     * @param sessionId 会话 ID
     * @param context   当前对话上下文
     * @return 事务期间的变更记录列表。空列表表示无变更
     */
    List<SessionUpdate> createSnapshot(String sessionId, ChatContext context);
}