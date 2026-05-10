package lyjew.com.lyclaw.memory.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.*;
import lyjew.com.lyclaw.memory.extractor.MemoryExtractor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class LLMMemoryExtractor implements MemoryExtractor {

    private static final Pattern PROPER_NOUN_PATTERN =
            Pattern.compile("\\b([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)+)\\b");
    private static final Pattern NUMBER_PATTERN =
            Pattern.compile("\\b(\\d+(?:\\.\\d+)?%?)\\b|\\b(\\$\\d+(?:\\.\\d+)?)\\b");
    private static final Pattern PREFERENCE_PATTERN =
            Pattern.compile("(?:我(?:喜欢|偏好|更倾向|习惯|一直|从来|从不|总是|经常|通常|绝对|坚决))([^。！？\\n]{3,80})");
    private static final Pattern TASK_PATTERN =
            Pattern.compile("(?:我(?:要|需要|必须|得|决定|计划|打算|准备|将|会|想|希望))([^。！？\\n]{3,80})");
    private static final Pattern FACT_PATTERN =
            Pattern.compile("([^。！？\\n]{5,120}(?:是|属于|等于|位于|出生于|工作于|担任)[^。！？\\n]{5,80})");
    private static final Pattern GOAL_PATTERN =
            Pattern.compile("(?:我的(?:目标|目的是|意图是|方向是|规划是|愿景))([^。！？\\n]{3,80})");
    private static final Pattern LESSON_PATTERN =
            Pattern.compile("(?:上次|上回|之前|曾经|以前|过去)([^。！？\\n]{3,80})(?:导致|造成|引起|出现|发生|产生)([^。！？\\n]{3,50})");

    private static final Set<String> STOP_PHRASES = new HashSet<>(Arrays.asList(
            "是", "是的", "不是", "是否", "可能是", "应该是",
            "是吗", "对不对", "什么意思", "怎么办", "你是谁",
            "你好", "谢谢", "再见", "好的", "明白", "知道",
            "这个", "那个", "什么", "怎么", "为什么"));

    @Override
    public List<MemoryEntry> extract(String conversation, List<MemoryEntry> existingMemories) {
        if (conversation == null || conversation.isBlank()) {
            log.debug("Empty conversation, skipping extraction");
            return Collections.emptyList();
        }

        long startTime = System.currentTimeMillis();
        List<MemoryEntry> extracted = new ArrayList<>();

        extracted.addAll(extractPattern(conversation, PROPER_NOUN_PATTERN, MemoryCategory.FACT, 0.6, "keyword-proper-noun"));
        extracted.addAll(extractPattern(conversation, NUMBER_PATTERN, MemoryCategory.FACT, 0.4, "keyword-number"));
        extracted.addAll(extractPattern(conversation, PREFERENCE_PATTERN, MemoryCategory.PREFERENCE, 0.75, "keyword-preference"));
        extracted.addAll(extractPattern(conversation, TASK_PATTERN, MemoryCategory.TASK, 0.7, "keyword-task"));
        extracted.addAll(extractPattern(conversation, FACT_PATTERN, MemoryCategory.FACT, 0.55, "keyword-fact"));
        extracted.addAll(extractPattern(conversation, GOAL_PATTERN, MemoryCategory.GOAL, 0.65, "keyword-goal"));
        extracted.addAll(extractPattern(conversation, LESSON_PATTERN, MemoryCategory.LESSON, 0.5, "keyword-lesson"));

        Set<String> existingContents = new HashSet<>();
        if (existingMemories != null) {
            for (MemoryEntry em : existingMemories) {
                if (em.getContent() != null) existingContents.add(normalizeForDedup(em.getContent()));
            }
        }

        List<MemoryEntry> deduped = new ArrayList<>();
        for (MemoryEntry entry : extracted) {
            String normalized = normalizeForDedup(entry.getContent());
            if (!existingContents.contains(normalized)) {
                deduped.add(entry);
                existingContents.add(normalized);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.debug("Extracted {} memories ({} after dedup) from {} chars in {}ms",
                extracted.size(), deduped.size(), conversation.length(), elapsed);

        return deduped;
    }

    @Override
    public boolean supportsRealtime() { return true; }

    @Override
    public String getExtractorName() { return "LLMMemoryExtractor(heuristic)"; }

    private List<MemoryEntry> extractPattern(String text, Pattern pattern,
                                              MemoryCategory category, double importance, String extractMethod) {
        List<MemoryEntry> entries = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            String match = matcher.group().trim();
            if (match.isEmpty() || match.length() < 3 || STOP_PHRASES.contains(match)) continue;

            MemoryEntry entry = MemoryEntry.builder()
                    .entryId(UUID.randomUUID().toString())
                    .layer(MemoryLayerType.SHORT_TERM)
                    .content(match)
                    .category(category)
                    .importance(clampImportance(importance))
                    .accessCount(0)
                    .tags(List.of("auto-extracted", extractMethod))
                    .temporal(TemporalProps.builder()
                            .createdAt(Instant.now()).lastAccessedAt(Instant.now())
                            .decayFactor(0.05).strength(1.0).build())
                    .build();
            entries.add(entry);
        }
        return entries;
    }

    private double clampImportance(double raw) { return Math.max(0.0, Math.min(1.0, raw)); }

    private String normalizeForDedup(String content) {
        if (content == null) return "";
        return content.toLowerCase().trim().replaceAll("\\s+", " ");
    }
}
