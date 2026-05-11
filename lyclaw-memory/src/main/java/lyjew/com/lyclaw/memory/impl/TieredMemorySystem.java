package lyjew.com.lyclaw.memory.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.*;
import lyjew.com.lyclaw.memory.retriever.MemoryRetriever;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 三层记忆系统实现，模拟人类记忆的多层结构进行信息管理。
 *
 * <p>架构基于认知心理学中的 <b>Atkinson-Shiffrin 多层记忆模型</b>，
 * 将记忆划分为四个存储层，信息从感知层逐级向上流动：</p>
 * <ol>
 *   <li><b>感知层 (Sensory)</b> — 原始输入，未经加工，默认衰减因子 0.1</li>
 *   <li><b>短期记忆 (Short-Term)</b> — 经过提取的精炼信息，衰减因子 0.05</li>
 *   <li><b>长期记忆 (Long-Term)</b> — 永久保存的核心知识，衰减因子 0.02</li>
 *   <li><b>实体记忆 (Entity)</b> — 结构化的实体属性信息</li>
 * </ol>
 *
 * <p>核心流程：原始感知 → 提取 → 短期记忆 → 合并推广 → 长期记忆。
 * 其中合并操作由 {@link lyjew.com.lyclaw.memory.consolidate.MemoryConsolidator} 负责，
 * 清理操作由 {@link lyjew.com.lyclaw.memory.janitor.MemoryJanitor} 负责。</p>
 *
 * <p>所有存储层均使用 {@link ConcurrentHashMap} 以保证线程安全，
 * 适合高并发 Agent 写入场景。</p>
 */
@Slf4j
@Service
public class TieredMemorySystem implements MemorySystem {

    /** 感知层存储 */
    private final ConcurrentHashMap<String, MemoryEntry> perceptionStore = new ConcurrentHashMap<>();
    /** 短期记忆层存储 */
    private final ConcurrentHashMap<String, MemoryEntry> shortTermStore = new ConcurrentHashMap<>();
    /** 长期记忆层存储 */
    private final ConcurrentHashMap<String, MemoryEntry> longTermStore = new ConcurrentHashMap<>();
    /** 实体记忆存储 */
    private final ConcurrentHashMap<String, EntityMemory> entityStore = new ConcurrentHashMap<>();

    private final MemoryRetriever memoryRetriever;
    /** 上次合并操作的时间戳 */
    private long lastConsolidationTime;
    /** 上次清理操作的时间戳 */
    private long lastJanitorRunTime;

    public TieredMemorySystem(MemoryRetriever memoryRetriever) {
        this.memoryRetriever = memoryRetriever;
    }

    /**
     * 将原始感知数据摄入感知层。
     *
     * @param sessionId 会话标识
     * @param data      感知数据（角色、内容、时间戳等）
     * @return 新创建的感知记忆条目
     */
    @Override
    public MemoryEntry ingestPerception(String sessionId, PerceptionData data) {
        String entryId = UUID.randomUUID().toString();

        TemporalProps temporal = TemporalProps.builder()
                .createdAt(Instant.now()).lastAccessedAt(Instant.now())
                .decayFactor(0.1).strength(1.0).build();

        MemoryEntry entry = MemoryEntry.builder()
                .entryId(entryId).sessionId(sessionId).layer(MemoryLayerType.SENSORY)
                .content(data.getContent()).importance(0.5).accessCount(0)
                .temporal(temporal).tags(data.getToolCallIds()).metadata(data.getMetadata())
                .build();

        perceptionStore.put(entryId, entry);
        log.debug("Ingested perception {} for session {} ({} chars)",
                entryId, sessionId, data.getContent() != null ? data.getContent().length() : 0);
        return entry;
    }

