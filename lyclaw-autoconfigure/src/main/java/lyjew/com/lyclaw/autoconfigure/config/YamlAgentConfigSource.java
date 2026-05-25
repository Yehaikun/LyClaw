package lyjew.com.lyclaw.autoconfigure.config;

import lyjew.com.lyclaw.config.AgentConfigSource;
import lyjew.com.lyclaw.config.AgentDeclaration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从 application.yml 读取声明式 Agent 配置的配置源，优先级 10（低于 @Agent 注解）。
 *
 * <p>读取 {@code lyclaw.agents.<agentName>.*} 下的配置，支持完整 Agent 声明字段。
 *
 * <p>优先级: @Agent 注解 (50) → application.yml (10) → 全局默认值 → 系统默认值
 */
public class YamlAgentConfigSource implements AgentConfigSource {

    private final Map<String, AgentDeclaration> agentDeclarations;

    public YamlAgentConfigSource(Map<String, AgentDeclaration> agentDeclarations) {
        this.agentDeclarations = agentDeclarations != null
                ? Collections.unmodifiableMap(agentDeclarations)
                : Collections.emptyMap();
    }

    /**
     * 读取 YAML 中声明的所有 Agent 名称集合。
     */
    public Set<String> getAgentNames() {
        return agentDeclarations.keySet();
    }

    /**
     * 获取指定 agent 的原始声明对象。
     */
    public AgentDeclaration getDeclaration(String agentName) {
        return agentDeclarations.get(agentName);
    }

    @Override
    public Map<String, String> loadConfig(String agentName) {
        AgentDeclaration decl = agentDeclarations.get(agentName);
        if (decl == null) return Collections.emptyMap();

        Map<String, String> config = new LinkedHashMap<>();

        putIfNotEmpty(config, "name", decl.getName());
        putIfNotEmpty(config, "description", decl.getDescription());
        putIfNotEmpty(config, "version", decl.getVersion());
        putIfNotEmpty(config, "model", decl.getModel());
        putIfNotEmpty(config, "provider", decl.getProvider());
        putIfNotEmpty(config, "workspace", decl.getWorkspace());
        putIfNotEmpty(config, "agent-dir", decl.getAgentDir());
        putIfNotEmpty(config, "system-prompt-override", decl.getSystemPromptOverride());
        putIfNotEmpty(config, "thinking-default", decl.getThinkingDefault());
        putIfNotEmpty(config, "verbose-default", decl.getVerboseDefault());
        putIfNotEmpty(config, "reasoning-default", decl.getReasoningDefault());
        putIfNotEmpty(config, "context-injection", decl.getContextInjection());
        putIfNotEmpty(config, "delegation-mode", decl.getDelegationMode());
        putIfNotEmpty(config, "sandbox", decl.getSandbox());

        if (!decl.getFallbacks().isEmpty())
            config.put("fallbacks", String.join(",", decl.getFallbacks()));
        if (!decl.getSkills().isEmpty())
            config.put("skills", String.join(",", decl.getSkills()));
        if (!decl.getAllowAgents().isEmpty())
            config.put("allow-agents", String.join(",", decl.getAllowAgents()));

        if (decl.isDefaultAgent())
            config.put("default-agent", "true");
        if (decl.isFastModeDefault())
            config.put("fast-mode-default", "true");

        if (decl.getContextTokens() > 0)
            config.put("context-tokens", String.valueOf(decl.getContextTokens()));
        if (decl.getBootstrapMaxChars() != 20000)
            config.put("bootstrap-max-chars", String.valueOf(decl.getBootstrapMaxChars()));
        if (decl.getBootstrapTotalMaxChars() != 150000)
            config.put("bootstrap-total-max-chars", String.valueOf(decl.getBootstrapTotalMaxChars()));
        if (decl.getMaxSpawnDepth() != 1)
            config.put("max-spawn-depth", String.valueOf(decl.getMaxSpawnDepth()));
        if (decl.getMaxChildrenPerAgent() != 5)
            config.put("max-children-per-agent", String.valueOf(decl.getMaxChildrenPerAgent()));

        // 扩展属性
        for (Map.Entry<String, String> e : decl.getExtensions().entrySet()) {
            config.put("ext." + e.getKey(), e.getValue());
        }

        return config;
    }

    /**
     * 列出所有 YAML 声明的 agent 名称。
     */
    @Override
    public List<String> listAgentNames() {
        return new ArrayList<>(agentDeclarations.keySet());
    }

    @Override
    public int getPriority() { return 10; }

    @Override
    public String getSourceName() { return "application.yml"; }

    private static void putIfNotEmpty(Map<String, String> map, String key, String value) {
        if (value != null && !value.isEmpty()) {
            map.put(key, value);
        }
    }
}
