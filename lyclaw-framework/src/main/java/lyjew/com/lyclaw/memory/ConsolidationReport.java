package lyjew.com.lyclaw.memory;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 记忆固化操作的执行报告，记录单次固化流程的处理统计。
 *
 * 由 {@link lyjew.com.lyclaw.memory.consolidate.MemoryConsolidator#consolidate} 返回，
 * 用于监控固化效果和诊断问题。使用 Lombok 自动生成 getter/Builder 等样板方法。
 */
@Data
@Builder
public class ConsolidationReport {
    /** 提升为长期记忆的条目数 */
    private int promotedToLongTerm;
    /** 合并的重复条目数（被合并掉的条目数） */
    private int mergedDuplicates;
    /** 因过期被移除的条目数 */
    private int expiredRemoved;
    /** 本次固化处理的总条目数 */
    private int totalProcessed;
    /** 固化耗时（毫秒） */
    private long durationMs;
    /** 被提升为长期记忆的条目 ID 列表，用于追踪和审计 */
    private List<String> promotedEntryIds;
}
