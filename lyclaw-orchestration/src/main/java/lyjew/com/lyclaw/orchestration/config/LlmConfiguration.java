package lyjew.com.lyclaw.orchestration.config;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.adapter.deepseek.DeepSeekOpenAIAdapter;
import lyjew.com.lyclaw.model.ModelConfig;
import lyjew.com.lyclaw.provider.ModelProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM（大语言模型）配置类。
 *
 * 负责根据 application.yml 中的配置项创建并初始化 DeepSeek 模型适配器。
 * 如果没有配置 API Key，则 LLM 响应功能被禁用，但应用仍可正常启动。
 * 通过 @Primary 注解确保该 ModelProvider 在存在多个实现时优先注入。
 */
@Slf4j
@Configuration
public class LlmConfiguration {

    /**
     * 创建主要的 ModelProvider Bean。
     * 从配置文件读取 DeepSeek 的 API Key、Base URL 和模型名称，
     * 配置适配器后返回封装好的 ConfiguredModelProvider。
     *
     * @param deepseekAdapter DeepSeek OpenAI 兼容适配器
     * @param apiKey         API 密钥（配置键：lyclaw.llm.deepseek.api-key）
     * @param baseUrl        API 基础地址（默认 https://api.deepseek.com）
     * @param model          模型名称（默认 deepseek-v4-flash）
     * @return ModelProvider 实例
     */
    @Bean
    @Primary
    public ModelProvider modelProvider(
            DeepSeekOpenAIAdapter deepseekAdapter,
            @Value("${lyclaw.llm.deepseek.api-key:}") String apiKey,
            @Value("${lyclaw.llm.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${lyclaw.llm.deepseek.model:deepseek-v4-flash}") String model) {

        log.info("LLM configuration: model={}, baseUrl={}, apiKeyProvided={}",
                model, baseUrl, apiKey != null && !apiKey.isEmpty());

        // 仅当 API Key 非空时才真正配置适配器
        if (apiKey != null && !apiKey.isEmpty()) {
            ModelConfig config = ModelConfig.builder()
                    .provider("deepseek-openai")
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .model(model)
                    .enabled(true)
                    .build();
            deepseekAdapter.configure(config);
            log.info("DeepSeek adapter configured: provider={}, model={}", config.getProvider(), config.getModel());
        } else {
            log.warn("No DeepSeek API key configured (set DEEPSEEK_API_KEY env var). LLM responses disabled.");
        }

        return new ConfiguredModelProvider(deepseekAdapter);
    }

    /**
     * 封装单个适配器的模型提供者实现。
     * 这是一个 private record，用于适配单个 LLM 适配器到 ModelProvider 接口。
     */
    private record ConfiguredModelProvider(ModelAdapter adapter) implements ModelProvider {

        /**
         * 根据提供者名称获取适配器，仅当适配器已配置且名称匹配时返回。
         *
         * @param provider 提供者名称
         * @return 匹配的 ModelAdapter，不匹配则返回 null
         */
        @Override
        public ModelAdapter getAdapter(String provider) {
            if (adapter.isConfigured() && adapter.getProvider().equals(provider)) {
                return adapter;
            }
            return null;
        }

        /**
         * @return 默认的 LLM 提供者名称
         */
        @Override
        public String getDefaultProvider() {
            return adapter.getProvider();
        }

        /**
         * @return 已配置的适配器，未配置时返回 null
         */
        @Override
        public ModelAdapter getConfiguredAdapter() {
            return adapter.isConfigured() ? adapter : null;
        }

        /**
         * @return 可用提供者集合（当前仅一个）
         */
        @Override
        public Set<String> listProviders() {
            return Set.of(adapter.getProvider());
        }

        /**
         * 刷新提供者状态，当前实现为空操作。
         */
        @Override
        public void refresh() {
        }
    }
}
