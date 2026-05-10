package lyjew.com.lyclaw.memory.impl;

import lyjew.com.lyclaw.memory.MemoryEntry;
import lyjew.com.lyclaw.memory.MemoryQuery;
import lyjew.com.lyclaw.memory.MemoryQueryResult;
import lyjew.com.lyclaw.memory.vector.VectorSearchResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class HybridMemoryRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridMemoryRetriever.class);

    private final InMemoryVectorStore vectorStore;

    public HybridMemoryRetriever(InMemoryVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public MemoryQueryResult retrieve(MemoryQuery query, List<MemoryEntry> candidates) {
        long start = System.currentTimeMillis();

        // 1. 向量相似度得分
        Map<String, Double> vectorScores = new HashMap<>();
        if (query.getQueryEmbedding() != null && query.getQueryEmbedding().length > 0) {
            List<VectorSearchResult> vsResults = vectorStore.search(query.getQueryEmbedding(),
                    Math.max(candidates.size(), query.getTopK()));
            for (VectorSearchResult vsr : vsResults) {
                vectorScores.put(vsr.getId(), vsr.getScore());
            }
        }

        // 2. BM25 关键词得分
        Map<String, Double> bm25Scores = computeBM25(query.getQueryText(), candidates);

        // 3. 混合排序
        List<ScoredEntry> scored = new ArrayList<>();
        for (MemoryEntry entry : candidates) {
            double vectorScore = vectorScores.getOrDefault(entry.getEntryId(), 0.0);
            double bm25Score = bm25Scores.getOrDefault(entry.getEntryId(), 0.0);
            double temporalScore = entry.getTemporal() != null ? entry.getTemporal().computeDecay() : 1.0;
            double importanceScore = entry.getImportance();

            double combined = query.getAlpha() * vectorScore
                    + query.getBeta() * bm25Score
                    + query.getGamma() * temporalScore
                    + query.getDelta() * importanceScore;

            scored.add(new ScoredEntry(entry, combined));
        }

        scored.sort(Comparator.comparingDouble(ScoredEntry::score).reversed());

        int topK = Math.min(query.getTopK(), scored.size());
        List<MemoryEntry> result = new ArrayList<>(topK);
        for (int i = 0; i < topK; i++) {
            MemoryEntry e = scored.get(i).entry();
            e.incrementAccess();
            result.add(e);
        }

        long elapsed = System.currentTimeMillis() - start;
        log.debug("Hybrid retrieve: {} candidates, {} results in {}ms", candidates.size(), result.size(), elapsed);

        return MemoryQueryResult.builder()
                .entries(result)
                .totalHits(candidates.size())
                .queryTimeMs(elapsed)
                .retrievalMethod("hybrid_vector_bm25_temporal")
                .build();
    }

    private Map<String, Double> computeBM25(String queryText, List<MemoryEntry> candidates) {
        if (queryText == null || queryText.isBlank()) {
            return Collections.emptyMap();
        }

        String[] queryTerms = queryText.toLowerCase().split("\\s+");
        if (queryTerms.length == 0) {
            return Collections.emptyMap();
        }

        // 计算平均文档长度
        double totalLength = 0;
        for (MemoryEntry e : candidates) {
            if (e.getContent() != null) {
                totalLength += e.getContent().length();
            }
        }
        double avgdl = candidates.isEmpty() ? 100.0 : totalLength / candidates.size();

        // 计算 DF (document frequency) for each term
        Map<String, Integer> df = new HashMap<>();
        for (String term : queryTerms) {
            int count = 0;
            for (MemoryEntry e : candidates) {
                if (e.getContent() != null && e.getContent().toLowerCase().contains(term)) {
                    count++;
                }
            }
            df.put(term, count);
        }

        int N = candidates.size();
        double k1 = 1.5;
        double b = 0.75;

        Map<String, Double> scores = new HashMap<>();
        for (MemoryEntry entry : candidates) {
            String content = entry.getContent();
            if (content == null) continue;
            content = content.toLowerCase();
            int dl = content.length();

            double score = 0.0;
            for (String term : queryTerms) {
                int tf = countOccurrences(content, term);
                if (tf == 0) continue;

                int docFreq = df.getOrDefault(term, 0);
                double idf = Math.log(1.0 + (N - docFreq + 0.5) / (docFreq + 0.5));

                double numerator = tf * (k1 + 1.0);
                double denominator = tf + k1 * (1.0 - b + b * dl / Math.max(avgdl, 1.0));
                score += idf * numerator / denominator;
            }
            scores.put(entry.getEntryId(), score);
        }

        return scores;
    }

    private int countOccurrences(String text, String term) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(term, idx)) != -1) {
            count++;
            idx += term.length();
        }
        return count;
    }

    private record ScoredEntry(MemoryEntry entry, double score) {}
}
