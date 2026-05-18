package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import lyjew.com.lyclaw.autoconfigure.processor.AgentInterfaceProcessor;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.react.AgentProxyFactory;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.tool.ToolRegistry;

/**
 * Agent 动态代理的自动配置。
 *
 * <p>在检测到 ChatFacade、ReActEngine、ToolRegistry 均可用时自动启用。
 * 创建 AgentProxyFactory 和 AgentInterfaceProcessor，使得标注 @Agent 的
 * 接口能被自动发现并注册为 Spring Bean。
 */
@AutoConfiguration
@ConditionalOnClass({ReActEngine.class, ToolRegistry.class, ChatFacade.class})
public class AgentProxyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ChatFacade.class, ReActEngine.class, ToolRegistry.class})
    public AgentProxyFactory agentProxyFactory(ChatFacade chatFacade,
                                                ReActEngine reActEngine,
                                                ToolRegistry toolRegistry) {
        return new AgentProxyFactory(chatFacade, reActEngine, toolRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AgentProxyFactory.class)
    public static AgentInterfaceProcessor agentInterfaceProcessor(
            AgentProxyFactory agentProxyFactory) {
        return new AgentInterfaceProcessor(agentProxyFactory);
    }
}
