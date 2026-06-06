package lyjew.com.lyclaw.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 阈值写策略 —— 累计 N 条新消息或超过 T 毫秒间隔时触发批量写入。
 *
 * <p>平衡写入性能和持久化安全性。</p>
 *
 * <p>默认阈值：{@value DEFAULT_COUNT_THRESHOLD} 条或 {@value DEFAULT_INTERVAL_MS} 毫秒。</p>
 */
public class ThresholdWritePolicy implements SessionWritePolicy {

    private static final Logger log = LoggerFactory.getLogger(ThresholdWritePolicy.class);

    static final int DEFAULT_COUNT_THRESHOLD = 10;
    static final long DEFAULT_INTERVAL_MS = 10_000;

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
    public boolean shouldFlush(SessionWriteState state) {
        if (state == null || state.getPendingCount() == 0) {
            return false;
        }
        boolean byCount = state.getPendingCount() >= countThreshold;
        boolean byTime = state.getElapsedSinceFirstPending() >= intervalMs;
        if (byCount || byTime) {
            log.debug("阈值写策略触发: pending={}, elapsed={}ms (阈值: {}条/{}ms)",
                    state.getPendingCount(), state.getElapsedSinceFirstPending(),
                    countThreshold, intervalMs);
            return true;
        }
        return false;
    }

    @Override
    public String name() {
        return "Threshold(" + countThreshold + "/" + intervalMs + "ms)";
    }
}
