package lyjew.com.lyclaw.storage;

import lyjew.com.lyclaw.annotation.storage.MemoryStore;
import lyjew.com.lyclaw.annotation.storage.SessionStore;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存存储后端——框架兜底实现，永远可用。
 *
 * <p>所有数据存储在 ConcurrentHashMap 中，进程重启丢失。
 * 不支持向量搜索（线性扫描）、全文搜索和图查询。
 */
@lyjew.com.lyclaw.annotation.storage.StorageBackend(name = "inmemory", displayName = "内存存储", priority = 0)
@SessionStore(layerPriority = 0, layerDefault = false)
@MemoryStore(layerPriority = 0, layerDefault = false)
public class InMemoryBackend implements StorageBackend {

    private final Map<String, Map<String, Object>> store = new ConcurrentHashMap<>();
    private volatile boolean initialized;

    @Override
    public String backendName() {
        return "inmemory";
    }

    @Override
    public Set<StorageCapability> capabilities() {
        return EnumSet.of(StorageCapability.KEY_VALUE, StorageCapability.STREAMING);
    }

    @Override
    public void initialize(Map<String, Object> config) {
        initialized = true;
    }

    @Override
    public HealthResult healthCheck() {
        return HealthResult.up("InMemory 后端正常，存储条目数: " + totalEntries(),
                Map.of("totalEntries", totalEntries(), "namespaces", store.size()));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void put(String namespace, String key, T value) {
        store.computeIfAbsent(namespace, k -> new ConcurrentHashMap<>()).put(key, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String namespace, String key, Class<T> type) {
        Map<String, Object> ns = store.get(namespace);
        if (ns == null) return null;
        return (T) ns.get(key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> list(String namespace, Class<T> type) {
        Map<String, Object> ns = store.get(namespace);
        if (ns == null) return Collections.emptyList();
        return ns.values().stream().map(v -> (T) v).collect(Collectors.toList());
    }

    @Override
    public void delete(String namespace, String key) {
        Map<String, Object> ns = store.get(namespace);
        if (ns != null) ns.remove(key);
    }

    @Override
    public boolean exists(String namespace, String key) {
        Map<String, Object> ns = store.get(namespace);
        return ns != null && ns.containsKey(key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void putBatch(String namespace, Map<String, T> entries) {
        Map<String, Object> ns = store.computeIfAbsent(namespace, k -> new ConcurrentHashMap<>());
        ns.putAll(entries);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Map<String, T> getBatch(String namespace, Set<String> keys, Class<T> type) {
        Map<String, Object> ns = store.get(namespace);
        if (ns == null) return Collections.emptyMap();
        Map<String, T> result = new HashMap<>();
        for (String key : keys) {
            Object val = ns.get(key);
            if (val != null) result.put(key, (T) val);
        }
        return result;
    }

    @Override
    public QueryResult query(QuerySpec spec) {
        List<QueryResult.QueryResultItem> items = new ArrayList<>();
        Map<String, Object> ns = store.get(spec.getNamespace());
        if (ns != null) {
            for (Map.Entry<String, Object> entry : ns.entrySet()) {
                items.add(new QueryResult.QueryResultItem(
                        entry.getKey(), 0.0, String.valueOf(entry.getValue()),
                        Map.of(), QueryPath.KEYWORD));
            }
        }
        return new QueryResult(items, List.of(QueryPath.KEYWORD), 0);
    }

    @Override
    public void flush() { /* no-op */ }

    @Override
    public void compact() { /* no-op */ }

    private int totalEntries() {
        return store.values().stream().mapToInt(Map::size).sum();
    }
}
