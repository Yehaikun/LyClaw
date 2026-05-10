package lyjew.com.lyclaw.infra.security;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.filter.ContentFilter;
import lyjew.com.lyclaw.filter.FilterResult;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
public class PromptInjectionFilter implements ContentFilter {

    private static final Set<String> INJECTION_PATTERNS = Set.of(
            "ignore previous instructions", "ignore all previous",
            "disregard your instructions", "forget your training",
            "you are now", "new instructions:", "system prompt:",
            "<<SYS>>", "<|system|>", "DAN mode", "jailbreak");

    private static final Pattern PII_PATTERN = Pattern.compile(
            "\\b(\\d{3}-\\d{2}-\\d{4}|\\d{16}|\\d{3}-\\d{3}-\\d{4})\\b");

    @Override
    public FilterResult filter(String content, ChatContext context) {
        String lower = content.toLowerCase();

        for (String pattern : INJECTION_PATTERNS) {
            if (lower.contains(pattern)) {
                log.warn("[PromptInjection] Detected injection pattern: '{}'", pattern);
                return FilterResult.reject(content, "Prompt injection: " + pattern);
            }
        }

        if (PII_PATTERN.matcher(content).find()) {
            log.warn("[PromptInjection] PII detected in input");
            String sanitized = PII_PATTERN.matcher(content).replaceAll("***REDACTED***");
            return FilterResult.pass(sanitized);
        }

        return FilterResult.pass(content);
    }

    @Override
    public String getFilterName() {
        return "prompt-injection-filter";
    }
}
