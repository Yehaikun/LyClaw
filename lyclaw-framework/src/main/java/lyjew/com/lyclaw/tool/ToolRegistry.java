package lyjew.com.lyclaw.tool;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * 工具注册表 SPI 接口。
 *
 * <p>作为工具的管理中心，负责注册、查找和执行工具。
 * 在系统启动时通过 ToolAnnotationProcessor 自动收集 @Tool 注解的工具，
 * 运行时根据模型发起的工具调用请求查找对应工具并执行。
 *
 * <p>支持两种工具来源：
 * <ul>
 *   <li><b>静态工具</b> — 通过 {@link #register(Tool)} 注册，启动时确定</li>
 *   <li><b>动态工具</b> — 通过 {@link ToolProvider} 提供，每次调用时动态决定</li>
 * </ul>
 */
public interface ToolRegistry {

    /**
     * 向注册表中注册一个新工具。
     */
    void register(Tool tool);

    /**
     * 根据工具名称查找已注册的工具实例。
     */
    Tool get(String name);

    /**
     * 获取所有已注册工具的定义列表（仅静态工具）。
     */
    List<ToolDefinition> getAllDefinitions();

    /**
     * 获取适用于指定请求的所有工具定义（静态 + 动态）。
     * 默认实现退回 {@link #getAllDefinitions()}，子类可覆写以支持 ToolProvider。
     *
     * @param request 当前聊天请求，为 null 时等同 getAllDefinitions()
     */
    default List<ToolDefinition> getAllDefinitions(ChatRequest request) {
        return getAllDefinitions();
    }

    /**
     * 获取适用于指定请求的所有工具定义，携带扩展属性。
     * 扩展属性（如 "agentContext"）会被传入 ToolProviderRequest，
     * 供 ToolProvider 在生成定义和执行时使用。
     *
     * @param request    当前聊天请求
     * @param attributes 扩展属性
     */
    default List<ToolDefinition> getAllDefinitions(ChatRequest request, Map<String, Object> attributes) {
        return getAllDefinitions(request);
    }

    /**
     * 根据工具调用请求执行对应的工具。
     */
    ToolExecutionResult execute(ToolCall toolCall, ChatContext context);

    /**
     * 通过工具名称和参数直接执行（不需要 ChatContext），支持动态工具。
     * 默认实现退回 {@link #execute(ToolCall, ChatContext)}。
     */
    default ToolExecutionResult executeByName(String toolName, String toolCallId,
                                               String argumentsJson, ChatRequest request) {
        ToolCall toolCall = ToolCall.builder()
                .toolCallId(toolCallId).name(toolName).arguments(argumentsJson).build();
        return execute(toolCall, null);
    }

    /**
     * 通过工具名称和参数直接执行，携带扩展属性。
     * 扩展属性（如 "agentContext"）会被传入 ToolProviderRequest，
     * 供 DelegateToAgentToolProvider 在执行时解析 AgentContext。
     */
    default ToolExecutionResult executeByName(String toolName, String toolCallId,
                                               String argumentsJson, ChatRequest request,
                                               Map<String, Object> attributes) {
        return executeByName(toolName, toolCallId, argumentsJson, request);
    }
}
