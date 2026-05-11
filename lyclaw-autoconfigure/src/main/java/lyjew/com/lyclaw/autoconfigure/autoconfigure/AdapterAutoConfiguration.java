package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.autoconfigure.processor.AdapterAnnotationProcessor;
import lyjew.com.lyclaw.framework.config.LyClawProperties;
import lyjew.com.lyclaw.provider.ModelProvider;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.Set;

/**
 * Auto-configuration for the ModelAdapter detection and the
 * corresponding {@link ModelProvider} bean that selects the
 * adapter matching the configured LLM provider.
 *
 * @deprecated 已由 ChatAutoConfiguration + ChatModelPostProcessor 取代。
 *             保留以备回滚到旧版配置（lyclaw.chat.legacy=true）。
 */
@Deprecated
@AutoConfiguration
@ConditionalOnClass(ModelAdapter.class)
public class AdapterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AdapterAnnotationProcessor adapterAnnotationProcessor() {
        return new AdapterAnnotationProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelProvider modelProvider(LyClawProperties props,
                                       AdapterAnnotationProcessor processor) {
        return new ModelProvider() {

            @Override
            public ModelAdapter getAdapter(String provider) {
                return processor.getAdapter(provider).orElse(null);
            }

            @Override
            public String getDefaultProvider() {
                return props.getLlm().getProvider();
            }

            @Override
            public ModelAdapter getConfiguredAdapter() {
                String provider = props.getLlm().getProvider();
                return processor.getAdapter(provider)
                        .orElseThrow(() -> new IllegalStateException(
                                "No ModelAdapter found for provider '" + provider
                                        + "'. Available: " + processor.getAvailableProviders()));
            }

            @Override
            public Set<String> listProviders() {
                return processor.getAvailableProviders();
            }

            @Override
            public void refresh() {
                // Adapters are statically registered via BeanPostProcessor;
                // runtime refresh is not supported in this configuration.
            }
        };
    }
}
