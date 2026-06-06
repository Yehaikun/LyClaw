package lyjew.com.lyclaw.mesh.impl;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.mesh.AgentFactory;
import lyjew.com.lyclaw.mesh.AgentInstance;
import lyjew.com.lyclaw.mesh.AgentRef;
import lyjew.com.lyclaw.mesh.AgentSpec;
import lyjew.com.lyclaw.mesh.AgentMesh;
import lyjew.com.lyclaw.react.DefaultReActEngine;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.mesh.impl.DefaultAgentMesh;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolRegistry;

/**
 * 默认 Agent 工厂 —— 根据 AgentSpec 创建对应的 AgentInstance 实现。
 *
 * <p>使用策略模式：根据 spec.type 选择不同的创建策略。
 * 用户可以扩展 {@link AgentFactory} 接口添加自定义 Agent 类型。</p>
 */
public class DefaultAgentFactory implements AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentFactory.class);

    private volatile AgentMesh mesh;
    private ChatFacade chatFacade;
    private ReActEngine reActEngine;
    private ToolRegistry toolRegistry;

    public DefaultAgentFactory() {}

    public DefaultAgentFactory(AgentMesh mesh) {
        this.mesh = mesh;
    }

    /** 注入 AgentMesh */
    public void setMesh(AgentMesh mesh) { this.mesh = mesh; }

    /** 注入 ChatFacade */
    public void setChatFacade(ChatFacade chatFacade) { this.chatFacade = chatFacade; }

    /** 注入 ReActEngine */
    public void setReActEngine(ReActEngine reActEngine) { this.reActEngine = reActEngine; }

    /** 注入 ToolRegistry */
    public void setToolRegistry(ToolRegistry toolRegistry) { this.toolRegistry = toolRegistry; }

    @Override
    public AgentInstance create(AgentSpec spec) {
        return switch (spec.getType()) {
            case LLM -> createLLM(spec);
            case TOOL -> createTool(spec);
            case ORCHESTRATOR -> createOrchestrator(spec);
            case PROXY -> throw new UnsupportedOperationException(
                    "ProxyAgent must be created via AgentProxyFactory");
        };
    }

    private AgentInstance createLLM(AgentSpec spec) {
        ReActEngine engine = reActEngine != null ? reActEngine : new DefaultReActEngine(null);
        ToolRegistry registry = toolRegistry;

        // 如果 spec 指定了私有工具，创建作用域化的 ToolRegistry
        if (spec.getTools() != null && !spec.getTools().isEmpty()) {
            registry = createScopedToolRegistry(spec);
        }

        // 使用有效的 mesh 引用：优先 Spring 注入的，其次静态默认实例
        AgentMesh effectiveMesh = this.mesh != null ? this.mesh : DefaultAgentMesh.getDefault();

        LLMAgentInstance instance = new LLMAgentInstance(
                spec, engine, chatFacade, registry, effectiveMesh);
        log.info("Created LLM Agent: {} (model={}, tools={})",
                spec.getAgentId(), spec.getModel(),
                spec.getTools() != null ? spec.getTools().size() : 0);
        return instance;
    }

    private AgentInstance createTool(AgentSpec spec) {
        AgentMesh effectiveMesh = this.mesh != null ? this.mesh : DefaultAgentMesh.getDefault();
        ToolAgentInstance instance = new ToolAgentInstance(spec, effectiveMesh);
        log.info("Created Tool Agent: {} (tool={})", spec.getAgentId(), spec.getName());
        return instance;
    }

    private AgentInstance createOrchestrator(AgentSpec spec) {
        // 编排器 Agent：使用 OrchestratorAgentInstance
        // (Phase 2 实现)
        throw new UnsupportedOperationException("Orchestrator agent type not yet implemented");
    }

    private ToolRegistry createScopedToolRegistry(AgentSpec spec) {
        // Phase 3: per-agent tool scoping
        // 暂时返回全局 registry
        return toolRegistry;
    }
}
