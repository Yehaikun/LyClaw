package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.mesh.AgentFactory;
import lyjew.com.lyclaw.mesh.AgentMesh;
import lyjew.com.lyclaw.mesh.OrchestrationEngine;
import lyjew.com.lyclaw.mesh.impl.DefaultAgentFactory;
import lyjew.com.lyclaw.mesh.impl.DefaultAgentMesh;
import lyjew.com.lyclaw.mesh.impl.DefaultOrchestrationEngine;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.tool.ToolRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Agent Mesh 自动配置 —— 注册 AgentMesh + OrchestrationEngine + AgentFactory。
 *
 * <p>用户可以通过提供自定义的 {@link AgentMesh}、{@link OrchestrationEngine}、
 * 或 {@link AgentFactory} Bean 来覆盖默认实现。</p>
 *
 * <p>AgentFactory 使用 @Lazy 注入到 AgentMesh，避免循环依赖。</p>
 */
@AutoConfiguration
@ConditionalOnClass(AgentMesh.class)
public class MeshAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MeshAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public AgentMesh agentMesh() {
        DefaultAgentMesh mesh = new DefaultAgentMesh();
        log.info("AgentMesh initialized");
        return mesh;
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentFactory agentFactory(ChatFacade chatFacade,
                                      ReActEngine reActEngine,
                                      ToolRegistry toolRegistry) {
        DefaultAgentFactory factory = new DefaultAgentFactory();
        factory.setChatFacade(chatFacade);
        factory.setReActEngine(reActEngine);
        factory.setToolRegistry(toolRegistry);
        log.info("AgentFactory initialized");
        return factory;
    }

    @Bean
    @ConditionalOnMissingBean
    public OrchestrationEngine orchestrationEngine(AgentMesh mesh) {
        DefaultOrchestrationEngine engine = new DefaultOrchestrationEngine(mesh);
        log.info("OrchestrationEngine initialized with {} agents", mesh.getAllAgents().size());
        return engine;
    }
}
