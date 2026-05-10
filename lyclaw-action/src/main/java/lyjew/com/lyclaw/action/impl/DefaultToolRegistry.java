package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component("actionDefaultToolRegistry")
public class DefaultToolRegistry implements ToolRegistry {

    private final ConcurrentHashMap<String, Tool> tools = new ConcurrentHashMap<>();

    public DefaultToolRegistry(List<Tool> toolList) {
        if (toolList != null) {
            for (Tool tool : toolList) {
                register(tool);
            }
        }
        log.info("初始化完成，已注册 {} 个工具", tools.size());
    }

    @Override
    public void register(Tool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("工具不能为 null");
        }
        Tool old = tools.put(tool.getName(), tool);
        if (old != null) {
            log.warn("同名工具被覆盖: name={}", tool.getName());
        }
    }

    public Tool unregister(String name) {
        Tool removed = tools.remove(name);
        if (removed != null) {
            log.info("注销工具: name={}", name);
        }
        return removed;
    }

    @Override
    public Tool get(String name) {
        return tools.get(name);
    }

    @Override
    public List<ToolDefinition> getAllDefinitions() {
        return tools.values().stream()
                .map(Tool::getDefinition)
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public ToolResult execute(ToolCall toolCall, ChatContext context) {
        Tool tool = tools.get(toolCall.getName());
        if (tool == null) {
            throw new IllegalArgumentException(
                    "Tool not found: " + toolCall.getName()
                            + ". 可用工具: " + tools.keySet());
        }
        return tool.execute(toolCall, context);
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public Set<String> getToolNames() {
        return Collections.unmodifiableSet(new HashSet<>(tools.keySet()));
    }

    public int size() {
        return tools.size();
    }

    public void registerMcpTool(String name, String description,
                                Map<String, Object> parameters,
                                String category, String endpointUrl) {
        McpToolAdapter adapter = new McpToolAdapter(name, description,
                parameters, category, endpointUrl);
        register(adapter);
    }

    public void clear() {
        tools.clear();
        log.info("已清空所有工具");
    }

    public Map<String, Long> getCategoryStats() {
        return tools.values().stream()
                .map(t -> t.getDefinition().getSource())
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(c -> c, Collectors.counting()));
    }
}
