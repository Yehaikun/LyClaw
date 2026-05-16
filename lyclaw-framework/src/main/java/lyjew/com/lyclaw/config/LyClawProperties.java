package lyjew.com.lyclaw.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LyClaw 全局配置属性根节点，对应 application.yml 中 lyclaw.* 前缀。
 *
 * <p>采用嵌套静态类结构组织 LLM、Pipeline、Tools、Sandbox、Agent 五大配置域，
 * 支持 Spring Boot {@code @ConfigurationProperties} 自动绑定。</p>
 *
 * <p>每个嵌套类提供合理的默认值，未显式配置时回退到默认行为。</p>
 */
public class LyClawProperties {

    /** LLM 提供商配置 */
    private LlmProperties llm = new LlmProperties();
    /** 管道配置 */
    private PipelineProperties pipeline = new PipelineProperties();
    /** 工具配置 */
    private ToolsProperties tools = new ToolsProperties();
    /** 沙箱配置 */
    private SandboxProperties sandbox = new SandboxProperties();
    /** Agent 配置 */
    private AgentProperties agent = new AgentProperties();

    /** @return LLM 大模型配置 */
    public LlmProperties getLlm() { return llm; }
    public void setLlm(LlmProperties llm) { this.llm = llm; }

    /** @return 编排流水线配置 */
    public PipelineProperties getPipeline() { return pipeline; }
    public void setPipeline(PipelineProperties pipeline) { this.pipeline = pipeline; }

    /** @return 工具调用配置 */
    public ToolsProperties getTools() { return tools; }
    public void setTools(ToolsProperties tools) { this.tools = tools; }

    /** @return 沙箱安全配置 */
    public SandboxProperties getSandbox() { return sandbox; }
    public void setSandbox(SandboxProperties sandbox) { this.sandbox = sandbox; }

    /** @return 智能体配置 */
    public AgentProperties getAgent() { return agent; }
    public void setAgent(AgentProperties agent) { this.agent = agent; }

    /**
     * LLM 大语言模型配置域，控制模型提供商、鉴权、调用参数和重试策略。
     */
    public static class LlmProperties {
        /** 提供商标识，默认 deepseek-openai（兼容 OpenAI 协议） */
        private String provider = "deepseek-openai";
        /** API 密钥 */
        private String apiKey;
        /** API 基础 URL，为空时使用适配器默认值 */
        private String baseUrl;
        /** 模型名称，默认 deepseek-chat */
        private String model = "deepseek-chat";
        /** 温度参数 [0.0, 2.0]，控制输出随机性 */
        private double temperature = 0.7;
        /** 最大输出 token 数 */
        private int maxTokens = 4096;
        /** 请求超时毫秒数 */
        private long timeout = 60000;
        /** 失败重试次数上限 */
        private int maxRetries = 3;
        /** 是否使用旧版配置格式 */
        private boolean useLegacyConfig;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public long getTimeout() {
            return timeout;
        }

        public void setTimeout(long timeout) {
            this.timeout = timeout;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public boolean isUseLegacyConfig() {
            return useLegacyConfig;
        }

        public void setUseLegacyConfig(boolean useLegacyConfig) {
            this.useLegacyConfig = useLegacyConfig;
        }
    }

    /**
     * 管道配置域，定义处理阶段顺序、超时和开关。
     */
    public static class PipelineProperties {
        /** 处理阶段执行顺序列表 */
        private List<String> stagesOrder = new ArrayList<>();
        /** 管道整体超时毫秒数 */
        private long timeout = 300000;
        /** 管道启用开关 */
        private boolean enabled = true;

        public List<String> getStagesOrder() {
            return stagesOrder;
        }

        public void setStagesOrder(List<String> stagesOrder) {
            this.stagesOrder = stagesOrder;
        }

        public long getTimeout() {
            return timeout;
        }

        public void setTimeout(long timeout) {
            this.timeout = timeout;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * 工具配置域，控制工具调用全局开关、默认超时及逐工具粒度配置。
     */
    public static class ToolsProperties {
        /** 工具调用全局启用开关 */
        private boolean enabled = true;
        /** 工具调用默认超时毫秒数 */
        private long defaultTimeout = 30000;
        /** 逐工具个性化配置，key 为工具名 */
        private Map<String, ToolConfig> tools = new HashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getDefaultTimeout() {
            return defaultTimeout;
        }

        public void setDefaultTimeout(long defaultTimeout) {
            this.defaultTimeout = defaultTimeout;
        }

        public Map<String, ToolConfig> getTools() {
            return tools;
        }

        public void setTools(Map<String, ToolConfig> tools) {
            this.tools = tools;
        }
    }

    /**
     * 单工具配置项，控制单个工具的启用状态和超时。
     */
    public static class ToolConfig {
        /** 工具是否启用 */
        private boolean enabled = true;
        /** 工具超时毫秒数，0 表示使用全局默认值 */
        private long timeout;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getTimeout() {
            return timeout;
        }

        public void setTimeout(long timeout) {
            this.timeout = timeout;
        }
    }

    /**
     * 沙箱配置域，控制工具执行的安全隔离级别和只读工具白名单。
     */
    public static class SandboxProperties {
        /** 沙箱执行模式：DIRECT/SANDBOX/PROCESS */
        private String level = "SANDBOX";
        /** 保留字段，未来可复用 */
        private List<String> readonlyTools = new ArrayList<>();

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }

        public List<String> getReadonlyTools() {
            return readonlyTools;
        }

        public void setReadonlyTools(List<String> readonlyTools) {
            this.readonlyTools = readonlyTools;
        }
    }

    /**
     * Agent 配置域，控制 Agent 类型和最大推理轮数。
     */
    public static class AgentProperties {
        /** 激活的 Agent 类型，默认 react */
        private String active = "react";
        /** 最大推理轮数，防止死循环 */
        private int maxRounds = 10;

        public String getActive() {
            return active;
        }

        public void setActive(String active) {
            this.active = active;
        }

        public int getMaxRounds() {
            return maxRounds;
        }

        public void setMaxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
        }
    }
}
