package lyjew.com.lyclaw.storage;

import java.util.List;
import java.util.Map;

/**
 * 记忆写状态快照，描述当前待写入的记忆数据。
 *
 * <p>包含各层级的条目数、最后写入时间、累积大小等统计信息，
 * 供 {@link MemoryWriteManager} 评估是否需要执行写入。</p>
 */
public class MemoryWriteState {

    /** 各记忆层级的待写入条目数 */
    private Map<MemoryLayer, Integer> pendingCounts;
    /** 上次刷新的时间戳 */
    private long lastFlushTimestamp;
    /** 各层级累积的新条目列表（按需填充） */
    private Map<MemoryLayer, List<Object>> pendingEntries;
    /** 累积的条目总数 */
    private int totalPending;

    public Map<MemoryLayer, Integer> getPendingCounts() { return pendingCounts; }
    public void setPendingCounts(Map<MemoryLayer, Integer> pendingCounts) { this.pendingCounts = pendingCounts; }
    public long getLastFlushTimestamp() { return lastFlushTimestamp; }
    public void setLastFlushTimestamp(long lastFlushTimestamp) { this.lastFlushTimestamp = lastFlushTimestamp; }
    public Map<MemoryLayer, List<Object>> getPendingEntries() { return pendingEntries; }
    public void setPendingEntries(Map<MemoryLayer, List<Object>> pendingEntries) { this.pendingEntries = pendingEntries; }
    public int getTotalPending() { return totalPending; }
    public void setTotalPending(int totalPending) { this.totalPending = totalPending; }
}
