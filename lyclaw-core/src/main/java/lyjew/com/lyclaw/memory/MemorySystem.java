package lyjew.com.lyclaw.memory;

import java.util.List;
import java.util.Optional;

public interface MemorySystem {

    MemoryEntry ingestPerception(String sessionId, PerceptionData data);
    MemoryEntry storeShortTerm(String sessionId, MemoryEntry entry);
    MemoryEntry commitLongTerm(MemoryEntry entry);
    void upsertEntity(EntityMemory entity);
    MemoryQueryResult retrieve(MemoryQuery query);
    List<MemoryEntry> getShortTermMemories(String sessionId);
    List<MemoryEntry> getRelevantLongTerm(float[] contextEmbedding, int topK);
    Optional<EntityMemory> getEntity(String entityType, String entityId);
    void consolidate(String userId, MemoryConsolidationPolicy policy);
    void evictExpiredPerceptions();
    MemoryStats getStats();
}
