package lyjew.com.lyclaw.react;

import java.lang.reflect.Proxy;
import java.util.List;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.chat.ChatFacade;
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

    public AgentProxyFactory(ChatFacade chatFacade, ReActEngine reActEngine,
                              ToolRegistry toolRegistry) {
        this(chatFacade, reActEngine, toolRegistry, null, null, null, List.of(), List.of());
    }

    public AgentProxyFactory(ChatFacade chatFacade, ReActEngine reActEngine,
                              ToolRegistry toolRegistry, String defaultSystemPrompt,
                              String modelOverride, String providerOverride) {
        this(chatFacade, reActEngine, toolRegistry, defaultSystemPrompt, modelOverride,
                providerOverride, List.of(), List.of());
    }

    public AgentProxyFactory(ChatFacade chatFacade, ReActEngine reActEngine,
                              ToolRegistry toolRegistry, String defaultSystemPrompt,
                              String modelOverride, String providerOverride,
                              List<AgentHook> hooks) {
        this(chatFacade, reActEngine, toolRegistry, defaultSystemPrompt, modelOverride,
                providerOverride, hooks, List.of());
    }

    public AgentProxyFactory(ChatFacade chatFacade, ReActEngine reActEngine,
                              ToolRegistry toolRegistry, String defaultSystemPrompt,
                              String modelOverride, String providerOverride,
                              List<AgentHook> hooks,
                              List<ReactivePipelineStage> stages) {
        this.chatFacade = chatFacade;
        this.reActEngine = reActEngine;
        this.toolRegistry = toolRegistry;
        this.defaultSystemPrompt = defaultSystemPrompt;
        this.modelOverride = modelOverride;
        this.providerOverride = providerOverride;
        this.hooks = hooks != null ? List.copyOf(hooks) : List.of();
        this.stages = stages != null ? List.copyOf(stages) : List.of();
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

        String systemPrompt = defaultSystemPrompt;
        String model = modelOverride;
        String provider = providerOverride;

        if (ann != null) {
            if (systemPrompt == null && !ann.description().isEmpty()) {
                systemPrompt = ann.description();
            }
            if ((model == null || model.isEmpty()) && !ann.model().isEmpty()) {
                model = ann.model();
            }
            if ((provider == null || provider.isEmpty()) && !ann.provider().isEmpty()) {
                provider = ann.provider();
            }
        }

        AgentInvocationHandler handler = new AgentInvocationHandler(
                chatFacade, reActEngine, toolRegistry, systemPrompt, model, provider,
                hooks, stages);

        return (T) Proxy.newProxyInstance(
                agentInterface.getClassLoader(),
                new Class<?>[]{agentInterface},
                handler);
    }
}
