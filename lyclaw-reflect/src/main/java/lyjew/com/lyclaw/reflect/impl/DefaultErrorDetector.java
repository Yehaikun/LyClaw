package lyjew.com.lyclaw.reflect.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.reflect.DetectedError;
import lyjew.com.lyclaw.reflect.ErrorDetector;
import lyjew.com.lyclaw.reflect.ToolCallRecord;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 默认错误检测器，使用规则匹配检测 AI 输出中的三类错误。
 *
 * <p>检测能力：</p>
 * <ol>
 *   <li><b>幻觉检测 (Hallucination)</b> — 无证据支持的断言、高频置信标记、
 *       与已知事实的矛盾</li>
 *   <li><b>逻辑矛盾 (Logic Contradiction)</b> — 同一输出的不同句子出现
 *       互斥的陈述（如同时说"增加"和"减少"）</li>
 *   <li><b>工具失败模式 (Tool Failure Pattern)</b> — 连续失败、
 *       系统性故障、超时</li>
 * </ol>
 *
 * <p>检测方法基于预定义的模式库和关键词匹配，不依赖 LLM 调用。
 * 置信度评分由经验常量确定，用于供上层 StrategyAdjuster 做决策。</p>
 */
@Slf4j
@Component
public class DefaultErrorDetector implements ErrorDetector {

    /** 幻觉检测的基础置信度 */
    private static final double HALLUCINATION_BASE_CONFIDENCE = 0.65;
    /** 逻辑矛盾检测的置信度 */
    private static final double CONTRADICTION_BASE_CONFIDENCE = 0.75;
    /** 连续失败次数阈值，超过此值视为工具问题 */
    private static final int CONSECUTIVE_FAILURE_THRESHOLD = 3;

    /** 无证据支持的主张标记词 */
    private static final String[] UNSUPPORTED_CLAIM_MARKERS = {
            "research shows", "studies have shown", "study shows",
            "it is well known", "as we all know", "obviously",
            "undoubtedly", "without a doubt", "it is proven",
            "experts agree", "scientists have found",
            "according to research", "data shows",
            "it has been demonstrated", "clearly"
    };

    /** 高频自信标记词：绝对化表述可能是幻觉的信号 */
    private static final String[] HIGH_CONFIDENCE_MARKERS = {
            "definitely", "certainly", "absolutely", "always",
            "never", "without exception", "every single",
            "in all cases", "guaranteed", "invariably"
    };

    /** 矛盾词对：如果同一输出的不同句子分别包含一对中的两个词，则检测为逻辑矛盾 */
    private static final String[][] CONTRADICTION_PAIRS = {
            {"increase", "decrease"}, {"increasing", "decreasing"},
            {"rose", "fell"}, {"always", "never"},
            {"always", "sometimes"}, {"every", "none"},
            {"all of", "none of"}, {"must", "should not"},
            {"must", "optional"}, {"required", "optional"},
            {"mandatory", "voluntary"}, {"true", "false"},
            {"correct", "wrong"}, {"correct", "incorrect"},
            {"hot", "cold"}, {"fast", "slow"},
            {"high", "low"}, {"best", "worst"},
            {"first", "last"}, {"recommend", "avoid"},
            {"beneficial", "harmful"}, {"efficient", "inefficient"},
            {"large", "small"}, {"many", "few"},
            {"more", "less"}, {"better", "worse"},
            {"open", "closed"}, {"start", "end"},
            {"begin", "finish"}, {"safe", "dangerous"},
            {"easy", "difficult"}, {"simple", "complex"},
            {"positive", "negative"}, {"success", "failure"},
            {"good", "bad"},
    };

