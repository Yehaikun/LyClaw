package lyjew.com.lyclaw.tool;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;

/**
 * 工具 SPI 接口。
 *
 * <p>定义工具的完整行为契约。工具可以通过以下三种方式注册：
 * <ul>
 *   <li>模式一：纯 @Tool 注解 — 由 AnnotatedToolAdapter 自动包装</li>
 *   <li>模式二：直接实现此接口 — 完全掌控执行逻辑</li>
 *   <li>模式三：注解 + 接口 — 优先走接口分支，不走 adapter</li>
 * </ul>
 */
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
    ToolExecutionResult execute(ToolCall toolCall, ChatContext context);

    /**
     * 获取工具的定义信息，用于构造发送给 AI 模型的 tools 参数。
     * 包含工具名称、描述、参数 schema 等元数据。
     *
     * @return 工具定义对象
     */
    ToolDefinition getDefinition();
}
