package lyjew.com.lyclaw.memory.impl;

import lyjew.com.lyclaw.memory.EntityMemory;
import lyjew.com.lyclaw.memory.MemoryConsolidationPolicy;
import lyjew.com.lyclaw.memory.MemoryEntry;
import lyjew.com.lyclaw.memory.MemoryLayerType;
import lyjew.com.lyclaw.memory.MemoryQuery;
import lyjew.com.lyclaw.memory.MemoryQueryResult;
import lyjew.com.lyclaw.memory.MemoryStats;
import lyjew.com.lyclaw.memory.MemorySystem;
import lyjew.com.lyclaw.memory.PerceptionData;
import lyjew.com.lyclaw.memory.TemporalProps;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class TieredMemorySystem implements MemorySystem {

    private static final Logger log = LoggerFactory.getLogger(TieredMemorySystem.class);

    private final ConcurrentHashMap<String, MemoryEntry> perceptionStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MemoryEntry> shortTermStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MemoryEntry> longTermStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, EntityMemory> entityStore = new ConcurrentHashMap<>();

    private long lastConsolidationTime;
    private long lastJanitorRunTime;

    // ========== 写入 ==========

    @Override
    public MemoryEntry ingestPerception(String sessionId, PerceptionData data) {
        String entryId = UUID.randomUUID().toString();
        MemoryEntry entry = MemoryEntry.builder()
                .entryId(entryId)
                .sessionId(sessionId)
                .layer(MemoryLayerType.SENSORY)
                .content(data.getContent())
                .importance(0.5)
                .accessCount(0)
                .temporal(TemporalProps.builder()
                        .createdAt(Instant.now())
                        .lastAccessedAt(Instant.now())
                        .decayFactor(0.1)
                        .strength(1.0)
                        .build())
                .metadata(data.getMetadata())
                .tags(data.getToolCallIds())
                .build();
        perceptionStore.put(entryId, entry);
        log.debug("Ingested perception {} for session {}", entryId, sessionId);
        return entry;
    }

    @Override
    public MemoryEntry storeShortTerm(String sessionId, MemoryEntry entry) {
        entry.setLayer(MemoryLayerType.SHORT_TERM);
        entry.setSessionId(sessionId);
        if (entry.getTemporal() == null) {
            entry.setTemporal(TemporalProps.builder()
                    .createdAt(Instant.now())
                    .lastAccessedAt(Instant.now())
                    .decayFactor(0.05)
                    .strength(1.0)
                    .build());
        }
        shortTermStore.put(entry.getEntryId(), entry);
        log.debug("Stored short-term {} for session {}", entry.getEntryId(), sessionId);
        return entry;
    }

    @Override
    public MemoryEntry commitLongTerm(MemoryEntry entry) {
        entry.setLayer(MemoryLayerType.LONG_TERM);
        if (entry.getTemporal() == null) {
            entry.setTemporal(TemporalProps.builder()
                    .createdAt(Instant.now())
                    .lastAccessedAt(Instant.now())
                    .decayFactor(0.02)
                    .strength(1.0)
                    .build());
        }
        longTermStore.put(entry.getEntryId(), entry);
        log.debug("Committed long-term {}", entry.getEntryId());
        return entry;
    }

    @Override
    public void upsertEntity(EntityMemory entity) {
        String key = entity.getEntityType() + ":" + entity.getEntityId();
        entity.setVersion(entity.getVersion() + 1);
        entity.setUpdatedAt(System.currentTimeMillis());
        entityStore.put(key, entity);
        log.debug("Upserted entity {}", key);
    }

    // ========== 读取 ==========

    @Override
    public MemoryQueryResult retrieve(MemoryQuery query) {
        long start = System.currentTimeMillis();

        List<MemoryEntry> all = new ArrayList<>();
        all.addAll(perceptionStore.values());
        all.addAll(shortTermStore.values());
        all.addAll(longTermStore.values());

        // 按层级过滤
        if (query.getLayerFilter() != null && !query.getLayerFilter().isEmpty()) {
            all = all.stream()
                    .filter(e -> query.getLayerFilter().contains(e.getLayer()))
                    .collect(Collectors.toList());
        }

        // 按类别过滤
        if (query.getCategoryFilter() != null && !query.getCategoryFilter().isEmpty()) {
            all = all.stream()
                    .filter(e -> e.getCategory() != null && query.getCategoryFilter().contains(e.getCategory()))
                    .collect(Collectors.toList());
        }

        // 按标签过滤
        if (query.getTagFilter() != null && !query.getTagFilter().isEmpty()) {
            all = all.stream()
                    .filter(e -> e.getTags() != null && !Collections.disjoint(e.getTags(), query.getTagFilter()))
                    .collect(Collectors.toList());
        }

        // 按元数据过滤
        if (query.getMetadataFilter() != null && !query.getMetadataFilter().isEmpty()) {
            all = all.stream()
                    .filter(e -> e.getMetadata() != null && matchesMetadata(e.getMetadata(), query.getMetadataFilter()))
                    .collect(Collectors.toList());
        }

        // 按相关度排序
        all.sort((a, b) -> Double.compare(
                b.computeRelevanceScore(query.getAlpha(), query.getBeta(), query.getGamma(), query.getDelta()),
                a.computeRelevanceScore(query.getAlpha(), query.getBeta(), query.getGamma(), query.getDelta())));

        // 限制 topK
        int topK = Math.min(query.getTopK(), all.size());
        List<MemoryEntry> result = all.subList(0, topK);

        for (MemoryEntry e : result) {
            e.incrementAccess();
        }

        long elapsed = System.currentTimeMillis() - start;
        log.debug("Retrieve completed: {} hits in {}ms", all.size(), elapsed);

        return MemoryQueryResult.builder()
                .entries(result)
                .totalHits(all.size())
                .queryTimeMs(elapsed)
                .retrievalMethod("tiered_ranked")
                .build();
    }

    @Override
    public List<MemoryEntry> getShortTermMemories(String sessionId) {
        return shortTermStore.values().stream()
                .filter(e -> sessionId.equals(e.getSessionId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryEntry> getRelevantLongTerm(float[] contextEmbedding, int topK) {
        return longTermStore.values().stream()
                .limit(topK)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<EntityMemory> getEntity(String entityType, String entityId) {
        String key = entityType + ":" + entityId;
        return Optional.ofNullable(entityStore.get(key));
    }

    // ========== 生命周期 ==========

    @Override
    public void consolidate(String userId, MemoryConsolidationPolicy policy) {
        log.info("Consolidating memories for user {} with threshold {}", userId, policy.getImportanceThreshold());
        int promoted = 0;
        int maxBatch = Math.min(policy.getMaxBatchSize(), shortTermStore.size());

        Iterator<Map.Entry<String, MemoryEntry>> it = shortTermStore.entrySet().iterator();
        while (it.hasNext() && promoted < maxBatch) {
            Map.Entry<String, MemoryEntry> kv = it.next();
            MemoryEntry entry = kv.getValue();
            if (userId.equals(entry.getUserId()) && entry.getImportance() >= policy.getImportanceThreshold()) {
                it.remove();
                commitLongTerm(entry);
                promoted++;
            }
        }

        lastConsolidationTime = System.currentTimeMillis();
        log.info("Consolidated: {} entries promoted to long-term memory for user {}", promoted, userId);
    }

    @Override
    public void evictExpiredPerceptions() {
        int before = perceptionStore.size();
        perceptionStore.entrySet().removeIf(e -> {
            TemporalProps t = e.getValue().getTemporal();
            return t != null && t.isExpired();
        });
        int evicted = before - perceptionStore.size();
        lastJanitorRunTime = System.currentTimeMillis();
        if (evicted > 0) {
            log.debug("Evicted {} expired perceptions", evicted);
        }
    }

    @Override
    public MemoryStats getStats() {
        long totalTokens = 0;
        double totalImportance = 0;
        int scoredCount = 0;

        for (MemoryEntry e : perceptionStore.values()) {
            if (e.getContent() != null) totalTokens += e.getContent().length() / 4;
            totalImportance += e.getImportance();
            scoredCount++;
        }
        for (MemoryEntry e : shortTermStore.values()) {
            if (e.getContent() != null) totalTokens += e.getContent().length() / 4;
            totalImportance += e.getImportance();
            scoredCount++;
        }
        for (MemoryEntry e : longTermStore.values()) {
            if (e.getContent() != null) totalTokens += e.getContent().length() / 4;
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

    // ========== 内部辅助 ==========

    private boolean matchesMetadata(Map<String, Object> entryMeta, Map<String, Object> filterMeta) {
        for (Map.Entry<String, Object> f : filterMeta.entrySet()) {
            Object value = entryMeta.get(f.getKey());
            if (value == null || !value.equals(f.getValue())) {
                return false;
            }
        }
        return true;
    }
}
