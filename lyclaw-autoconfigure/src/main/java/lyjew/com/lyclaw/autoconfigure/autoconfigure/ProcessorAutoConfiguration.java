package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import lyjew.com.lyclaw.autoconfigure.processor.*;
import lyjew.com.lyclaw.chat.*;
import lyjew.com.lyclaw.storage.*;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 注解处理器自动配置——注册所有 BeanPostProcessor 和自动配置器。
 *
 * <p>所有处理器使用 @ConditionalOnMissingBean 允许覆盖，
 * 部分处理器使用 @ConditionalOnBean 确保依赖就绪后才加载。
 */
@AutoConfiguration
public class ProcessorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(StorageBackendRegistry.class)
    public StorageBackendPostProcessor storageBackendPostProcessor(StorageBackendRegistry registry) {
        return new StorageBackendPostProcessor(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DefaultMemoryWriteManager.class)
    public WritePolicyPostProcessor writePolicyPostProcessor(DefaultMemoryWriteManager writeManager) {
        return new WritePolicyPostProcessor(writeManager);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({StorageBackendRegistry.class, StorageProperties.class})
    public MemorySystemAutoConfigurator memorySystemAutoConfigurator(
            StorageBackendRegistry registry, StorageProperties properties) {
        return new MemorySystemAutoConfigurator(registry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ChatModelRegistry.class)
    public ChatModelPostProcessor chatModelPostProcessor(ChatModelRegistry registry) {
        return new ChatModelPostProcessor(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ChatModelRegistry.class)
    public ModelRouterPostProcessor modelRouterPostProcessor(ChatModelRegistry registry) {
        return new ModelRouterPostProcessor(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ChatModelRegistry.class, ChatProperties.class})
    public OpenAiProtocolAutoConfigurator openAiProtocolAutoConfigurator(
            ChatModelRegistry registry, ChatProperties properties) {
        return new OpenAiProtocolAutoConfigurator(registry, properties);
    }
}