    /**
     * 将条目存入短期记忆层，设置衰减因子为 0.05。
     *
     * <p>对超过 200 字符的内容自动生成摘要（截取前 200 字符）。</p>
     *
     * @param sessionId 会话标识
     * @param entry     要存储的记忆条目
     * @return 更新后的记忆条目
     */
    @Override
    public MemoryEntry storeShortTerm(String sessionId, MemoryEntry entry) {
        entry.setLayer(MemoryLayerType.SHORT_TERM);
        entry.setSessionId(sessionId);

        if (entry.getTemporal() == null) {
            entry.setTemporal(TemporalProps.builder()
                    .createdAt(Instant.now()).lastAccessedAt(Instant.now())
                    .decayFactor(0.05).strength(1.0).build());
        } else {
            entry.getTemporal().setLastAccessedAt(Instant.now());
        }

        entry.setSummary(entry.getContent() != null && entry.getContent().length() > 200
                ? entry.getContent().substring(0, 200) + "..." : entry.getContent());
        entry.incrementAccess();
        shortTermStore.put(entry.getEntryId(), entry);
        log.debug("Stored short-term {} for session {}", entry.getEntryId(), sessionId);
        return entry;
    }

    /**
     * 将条目提交到长期记忆层，衰减因子降为 0.02（几乎不衰减）。
     *
     * @param entry 要提交的记忆条目
     * @return 更新后的记忆条目
     */
    @Override
    public MemoryEntry commitLongTerm(MemoryEntry entry) {
        entry.setLayer(MemoryLayerType.LONG_TERM);

        if (entry.getTemporal() == null) {
            entry.setTemporal(TemporalProps.builder()
                    .createdAt(Instant.now()).lastAccessedAt(Instant.now())
                    .decayFactor(0.02).strength(1.0).build());
        } else {
            entry.getTemporal().setDecayFactor(0.02);
            entry.getTemporal().setLastAccessedAt(Instant.now());
        }

        longTermStore.put(entry.getEntryId(), entry);
        log.debug("Committed long-term {}", entry.getEntryId());
        return entry;
    }

    /**
     * 插入或更新实体记忆，自动递增版本号。
     *
     * @param entity 要 upsert 的实体记忆对象
     */
    @Override
    public void upsertEntity(EntityMemory entity) {
        // 实体键 = 实体类型:实体ID
        String key = entity.getEntityType() + ":" + entity.getEntityId();
        EntityMemory existing = entityStore.get(key);
        // 版本号自增
        entity.setVersion(existing != null ? existing.getVersion() + 1 : 1L);
        entity.setUpdatedAt(System.currentTimeMillis());
        entityStore.put(key, entity);
        log.debug("Upserted entity {} (v{})", key, entity.getVersion());
    }

    /**
     * 执行多维度过滤和排序的记忆检索。
     *
     * <p>过滤顺序：层级过滤 → 类别过滤 → 标签过滤 → 元数据过滤，
     * 最后交由 {@link MemoryRetriever} 进行混合排序。</p>
     *
     * @param query 检索查询对象
     * @return 包含排序结果和统计信息的查询结果
     */
    @Override
    public MemoryQueryResult retrieve(MemoryQuery query) {
        long start = System.currentTimeMillis();

        List<MemoryEntry> candidates = new ArrayList<>();
        if (query.getLayerFilter() == null || query.getLayerFilter().isEmpty()) {
            candidates.addAll(perceptionStore.values());
            candidates.addAll(shortTermStore.values());
            candidates.addAll(longTermStore.values());
        } else {
            if (query.getLayerFilter().contains(MemoryLayerType.SENSORY)) candidates.addAll(perceptionStore.values());
            if (query.getLayerFilter().contains(MemoryLayerType.SHORT_TERM)) candidates.addAll(shortTermStore.values());
            if (query.getLayerFilter().contains(MemoryLayerType.LONG_TERM)) candidates.addAll(longTermStore.values());
        }

        if (query.getCategoryFilter() != null && !query.getCategoryFilter().isEmpty()) {
            candidates = candidates.stream()
                    .filter(e -> e.getCategory() != null && query.getCategoryFilter().contains(e.getCategory()))
                    .collect(Collectors.toList());
        }

        if (query.getTagFilter() != null && !query.getTagFilter().isEmpty()) {
            candidates = candidates.stream()
                    .filter(e -> e.getTags() != null && !Collections.disjoint(e.getTags(), query.getTagFilter()))
                    .collect(Collectors.toList());
        }

        if (query.getMetadataFilter() != null && !query.getMetadataFilter().isEmpty()) {
            candidates = candidates.stream()
                    .filter(e -> e.getMetadata() != null && matchesMetadata(e.getMetadata(), query.getMetadataFilter()))
                    .collect(Collectors.toList());
        }

        int totalHits = candidates.size();
        List<MemoryEntry> ranked = candidates.isEmpty() ? Collections.emptyList()
                : memoryRetriever.retrieve(query, candidates);

        long elapsed = System.currentTimeMillis() - start;
        log.debug("Retrieve completed: {} candidates -> {} ranked results in {}ms (method={})",
                totalHits, ranked.size(), elapsed, memoryRetriever.getRetrievalMethod());

        return MemoryQueryResult.builder()
                .entries(ranked).totalHits(totalHits).queryTimeMs(elapsed)
                .retrievalMethod(memoryRetriever.getRetrievalMethod()).build();
    }

