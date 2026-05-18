package lyjew.com.lyclaw.autoconfigure.config;

import lyjew.com.lyclaw.chat.ChatProperties;
import lyjew.com.lyclaw.config.AgentConfigSource;

import java.util.HashMap;
import java.util.Map;

/**
 * 从 application.yml 读取代理配置的配置源，优先级 10（最低）。
 *
 * <p>读取 {@code lyclaw.chat.models.*} 下的模型配置和 {@code lyclaw.agents.*} 下的 agent 配置。
 */
public class YamlAgentConfigSource implements AgentConfigSource {

    private final ChatProperties chatProperties;

    public YamlAgentConfigSource(ChatProperties chatProperties) {
        this.chatProperties = chatProperties;
    }

    @Override
    public Map<String, String> loadConfig(String agentName) {
        Map<String, String> config = new HashMap<>();

        // 从 lyclaw.chat.models.<agentName> 读取模型配置
        if (chatProperties != null && chatProperties.getModels() != null) {
            ChatProperties.ModelProperties modelProps = chatProperties.getModels().get(agentName);
            if (modelProps != null) {
                if (modelProps.getProvider() != null) config.put("provider", modelProps.getProvider());
                if (modelProps.getModel() != null) config.put("model", modelProps.getModel());
                if (modelProps.getBaseUrl() != null) config.put("base-url", modelProps.getBaseUrl());
                if (modelProps.getApiKey() != null) config.put("api-key", modelProps.getApiKey());
            }
        }

        // 从 lyclaw.chat 全局默认读取
        if (chatProperties != null) {
            if (chatProperties.getDefaultProvider() != null
                    && !config.containsKey("provider"))
                config.put("provider", chatProperties.getDefaultProvider());
            if (chatProperties.getDefaultModel() != null
                    && !config.containsKey("model"))
                config.put("model", chatProperties.getDefaultModel());
        }

        return config;
    }

    @Override public int getPriority() { return 10; }
    @Override public String getSourceName() { return "application.yml"; }
}
