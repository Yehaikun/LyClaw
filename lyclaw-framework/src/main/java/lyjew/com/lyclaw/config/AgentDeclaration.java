package lyjew.com.lyclaw.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可配置的 Agent 声明 —— 对应 application.yml 中 {@code lyclaw.agents.<agentName>} 的配置项。
 *
 * <p>字段与 {@link lyjew.com.lyclaw.annotation.Agent @Agent} 注解一一对应，
 * 提供 YAML 声明式 Agent 定义的能力。优先级低于 @Agent 注解，高于全局默认值。
 *
 * <p>使用示例：
 * <pre>{@code
 * lyclaw:
 *   agents:
 *     researcher:
 *       name: "研究助手"
 *       description: "擅长搜索和分析文档"
 *       model: deepseek-v4-flash
 *       provider: deepseek
 *       skills:
 *         - web_search
 *       sandbox: none
 * }</pre>
 */
public class AgentDeclaration {

    // ── 身份标识 ──
    private String name = "";
    private String id = "";
    private String description = "";
    private String version = "1.0.0";
    private boolean defaultAgent;

    // ── 工作区 ──
    private String workspace = "";
    private String agentDir = "";

    // ── 系统提示词 ──
    private String systemPromptOverride = "";

    // ── 模型 ──
    private String model = "";
    private String provider = "";
    private List<String> fallbacks = new ArrayList<>();

    // ── 思考 / 详细度 / 推理 ──
    private String thinkingDefault = "";
    private String verboseDefault = "";
    private String reasoningDefault = "";
    private boolean fastModeDefault;

    // ── 上下文 ──
    private int contextTokens;
    private String contextInjection = "always";
    private int bootstrapMaxChars = 20000;
    private int bootstrapTotalMaxChars = 150000;

    // ── 技能 ──
    private List<String> skills = new ArrayList<>();

    // ── 委托 ──
    private String delegationMode = "suggest";
    private List<String> allowAgents = new ArrayList<>();
    private int maxSpawnDepth = 1;
    private int maxChildrenPerAgent = 5;

    // ── 沙箱 ──
    private String sandbox = "none";

    // ── 扩展 ──
    private Map<String, String> extensions = new LinkedHashMap<>();

    // ===== Getters / Setters =====

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public boolean isDefaultAgent() { return defaultAgent; }
    public void setDefaultAgent(boolean defaultAgent) { this.defaultAgent = defaultAgent; }

    public String getWorkspace() { return workspace; }
    public void setWorkspace(String workspace) { this.workspace = workspace; }

    public String getAgentDir() { return agentDir; }
    public void setAgentDir(String agentDir) { this.agentDir = agentDir; }

    public String getSystemPromptOverride() { return systemPromptOverride; }
    public void setSystemPromptOverride(String systemPromptOverride) { this.systemPromptOverride = systemPromptOverride; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public List<String> getFallbacks() { return fallbacks; }
    public void setFallbacks(List<String> fallbacks) { this.fallbacks = fallbacks; }

    public String getThinkingDefault() { return thinkingDefault; }
    public void setThinkingDefault(String thinkingDefault) { this.thinkingDefault = thinkingDefault; }

    public String getVerboseDefault() { return verboseDefault; }
    public void setVerboseDefault(String verboseDefault) { this.verboseDefault = verboseDefault; }

    public String getReasoningDefault() { return reasoningDefault; }
    public void setReasoningDefault(String reasoningDefault) { this.reasoningDefault = reasoningDefault; }

    public boolean isFastModeDefault() { return fastModeDefault; }
    public void setFastModeDefault(boolean fastModeDefault) { this.fastModeDefault = fastModeDefault; }

    public int getContextTokens() { return contextTokens; }
    public void setContextTokens(int contextTokens) { this.contextTokens = contextTokens; }

    public String getContextInjection() { return contextInjection; }
    public void setContextInjection(String contextInjection) { this.contextInjection = contextInjection; }

    public int getBootstrapMaxChars() { return bootstrapMaxChars; }
    public void setBootstrapMaxChars(int bootstrapMaxChars) { this.bootstrapMaxChars = bootstrapMaxChars; }

    public int getBootstrapTotalMaxChars() { return bootstrapTotalMaxChars; }
    public void setBootstrapTotalMaxChars(int bootstrapTotalMaxChars) { this.bootstrapTotalMaxChars = bootstrapTotalMaxChars; }

    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills; }

    public String getDelegationMode() { return delegationMode; }
    public void setDelegationMode(String delegationMode) { this.delegationMode = delegationMode; }

    public List<String> getAllowAgents() { return allowAgents; }
    public void setAllowAgents(List<String> allowAgents) { this.allowAgents = allowAgents; }

    public int getMaxSpawnDepth() { return maxSpawnDepth; }
    public void setMaxSpawnDepth(int maxSpawnDepth) { this.maxSpawnDepth = maxSpawnDepth; }

    public int getMaxChildrenPerAgent() { return maxChildrenPerAgent; }
    public void setMaxChildrenPerAgent(int maxChildrenPerAgent) { this.maxChildrenPerAgent = maxChildrenPerAgent; }

    public String getSandbox() { return sandbox; }
    public void setSandbox(String sandbox) { this.sandbox = sandbox; }

    public Map<String, String> getExtensions() { return extensions; }
    public void setExtensions(Map<String, String> extensions) { this.extensions = extensions; }
}
