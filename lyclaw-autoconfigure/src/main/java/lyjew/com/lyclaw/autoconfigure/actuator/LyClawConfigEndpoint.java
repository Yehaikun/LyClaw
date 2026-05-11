package lyjew.com.lyclaw.autoconfigure.actuator;

import lyjew.com.lyclaw.framework.config.LyClawProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes current LyClaw configuration (with api-key masked).
 */
@Endpoint(id = "lyclaw-config")
public class LyClawConfigEndpoint {

    private final LyClawProperties props;
    private final Environment env;

    @Autowired
    public LyClawConfigEndpoint(@Autowired(required = false) LyClawProperties props,
                                 @Autowired(required = false) Environment env) {
        this.props = props;
        this.env = env;
    }

    @ReadOperation
    public Map<String, Object> config() {
        Map<String, Object> result = new LinkedHashMap<>();

        if (props != null) {
            // LLM config (sanitized)
            Map<String, Object> llm = new LinkedHashMap<>();
            llm.put("provider", props.getLlm().getProvider());
            llm.put("model", props.getLlm().getModel());
            llm.put("temperature", props.getLlm().getTemperature());
            llm.put("maxTokens", props.getLlm().getMaxTokens());
            llm.put("timeout", props.getLlm().getTimeout());
            llm.put("baseUrl", props.getLlm().getBaseUrl());
            // Mask api-key: show first 4 + last 2 chars
            String apiKey = props.getLlm().getApiKey();
            if (apiKey != null && apiKey.length() > 6) {
                llm.put("apiKey", apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 2));
            } else if (apiKey != null) {
                llm.put("apiKey", "****");
            } else {
                llm.put("apiKey", null);
            }
            result.put("llm", llm);

            // Pipeline config
            Map<String, Object> pipeline = new LinkedHashMap<>();
            pipeline.put("enabled", props.getPipeline().isEnabled());
            pipeline.put("timeout", props.getPipeline().getTimeout());
            pipeline.put("stagesOrder", props.getPipeline().getStagesOrder());
            result.put("pipeline", pipeline);

            // Tools config
            Map<String, Object> tools = new LinkedHashMap<>();
            tools.put("enabled", props.getTools().isEnabled());
            tools.put("defaultTimeout", props.getTools().getDefaultTimeout());
            result.put("tools", tools);

            // Sandbox config
            Map<String, Object> sandbox = new LinkedHashMap<>();
            sandbox.put("level", props.getSandbox().getLevel());
            sandbox.put("readonlyTools", props.getSandbox().getReadonlyTools());
            result.put("sandbox", sandbox);

            // Agent config
            Map<String, Object> agent = new LinkedHashMap<>();
            agent.put("active", props.getAgent().getActive());
            agent.put("maxRounds", props.getAgent().getMaxRounds());
            result.put("agent", agent);
        } else {
            result.put("available", false);
            result.put("reason", "No LyClawProperties bean registered");
        }

        return result;
    }
}
