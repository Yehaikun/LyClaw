package lyjew.com.lyclaw.storage;

/**
 * 记忆持久化接口，定义记忆写入和恢复的契约。
 *
 * <p>实现类通过 {@code @WritePolicy} 注解声明适用的记忆层级和策略类型。
 */
public interface MemoryPersistence {

    /** 持久化一批记忆 */
    void persist(MemoryWriteState state);

    /** 恢复指定层级的记忆 */
    MemoryWriteState recover(MemoryLayer layer);

    /** 手动触发刷新 */
    void flush();
}
