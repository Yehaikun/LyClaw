package lyjew.com.lyclaw.error;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.exception.ModelException;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.tool.ToolErrorAction;
import lyjew.com.lyclaw.model.ChatRequest;

public interface ErrorPolicy {

    ToolErrorAction onModelError(ModelException exception, ChatContext context,
                                 ChatRequest request);

    ToolErrorAction onToolError(ToolCall toolCall, Exception exception,
                                int retryCount);

    RetryConfig getRetryConfig();

    String getCircuitBreakerState();
}
