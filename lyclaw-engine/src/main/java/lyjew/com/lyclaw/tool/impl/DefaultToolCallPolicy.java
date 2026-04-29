package lyjew.com.lyclaw.tool.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.tool.ToolCallPolicy;
import lyjew.com.lyclaw.tool.ToolErrorAction;

import org.springframework.stereotype.Component;

/**
 * 默认工具调用策略 —— 最大 10 轮调用上限，异常时立即终止。
 *
 * <p><b>设计动机</b>：ToolCallPolicy 控制模型与工具的交互行为。
 * 如果没有轮次上限，模型可能陷入无限工具调用循环（如一直调用搜索工具）。
 * 设置上限既是保护机制也是降级策略。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>ToolCallLoopStage 在每次循环前调用 getMaxRounds() 和 shouldContinue()</li>
 *   <li>ErrorPolicy.onToolError() 决策后如果需要重试，回调 shouldRetryOnError()</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ToolCallPolicy
 */
@Component
public class DefaultToolCallPolicy implements ToolCallPolicy {

    /** 最大工具调用轮次 */
    private static final int MAX_ROUNDS = 10;

    /** 最大重试次数 */
    private static final int MAX_RETRIES = 3;

    /**
     * 获取最大工具调用轮次。达到此上限后 ToolCallLoop 停止循环。
     *
     * @return 最大轮次数
     */
    @Override
    public int getMaxRounds() {
        return MAX_ROUNDS;
    }

    /**
     * 判断是否应该继续工具调用循环。
     *
     * @param context      当前对话上下文
     * @param currentRound 当前已执行的轮次（从 1 开始计数）
     * @return true 表示继续循环
     */
    @Override
    public boolean shouldContinue(ChatContext context, int currentRound) {
        // 当前轮次小于最大轮次时继续
        return currentRound < MAX_ROUNDS;
    }

    /**
     * 处理工具执行错误。默认返回 ABORT，立即终止循环。
     *
     * @param toolCall 出错的工具调用
     * @param error    异常信息
     * @param context  当前对话上下文
     * @return 返回 ABORT
     */
    @Override
    public ToolErrorAction handleToolError(ToolCall toolCall,
                                           Exception error,
                                           ChatContext context) {
        // 默认：工具执行出错后立即终止循环
        return ToolErrorAction.ABORT;
    }

    /**
     * 判断是否应该重试。默认允许最多重试 3 次。
     *
     * @param toolCall   出错的工具调用
     * @param error      异常信息
     * @param retryCount 已重试次数
     * @return retryCount < 3 时返回 true
     */
    @Override
    public boolean shouldRetryOnError(ToolCall toolCall,
                                      Exception error,
                                      int retryCount) {
        // 默认：最多重试 3 次
        return retryCount < MAX_RETRIES;
    }
}