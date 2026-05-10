package lyjew.com.lyclaw.memory.impl;

import lyjew.com.lyclaw.memory.vector.VectorSearchResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class InMemoryVectorStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorStore.class);

    private final ConcurrentHashMap<String, float[]> embeddings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> payloads = new ConcurrentHashMap<>();

    public void store(String id, float[] embedding, String payload) {
        if (id == null || embedding == null) {
            log.warn("Invalid store request: id={}, embedding={}", id, embedding);
            return;
        }
        embeddings.put(id, embedding);
        if (payload != null) {
            payloads.put(id, payload);
        }
        log.debug("Stored vector {} with dimension {}", id, embedding.length);
    }

    public List<VectorSearchResult> search(float[] query, int topK) {
        if (query == null || query.length == 0) {
            log.warn("Empty query vector");
            return List.of();
        }

        return embeddings.entrySet().stream()
                .map(e -> {
                    double score = cosineSimilarity(query, e.getValue());
                    return VectorSearchResult.builder()
                            .id(e.getKey())
                            .score(score)
                            .metadata(Map.of("payload", payloads.getOrDefault(e.getKey(), "")))
                            .build();
                })
                .filter(r -> r.getScore() > 0)
                .sorted(Comparator.comparingDouble(VectorSearchResult::getScore).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    public int size() {
        return embeddings.size();
    }

    public void clear() {
        embeddings.clear();
        payloads.clear();
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        if (denominator == 0.0) {
            return 0.0;
        }
        return dot / denominator;
    }
}
