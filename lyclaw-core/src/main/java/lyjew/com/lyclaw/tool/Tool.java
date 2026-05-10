package lyjew.com.lyclaw.tool;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;

public interface Tool {

    String getName();

    ToolResult execute(ToolCall toolCall, ChatContext context);

    ToolDefinition getDefinition();
}
