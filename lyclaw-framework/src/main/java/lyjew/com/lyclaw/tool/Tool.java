package lyjew.com.lyclaw.tool;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;

/**
 * 旧版工具接口，已废弃。
 *
 * 定义了一个可被 AI 模型调用的工具的完整行为：名称、执行逻辑以及模型可见的定义信息。
 * 在旧版同步架构中使用，每个 Tool 实现类对应一个具体的工具功能
 * （如文件读写、命令执行等）。
 *
 * @deprecated 请使用新的工具架构替代
 */
@Deprecated
public interface Tool {

    /**
     * 获取工具的唯一名称，用于在工具调用请求中匹配对应的工具。
     *
     * @return 工具名称
     */
    String getName();

    /**
     * 执行工具的实际逻辑，接收模型发起的工具调用请求并返回执行结果。
     *
     * @param toolCall 模型发起的工具调用，包含调用 ID、参数等信息
     * @param context  聊天上下文，提供会话、消息等运行时数据
     * @return 工具执行结果，包含成功/失败状态以及结果数据
     */
    ToolResult execute(ToolCall toolCall, ChatContext context);

    /**
     * 获取工具的定义信息，用于构造发送给 AI 模型的 tools 参数。
     * 包含工具名称、描述、参数 schema 等元数据。
     *
     * @return 工具定义对象
     */
    ToolDefinition getDefinition();
}
