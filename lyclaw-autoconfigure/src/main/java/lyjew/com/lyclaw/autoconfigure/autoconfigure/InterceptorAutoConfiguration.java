package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import lyjew.com.lyclaw.autoconfigure.processor.InterceptorProcessor;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the interceptor discovery chain.
 */
@AutoConfiguration
public class InterceptorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public InterceptorProcessor interceptorProcessor() {
        return new InterceptorProcessor();
    }
}