    /**
     * 检测输出文本中的幻觉错误。
     *
     * <p>检测逻辑：
     * <ul>
     *   <li>与已知事实对比，检查否定不一致</li>
     *   <li>无证据支持的主张（如 "research shows" 等）</li>
     *   <li>高频绝对化断言（如 "definitely", "never" 等）</li>
     * </ul>
     * </p>
     *
     * @param output     待检测的输出文本
     * @param knownFacts 已知事实列表，用于交叉验证
     * @return 检测到的错误列表
     */
    @Override
    public List<DetectedError> detectHallucination(String output, List<String> knownFacts) {
        if (output == null || output.isBlank()) return Collections.emptyList();

        List<DetectedError> errors = new ArrayList<>();
        String lowerOutput = output.toLowerCase();

        if (knownFacts != null && !knownFacts.isEmpty()) {
            for (String fact : knownFacts) {
                if (fact == null || fact.isBlank()) continue;
                String lowerFact = fact.toLowerCase();
                if (contradictsFact(lowerOutput, lowerFact)) {
                    errors.add(DetectedError.builder()
                            .type(DetectedError.ErrorType.HALLUCINATION)
                            .description("Output contradicts known fact: \"" + fact + "\"")
                            .location(extractSurroundingContext(output, lowerFact, 80))
                            .confidence(0.85)
                            .suggestion("Verify the output against the known fact and correct the contradiction")
                            .build());
                }
            }
        }

        for (String marker : UNSUPPORTED_CLAIM_MARKERS) {
            if (lowerOutput.contains(marker)) {
                int index = lowerOutput.indexOf(marker);
                String snippet = safeSubstring(output, index, Math.min(output.length(), index + 120));
                errors.add(DetectedError.builder()
                        .type(DetectedError.ErrorType.HALLUCINATION)
                        .description("Unsupported claim detected: \"" + marker + "...\" - no citation or evidence provided")
                        .location(trimToWord(snippet))
                        .confidence(HALLUCINATION_BASE_CONFIDENCE)
                        .suggestion("Add citations or mark the claim as uncertain to avoid hallucination")
                        .build());
                break;
            }
        }

        for (String marker : HIGH_CONFIDENCE_MARKERS) {
            if (lowerOutput.contains(marker)) {
                int index = lowerOutput.indexOf(marker);
                String snippet = safeSubstring(output, Math.max(0, index - 10), Math.min(output.length(), index + 100));
                errors.add(DetectedError.builder()
                        .type(DetectedError.ErrorType.HALLUCINATION)
                        .description("High-confidence absolute assertion using \"" + marker
                                   + "\" - may indicate hallucinated certainty")
                        .location(trimToWord(snippet))
                        .confidence(HALLUCINATION_BASE_CONFIDENCE - 0.1)
                        .suggestion("Use hedged language or provide evidence for absolute claims")
                        .build());
                break;
            }
        }

        return errors;
    }

    /**
     * 检测输出文本内部的逻辑矛盾。
     *
     * <p>按句子拆分输出文本，逐对比较，使用预定义的矛盾词对
     * （如 increase/decrease）检测不一致陈述。</p>
     *
     * @param output 待检测的输出文本
     * @return 检测到的逻辑矛盾错误列表
     */
    @Override
    public List<DetectedError> detectLogicContradiction(String output) {
        if (output == null || output.isBlank()) return Collections.emptyList();

        List<DetectedError> errors = new ArrayList<>();
        List<String> sentences = splitSentences(output);

        if (sentences.size() < 2) return errors;

        for (int i = 0; i < sentences.size(); i++) {
            for (int j = i + 1; j < sentences.size(); j++) {
                String s1 = sentences.get(i).toLowerCase();
                String s2 = sentences.get(j).toLowerCase();

                for (String[] pair : CONTRADICTION_PAIRS) {
                    boolean s1HasA = containsWord(s1, pair[0]);
                    boolean s1HasB = containsWord(s1, pair[1]);
                    boolean s2HasA = containsWord(s2, pair[0]);
                    boolean s2HasB = containsWord(s2, pair[1]);

                    if ((s1HasA && s2HasB && !(s1HasA && s1HasB) && !(s2HasA && s2HasB))
                     || (s1HasB && s2HasA && !(s1HasA && s1HasB) && !(s2HasA && s2HasB))) {
                        String desc = String.format(
                                "Logic contradiction: sentence %d says \"%s\" but sentence %d says \"%s\" "
                              + "(keywords '%s' vs '%s')",
                                i + 1, trimSentences(sentences.get(i)),
                                j + 1, trimSentences(sentences.get(j)),
                                pair[0], pair[1]);
                        errors.add(DetectedError.builder()
                                .type(DetectedError.ErrorType.LOGIC_CONTRADICTION)
                                .description(desc)
                                .location("Sentences " + (i + 1) + " and " + (j + 1))
                                .confidence(CONTRADICTION_BASE_CONFIDENCE)
                                .suggestion("Review and resolve the contradiction. Consider using CoT reasoning to verify logical consistency")
                                .build());
                        break;
                    }
                }
            }
        }

        return errors;
    }

