package lyjew.com.lyclaw.transaction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 事务上下文 —— 记录事务 ID、关联会话、变更快照、当前状态和创建时间。
 *
 * <p>每个 begin() 调用创建一个 TransactionContext 实例，存储在 SessionTransaction
 * 的内部 Map 中，通过 transactionId 索引。commit/rollback 时通过 transactionId
 * 找到对应的 TransactionContext，读取其中的变更记录做持久化或回滚。</p>
 *
 * <p><b>设计动机</b>：事务上下文需要在 begin() 和 commit/rollback() 之间传递状态。
 * 如果不封装为独立对象，SessionTransaction 的实现类需要自己维护 Map 和状态，复用性差。
 * TransactionContext 将事务状态封装为值对象，便于序列化/反序列化和日志追踪。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see SessionTransaction
 * @see SessionUpdate
 */
public class TransactionContext {

    /** 关联的会话 ID */
    private final String sessionId;

    /** 事务开始时的上下文快照（消息数量、记忆内容摘要），用于日志和回滚验证 */
    private final String contextSnapshot;

    /** 事务期间的变更记录列表 */
    private final List<SessionUpdate> updates;

    /**
     * 事务状态。设计文档用 String，此处改为枚举三个常量字符串，
     * 保持与文档一致的 String 签名，但通过常量化避免拼写错误。
     */
    private String status;

    /** 事务创建时间 */
    private final Instant createdAt;

    /** 事务 ID（由 SessionTransaction.begin() 生成并返回给调用方） */
    private final String transactionId;

    /** 状态常量 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMMITTED = "COMMITTED";
    public static final String STATUS_ROLLED_BACK = "ROLLED_BACK";

    /**
     * 构造事务上下文。
     *
     * @param transactionId   事务 ID
     * @param sessionId       关联的会话 ID
     * @param contextSnapshot 开始时的上下文快照
     */
    public TransactionContext(String transactionId, String sessionId,
                              String contextSnapshot) {
        this.transactionId = transactionId;
        this.sessionId = sessionId;
        this.contextSnapshot = contextSnapshot;
        this.updates = new ArrayList<>();
        this.status = STATUS_ACTIVE;
        this.createdAt = Instant.now();
    }

    /**
     * 添加一条变更记录到事务上下文中。
     *
     * @param update 变更记录
     */
    public void addUpdate(SessionUpdate update) {
        if (STATUS_ACTIVE.equals(this.status)) {
            this.updates.add(update);
        }
    }

    /** @return 事务 ID */
    public String getTransactionId() { return transactionId; }

    /** @return 关联的会话 ID */
    public String getSessionId() { return sessionId; }

    /** @return 开始时的上下文快照 */
    public String getContextSnapshot() { return contextSnapshot; }

    /** @return 变更记录列表 */
    public List<SessionUpdate> getUpdates() { return updates; }

    /** @return 当前状态 */
    public String getStatus() { return status; }

    /**
     * 更新事务状态。
     *
     * @param status 新状态（建议使用 STATUS_* 常量）
     */
    public void setStatus(String status) { this.status = status; }

    /** @return 事务创建时间 */
    public Instant getCreatedAt() { return createdAt; }
}