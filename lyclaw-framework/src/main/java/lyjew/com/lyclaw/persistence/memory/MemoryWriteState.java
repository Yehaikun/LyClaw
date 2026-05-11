package lyjew.com.lyclaw.persistence.memory;

import java.util.Objects;

/**
 * 记忆写入状态不可变值对象，记录自上次刷写以来积累的待处理数据。
 *
 * <p>该类跟踪三个维度的状态：</p>
 * <ul>
 *   <li>待处理变更次数（pendingChangeCount）：自上次刷写后积累了多次新内容</li>
 *   <li>待处理字符数（pendingCharCount）：积累的总字符量</li>
 *   <li>上次刷写时间戳（lastFlushTimestamp）：最近一次调用刷写的时间</li>
 * </ul>
 *
 * <p>{@link MemoryPersistence} 的实现类根据这些指标来决定是否执行刷写。
 * 该类遵循不可变设计——每次状态变更都返回新实例，不会修改现有对象。</p>
 */
public final class MemoryWriteState {

    /** 自上次刷写以来积累的待处理变更次数 */
    private final int pendingChangeCount;
    /** 自上次刷写以来积累的待处理字符总数 */
    private final long pendingCharCount;
    /** 上次刷写操作的时间戳（毫秒） */
    private final long lastFlushTimestamp;

    /**
     * 私有构造器，通过工厂方法创建。
     *
     * @param pendingChangeCount 待处理变更次数
     * @param pendingCharCount   待处理字符数
     * @param lastFlushTimestamp 上次刷写时间戳
     */
    private MemoryWriteState(int pendingChangeCount, long pendingCharCount, long lastFlushTimestamp) {
        this.pendingChangeCount = pendingChangeCount;
        this.pendingCharCount = pendingCharCount;
        this.lastFlushTimestamp = lastFlushTimestamp;
    }

    /**
     * 创建初始写入状态，所有计数器归零，时间戳为当前时间。
     *
     * @return 初始状态实例
     */
    public static MemoryWriteState initial() {
        return new MemoryWriteState(0, 0, System.currentTimeMillis());
    }

    /**
     * 累加新内容到写入状态中，生成新状态实例。
     *
     * @param newContent 新增的内容字符串，可为 null（null 视为空字符串）
     * @return 更新后的新状态实例，changeCount +1，charCount 增加内容长度
     */
    public MemoryWriteState accumulate(String newContent) {
        return new MemoryWriteState(
                pendingChangeCount + 1,
                pendingCharCount + (newContent != null ? newContent.length() : 0),
                lastFlushTimestamp
        );
    }

    /**
     * 重置写入状态，计数器归零，更新刷写时间戳为当前时间。
     *
     * @return 重置后的新状态实例
     */
    public MemoryWriteState reset() {
        return new MemoryWriteState(0, 0, System.currentTimeMillis());
    }

    /** @return 待处理变更次数 */
    public int getPendingChangeCount() { return pendingChangeCount; }

    /** @return 待处理字符总数 */
    public long getPendingCharCount() { return pendingCharCount; }

    /** @return 上次刷写时间戳 */
    public long getLastFlushTimestamp() { return lastFlushTimestamp; }

    // equals/hashCode/toString 基于三个字段
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MemoryWriteState that)) return false;
        return pendingChangeCount == that.pendingChangeCount
                && pendingCharCount == that.pendingCharCount
                && lastFlushTimestamp == that.lastFlushTimestamp;
    }

    @Override
    public int hashCode() {
        return Objects.hash(pendingChangeCount, pendingCharCount, lastFlushTimestamp);
    }

    @Override
    public String toString() {
        return "MemoryWriteState{changes=" + pendingChangeCount + ", chars=" + pendingCharCount + "}";
    }
}
