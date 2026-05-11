package lyjew.com.lyclaw.framework.tool;

import lyjew.com.lyclaw.framework.model.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;

public interface Tool {

    String getName();

    ToolResult execute(ToolCall toolCall, ChatContext context);

    ToolDefinition getDefinition();
}
