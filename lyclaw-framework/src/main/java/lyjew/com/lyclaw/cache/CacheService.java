package lyjew.com.lyclaw.cache;

import java.util.Optional;

public interface CacheService {

    Optional<String> get(String key);

    void set(String key, String value, long ttlSeconds);

    void evict(String key);

    void clear();

    CacheStats getStats();
}
