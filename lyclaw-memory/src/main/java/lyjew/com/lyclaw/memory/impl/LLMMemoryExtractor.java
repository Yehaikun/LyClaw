package lyjew.com.lyclaw.memory.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.*;
import lyjew.com.lyclaw.memory.extractor.MemoryExtractor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于规则的记忆提取器，使用正则表达式从对话中提取结构化记忆。
 *
 * <p>工作方式：使用预定义的正则模式匹配对话文本，识别以下类别的信息：</p>
 * <ul>
 *   <li><b>专有名词 (FACT)</b> — 英文大写单词组合，重要度 0.6</li>
 *   <li><b>数字/金额 (FACT)</b> — 数字、百分比、美元金额，重要度 0.4</li>
 *   <li><b>偏好 (PREFERENCE)</b> — 用户喜好/习惯表达，重要度 0.75</li>
 *   <li><b>任务 (TASK)</b> — 用户要执行的任务，重要度 0.7</li>
 *   <li><b>事实 (FACT)</b> — "X 是/属于/等于 Y" 模式的陈述，重要度 0.55</li>
 *   <li><b>目标 (GOAL)</b> — 用户的目标/愿景，重要度 0.65</li>
 *   <li><b>教训 (LESSON)</b> — 从过往经验中总结的教训，重要度 0.5</li>
 * </ul>
 *
 * <p>提取后自动与已有记忆去重，过滤停用短语。</p>
 *
 * <p>设计动机：标注 "LLM" 是因为该类模仿了 LLM 通过 prompt engineering
 * 进行信息提取的思路，但用规则引擎替代了 LLM 调用，以降低延迟和成本。
 * 实际上该类完全不调用 LLM，是纯规则驱动的。</p>
 */
@Slf4j
@Component
public class LLMMemoryExtractor implements MemoryExtractor {

    /** 专有名词模式：匹配英文大写单词组合 */
    private static final Pattern PROPER_NOUN_PATTERN =
            Pattern.compile("\\b([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)+)\\b");
    /** 数字和金额模式 */
    private static final Pattern NUMBER_PATTERN =
            Pattern.compile("\\b(\\d+(?:\\.\\d+)?%?)\\b|\\b(\\$\\d+(?:\\.\\d+)?)\\b");
    /** 偏好模式：匹配"我喜欢/偏好/习惯..." */
    private static final Pattern PREFERENCE_PATTERN =
            Pattern.compile("(?:我(?:喜欢|偏好|更倾向|习惯|一直|从来|从不|总是|经常|通常|绝对|坚决))([^。！？\\n]{3,80})");
    /** 任务模式：匹配"我要/需要/计划..." */
    private static final Pattern TASK_PATTERN =
            Pattern.compile("(?:我(?:要|需要|必须|得|决定|计划|打算|准备|将|会|想|希望))([^。！？\\n]{3,80})");
    /** 事实模式：匹配"X 是/属于/等于 Y" */
    private static final Pattern FACT_PATTERN =
            Pattern.compile("([^。！？\\n]{5,120}(?:是|属于|等于|位于|出生于|工作于|担任)[^。！？\\n]{5,80})");
    /** 目标模式：匹配"我的目标是..." */
    private static final Pattern GOAL_PATTERN =
            Pattern.compile("(?:我的(?:目标|目的是|意图是|方向是|规划是|愿景))([^。！？\\n]{3,80})");
    /** 教训模式：匹配"上次...导致了..." */
    private static final Pattern LESSON_PATTERN =
            Pattern.compile("(?:上次|上回|之前|曾经|以前|过去)([^。！？\\n]{3,80})(?:导致|造成|引起|出现|发生|产生)([^。！？\\n]{3,50})");

    /** 停用短语：匹配到这些内容的条目不作为有效记忆 */
    private static final Set<String> STOP_PHRASES = new HashSet<>(Arrays.asList(
            "是", "是的", "不是", "是否", "可能是", "应该是",
            "是吗", "对不对", "什么意思", "怎么办", "你是谁",
            "你好", "谢谢", "再见", "好的", "明白", "知道",
            "这个", "那个", "什么", "怎么", "为什么"));

    /**
     * 从对话文本中提取记忆，并与已有记忆去重。
     *
     * @param conversation     对话文本
     * @param existingMemories 已有的记忆列表
     * @return 新提取的、未重复的记忆条目列表
     */
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

    /** @return 是否支持实时提取，规则引擎始终为 true */
    @Override
    public boolean supportsRealtime() { return true; }

    /** @return 提取器名称标识符 */
    @Override
    public String getExtractorName() { return "LLMMemoryExtractor(heuristic)"; }

    /**
     * 使用指定的正则模式从文本中提取记忆条目。
     *
     * @param text          源文本
     * @param pattern       匹配模式
     * @param category      记忆类别
     * @param importance    基础重要性评分
     * @param extractMethod 提取方法标签
     * @return 提取到的记忆条目列表
     */
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

    /** 将重要性值限制在 [0, 1] 范围内。 */
    private double clampImportance(double raw) { return Math.max(0.0, Math.min(1.0, raw)); }

    /**
     * 对内容进行去重规范化：转小写、去首尾空格、压缩连续空白。
     */
    private String normalizeForDedup(String content) {
        if (content == null) return "";
        return content.toLowerCase().trim().replaceAll("\\s+", " ");
    }
}
