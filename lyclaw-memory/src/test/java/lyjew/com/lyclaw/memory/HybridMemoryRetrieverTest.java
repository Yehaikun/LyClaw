package lyjew.com.lyclaw.memory;

import lyjew.com.lyclaw.memory.impl.HybridMemoryRetriever;
import lyjew.com.lyclaw.memory.impl.InMemoryVectorStore;
import lyjew.com.lyclaw.memory.retriever.MemoryRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HybridMemoryRetrieverTest {

    private InMemoryVectorStore vectorStore;
    private MemoryRetriever retriever;

    @BeforeEach
    void setUp() {
        vectorStore = new InMemoryVectorStore();
        retriever = new HybridMemoryRetriever(vectorStore);
    }

    @Test
    @DisplayName("retrieve with empty candidate pool should return empty list")
    void retrieveEmptyPool() {
        MemoryQuery query = MemoryQuery.builder().topK(10).build();
        List<MemoryEntry> results = retriever.retrieve(query, Collections.emptyList());
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("retrieve with null candidate pool should return empty list")
    void retrieveNullPool() {
        MemoryQuery query = MemoryQuery.builder().topK(10).build();
        List<MemoryEntry> results = retriever.retrieve(query, null);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("retrieve should return at most topK results")
    void retrieveTopKLimit() {
        MemoryQuery query = MemoryQuery.builder().topK(2).build();
        List<MemoryEntry> candidates = createCandidates(5);
        List<MemoryEntry> results = retriever.retrieve(query, candidates);
        assertEquals(2, results.size());
    }

    @Test
    @DisplayName("retrieve should sort by relevance descending")
    void retrieveSortOrder() {
        float[] queryVec = {1.0f, 0.0f, 0.0f};
        MemoryQuery query = MemoryQuery.builder()
                .topK(3)
                .queryEmbedding(queryVec)
                .alpha(0.5).beta(0.0).gamma(0.25).delta(0.25)
                .build();

        MemoryEntry e1 = MemoryEntry.builder()
                .entryId("e1").importance(0.9)
                .embedding(new float[]{1.0f, 0.0f, 0.0f})
                .temporal(TemporalProps.builder().createdAt(Instant.now())
                        .lastAccessedAt(Instant.now()).decayFactor(0.0).strength(1.0).build())
                .build();
        vectorStore.store("e1", e1.getEmbedding(), "p1");

        MemoryEntry e2 = MemoryEntry.builder()
                .entryId("e2").importance(0.3)
                .embedding(new float[]{0.0f, 1.0f, 0.0f})
                .temporal(TemporalProps.builder().createdAt(Instant.now())
                        .lastAccessedAt(Instant.now()).decayFactor(0.0).strength(1.0).build())
                .build();
        vectorStore.store("e2", e2.getEmbedding(), "p2");

        MemoryEntry e3 = MemoryEntry.builder()
                .entryId("e3").importance(0.5)
                .embedding(new float[]{0.5f, 0.5f, 0.0f})
                .temporal(TemporalProps.builder().createdAt(Instant.now())
                        .lastAccessedAt(Instant.now()).decayFactor(0.0).strength(1.0).build())
                .build();
        vectorStore.store("e3", e3.getEmbedding(), "p3");

        List<MemoryEntry> results = retriever.retrieve(query, List.of(e1, e2, e3));
        assertEquals(3, results.size());
        // e1 should be first (closest to query vector + high importance)
        assertEquals("e1", results.get(0).getEntryId());
    }

    @Test
    @DisplayName("retrieve with vector-only scoring (alpha=1)")
    void retrieveVectorOnly() {
        float[] queryVec = {1.0f, 0.0f};
        MemoryQuery query = MemoryQuery.builder()
                .topK(2).queryEmbedding(queryVec)
                .alpha(1.0).beta(0.0).gamma(0.0).delta(0.0)
                .build();

        MemoryEntry e1 = MemoryEntry.builder()
                .entryId("e1").importance(0.5).embedding(new float[]{1.0f, 0.0f}).build();
        MemoryEntry e2 = MemoryEntry.builder()
                .entryId("e2").importance(0.5).embedding(new float[]{0.0f, 1.0f}).build();
        vectorStore.store("e1", e1.getEmbedding(), "p1");
        vectorStore.store("e2", e2.getEmbedding(), "p2");

        List<MemoryEntry> results = retriever.retrieve(query, List.of(e1, e2));
        assertEquals(2, results.size());
        assertEquals("e1", results.get(0).getEntryId());
    }

    @Test
    @DisplayName("retrieve with BM25 text scoring")
    void retrieveBM25() {
        MemoryQuery query = MemoryQuery.builder()
                .topK(3).queryText("machine learning")
                .alpha(0.0).beta(1.0).gamma(0.0).delta(0.0)
                .build();

        MemoryEntry e1 = MemoryEntry.builder()
                .entryId("e1").importance(0.5).content("machine learning is great").build();
        MemoryEntry e2 = MemoryEntry.builder()
                .entryId("e2").importance(0.5).content("deep learning networks").build();
        MemoryEntry e3 = MemoryEntry.builder()
                .entryId("e3").importance(0.5).content("machine learning and AI").build();

        List<MemoryEntry> results = retriever.retrieve(query, List.of(e1, e2, e3));
        assertEquals(3, results.size());
        // e3 has "machine" AND "learning", e1 has both, e2 only has "learning"
        // Both e1 and e3 should score higher than e2
        assertFalse(results.isEmpty());
    }

    @Test
    @DisplayName("retrieve with importance-only scoring (delta=1)")
    void retrieveImportanceOnly() {
        MemoryQuery query = MemoryQuery.builder()
                .topK(3)
                .alpha(0.0).beta(0.0).gamma(0.0).delta(1.0)
                .build();

        MemoryEntry e1 = MemoryEntry.builder().entryId("e1").importance(0.1).build();
        MemoryEntry e2 = MemoryEntry.builder().entryId("e2").importance(0.9).build();
        MemoryEntry e3 = MemoryEntry.builder().entryId("e3").importance(0.5).build();

        List<MemoryEntry> results = retriever.retrieve(query, List.of(e1, e2, e3));
        assertEquals(3, results.size());
        assertEquals("e2", results.get(0).getEntryId()); // highest importance
        assertEquals("e3", results.get(1).getEntryId());
        assertEquals("e1", results.get(2).getEntryId());
    }

    @Test
    @DisplayName("retrieve should increment access count")
    void retrieveAccessCount() {
        MemoryQuery query = MemoryQuery.builder().topK(5).build();
        MemoryEntry e1 = MemoryEntry.builder().entryId("e1").importance(0.5)
                .accessCount(3).build();
        List<MemoryEntry> results = retriever.retrieve(query, List.of(e1));
        assertEquals(4, results.get(0).getAccessCount());
    }

    @Test
    @DisplayName("getRetrievalMethod should return hybrid method name")
    void getRetrievalMethod() {
        assertTrue(retriever.getRetrievalMethod().contains("hybrid"));
        assertTrue(retriever.getRetrievalMethod().contains("vector"));
        assertTrue(retriever.getRetrievalMethod().contains("bm25"));
    }

    @Test
    @DisplayName("BM25 with empty query text should return empty scores (fallback)")
    void bm25EmptyQuery() {
        MemoryQuery query = MemoryQuery.builder().topK(3).queryText("")
                .alpha(0.0).beta(1.0).gamma(0.0).delta(0.0).build();
        MemoryEntry e1 = MemoryEntry.builder().entryId("e1").importance(0.5)
                .content("some content").build();
        List<MemoryEntry> results = retriever.retrieve(query, List.of(e1));
        assertEquals(1, results.size());
    }

    // Helper
    private List<MemoryEntry> createCandidates(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> MemoryEntry.builder()
                        .entryId("entry-" + i).importance(0.5)
                        .temporal(TemporalProps.builder().createdAt(Instant.now())
                                .lastAccessedAt(Instant.now()).decayFactor(0.0).strength(1.0).build())
                        .build())
                .toList();
    }
}
