package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import java.util.List;
import java.util.Map;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.mesh.AgentFactory;
import lyjew.com.lyclaw.mesh.AgentMesh;
import lyjew.com.lyclaw.mesh.AgentRef;
import lyjew.com.lyclaw.mesh.AgentSpec;
import lyjew.com.lyclaw.mesh.impl.DefaultAgentFactory;
import lyjew.com.lyclaw.mesh.impl.DefaultAgentMesh;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.session.SessionService;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.ContextRefreshedEvent;

/**
 * Agent Mesh 自动配置。
 *
 * <p>DefaultAgentMesh 通过静态 getDefault() 对外开放，
 * DefaultAgentFactory 在无 Spring 注入时回退到静态默认实例，
 * 彻底消除循环依赖。</p>
 */
@AutoConfiguration
@ConditionalOnClass(AgentMesh.class)
public class MeshAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MeshAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(AgentMesh.class)
    public DefaultAgentMesh agentMesh() {
        DefaultAgentMesh mesh = new DefaultAgentMesh();
        log.info("AgentMesh initialized");
        return mesh;
    }

    @Bean
    @ConditionalOnMissingBean(AgentFactory.class)
    @DependsOn("agentMesh")
    public DefaultAgentFactory agentFactory(ChatFacade chatFacade,
                                             ReActEngine reActEngine,
                                             ToolRegistry toolRegistry,
                                             SessionService sessionService) {
        DefaultAgentFactory factory = new DefaultAgentFactory();
        factory.setChatFacade(chatFacade);
        factory.setReActEngine(reActEngine);
        factory.setToolRegistry(toolRegistry);
        factory.setSessionService(sessionService);

        // @DependsOn("agentMesh") 确保此时 mesh 已创建
        DefaultAgentMesh defaultMesh = DefaultAgentMesh.getDefault();
        if (defaultMesh != null) {
            defaultMesh.configureAgentFactory(factory);
            defaultMesh.setSessionService(sessionService);
            log.info("✓ AgentFactory wired into AgentMesh");
        } else {
            log.warn("✗ DefaultAgentMesh.getDefault() returned null");
        }
        log.info("AgentFactory initialized (chatFacade={}, tools={})",
                chatFacade != null ? "✓" : "✗",
                toolRegistry != null ? toolRegistry.getAllDefinitions().size() : 0);
        return factory;
    }

    /**
     * 启动时自动注册 @Agent 和 Tool 到 AgentMesh。
     * 用户无需手动 curl 注册 Agent。
     */
    @Bean
    public ApplicationListener<ContextRefreshedEvent> autoAgentRegistrar(
            AgentMesh mesh, ToolRegistry toolRegistry,
            org.springframework.context.ApplicationContext appCtx) {
        return event -> {
            if (!(mesh instanceof DefaultAgentMesh)) return;

            // 1. 注册 @Agent 接口
            Map<String, Object> agentBeans = appCtx.getBeansWithAnnotation(Agent.class);
            for (Map.Entry<String, Object> entry : agentBeans.entrySet()) {
                Object bean = entry.getValue();
                Class<?> beanClass = bean.getClass();
                Agent ann = beanClass.getAnnotation(Agent.class);
                if (ann == null && java.lang.reflect.Proxy.isProxyClass(beanClass)) {
                    for (Class<?> iface : beanClass.getInterfaces()) {
                        ann = iface.getAnnotation(Agent.class);
                        if (ann != null) break;
                    }
                }
                if (ann == null) continue;

                String agentId = ann.id().isEmpty()
                        ? Character.toLowerCase(beanClass.getSimpleName().charAt(0))
                          + beanClass.getSimpleName().substring(1)
                        : ann.id();

                if (mesh.lookup(agentId).isPresent()) continue;

                AgentSpec spec = AgentSpec.builder()
                        .agentId(agentId)
                        .name(ann.name().isEmpty() ? agentId : ann.name())
                        .description(ann.description())
                        .model(ann.model().isEmpty() ? null : ann.model())
                        .systemPrompt(ann.systemPromptOverride().isEmpty() ? null : ann.systemPromptOverride())
                        .type(AgentRef.AgentType.PROXY)
                        .build();
                try {
                    mesh.register(spec);
                    log.info("Auto-registered @Agent: {} (interface={})", agentId, beanClass.getSimpleName());
                } catch (Exception e) {
                    log.warn("Failed to auto-register @Agent {}: {}", agentId, e.getMessage());
                }
            }

            // 2. 注册 Tool 为 ToolAgent
            if (toolRegistry != null) {
                try {
                    java.lang.reflect.Method getToolNames = toolRegistry.getClass().getMethod("getToolNames");
                    @SuppressWarnings("unchecked")
                    java.util.Set<String> toolNames = (java.util.Set<String>) getToolNames.invoke(toolRegistry);
                    for (String toolName : toolNames) {
                        if (mesh.lookup(toolName).isPresent()) continue;
                        AgentSpec toolSpec = AgentSpec.builder()
                                .agentId(toolName)
                                .name(toolName)
                                .type(AgentRef.AgentType.TOOL)
                                .build();
                        try {
                            mesh.register(toolSpec);
                            log.info("Auto-registered ToolAgent: {}", toolName);
                        } catch (Exception e) {
                            log.debug("Skipping tool {}: {}", toolName, e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.debug("Could not auto-register tools: {}", e.getMessage());
                }
            }

            log.info("Agent auto-registration complete. {} agents in mesh.", mesh.getAllAgents().size());
        };
    }
}
