package lyjew.com.lyclaw.persistence.memory;

import lyjew.com.lyclaw.persistence.PersistenceDecision;

/**
 * 记忆持久化策略接口，定义记忆写入的评估逻辑。
 *
 * <p>该接口的实现类负责根据当前的写入状态（{@link MemoryWriteState}）
 * 决定是否应该将积累的记忆数据刷写到持久化存储中。这是一种「写前评估」
 * 模式，允许通过不同的策略（如按变更次数、按字符数、按时间间隔）来控制
 * 写入频率，避免频繁 I/O。</p>
 *
 * <p>评估结果以 {@link PersistenceDecision} 的形式返回，可能为
 * WRITE（立即写入）、DEFER（推迟）或 SKIP（跳过）。</p>
 */
public interface MemoryPersistence {

    /**
     * 评估当前记忆写入状态，返回持久化决策。
     *
     * @param writeState 当前的写入状态，包含待处理变更数、字符数和上次刷写时间
     * @return 持久化决策，指示应立即写入、推迟还是跳过
     */
    PersistenceDecision evaluate(MemoryWriteState writeState);
}
