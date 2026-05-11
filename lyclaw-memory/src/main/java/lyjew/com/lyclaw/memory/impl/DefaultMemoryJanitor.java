package lyjew.com.lyclaw.memory.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.*;
import lyjew.com.lyclaw.memory.janitor.MemoryJanitor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 默认记忆清理器，负责定期清理长期记忆中的脏数据。
 *
 * <p>执行三种清理操作：</p>
 * <ol>
 *   <li><b>去重 (Deduplication)</b> — 使用 Jaccard 相似度 (阈值 0.85) 识别重复条目</li>
 *   <li><b>冲突解决 (Conflict Resolution)</b> — 检测矛盾陈述（如"喜欢"vs"不喜欢"）</li>
 *   <li><b>过期清理 (Expiration)</b> — 删除已过期的记忆条目</li>
 * </ol>
 *
 * <p>去重时选取更优的条目保留（优选标准：访问次数 > 重要性 > 创建时间）。
 * 冲突解决时保留更新的条目（以创建时间为准）。</p>
 *
 * <p>设计类比：类似操作系统中的垃圾回收 (GC)，定期扫描内存空间，
 * 释放无用数据，维护数据一致性。</p>
 */
@Slf4j
@Component
public class DefaultMemoryJanitor implements MemoryJanitor {

    /** Jaccard 相似度阈值，超过此值视为重复 */
    private static final double DEDUP_JACCARD_THRESHOLD = 0.85;

    /** 矛盾模式对，用于检测冲突陈述 */
    private static final String[][] CONFLICT_PATTERNS = {
            {"在", "不在"}, {"喜欢", "不喜欢"}, {"会", "不会"},
            {"可以", "不可以"}, {"是", "不是"},
    };

    private final MemorySystem memorySystem;

    public DefaultMemoryJanitor(MemorySystem memorySystem) {
        this.memorySystem = memorySystem;
    }

    /**
     * 执行完整的记忆清理流程。
     *
     * @param userId 用户标识，用于日志记录
     * @return 包含各项清理统计的报告
     */
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

    /**
     * 从两个重复条目中选择更优的一个保留。
     * 优先级：访问次数 > 重要性 > 创建时间。
     *
     * @return true 表示 b 更好，false 表示 a 更好
     */
    private boolean chooseBetterEntry(MemoryEntry a, MemoryEntry b) {
        if (b.getAccessCount() != a.getAccessCount()) return b.getAccessCount() > a.getAccessCount();
        if (Math.abs(b.getImportance() - a.getImportance()) > 0.01) return b.getImportance() > a.getImportance();
        long timeA = a.getTemporal() != null && a.getTemporal().getCreatedAt() != null
                ? a.getTemporal().getCreatedAt().toEpochMilli() : 0;
        long timeB = b.getTemporal() != null && b.getTemporal().getCreatedAt() != null
                ? b.getTemporal().getCreatedAt().toEpochMilli() : 0;
        return timeB > timeA;
    }

    /**
     * 检测两个条目是否存在逻辑冲突。
     * 通过匹配矛盾模式对（如 A 说"喜欢"而 B 说"不喜欢"）来判断。
     */
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

    /** 获取所有长期记忆条目。 */
    private List<MemoryEntry> getAllLongTermMemories() {
        MemoryQuery query = MemoryQuery.builder().topK(Integer.MAX_VALUE)
                .layerFilter(List.of(MemoryLayerType.LONG_TERM)).build();
        return memorySystem.retrieve(query).getEntries();
    }

    /** 获取所有层级的所有条目（用于过期检测）。 */
    private List<MemoryEntry> getAllEntries() {
        MemoryQuery query = MemoryQuery.builder().topK(Integer.MAX_VALUE).build();
        return memorySystem.retrieve(query).getEntries();
    }

    /**
     * 对文本进行分词，提取中英文单词，过滤掉单字符和标点。
     *
     * @return 分词的不可变集合
     */
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
