package lyjew.com.lyclaw.storage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 存储层统一门面，将所有分散的存储组件（后端注册表、记忆系统、写策略）统一暴露。
 *
 * <p>这是框架使用者操作存储层的唯一入口，业务代码通过依赖注入获取此门面实例。
 * 不直接依赖具体后端实现，后端切换对业务代码透明。
 */
public interface StorageFacade {

    // ── 后端管理 ──

    /** 获取当前某层使用的存储后端 */
    StorageBackend getBackend(StoreLayer layer);

    /** 获取所有已注册的存储后端 */
    Map<String, StorageBackend> getBackends();

    /** 运行时切换某层的后端（热切换） */
    void switchBackend(StoreLayer layer, String backendName);

    // ── 键值操作（便捷方法） ──

    /** 保存对象到指定命名空间 */
    <T> void save(String namespace, String key, T value);

    /** 从命名空间加载对象 */
    <T> Optional<T> load(String namespace, String key, Class<T> type);

    /** 列出命名空间下所有对象 */
    <T> List<T> list(String namespace, Class<T> type);

    /** 删除键 */
    void delete(String namespace, String key);

    // ── 向量操作（便捷方法） ──

    /** 相似向量搜索 */
    List<QueryResult.QueryResultItem> searchSimilar(String namespace, float[] embedding, int topK);

    // ── 健康检查 ──

    /** 对所有已注册后端执行健康检查 */
    Map<String, HealthResult> healthCheck();
}
