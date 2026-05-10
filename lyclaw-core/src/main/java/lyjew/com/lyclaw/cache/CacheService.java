package lyjew.com.lyclaw.cache;

import java.util.Optional;

/**
 * 缓存服务接口 —— 通用 key-value 缓存抽象，支持 TTL 和命中率统计。
 *
 * <p>缓存服务用于缓存不常变化的配置、模型会话快照和工具定义。
 * 通过接口隔离具体实现（Caffeine、Redis 或本地 Map）。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public interface CacheService {

    Optional<String> get(String key);

    void set(String key, String value, long ttlSeconds);

    void evict(String key);

    void clear();

    CacheStats getStats();
}