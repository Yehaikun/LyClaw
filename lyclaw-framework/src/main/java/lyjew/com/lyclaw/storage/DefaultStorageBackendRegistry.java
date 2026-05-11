package lyjew.com.lyclaw.storage;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 默认存储后端注册表实现。
 *
 * <p>使用 EnumMap 维护各层默认后端，ConcurrentHashMap 维护所有已注册后端。
 * resolve() 优先使用用户配置的后端名，未配置时回退到 priority 最高的后端。</p>
 */
public class DefaultStorageBackendRegistry implements StorageBackendRegistry {

    private final Map<String, StorageBackend> backends = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Set<StoreLayer>> layerMap = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<StoreLayer, String> configuredDefaults = new EnumMap<>(StoreLayer.class);
    private final Map<String, Integer> priorities = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void register(String name, StorageBackend backend, Set<StoreLayer> layers, int priority) {
        backends.put(name, backend);
        layerMap.put(name, layers);
        priorities.put(name, priority);
        // 各层在没有显式配置时选 priority 最高的后端
        for (StoreLayer layer : layers) {
            if (!configuredDefaults.containsKey(layer)
                    || priorities.getOrDefault(configuredDefaults.get(layer), -1) < priority) {
                configuredDefaults.put(layer, name);
            }
        }
    }

    /** 显式设置某层的默认后端名（由配置驱动） */
    public void setLayerDefault(StoreLayer layer, String backendName) {
        configuredDefaults.put(layer, backendName);
    }

    @Override
    public StorageBackend resolve(StoreLayer layer) {
        String name = configuredDefaults.get(layer);
        if (name != null) {
            StorageBackend backend = backends.get(name);
            if (backend != null) return backend;
        }
        return null;
    }

    @Override
    public StorageBackend get(String name) {
        return backends.get(name);
    }

    @Override
    public Map<String, StorageBackend> getAll() {
        return Collections.unmodifiableMap(backends);
    }

    @Override
    public Map<StoreLayer, StorageBackend> getDefaults() {
        Map<StoreLayer, StorageBackend> result = new EnumMap<>(StoreLayer.class);
        for (Map.Entry<StoreLayer, String> e : configuredDefaults.entrySet()) {
            StorageBackend backend = backends.get(e.getValue());
            if (backend != null) result.put(e.getKey(), backend);
        }
        return result;
    }
}
