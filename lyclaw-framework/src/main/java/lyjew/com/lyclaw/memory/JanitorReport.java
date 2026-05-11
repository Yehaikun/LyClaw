package lyjew.com.lyclaw.memory;

import lombok.Builder;
import lombok.Data;

/**
 * 记忆清理操作的执行报告，记录单次清理流程的处理统计。
 *
 * 由 {@link lyjew.com.lyclaw.memory.janitor.MemoryJanitor#clean} 返回，
 * 用于监控存储空间回收效果。使用 Lombok 自动生成 getter/Builder 等样板方法。
 */
@Data
@Builder
public class JanitorReport {
    /** 移除的重复条目数量 */
    private int duplicatesRemoved;
    /** 因过期被清理的条目数量 */
    private int expiredEntriesRemoved;
    /** 解决的冲突数量 */
    private int conflictsResolved;
    /** 本次清理的总条目数 */
    private int totalCleaned;
    /** 清理耗时（毫秒） */
    private long durationMs;
    /** 释放的存储空间（字节） */
    private long spaceFreedBytes;
}
