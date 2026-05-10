package lyjew.com.lyclaw.tool;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;

public interface ToolCallPolicy {

    int getMaxRounds();

    boolean shouldContinue(ChatContext context, int currentRound);

    ToolErrorAction handleToolError(ToolCall toolCall, Exception e, ChatContext context);

    boolean shouldRetryOnError(ToolCall toolCall, Exception e, int retryCount);
}
