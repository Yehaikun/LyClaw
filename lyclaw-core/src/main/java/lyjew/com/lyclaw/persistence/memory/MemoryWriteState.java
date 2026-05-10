package lyjew.com.lyclaw.persistence.memory;

import java.util.Objects;

public final class MemoryWriteState {

    private final int pendingChangeCount;
    private final long pendingCharCount;
    private final long lastFlushTimestamp;

    private MemoryWriteState(int pendingChangeCount, long pendingCharCount, long lastFlushTimestamp) {
        this.pendingChangeCount = pendingChangeCount;
        this.pendingCharCount = pendingCharCount;
        this.lastFlushTimestamp = lastFlushTimestamp;
    }

    public static MemoryWriteState initial() {
        return new MemoryWriteState(0, 0, System.currentTimeMillis());
    }

    public MemoryWriteState accumulate(String newContent) {
        return new MemoryWriteState(
                pendingChangeCount + 1,
                pendingCharCount + (newContent != null ? newContent.length() : 0),
                lastFlushTimestamp
        );
    }

    public MemoryWriteState reset() {
        return new MemoryWriteState(0, 0, System.currentTimeMillis());
    }

    public int getPendingChangeCount() { return pendingChangeCount; }

    public long getPendingCharCount() { return pendingCharCount; }

    public long getLastFlushTimestamp() { return lastFlushTimestamp; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MemoryWriteState that)) return false;
        return pendingChangeCount == that.pendingChangeCount
                && pendingCharCount == that.pendingCharCount
                && lastFlushTimestamp == that.lastFlushTimestamp;
    }

    @Override
    public int hashCode() {
        return Objects.hash(pendingChangeCount, pendingCharCount, lastFlushTimestamp);
    }

    @Override
    public String toString() {
        return "MemoryWriteState{changes=" + pendingChangeCount + ", chars=" + pendingCharCount + "}";
    }
}
