package lyjew.com.lyclaw.memory;

import lyjew.com.lyclaw.memory.impl.InMemoryVectorStore;
import lyjew.com.lyclaw.memory.vector.VectorSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryVectorStoreTest {

    private InMemoryVectorStore vectorStore;

    @BeforeEach
    void setUp() {
        vectorStore = new InMemoryVectorStore();
        vectorStore.clear();
    }

    @Test
    @DisplayName("Store valid vector should succeed")
    void storeValid() {
        vectorStore.store("id1", new float[]{1.0f, 0.0f, 0.0f}, "payload1");
        assertEquals(1, vectorStore.size());
        assertTrue(vectorStore.contains("id1"));
    }

    @Test
    @DisplayName("Store null id should be handled gracefully")
    void storeNullId() {
        vectorStore.store(null, new float[]{1.0f, 0.0f}, "payload");
        assertEquals(0, vectorStore.size());
    }

    @Test
    @DisplayName("Store null embedding should be handled gracefully")
    void storeNullEmbedding() {
        vectorStore.store("id1", null, "payload");
        assertEquals(0, vectorStore.size());
    }

    @Test
    @DisplayName("Store with metadata should succeed")
    void storeWithMetadata() {
        vectorStore.store("id1", new float[]{1.0f, 0.0f}, "payload",
                Map.of("key", "value"));
        assertEquals(1, vectorStore.size());
    }

    @Test
    @DisplayName("Search with null query should return empty list")
    void searchNullQuery() {
        vectorStore.store("id1", new float[]{1.0f, 0.0f, 0.0f}, "p1");
        List<VectorSearchResult> results = vectorStore.search(null, 10);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("Search with empty query vector should return empty list")
    void searchEmptyQuery() {
        vectorStore.store("id1", new float[]{1.0f, 0.0f, 0.0f}, "p1");
        List<VectorSearchResult> results = vectorStore.search(new float[0], 10);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("Search on empty store should return empty list")
    void searchEmptyStore() {
        List<VectorSearchResult> results = vectorStore.search(new float[]{1.0f, 0.0f}, 10);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("Search should return topK results sorted by score descending")
    void searchTopK() {
        // Store vectors pointing in different directions
        vectorStore.store("a", new float[]{1.0f, 0.0f}, "payload-a");
        vectorStore.store("b", new float[]{0.0f, 1.0f}, "payload-b");
        vectorStore.store("c", new float[]{0.707f, 0.707f}, "payload-c");
        vectorStore.store("d", new float[]{-1.0f, 0.0f}, "payload-d");

        // Query close to vector a
        List<VectorSearchResult> results = vectorStore.search(new float[]{1.0f, 0.1f}, 3);
        assertEquals(3, results.size());

        // First result should be closest to query (highest cosine sim)
        assertEquals("a", results.get(0).getId());
        assertTrue(results.get(0).getScore() > 0.9); // nearly 1.0
        assertTrue(results.get(0).getScore() > results.get(2).getScore());
    }

    @Test
    @DisplayName("Search with topK larger than store size should return all results")
    void searchTopKLarger() {
        vectorStore.store("a", new float[]{1.0f, 0.0f}, "pa");
        vectorStore.store("b", new float[]{0.0f, 1.0f}, "pb");
        List<VectorSearchResult> results = vectorStore.search(new float[]{1.0f, 0.1f}, 100);
        assertEquals(2, results.size());
    }

    @Test
    @DisplayName("Cosine similarity between identical vectors should be 1.0")
    void cosineSimilarityIdentical() {
        float[] v = {1.0f, 2.0f, 3.0f};
        double sim = vectorStore.cosineSimilarity(v, v);
        assertEquals(1.0, sim, 1e-6);
    }

    @Test
    @DisplayName("Cosine similarity between orthogonal vectors should be 0.0")
    void cosineSimilarityOrthogonal() {
        float[] a = {1.0f, 0.0f, 0.0f};
        float[] b = {0.0f, 1.0f, 0.0f};
        double sim = vectorStore.cosineSimilarity(a, b);
        assertEquals(0.0, sim, 1e-6);
    }

    @Test
    @DisplayName("Cosine similarity with dimension mismatch should return 0.0")
    void cosineSimilarityDimensionMismatch() {
        float[] a = {1.0f, 0.0f};
        float[] b = {1.0f, 0.0f, 0.0f};
        double sim = vectorStore.cosineSimilarity(a, b);
        assertEquals(0.0, sim, 1e-6);
    }

    @Test
    @DisplayName("Cosine similarity with null vectors should return 0.0")
    void cosineSimilarityNull() {
        float[] a = {1.0f, 0.0f};
        assertEquals(0.0, vectorStore.cosineSimilarity(null, a), 1e-6);
        assertEquals(0.0, vectorStore.cosineSimilarity(a, null), 1e-6);
        assertEquals(0.0, vectorStore.cosineSimilarity(null, null), 1e-6);
    }

    @Test
    @DisplayName("Cosine similarity with zero vectors should return 0.0")
    void cosineSimilarityZeroVector() {
        float[] zero = {0.0f, 0.0f};
        float[] vec = {1.0f, 0.0f};
        double sim = vectorStore.cosineSimilarity(zero, vec);
        assertEquals(0.0, sim, 1e-6);
    }

    @Test
    @DisplayName("Delete should remove vector")
    void delete() {
        vectorStore.store("id1", new float[]{1.0f, 0.0f}, "p1");
        assertEquals(1, vectorStore.size());
        vectorStore.delete("id1");
        assertEquals(0, vectorStore.size());
        assertFalse(vectorStore.contains("id1"));
    }

    @Test
    @DisplayName("Clear should remove all vectors")
    void clear() {
        vectorStore.store("a", new float[]{1.0f, 0.0f}, "pa");
        vectorStore.store("b", new float[]{0.0f, 1.0f}, "pb");
        vectorStore.clear();
        assertEquals(0, vectorStore.size());
    }

    @Test
    @DisplayName("getEmbedding should return stored vector")
    void getEmbedding() {
        float[] vec = {1.0f, 2.0f, 3.0f};
        vectorStore.store("id1", vec, "p1");
        assertArrayEquals(vec, vectorStore.getEmbedding("id1"), 1e-6f);
    }

    @Test
    @DisplayName("getEmbedding for unknown id should return null")
    void getEmbeddingUnknown() {
        assertNull(vectorStore.getEmbedding("unknown"));
    }
}
