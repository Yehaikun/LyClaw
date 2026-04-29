package lyjew.com.lyclaw.error.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.error.ErrorPolicy;
import lyjew.com.lyclaw.error.RetryConfig;
import lyjew.com.lyclaw.exception.ModelException;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.tool.ToolErrorAction;

import org.springframework.stereotype.Component;

/**
 * 默认错误处理策略 —— 模型异常 RETRY（最多3次），工具异常默认 ABORT，熔断器 CLOSED。
 *
 * <p><b>设计动机</b>：错误处理是引擎稳定性的保障。
 * DefaultErrorPolicy 采用"尽量重试，最坏降级"的原则：
 * <ul>
 *   <li>模型调用失败：可能是网络抖动，允许重试 3 次</li>
 *   <li>工具执行失败：可能是参数问题，默认 ABORT</li>
 *   <li>超过重试上限：记录日志，抛出异常让上层处理</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ErrorPolicy
 * @see RetryConfig
 */
@Component
public class DefaultErrorPolicy implements ErrorPolicy {

    /** 模型调用最大重试次数 */
    private static final int MODEL_MAX_RETRIES = 3;

    /** 工具执行最大重试次数 */
    private static final int TOOL_MAX_RETRIES = 2;

    /** 重试基础延迟（ms） */
    private static final long BASE_DELAY_MS = 1000;

    @Override
    public ToolErrorAction onModelError(ModelException exception,
                                        ChatContext context,
                                        ChatRequest request) {
        return ToolErrorAction.RETRY;
    }

    @Override
    public ToolErrorAction onToolError(ToolCall toolCall,
                                       Exception exception,
                                       int retryCount) {
        if (retryCount < TOOL_MAX_RETRIES) {
            return ToolErrorAction.RETRY;
        }
        return ToolErrorAction.ABORT;
    }

    @Override
    public RetryConfig getRetryConfig() {
        return RetryConfig.exponential(MODEL_MAX_RETRIES, BASE_DELAY_MS);
    }

    @Override
    public String getCircuitBreakerState() {
        return "CLOSED";
    }
}