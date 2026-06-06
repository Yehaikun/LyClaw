package lyjew.com.lyclaw.session;

/**
 * 会话写状态 —— 向 {@link SessionWritePolicy} 提供决策依据。
 *
 * <p>记录自上次 flush 以来累积的消息数、经过的时间等指标。</p>
 */
public class SessionWriteState {

    private final String sessionId;
    private int pendingCount;
    private long lastFlushTimestamp;
    private long firstPendingTimestamp;

    public SessionWriteState(String sessionId) {
        this.sessionId = sessionId;
        this.lastFlushTimestamp = System.currentTimeMillis();
        this.firstPendingTimestamp = 0;
    }

    /** 通知策略有一条新消息待写入 */
    public void onMessageAppended() {
        if (pendingCount == 0) {
            firstPendingTimestamp = System.currentTimeMillis();
        }
        pendingCount++;
    }

    /** 通知策略已完成一次 flush */
    public void onFlushed() {
        lastFlushTimestamp = System.currentTimeMillis();
        pendingCount = 0;
        firstPendingTimestamp = 0;
    }

    public String getSessionId() { return sessionId; }
    public int getPendingCount() { return pendingCount; }
    public long getLastFlushTimestamp() { return lastFlushTimestamp; }
    public long getElapsedSinceLastFlush() {
        return System.currentTimeMillis() - lastFlushTimestamp;
    }
    public long getElapsedSinceFirstPending() {
        if (firstPendingTimestamp == 0) return 0;
        return System.currentTimeMillis() - firstPendingTimestamp;
    }
}
