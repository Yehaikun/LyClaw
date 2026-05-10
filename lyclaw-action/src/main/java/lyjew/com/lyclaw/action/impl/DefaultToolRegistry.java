package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.tool.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DefaultToolRegistry {

    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();

    public DefaultToolRegistry() {
        registerTool(ToolDefinition.builder()
                .name("calculator")
                .description("Performs arithmetic calculations")
                .category("utility")
                .parameters(Map.of(
                        "expression", Map.of("type", "string", "description", "Math expression to evaluate")
                ))
                .build());

        registerTool(ToolDefinition.builder()
                .name("current_time")
                .description("Gets the current date and time")
                .category("utility")
                .parameters(Map.of(
                        "timezone", Map.of("type", "string", "description", "Timezone identifier")
                ))
                .build());

        registerTool(ToolDefinition.builder()
                .name("web_search")
                .description("Searches the web for information")
                .category("search")
                .parameters(Map.of(
                        "query", Map.of("type", "string", "description", "Search query string")
                ))
                .build());
    }

    public void registerTool(ToolDefinition tool) {
        tools.put(tool.getName(), tool);
    }

    public ToolDefinition getTool(String name) {
        return tools.get(name);
    }

    public List<ToolDefinition> listTools() {
        return new ArrayList<>(tools.values());
    }
}
