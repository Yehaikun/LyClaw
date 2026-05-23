package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.react.ToolExecutor;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolProvider;
import lyjew.com.lyclaw.tool.ToolProviderRequest;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    /** 静态工具存储，以工具名为键 */
    private final ConcurrentHashMap<String, Tool> tools = new ConcurrentHashMap<>();

    /** 动态工具提供者列表（线程安全） */
    private final List<ToolProvider> toolProviders = new CopyOnWriteArrayList<>();

    /**
     * 无参构造函数，用于 Spring 容器早期实例化（BFPP 阶段）。
     * 工具由 {@code ToolAnnotationProcessor}（BeanPostProcessor）在后续阶段注册。
     */
    public DefaultToolRegistry() {
        log.info("ToolRegistry 实例化完成，等待 BeanPostProcessor 阶段注册注解工具");
    }

    /**
     * 构造函数，接收 Spring 容器中所有 Tool 类型的 Bean 并自动注册。
     *
     * @param toolList 所有可用工具的列表（可为空）
     */
    @Autowired
    public DefaultToolRegistry(List<Tool> toolList) {
        this();
        if (toolList != null) {
            for (Tool tool : toolList) {
                register(tool);
            }
        }
    }

    /**
     * 在 Spring 容器刷新完成后自动发现并注册所有 {@link ToolProvider} Bean。
     * 使用事件监听器避免与 SubagentSpawner → ToolRegistry 形成循环依赖。
     */
    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed(ContextRefreshedEvent event) {
        Map<String, ToolProvider> providerBeans = event.getApplicationContext().getBeansOfType(ToolProvider.class);
        for (ToolProvider provider : providerBeans.values()) {
            registerProvider(provider);
        }
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
     * 注册一个动态工具提供者。
     *
     * @param provider 工具提供者
     */
    public void registerProvider(ToolProvider provider) {
        toolProviders.add(provider);
        log.info("注册 ToolProvider: {}", provider.getClass().getSimpleName());
    }

    /**
     * 获取所有已注册工具的定义信息（名称、描述、参数等）。
     * 合并静态工具和动态提供者的工具定义。
     *
     * @return 不可修改的工具定义列表
     */
    @Override
    public List<ToolDefinition> getAllDefinitions() {
        return getAllDefinitions(null);
    }

    /**
     * 获取适用于指定请求的所有工具定义。
     * 合并静态工具和动态提供者（根据请求上下文动态决定）的工具定义。
     *
     * @param request 当前聊天请求，为 null 时只返回静态工具
     * @return 工具定义列表
     */
    public List<ToolDefinition> getAllDefinitions(ChatRequest request) {
        return getAllDefinitions(request, Collections.emptyMap());
    }

    /**
     * 获取适用于指定请求的所有工具定义，携带扩展属性（如 AgentContext）。
     * 扩展属性会被传入 {@link ToolProviderRequest}，供 ToolProvider 在执行时使用。
     *
     * @param request    当前聊天请求，为 null 时只返回静态工具
     * @param attributes 扩展属性（如 "agentContext" → AgentContext 实例）
     * @return 工具定义列表
     */
    public List<ToolDefinition> getAllDefinitions(ChatRequest request, Map<String, Object> attributes) {
        Stream<ToolDefinition> staticDefs = tools.values().stream()
                .map(Tool::getDefinition);

        if (request == null || toolProviders.isEmpty()) {
            return staticDefs.collect(Collectors.toUnmodifiableList());
        }

        ToolProviderRequest providerRequest = new ToolProviderRequest(request, attributes);
        Stream<ToolDefinition> dynamicDefs = toolProviders.stream()
                .flatMap(p -> p.provideTools(providerRequest).getDefinitions().stream());

        return Stream.concat(staticDefs, dynamicDefs)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 合并所有工具执行器（静态 + 动态）用于查找。
     */
    private Tool resolveExecutor(String toolName, ChatRequest request) {
        return resolveExecutor(toolName, request, Collections.emptyMap());
    }

    /**
     * 合并所有工具执行器（静态 + 动态）用于查找，携带扩展属性。
     * 扩展属性被传入 ToolProviderRequest，使 DelegateToAgentToolProvider
     * 等能在执行时解析到 AgentContext。
     */
    private Tool resolveExecutor(String toolName, ChatRequest request, Map<String, Object> attributes) {
        // 优先查静态工具
        Tool tool = tools.get(toolName);
        if (tool != null) return tool;

        // 查动态提供者
        if (request != null) {
            ToolProviderRequest providerRequest = new ToolProviderRequest(request, attributes);
            for (ToolProvider provider : toolProviders) {
                ToolProvider.ToolProviderResult result = provider.provideTools(providerRequest);
                ToolExecutor executor = result.getExecutor(toolName);
                if (executor != null) {
                    // 将 ToolExecutor 包装为 Tool 接口
                    ToolDefinition def = result.getDefinition(toolName);
                    return new Tool() {
                        @Override public String getName() { return toolName; }
                        @Override public ToolDefinition getDefinition() { return def; }
                        @Override public ToolExecutionResult execute(ToolCall tc, ChatContext ctx) {
                            String output = executor.execute(toolName, tc.getToolCallId(), tc.getArguments());
                            return output.startsWith("Error:")
                                    ? ToolExecutionResult.failure(output.substring(7), toolName)
                                    : ToolExecutionResult.success(output, toolName);
                        }
                    };
                }
            }
        }
        return null;
    }

    /**
     * 执行指定工具的调用。先查静态注册表，再查动态提供者。
     * 当工具未找到时返回 failure 结果而非抛出异常，
     * 以便调用方可回退到 executeByName 尝试动态提供者。
     *
     * @param toolCall 工具调用信息（包含工具名和参数）
     * @param context  对话上下文
     * @return 工具执行结果（工具未注册时返回 failure）
     */
    @Override
    public ToolExecutionResult execute(ToolCall toolCall, ChatContext context) {
        ChatRequest request = context != null ? context.getRequest() : null;
        Tool tool = resolveExecutor(toolCall.getName(), request);
        if (tool == null) {
            return ToolExecutionResult.failure(
                    "Tool not found: " + toolCall.getName()
                            + ". 可用工具: " + getAllToolNames(request),
                    toolCall.getName());
        }
        return tool.execute(toolCall, context);
    }

    /**
     * 通过 ToolExecutor 直接执行工具（不需要 ChatContext）。
     * 先查静态注册表，再查动态提供者。
     */
    public ToolExecutionResult executeByName(String toolName, String toolCallId,
                                              String argumentsJson, ChatRequest request) {
        return executeByName(toolName, toolCallId, argumentsJson, request, Collections.emptyMap());
    }

    /**
     * 通过 ToolExecutor 直接执行工具，携带扩展属性。
     * 扩展属性（如 "agentContext"）会被传入 ToolProviderRequest，
     * 供 DelegateToAgentToolProvider 在执行时解析 AgentContext。
     */
    public ToolExecutionResult executeByName(String toolName, String toolCallId,
                                              String argumentsJson, ChatRequest request,
                                              Map<String, Object> attributes) {
        Tool tool = resolveExecutor(toolName, request, attributes);
        if (tool == null) {
            return ToolExecutionResult.failure(
                    "Tool not found: " + toolName + ". 可用工具: " + getAllToolNames(request),
                    toolName);
        }
        ToolCall toolCall = ToolCall.builder()
                .toolCallId(toolCallId).name(toolName).arguments(argumentsJson).build();
        return tool.execute(toolCall, null);
    }

    private Set<String> getAllToolNames(ChatRequest request) {
        Set<String> names = new HashSet<>(tools.keySet());
        if (request != null) {
            ToolProviderRequest providerRequest = new ToolProviderRequest(request);
            for (ToolProvider provider : toolProviders) {
                names.addAll(provider.provideTools(providerRequest).getToolNames());
            }
        }
        return names;
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
