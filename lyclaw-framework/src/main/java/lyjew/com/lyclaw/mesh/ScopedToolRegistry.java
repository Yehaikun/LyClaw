package lyjew.com.lyclaw.mesh;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lyjew.com.lyclaw.tool.ToolProvider;
import lyjew.com.lyclaw.tool.ToolProviderRequest;
import lyjew.com.lyclaw.tool.ToolRegistry;

/**
 * 带作用域的 ToolRegistry —— 包装全局 ToolRegistry，叠加 Per-Agent 私有工具。
 *
 * <p>这是 Decorator 模式的应用：在不改变全局 ToolRegistry 的前提下，
 * 为每个 Agent 添加私有工具隔离层。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * // 创建私有工具
 * ToolDefinition myTool = ToolDefinition.builder()
 *     .name("my-secret-tool")
 *     .description("Only visible to this agent")
 *     .build();
 *
 * // 在 AgentSpec 中指定工具
 * AgentSpec spec = AgentSpec.builder()
 *     .agentId("special-agent")
 *     .tool(myTool)
 *     .config("toolScope", ToolScope.PRIVATE.name())
 *     .build();
 *
 * // LLMAgentInstance 自动使用 ScopedToolRegistry
 * }</pre>
 */
public class ScopedToolRegistry implements ToolRegistry {

    private final ToolRegistry globalRegistry;
    private final String agentId;
    private final ToolScope scope;
    private final List<ToolDefinition> privateTools;
    private final Map<String, Tool> privateToolMap;
    private final String parentAgentId;

    /**
     * 创建作用域化的工具注册表。
     *
     * @param globalRegistry 全局 ToolRegistry
     * @param agentId        当前 Agent ID
     * @param scope          工具作用域
     * @param privateTools   私有工具定义列表
     * @param parentAgentId  父 Agent ID（INHERIT 作用域时需要）
     */
    public ScopedToolRegistry(ToolRegistry globalRegistry, String agentId,
                               ToolScope scope, List<ToolDefinition> privateTools,
                               String parentAgentId) {
        this.globalRegistry = globalRegistry;
        this.agentId = agentId;
        this.scope = scope != null ? scope : ToolScope.GLOBAL;
        this.privateTools = privateTools != null ? privateTools : List.of();
        this.privateToolMap = new ConcurrentHashMap<>();
        this.parentAgentId = parentAgentId;
    }

    @Override
    public void register(Tool tool) {
        // 私有工具：仅在 scope 不是 GLOBAL 时注册到本地
        if (scope != ToolScope.GLOBAL) {
            privateToolMap.put(tool.getName(), tool);
        } else {
            globalRegistry.register(tool);
        }
    }

    @Override
    public Tool get(String name) {
        // 优先查私有工具
        Tool privateTool = privateToolMap.get(name);
        if (privateTool != null) return privateTool;
        // 再查全局
        return globalRegistry.get(name);
    }

    @Override
    public List<ToolDefinition> getAllDefinitions() {
        return getAllDefinitions(null, Map.of());
    }

    @Override
    public List<ToolDefinition> getAllDefinitions(ChatRequest request) {
        return getAllDefinitions(request, Map.of());
    }

    @Override
    public List<ToolDefinition> getAllDefinitions(ChatRequest request, Map<String, Object> attributes) {
        List<ToolDefinition> result = new ArrayList<>();

        // 全局工具（所有 scope 都包含）
        if (globalRegistry != null) {
            result.addAll(globalRegistry.getAllDefinitions(request, attributes));
        }

        // 私有工具（PRIVATE 和 INHERIT 作用域）
        if (scope == ToolScope.PRIVATE || scope == ToolScope.INHERIT) {
            for (ToolDefinition def : privateTools) {
                // 避免与全局工具重名
                if (result.stream().noneMatch(d -> d.getName().equals(def.getName()))) {
                    result.add(def);
                }
            }
            // 本地注册的私有 Tool
            for (Tool tool : privateToolMap.values()) {
                ToolDefinition def = tool.getDefinition();
                if (result.stream().noneMatch(d -> d.getName().equals(def.getName()))) {
                    result.add(def);
                }
            }
        }

        return result;
    }

    @Override
    public ToolExecutionResult execute(ToolCall toolCall, ChatContext context) {
        String toolName = toolCall.getName();

        // 优先执行私有工具
        Tool privateTool = privateToolMap.get(toolName);
        if (privateTool != null) {
            return privateTool.execute(toolCall, context);
        }

        // 再查私有工具定义（但只有定义没有 Tool 实例）
        // 这种情况需要回退到全局
        return globalRegistry != null ? globalRegistry.execute(toolCall, context)
                : ToolExecutionResult.failure("Tool not found: " + toolName, toolName);
    }

    @Override
    public ToolExecutionResult executeByName(String toolName, String toolCallId,
                                              String argumentsJson, ChatRequest request) {
        // 私有工具没有 Tool 实例，但有 ToolDefinition
        // 回退到全局
        return globalRegistry != null
                ? globalRegistry.executeByName(toolName, toolCallId, argumentsJson, request)
                : ToolExecutionResult.failure("Tool not found: " + toolName, toolName);
    }

    @Override
    public ToolExecutionResult executeByName(String toolName, String toolCallId,
                                              String argumentsJson, ChatRequest request,
                                              Map<String, Object> attributes) {
        return globalRegistry != null
                ? globalRegistry.executeByName(toolName, toolCallId, argumentsJson, request, attributes)
                : ToolExecutionResult.failure("Tool not found: " + toolName, toolName);
    }

    /** 获取当前作用域 */
    public ToolScope getScope() { return scope; }

    /** 获取私有工具数 */
    public int privateToolCount() { return privateTools.size() + privateToolMap.size(); }

    /** 获取所有私有工具定义 */
    public List<ToolDefinition> getPrivateDefinitions() {
        return List.copyOf(privateTools);
    }
}
