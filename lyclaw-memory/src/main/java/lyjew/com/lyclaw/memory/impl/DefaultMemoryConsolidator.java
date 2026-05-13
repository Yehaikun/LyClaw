package lyjew.com.lyclaw.memory.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.*;
import lyjew.com.lyclaw.memory.consolidate.MemoryConsolidator;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 默认记忆合并器，将短期记忆去重合并后提升为长期记忆。
 *
 * <p>核心流程：使用并查集 (Union-Find) 算法识别语义相似的短期记忆条目，
 * 将相似条目合并为一个综合条目，然后根据重要性阈值决定是否提升到长期记忆层。</p>
 *
 * <p>合并策略：
 * <ol>
 *   <li>对每个条目的内容进行分词（中英文混合）</li>
 *   <li>计算条目间的 Jaccard 相似度</li>
 *   <li>使用并查集将相似度 >= 阈值 (0.6) 的条目分到同一组</li>
 *   <li>每组选取最重要的条目作为代表，合并其他条目的内容</li>
 *   <li>重要性 >= 阈值的条目提升为长期记忆</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
public class DefaultMemoryConsolidator implements MemoryConsolidator {

    /** 关键词重叠阈值，用于判断两个条目是否属于同一语义组 */
    private static final double KEYWORD_OVERLAP_THRESHOLD = 0.6;

    private final MemorySystem memorySystem;

    public DefaultMemoryConsolidator(MemorySystem memorySystem) {
        this.memorySystem = memorySystem;
    }

    /**
     * 使用默认合并策略对指定用户和会话的短期记忆执行合并。
     *
     * <p>这是一个便捷重载方法，其内部委托给
     * {@link #consolidate(String, String, MemoryConsolidationPolicy)}
     * 三参数版本，并自动构建一个使用全部默认值的 {@link MemoryConsolidationPolicy} 对象。
     * 默认策略的配置参数如下：</p>
     * <ul>
     *   <li><b>重要性阈值 (importanceThreshold)</b> — 使用 {@code 0.0}，
     *       即不进行重要性过滤，所有合并后的条目都有机会被提升为长期记忆</li>
     *   <li><b>最大批次大小 (maxBatchSize)</b> — 使用 {@link Integer#MAX_VALUE}，
     *       即不限制单次合并处理的条目数量</li>
     *   <li><b>LLM 摘要开关 (enableLlmSummary)</b> — 默认为 {@code false}，
     *       表示不使用大语言模型来生成摘要，仅使用规则化方法</li>
     *   <li><b>合并策略模式 (mode)</b> — 使用标准的基于并查集的语义相似度合并算法</li>
     * </ul>
     *
     * <p>该重载方法的典型使用场景是定时任务触发或管理后台手动触发，
     * 不需要精细控制合并行为时可直接调用此简化版本。</p>
     *
     * <p>内部调用链路：</p>
     * <pre>
     * consolidate(userId, sessionId)
     *   └── memorySystem.getShortTermMemories(sessionId)  // 获取会话的短期记忆
     *   └── tokenize + jaccardSimilarity                  // 分词并计算相似度
     *   └── union-find 分组                               // 将相似条目分到同组
     *   └── 合并每组内容 + 重要性排序                      // 选取最佳代表条目
     *   └── commitLongTerm(entry)                         // 提升符合条件的条目
     *   └── evictExpiredPerceptions()                     // 清理过期感知数据
     * </pre>
     *
     * @param userId    用户标识，用于过滤特定用户的短期记忆条目。
     *                  可以为 {@code null}，此时不过滤用户，处理所有短期记忆。
     * @param sessionId 会话标识，用于从记忆系统中检索对应会话的短期记忆条目。
     *                  不能为 {@code null}，否则无法定位任何短期记忆。
     * @return {@link ConsolidationReport} 合并报告对象，包含以下统计信息：
     *         <ul>
     *           <li>{@code promotedToLongTerm} — 成功提升为长期记忆的条目数量</li>
     *           <li>{@code mergedDuplicates} — 被合并消除的重复条目数量</li>
     *           <li>{@code expiredRemoved} — 因过期而被清理的感知数据数量</li>
     *           <li>{@code totalProcessed} — 实际处理的条目总数</li>
     *           <li>{@code durationMs} — 合并操作耗时（毫秒）</li>
     *           <li>{@code promotedEntryIds} — 被提升条目的 ID 列表</li>
     *         </ul>
     */
    @Override
    public ConsolidationReport consolidate(String userId, String sessionId) {
        return consolidate(userId, sessionId, MemoryConsolidationPolicy.builder().build());
    }

    /**
     * 使用指定策略执行记忆合并。
     *
     * @param userId    用户标识
     * @param sessionId 会话标识
     * @param policy    合并策略
     * @return 包含合并统计信息的报告
     */
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

    /** @return 是否支持 LLM 驱动的摘要生成，当前为 false */
    @Override
    public boolean supportsLlmDrivenSummary() { return false; }

    /** 对文本进行分词，过滤短词和标点。 */
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

    /** 计算两个集合的 Jaccard 相似度 = |交集| / |并集|。 */
    private double jaccardSimilarity(Set<String> set1, Set<String> set2) {
        if (set1.isEmpty() && set2.isEmpty()) return 1.0;
        if (set1.isEmpty() || set2.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);

        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);

        return (double) intersection.size() / union.size();
    }

    /** 截断文本到指定长度，超出部分用 "..." 替代。 */
    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    /** 并查集的查找操作，带路径压缩。 */
    private int find(int[] parent, int x) {
        if (parent[x] != x) parent[x] = find(parent, parent[x]);
        return parent[x];
    }

    /** 并查集的合并操作。 */
    private void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) parent[rb] = ra;
    }
}
