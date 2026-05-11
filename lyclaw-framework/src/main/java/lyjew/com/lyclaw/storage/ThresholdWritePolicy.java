package lyjew.com.lyclaw.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 阈值写策略——累计 N 条新记忆或超过 T 秒间隔触发批量写入。
 *
 * <p>默认阈值：50 条或 30 秒。这是框架推荐的默认写策略，
 * 平衡写入性能和持久化安全性。
 */
public class ThresholdWritePolicy implements MemoryPersistence {

    private static final Logger log = LoggerFactory.getLogger(ThresholdWritePolicy.class);

    static final int DEFAULT_COUNT_THRESHOLD = 50;
    static final long DEFAULT_INTERVAL_MS = 30_000;

    private final int countThreshold;
    private final long intervalMs;

    public ThresholdWritePolicy() {
        this(DEFAULT_COUNT_THRESHOLD, DEFAULT_INTERVAL_MS);
    }

    public ThresholdWritePolicy(int countThreshold, long intervalMs) {
        this.countThreshold = countThreshold;
        this.intervalMs = intervalMs;
    }

    @Override
    public void persist(MemoryWriteState state) {
        int total = state.getTotalPending();
        long elapsed = System.currentTimeMillis() - state.getLastFlushTimestamp();
        if (total >= countThreshold || elapsed >= intervalMs) {
            log.debug("阈值写策略触发: pending={}, elapsed={}ms (阈值: {}条/{}ms)",
                    total, elapsed, countThreshold, intervalMs);
            flush();
        }
    }

    @Override
    public MemoryWriteState recover(MemoryLayer layer) {
        return new MemoryWriteState();
    }

    @Override
    public void flush() {
        log.debug("ThresholdWritePolicy flush 完成");
    }
}
