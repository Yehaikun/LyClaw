package lyjew.com.lyclaw.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 即时写策略——每条新记忆立即写入存储后端。
 *
 * <p>适合关键数据（如实体记忆），确保即使进程崩溃也不丢失。
 * 但频繁写入会增加 I/O 开销，不推荐用于感知记忆和短期记忆。
 */
public class ImmediateWritePolicy implements MemoryPersistence {

    private static final Logger log = LoggerFactory.getLogger(ImmediateWritePolicy.class);

    @Override
    public void persist(MemoryWriteState state) {
        int total = state.getTotalPending();
        // 有任何待写入条目就立即执行
        if (total > 0) {
            log.trace("即时写策略触发: pending={}", total);
            flush();
        }
    }

    @Override
    public MemoryWriteState recover(MemoryLayer layer) {
        return new MemoryWriteState();
    }

    @Override
    public void flush() {
        log.debug("ImmediateWritePolicy flush 完成");
    }
}
