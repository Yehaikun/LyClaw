package lyjew.com.lyclaw.memory;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 记忆系统的默认空实现，在记忆系统重新设计之前作为占位 bean。
 * 所有查询返回空结果，写入操作静默忽略。
 */
@Component
public class DefaultMemorySystem implements MemorySystem {

    @Override
    public MemoryEntry ingestPerception(String sessionId, PerceptionData data) {
        return MemoryEntry.builder()
                .entryId("noop-" + System.currentTimeMillis())
                .content(data != null ? data.getContent() : "")
                .build();
    }

    @Override
    public MemoryEntry storeShortTerm(String sessionId, MemoryEntry entry) {
        return entry;
    }

    @Override
    public MemoryEntry commitLongTerm(MemoryEntry entry) {
        return entry;
    }

    @Override
    public MemoryQueryResult retrieve(MemoryQuery query) {
        return MemoryQueryResult.builder()
                .entries(Collections.emptyList())
                .totalHits(0)
                .queryTimeMs(0)
                .build();
    }

    @Override
    public List<MemoryEntry> getShortTermMemories(String sessionId) {
        return Collections.emptyList();
    }

    @Override
    public List<MemoryEntry> getRelevantLongTerm(float[] contextEmbedding, int topK) {
        return Collections.emptyList();
    }

    @Override
    public void consolidate(String userId, MemoryConsolidationPolicy policy) {
    }

    @Override
    public void evictExpiredPerceptions() {
    }

    @Override
    public MemoryStats getStats() {
        return MemoryStats.builder()
                .perceptionCount(0)
                .shortTermCount(0)
                .longTermCount(0)
                .totalTokens(0)
                .build();
    }

    @Override
    public List<MemoryEntry> getLongTermEntries() {
        return Collections.emptyList();
    }

    @Override
    public void removeLongTermEntry(String entryId) {
    }
}
