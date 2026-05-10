package lyjew.com.lyclaw.memory.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.MemoryEntry;
import lyjew.com.lyclaw.memory.MemoryQuery;
import lyjew.com.lyclaw.memory.retriever.MemoryRetriever;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class HybridMemoryRetriever implements MemoryRetriever {

    private static final String RETRIEVAL_METHOD = "hybrid_vector_bm25_temporal";
    private static final double BM25_K1 = 1.5;
    private static final double BM25_B = 0.75;

    private final InMemoryVectorStore vectorStore;

    public HybridMemoryRetriever(InMemoryVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public List<MemoryEntry> retrieve(MemoryQuery query, List<MemoryEntry> candidatePool) {
        if (candidatePool == null || candidatePool.isEmpty()) {
            log.debug("Empty candidate pool, returning empty results");
            return Collections.emptyList();
        }

        long start = System.currentTimeMillis();
        int topK = Math.min(query.getTopK(), candidatePool.size());

        Map<String, Double> vectorScores = computeVectorScores(query, candidatePool);
        Map<String, Double> bm25Scores = computeBM25(query.getQueryText(), candidatePool);

        double alpha = query.getAlpha();
        double beta = query.getBeta();
        double gamma = query.getGamma();
        double delta = query.getDelta();

        PriorityQueue<ScoredEntry> minHeap = new PriorityQueue<>(
                Comparator.comparingDouble(ScoredEntry::score));

        for (MemoryEntry entry : candidatePool) {
            double vectorScore = vectorScores.getOrDefault(entry.getEntryId(), 0.0);
            double bm25Score = bm25Scores.getOrDefault(entry.getEntryId(), 0.0);
            double temporalScore = entry.getTemporal() != null ? entry.getTemporal().computeDecay() : 1.0;
            double importanceScore = entry.getImportance();

            double combined = alpha * vectorScore + beta * bm25Score
                    + gamma * temporalScore + delta * importanceScore;

            if (combined <= 0.0 && minHeap.size() >= topK) continue;

            ScoredEntry se = new ScoredEntry(entry, combined);

            if (minHeap.size() < topK) {
                minHeap.offer(se);
            } else if (combined > minHeap.peek().score()) {
                minHeap.poll();
                minHeap.offer(se);
            }
        }

        List<MemoryEntry> result = new ArrayList<>();
        List<ScoredEntry> sorted = new ArrayList<>(minHeap);
        sorted.sort(Comparator.comparingDouble(ScoredEntry::score).reversed());

        for (ScoredEntry se : sorted) {
            MemoryEntry e = se.entry();
            e.incrementAccess();
            result.add(e);
        }

        long elapsed = System.currentTimeMillis() - start;
        log.debug("Hybrid retrieve: {} candidates, {} results in {}ms", candidatePool.size(), result.size(), elapsed);

        return result;
    }

    @Override
    public String getRetrievalMethod() { return RETRIEVAL_METHOD; }

    private Map<String, Double> computeVectorScores(MemoryQuery query, List<MemoryEntry> candidates) {
        Map<String, Double> scores = new HashMap<>();
        if (query.getQueryEmbedding() == null || query.getQueryEmbedding().length == 0) return scores;

        float[] queryVec = query.getQueryEmbedding();
        for (MemoryEntry entry : candidates) {
            float[] entryVec = entry.getEmbedding();
            if (entryVec == null) entryVec = vectorStore.getEmbedding(entry.getEntryId());
            if (entryVec != null) scores.put(entry.getEntryId(), vectorStore.cosineSimilarity(queryVec, entryVec));
        }
        return scores;
    }

    private Map<String, Double> computeBM25(String queryText, List<MemoryEntry> candidates) {
        if (queryText == null || queryText.isBlank()) return Collections.emptyMap();

        String[] queryTerms = queryText.toLowerCase().split("\\s+");
        if (queryTerms.length == 0) return Collections.emptyMap();

        double totalLength = candidates.stream()
                .mapToDouble(e -> e.getContent() != null ? e.getContent().length() : 0).sum();
        double avgdl = totalLength / Math.max(1, candidates.size());

        Map<String, Integer> df = new HashMap<>();
        for (String term : queryTerms) {
            int count = 0;
            for (MemoryEntry e : candidates) {
                if (e.getContent() != null && e.getContent().toLowerCase().contains(term)) count++;
            }
            df.put(term, count);
        }

        int N = candidates.size();
        Map<String, Double> scores = new HashMap<>();

        for (MemoryEntry entry : candidates) {
            String content = entry.getContent();
            if (content == null || content.isEmpty()) continue;
            String lowerContent = content.toLowerCase();
            int dl = lowerContent.length();

            double score = 0.0;
            for (String term : queryTerms) {
                int tf = countOccurrences(lowerContent, term);
                if (tf == 0) continue;

                int docFreq = df.getOrDefault(term, 0);
                double idf = Math.log(1.0 + (N - docFreq + 0.5) / (docFreq + 0.5));
                double numerator = tf * (BM25_K1 + 1.0);
                double denominator = tf + BM25_K1 * (1.0 - BM25_B + BM25_B * dl / Math.max(avgdl, 1.0));
                score += idf * numerator / denominator;
            }
            scores.put(entry.getEntryId(), score);
        }

        return scores;
    }

    private int countOccurrences(String text, String term) {
        int count = 0;
        int idx = 0;
        int termLen = term.length();
        while ((idx = text.indexOf(term, idx)) != -1) {
            count++;
            idx += termLen;
        }
        return count;
    }

    private record ScoredEntry(MemoryEntry entry, double score) {}
}
