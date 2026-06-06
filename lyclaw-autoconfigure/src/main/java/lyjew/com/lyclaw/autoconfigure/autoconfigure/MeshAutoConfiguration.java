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
import org.springframework.context.annotation.DependsOn;

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
                                             ToolRegistry toolRegistry) {
        DefaultAgentFactory factory = new DefaultAgentFactory();
        factory.setChatFacade(chatFacade);
        factory.setReActEngine(reActEngine);
        factory.setToolRegistry(toolRegistry);
        log.info("AgentFactory initialized (chatFacade={}, tools={})",
                chatFacade != null ? "✓" : "✗",
                toolRegistry != null ? toolRegistry.getAllDefinitions().size() : 0);

        // 将配置好的 Factory 注入到 AgentMesh
        // @DependsOn("agentMesh") 确保此时 mesh 已创建
        DefaultAgentMesh defaultMesh = DefaultAgentMesh.getDefault();
        if (defaultMesh != null) {
            defaultMesh.configureAgentFactory(factory);
            log.info("✓ AgentFactory wired into AgentMesh");
        } else {
            log.warn("✗ DefaultAgentMesh.getDefault() returned null");
        }
        return factory;
    }

    @Bean
    @ConditionalOnMissingBean
    public OrchestrationEngine orchestrationEngine(AgentMesh mesh) {
        DefaultOrchestrationEngine engine = new DefaultOrchestrationEngine(mesh);
        log.info("OrchestrationEngine initialized");
        return engine;
    }
}
