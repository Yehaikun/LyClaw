package lyjew.com.lyclaw.persistence.memory.impl;

import lyjew.com.lyclaw.persistence.PersistenceDecision;
import lyjew.com.lyclaw.persistence.memory.MemoryPersistence;
import lyjew.com.lyclaw.persistence.memory.MemoryWriteState;
import org.springframework.stereotype.Component;

/**
 * 即时刷盘策略 —— 每次追加后立即写入。
 *
 * <p>当前 FileMemoryManager 的默认行为（每次 append 都 persist），
 * 作为迁移到持久化决策层后的默认策略，保持行为不变。</p>
 *
 * <p><b>适用场景</b>：对数据安全性要求高、内容不频繁的小内存场景。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see MemoryPersistence
 */
@Component
public class ImmediateMemoryPersistence implements MemoryPersistence {

    @Override
    public PersistenceDecision evaluate(MemoryWriteState writeState) {
        // 有未刷盘的变更就立即写
        if (writeState.getPendingChangeCount() > 0) {
            return PersistenceDecision.write("即时刷盘 (changes=" + writeState.getPendingChangeCount() + ")");
        }
        return PersistenceDecision.skip("无变更，跳过");
    }
}
