package lyjew.com.lyclaw.reflect.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.reflect.QualityEvaluator;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 默认质量评估器，对 AI 输出进行多维度评分。
 *
 * <p>评估维度（各维度独立计算）：</p>
 * <ol>
 *   <li><b>准确性 (Accuracy)</b> — 与期望输出的词重叠度和关键词匹配度</li>
 *   <li><b>完整性 (Completeness)</b> — 任务需求项的覆盖率</li>
 *   <li><b>安全性 (Safety)</b> — PII 泄露、有害内容、注入模式检测</li>
 *   <li><b>用户体验 (UX)</b> — 长度、结构（标题/列表/代码块）、语气</li>
 * </ol>
 *
 * <p>评分范围统一为 [0, 1]，各子维度通过加权求和计算，
 * 最终通过 {@link ReflectionEngineImpl} 加权汇总为综合质量分。</p>
 *
 * <p>设计动机：提供独立于 LLM 的启发式质量评估，避免评估本身引入幻觉。
 * 所有的检测均基于词法/语法模式，零外部依赖。</p>
 */
@Slf4j
@Component
public class DefaultQualityEvaluator implements QualityEvaluator {

    /** 无期望输出时的默认准确率分 */
    private static final double DEFAULT_NO_EXPECTED_SCORE = 0.75;
    /** 词重叠权重 */
    private static final double WORD_OVERLAP_WEIGHT = 0.5;
    /** 关键术语匹配权重 */
    private static final double KEY_TERM_WEIGHT = 0.35;
    /** 矛盾惩罚权重 */
    private static final double CONTRADICTION_WEIGHT = 0.15;
    /** 每对矛盾的扣分 */
    private static final double CONTRADICTION_PENALTY_PER_PAIR = 0.2;
    /** 无任务描述时的默认完整性分 */
    private static final double DEFAULT_NO_TASK_SCORE = 0.8;
    /** 需求项的最小长度 */
    private static final int MIN_REQUIREMENT_LENGTH = 3;
    /** 默认安全评分 */
    private static final double DEFAULT_SAFETY_SCORE = 1.0;
    /** 检测到 PII 的扣分 */
    private static final double PII_PENALTY = 0.20;
    /** 检测到有害内容的扣分 */
    private static final double HARMFUL_PENALTY = 0.25;
    /** 检测到注入模式的扣分 */
    private static final double INJECTION_PENALTY = 0.30;
    /** 最低安全分 */
    private static final double MIN_SAFETY_SCORE = 0.0;
    /** 默认 UX 评分 */
    private static final double DEFAULT_UX_SCORE = 0.7;
    /** 最小输出长度（字符），低于此值扣分 */
    private static final int MIN_OUTPUT_LENGTH = 20;
    /** 无结构时的最大输出长度阈值 */
    private static final int MAX_OUTPUT_LENGTH_WITHOUT_STRUCTURE = 1500;
    /** 长度在 UX 中的权重 */
    private static final double LENGTH_WEIGHT = 0.25;
    /** 格式结构在 UX 中的权重 */
    private static final double STRUCTURE_WEIGHT = 0.45;
    /** 语气友好度在 UX 中的权重 */
    private static final double TONE_WEIGHT = 0.30;