    /**
     * 获取指定会话的短期记忆，sessionId 为 null 时返回全部。
     */
    @Override
    public List<MemoryEntry> getShortTermMemories(String sessionId) {
        if (sessionId == null) return List.copyOf(shortTermStore.values());
        return shortTermStore.values().stream()
                .filter(e -> sessionId.equals(e.getSessionId())).collect(Collectors.toList());
    }

    /**
     * 根据上下文嵌入向量或时间排序获取 top-K 相关的长期记忆。
     * 有嵌入时按余弦相似度排序，无嵌入时按创建时间降序排列。
     */
    @Override
    public List<MemoryEntry> getRelevantLongTerm(float[] contextEmbedding, int topK) {
        if (longTermStore.isEmpty()) return Collections.emptyList();

        if (contextEmbedding != null && contextEmbedding.length > 0) {
            return longTermStore.values().stream()
                    .sorted((a, b) -> Double.compare(
                            computeRelevance(b, contextEmbedding),
                            computeRelevance(a, contextEmbedding)))
                    .limit(topK).collect(Collectors.toList());
        }

        return longTermStore.values().stream()
                .sorted((a, b) -> {
                    long ta = a.getTemporal() != null && a.getTemporal().getCreatedAt() != null
                            ? a.getTemporal().getCreatedAt().toEpochMilli() : 0;
                    long tb = b.getTemporal() != null && b.getTemporal().getCreatedAt() != null
                            ? b.getTemporal().getCreatedAt().toEpochMilli() : 0;
                    return Long.compare(tb, ta);
                }).limit(topK).collect(Collectors.toList());
    }

    /** 根据类型和 ID 查找实体记忆。 */
    @Override
    public Optional<EntityMemory> getEntity(String entityType, String entityId) {
        return Optional.ofNullable(entityStore.get(entityType + ":" + entityId));
    }

    /**
     * 根据合并策略将符合条件的短期记忆提升为长期记忆。
     *
     * <p>遍历短期记忆层，将重要性 >= 阈值的条目移除并提交到长期记忆层。</p>
     *
     * @param userId 用户标识，用于过滤用户相关的记忆
     * @param policy 合并策略（阈值、批次大小等）
     */
    @Override
    public void consolidate(String userId, MemoryConsolidationPolicy policy) {
        log.info("Consolidating memories for user {} with threshold={}, maxBatch={}",
                userId, policy.getImportanceThreshold(), policy.getMaxBatchSize());

        int promoted = 0;
        int maxBatch = Math.min(policy.getMaxBatchSize(), shortTermStore.size());

        Iterator<Map.Entry<String, MemoryEntry>> it = shortTermStore.entrySet().iterator();
        while (it.hasNext() && promoted < maxBatch) {
            Map.Entry<String, MemoryEntry> kv = it.next();
            MemoryEntry entry = kv.getValue();

            boolean matchesUser = userId == null || entry.getUserId() == null || userId.equals(entry.getUserId());

            if (matchesUser && entry.getImportance() >= policy.getImportanceThreshold()) {
                it.remove();
                commitLongTerm(entry);
                promoted++;
            }
        }

        lastConsolidationTime = System.currentTimeMillis();
        log.info("Consolidated: {} entries promoted to long-term memory for user {}", promoted, userId);
    }

