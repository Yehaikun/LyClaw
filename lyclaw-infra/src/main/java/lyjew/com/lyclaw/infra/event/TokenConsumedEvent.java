package lyjew.com.lyclaw.infra.event;

import lyjew.com.lyclaw.event.Event;

/**
 * Token 消耗事件，在每次 LLM 调用完成后发布。
 *
 * <p>携带模型提供商、模型名称、提示/补全 Token 数量、调用延迟和会话 ID，
 * 用于成本核算、速率限制和用量监控。</p>
 */
public class TokenConsumedEvent extends Event {

    /** 模型提供商名称 */
    private final String provider;
    /** 模型名称 */
    private final String model;
    /** 提示消耗的 Token 数 */
    private final int promptTokens;
    /** 补全消耗的 Token 数 */
    private final int completionTokens;
    /** 调用延迟（毫秒） */
    private final long latencyMs;
    /** 关联的会话 ID */
    private final String sessionId;

    /**
     * 构造一个 Token 消耗事件。
     *
     * @param source          事件来源标识
     * @param provider        模型提供商
     * @param model           模型名称
     * @param promptTokens    提示 Token 数
     * @param completionTokens 补全 Token 数
     * @param latencyMs       调用延迟（毫秒）
     * @param sessionId       会话 ID
     */
    public TokenConsumedEvent(String source, String provider, String model,
                              int promptTokens, int completionTokens, long latencyMs,
                              String sessionId) {
        super(source, "TOKEN_CONSUMED");
        this.provider = provider;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.latencyMs = latencyMs;
        this.sessionId = sessionId;
    }

    /** @return 模型提供商 */
    public String getProvider() { return provider; }
    /** @return 模型名称 */
    public String getModel() { return model; }
    /** @return 提示 Token 数 */
    public int getPromptTokens() { return promptTokens; }
    /** @return 补全 Token 数 */
    public int getCompletionTokens() { return completionTokens; }
    /** @return 调用延迟（毫秒） */
    public long getLatencyMs() { return latencyMs; }
    /** @return 会话 ID */
    public String getSessionId() { return sessionId; }
    /** @return 总 Token 数（提示 + 补全） */
    public int getTotalTokens() { return promptTokens + completionTokens; }
}
