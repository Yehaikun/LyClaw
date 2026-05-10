package lyjew.com.lyclaw.infra.event;

import lyjew.com.lyclaw.event.Event;

public class MemoryConsolidatedEvent extends Event {

    private final String userId;
    private final int promotedCount;
    private final int mergedCount;
    private final long durationMs;

    public MemoryConsolidatedEvent(String source, String userId, int promotedCount,
                                   int mergedCount, long durationMs) {
        super(source, "MEMORY_CONSOLIDATED");
        this.userId = userId;
        this.promotedCount = promotedCount;
        this.mergedCount = mergedCount;
        this.durationMs = durationMs;
    }

    public String getUserId() { return userId; }
    public int getPromotedCount() { return promotedCount; }
    public int getMergedCount() { return mergedCount; }
    public long getDurationMs() { return durationMs; }
}
