package lyjew.com.lyclaw.memory.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.*;
import lyjew.com.lyclaw.memory.janitor.MemoryJanitor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DefaultMemoryJanitor implements MemoryJanitor {

    private static final double DEDUP_JACCARD_THRESHOLD = 0.85;

    private static final String[][] CONFLICT_PATTERNS = {
            {"在", "不在"}, {"喜欢", "不喜欢"}, {"会", "不会"},
            {"可以", "不可以"}, {"是", "不是"},
    };

    private final MemorySystem memorySystem;

    public DefaultMemoryJanitor(MemorySystem memorySystem) {
        this.memorySystem = memorySystem;
    }

    @Override
    public JanitorReport clean(String userId) {
        long start = System.currentTimeMillis();

        int duplicatesRemoved = 0;
        int expiredEntriesRemoved = 0;
        int conflictsResolved = 0;
        long spaceFreed = 0;

        List<MemoryEntry> longTermEntries = getAllLongTermMemories();
        if (longTermEntries.isEmpty()) {
            return JanitorReport.builder().duplicatesRemoved(0).expiredEntriesRemoved(0)
                    .conflictsResolved(0).totalCleaned(0)
                    .durationMs(System.currentTimeMillis() - start).spaceFreedBytes(0).build();
        }

        List<String> toRemove = new ArrayList<>();
        List<Set<String>> tokenized = longTermEntries.stream()
                .map(e -> tokenize(e.getContent())).collect(Collectors.toList());

        int n = longTermEntries.size();
        boolean[] removed = new boolean[n];

        for (int i = 0; i < n; i++) {
            if (removed[i]) continue;
            for (int j = i + 1; j < n; j++) {
                if (removed[j]) continue;

                double similarity = jaccardSimilarity(tokenized.get(i), tokenized.get(j));
                if (similarity >= DEDUP_JACCARD_THRESHOLD) {
                    MemoryEntry a = longTermEntries.get(i);
                    MemoryEntry b = longTermEntries.get(j);
                    int betterIdx = chooseBetterEntry(a, b) ? j : i;
                    int worseIdx = (betterIdx == j) ? i : j;

                    removed[worseIdx] = true;
                    toRemove.add(longTermEntries.get(worseIdx).getEntryId());
                    duplicatesRemoved++;
                    spaceFreed += longTermEntries.get(worseIdx).getContent() != null
                            ? longTermEntries.get(worseIdx).getContent().length() : 0;
                }
            }
        }

        for (int i = 0; i < n; i++) {
            if (removed[i]) continue;
            for (int j = i + 1; j < n; j++) {
                if (removed[j]) continue;
                if (detectConflict(longTermEntries.get(i), longTermEntries.get(j))) {
                    MemoryEntry a = longTermEntries.get(i);
                    MemoryEntry b = longTermEntries.get(j);
                    long timeA = a.getTemporal() != null && a.getTemporal().getCreatedAt() != null
                            ? a.getTemporal().getCreatedAt().toEpochMilli() : 0;
                    long timeB = b.getTemporal() != null && b.getTemporal().getCreatedAt() != null
                            ? b.getTemporal().getCreatedAt().toEpochMilli() : 0;

                    int worseIdx = (timeA >= timeB) ? j : i;
                    removed[worseIdx] = true;
                    toRemove.add(longTermEntries.get(worseIdx).getEntryId());
                    conflictsResolved++;
                    spaceFreed += longTermEntries.get(worseIdx).getContent() != null
                            ? longTermEntries.get(worseIdx).getContent().length() : 0;
                }
            }
        }

        List<MemoryEntry> allEntries = getAllEntries();
        for (MemoryEntry entry : allEntries) {
            if (entry.getTemporal() != null && entry.getTemporal().isExpired()) {
                expiredEntriesRemoved++;
                spaceFreed += entry.getContent() != null ? entry.getContent().length() : 0;
            }
        }

        // Actually remove detected duplicates and conflicts
        if (!toRemove.isEmpty() && memorySystem instanceof TieredMemorySystem tiered) {
            for (String entryId : toRemove) {
                tiered.removeLongTermEntry(entryId);
            }
            log.info("Janitor removed {} long-term entries: {}", toRemove.size(), toRemove);
        }

        memorySystem.evictExpiredPerceptions();

        int totalCleaned = duplicatesRemoved + expiredEntriesRemoved + conflictsResolved;
        long elapsed = System.currentTimeMillis() - start;
        log.info("Janitor cleaned for user {}: dupes={}, expired={}, conflicts={}, total={}, space={}, durationMs={}",
                userId, duplicatesRemoved, expiredEntriesRemoved, conflictsResolved, totalCleaned, spaceFreed, elapsed);

        return JanitorReport.builder()
                .duplicatesRemoved(duplicatesRemoved)
                .expiredEntriesRemoved(expiredEntriesRemoved)
                .conflictsResolved(conflictsResolved)
                .totalCleaned(totalCleaned)
                .durationMs(elapsed)
                .spaceFreedBytes(spaceFreed)
                .build();
    }

    private boolean chooseBetterEntry(MemoryEntry a, MemoryEntry b) {
        if (b.getAccessCount() != a.getAccessCount()) return b.getAccessCount() > a.getAccessCount();
        if (Math.abs(b.getImportance() - a.getImportance()) > 0.01) return b.getImportance() > a.getImportance();
        long timeA = a.getTemporal() != null && a.getTemporal().getCreatedAt() != null
                ? a.getTemporal().getCreatedAt().toEpochMilli() : 0;
        long timeB = b.getTemporal() != null && b.getTemporal().getCreatedAt() != null
                ? b.getTemporal().getCreatedAt().toEpochMilli() : 0;
        return timeB > timeA;
    }

    private boolean detectConflict(MemoryEntry a, MemoryEntry b) {
        String contentA = a.getContent() != null ? a.getContent().toLowerCase() : "";
        String contentB = b.getContent() != null ? b.getContent().toLowerCase() : "";
        if (contentA.isEmpty() || contentB.isEmpty()) return false;

        Set<String> tokensA = tokenize(contentA);
        Set<String> tokensB = tokenize(contentB);
        Set<String> common = new HashSet<>(tokensA);
        common.retainAll(tokensB);
        if (common.isEmpty()) return false;

        for (String[] pattern : CONFLICT_PATTERNS) {
            boolean aHasPos = contentA.contains(pattern[0]);
            boolean aHasNeg = contentA.contains(pattern[1]);
            boolean bHasPos = contentB.contains(pattern[0]);
            boolean bHasNeg = contentB.contains(pattern[1]);
            if ((aHasPos && bHasNeg) || (aHasNeg && bHasPos)) return true;
        }
        return false;
    }

    private List<MemoryEntry> getAllLongTermMemories() {
        MemoryQuery query = MemoryQuery.builder().topK(Integer.MAX_VALUE)
                .layerFilter(List.of(MemoryLayerType.LONG_TERM)).build();
        return memorySystem.retrieve(query).getEntries();
    }

    private List<MemoryEntry> getAllEntries() {
        MemoryQuery query = MemoryQuery.builder().topK(Integer.MAX_VALUE).build();
        return memorySystem.retrieve(query).getEntries();
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Collections.emptySet();
        Set<String> tokens = new HashSet<>();
        String[] words = text.toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fff\\s]", " ").split("\\s+");
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
}
