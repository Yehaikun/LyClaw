package lyjew.com.lyclaw.transaction;

import java.time.Instant;

/**
 * 单次变更记录值对象 —— 记录会话中某一次具体变更的内容（old → new）。
 *
 * <p>每次对会话状态的变更（追加消息、更新记忆、修改配置）都生成一个 SessionUpdate，
 * 记录变更前后的值以及操作人信息。commit 时将所有变更持久化，
 * rollback 时根据 oldValue 恢复到变更前的状态。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see TransactionContext
 * @see SessionTransaction
 */
public class SessionUpdate {

    /** 关联的会话 ID */
    private final String sessionId;

    /** 更新类型。如 "MESSAGE_ADDED"、"MEMORY_UPDATED"、"CONFIG_CHANGED" */
    private final String updateType;

    /** 变更前的值（JSON 格式），用于回滚 */
    private final String oldValue;

    /** 变更后的值（JSON 格式） */
    private final String newValue;

    /** 操作人标识 */
    private final String operator;

    /** 操作时间 */
    private final Instant timestamp;

    /**
     * 构造一条变更记录。
     *
     * @param sessionId  关联的会话 ID
     * @param updateType 更新类型
     * @param oldValue   变更前的值（null 表示新增而不是修改）
     * @param newValue   变更后的值
     * @param operator   操作人
     * @param timestamp  操作时间
     */
    public SessionUpdate(String sessionId, String updateType,
                         String oldValue, String newValue,
                         String operator, Instant timestamp) {
        this.sessionId = sessionId;
        this.updateType = updateType;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.operator = operator;
        this.timestamp = timestamp;
    }

    /** @return 关联的会话 ID */
    public String getSessionId() { return sessionId; }

    /** @return 更新类型 */
    public String getUpdateType() { return updateType; }

    /** @return 变更前的值（新增时为 null） */
    public String getOldValue() { return oldValue; }

    /** @return 变更后的值 */
    public String getNewValue() { return newValue; }

    /** @return 操作人 */
    public String getOperator() { return operator; }

    /** @return 操作时间 */
    public Instant getTimestamp() { return timestamp; }
}