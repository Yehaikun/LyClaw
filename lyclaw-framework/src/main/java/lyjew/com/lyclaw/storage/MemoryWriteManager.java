package lyjew.com.lyclaw.storage;

import java.util.Set;

/**
 * 记忆写策略管理器。
 *
 * <p>管理所有通过 {@code @WritePolicy} 注解声明的写策略实现。
 * 提供策略注册和写操作评估能力。
 */
public interface MemoryWriteManager {

    /** 注册写策略 */
    void register(String name, MemoryPersistence policy, Set<MemoryLayer> layers);

    /** 评估当前是否需要执行写操作 */
    PersistenceDecision evaluate(MemoryLayer layer, MemoryWriteState state);

    /**
     * 写策略评估结果。
     *
     * @param shouldWrite 是否应执行写入
     * @param reason      决策原因
     * @param policyName  使用的策略名称
     */
    record PersistenceDecision(boolean shouldWrite, String reason, String policyName) {}
}
