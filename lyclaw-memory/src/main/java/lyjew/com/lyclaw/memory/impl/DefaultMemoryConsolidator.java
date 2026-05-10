package lyjew.com.lyclaw.memory.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.*;
import lyjew.com.lyclaw.memory.consolidate.MemoryConsolidator;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DefaultMemoryConsolidator implements MemoryConsolidator {

    private static final double KEYWORD_OVERLAP_THRESHOLD = 0.6;

    private final MemorySystem memorySystem;

    public DefaultMemoryConsolidator(MemorySystem memorySystem) {
        this.memorySystem = memorySystem;
    }

    @Override
    public ConsolidationReport consolidate(String userId, String sessionId) {
        return consolidate(userId, sessionId, MemoryConsolidationPolicy.builder().build());
    }

    @Override
    public ConsolidationReport consolidate(String userId, String sessionId,
                                            MemoryConsolidationPolicy policy) {
        long start = System.currentTimeMillis();

        List<MemoryEntry> shortTermMemories = memorySystem.getShortTermMemories(sessionId);
        List<MemoryEntry> userMemories = shortTermMemories.stream()
                .filter(e -> userId == null || userId.equals(e.getUserId()))
                .collect(Collectors.toList());

        if (userMemories.isEmpty()) {
            log.debug("No short-term memories to consolidate for user {}", userId);
            return ConsolidationReport.builder().promotedToLongTerm(0).mergedDuplicates(0)
                    .expiredRemoved(0).totalProcessed(0)
                    .durationMs(System.currentTimeMillis() - start)
                    .promotedEntryIds(Collections.emptyList()).build();
        }

        int totalProcessed = Math.min(userMemories.size(), policy.getMaxBatchSize());

        List<Set<String>> tokenizedEntries = new ArrayList<>();
        for (MemoryEntry entry : userMemories) {
            tokenizedEntries.add(tokenize(entry.getContent()));
        }

        int n = userMemories.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double overlap = jaccardSimilarity(tokenizedEntries.get(i), tokenizedEntries.get(j));
                if (overlap >= KEYWORD_OVERLAP_THRESHOLD) {
                    union(parent, i, j);
                }
            }
        }

        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            groups.computeIfAbsent(root, k -> new ArrayList<>()).add(i);
        }

        int promotedToLongTerm = 0;
        int mergedDuplicates = 0;
        List<String> promotedEntryIds = new ArrayList<>();

        for (List<Integer> group : groups.values()) {
            if (group.size() > 1) {
                mergedDuplicates += group.size() - 1;

                int bestIdx = group.get(0);
                double bestImportance = userMemories.get(bestIdx).getImportance();
                for (int idx : group) {
                    if (userMemories.get(idx).getImportance() > bestImportance) {
                        bestImportance = userMemories.get(idx).getImportance();
                        bestIdx = idx;
                    }
                }

                MemoryEntry bestEntry = userMemories.get(bestIdx);
                StringBuilder mergedContent = new StringBuilder(
                        bestEntry.getContent() != null ? bestEntry.getContent() : "");
                for (int idx : group) {
                    if (idx == bestIdx) continue;
                    MemoryEntry other = userMemories.get(idx);
                    if (other.getContent() != null && !other.getContent().isEmpty()) {
                        mergedContent.append("; ").append(other.getContent());
                    }
                    if (other.getImportance() > bestEntry.getImportance()) {
                        bestEntry.setImportance(other.getImportance());
                    }
                }
                bestEntry.setSummary(truncate(mergedContent.toString(), 500));
            }

            for (int idx : group) {
                MemoryEntry entry = userMemories.get(idx);
                if (entry.getImportance() >= policy.getImportanceThreshold()) {
                    entry.setLayer(MemoryLayerType.LONG_TERM);
                    if (entry.getTemporal() != null) {
                        entry.getTemporal().setDecayFactor(0.02);
                    }
                    memorySystem.commitLongTerm(entry);
                    promotedToLongTerm++;
                    promotedEntryIds.add(entry.getEntryId());
                }
            }
        }

        int expiredBefore = (int) memorySystem.getStats().getPerceptionCount();
        memorySystem.evictExpiredPerceptions();
        int expiredAfter = (int) memorySystem.getStats().getPerceptionCount();
        int expiredRemoved = expiredBefore - expiredAfter;

        long elapsed = System.currentTimeMillis() - start;
        log.info("Consolidation complete for user {} / session {}: promoted={}, merged={}, expired={}, totalProcessed={}, durationMs={}",
                userId, sessionId, promotedToLongTerm, mergedDuplicates, expiredRemoved, totalProcessed, elapsed);

        return ConsolidationReport.builder()
                .promotedToLongTerm(promotedToLongTerm)
                .mergedDuplicates(mergedDuplicates)
                .expiredRemoved(Math.max(0, expiredRemoved))
                .totalProcessed(totalProcessed)
                .durationMs(elapsed)
                .promotedEntryIds(promotedEntryIds)
                .build();
    }

    @Override
    public boolean supportsLlmDrivenSummary() { return false; }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Collections.emptySet();
        Set<String> tokens = new HashSet<>();
        String[] words = text.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff\\s]", " ")
                .split("\\s+");
        for (String word : words) {
            if (word.length() >= 2) tokens.add(word);
        }
        return tokens;
    }

    private double jaccardSimilarity(Set<String> set1, Set<String> set2) {
        if (set1.isEmpty() && set2.isEmpty()) return 1.0;
        if (set1.isEmpty() || set2.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);

        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);

        return (double) intersection.size() / union.size();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private int find(int[] parent, int x) {
        if (parent[x] != x) parent[x] = find(parent, parent[x]);
        return parent[x];
    }

    private void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) parent[rb] = ra;
    }
}
