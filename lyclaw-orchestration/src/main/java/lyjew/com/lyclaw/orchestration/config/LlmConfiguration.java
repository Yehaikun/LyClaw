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

@Slf4j
@Configuration
public class LlmConfiguration {

    @Bean
    @Primary
    public ModelProvider modelProvider(
            DeepSeekOpenAIAdapter deepseekAdapter,
            @Value("${lyclaw.llm.deepseek.api-key:}") String apiKey,
            @Value("${lyclaw.llm.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${lyclaw.llm.deepseek.model:deepseek-v4-flash}") String model) {

        log.info("LLM configuration: model={}, baseUrl={}, apiKeyProvided={}",
                model, baseUrl, apiKey != null && !apiKey.isEmpty());

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

    private record ConfiguredModelProvider(ModelAdapter adapter) implements ModelProvider {

        @Override
        public ModelAdapter getAdapter(String provider) {
            if (adapter.isConfigured() && adapter.getProvider().equals(provider)) {
                return adapter;
            }
            return null;
        }

        @Override
        public String getDefaultProvider() {
            return adapter.getProvider();
        }

        @Override
        public ModelAdapter getConfiguredAdapter() {
            return adapter.isConfigured() ? adapter : null;
        }

        @Override
        public Set<String> listProviders() {
            return Set.of(adapter.getProvider());
        }

        @Override
        public void refresh() {
        }
    }
}
