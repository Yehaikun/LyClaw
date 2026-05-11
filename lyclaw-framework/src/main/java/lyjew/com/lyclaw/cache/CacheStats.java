package lyjew.com.lyclaw.cache;

/**
 * 缓存统计信息，记录缓存的命中与未命中次数。
 *
 * <p>提供命中率计算方法：hitCount / (hitCount + missCount)，无请求时返回 0.0。</p>
 */
public class CacheStats {

    /** 缓存命中次数 */
    private final long hitCount;
    /** 缓存未命中次数 */
    private final long missCount;

    public CacheStats(long hitCount, long missCount) {
        this.hitCount = hitCount;
        this.missCount = missCount;
    }

    public long getHitCount() { return hitCount; }
    public long getMissCount() { return missCount; }

    /**
     * 计算缓存命中率。
     *
     * @return 命中率 [0.0, 1.0]，无请求时返回 0.0
     */
    public double getHitRate() {
        long total = hitCount + missCount;
        return total == 0 ? 0.0 : (double) hitCount / total; // 避免除零
    }

    /** @return 零命中率的空统计实例 */
    public static CacheStats empty() {
        return new CacheStats(0, 0);
    }
}
