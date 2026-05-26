package lyjew.com.lyclaw.action.agent;

import lyjew.com.lyclaw.agent.AgentCollaborationMode;
import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.AgentHandle.HealthStatus;
import lyjew.com.lyclaw.agent.AgentState;
import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.autoconfigure.config.LyClawConfigurationProperties;
import lyjew.com.lyclaw.autoconfigure.config.YamlAgentConfigSource;
import lyjew.com.lyclaw.config.AgentConfigResolver;
import lyjew.com.lyclaw.config.AgentDeclaration;
import lyjew.com.lyclaw.config.ResolvedAgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class AgentScanner implements ApplicationListener<ContextRefreshedEvent> {

    private static final Logger log = LoggerFactory.getLogger(AgentScanner.class);

    private final DefaultAgentRegistry registry;
    private final ApplicationContext applicationContext;
    private final AgentConfigResolver configResolver;
    private final YamlAgentConfigSource yamlConfigSource;
    private final LyClawConfigurationProperties lyClawConfig;

    public AgentScanner(DefaultAgentRegistry registry,
                        ApplicationContext applicationContext,
                        AgentConfigResolver configResolver,
                        YamlAgentConfigSource yamlConfigSource,
                        LyClawConfigurationProperties lyClawConfig) {
        this.registry = registry;
        this.applicationContext = applicationContext;
        this.configResolver = configResolver;
        this.yamlConfigSource = yamlConfigSource;
        this.lyClawConfig = lyClawConfig;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (event.getApplicationContext() != applicationContext) return;

        scanAnnotatedAgents();
        scanYamlAgents();

        log.info("AgentScanner completed: {} agents registered", registry.getAgentCount());
    }

    private void scanAnnotatedAgents() {
        Map<String, Object> agentBeans = applicationContext.getBeansWithAnnotation(Agent.class);

        for (Map.Entry<String, Object> entry : agentBeans.entrySet()) {
            Object bean = entry.getValue();
            Class<?> beanClass = bean.getClass();

            // JDK Proxy 需要获取接口上的 @Agent
            Agent ann = findAgentAnnotation(beanClass);
            if (ann == null && Proxy.isProxyClass(beanClass)) {
                for (Class<?> iface : beanClass.getInterfaces()) {
                    ann = iface.getAnnotation(Agent.class);
                    if (ann != null) break;
                }
            }
            if (ann == null) continue;

            ResolvedAgentConfig config = configResolver.resolve(ann);
            String agentId = config.getAgentId();

            if (registry.lookup(agentId).isPresent()) {
                log.debug("Agent already registered, skipping: {}", agentId);
                continue;
            }

            AgentHandle handle = buildHandle(agentId, config, extractCapabilities(config));
            registry.register(handle);
            log.info("Registered @Agent: {} ({})", agentId, ann.name());
        }
    }

    private void scanYamlAgents() {
        for (String agentName : yamlConfigSource.getAgentNames()) {
            AgentDeclaration decl = yamlConfigSource.getDeclaration(agentName);
            if (decl == null) continue;

            String agentId = decl.getId() != null && !decl.getId().isEmpty() ? decl.getId() : agentName;
            if (registry.lookup(agentId).isPresent()) {
                log.debug("YAML agent already registered, skipping: {}", agentId);
                continue;
            }

            List<String> capabilities = extractYamlCapabilities(decl);
            Map<String, String> extMap = decl.getExtensions() != null ? decl.getExtensions() : Collections.emptyMap();

            AgentHandle handle = AgentHandle.builder()
                    .agentId(agentId)
                    .name(decl.getName() != null && !decl.getName().isEmpty() ? decl.getName() : agentName)
                    .description(decl.getDescription() != null ? decl.getDescription() : "")
                    .state(AgentState.IDLE)
                    .health(HealthStatus.UP)
                    .capabilities(capabilities)
                    .model(decl.getModel() != null && !decl.getModel().isEmpty() ? decl.getModel() : "deepseek-chat")
                    .provider(decl.getProvider() != null && !decl.getProvider().isEmpty() ? decl.getProvider() : "deepseek")
                    .systemPrompt(decl.getSystemPromptOverride() != null ? decl.getSystemPromptOverride() : "")
                    .collaborationMode(parseCollaborationMode(decl.getDelegationMode()))
                    .allowAgents(decl.getAllowAgents() != null ? decl.getAllowAgents() : Collections.emptyList())
                    .maxSpawnDepth(decl.getMaxSpawnDepth())
                    .maxChildrenPerAgent(decl.getMaxChildrenPerAgent())
                    .extensions(extMap)
                    .activeSubagentCount(0)
                    .totalTasksCompleted(0)
                    .totalTasksFailed(0)
                    .historicalAccuracy(1.0)
                    .createdAt(LocalDateTime.now())
                    .lastActiveAt(LocalDateTime.now())
                    .build();

            registry.register(handle);
            log.info("Registered YAML agent: {} ({})", agentId, decl.getName());
        }
    }

    private List<String> extractCapabilities(ResolvedAgentConfig config) {
        List<String> caps = new ArrayList<>();
        Map<String, String> exts = config.getExtensions();
        if (exts != null) {
            String capsStr = exts.get("capabilities");
            if (capsStr != null && !capsStr.isEmpty()) {
                caps.addAll(Arrays.asList(capsStr.split("\\s*,\\s*")));
            }
            String cap = exts.get("capability");
            if (cap != null && !cap.isEmpty()) {
                caps.add(cap);
            }
        }
        return caps;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractYamlCapabilities(AgentDeclaration decl) {
        List<String> caps = new ArrayList<>();
        Map<String, String> exts = decl.getExtensions();
        if (exts != null) {
            String capsStr = exts.get("capabilities");
            if (capsStr != null && !capsStr.isEmpty()) {
                caps.addAll(Arrays.asList(capsStr.split("\\s*,\\s*")));
            }
            String cap = exts.get("capability");
            if (cap != null && !cap.isEmpty()) {
                caps.add(cap);
            }
        }
        return caps;
    }

    private AgentHandle buildHandle(String agentId, ResolvedAgentConfig config, List<String> capabilities) {
        return AgentHandle.builder()
                .agentId(agentId)
                .name(config.getAgentName() != null ? config.getAgentName() : agentId)
                .description(config.getDescription() != null ? config.getDescription() : "")
                .state(AgentState.IDLE)
                .health(HealthStatus.UP)
                .capabilities(capabilities)
                .model(config.getModel() != null ? config.getModel() : "deepseek-chat")
                .provider(config.getProvider() != null ? config.getProvider() : "deepseek")
                .systemPrompt(config.getSystemPromptOverride() != null ? config.getSystemPromptOverride() : "")
                .collaborationMode(parseCollaborationMode(config.getDelegationMode()))
                .allowAgents(config.getAllowAgents() != null ? config.getAllowAgents() : Collections.emptyList())
                .maxSpawnDepth(config.getMaxSpawnDepth())
                .maxChildrenPerAgent(config.getMaxChildrenPerAgent())
                .extensions(config.getExtensions() != null ? config.getExtensions() : Collections.emptyMap())
                .activeSubagentCount(0)
                .totalTasksCompleted(0)
                .totalTasksFailed(0)
                .historicalAccuracy(1.0)
                .createdAt(LocalDateTime.now())
                .lastActiveAt(LocalDateTime.now())
                .build();
    }

    static AgentCollaborationMode parseCollaborationMode(String delegationMode) {
        if (delegationMode == null) return AgentCollaborationMode.WORKER;
        return switch (delegationMode) {
            case "none" -> AgentCollaborationMode.NONE;
            case "auto" -> AgentCollaborationMode.SUPERVISOR;
            case "orchestrator" -> AgentCollaborationMode.ORCHESTRATOR;
            case "consensus" -> AgentCollaborationMode.CONSENSUS;
            default -> AgentCollaborationMode.WORKER;
        };
    }

    private Agent findAgentAnnotation(Class<?> clazz) {
        Agent ann = clazz.getAnnotation(Agent.class);
        if (ann != null) return ann;
        for (Class<?> iface : clazz.getInterfaces()) {
            ann = iface.getAnnotation(Agent.class);
            if (ann != null) return ann;
        }
        return null;
    }
}
