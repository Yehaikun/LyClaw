package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import lyjew.com.lyclaw.chat.*;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Adapter 层自动配置——注册 ChatModel 注册表和路由。
 *
 * <p>当配置了 lyclaw.chat.models.* 时激活。
 * @ConditionalOnMissingBean 确保使用者自定义实现优先于框架默认。</p>
 */
@AutoConfiguration
public class ChatAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConfigurationProperties(prefix = "lyclaw.chat")
    public ChatProperties chatProperties() {
        return new ChatProperties();
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatModelRegistry chatModelRegistry() {
        return new DefaultChatModelRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public FirstAvailableRouter firstAvailableRouter(ChatModelRegistry registry) {
        return new FirstAvailableRouter(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatFacade chatFacade(ChatModelRegistry registry, FirstAvailableRouter router) {
        return new DefaultChatFacade(registry, router);
    }
}
