package lyjew.com.lyclaw.memory.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.vector.VectorSearchResult;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的向量存储，使用 ConcurrentHashMap 实现线程安全的嵌入向量索引。
 *
 * <p>该类是轻量级的向量数据库替代方案，提供向量的增删查操作。
 * 搜索采用暴力 (brute-force) 余弦相似度计算，配合优先队列 (最小堆) 实现 top-K 检索。</p>
 *
 * <p>数据存储在三张并发哈希表中：
 * <ul>
 *   <li>{@code embeddings} — ID 到向量的映射</li>
 *   <li>{@code payloads} — ID 到负载文本的映射</li>
 *   <li>{@code metadataStore} — ID 到元数据的映射</li>
 * </ul>
 * </p>
 *
 * <p>适用场景：小规模数据集（数千条记录）的原型开发和测试。
 * 生产环境应替换为专业的向量数据库（如 Milvus、Qdrant、Pinecone）。</p>
 */
@Slf4j
@Component
public class InMemoryVectorStore {

    /** 向量存储映射 */
    private final ConcurrentHashMap<String, float[]> embeddings = new ConcurrentHashMap<>();
    /** 负载文本存储映射 */
    private final ConcurrentHashMap<String, String> payloads = new ConcurrentHashMap<>();
    /** 元数据存储映射 */
    private final ConcurrentHashMap<String, Map<String, Object>> metadataStore = new ConcurrentHashMap<>();

    /**
     * 存储向量及其关联的负载文本。
     *
     * @param id        唯一标识符，为空时跳过存储
     * @param embedding 浮点向量，为空时跳过存储
     * @param payload   关联的文本负载，可为 null
     */
    public void store(String id, float[] embedding, String payload) {
        if (id == null || embedding == null) {
            log.warn("Invalid store request: id={}, embedding={}", id, embedding);
            return;
        }
        embeddings.put(id, embedding);
        if (payload != null) payloads.put(id, payload);
        log.debug("Stored vector {} with dimension {}", id, embedding.length);
    }

    /**
     * 存储向量及其负载文本和元数据。
     *
     * @param id        唯一标识符
     * @param embedding 浮点向量
     * @param payload   关联的文本负载
     * @param metadata  附加元数据映射
     */
    public void store(String id, float[] embedding, String payload, Map<String, Object> metadata) {
        store(id, embedding, payload);
        if (metadata != null) metadataStore.put(id, metadata);
    }

    /**
     * 使用余弦相似度搜索 top-K 最相似的向量。
     *
     * <p>算法：遍历所有存储的向量，使用最小堆维护前 K 个最高相似度的结果。
     * 当相似度 <= 0 且堆已满时跳过，以提升性能。</p>
     *
     * @param query 查询向量，为空时返回空列表
     * @param topK  返回的最大结果数
     * @return 按相似度降序排列的搜索结果列表
     */
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

    /** 删除指定 ID 的向量及其关联数据。 */
    public void delete(String id) {
        embeddings.remove(id);
        payloads.remove(id);
        metadataStore.remove(id);
        log.debug("Deleted vector {}", id);
    }

    /** @return 当前存储的向量总数 */
    public int size() { return embeddings.size(); }

    /** 清空所有存储的向量、负载和元数据。 */
    public void clear() {
        embeddings.clear();
        payloads.clear();
        metadataStore.clear();
        log.debug("Vector store cleared");
    }

    /** @return 是否包含指定 ID 的向量 */
    public boolean contains(String id) { return embeddings.containsKey(id); }

    /** @return 指定 ID 对应的向量，不存在时返回 null */
    public float[] getEmbedding(String id) { return embeddings.get(id); }

    /**
     * 计算两个向量之间的余弦相似度。
     *
     * <p>余弦相似度 = dot(A,B) / (||A|| * ||B||)</p>
     *
     * @param a 向量 A
     * @param b 向量 B
     * @return [0, 1] 范围内的相似度值，维度不匹配或范数为零时返回 0
     */
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
