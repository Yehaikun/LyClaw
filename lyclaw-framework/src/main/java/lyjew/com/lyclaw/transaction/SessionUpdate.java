package lyjew.com.lyclaw.transaction;

import java.time.Instant;

/**
 * 会话变更记录，表示对会话状态的一次原子性修改。
 *
 * <p>每条记录包含变更类型、新旧值对比、操作者标识和时间戳，
 * 可用于审计追踪、冲突检测以及事务回滚时的状态恢复。
 * 所有字段均为不可变（final），确保变更记录的完整性和不可篡改性。
 */
public class SessionUpdate {

    /** 所属会话标识 */
    private final String sessionId;
    /** 变更类型（如 "APPEND_MESSAGE"、"UPDATE_CONFIG" 等） */
    private final String updateType;
    /** 变更前的值 */
    private final String oldValue;
    /** 变更后的值 */
    private final String newValue;
    /** 执行变更的操作者标识 */
    private final String operator;
    /** 变更发生的时间戳 */
    private final Instant timestamp;

    /**
     * 构建一条完整的会话变更记录。
     *
     * @param sessionId  会话标识
     * @param updateType 变更类型
     * @param oldValue   变更前的值
     * @param newValue   变更后的值
     * @param operator   操作者标识
     * @param timestamp  变更时间戳
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

    /** @return 所属会话标识 */
    public String getSessionId() { return sessionId; }
    /** @return 变更类型 */
    public String getUpdateType() { return updateType; }
    /** @return 变更前的值 */
    public String getOldValue() { return oldValue; }
    /** @return 变更后的值 */
    public String getNewValue() { return newValue; }
    /** @return 操作者标识 */
    public String getOperator() { return operator; }
    /** @return 变更时间戳 */
    public Instant getTimestamp() { return timestamp; }
}