    /** 清理所有层级中已过期的记忆条目，并更新清理时间戳。 */
    @Override
    public void evictExpiredPerceptions() {
        perceptionStore.entrySet().removeIf(e -> e.getValue().getTemporal() != null && e.getValue().getTemporal().isExpired());
        shortTermStore.entrySet().removeIf(e -> e.getValue().getTemporal() != null && e.getValue().getTemporal().isExpired());
        longTermStore.entrySet().removeIf(e -> e.getValue().getTemporal() != null && e.getValue().getTemporal().isExpired());
        lastJanitorRunTime = System.currentTimeMillis();
    }

    /**
     * 获取记忆系统的统计信息，包括各层条目数、总 token 数、平均重要性等。
     */
    @Override
    public MemoryStats getStats() {
        long totalTokens = 0L;
        double totalImportance = 0.0;
        int scoredCount = 0;

        for (MemoryEntry e : perceptionStore.values()) {
            totalTokens += estimateTokens(e.getContent());
            totalImportance += e.getImportance();
            scoredCount++;
        }
        for (MemoryEntry e : shortTermStore.values()) {
            totalTokens += estimateTokens(e.getContent());
            totalImportance += e.getImportance();
            scoredCount++;
        }
        for (MemoryEntry e : longTermStore.values()) {
            totalTokens += estimateTokens(e.getContent());
            totalImportance += e.getImportance();
            scoredCount++;
        }

        return MemoryStats.builder()
                .perceptionCount(perceptionStore.size())
                .shortTermCount(shortTermStore.size())
                .longTermCount(longTermStore.size())
                .entityCount(entityStore.size())
                .totalTokens(totalTokens)
                .avgImportance(scoredCount > 0 ? totalImportance / scoredCount : 0.0)
                .lastConsolidationTime(lastConsolidationTime)
                .lastJanitorRunTime(lastJanitorRunTime)
                .build();
    }

    /** 获取长期记忆层的不可变快照（包级访问，供 Janitor 使用）。 */
    List<MemoryEntry> getLongTermEntries() { return List.copyOf(longTermStore.values()); }

    /** 从长期记忆层移除指定条目（包级访问，供 Janitor 使用）。 */
    void removeLongTermEntry(String entryId) { longTermStore.remove(entryId); }

    /**
     * 检查条目元数据是否与过滤条件匹配（所有条件必须同时满足）。
     */
    private boolean matchesMetadata(Map<String, Object> entryMeta, Map<String, Object> filterMeta) {
        for (Map.Entry<String, Object> f : filterMeta.entrySet()) {
            Object value = entryMeta.get(f.getKey());
            if (value == null || !value.equals(f.getValue())) return false;
        }
        return true;
    }

    /**
     * 估算文本的 token 数量。
     * 中文字符计为 1 token，其他字符按 1/4 token 估算。
     */
    private long estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0L;
        int chineseChars = 0, otherChars = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        // 中文按 1 token/字，英文按 4 字/token
        return chineseChars + (otherChars / 4);
    }

    /** 计算记忆条目与上下文嵌入向量的余弦相似度作为相关度评分。 */
    private double computeRelevance(MemoryEntry entry, float[] contextEmbedding) {
        float[] entryEmbedding = entry.getEmbedding();
        if (entryEmbedding == null || entryEmbedding.length != contextEmbedding.length) return 0.0;

        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < contextEmbedding.length; i++) {
            dot += (double) entryEmbedding[i] * contextEmbedding[i];
            normA += (double) entryEmbedding[i] * entryEmbedding[i];
            normB += (double) contextEmbedding[i] * contextEmbedding[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator < 1e-12 ? 0.0 : dot / denominator;
    }
}
