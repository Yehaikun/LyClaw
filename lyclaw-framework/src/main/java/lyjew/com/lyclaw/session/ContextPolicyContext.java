package lyjew.com.lyclaw.session;

import lyjew.com.lyclaw.model.ChatRequest;

/**
 * 上下文裁剪策略的运行时上下文。
 *
 * <p>携带 LLM 请求的参数，供策略决策时参考。</p>
 */
public class ContextPolicyContext {

    private final ChatRequest request;
    private final int maxTokens;

    public ContextPolicyContext(ChatRequest request, int maxTokens) {
        this.request = request;
        this.maxTokens = maxTokens;
    }

    public ChatRequest getRequest() { return request; }
    public int getMaxTokens() { return maxTokens; }

    public static ContextPolicyContext fromRequest(ChatRequest request) {
        int maxTokens = request.getMaxTokens() != null ? request.getMaxTokens() : 8192;
        return new ContextPolicyContext(request, maxTokens);
    }
}