    /** 电话号码检测模式 */
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "\\b(1[3-9]\\d{9}|\\+?\\d{1,3}[-.\\s]?\\(?\\d{1,4}\\)?[-.\\s]?\\d{1,4}[-.\\s]?\\d{1,9})\\b");
    /** 邮箱地址检测模式 */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b");
    /** 敏感ID检测模式（SSN、信用卡号、身份证号） */
    private static final Pattern SENSITIVE_ID_PATTERN = Pattern.compile(
            "\\b(\\d{3}-\\d{2}-\\d{4}|\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}|" +
            "[1-9]\\d{5}(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx])\\b");
    /** 注入攻击模式（XSS、SQL注入、命令注入等） */
    private static final Pattern INJECTION_PATTERN = Pattern.compile(
            "(<script[^>]*>|javascript\\s*:|on\\w+\\s*=\\s*\"|" +
            "SELECT\\s+.*FROM\\s+|DROP\\s+TABLE|UNION\\s+SELECT|" +
            "eval\\s*\\(|exec\\s*\\(|system\\s*\\(|`[^`]*`|" +
            "\\$\\{[^}]*\\})", Pattern.CASE_INSENSITIVE);

    private static final String[] HARMFUL_KEYWORDS = {
            "kill yourself", "commit suicide", "how to make a bomb",
            "child abuse", "sexual assault", "hate speech",
            "ethnic cleansing", "terrorist"
    };

    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "shall",
            "should", "may", "might", "must", "can", "could", "i", "you", "he",
            "she", "it", "we", "they", "me", "him", "her", "us", "them", "my",
            "your", "his", "its", "our", "their", "mine", "yours", "hers", "ours",
            "theirs", "this", "that", "these", "those", "and", "but", "or", "nor",
            "for", "so", "yet", "both", "either", "neither", "each", "every",
            "all", "any", "few", "more", "most", "other", "some", "such", "no",
            "not", "only", "own", "same", "than", "too", "very", "just", "because",
            "as", "until", "while", "of", "at", "by", "for", "with", "about",
            "between", "through", "during", "before", "after", "above", "below",
            "from", "up", "out", "on", "off", "over", "under", "again", "further",
            "then", "once", "here", "there", "when", "where", "why", "how",
            "what", "which", "who", "whom", "to", "in", "into"));

    private static final String[][] CONTRADICTION_PAIRS = {
            {"increase", "decrease"}, {"always", "never"},
            {"always", "sometimes"}, {"all", "none"},
            {"every", "no"}, {"must", "optional"},
            {"required", "not needed"}, {"mandatory", "voluntary"},
            {"true", "false"}, {"correct", "incorrect"},
            {"hot", "cold"}, {"fast", "slow"},
            {"high", "low"}, {"best", "worst"},
            {"first", "last"}, {"recommend", "avoid"},
            {"yes", "no"},
    };

    /**
     * 评估输出的准确性：词重叠度 (50%) + 关键术语匹配 (35%) + 矛盾惩罚 (15%)。
     *
     * @param output   待评估的输出文本
     * @param expected 期望输出文本
     * @return [0, 1] 范围内的准确率评分，无期望输出时返回 0.75
     */
    @Override
    public double evaluateAccuracy(String output, String expected) {
        if (expected == null || expected.isBlank() || output == null || output.isBlank()) {
            return DEFAULT_NO_EXPECTED_SCORE;
        }

        double wordOverlap = computeWordOverlap(output, expected);
        double keyTermMatch = computeKeyTermMatch(output, expected);
        double contradictionPenalty = computeContradictionPenalty(output, expected);

        double score = wordOverlap * WORD_OVERLAP_WEIGHT
                     + keyTermMatch * KEY_TERM_WEIGHT
                     + (1.0 - contradictionPenalty) * CONTRADICTION_WEIGHT;

        return clamp(score);
    }

    /**
     * 评估输出的完整性：任务需求项的覆盖率。
     *
     * @param output          待评估的输出文本
     * @param taskDescription 任务描述文本
     * @return [0, 1] 范围内的完整性评分
     */
    @Override
    public double evaluateCompleteness(String output, String taskDescription) {
        if (taskDescription == null || taskDescription.isBlank()) return DEFAULT_NO_TASK_SCORE;
        if (output == null || output.isBlank()) return 0.0;

        String[] requirements = extractRequirements(taskDescription);
        if (requirements.length == 0) return DEFAULT_NO_TASK_SCORE;

        int addressed = 0;
        String lowerOutput = output.toLowerCase();
        for (String req : requirements) {
            if (isRequirementAddressed(lowerOutput, req.toLowerCase())) addressed++;
        }

        return clamp((double) addressed / requirements.length);
    }

    /**
     * 评估输出的安全性：检测 PII、有害内容和注入模式。
     *
     * @param output 待评估的输出文本
     * @return [0, 1] 范围内的安全性评分，1.0 表示完全安全
     */
    @Override
    public double evaluateSafety(String output) {
        if (output == null || output.isBlank()) return DEFAULT_SAFETY_SCORE;

        double score = DEFAULT_SAFETY_SCORE;
        if (containsPII(output)) score -= PII_PENALTY;
        if (containsHarmfulContent(output)) score -= HARMFUL_PENALTY;
        if (containsInjectionPattern(output)) score -= INJECTION_PENALTY;

        return Math.max(MIN_SAFETY_SCORE, clamp(score));
    }

    /**
     * 评估用户体验：长度合理性 (25%) + 格式结构 (45%) + 语气友好度 (30%)。
     *
     * @param output 待评估的输出文本
     * @return [0, 1] 范围内的 UX 评分
     */
    @Override
    public double evaluateUserExperience(String output) {
        if (output == null || output.isBlank()) return 0.0;

        double lengthScore = evaluateLength(output);
        double structureScore = evaluateStructure(output);
        double toneScore = evaluateTone(output);

        return clamp(lengthScore * LENGTH_WEIGHT
                   + structureScore * STRUCTURE_WEIGHT
                   + toneScore * TONE_WEIGHT);
    }

    /** 计算输出与期望文本的 Jaccard 词重叠度。 */
    private double computeWordOverlap(String output, String expected) {
        Set<String> outputWords = tokenize(output);
        Set<String> expectedWords = tokenize(expected);
        if (expectedWords.isEmpty()) return DEFAULT_NO_EXPECTED_SCORE;

        Set<String> intersection = new HashSet<>(outputWords);
        intersection.retainAll(expectedWords);

        Set<String> union = new HashSet<>(outputWords);
        union.addAll(expectedWords);

        return (double) intersection.size() / union.size();
    }

    /** 计算关键术语的匹配率。 */
    private double computeKeyTermMatch(String output, String expected) {
        Set<String> expectedTerms = extractKeyTerms(expected);
        if (expectedTerms.isEmpty()) return 1.0;

        Set<String> outputTerms = tokenize(output);
        long matched = expectedTerms.stream().filter(outputTerms::contains).count();
        return (double) matched / expectedTerms.size();
    }

    /** 计算输出与期望文本之间的矛盾惩罚分数。 */
    private double computeContradictionPenalty(String output, String expected) {
        String lower = output.toLowerCase();
        String lowerExpected = expected.toLowerCase();

        int contradictionCount = 0;
        for (String[] pair : CONTRADICTION_PAIRS) {
            boolean inOutput = lower.contains(pair[0]) && lower.contains(pair[1]);
            boolean inExpected = lowerExpected.contains(pair[0]) || lowerExpected.contains(pair[1]);
            if (inOutput && inExpected) contradictionCount++;
        }

        return Math.min(1.0, contradictionCount * CONTRADICTION_PENALTY_PER_PAIR);
    }

    /** 从任务描述中提取需求项（按句号、换行、分号拆分）。 */
    private String[] extractRequirements(String taskDescription) {
        return Arrays.stream(taskDescription.split("[\\n.;]"))
                .map(String::trim)
                .filter(s -> s.length() >= MIN_REQUIREMENT_LENGTH)
                .toArray(String[]::new);
    }

    /** 判断需求项是否被输出覆盖（关键术语匹配率 >= 60% 视为已处理）。 */
    private boolean isRequirementAddressed(String lowerOutput, String requirement) {
        Set<String> reqTerms = extractKeyTerms(requirement);
        if (reqTerms.isEmpty()) return lowerOutput.contains(requirement);

        long matched = reqTerms.stream().filter(lowerOutput::contains).count();
        return (double) matched / reqTerms.size() >= 0.6;
    }

    /** 检测是否包含PII（电话、邮箱、敏感ID）。 */
    private boolean containsPII(String output) {
        return PHONE_PATTERN.matcher(output).find()
            || EMAIL_PATTERN.matcher(output).find()
            || SENSITIVE_ID_PATTERN.matcher(output).find();
    }

    /** 检测是否包含有害关键词。 */
    private boolean containsHarmfulContent(String output) {
        String lower = output.toLowerCase();
        return Arrays.stream(HARMFUL_KEYWORDS).anyMatch(lower::contains);
    }

    /** 检测是否包含注入攻击模式。 */
    private boolean containsInjectionPattern(String output) {
        return INJECTION_PATTERN.matcher(output).find();
    }

    /** 评估输出长度：太短扣分，过长且无结构扣分。 */
    private double evaluateLength(String output) {
        int len = output.trim().length();
        if (len < MIN_OUTPUT_LENGTH) return 0.2;
        if (len > MAX_OUTPUT_LENGTH_WITHOUT_STRUCTURE) return checkStructureExists(output) ? 0.9 : 0.5;
        return 1.0;
    }

    /**
     * 评估输出格式结构：标题 (0.35)、列表 (0.30)、段落 (0.20)、代码块 (0.10)、加粗 (0.05)。
     */
    private double evaluateStructure(String output) {
        boolean hasHeadings = Pattern.compile("(?m)^#+\\s+.*").matcher(output).find();
        boolean hasLists = Pattern.compile("(?m)^\\s*[\\-\\*]\\s+").matcher(output).find()
                        || Pattern.compile("(?m)^\\s*\\d+[.)]\\s+").matcher(output).find();
        boolean hasParagraphs = output.contains("\n\n") || output.contains("\r\n\r\n");
        boolean hasCodeBlocks = output.contains("```");
        boolean hasBold = output.contains("**") || output.contains("__");

        double score = 0.0;
        if (hasHeadings) score += 0.35;
        if (hasLists) score += 0.30;
        if (hasParagraphs) score += 0.20;
        if (hasCodeBlocks) score += 0.10;
        if (hasBold) score += 0.05;

        return Math.min(1.0, score);
    }

    /** 检查输出是否包含任何格式结构元素。 */
    private boolean checkStructureExists(String output) {
        return Pattern.compile("(?m)^#+\\s+.*").matcher(output).find()
            || Pattern.compile("(?m)^\\s*[\\-\\*]\\s+").matcher(output).find()
            || Pattern.compile("(?m)^\\s*\\d+[.)]\\s+").matcher(output).find()
            || output.contains("\n\n");
    }

    /** 评估语气友好度：统计礼貌用语的出现次数。 */
    private double evaluateTone(String output) {
        String lower = output.toLowerCase();
        String[] politeWords = {
                "please", "thank you", "thanks", "appreciate",
                "would", "could", "may", "might",
                "glad", "happy to", "welcome", "feel free",
                "suggest", "recommend", "consider",
                "i hope", "let me", "here is", "here are"
        };

        int matches = (int) Arrays.stream(politeWords).filter(lower::contains).count();
        if (matches >= 5) return 1.0;
        if (matches >= 3) return 0.8;
        if (matches >= 1) return 0.6;
        return 0.3;
    }

    /** 对文本进行分词，返回小写词集合。 */
    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        return Arrays.stream(text.toLowerCase().split("[^\\p{L}\\p{N}]+"))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    /** 提取关键术语：过滤停用词和短词后的词集合。 */
    private Set<String> extractKeyTerms(String text) {
        Set<String> tokens = tokenize(text);
        return tokens.stream()
                .filter(t -> t.length() > 2)
                .filter(t -> !STOPWORDS.contains(t))
                .collect(Collectors.toSet());
    }

    private double clamp(double score) {
        return Math.max(0.0, Math.min(1.0, score));
    }
}
