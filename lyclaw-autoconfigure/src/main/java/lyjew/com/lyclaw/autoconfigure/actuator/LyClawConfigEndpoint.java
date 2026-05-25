package lyjew.com.lyclaw.autoconfigure.actuator;

import lyjew.com.lyclaw.chat.ChatProperties;
import lyjew.com.lyclaw.config.AgentProperties;
import lyjew.com.lyclaw.pipeline.PipelineProperties;
import lyjew.com.lyclaw.tool.ToolProperties;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LyClaw config Actuator endpoint at {@code /actuator/lyclaw-config}.
 * Aggregates configuration from all domain-specific Properties beans.
 */
@Endpoint(id = "lyclaw-config")
public class LyClawConfigEndpoint {

    @Autowired(required = false)
    private ChatProperties chatProperties;

    @Autowired(required = false)
    private ToolProperties toolProperties;

    @Autowired(required = false)
    private AgentProperties agentProperties;

    @Autowired(required = false)
    private PipelineProperties pipelineProperties;

    @ReadOperation
    public Map<String, Object> config() {
        Map<String, Object> result = new LinkedHashMap<>();

        if (chatProperties != null) {
            Map<String, Object> chat = new LinkedHashMap<>();
            chat.put("defaultProvider", chatProperties.getDefaultProvider());
            chat.put("defaultModel", chatProperties.getDefaultModel());
            chat.put("legacy", chatProperties.isLegacy());
            if (chatProperties.getModels() != null) {
                Map<String, Object> models = new LinkedHashMap<>();
                chatProperties.getModels().forEach((k, v) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("baseUrl", v.getBaseUrl());
                    m.put("model", v.getModel());
                    String key = v.getApiKey();
                    m.put("apiKey", maskApiKey(key));
                    models.put(k, m);
                });
                chat.put("models", models);
            }
            result.put("chat", chat);
        }

        if (toolProperties != null) {
            Map<String, Object> tools = new LinkedHashMap<>();
            tools.put("defaultTimeoutMs", toolProperties.getDefaultTimeoutMs());
            tools.put("maxOutputLength", toolProperties.getMaxOutputLength());
            tools.put("maxCallsPerTool", toolProperties.getMaxCallsPerTool());
            tools.put("maxRetries", toolProperties.getMaxRetries());
            tools.put("maxRounds", toolProperties.getMaxRounds());
            tools.put("sandboxLevel", toolProperties.getSandboxLevel());
            String tavilyKey = toolProperties.getTavilyApiKey();
            tools.put("tavilyApiKey", maskApiKey(tavilyKey));
            result.put("tool", tools);
        }

        if (agentProperties != null) {
            Map<String, Object> agent = new LinkedHashMap<>();
            agent.put("defaultMode", agentProperties.getDefaultMode());
            agent.put("maxToolRounds", agentProperties.getMaxToolRounds());
            agent.put("approvalTimeoutSeconds", agentProperties.getApprovalTimeoutSeconds());
            agent.put("timeoutMs", agentProperties.getTimeoutMs());
            result.put("agent", agent);
        }

        if (pipelineProperties != null) {
            Map<String, Object> pipeline = new LinkedHashMap<>();
            pipeline.put("enabled", pipelineProperties.isEnabled());
            pipeline.put("timeoutMs", pipelineProperties.getTimeoutMs());
            result.put("pipeline", pipeline);
        }

        if (result.isEmpty()) {
            result.put("available", false);
            result.put("reason", "No LyClaw properties beans registered — is lyclaw-autoconfigure on the classpath?");
        }

        return result;
    }

    private static String maskApiKey(String key) {
        if (key == null) return null;
        if (key.length() > 6) {
            return key.substring(0, 4) + "****" + key.substring(key.length() - 2);
        }
        return "****";
    }
}
