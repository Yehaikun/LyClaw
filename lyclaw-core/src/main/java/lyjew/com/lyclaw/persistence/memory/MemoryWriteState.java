package lyjew.com.lyclaw.persistence.memory;

import java.util.Objects;

/**
 * 记忆累积变更状态。
 *
 * <p>纯值对象，不可变。描述"有多少未落盘的变更"。</p>
 *
 * <p><b>单一职责</b>：只记录累积数据，不参与任何决策逻辑。
 * 每次追加内容通过 {@link #accumulate(String)} 生成新的状态实例。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see MemoryPersistence
 */
public final class MemoryWriteState {

    private final int pendingChangeCount;
    private final long pendingCharCount;
    private final long lastFlushTimestamp;

    private MemoryWriteState(int pendingChangeCount, long pendingCharCount, long lastFlushTimestamp) {
        this.pendingChangeCount = pendingChangeCount;
        this.pendingCharCount = pendingCharCount;
        this.lastFlushTimestamp = lastFlushTimestamp;
    }

    // ========== 工厂方法 ==========

    /** 初始状态：无待写入变更 */
    public static MemoryWriteState initial() {
        return new MemoryWriteState(0, 0, System.currentTimeMillis());
    }

    // ========== 状态转换 ==========

    /**
     * 追加一次变更，返回新状态。
     *
     * @param newContent 新增的内容，为 null 时只增加次数不增加字符
     * @return 新状态实例
     */
    public MemoryWriteState accumulate(String newContent) {
        return new MemoryWriteState(
                pendingChangeCount + 1,
                pendingCharCount + (newContent != null ? newContent.length() : 0),
                lastFlushTimestamp
        );
    }

    /** 刷盘后重置状态 */
    public MemoryWriteState reset() {
        return new MemoryWriteState(0, 0, System.currentTimeMillis());
    }

    // ========== getters ==========

    /** 自上次刷盘以来的 append 调用次数 */
    public int getPendingChangeCount() {
        return pendingChangeCount;
    }

    /** 自上次刷盘以来的累积字符数 */
    public long getPendingCharCount() {
        return pendingCharCount;
    }

    /** 上次刷盘的时间戳（毫秒） */
    public long getLastFlushTimestamp() {
        return lastFlushTimestamp;
    }

    @Override
    public String toString() {
        return "MemoryWriteState{changes=" + pendingChangeCount + ", chars=" + pendingCharCount + "}";
    }

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
}
