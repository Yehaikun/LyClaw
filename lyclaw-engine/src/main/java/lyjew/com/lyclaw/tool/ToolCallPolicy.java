package lyjew.com.lyclaw.tool;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;

/**
 * 工具调用循环策略接口 —— 控制 ToolCallLoop 的行为。
 *
 * <p>ToolCallLoop 负责执行"模型调用 + 工具执行"的循环。
 * ToolCallPolicy 定义了循环的边界条件和错误处理策略，
 * 使 ToolCallLoop 的核心逻辑可以保持稳定，而循环策略可以灵活替换。</p>
 *
 * <p><b>可替换的策略实现</b>：
 * <ul>
 *   <li>DefaultToolCallPolicy：最多 10 轮，超出则终止</li>
 *   <li>BudgetAwarePolicy：根据 Token 预算动态决定是否继续</li>
 *   <li>ModelDrivenPolicy：让模型自己决定是否继续（TLS 1.3 的 max_tool_calls）</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ToolCallLoop
 */
public interface ToolCallPolicy {

    /**
     * 获取最大工具调用轮次。超出此轮次后强制终止循环。
     *
     * @return 最大轮次
     */
    int getMaxRounds();

    /**
     * 判断是否继续循环。在每一轮结束后调用。
     *
     * @param context      当前对话上下文
     * @param currentRound 已完成轮次（从 0 开始）
     * @return true 表示继续下一轮，false 表示终止
     */
    boolean shouldContinue(ChatContext context, int currentRound);

    /**
     * 工具执行出错时的决策。返回不同的 ToolErrorAction 引导循环下一步行为。
     *
     * @param toolCall 出错的工具调用
     * @param e        捕获的异常
     * @param context  当前对话上下文
     * @return 错误处理动作（RETRY / SKIP / ABORT / FALLBACK）
     */
    ToolErrorAction handleToolError(ToolCall toolCall, Exception e, ChatContext context);

    /**
     * 判断是否应该重试当前工具调用。在 handleToolError 返回 RETRY 后被调用。
     *
     * @param toolCall   出错的工具调用
     * @param e          捕获的异常
     * @param retryCount 已重试次数（从 0 开始）
     * @return true 表示可以继续重试
     */
    boolean shouldRetryOnError(ToolCall toolCall, Exception e, int retryCount);
}