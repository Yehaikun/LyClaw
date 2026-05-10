package lyjew.com.lyclaw.memory;

import lyjew.com.lyclaw.memory.controller.MemoryController;
import lyjew.com.lyclaw.memory.impl.HybridMemoryRetriever;
import lyjew.com.lyclaw.memory.impl.InMemoryVectorStore;
import lyjew.com.lyclaw.memory.impl.TieredMemorySystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryControllerTest {

    private MemoryController controller;
    private TieredMemorySystem memorySystem;

    @BeforeEach
    void setUp() {
        memorySystem = new TieredMemorySystem(
                new HybridMemoryRetriever(new InMemoryVectorStore()));
        controller = new MemoryController(memorySystem);
    }

    @Test
    @DisplayName("GET /api/memory/stats should return stats with non-negative counts")
    void statsEndpoint() {
        MemoryStats stats = controller.stats();
        assertNotNull(stats);
        assertTrue(stats.getPerceptionCount() >= 0);
        assertTrue(stats.getShortTermCount() >= 0);
        assertTrue(stats.getLongTermCount() >= 0);
        assertTrue(stats.getEntityCount() >= 0);
    }

    @Test
    @DisplayName("POST /api/memory/ingest should return ingested entry info")
    void ingestEndpoint() {
        PerceptionData data = PerceptionData.builder()
                .role("user").content("Hello from test")
                .timestamp(System.currentTimeMillis())
                .toolCallIds(List.of()).metadata(Map.of()).build();

        Map<String, Object> result = controller.ingest(data, "test-session", "test-user");
        assertNotNull(result);
        assertEquals("ingested", result.get("status"));
        assertNotNull(result.get("entryId"));
        assertEquals("SENSORY", result.get("layer"));
    }

    @Test
    @DisplayName("POST /api/memory/ingest with default userId")
    void ingestDefaultUserId() {
        PerceptionData data = PerceptionData.builder()
                .role("user").content("Test")
                .timestamp(System.currentTimeMillis())
                .toolCallIds(List.of()).metadata(Map.of()).build();

        Map<String, Object> result = controller.ingest(data, "test-session", "default");
        assertNotNull(result);
        assertEquals("ingested", result.get("status"));
    }

    @Test
    @DisplayName("POST /api/memory/retrieve should return query results")
    void retrieveEndpoint() {
        // First ingest something
        PerceptionData data = PerceptionData.builder()
                .role("user").content("test retrieve content")
                .timestamp(System.currentTimeMillis())
                .toolCallIds(List.of()).metadata(Map.of()).build();
        memorySystem.ingestPerception("test-session", data);

        MemoryQuery query = MemoryQuery.builder().topK(10).build();
        MemoryQueryResult result = controller.retrieve(query);
        assertNotNull(result);
        assertTrue(result.getTotalHits() >= 0);
        assertNotNull(result.getEntries());
    }

    @Test
    @DisplayName("POST /api/memory/consolidate should return success map")
    void consolidateEndpoint() {
        // First store a short-term memory
        MemoryEntry entry = MemoryEntry.builder()
                .entryId("test-entry").content("Test content")
                .importance(0.9).userId("test-user").build();
        memorySystem.storeShortTerm("test-session", entry);

        Map<String, Object> result = controller.consolidate("test-user", "test-session");
        assertNotNull(result);
        assertEquals("test-user", result.get("userId"));
        assertEquals("test-session", result.get("sessionId"));
        assertEquals("consolidated", result.get("status"));
    }
}
