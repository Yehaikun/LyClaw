package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * 全局 Agent 默认值，绑定自 {@code lyclaw.agent.defaults.*}。
 * 解析顺序: Agent 注解 > lyclaw.agent.defaults > AgentSystemDefaults。
 */
@ConfigurationProperties(prefix = "lyclaw.agent.defaults")
public class AgentDefaultsConfig {

    // ── 模型默认值 ─────────────────────────────────────────
    private String model;
    private String provider;
    private List<String> fallbacks = new ArrayList<>();

    // ── 思考 / 详细度 / 推理 ─────────────────────────────
    private String thinkingDefault;
    private String verboseDefault;
    private String reasoningDefault;
    private boolean fastModeDefault;

    // ── 上下文 ────────────────────────────────────────────────
    private String contextInjection = "always";
    private int bootstrapMaxChars = 20000;
    private int bootstrapTotalMaxChars = 150000;
    private int contextTokens;

    // ── 技能 ─────────────────────────────────────────────────
    private List<String> skills = new ArrayList<>();

    // ── 沙箱 ────────────────────────────────────────────────
    private String sandbox = "none";

    // ── 工作区 ──────────────────────────────────────────────
    private String workspace;

    // ── 委托默认值 ───────────────────────────────────────
    private String delegationMode = "suggest";
    private List<String> allowAgents = new ArrayList<>();
    private int maxSpawnDepth = 1;
    private int maxChildrenPerAgent = 5;

    // ── 子Agent模型默认值 ───────────────────────────────
    private String subagentModel;
    private String subagentThinking;

    // ── 子Agent运行时默认值 ────────────────────────────
    private boolean subagentEnabled = true;
    private int subagentMaxConcurrent = 1;
    private int subagentArchiveAfterMinutes = 60;
    private int subagentRunTimeoutSeconds = 300;
    private int subagentAnnounceTimeoutMs = 120_000;
    private boolean subagentRequireAgentId = false;
    private String subagentDelegationMode = "suggest";
    private String subagentAllowAgents = "*";
    private int subagentMaxSpawnDepth = 1;
    private int subagentMaxChildrenPerAgent = 5;

    // ── 运行重试 ────────────────────────────────────────────
    private int maxRetryAttempts = 3;
    private long retryBaseDelayMs = 1000;
    private String retryBackoff = "exponential";

    // ── 心跳检测 ──────────────────────────────────────────
    private boolean heartbeatEnabled;
    private long heartbeatIntervalSeconds = 60;
    private long heartbeatMaxIdleSeconds = 300;

    // ── 上下文限制（工具输出裁剪） ──────────────────────
    private int memoryGetMaxChars = 50000;
    private int toolResultMaxChars = 80000;
    private int toolResultTotalMaxChars = 200000;

    // ── 多模态模型默认值 ──────────────────────────────
    private String imageModel = "";
    private String imageGenerationModel = "";
    private String videoGenerationModel = "";
    private String musicGenerationModel = "";
    private String pdfModel = "";
    private int pdfMaxBytesMb = 10;
    private int pdfMaxPages = 20;
    private boolean mediaGenerationAutoProviderFallback = true;

    // ── 模型降级链 ──────────────────────────────────────
    private List<String> modelFallbackChain = new ArrayList<>();

    // ===== Getters / Setters =====

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public List<String> getFallbacks() { return fallbacks; }
    public void setFallbacks(List<String> fallbacks) { this.fallbacks = fallbacks; }

    public String getThinkingDefault() { return thinkingDefault; }
    public void setThinkingDefault(String t) { this.thinkingDefault = t; }

    public String getVerboseDefault() { return verboseDefault; }
    public void setVerboseDefault(String v) { this.verboseDefault = v; }

    public String getReasoningDefault() { return reasoningDefault; }
    public void setReasoningDefault(String r) { this.reasoningDefault = r; }

    public boolean isFastModeDefault() { return fastModeDefault; }
    public void setFastModeDefault(boolean f) { this.fastModeDefault = f; }

    public String getContextInjection() { return contextInjection; }
    public void setContextInjection(String c) { this.contextInjection = c; }

    public int getBootstrapMaxChars() { return bootstrapMaxChars; }
    public void setBootstrapMaxChars(int n) { this.bootstrapMaxChars = n; }

    public int getBootstrapTotalMaxChars() { return bootstrapTotalMaxChars; }
    public void setBootstrapTotalMaxChars(int n) { this.bootstrapTotalMaxChars = n; }

