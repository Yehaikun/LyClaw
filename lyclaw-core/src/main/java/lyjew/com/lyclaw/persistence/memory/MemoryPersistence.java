package lyjew.com.lyclaw.persistence.memory;

import lyjew.com.lyclaw.persistence.PersistenceDecision;

/**
 * 记忆持久化策略接口。
 *
 * <p>只负责返回"该不该刷盘"的决策，不持有任何存储引用。
 * 实现类是纯函数：输入 {@link MemoryWriteState}，输出 {@link PersistenceDecision}。</p>
 *
 * <p><b>设计动机</b>：将"何时持久化记忆"的决策逻辑从 FileMemoryManager 中独立出来，
 * 使得记忆持久化时机可配置、可替换、可扩展。</p>
 *
 * <p><b>设计模式</b>：策略模式</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ImmediateMemoryPersistence
 * @see ThresholdMemoryPersistence
 * @see PersistenceDecision
 * @see MemoryWriteState
 */
public interface MemoryPersistence {

    /**
     * 记忆追加后的持久化决策。
     *
     * @param writeState 当前累积状态（变更次数、字符数、上次刷盘时间）
     * @return WRITE = 立即刷盘 / DEFER = 继续积累 / SKIP = 不需要
     */
    PersistenceDecision evaluate(MemoryWriteState writeState);
}
