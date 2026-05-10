package lyjew.com.lyclaw.cache;

/**
 * 缓存统计信息值对象 —— CacheService.getStats() 的返回值。
 *
 * <p>包含缓存命中次数、未命中次数和命中率。
 * 用于监控缓存效率和定位缓存问题。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see CacheService
 */
public class CacheStats {

    /** 缓存命中次数 */
    private final long hitCount;

    /** 缓存未命中次数 */
    private final long missCount;

    /**
     * 构造缓存统计信息。
     *
     * @param hitCount  命中次数
     * @param missCount 未命中次数
     */
    public CacheStats(long hitCount, long missCount) {
        this.hitCount = hitCount;
        this.missCount = missCount;
    }

    /** @return 缓存命中次数 */
    public long getHitCount() { return hitCount; }

    /** @return 缓存未命中次数 */
    public long getMissCount() { return missCount; }

    /**
     * 获取缓存命中率。
     *
     * @return 命中率（0.0 ~ 1.0），无请求时返回 0.0
     */
    public double getHitRate() {
        long total = hitCount + missCount;
        return total == 0 ? 0.0 : (double) hitCount / total;
    }

    /**
     * 快速创建一个空的统计（全为0）。
     *
     * @return 空统计
     */
    public static CacheStats empty() {
        return new CacheStats(0, 0);
    }
}