    public int getContextTokens() { return contextTokens; }
    public void setContextTokens(int n) { this.contextTokens = n; }

    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> s) { this.skills = s; }

    public String getSandbox() { return sandbox; }
    public void setSandbox(String s) { this.sandbox = s; }

    public String getWorkspace() { return workspace; }
    public void setWorkspace(String w) { this.workspace = w; }

    public String getDelegationMode() { return delegationMode; }
    public void setDelegationMode(String d) { this.delegationMode = d; }

    public List<String> getAllowAgents() { return allowAgents; }
    public void setAllowAgents(List<String> a) { this.allowAgents = a; }

    public int getMaxSpawnDepth() { return maxSpawnDepth; }
    public void setMaxSpawnDepth(int d) { this.maxSpawnDepth = d; }

    public int getMaxChildrenPerAgent() { return maxChildrenPerAgent; }
    public void setMaxChildrenPerAgent(int c) { this.maxChildrenPerAgent = c; }

    public String getSubagentModel() { return subagentModel; }
    public void setSubagentModel(String m) { this.subagentModel = m; }

    public String getSubagentThinking() { return subagentThinking; }
    public void setSubagentThinking(String t) { this.subagentThinking = t; }

    public boolean isSubagentEnabled() { return subagentEnabled; }
    public void setSubagentEnabled(boolean e) { this.subagentEnabled = e; }

    public int getSubagentMaxConcurrent() { return subagentMaxConcurrent; }
    public void setSubagentMaxConcurrent(int n) { this.subagentMaxConcurrent = n; }

    public int getSubagentArchiveAfterMinutes() { return subagentArchiveAfterMinutes; }
    public void setSubagentArchiveAfterMinutes(int n) { this.subagentArchiveAfterMinutes = n; }

    public int getSubagentRunTimeoutSeconds() { return subagentRunTimeoutSeconds; }
    public void setSubagentRunTimeoutSeconds(int n) { this.subagentRunTimeoutSeconds = n; }

    public int getSubagentAnnounceTimeoutMs() { return subagentAnnounceTimeoutMs; }
    public void setSubagentAnnounceTimeoutMs(int n) { this.subagentAnnounceTimeoutMs = n; }

    public boolean isSubagentRequireAgentId() { return subagentRequireAgentId; }
    public void setSubagentRequireAgentId(boolean r) { this.subagentRequireAgentId = r; }

    public String getSubagentDelegationMode() { return subagentDelegationMode; }
    public void setSubagentDelegationMode(String m) { this.subagentDelegationMode = m; }

    public String getSubagentAllowAgents() { return subagentAllowAgents; }
    public void setSubagentAllowAgents(String a) { this.subagentAllowAgents = a; }

    public int getSubagentMaxSpawnDepth() { return subagentMaxSpawnDepth; }
    public void setSubagentMaxSpawnDepth(int d) { this.subagentMaxSpawnDepth = d; }

    public int getSubagentMaxChildrenPerAgent() { return subagentMaxChildrenPerAgent; }
    public void setSubagentMaxChildrenPerAgent(int c) { this.subagentMaxChildrenPerAgent = c; }

    public int getMaxRetryAttempts() { return maxRetryAttempts; }
    public void setMaxRetryAttempts(int n) { this.maxRetryAttempts = n; }

    public long getRetryBaseDelayMs() { return retryBaseDelayMs; }
    public void setRetryBaseDelayMs(long d) { this.retryBaseDelayMs = d; }

    public String getRetryBackoff() { return retryBackoff; }
    public void setRetryBackoff(String b) { this.retryBackoff = b; }

    public boolean isHeartbeatEnabled() { return heartbeatEnabled; }
    public void setHeartbeatEnabled(boolean e) { this.heartbeatEnabled = e; }

    public long getHeartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }
    public void setHeartbeatIntervalSeconds(long s) { this.heartbeatIntervalSeconds = s; }

    public long getHeartbeatMaxIdleSeconds() { return heartbeatMaxIdleSeconds; }
    public void setHeartbeatMaxIdleSeconds(long s) { this.heartbeatMaxIdleSeconds = s; }

    public int getMemoryGetMaxChars() { return memoryGetMaxChars; }
    public void setMemoryGetMaxChars(int n) { this.memoryGetMaxChars = n; }

    public int getToolResultMaxChars() { return toolResultMaxChars; }
    public void setToolResultMaxChars(int n) { this.toolResultMaxChars = n; }

    public int getToolResultTotalMaxChars() { return toolResultTotalMaxChars; }
    public void setToolResultTotalMaxChars(int n) { this.toolResultTotalMaxChars = n; }

    public String getImageModel() { return imageModel; }
    public void setImageModel(String m) { this.imageModel = m; }

    public String getImageGenerationModel() { return imageGenerationModel; }
    public void setImageGenerationModel(String m) { this.imageGenerationModel = m; }

    public String getVideoGenerationModel() { return videoGenerationModel; }
    public void setVideoGenerationModel(String m) { this.videoGenerationModel = m; }

    public String getMusicGenerationModel() { return musicGenerationModel; }
    public void setMusicGenerationModel(String m) { this.musicGenerationModel = m; }

    public String getPdfModel() { return pdfModel; }
    public void setPdfModel(String m) { this.pdfModel = m; }

    public int getPdfMaxBytesMb() { return pdfMaxBytesMb; }
    public void setPdfMaxBytesMb(int n) { this.pdfMaxBytesMb = n; }

    public int getPdfMaxPages() { return pdfMaxPages; }
    public void setPdfMaxPages(int n) { this.pdfMaxPages = n; }

    public boolean isMediaGenerationAutoProviderFallback() { return mediaGenerationAutoProviderFallback; }
    public void setMediaGenerationAutoProviderFallback(boolean f) { this.mediaGenerationAutoProviderFallback = f; }

    public List<String> getModelFallbackChain() { return modelFallbackChain; }
    public void setModelFallbackChain(List<String> chain) { this.modelFallbackChain = chain; }
}
