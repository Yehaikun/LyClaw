package lyjew.com.lyclaw.tool;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;

/**
 * 工具抽象接口 —— 所有工具必须实现此接口。
 *
 * <p>工具是引擎中"可被模型调用的功能单元"。模型在生成回复时，
 * 如果判定需要调用某个工具，会在响应中包含 ToolCall 对象。
 * ToolCallLoop 根据 ToolCall 中的 name 找到对应的 Tool 并执行。</p>
 *
 * <p><b>设计动机</b>：将各种功能（搜索网页、计算数学、获取时间、操作数据库等）
 * 统一为 Tool 接口。ToolRegistry 管理所有 Tool 的生命周期，
 * ToolCallLoop 统一调度执行。新增工具只需新建类实现 Tool 接口并注册即可。</p>
 *
 * <p><b>与 MCP 的关系</b>：MCP（Model Context Protocol）工具通过 McpToolAdapter
 * 适配为 Tool 接口，这样引擎层不需要感知 MCP 协议的具体细节。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ToolRegistry
 * @see ToolCallLoop
 */
public interface Tool {

    /**
     * 获取工具名称，全局唯一。如 "web_search"、"calculator"。
     * 模型返回的 ToolCall.name 与此名称匹配。
     *
     * @return 工具名称（非 null，全局唯一）
     */
    String getName();

    /**
     * 执行工具。
     *
     * @param toolCall 模型返回的工具调用请求，包含工具名和参数
     * @param context  当前对话上下文，可用于获取会话信息、注入结果
     * @return 工具执行结果
     */
    ToolResult execute(ToolCall toolCall, ChatContext context);

    /**
     * 获取工具定义。定义中包含名称、描述、参数 JSON Schema，
     * 会被发送给模型，让模型知道这个工具的功能和参数格式。
     *
     * @return 工具定义（非 null），来源于 lyjew.com.lyclaw.model.ToolDefinition
     */
    ToolDefinition getDefinition();
}