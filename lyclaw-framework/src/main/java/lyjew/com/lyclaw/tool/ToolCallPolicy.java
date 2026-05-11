package lyjew.com.lyclaw.tool;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;

/**
 * 工具调用策略接口，控制工具调用的轮次、继续条件和错误处理。
 *
 * 该接口定义了工具调用的运行策略，包括最大调用轮次限制、是否应该继续下一轮
 * 调用、错误处理方式以及是否应该重试。实现此接口可以为不同的场景定制
 * 不同的工具调用行为（如保守策略、激进策略、自适应策略等）。
 */
public interface ToolCallPolicy {

    /**
     * 获取工具调用的最大轮次限制，防止无限循环调用工具。
     *
     * @return 最大允许的调用轮次
     */
    int getMaxRounds();

    /**
     * 判断是否应该继续下一轮的工具调用。
     * 根据当前上下文和已完成的轮次来决定是否继续。
     *
     * @param context      聊天上下文
     * @param currentRound 当前已完成的调用轮次（从 1 开始计数）
     * @return true 继续下一轮，false 停止调用
     */
    boolean shouldContinue(ChatContext context, int currentRound);

    /**
     * 处理工具调用过程中发生的错误，返回预设的错误处理动作。
     * 实现类可根据错误类型、工具名称和上下文来决定处理方式。
     *
     * @param toolCall 发生错误的工具调用请求
     * @param e        捕获到的异常
     * @param context  聊天上下文
     * @return 错误处理动作枚举值
     */
    ToolErrorAction handleToolError(ToolCall toolCall, Exception e, ChatContext context);

    /**
     * 判断在工具调用失败后是否应该重试。
     * 可基于工具类型、异常类型和已重试次数来决定。
     *
     * @param toolCall   发生错误的工具调用
     * @param e          捕获到的异常
     * @param retryCount 已经重试的次数（从 1 开始）
     * @return true 应该重试，false 不再重试
     */
    boolean shouldRetryOnError(ToolCall toolCall, Exception e, int retryCount);
}
