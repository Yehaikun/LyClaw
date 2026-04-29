package lyjew.com.lyclaw.error;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.exception.ModelException;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.tool.ToolErrorAction;
import lyjew.com.lyclaw.model.ChatRequest;
/**
 * 错误处理策略接口 —— 定义模型调用失败或工具执行失败时的处理策略。
 *
 * <p>ErrorPolicy 被 ToolCallLoopStage 回调，根据异常类型和上下文决定
 * 下一步动作：
 * <ul>
 *   <li>模型异常 → 返回 ToolErrorAction 决定是否重试模型调用</li>
 *   <li>工具异常 → 返回 ToolErrorAction 决定是否重试/跳过/中止</li>
 * </ul>
 * </p>
 *
 * <p><b>设计动机</b>：如果不通过 ErrorPolicy 集中管理错误处理策略，
 * ToolCallLoopStage 中会充满 if-else 异常判断逻辑，且不同场景
 * （对话/Agent/技能）的错误处理逻辑不同，需要策略可替换。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ToolErrorAction
 */
public interface ErrorPolicy {

    /**
     * 模型调用异常时的处理策略。
     *
     * @param exception 模型调用异常
     * @param context   当前对话上下文
     * @param request   原始请求
     * @return 错误处理决策
     */
    ToolErrorAction onModelError(ModelException exception, ChatContext context,
                                 ChatRequest request);

    /**
     * 工具执行异常时的处理策略。
     *
     * @param toolCall   出错的工具调用
     * @param exception  工具执行异常
     * @param retryCount 已重试次数
     * @return 错误处理决策
     */
    ToolErrorAction onToolError(ToolCall toolCall, Exception exception,
                                int retryCount);

    /**
     * 获取重试配置。
     *
     * @return 重试相关配置
     */
    RetryConfig getRetryConfig();

    /**
     * 获取熔断器当前状态。
     *
     * @return 状态描述，如 "CLOSED"、"HALF_OPEN"、"OPEN"
     */
    String getCircuitBreakerState();
}