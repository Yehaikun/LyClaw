package lyjew.com.lyclaw.memory;

import lyjew.com.lyclaw.memory.impl.HybridMemoryRetriever;
import lyjew.com.lyclaw.memory.impl.InMemoryVectorStore;
import lyjew.com.lyclaw.memory.impl.TieredMemorySystem;
import lyjew.com.lyclaw.memory.retriever.MemoryRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TieredMemorySystemTest {

    private TieredMemorySystem memorySystem;
    private MemoryRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new HybridMemoryRetriever(new InMemoryVectorStore());
        memorySystem = new TieredMemorySystem(retriever);
    }

    @Test
    @DisplayName("ingestPerception should create SENSORY entry")
    void ingestPerceptionSensory() {
        PerceptionData data = PerceptionData.builder()
                .role("user").content("Hello, world!")
                .timestamp(System.currentTimeMillis())
                .toolCallIds(List.of()).metadata(java.util.Map.of()).build();

        MemoryEntry entry = memorySystem.ingestPerception("session-1", data);
        assertNotNull(entry);
        assertNotNull(entry.getEntryId());
        assertEquals("session-1", entry.getSessionId());
        assertEquals(MemoryLayerType.SENSORY, entry.getLayer());
        assertEquals("Hello, world!", entry.getContent());
        assertEquals(0.5, entry.getImportance(), 1e-6);
        assertEquals(0, entry.getAccessCount());
        assertNotNull(entry.getTemporal());
        assertEquals(0.1, entry.getTemporal().getDecayFactor(), 1e-6);
        assertEquals(1.0, entry.getTemporal().getStrength(), 1e-6);
    }

    @Test
    @DisplayName("ingestPerception should set tags from toolCallIds")
    void ingestPerceptionTags() {
        PerceptionData data = PerceptionData.builder()
                .role("user").content("test")
                .timestamp(System.currentTimeMillis())
                .toolCallIds(List.of("tool1", "tool2"))
                .metadata(java.util.Map.of()).build();

        MemoryEntry entry = memorySystem.ingestPerception("s1", data);
        assertEquals(List.of("tool1", "tool2"), entry.getTags());
    }

    @Test
    @DisplayName("storeShortTerm should move entry to SHORT_TERM")
    void storeShortTerm() {
        MemoryEntry entry = MemoryEntry.builder()
                .entryId("test-id").content("Short term memory content")
                .importance(0.8).build();

        MemoryEntry stored = memorySystem.storeShortTerm("session-1", entry);
        assertEquals(MemoryLayerType.SHORT_TERM, stored.getLayer());
        assertEquals("session-1", stored.getSessionId());
        assertNotNull(stored.getTemporal());
        assertEquals(0.05, stored.getTemporal().getDecayFactor(), 1e-6);
        assertEquals(1, stored.getAccessCount());
    }

    @Test
    @DisplayName("storeShortTerm should create summary for long content")
    void storeShortTermSummaryLong() {
        String longContent = "A".repeat(300);
        MemoryEntry entry = MemoryEntry.builder()
                .entryId("test-id").content(longContent).build();

        MemoryEntry stored = memorySystem.storeShortTerm("s1", entry);
        assertNotNull(stored.getSummary());
        assertTrue(stored.getSummary().endsWith("..."));
        assertEquals(203, stored.getSummary().length()); // 200 + "..."
    }

    @Test
    @DisplayName("storeShortTerm should keep content as summary for short content")
    void storeShortTermSummaryShort() {
        String shortContent = "Short";
        MemoryEntry entry = MemoryEntry.builder()
                .entryId("test-id").content(shortContent).build();

        MemoryEntry stored = memorySystem.storeShortTerm("s1", entry);
        assertEquals("Short", stored.getSummary());
    }

    @Test
    @DisplayName("commitLongTerm should move entry to LONG_TERM")
    void commitLongTerm() {
        MemoryEntry entry = MemoryEntry.builder()
                .entryId("ltm-1").content("Long term memory")
                .importance(0.9).build();

        MemoryEntry stored = memorySystem.commitLongTerm(entry);
        assertEquals(MemoryLayerType.LONG_TERM, stored.getLayer());
        assertNotNull(stored.getTemporal());
        assertEquals(0.02, stored.getTemporal().getDecayFactor(), 1e-6);
    }

    @Test
    @DisplayName("upsertEntity should store and version entities")
    void upsertEntity() {
        EntityMemory entity = EntityMemory.builder()
                .entityType("USER").entityId("123")
                .name("Test User").description("A test user")
                .properties(java.util.Map.of()).relations(List.of())
                .build();

        memorySystem.upsertEntity(entity);
        assertEquals(1L, entity.getVersion());

        // Upsert again → version increments
        EntityMemory entity2 = EntityMemory.builder()
                .entityType("USER").entityId("123")
                .name("Test User Updated").description("Updated")
                .properties(java.util.Map.of()).relations(List.of())
                .build();

        memorySystem.upsertEntity(entity2);
        assertEquals(2L, entity2.getVersion());
    }

    @Test
    @DisplayName("getEntity should return stored entity")
    void getEntity() {
        EntityMemory entity = EntityMemory.builder()
                .entityType("USER").entityId("456")
                .name("Entity 456").description("test")
                .properties(java.util.Map.of()).relations(List.of())
                .build();

        memorySystem.upsertEntity(entity);

        Optional<EntityMemory> found = memorySystem.getEntity("USER", "456");
        assertTrue(found.isPresent());
        assertEquals("Entity 456", found.get().getName());
    }

    @Test
    @DisplayName("getEntity for unknown entity should return empty")
    void getEntityUnknown() {
        Optional<EntityMemory> found = memorySystem.getEntity("USER", "nonexistent");
        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("getShortTermMemories should filter by sessionId")
    void getShortTermMemoriesSession() {
        MemoryEntry e1 = MemoryEntry.builder().entryId("e1").content("c1").build();
        MemoryEntry e2 = MemoryEntry.builder().entryId("e2").content("c2").build();

        memorySystem.storeShortTerm("session-A", e1);
        memorySystem.storeShortTerm("session-B", e2);

        List<MemoryEntry> results = memorySystem.getShortTermMemories("session-A");
        assertEquals(1, results.size());
        assertEquals("e1", results.get(0).getEntryId());
    }

    @Test
    @DisplayName("getShortTermMemories with null sessionId should return all")
    void getShortTermMemoriesAll() {
        MemoryEntry e1 = MemoryEntry.builder().entryId("e1").content("c1").build();
        MemoryEntry e2 = MemoryEntry.builder().entryId("e2").content("c2").build();
        memorySystem.storeShortTerm("sA", e1);
        memorySystem.storeShortTerm("sB", e2);

        List<MemoryEntry> results = memorySystem.getShortTermMemories(null);
        assertEquals(2, results.size());
    }

    @Test
    @DisplayName("retrieve with layer filter should only return matching layers")
    void retrieveLayerFilter() {
        // Ingest perception (SENSORY)
        PerceptionData data = PerceptionData.builder()
                .role("user").content("perception content")
                .timestamp(System.currentTimeMillis())
                .toolCallIds(List.of()).metadata(java.util.Map.of()).build();
        memorySystem.ingestPerception("s1", data);

        // Store short-term
        MemoryEntry stm = MemoryEntry.builder()
                .entryId("stm-1").content("STM content").importance(0.8).build();
        memorySystem.storeShortTerm("s1", stm);

        // Query only SHORT_TERM
        MemoryQuery query = MemoryQuery.builder()
                .topK(10).layerFilter(List.of(MemoryLayerType.SHORT_TERM)).build();
        MemoryQueryResult result = memorySystem.retrieve(query);
        assertEquals(1, result.getEntries().size());
        assertEquals("stm-1", result.getEntries().get(0).getEntryId());
    }

    @Test
    @DisplayName("retrieve with tag filter should filter by tags")
    void retrieveTagFilter() {
        PerceptionData data = PerceptionData.builder()
                .role("user").content("tagged content")
                .timestamp(System.currentTimeMillis())
                .toolCallIds(List.of("important", "urgent"))
                .metadata(java.util.Map.of()).build();
        memorySystem.ingestPerception("s1", data);

        MemoryQuery query = MemoryQuery.builder()
                .topK(10).tagFilter(List.of("important")).build();
        MemoryQueryResult result = memorySystem.retrieve(query);
        assertEquals(1, result.getEntries().size());
    }

    @Test
    @DisplayName("retrieve with tag filter (no match) should return empty")
    void retrieveTagFilterNoMatch() {
        PerceptionData data = PerceptionData.builder()
                .role("user").content("content")
                .timestamp(System.currentTimeMillis())
                .toolCallIds(List.of("tagA")).metadata(java.util.Map.of()).build();
        memorySystem.ingestPerception("s1", data);

        MemoryQuery query = MemoryQuery.builder()
                .topK(10).tagFilter(List.of("nonexistent")).build();
        MemoryQueryResult result = memorySystem.retrieve(query);
        assertEquals(0, result.getEntries().size());
    }

    @Test
    @DisplayName("getStats should return correct counts")
    void getStats() {
        PerceptionData data = PerceptionData.builder()
                .role("user").content("stats test")
                .timestamp(System.currentTimeMillis())
                .toolCallIds(List.of()).metadata(java.util.Map.of()).build();
        memorySystem.ingestPerception("s1", data);
        memorySystem.ingestPerception("s1", data);

        MemoryStats stats = memorySystem.getStats();
        assertEquals(2, stats.getPerceptionCount());
        assertEquals(0, stats.getShortTermCount());
        assertEquals(0, stats.getLongTermCount());
        assertTrue(stats.getTotalTokens() > 0);
        assertTrue(stats.getAvgImportance() > 0);
    }

    @Test
    @DisplayName("consolidate should promote high-importance entries to LTM")
    void consolidatePromote() {
        MemoryEntry highImp = MemoryEntry.builder()
                .entryId("high").content("important").importance(0.95)
                .userId("user1").build();
        MemoryEntry lowImp = MemoryEntry.builder()
                .entryId("low").content("not important").importance(0.3)
                .userId("user1").build();

        memorySystem.storeShortTerm("s1", highImp);
        memorySystem.storeShortTerm("s1", lowImp);

        MemoryConsolidationPolicy policy = MemoryConsolidationPolicy.builder()
                .importanceThreshold(0.7).maxBatchSize(10).build();
        memorySystem.consolidate("user1", policy);

        // highImp should now be in LTM
        List<MemoryEntry> ltm = memorySystem.getRelevantLongTerm(null, 10);
        assertEquals(1, ltm.size());
        assertEquals("high", ltm.get(0).getEntryId());
        assertEquals(MemoryLayerType.LONG_TERM, ltm.get(0).getLayer());
    }

    @Test
    @DisplayName("getRelevantLongTerm with embedding should sort by cosine similarity")
    void getRelevantLongTermWithEmbedding() {
        MemoryEntry e1 = MemoryEntry.builder()
                .entryId("e1").content("alpha").importance(0.8)
                .embedding(new float[]{1.0f, 0.0f, 0.0f}).build();
        MemoryEntry e2 = MemoryEntry.builder()
                .entryId("e2").content("beta").importance(0.8)
                .embedding(new float[]{0.0f, 1.0f, 0.0f}).build();

        memorySystem.commitLongTerm(e1);
        memorySystem.commitLongTerm(e2);

        List<MemoryEntry> results = memorySystem.getRelevantLongTerm(
                new float[]{1.0f, 0.1f, 0.0f}, 2);
        assertEquals(2, results.size());
        assertEquals("e1", results.get(0).getEntryId()); // closer to query
    }

    @Test
    @DisplayName("getRelevantLongTerm for empty store should return empty")
    void getRelevantLongTermEmpty() {
        List<MemoryEntry> results = memorySystem.getRelevantLongTerm(null, 10);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("evictExpiredPerceptions should remove expired entries")
    void evictExpiredPerceptions() {
        // Create perception with expired temporal
        PerceptionData data = PerceptionData.builder()
                .role("user").content("fresh").timestamp(System.currentTimeMillis())
                .toolCallIds(List.of()).metadata(java.util.Map.of()).build();
        MemoryEntry entry = memorySystem.ingestPerception("s1", data);

        // Manually set expiresAt to past
        entry.getTemporal().setExpiresAt(Instant.now().minusSeconds(3600));

        memorySystem.evictExpiredPerceptions();
        MemoryStats stats = memorySystem.getStats();
        assertEquals(0, stats.getPerceptionCount());
    }

    @Test
    @DisplayName("getRelevantLongTerm without embedding should sort by recency")
    void getRelevantLongTermNoEmbedding() {
        TemporalProps oldTime = TemporalProps.builder()
                .createdAt(Instant.now().minusSeconds(7200))
                .lastAccessedAt(Instant.now()).decayFactor(0.02).strength(1.0).build();
        TemporalProps newTime = TemporalProps.builder()
                .createdAt(Instant.now())
                .lastAccessedAt(Instant.now()).decayFactor(0.02).strength(1.0).build();

        MemoryEntry e1 = MemoryEntry.builder()
                .entryId("old").content("old").importance(0.8)
                .temporal(oldTime).build();
        MemoryEntry e2 = MemoryEntry.builder()
                .entryId("new").content("new").importance(0.8)
                .temporal(newTime).build();

        memorySystem.commitLongTerm(e1);
        memorySystem.commitLongTerm(e2);

        List<MemoryEntry> results = memorySystem.getRelevantLongTerm(null, 2);
        assertEquals(2, results.size());
        assertEquals("new", results.get(0).getEntryId()); // newer first
    }
}
