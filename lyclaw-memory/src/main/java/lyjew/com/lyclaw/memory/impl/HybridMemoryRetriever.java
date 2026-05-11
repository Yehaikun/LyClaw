package lyjew.com.lyclaw.memory.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.MemoryEntry;
import lyjew.com.lyclaw.memory.MemoryQuery;
import lyjew.com.lyclaw.memory.retriever.MemoryRetriever;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合记忆检索器，融合四种评分信号对候选记忆进行综合排序。
 *
 * <p>检索策略：结合向量语义相似度、BM25 关键词匹配、时间衰减和重要性评分，
 * 加权计算每个候选条目的综合分数，取 top-K 返回。</p>
 *
 * <p>评分公式：
 * <pre>
 *   score = alpha * vectorScore + beta * bm25Score + gamma * temporalScore + delta * importanceScore
 * </pre>
 * 其中 alpha、beta、gamma、delta 权重由 MemoryQuery 配置。</p>
 *
 * <p>BM25 实现参考经典的 Okapi BM25 公式，使用 k1=1.5、b=0.75 的经验参数。</p>
 */
@Slf4j
@Component
public class HybridMemoryRetriever implements MemoryRetriever {

    /** 检索方法标识符 */
    private static final String RETRIEVAL_METHOD = "hybrid_vector_bm25_temporal";
    /** BM25 词频饱和参数，控制词频对评分的影响程度 */
    private static final double BM25_K1 = 1.5;
    /** BM25 文档长度归一化参数，0=无归一化，1=完全归一化 */
    private static final double BM25_B = 0.75;

    private final InMemoryVectorStore vectorStore;

    public HybridMemoryRetriever(InMemoryVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 从候选池中检索 top-K 最相关的记忆条目。
     *
     * @param query         查询对象，包含查询文本、嵌入向量和各信号权重
     * @param candidatePool 候选记忆条目列表
     * @return 按综合评分降序排列的结果列表
     */
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

    /** @return 检索方法标识符 */
    @Override
    public String getRetrievalMethod() { return RETRIEVAL_METHOD; }

    /**
     * 计算查询向量与候选条目的余弦相似度评分。
     *
     * @param query      查询对象，包含查询嵌入向量
     * @param candidates 候选记忆列表
     * @return 条目 ID 到向量相似度评分的映射
     */
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

    /**
     * 使用 Okapi BM25 算法计算关键词匹配评分。
     *
     * <p>BM25 公式：score(D,Q) = sum(IDF(q_i) * [tf(q_i,D) * (k1 + 1)] / [tf + k1 * (1 - b + b * |D| / avgdl)])</p>
     *
     * @param queryText  查询文本
     * @param candidates 候选记忆列表
     * @return 条目 ID 到 BM25 评分的映射，查询为空时返回空映射
     */
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

    /**
     * 统计词项在文本中的出现次数（重叠匹配）。
     *
     * @param text 目标文本
     * @param term 要搜索的词项
     * @return 出现次数
     */
    private int countOccurrences(String text, String term) {
        int count = 0;
        int idx = 0;
        int termLen = term.length();
        // 非重叠匹配：每次匹配后跳过词项长度
        while ((idx = text.indexOf(term, idx)) != -1) {
            count++;
            idx += termLen;
        }
        return count;
    }

    /** 内部记录类：包装记忆条目及其评分 */
    private record ScoredEntry(MemoryEntry entry, double score) {}
}