    /**
     * 检测工具调用历史中的失败模式。
     *
     * <p>分析连续失败、系统性故障（所有调用均失败）和超时三种模式。</p>
     *
     * @param history 工具调用历史记录
     * @return 检测到的工具失败错误列表
     */
    @Override
    public List<DetectedError> detectToolFailurePattern(List<ToolCallRecord> history) {
        if (history == null || history.isEmpty()) return Collections.emptyList();

        List<DetectedError> errors = new ArrayList<>();
        errors.addAll(detectConsecutiveFailures(history));
        errors.addAll(detectSystemicFailure(history));
        errors.addAll(detectTimeouts(history));

        return errors;
    }

    /**
     * 判断输出是否与已知事实矛盾。
     * 通过对比否定词和共享关键词来检测。
     */
    private boolean contradictsFact(String lowerOutput, String lowerFact) {
        Set<String> factTerms = extractSignificantTerms(lowerFact);
        Set<String> outputTerms = new HashSet<>(Arrays.asList(lowerOutput.split("[^\\p{L}\\p{N}]+")));

        boolean factContainsNegation = containsNegation(lowerFact);
        boolean outputContainsNegation = containsNegation(lowerOutput);

        if (factContainsNegation != outputContainsNegation) {
            long shared = factTerms.stream().filter(outputTerms::contains).count();
            if (shared >= 2) return true;
        }

        return false;
    }

    /** 检查文本是否包含否定词。 */
    private boolean containsNegation(String text) {
        String[] negators = {" not ", "n't ", " never ", " no ", " neither ", " none ", " cannot ", " can't "};
        for (String n : negators) {
            if (text.contains(n)) return true;
        }
        return false;
    }

    /** 按句号、问号、感叹号、换行等分隔符拆分文本为句子列表。 */
    private List<String> splitSentences(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();
        return Arrays.stream(text.split("[.!?\\n]+"))
                .map(String::trim)
                .filter(s -> s.length() >= 5)
                .toList();
    }

    /** 使用词边界匹配检测句子中是否包含指定单词。 */
    private boolean containsWord(String sentence, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(sentence).find();
    }

    /** 检测同一工具的连续失败模式。超过阈值则报警。 */
    private List<DetectedError> detectConsecutiveFailures(List<ToolCallRecord> history) {
        List<DetectedError> errors = new ArrayList<>();
        Map<String, Integer> consecutiveCounts = new HashMap<>();
        String currentTool = null;

        for (ToolCallRecord record : history) {
            String toolName = record.getToolName();
            if (!record.isSuccess()) {
                if (toolName.equals(currentTool)) {
                    int count = consecutiveCounts.getOrDefault(toolName, 0) + 1;
                    consecutiveCounts.put(toolName, count);
                    if (count >= CONSECUTIVE_FAILURE_THRESHOLD && count == CONSECUTIVE_FAILURE_THRESHOLD) {
                        errors.add(DetectedError.builder()
                                .type(DetectedError.ErrorType.TOOL_FAILURE_PATTERN)
                                .description("Tool \"" + toolName + "\" failed " + count
                                           + " consecutive times - possible tool issue")
                                .location("Tool: " + toolName)
                                .confidence(0.85)
                                .suggestion("Switch to alternative tool or verify tool availability and parameters")
                                .build());
                    }
                } else {
                    currentTool = toolName;
                    consecutiveCounts.clear();
                    consecutiveCounts.put(toolName, 1);
                }
            } else {
                currentTool = null;
                consecutiveCounts.clear();
            }
        }

        return errors;
    }

