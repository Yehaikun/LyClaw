package lyjew.com.lyclaw.tool;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;

import java.util.List;

/**
 * 旧版工具注册表接口，已废弃。
 *
 * 作为工具的管理中心，负责注册、查找和执行工具。在系统启动时收集所有
 * 可用的工具实现，运行时根据模型发起的工具调用请求查找对应工具并执行。
 * 同时负责提供所有工具定义列表，用于构造发送给 AI 模型的请求参数。
 *
 * @deprecated 请使用新的工具注册架构替代
 */
@Deprecated
public interface ToolRegistry {

    /**
     * 向注册表中注册一个新工具。
     *
     * @param tool 要注册的工具实例
     */
    void register(Tool tool);

    /**
     * 根据工具名称查找已注册的工具实例。
     *
     * @param name 工具名称
     * @return 对应的工具实例，未找到时返回 null
     */
    Tool get(String name);

    /**
     * 获取所有已注册工具的定义列表。
     * 通常用于构造发送给 AI 模型的 tools 字段，让模型知道有哪些可用工具。
     *
     * @return 所有工具定义的列表
     */
    List<ToolDefinition> getAllDefinitions();

    /**
     * 根据工具调用请求执行对应的工具。
     * 先通过工具名称查找注册的工具，再调用其 execute 方法。
     *
     * @param toolCall 模型发起的工具调用请求
     * @param context  聊天上下文
     * @return 工具执行结果
     */
    ToolResult execute(ToolCall toolCall, ChatContext context);
}
