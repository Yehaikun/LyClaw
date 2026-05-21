package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import java.util.Arrays;
import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import lyjew.com.lyclaw.autoconfigure.processor.AgentInterfaceProcessor;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.chat.ChatModelRegistry;
import lyjew.com.lyclaw.chat.catalog.ModelCatalog;
import lyjew.com.lyclaw.chat.config.ModelResolutionService;
import lyjew.com.lyclaw.config.AgentConfigResolver;
import lyjew.com.lyclaw.config.AgentDefaultsConfig;
import lyjew.com.lyclaw.persistence.SessionFactory;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.react.AgentHook;
import lyjew.com.lyclaw.react.AgentProxyFactory;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.react.subagent.DelegateToAgentToolProvider;
import lyjew.com.lyclaw.react.subagent.SubagentConfig;
import lyjew.com.lyclaw.react.subagent.SubagentSpawner;
import lyjew.com.lyclaw.react.subagent.SubagentSessionManager;
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

    // ══════════════════════════════════════════════════════════════
    //  Phase 2: Subagent + Model Management beans
    // ══════════════════════════════════════════════════════════════

    /**
     * Subagent runtime configuration, populated from
     * {@code lyclaw.agent.defaults.subagent-*} keys in application.yml
     * via {@link AgentDefaultsConfig}.
     */
    @Bean
    @ConditionalOnMissingBean(SubagentConfig.class)
    public SubagentConfig subagentConfig(AgentDefaultsConfig defaults) {
        SubagentConfig config = new SubagentConfig();
        config.setDelegationMode(defaults.getSubagentDelegationMode());
        // subagentAllowAgents: "*" means allow all, otherwise comma-separated
        String rawAllow = defaults.getSubagentAllowAgents();
        if (rawAllow != null && !rawAllow.isBlank() && !"*".equals(rawAllow.trim())) {
            config.setAllowAgents(Arrays.asList(rawAllow.split("\\s*,\\s*")));
        } else {
            config.setAllowAgents(List.of("*"));
        }
        config.setMaxConcurrent(defaults.getSubagentMaxConcurrent());
        config.setMaxSpawnDepth(defaults.getSubagentMaxSpawnDepth());
        config.setMaxChildrenPerAgent(defaults.getSubagentMaxChildrenPerAgent());
        config.setArchiveAfterMinutes(defaults.getSubagentArchiveAfterMinutes());
        String subModel = defaults.getSubagentModel();
        config.setModel(subModel != null && !subModel.isBlank() ? subModel : null);
        String subThinking = defaults.getSubagentThinking();
        config.setThinking(subThinking != null && !subThinking.isBlank() ? subThinking : null);
        config.setRunTimeoutSeconds(defaults.getSubagentRunTimeoutSeconds());
        config.setAnnounceTimeoutMs(defaults.getSubagentAnnounceTimeoutMs());
        config.setRequireAgentId(defaults.isSubagentRequireAgentId());
        return config;
    }

    /**
     * Subagent session manager — tracks hierarchical session keys for the
     * subagent tree. SessionStore is not available yet; pass {@code null}
     * and operate in in-memory-only mode.
     *
     * TODO: wire SessionStore when {@code lyjew.com.lyclaw.persistence.SessionStore}
     *       is added to the framework.
     */
    @Bean
    @ConditionalOnMissingBean(SubagentSessionManager.class)
    public SubagentSessionManager subagentSessionManager() {
        return new SubagentSessionManager(null);
    }

    /**
     * 多模态模型目录，Provider 在启动后通过此目录注册各自支持的模态模型。
     */
    @Bean
    @ConditionalOnMissingBean(ModelCatalog.class)
    public ModelCatalog modelCatalog() {
        return new ModelCatalog();
    }

    /**
     * 模型解析服务，按模态和降级链解析最优模型。
     */
    @Bean
    @ConditionalOnMissingBean(ModelResolutionService.class)
    public ModelResolutionService modelResolutionService(
            ChatModelRegistry chatModelRegistry,
            ModelCatalog modelCatalog,
            AgentDefaultsConfig defaults) {
        return new ModelResolutionService(chatModelRegistry, modelCatalog,
                defaults.getModelFallbackChain());
    }

    /**
     * Subagent spawner — orchestrates the full lifecycle of child agents,
     * from limit validation through ReAct loop execution to result collection.
     */
    @Bean
    @ConditionalOnMissingBean(SubagentSpawner.class)
    public SubagentSpawner subagentSpawner(
            ChatFacade chatFacade,
            ReActEngine reActEngine,
            ToolRegistry toolRegistry,
            AgentConfigResolver configResolver,
            SessionFactory sessionFactory,
            List<ReactivePipelineStage> stages,
            List<AgentHook> hooks) {
        List<AgentHook> hookList = hooks != null ? hooks : List.of();
        List<ReactivePipelineStage> pipelineStages = stages != null ? stages : List.of();
        return new SubagentSpawner(chatFacade, reActEngine, toolRegistry,
                configResolver, sessionFactory, pipelineStages, hookList);
    }

    /**
     * Built-in tool provider that injects {@code delegate_to_agent} into every
     * agent's tool set, enabling LLM-driven task delegation to subagents.
     * Enabled/disabled via the {@code lyclaw.agent.defaults.subagent-enabled} flag.
     */
    @Bean
    @ConditionalOnMissingBean(DelegateToAgentToolProvider.class)
    public DelegateToAgentToolProvider delegateToAgentToolProvider(
            SubagentSpawner spawner,
            AgentDefaultsConfig defaults) {
        return new DelegateToAgentToolProvider(spawner, defaults.isSubagentEnabled());
    }
}
