package lyjew.com.lyclaw.autoconfigure.actuator;

import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.ToolRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes all registered tools via Actuator.
 */
@Endpoint(id = "lyclaw-tools")
public class LyClawToolsEndpoint {

    private final ToolRegistry toolRegistry;

    @Autowired
    public LyClawToolsEndpoint(@Autowired(required = false) ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @ReadOperation
    public Map<String, Object> tools() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (toolRegistry == null) {
            result.put("available", false);
            result.put("reason", "No ToolRegistry bean registered");
            return result;
        }
        List<ToolDefinition> definitions = toolRegistry.getAllDefinitions();
        result.put("count", definitions.size());
        result.put("tools", definitions.stream()
                .map(def -> {
                    Map<String, Object> tool = new LinkedHashMap<>();
                    tool.put("name", def.getName());
                    tool.put("displayName", def.getDisplayName());
                    tool.put("description", def.getDescription());
                    tool.put("source", def.getSource());
                    tool.put("timeout", def.getTimeout());
                    return tool;
                })
                .toList());
        return result;
    }
}
