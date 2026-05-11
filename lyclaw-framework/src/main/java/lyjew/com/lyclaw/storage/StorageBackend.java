package lyjew.com.lyclaw.storage;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 存储后端核心接口，定义键值存取、批量操作、查询和生命周期的统一契约。
 *
 * <p>所有存储后端实现（File、SQLite、PostgreSQL、Redis 等）必须实现此接口。
 * 通过 {@link #capabilities()} 声明自身支持的能力，
 * 框架据此自动选择查询路径。</p>
 */
public interface StorageBackend extends AutoCloseable {

    /** 后端名称（与 @StorageBackend.name 对应） */
    String backendName();

    /** 后端声明的能力集合 */
    Set<StorageCapability> capabilities();

    /** 初始化后端（建表、建索引等），框架在注册后调用 */
    void initialize(Map<String, Object> config);

    /** 健康检查，用于 Actuator 端点 */
    HealthResult healthCheck();

    // ── 键值操作 ──
    <T> void put(String namespace, String key, T value);
    <T> T get(String namespace, String key, Class<T> type);
    <T> List<T> list(String namespace, Class<T> type);
    void delete(String namespace, String key);
    boolean exists(String namespace, String key);

    // ── 批量操作 ──
    <T> void putBatch(String namespace, Map<String, T> entries);
    <T> Map<String, T> getBatch(String namespace, Set<String> keys, Class<T> type);

    // ── 查询（多路融合） ──
    QueryResult query(QuerySpec spec);

    // ── 生命周期 ──
    void flush();
    void compact();

    @Override
    default void close() throws Exception { /* no-op */ }
}
