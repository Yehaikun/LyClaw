package lyjew.com.lyclaw.react;

import java.lang.reflect.Proxy;
import java.util.List;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.config.AgentConfigResolver;
import lyjew.com.lyclaw.config.ResolvedAgentConfig;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.tool.ToolRegistry;

/**
 * Agent 接口的动态代理工厂。
 *
 * <p>通过 JDK {@link Proxy#newProxyInstance} 为标注了 {@link Agent @Agent} 的
 * 接口创建运行时实现，将方法调用透明地转换为 Stage 管线 + ReAct 循环。</p>
 */
public class AgentProxyFactory {

    private final ChatFacade chatFacade;
    private final ReActEngine reActEngine;
    private final ToolRegistry toolRegistry;
    private final String defaultSystemPrompt;
    private final String modelOverride;
    private final String providerOverride;
    private final List<AgentHook> hooks;
    private final List<ReactivePipelineStage> stages;
    private final AgentConfigResolver configResolver;

    public AgentProxyFactory(ChatFacade chatFacade, ReActEngine reActEngine,
                              ToolRegistry toolRegistry) {
        this(chatFacade, reActEngine, toolRegistry, null, null, null, List.of(), List.of(), null);
    }

    public AgentProxyFactory(ChatFacade chatFacade, ReActEngine reActEngine,
                              ToolRegistry toolRegistry, String defaultSystemPrompt,
                              String modelOverride, String providerOverride) {
        this(chatFacade, reActEngine, toolRegistry, defaultSystemPrompt, modelOverride,
                providerOverride, List.of(), List.of(), null);
    }

    public AgentProxyFactory(ChatFacade chatFacade, ReActEngine reActEngine,
                              ToolRegistry toolRegistry, String defaultSystemPrompt,
                              String modelOverride, String providerOverride,
                              List<AgentHook> hooks) {
        this(chatFacade, reActEngine, toolRegistry, defaultSystemPrompt, modelOverride,
                providerOverride, hooks, List.of(), null);
    }

    public AgentProxyFactory(ChatFacade chatFacade, ReActEngine reActEngine,
                              ToolRegistry toolRegistry, String defaultSystemPrompt,
                              String modelOverride, String providerOverride,
                              List<AgentHook> hooks,
                              List<ReactivePipelineStage> stages) {
        this(chatFacade, reActEngine, toolRegistry, defaultSystemPrompt, modelOverride,
                providerOverride, hooks, stages, null);
    }

    public AgentProxyFactory(ChatFacade chatFacade, ReActEngine reActEngine,
                              ToolRegistry toolRegistry, String defaultSystemPrompt,
                              String modelOverride, String providerOverride,
                              List<AgentHook> hooks,
                              List<ReactivePipelineStage> stages,
                              AgentConfigResolver configResolver) {
        this.chatFacade = chatFacade;
        this.reActEngine = reActEngine;
        this.toolRegistry = toolRegistry;
        this.defaultSystemPrompt = defaultSystemPrompt;
        this.modelOverride = modelOverride;
        this.providerOverride = providerOverride;
        this.hooks = hooks != null ? List.copyOf(hooks) : List.of();
        this.stages = stages != null ? List.copyOf(stages) : List.of();
        this.configResolver = configResolver;
    }

    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> agentInterface) {
        if (chatFacade == null) {
            throw new IllegalStateException("ChatFacade must not be null");
        }
        if (reActEngine == null) {
            throw new IllegalStateException("ReActEngine must not be null");
        }
        if (toolRegistry == null) {
            throw new IllegalStateException("ToolRegistry must not be null");
        }

        Agent ann = agentInterface.getAnnotation(Agent.class);
        ResolvedAgentConfig resolvedConfig = (configResolver != null && ann != null)
                ? configResolver.resolve(ann)
                : ResolvedAgentConfig.fromAnnotation(ann);

        String systemPrompt = (defaultSystemPrompt != null) ? defaultSystemPrompt :
                (ann != null && !ann.systemPromptOverride().isEmpty()) ? ann.systemPromptOverride() : null;

        String model = (modelOverride != null && !modelOverride.isEmpty()) ? modelOverride :
                (resolvedConfig.getModel() != null && !resolvedConfig.getModel().isEmpty()) ? resolvedConfig.getModel() : null;

        String provider = (providerOverride != null && !providerOverride.isEmpty()) ? providerOverride :
                (resolvedConfig.getProvider() != null && !resolvedConfig.getProvider().isEmpty()) ? resolvedConfig.getProvider() : null;

        AgentInvocationHandler handler = new AgentInvocationHandler(
                chatFacade, reActEngine, toolRegistry, systemPrompt, model, provider,
                hooks, stages, resolvedConfig);

        return (T) Proxy.newProxyInstance(
                agentInterface.getClassLoader(),
                new Class<?>[]{agentInterface},
                handler);
    }
}
