package lyjew.com.lyclaw.memory.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.vector.VectorSearchResult;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class InMemoryVectorStore {

    private final ConcurrentHashMap<String, float[]> embeddings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> payloads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Object>> metadataStore = new ConcurrentHashMap<>();

    public void store(String id, float[] embedding, String payload) {
        if (id == null || embedding == null) {
            log.warn("Invalid store request: id={}, embedding={}", id, embedding);
            return;
        }
        embeddings.put(id, embedding);
        if (payload != null) payloads.put(id, payload);
        log.debug("Stored vector {} with dimension {}", id, embedding.length);
    }

    public void store(String id, float[] embedding, String payload, Map<String, Object> metadata) {
        store(id, embedding, payload);
        if (metadata != null) metadataStore.put(id, metadata);
    }

    public List<VectorSearchResult> search(float[] query, int topK) {
        if (query == null || query.length == 0) {
            log.warn("Empty query vector, returning empty results");
            return Collections.emptyList();
        }
        if (embeddings.isEmpty()) {
            log.debug("Vector store is empty, returning empty results");
            return Collections.emptyList();
        }

        int k = Math.min(topK, embeddings.size());

        PriorityQueue<VectorSearchResult> minHeap = new PriorityQueue<>(
                Comparator.comparingDouble(VectorSearchResult::getScore));

        for (Map.Entry<String, float[]> entry : embeddings.entrySet()) {
            double score = cosineSimilarity(query, entry.getValue());
            if (score <= 0.0 && minHeap.size() >= k) continue;

            VectorSearchResult result = VectorSearchResult.builder()
                    .id(entry.getKey())
                    .score(score)
                    .metadata(Map.of("payload", payloads.getOrDefault(entry.getKey(), ""),
                            "metadata", metadataStore.getOrDefault(entry.getKey(), Collections.emptyMap())))
                    .build();

            if (minHeap.size() < k) {
                minHeap.offer(result);
            } else if (score > minHeap.peek().getScore()) {
                minHeap.poll();
                minHeap.offer(result);
            }
        }

        List<VectorSearchResult> results = new ArrayList<>(minHeap);
        results.sort(Comparator.comparingDouble(VectorSearchResult::getScore).reversed());

        log.debug("Vector search returned {} results for topK={} (store size={})",
                results.size(), topK, embeddings.size());
        return results;
    }

    public void delete(String id) {
        embeddings.remove(id);
        payloads.remove(id);
        metadataStore.remove(id);
        log.debug("Deleted vector {}", id);
    }

    public int size() { return embeddings.size(); }

    public void clear() {
        embeddings.clear();
        payloads.clear();
        metadataStore.clear();
        log.debug("Vector store cleared");
    }

    public boolean contains(String id) { return embeddings.containsKey(id); }

    public float[] getEmbedding(String id) { return embeddings.get(id); }

    public double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return 0.0;
        if (a.length != b.length) {
            log.debug("Dimension mismatch: {} vs {}", a.length, b.length);
            return 0.0;
        }

        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        if (denominator < 1e-12) return 0.0;

        return Math.max(0.0, Math.min(1.0, dot / denominator));
    }
}
