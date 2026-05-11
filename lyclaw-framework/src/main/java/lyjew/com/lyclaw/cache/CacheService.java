package lyjew.com.lyclaw.cache;

import java.util.Optional;

/**
 * 缓存服务接口，提供键值对的临时存储与查询能力。
 *
 * <p>支持设置 TTL（秒级过期时间）、逐出、清空及命中率统计。</p>
 */
public interface CacheService {

    /**
     * 按 key 获取缓存值。
     *
     * @param key 缓存键
     * @return 缓存值，不存在或已过期时为空
     */
    Optional<String> get(String key);

    /**
     * 设置缓存。
     *
     * @param key        缓存键
     * @param value      缓存值
     * @param ttlSeconds 过期时间（秒）
     */
    void set(String key, String value, long ttlSeconds);

    /** 逐出指定 key 的缓存。 */
    void evict(String key);

    /** 清空全部缓存。 */
    void clear();

    /** @return 缓存命中率统计 */
    CacheStats getStats();
}
