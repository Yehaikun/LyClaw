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

/**
 * 默认工具注册表，管理所有已注册的 {@link Tool} 实例。
 *
 * <p>提供工具的生命周期管理：注册、注销、查找、执行和统计。
 * 内部使用 {@link ConcurrentHashMap} 保证线程安全。
 * 支持通过 {@link #registerMcpTool} 便捷地创建 MCP 适配工具。</p>
 */
@Slf4j
@Component
public class DefaultToolRegistry implements ToolRegistry {

    /** 工具存储，以工具名为键 */
    private final ConcurrentHashMap<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * 构造函数，接收 Spring 容器中所有 Tool 类型的 Bean 并自动注册。
     *
     * @param toolList 所有可用工具的列表（可为空）
     */
    public DefaultToolRegistry(List<Tool> toolList) {
        if (toolList != null) {
            for (Tool tool : toolList) {
                register(tool);
            }
        }
        log.info("初始化完成，已注册 {} 个工具", tools.size());
    }

    /**
     * 注册一个工具。同名工具会被覆盖并记录警告。
     *
     * @param tool 工具实例（不可为 null）
     * @throws IllegalArgumentException 当 tool 为 null
     */
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

    /**
     * 注销指定名称的工具。
     *
     * @param name 工具名称
     * @return 被移除的工具实例，不存在时返回 null
     */
    public Tool unregister(String name) {
        Tool removed = tools.remove(name);
        if (removed != null) {
            log.info("注销工具: name={}", name);
        }
        return removed;
    }

    /**
     * 按名称查找工具。
     *
     * @param name 工具名称
     * @return 工具实例，不存在时返回 null
     */
    @Override
    public Tool get(String name) {
        return tools.get(name);
    }

    /**
     * 获取所有已注册工具的定义信息（名称、描述、参数等）。
     *
     * @return 不可修改的工具定义列表
     */
    @Override
    public List<ToolDefinition> getAllDefinitions() {
        return tools.values().stream()
                .map(Tool::getDefinition)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 执行指定工具的调用。
     *
     * @param toolCall 工具调用信息（包含工具名和参数）
     * @param context  对话上下文
     * @return 工具执行结果
     * @throws IllegalArgumentException 当工具未注册
     */
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

    /** @return 是否包含指定名称的工具 */
    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /** @return 所有工具名称的不可变集合 */
    public Set<String> getToolNames() {
        return Collections.unmodifiableSet(new HashSet<>(tools.keySet()));
    }

    /** @return 当前已注册工具数量 */
    public int size() {
        return tools.size();
    }

    /**
     * 便捷方法：创建一个 MCP 适配工具并注册。
     *
     * @param name        工具名称
     * @param description 工具描述
     * @param parameters  工具参数定义
     * @param category    工具分类
     * @param endpointUrl MCP 服务端点 URL
     */
    public void registerMcpTool(String name, String description,
                                Map<String, Object> parameters,
                                String category, String endpointUrl) {
        McpToolAdapter adapter = new McpToolAdapter(name, description,
                parameters, category, endpointUrl);
        register(adapter);
    }

    /** 清空所有已注册的工具 */
    public void clear() {
        tools.clear();
        log.info("已清空所有工具");
    }

    /**
     * 按来源分类统计工具数量。
     *
     * @return 分类名到计数的映射
     */
    public Map<String, Long> getCategoryStats() {
        return tools.values().stream()
                .map(t -> t.getDefinition().getSource())
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(c -> c, Collectors.counting()));
    }
}
