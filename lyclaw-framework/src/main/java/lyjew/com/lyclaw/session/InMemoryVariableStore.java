package lyjew.com.lyclaw.session;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内内存会话变量存储 —— 基于 ConcurrentHashMap。
 *
 * <p>线程安全，变量与会话绑定，会话删除时变量随之清除。</p>
 */
public class InMemoryVariableStore implements VariableStore {

    private final ConcurrentHashMap<String, Map<String, Object>> store = new ConcurrentHashMap<>();

    @Override
    public void set(String sessionId, String key, Object value) {
        store.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(key, value);
    }

    @Override
    public void setAll(String sessionId, Map<String, Object> values) {
        if (values != null) {
            store.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).putAll(values);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String sessionId, String key, Class<T> type) {
        Map<String, Object> vars = store.get(sessionId);
        if (vars == null) return Optional.empty();
        Object value = vars.get(key);
        if (value == null) return Optional.empty();
        if (type.isInstance(value)) {
            return Optional.of((T) value);
        }
        return Optional.empty();
    }

    @Override
    public Map<String, Object> getAll(String sessionId) {
        Map<String, Object> vars = store.get(sessionId);
        return vars != null ? Collections.unmodifiableMap(vars) : Map.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> remove(String sessionId, String key) {
        Map<String, Object> vars = store.get(sessionId);
        if (vars == null) return Optional.empty();
        Object removed = vars.remove(key);
        if (removed == null) return Optional.empty();
        try {
            return Optional.of((T) removed);
        } catch (ClassCastException e) {
            return Optional.empty();
        }
    }

    @Override
    public void clear(String sessionId) {
        store.remove(sessionId);
    }

    @Override
    public boolean exists(String sessionId, String key) {
        Map<String, Object> vars = store.get(sessionId);
        return vars != null && vars.containsKey(key);
    }
}
