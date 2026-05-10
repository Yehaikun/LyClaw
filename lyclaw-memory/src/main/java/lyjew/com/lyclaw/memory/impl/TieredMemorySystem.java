package lyjew.com.lyclaw.memory.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.*;
import lyjew.com.lyclaw.memory.retriever.MemoryRetriever;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TieredMemorySystem implements MemorySystem {

    private final ConcurrentHashMap<String, MemoryEntry> perceptionStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MemoryEntry> shortTermStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MemoryEntry> longTermStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, EntityMemory> entityStore = new ConcurrentHashMap<>();

    private final MemoryRetriever memoryRetriever;
    private long lastConsolidationTime;
    private long lastJanitorRunTime;

    public TieredMemorySystem(MemoryRetriever memoryRetriever) {
        this.memoryRetriever = memoryRetriever;
    }

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

    @Override
    public void upsertEntity(EntityMemory entity) {
        String key = entity.getEntityType() + ":" + entity.getEntityId();
        EntityMemory existing = entityStore.get(key);
        entity.setVersion(existing != null ? existing.getVersion() + 1 : 1L);
        entity.setUpdatedAt(System.currentTimeMillis());
        entityStore.put(key, entity);
        log.debug("Upserted entity {} (v{})", key, entity.getVersion());
    }

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

    @Override
    public List<MemoryEntry> getShortTermMemories(String sessionId) {
        if (sessionId == null) return List.copyOf(shortTermStore.values());
        return shortTermStore.values().stream()
                .filter(e -> sessionId.equals(e.getSessionId())).collect(Collectors.toList());
    }

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

    @Override
    public Optional<EntityMemory> getEntity(String entityType, String entityId) {
        return Optional.ofNullable(entityStore.get(entityType + ":" + entityId));
    }

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

    @Override
    public void evictExpiredPerceptions() {
        perceptionStore.entrySet().removeIf(e -> e.getValue().getTemporal() != null && e.getValue().getTemporal().isExpired());
        shortTermStore.entrySet().removeIf(e -> e.getValue().getTemporal() != null && e.getValue().getTemporal().isExpired());
        longTermStore.entrySet().removeIf(e -> e.getValue().getTemporal() != null && e.getValue().getTemporal().isExpired());
        lastJanitorRunTime = System.currentTimeMillis();
    }

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

    List<MemoryEntry> getLongTermEntries() { return List.copyOf(longTermStore.values()); }

    void removeLongTermEntry(String entryId) { longTermStore.remove(entryId); }

    private boolean matchesMetadata(Map<String, Object> entryMeta, Map<String, Object> filterMeta) {
        for (Map.Entry<String, Object> f : filterMeta.entrySet()) {
            Object value = entryMeta.get(f.getKey());
            if (value == null || !value.equals(f.getValue())) return false;
        }
        return true;
    }

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
        return chineseChars + (otherChars / 4);
    }

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
