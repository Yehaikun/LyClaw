package lyjew.com.lyclaw.infra.security;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.filter.ContentFilter;
import lyjew.com.lyclaw.filter.FilterResult;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 提示注入防护过滤器，检测并阻止常见的提示注入攻击和敏感个人信息泄露。
 *
 * <p>检测两类威胁：
 * <ul>
 *   <li><b>提示注入（Prompt Injection）</b>：匹配已知注入模式
 *       （如 "ignore previous instructions"、"DAN mode"、"jailbreak" 等），
 *       命中则直接拒绝输入</li>
 *   <li><b>个人身份信息（PII）</b>：匹配 SSN（xxx-xx-xxxx）、信用卡号（16位数字）、
 *       电话号码（xxx-xxx-xxxx）格式，命中则脱敏（替换为 ***REDACTED***）后放行</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class PromptInjectionFilter implements ContentFilter {

    /** 已知提示注入模式黑名单（不区分大小写匹配） */
    private static final Set<String> INJECTION_PATTERNS = Set.of(
            "ignore previous instructions", "ignore all previous",
            "disregard your instructions", "forget your training",
            "you are now", "new instructions:", "system prompt:",
            "<<SYS>>", "<|system|>", "DAN mode", "jailbreak");

    /** PII 正则模式：SSN (xxx-xx-xxxx)、信用卡号 (16位)、电话 (xxx-xxx-xxxx) */
    private static final Pattern PII_PATTERN = Pattern.compile(
            "\\b(\\d{3}-\\d{2}-\\d{4}|\\d{16}|\\d{3}-\\d{3}-\\d{4})\\b");

    /**
     * 过滤内容。
     *
     * <p>先检查提示注入（命中则拒绝），再检查 PII（命中则脱敏后放行）。</p>
     *
     * @param content 待过滤的内容
     * @param context 对话上下文
     * @return 过滤结果
     */
    @Override
    public FilterResult filter(String content, ChatContext context) {
        String lower = content.toLowerCase();

        // 1. 提示注入检测
        for (String pattern : INJECTION_PATTERNS) {
            if (lower.contains(pattern)) {
                log.warn("[PromptInjection] Detected injection pattern: '{}'", pattern);
                return FilterResult.reject(content, "Prompt injection: " + pattern);
            }
        }

        // 2. PII 检测与脱敏
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
