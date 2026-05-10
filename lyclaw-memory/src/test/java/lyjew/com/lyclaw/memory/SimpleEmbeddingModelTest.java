package lyjew.com.lyclaw.memory;

import lyjew.com.lyclaw.memory.impl.SimpleEmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SimpleEmbeddingModelTest {

    private SimpleEmbeddingModel embeddingModel;

    @BeforeEach
    void setUp() {
        embeddingModel = new SimpleEmbeddingModel();
    }

    @Test
    @DisplayName("getDimension should return 768")
    void getDimension() {
        assertEquals(768, embeddingModel.getDimension());
    }

    @Test
    @DisplayName("getModelName should return 'local-hash-v1'")
    void getModelName() {
        assertEquals("local-hash-v1", embeddingModel.getModelName());
    }

    @Test
    @DisplayName("Embed non-empty text should return 768-dim vector")
    void embedNonEmpty() {
        float[] vector = embeddingModel.embed("Hello, world!");
        assertNotNull(vector);
        assertEquals(768, vector.length);
    }

    @Test
    @DisplayName("Embed null text should return zero vector")
    void embedNull() {
        float[] vector = embeddingModel.embed(null);
        assertEquals(768, vector.length);
        for (float v : vector) {
            assertEquals(0.0f, v, 1e-6f);
        }
    }

    @Test
    @DisplayName("Embed empty text should return zero vector")
    void embedEmpty() {
        float[] vector = embeddingModel.embed("");
        assertEquals(768, vector.length);
        for (float v : vector) {
            assertEquals(0.0f, v, 1e-6f);
        }
    }

    @Test
    @DisplayName("Embedding should be L2-normalized (norm ≈ 1.0)")
    void l2Normalized() {
        float[] vector = embeddingModel.embed("test text for normalization");
        double norm = 0.0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        assertEquals(1.0, norm, 1e-4);
    }

    @Test
    @DisplayName("Same text should produce identical embedding (deterministic)")
    void deterministic() {
        float[] v1 = embeddingModel.embed("Hello, world!");
        float[] v2 = embeddingModel.embed("Hello, world!");
        assertArrayEquals(v1, v2, 1e-6f);
    }

    @Test
    @DisplayName("Different text should produce different embeddings")
    void differentText() {
        float[] v1 = embeddingModel.embed("Hello, world!");
        float[] v2 = embeddingModel.embed("Goodbye, world!");
        boolean differs = false;
        for (int i = 0; i < v1.length; i++) {
            if (Math.abs(v1[i] - v2[i]) > 1e-6f) {
                differs = true;
                break;
            }
        }
        assertTrue(differs, "Different texts should produce different embeddings");
    }

    @Test
    @DisplayName("No NaN or Inf values in embedding")
    void noNanOrInf() {
        float[] vector = embeddingModel.embed("Some random text 12345 !@#$%");
        for (float v : vector) {
            assertFalse(Float.isNaN(v), "Embedding should not contain NaN");
            assertFalse(Float.isInfinite(v), "Embedding should not contain Inf");
        }
    }

    @Test
    @DisplayName("Embed batch should return correct number of vectors")
    void embedBatch() {
        List<float[]> vectors = embeddingModel.embedBatch(
                List.of("Hello", "World", "Test"));
        assertEquals(3, vectors.size());
        for (float[] v : vectors) {
            assertEquals(768, v.length);
        }
    }

    @Test
    @DisplayName("Embed batch with null list should return empty list")
    void embedBatchNull() {
        List<float[]> vectors = embeddingModel.embedBatch(null);
        assertTrue(vectors.isEmpty());
    }

    @Test
    @DisplayName("Embed batch with empty list should return empty list")
    void embedBatchEmpty() {
        List<float[]> vectors = embeddingModel.embedBatch(List.of());
        assertTrue(vectors.isEmpty());
    }

    @Test
    @DisplayName("L2 normalization of zero vector should keep it zero")
    void l2NormZeroVector() {
        float[] vector = embeddingModel.embed("");
        double norm = 0.0;
        for (float v : vector) norm += (double) v * v;
        assertEquals(0.0, norm, 1e-6);
    }
}
