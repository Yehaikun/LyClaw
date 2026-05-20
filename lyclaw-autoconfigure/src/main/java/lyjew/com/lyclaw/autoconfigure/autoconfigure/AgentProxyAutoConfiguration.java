package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import lyjew.com.lyclaw.autoconfigure.processor.AgentInterfaceProcessor;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.config.AgentConfigResolver;
import lyjew.com.lyclaw.config.AgentDefaultsConfig;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.react.AgentHook;
import lyjew.com.lyclaw.react.AgentProxyFactory;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.tool.ToolRegistry;

/**
 * Agent 动态代理的自动配置。
 *
 * <p>在检测到 ChatFacade、ReActEngine、ToolRegistry 均可用时自动启用。
 * 创建 AgentProxyFactory 和 AgentInterfaceProcessor，使得标注 @Agent 的
 * 接口能被自动发现并注册为 Spring Bean。
 * Stage 管线通过 ReactivePipelineStage Bean 列表自动注入。
 */
@AutoConfiguration
@AutoConfigureAfter({ChatAutoConfiguration.class, ReActAutoConfiguration.class, ToolAutoConfiguration.class})
@ConditionalOnClass({ReActEngine.class, ToolRegistry.class, ChatFacade.class})
@EnableConfigurationProperties(AgentDefaultsConfig.class)
public class AgentProxyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentConfigResolver.class)
    public AgentConfigResolver agentConfigResolver(AgentDefaultsConfig defaults) {
        return new AgentConfigResolver(defaults);
    }

    @Bean
    @ConditionalOnMissingBean(AgentProxyFactory.class)
    public AgentProxyFactory agentProxyFactory(ChatFacade chatFacade,
                                                ReActEngine reActEngine,
                                                ToolRegistry toolRegistry,
                                                List<ReactivePipelineStage> stages,
                                                List<AgentHook> hooks,
                                                AgentConfigResolver configResolver) {
        List<AgentHook> hookList = hooks != null ? hooks : List.of();
        List<ReactivePipelineStage> pipelineStages = stages != null ? stages : List.of();
        return new AgentProxyFactory(chatFacade, reActEngine, toolRegistry,
                null, null, null, hookList, pipelineStages, configResolver);
    }

    @Bean
    @ConditionalOnMissingBean(AgentInterfaceProcessor.class)
    public static AgentInterfaceProcessor agentInterfaceProcessor() {
        return new AgentInterfaceProcessor();
    }
}
