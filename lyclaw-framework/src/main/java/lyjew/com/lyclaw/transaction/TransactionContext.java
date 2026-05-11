package lyjew.com.lyclaw.transaction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 事务上下文，记录一次会话事务的完整状态和变更历史。
 *
 * <p>每次调用 {@link SessionTransaction#begin} 都会创建一个 TransactionContext 实例，
 * 包含事务 ID、会话标识、初始快照、变更记录列表及生命周期状态。
 * 仅在 ACTIVE 状态下允许追加变更记录，确保已提交或已回滚的事务不可修改。
 */
public class TransactionContext {

    /** 事务状态：进行中 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 事务状态：已提交 */
    public static final String STATUS_COMMITTED = "COMMITTED";
    /** 事务状态：已回滚 */
    public static final String STATUS_ROLLED_BACK = "ROLLED_BACK";

    /** 关联的会话标识 */
    private final String sessionId;
    /** 事务开始时的上下文快照（序列化形式） */
    private final String contextSnapshot;
    /** 事务期间的变更记录列表 */
    private final List<SessionUpdate> updates;
    /** 当前事务状态 */
    private String status;
    /** 事务创建时间 */
    private final Instant createdAt;
    /** 事务唯一标识 */
    private final String transactionId;

    /**
     * 创建新的事务上下文实例。
     *
     * @param transactionId  事务唯一标识
     * @param sessionId      会话标识
     * @param contextSnapshot 事务开始时的上下文快照
     */
    public TransactionContext(String transactionId, String sessionId, String contextSnapshot) {
        this.transactionId = transactionId;
        this.sessionId = sessionId;
        this.contextSnapshot = contextSnapshot;
        this.updates = new ArrayList<>();
        this.status = STATUS_ACTIVE;
        this.createdAt = Instant.now();
    }

    /**
     * 向事务追加一条变更记录，仅在 ACTIVE 状态下生效。
     *
     * @param update 变更记录
     */
    public void addUpdate(SessionUpdate update) {
        if (STATUS_ACTIVE.equals(this.status)) {
            this.updates.add(update);
        }
    }

    /** @return 事务唯一标识 */
    public String getTransactionId() { return transactionId; }
    /** @return 关联的会话标识 */
    public String getSessionId() { return sessionId; }
    /** @return 事务初始上下文快照 */
    public String getContextSnapshot() { return contextSnapshot; }
    /** @return 事务期间的变更记录列表 */
    public List<SessionUpdate> getUpdates() { return updates; }
    /** @return 当前事务状态 */
    public String getStatus() { return status; }
    /** @param status 新的事务状态 */
    public void setStatus(String status) { this.status = status; }
    /** @return 事务创建时间 */
    public Instant getCreatedAt() { return createdAt; }
}
