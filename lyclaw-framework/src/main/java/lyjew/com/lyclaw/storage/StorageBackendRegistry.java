package lyjew.com.lyclaw.storage;

import java.util.Map;
import java.util.Set;

/**
 * 存储后端注册表。
 *
 * <p>管理所有通过 {@code @StorageBackend} 注解自动发现的后端实例。
 * 按名称和层级提供后端查找、解析能力。</p>
 */
public interface StorageBackendRegistry {

    /** 注册后端 */
    void register(String name, StorageBackend backend, Set<StoreLayer> layers, int priority);

    /** 按层解析默认后端 */
    StorageBackend resolve(StoreLayer layer);

    /** 按名称获取后端 */
    StorageBackend get(String name);

    /** 获取所有后端 */
    Map<String, StorageBackend> getAll();

    /** 各层默认后端 */
    Map<StoreLayer, StorageBackend> getDefaults();
}
