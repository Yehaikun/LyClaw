package lyjew.com.lyclaw.error;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.exception.ModelException;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.tool.ToolErrorAction;
import lyjew.com.lyclaw.model.ChatRequest;

/**
 * 错误处理策略接口，定义模型错误和工具错误时的应对行为。
 *
 * <p>实现类决定当 LLM 调用失败或工具执行异常时应采取的行动：
 * 重试（RETRY）、跳过（SKIP）、终止（ABORT）或回退（FALLBACK）。
 * 同时提供重试配置和熔断器状态查询。</p>
 */
public interface ErrorPolicy {

    /**
     * 处理 LLM 模型调用错误。
     *
     * @param exception 模型异常
     * @param context   当前对话上下文
     * @param request   原始请求
     * @return 应采取的应对行动
     */
    ToolErrorAction onModelError(ModelException exception, ChatContext context,
                                 ChatRequest request);

    /**
     * 处理工具调用错误。
     *
     * @param toolCall   出错的工具调用
     * @param exception  工具异常
     * @param retryCount 已重试次数
     * @return 应采取的应对行动
     */
    ToolErrorAction onToolError(ToolCall toolCall, Exception exception,
                                int retryCount);

    /** @return 当前重试配置 */
    RetryConfig getRetryConfig();

    /** @return 熔断器当前状态（CLOSED / OPEN / HALF_OPEN） */
    String getCircuitBreakerState();
}
