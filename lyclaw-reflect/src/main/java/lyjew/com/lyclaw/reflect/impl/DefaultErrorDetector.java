package lyjew.com.lyclaw.reflect.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.reflect.DetectedError;
import lyjew.com.lyclaw.reflect.ErrorDetector;
import lyjew.com.lyclaw.reflect.ToolCallRecord;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DefaultErrorDetector implements ErrorDetector {

    private static final double HALLUCINATION_BASE_CONFIDENCE = 0.65;
    private static final double CONTRADICTION_BASE_CONFIDENCE = 0.75;
    private static final int CONSECUTIVE_FAILURE_THRESHOLD = 3;

    private static final String[] UNSUPPORTED_CLAIM_MARKERS = {
            "research shows", "studies have shown", "study shows",
            "it is well known", "as we all know", "obviously",
            "undoubtedly", "without a doubt", "it is proven",
            "experts agree", "scientists have found",
            "according to research", "data shows",
            "it has been demonstrated", "clearly"
    };

    private static final String[] HIGH_CONFIDENCE_MARKERS = {
            "definitely", "certainly", "absolutely", "always",
            "never", "without exception", "every single",
            "in all cases", "guaranteed", "invariably"
    };

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

    @Override
    public List<DetectedError> detectToolFailurePattern(List<ToolCallRecord> history) {
        if (history == null || history.isEmpty()) return Collections.emptyList();

        List<DetectedError> errors = new ArrayList<>();
        errors.addAll(detectConsecutiveFailures(history));
        errors.addAll(detectSystemicFailure(history));
        errors.addAll(detectTimeouts(history));

        return errors;
    }

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

    private boolean containsNegation(String text) {
        String[] negators = {" not ", "n't ", " never ", " no ", " neither ", " none ", " cannot ", " can't "};
        for (String n : negators) {
            if (text.contains(n)) return true;
        }
        return false;
    }

    private List<String> splitSentences(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();
        return Arrays.stream(text.split("[.!?\\n]+"))
                .map(String::trim)
                .filter(s -> s.length() >= 5)
                .toList();
    }

    private boolean containsWord(String sentence, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(sentence).find();
    }

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

    private String safeSubstring(String text, int start, int end) {
        if (text == null) return "";
        int safeStart = Math.max(0, Math.min(start, text.length()));
        int safeEnd = Math.max(safeStart, Math.min(end, text.length()));
        return text.substring(safeStart, safeEnd);
    }

    private String extractSurroundingContext(String output, String keyword, int window) {
        if (output == null || keyword == null) return "";
        int idx = output.toLowerCase().indexOf(keyword.toLowerCase());
        if (idx < 0) return "";
        int start = Math.max(0, idx - window / 2);
        int end = Math.min(output.length(), idx + keyword.length() + window / 2);
        return output.substring(start, end) + "...";
    }

    private String trimToWord(String text) {
        if (text == null || text.length() < 5) return text;
        int start = 0;
        while (start < text.length() && !Character.isLetterOrDigit(text.charAt(start))) start++;
        int end = text.length();
        while (end > start && !Character.isLetterOrDigit(text.charAt(end - 1))) end--;
        String trimmed = text.substring(start, end);
        return trimmed.length() > 100 ? trimmed.substring(0, 100) + "..." : trimmed;
    }

    private String trimSentences(String sentence) {
        if (sentence == null) return "";
        return sentence.length() > 60 ? sentence.substring(0, 60) + "..." : sentence;
    }
}
