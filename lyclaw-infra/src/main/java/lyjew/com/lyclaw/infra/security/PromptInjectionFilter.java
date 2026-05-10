package lyjew.com.lyclaw.infra.security;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.filter.ContentFilter;
import lyjew.com.lyclaw.filter.FilterResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Prompt 注入检测过滤器 —— 检测用户输入中的常见注入模式。
 *
 * @since 2.0
 */
@Component
public class PromptInjectionFilter implements ContentFilter {

    private static final Logger log = LoggerFactory.getLogger(PromptInjectionFilter.class);

    private static final Set<String> INJECTION_PATTERNS = Set.of(
            "ignore previous instructions",
            "ignore all previous",
            "disregard your instructions",
            "forget your training",
            "you are now",
            "new instructions:",
            "system prompt:",
            "<<SYS>>",
            "<|system|>",
            "DAN mode",
            "jailbreak"
    );

    private static final Pattern PII_PATTERN = Pattern.compile(
            "\\b(\\d{3}-\\d{2}-\\d{4}|\\d{16}|\\d{3}-\\d{3}-\\d{4})\\b"
    );

    @Override
    public FilterResult filter(String content, ChatContext context) {
        String lower = content.toLowerCase();

        // 检测提示注入
        for (String pattern : INJECTION_PATTERNS) {
            if (lower.contains(pattern)) {
                log.warn("[PromptInjection] Detected injection pattern: '{}'", pattern);
                return FilterResult.reject(content, "Prompt injection: " + pattern);
            }
        }

        // 检测 PII
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
