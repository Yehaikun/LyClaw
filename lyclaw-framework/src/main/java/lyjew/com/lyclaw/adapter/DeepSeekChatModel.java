package lyjew.com.lyclaw.adapter;

import lyjew.com.lyclaw.annotation.chat.ChatModel;
import lyjew.com.lyclaw.annotation.chat.CircuitBreaker;
import lyjew.com.lyclaw.annotation.chat.Fallback;
import lyjew.com.lyclaw.annotation.chat.ModelCapability;
import lyjew.com.lyclaw.annotation.chat.RetryPolicy;
import lyjew.com.lyclaw.chat.ModelCapabilities;
import lyjew.com.lyclaw.model.ModelConfig;

/**
 * DeepSeek ChatModel，基于 OpenAI 兼容协议。
 *
 * <p>通过注解声明弹性能力：@RetryPolicy（3 次重试 + 指数退避）、
 * @Fallback（降级链到备用模型）、@CircuitBreaker（5 次失败后熔断 30 秒）。
 * ChatModelPostProcessor 解析这些注解后自动包装装饰器链：
 * CircuitBreaker → Retry → Fallback → DeepSeekChatModel。</p>
 */
@ChatModel(
    provider = "deepseek",
    displayName = "DeepSeek",
    description = "DeepSeek V4 系列模型，支持思考模式、工具调用",
    protocol = ChatModel.ModelProtocol.OPENAI,
    defaultModel = "deepseek-v4-flash",
    defaultBaseUrl = "https://api.deepseek.com",
    priority = 100
)
@ModelCapability(
    streaming = true,
    toolCalling = true,
    toolCallStreaming = false,
    thinking = true,
    vision = false,
    promptCaching = false,
    maxInputTokens = 128000,
    maxOutputTokens = 8192
)
@RetryPolicy(
    maxAttempts = 3,
    backoff = RetryPolicy.BackoffStrategy.EXPONENTIAL,
    retryOn = {
        RetryPolicy.HttpStatusHint.TOO_MANY_REQUESTS,
        RetryPolicy.HttpStatusHint.SERVICE_UNAVAILABLE,
        RetryPolicy.HttpStatusHint.GATEWAY_TIMEOUT
    }
)
@Fallback(chain = {"openai:gpt-4o-mini", "groq:llama-4"})
@CircuitBreaker(failureThreshold = 5, halfOpenAfterSeconds = 30)
public class DeepSeekChatModel extends OpenAiProtocolChatModel {

    public DeepSeekChatModel(ModelConfig config) {
        super("deepseek", config);
    }

    public DeepSeekChatModel(
            String baseUrl, String apiKey, String model, ModelCapabilities capabilities) {
        super("deepseek", baseUrl, apiKey, model, capabilities);
    }

    @Override
    protected String getDefaultBaseUrl() {
        return "https://api.deepseek.com";
    }

    @Override
    protected String getDefaultModel() {
        return "deepseek-v4-flash";
    }
}
