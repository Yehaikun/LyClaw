package lyjew.com.lyclaw.config;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.annotation.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 配置解析器 —— 3层深度合并。
 * 优先级: @Agent 注解 > AgentDefaultsConfig > AgentSystemDefaults。
 */
public class AgentConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigResolver.class);

    private final AgentDefaultsConfig defaults;

    /** Registry of @Agent annotations keyed by agent id and agent name. */
    private final ConcurrentHashMap<String, Agent> agentAnnotationRegistry = new ConcurrentHashMap<>();

    public AgentConfigResolver(AgentDefaultsConfig defaults) {
        this.defaults = defaults;
    }

    /**
     * Register an @Agent annotation so it can be looked up by agent ID or name later.
     * Called by AgentProxyFactory when creating a proxy for an @Agent interface.
     */
    public void registerAgent(Agent ann) {
        if (ann == null) return;
        if (!ann.id().isEmpty()) {
            agentAnnotationRegistry.put(ann.id(), ann);
        }
        if (!ann.name().isEmpty()) {
            agentAnnotationRegistry.putIfAbsent(ann.name(), ann);
        }
    }

    /**
     * Resolve config for an agent by its ID or name string.
     * Looks up the registered @Agent annotation and delegates to {@link #resolve(Agent)}.
     * Returns a sensible default if the agent is not found in the registry.
     *
     * @param agentId the agent ID or name to look up
     * @return resolved config, never null
     */
    public ResolvedAgentConfig resolveByAgentId(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return ResolvedAgentConfig.builder().build();
        }
        Agent ann = agentAnnotationRegistry.get(agentId.trim());
        if (ann != null) {
            return resolve(ann);
        }
        // Agent not found: build a minimal config with the agentId as name
        log.debug("Agent '{}' not found in registry, using default config", agentId);
        return ResolvedAgentConfig.builder()
                .agentId(agentId.trim())
                .agentName(agentId.trim())
                .description("You are a specialized subagent: " + agentId.trim())
                .build();
    }

    /**
     * 从 @Agent 注解解析配置，合并默认值。
     */
    public ResolvedAgentConfig resolve(Agent ann) {
        if (ann == null) {
            return ResolvedAgentConfig.builder().build();
        }

        String agentId = !ann.id().isEmpty() ? ann.id() : decapitalize(ann.name());
        String agentName = !ann.name().isEmpty() ? ann.name() : agentId;

        return ResolvedAgentConfig.builder()
                .agentId(agentId)
                .agentName(agentName)
                .description(!ann.description().isEmpty() ? ann.description() : "")
                .version(!ann.version().isEmpty() ? ann.version() : "1.0.0")
                .defaultAgent(ann.defaultAgent())
                .workspaceDir(firstNonEmpty(ann.workspace(), defaults.getWorkspace(), ""))
                .agentDir(firstNonEmpty(ann.agentDir(), agentId))
                .systemPromptOverride(firstNonEmpty(ann.systemPromptOverride(), null))
                .model(firstNonEmpty(ann.model(), defaults.getModel(), AgentSystemDefaults.MODEL))
                .provider(firstNonEmpty(ann.provider(), defaults.getProvider(), AgentSystemDefaults.PROVIDER))
                .fallbacks(mergeList(ann.fallbacks(), defaults.getFallbacks()))
                .thinkingDefault(firstNonEmpty(ann.thinkingDefault(), defaults.getThinkingDefault(), AgentSystemDefaults.THINKING_DEFAULT))
                .verboseDefault(firstNonEmpty(ann.verboseDefault(), defaults.getVerboseDefault(), AgentSystemDefaults.VERBOSE_DEFAULT))
                .reasoningDefault(firstNonEmpty(ann.reasoningDefault(), defaults.getReasoningDefault(), AgentSystemDefaults.REASONING_DEFAULT))
                .fastModeDefault(ann.fastModeDefault() || defaults.isFastModeDefault())
                .contextTokens(firstNonZero(ann.contextTokens(), defaults.getContextTokens(), AgentSystemDefaults.CONTEXT_TOKENS))
                .contextInjection(firstNonEmpty(ann.contextInjection(), defaults.getContextInjection(), AgentSystemDefaults.CONTEXT_INJECTION))
                .bootstrapMaxChars(firstNonZero(ann.bootstrapMaxChars(), defaults.getBootstrapMaxChars(), AgentSystemDefaults.BOOTSTRAP_MAX_CHARS))
                .bootstrapTotalMaxChars(firstNonZero(ann.bootstrapTotalMaxChars(), defaults.getBootstrapTotalMaxChars(), AgentSystemDefaults.BOOTSTRAP_TOTAL_MAX_CHARS))
                .skills(mergeList(ann.skills(), defaults.getSkills()))
                .delegationMode(firstNonEmpty(ann.delegationMode(), defaults.getDelegationMode(), AgentSystemDefaults.DELEGATION_MODE))
                .allowAgents(mergeList(ann.allowAgents(), defaults.getAllowAgents()))
                .maxSpawnDepth(firstNonZero(ann.maxSpawnDepth(), defaults.getMaxSpawnDepth(), AgentSystemDefaults.MAX_SPAWN_DEPTH))
                .maxChildrenPerAgent(firstNonZero(ann.maxChildrenPerAgent(), defaults.getMaxChildrenPerAgent(), AgentSystemDefaults.MAX_CHILDREN))
                .sandbox(firstNonEmpty(ann.sandbox(), defaults.getSandbox(), AgentSystemDefaults.SANDBOX))
                .extensions(toExtensionMap(ann.extensions()))
                .build();
    }

    /**
     * 列出所有已注册的 Agent ID。当前从 Spring 上下文扫描 @Agent 注解。
     */
    public List<String> listAgentIds() {
        // Agent IDs are discovered at bean registration time by AgentInterfaceProcessor
        return Collections.emptyList(); // populated externally
    }

    // ── 辅助方法 ──────────────────────────────────────────────

    @SafeVarargs
    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    private static int firstNonZero(int... values) {
        for (int v : values) {
            if (v != 0) return v;
        }
        return 0;
    }

    private static List<String> mergeList(String[] annotationValues, List<String> defaultsList) {
        if (annotationValues.length > 0) {
            return Collections.unmodifiableList(Arrays.asList(annotationValues));
        }
        if (defaultsList != null && !defaultsList.isEmpty()) {
            return Collections.unmodifiableList(new ArrayList<>(defaultsList));
        }
        return Collections.emptyList();
    }

    private static Map<String, String> toExtensionMap(Extension[] extensions) {
        if (extensions == null || extensions.length == 0) return Collections.emptyMap();
        Map<String, String> map = new LinkedHashMap<>();
        for (Extension ext : extensions) {
            if (ext.key() != null && !ext.key().isEmpty()) {
                map.put(ext.key(), ext.value());
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.length() == 1) return s.toLowerCase();
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
