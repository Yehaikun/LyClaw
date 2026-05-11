package lyjew.com.lyclaw.cache;

public class CacheStats {

    private final long hitCount;
    private final long missCount;

    public CacheStats(long hitCount, long missCount) {
        this.hitCount = hitCount;
        this.missCount = missCount;
    }

    public long getHitCount() { return hitCount; }

    public long getMissCount() { return missCount; }

    public double getHitRate() {
        long total = hitCount + missCount;
        return total == 0 ? 0.0 : (double) hitCount / total;
    }

    public static CacheStats empty() {
        return new CacheStats(0, 0);
    }
}
