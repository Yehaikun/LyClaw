package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import lyjew.com.lyclaw.autoconfigure.facade.ExtensionFacade;
import lyjew.com.lyclaw.autoconfigure.processor.PipelineStageProcessor;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the pipeline stage discovery chain.
 */
@AutoConfiguration
public class PipelineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PipelineStageProcessor pipelineStageProcessor() {
        return new PipelineStageProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    public ExtensionFacade extensionFacade() {
        return new ExtensionFacade();
    }
}
