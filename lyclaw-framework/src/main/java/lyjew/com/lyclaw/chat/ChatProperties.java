package lyjew.com.lyclaw.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter 层配置属性，对应 application.yml 中 lyclaw.chat.* 前缀。
 *
 * <p>支持配置全局默认 Provider/模型、路由开关、各 Provider 的模型配置（OpenAI 兼容协议 +
 * Anthropic/Ollama 等独立协议）、全局降级链和熔断配置。
 *
 * <p>不配任何配置时，回退到旧 LlmConfiguration 硬编码 DeepSeek 路径（向后兼容）。
 *
 * <p>Spring Boot 的 @ConfigurationProperties 绑定由 autoconfigure 模块的
 * ChatAutoConfiguration 通过 @EnableConfigurationProperties 完成。
 */
public class ChatProperties {

    /** 全局默认 Provider */
    private String defaultProvider = "deepseek";

    /** 全局默认模型名称 */
    private String defaultModel = "deepseek-v4-flash";

    /** 是否启用路由 */
    private boolean routingEnabled;

    /** 各 Provider 的模型配置 */
    private Map<String, ModelProperties> models = new HashMap<>();

    /** 全局降级链 */
    private List<String> fallbackChain = new ArrayList<>();

    /** 全局熔断配置 */
    private CircuitBreakerProperties circuitBreaker = new CircuitBreakerProperties();

    /** 是否使用旧版配置（向后兼容） */
    private boolean legacy = true;

    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }
    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
    public boolean isRoutingEnabled() { return routingEnabled; }
    public void setRoutingEnabled(boolean routingEnabled) { this.routingEnabled = routingEnabled; }
    public Map<String, ModelProperties> getModels() { return models; }
    public void setModels(Map<String, ModelProperties> models) { this.models = models; }
    public List<String> getFallbackChain() { return fallbackChain; }
    public void setFallbackChain(List<String> fallbackChain) { this.fallbackChain = fallbackChain; }
    public CircuitBreakerProperties getCircuitBreaker() { return circuitBreaker; }
    public void setCircuitBreaker(CircuitBreakerProperties circuitBreaker) { this.circuitBreaker = circuitBreaker; }
    public boolean isLegacy() { return legacy; }
    public void setLegacy(boolean legacy) { this.legacy = legacy; }

    /**
     * 单个 Provider 的模型配置。
     */
    public static class ModelProperties {
        /** 协议类型：openai-protocol、anthropic、ollama、gemini */
        private String provider;
        /** API 端点 URL */
        private String baseUrl;
        /** API 密钥 */
        private String apiKey;
        /** 模型名称 */
        private String model;
        /** 重试配置 */
        private RetryProperties retry = new RetryProperties();
        /** 降级链（Provider 级别） */
        private List<String> fallback = new ArrayList<>();
        /** 扩展参数 */
        private Map<String, Object> options = new HashMap<>();

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public RetryProperties getRetry() { return retry; }
        public void setRetry(RetryProperties retry) { this.retry = retry; }
        public List<String> getFallback() { return fallback; }
        public void setFallback(List<String> fallback) { this.fallback = fallback; }
        public Map<String, Object> getOptions() { return options; }
        public void setOptions(Map<String, Object> options) { this.options = options; }
    }

    /** 重试配置 */
    public static class RetryProperties {
        private int maxAttempts = 3;
        private String backoff = "exponential";
        private long baseDelayMs = 1000;

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public String getBackoff() { return backoff; }
        public void setBackoff(String backoff) { this.backoff = backoff; }
        public long getBaseDelayMs() { return baseDelayMs; }
        public void setBaseDelayMs(long baseDelayMs) { this.baseDelayMs = baseDelayMs; }
    }

    /** 熔断器配置 */
    public static class CircuitBreakerProperties {
        private int failureThreshold = 5;
        private long halfOpenAfterSeconds = 30;
        private int halfOpenMaxRequests = 3;

        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
        public long getHalfOpenAfterSeconds() { return halfOpenAfterSeconds; }
        public void setHalfOpenAfterSeconds(long halfOpenAfterSeconds) { this.halfOpenAfterSeconds = halfOpenAfterSeconds; }
        public int getHalfOpenMaxRequests() { return halfOpenMaxRequests; }
        public void setHalfOpenMaxRequests(int halfOpenMaxRequests) { this.halfOpenMaxRequests = halfOpenMaxRequests; }
    }
}
