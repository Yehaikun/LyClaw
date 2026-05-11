package lyjew.com.lyclaw.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * StorageFacade 默认实现，通过构造器注入所有依赖。
 *
 * <p>getBackend() 解析逻辑：优先检查配置 lyclaw.storage.stores.<layer>.backend，未配置时
 * 使用 @MemoryStore(layerDefault=true) 或 priority 最高的后端，最终回退到框架内置默认。
 */
public class DefaultStorageFacade implements StorageFacade {

    private static final Logger log = LoggerFactory.getLogger(DefaultStorageFacade.class);

    private final StorageBackendRegistry backendRegistry;

    public DefaultStorageFacade(StorageBackendRegistry backendRegistry) {
        this.backendRegistry = backendRegistry;
    }

    @Override
    public StorageBackend getBackend(StoreLayer layer) {
        StorageBackend backend = backendRegistry.resolve(layer);
        if (backend == null) {
            throw new IllegalStateException(
                    "没有为 " + layer + " 层配置存储后端。请检查 lyclaw.storage.stores 配置。");
        }
        return backend;
    }

    @Override
    public Map<String, StorageBackend> getBackends() {
        return backendRegistry.getAll();
    }

    @Override
    public void switchBackend(StoreLayer layer, String backendName) {
        StorageBackend target = backendRegistry.get(backendName);
        if (target == null) {
            throw new IllegalArgumentException("存储后端不存在: " + backendName);
        }
        if (backendRegistry instanceof DefaultStorageBackendRegistry reg) {
            reg.setLayerDefault(layer, backendName);
            log.warn("存储层 {} 已热切换到后端: {}（pending 写操作可能丢失）", layer, backendName);
        }
    }

    @Override
    public <T> void save(String namespace, String key, T value) {
        getBackendByNamespace(namespace).put(namespace, key, value);
    }

    @Override
    public <T> Optional<T> load(String namespace, String key, Class<T> type) {
        T value = getBackendByNamespace(namespace).get(namespace, key, type);
        return Optional.ofNullable(value);
    }

    @Override
    public <T> List<T> list(String namespace, Class<T> type) {
        return getBackendByNamespace(namespace).list(namespace, type);
    }

    @Override
    public void delete(String namespace, String key) {
        getBackendByNamespace(namespace).delete(namespace, key);
    }

    @Override
    public List<QueryResult.QueryResultItem> searchSimilar(String namespace, float[] embedding, int topK) {
        QuerySpec spec = QuerySpec.builder()
                .namespace(namespace).vector(embedding).topK(topK).build();
        QueryResult result = getBackendByNamespace(namespace).query(spec);
        return result.getItems();
    }

    @Override
    public Map<String, HealthResult> healthCheck() {
        Map<String, HealthResult> results = new LinkedHashMap<>();
        for (Map.Entry<String, StorageBackend> entry : backendRegistry.getAll().entrySet()) {
            try {
                results.put(entry.getKey(), entry.getValue().healthCheck());
            } catch (Exception e) {
                results.put(entry.getKey(), HealthResult.down(e.getMessage()));
            }
        }
        return results;
    }

    /** 根据命名空间推断所属存储层并取对应后端 */
    private StorageBackend getBackendByNamespace(String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            return getBackend(StoreLayer.ENTITY);
        }
        String lower = namespace.toLowerCase();
        if (lower.contains("session")) return getBackend(StoreLayer.SESSION);
        if (lower.contains("memory")) return getBackend(StoreLayer.MEMORY);
        return getBackend(StoreLayer.ENTITY);
    }
}
