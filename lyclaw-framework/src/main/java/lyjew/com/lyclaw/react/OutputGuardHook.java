package lyjew.com.lyclaw.react;

import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 输出安检 Hook，在 LLM 每次响应后检测有害内容。
 *
 * <p>order=90，确保在 afterResult 之前执行。
 * 基于正则关键词检测：提示注入、敏感信息泄露标记。</p>
 */
public class OutputGuardHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(OutputGuardHook.class);

    private static final List<Pattern> BLOCKED_PATTERNS = List.of(
            Pattern.compile("(?i)(password|secret|api[_-]?key|token)\\s*[:=]\\s*\\S+"),
            Pattern.compile("(?i)<script\\b[^>]*>"),
            Pattern.compile("(?i)(DROP\\s+TABLE|DELETE\\s+FROM|TRUNCATE\\s+TABLE)")
    );

    private static final Pattern HALLUCINATION_PATTERN = Pattern.compile(
            "(?i)(I am not sure|I don't know|cannot provide|no information|I cannot)");

    @Override
    public int getOrder() { return 90; }

    @Override
    public String afterModel(String response, AgentContext ctx) {
        if (response == null || response.isEmpty()) {
            return response;
        }

        for (Pattern p : BLOCKED_PATTERNS) {
            if (p.matcher(response).find()) {
                log.warn("OutputGuard: blocked sensitive content in response for session={}",
                        ctx.getSessionId());
                return "[Response filtered: potentially sensitive content detected]";
            }
        }

        if (HALLUCINATION_PATTERN.matcher(response).find()) {
            log.info("OutputGuard: possible hallucination markers detected for session={}",
                    ctx.getSessionId());
            ctx.setAttribute("hallucination_detected", Boolean.TRUE);
        }

        return response;
    }
}
