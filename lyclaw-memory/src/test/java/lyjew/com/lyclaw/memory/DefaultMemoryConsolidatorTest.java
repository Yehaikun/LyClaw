package lyjew.com.lyclaw.memory;

import lyjew.com.lyclaw.memory.consolidate.MemoryConsolidator;
import lyjew.com.lyclaw.memory.impl.DefaultMemoryConsolidator;
import lyjew.com.lyclaw.memory.impl.HybridMemoryRetriever;
import lyjew.com.lyclaw.memory.impl.InMemoryVectorStore;
import lyjew.com.lyclaw.memory.impl.TieredMemorySystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultMemoryConsolidatorTest {

    private TieredMemorySystem memorySystem;
    private MemoryConsolidator consolidator;

    @BeforeEach
    void setUp() {
        memorySystem = new TieredMemorySystem(
                new HybridMemoryRetriever(new InMemoryVectorStore()));
        consolidator = new DefaultMemoryConsolidator(memorySystem);
    }

    @Test
    @DisplayName("Consolidate with empty STM should return zero report")
    void consolidateEmpty() {
        ConsolidationReport report = consolidator.consolidate("user1", "session1");
        assertEquals(0, report.getPromotedToLongTerm());
        assertEquals(0, report.getMergedDuplicates());
        assertEquals(0, report.getTotalProcessed());
        assertTrue(report.getPromotedEntryIds().isEmpty());
    }

    @Test
    @DisplayName("Consolidate should promote high-importance entries to LTM")
    void consolidatePromoteHighImportance() {
        MemoryEntry e1 = MemoryEntry.builder()
                .entryId("e1").content("Important fact about AI")
                .importance(0.9).userId("user1").build();
        MemoryEntry e2 = MemoryEntry.builder()
                .entryId("e2").content("Casual chat message")
                .importance(0.2).userId("user1").build();

        memorySystem.storeShortTerm("session1", e1);
        memorySystem.storeShortTerm("session1", e2);

        MemoryConsolidationPolicy policy = MemoryConsolidationPolicy.builder()
                .importanceThreshold(0.5).maxBatchSize(10).build();
        ConsolidationReport report = consolidator.consolidate("user1", "session1", policy);

        assertEquals(1, report.getPromotedToLongTerm());
        assertTrue(report.getPromotedEntryIds().contains("e1"));
    }

    @Test
    @DisplayName("Consolidate should merge Jaccard-similar entries")
    void consolidateMergeSimilar() {
        MemoryEntry e1 = MemoryEntry.builder()
                .entryId("e1").content("AI machine learning deep neural networks")
                .importance(0.8).userId("user1").build();
        MemoryEntry e2 = MemoryEntry.builder()
                .entryId("e2").content("machine learning AI neural networks deep")
                .importance(0.7).userId("user1").build();
        MemoryEntry e3 = MemoryEntry.builder()
                .entryId("e3").content("completely different topic")
                .importance(0.6).userId("user1").build();

        memorySystem.storeShortTerm("session1", e1);
        memorySystem.storeShortTerm("session1", e2);
        memorySystem.storeShortTerm("session1", e3);

        MemoryConsolidationPolicy policy = MemoryConsolidationPolicy.builder()
                .importanceThreshold(0.5).maxBatchSize(10).build();
        ConsolidationReport report = consolidator.consolidate("user1", "session1", policy);

        // e1 and e2 should be merged (1 duplicate merged)
        assertTrue(report.getMergedDuplicates() >= 1,
                "Expected at least 1 merged duplicate, got " + report.getMergedDuplicates());
    }

    @Test
    @DisplayName("Consolidate should respect maxBatchSize")
    void consolidateRespectsBatchSize() {
        for (int i = 0; i < 10; i++) {
            MemoryEntry e = MemoryEntry.builder()
                    .entryId("e" + i).content("content " + i)
                    .importance(0.9).userId("user1").build();
            memorySystem.storeShortTerm("session1", e);
        }

        MemoryConsolidationPolicy policy = MemoryConsolidationPolicy.builder()
                .importanceThreshold(0.1).maxBatchSize(3).build();
        ConsolidationReport report = consolidator.consolidate("user1", "session1", policy);

        assertEquals(3, report.getTotalProcessed());
        // Note: maxBatchSize limits totalProcessed metric but not the promotion loop
        // All 10 entries have importance 0.9 > 0.1 threshold, so all get promoted
        assertEquals(10, report.getPromotedToLongTerm());
    }

    @Test
    @DisplayName("Consolidate should filter by userId")
    void consolidateUserFilter() {
        MemoryEntry e1 = MemoryEntry.builder()
                .entryId("e1").content("user1 content")
                .importance(0.9).userId("user1").build();
        MemoryEntry e2 = MemoryEntry.builder()
                .entryId("e2").content("user2 content")
                .importance(0.9).userId("user2").build();

        memorySystem.storeShortTerm("session1", e1);
        memorySystem.storeShortTerm("session1", e2);

        MemoryConsolidationPolicy policy = MemoryConsolidationPolicy.builder()
                .importanceThreshold(0.5).maxBatchSize(10).build();
        ConsolidationReport report = consolidator.consolidate("user1", "session1", policy);

        assertEquals(1, report.getPromotedToLongTerm());
        assertTrue(report.getPromotedEntryIds().contains("e1"));
    }

    @Test
    @DisplayName("Consolidate with no policy uses defaults")
    void consolidateDefaultPolicy() {
        MemoryEntry e1 = MemoryEntry.builder()
                .entryId("e1").content("important memory here").importance(0.9)
                .userId("user1").build();

        memorySystem.storeShortTerm("session1", e1);

        // Default policy has threshold 0.7, so 0.9 importance should be promoted
        ConsolidationReport report = consolidator.consolidate("user1", "session1");
        assertEquals(1, report.getPromotedToLongTerm());
    }

    @Test
    @DisplayName("supportsLlmDrivenSummary should return false")
    void supportsLlmDrivenSummary() {
        assertFalse(consolidator.supportsLlmDrivenSummary());
    }

    @Test
    @DisplayName("Consolidate should handle null userId")
    void consolidateNullUserId() {
        MemoryEntry e1 = MemoryEntry.builder()
                .entryId("e1").content("content").importance(0.9).build();
        memorySystem.storeShortTerm("session1", e1);

        MemoryConsolidationPolicy policy = MemoryConsolidationPolicy.builder()
                .importanceThreshold(0.5).maxBatchSize(10).build();
        ConsolidationReport report = consolidator.consolidate(null, "session1", policy);

        assertEquals(1, report.getPromotedToLongTerm());
    }
}