    /** 检测系统性故障：所有调用均失败且调用次数 >= 3。 */
    private List<DetectedError> detectSystemicFailure(List<ToolCallRecord> history) {
        long successCount = history.stream().filter(ToolCallRecord::isSuccess).count();

        if (successCount == 0 && history.size() >= 3) {
            return List.of(DetectedError.builder()
                    .type(DetectedError.ErrorType.TOOL_FAILURE_PATTERN)
                    .description("All " + history.size() + " tool calls failed - possible systemic issue (network, auth, config)")
                    .location("All tools")
                    .confidence(0.90)
                    .suggestion("Check network connectivity, API credentials, and tool configuration")
                    .build());
        }

        return Collections.emptyList();
    }

    /** 检测超时调用：失败且耗时超过 30 秒。 */
    private List<DetectedError> detectTimeouts(List<ToolCallRecord> history) {
        List<DetectedError> errors = new ArrayList<>();
        for (ToolCallRecord record : history) {
            if (!record.isSuccess() && record.getDurationMs() > 30_000) {
                errors.add(DetectedError.builder()
                        .type(DetectedError.ErrorType.TOOL_FAILURE_PATTERN)
                        .description("Tool \"" + record.getToolName() + "\" timed out after " + record.getDurationMs() + "ms")
                        .location("Tool: " + record.getToolName())
                        .confidence(0.80)
                        .suggestion("Increase timeout or use a faster alternative tool")
                        .build());
            }
        }
        return errors;
    }

    /**
     * 从文本中提取有意义的词项（过滤停用词和短词）。
     */
    private Set<String> extractSignificantTerms(String text) {
        Set<String> stopWords = new HashSet<>(Arrays.asList(
                "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
                "have", "has", "had", "do", "does", "did", "will", "would", "shall",
                "should", "may", "might", "must", "can", "could", "i", "you", "he",
                "she", "it", "we", "they", "me", "him", "her", "us", "them", "my",
                "your", "his", "its", "our", "their", "this", "that", "these", "those",
                "and", "but", "or", "nor", "for", "so", "yet", "of", "at", "by",
                "with", "about", "to", "in", "into", "on", "from", "not", "no"));

        return Arrays.stream(text.split("[^\\p{L}\\p{N}]+"))
                .filter(s -> s.length() > 2)
                .filter(s -> !stopWords.contains(s))
                .collect(HashSet::new, Set::add, Set::addAll);
    }

    /** 安全的子串提取，自动处理越界。 */
    private String safeSubstring(String text, int start, int end) {
        if (text == null) return "";
        int safeStart = Math.max(0, Math.min(start, text.length()));
        int safeEnd = Math.max(safeStart, Math.min(end, text.length()));
        return text.substring(safeStart, safeEnd);
    }

    /** 提取关键词周围的上下文文本（指定窗口大小）。 */
    private String extractSurroundingContext(String output, String keyword, int window) {
        if (output == null || keyword == null) return "";
        int idx = output.toLowerCase().indexOf(keyword.toLowerCase());
        if (idx < 0) return "";
        int start = Math.max(0, idx - window / 2);
        int end = Math.min(output.length(), idx + keyword.length() + window / 2);
        return output.substring(start, end) + "...";
    }

    /**
     * 修剪文本到单词边界，去除前后非字母数字字符，限制最大长度。
     */
    private String trimToWord(String text) {
        if (text == null || text.length() < 5) return text;
        int start = 0;
        while (start < text.length() && !Character.isLetterOrDigit(text.charAt(start))) start++;
        int end = text.length();
        while (end > start && !Character.isLetterOrDigit(text.charAt(end - 1))) end--;
        String trimmed = text.substring(start, end);
        return trimmed.length() > 100 ? trimmed.substring(0, 100) + "..." : trimmed;
    }

    /** 截断句子到最大 60 字符。 */
    private String trimSentences(String sentence) {
        if (sentence == null) return "";
        return sentence.length() > 60 ? sentence.substring(0, 60) + "..." : sentence;
    }
}
