package lyjew.com.lyclaw.infra.event;

import lyjew.com.lyclaw.event.Event;

/**
 * 记忆整合完成事件，在记忆系统的后台整合任务执行完成后发布。
 *
 * <p>携带用户 ID、提升的记忆数量、合并的记忆数量和处理耗时，
 * 用于监控记忆系统的健康状态和性能。</p>
 */
public class MemoryConsolidatedEvent extends Event {

    /** 用户 ID */
    private final String userId;
    /** 被提升的记忆条目数 */
    private final int promotedCount;
    /** 被合并的记忆条目数 */
    private final int mergedCount;
    /** 整合操作耗时（毫秒） */
    private final long durationMs;

    /**
     * 构造一个记忆整合完成事件。
     *
     * @param source        事件来源标识
     * @param userId        用户 ID
     * @param promotedCount 提升的记忆数
     * @param mergedCount   合并的记忆数
     * @param durationMs    耗时（毫秒）
     */
    public MemoryConsolidatedEvent(String source, String userId, int promotedCount,
                                   int mergedCount, long durationMs) {
        super(source, "MEMORY_CONSOLIDATED");
        this.userId = userId;
        this.promotedCount = promotedCount;
        this.mergedCount = mergedCount;
        this.durationMs = durationMs;
    }

    /** @return 用户 ID */
    public String getUserId() { return userId; }
    /** @return 提升的记忆数 */
    public int getPromotedCount() { return promotedCount; }
    /** @return 合并的记忆数 */
    public int getMergedCount() { return mergedCount; }
    /** @return 耗时（毫秒） */
    public long getDurationMs() { return durationMs; }
}
