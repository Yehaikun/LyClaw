package lyjew.com.lyclaw.framework.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LyClawProperties {

    private LlmProperties llm = new LlmProperties();
    private PipelineProperties pipeline = new PipelineProperties();
    private ToolsProperties tools = new ToolsProperties();
    private SandboxProperties sandbox = new SandboxProperties();
    private AgentProperties agent = new AgentProperties();

    public LlmProperties getLlm() {
        return llm;
    }

    public void setLlm(LlmProperties llm) {
        this.llm = llm;
    }

    public PipelineProperties getPipeline() {
        return pipeline;
    }

    public void setPipeline(PipelineProperties pipeline) {
        this.pipeline = pipeline;
    }

    public ToolsProperties getTools() {
        return tools;
    }

    public void setTools(ToolsProperties tools) {
        this.tools = tools;
    }

    public SandboxProperties getSandbox() {
        return sandbox;
    }

    public void setSandbox(SandboxProperties sandbox) {
        this.sandbox = sandbox;
    }

    public AgentProperties getAgent() {
        return agent;
    }

    public void setAgent(AgentProperties agent) {
        this.agent = agent;
    }

    public static class LlmProperties {
        private String provider = "deepseek-openai";
        private String apiKey;
        private String baseUrl;
        private String model = "deepseek-chat";
        private double temperature = 0.7;
        private int maxTokens = 4096;
        private long timeout = 60000;
        private int maxRetries = 3;
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

    public static class PipelineProperties {
        private List<String> stagesOrder = new ArrayList<>();
        private long timeout = 300000;
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

    public static class ToolsProperties {
        private boolean enabled = true;
        private long defaultTimeout = 30000;
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

    public static class ToolConfig {
        private boolean enabled = true;
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

    public static class SandboxProperties {
        private String level = "READ_ONLY";
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

    public static class AgentProperties {
        private String active = "react";
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
