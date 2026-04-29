package lyjew.com.lyclaw.event.impl;

import lyjew.com.lyclaw.event.Event;

/**
 * Token 消耗事件 —— 当模型调用消耗了 Token 时发布。
 *
 * <p>MetricsStage 或 ModelProvider 在模型调用完成后发布此事件，
 * EventBus 的订阅者（如 LoggingInterceptor、计费模块）据此记录
 * Token 消耗量。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Event
 */
public class TokenConsumedEvent extends Event {

    /** 模型提供商标识 */
    private final String provider;

    /** 使用的模型名称 */
    private final String model;

    /** 提示（输入）Token 数量 */
    private final int promptTokens;

    /** 补全（输出）Token 数量 */
    private final int completionTokens;

    /** 总 Token 数量 */
    private final int totalTokens;

    /**
     * 构造 Token 消耗事件。
     *
     * @param source           事件来源
     * @param provider         模型提供商标识
     * @param model            模型名称
     * @param promptTokens     提示 Token 数
     * @param completionTokens 补全 Token 数
     * @param totalTokens      总 Token 数
     */
    public TokenConsumedEvent(String source, String provider, String model,
                              int promptTokens, int completionTokens, int totalTokens) {
        super(source, "TOKEN_CONSUMED");
        this.provider = provider;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    /** @return 模型提供商标识 */
    public String getProvider() { return provider; }

    /** @return 模型名称 */
    public String getModel() { return model; }

    /** @return 提示 Token 数 */
    public int getPromptTokens() { return promptTokens; }

    /** @return 补全 Token 数 */
    public int getCompletionTokens() { return completionTokens; }

    /** @return 总 Token 数 */
    public int getTotalTokens() { return totalTokens; }
}