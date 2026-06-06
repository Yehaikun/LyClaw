package lyjew.com.lyclaw.session;

import java.util.ArrayList;
import java.util.List;

import lyjew.com.lyclaw.model.Message;

/**
 * Token 预算上下文策略 —— 控制在指定 token 数量以内。
 *
 * <p>从最新消息开始回溯，累积 token 计数直至接近预算。
 * 首条 system 消息始终保留。</p>
 *
 * <p>注意：这里的 token 计数是基于字符数的粗略估算
 * （中英文混合约 2 字符/token），精确计数需对接模型的 tokenizer。</p>
 *
 * <p>默认预算：{@value DEFAULT_MAX_TOKENS} tokens。</p>
 */
public class TokenBudgetPolicy implements ContextPolicy {

    static final int DEFAULT_MAX_TOKENS = 8192;
    static final double CHARS_PER_TOKEN = 2.0; // 中英文混合估算

    private final int maxTokens;

    public TokenBudgetPolicy() {
        this(DEFAULT_MAX_TOKENS);
    }

    public TokenBudgetPolicy(int maxTokens) {
        this.maxTokens = Math.max(1024, maxTokens);
    }

    @Override
    public List<Message> prune(String sessionId, List<Message> fullMessages, ContextPolicyContext ctx) {
        if (fullMessages == null || fullMessages.isEmpty()) {
            return List.of();
        }

        // 估算总 token
        int totalEstimate = estimateTokens(fullMessages);
        if (totalEstimate <= maxTokens) {
            return fullMessages;
        }

        // 保留首条 system（如有）
        Message systemMsg = null;
        int startIdx = 0;
        if ("system".equals(fullMessages.get(0).getRole())) {
            systemMsg = fullMessages.get(0);
            startIdx = 1;
        }

        // 从最新消息回溯
        List<Message> result = new ArrayList<>();
        int budget = maxTokens;
        if (systemMsg != null) {
            budget -= estimateTokens(List.of(systemMsg));
        }

        for (int i = fullMessages.size() - 1; i >= startIdx && budget > 0; i--) {
            Message msg = fullMessages.get(i);
            int cost = estimateTokens(List.of(msg));
            if (cost <= budget) {
                result.add(0, msg);
                budget -= cost;
            } else {
                break;
            }
        }

        if (systemMsg != null) {
            result.add(0, systemMsg);
        }

        // 至少保留一条消息
        if (result.isEmpty() && !fullMessages.isEmpty()) {
            result.add(fullMessages.get(fullMessages.size() - 1));
        }

        return result;
    }

    private int estimateTokens(List<Message> messages) {
        int chars = 0;
        for (Message msg : messages) {
            if (msg.getContent() != null) {
                chars += msg.getContent().length();
            }
            if (msg.getThinking() != null) {
                chars += msg.getThinking().length();
            }
        }
        return (int) Math.ceil(chars / CHARS_PER_TOKEN);
    }

    public int getMaxTokens() { return maxTokens; }

    @Override
    public String name() {
        return "TokenBudget(" + maxTokens + ")";
    }
}
