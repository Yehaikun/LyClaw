package lyjew.com.lyclaw.tool;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;

import java.util.List;

@Deprecated
public interface ToolRegistry {

    void register(Tool tool);

    Tool get(String name);

    List<ToolDefinition> getAllDefinitions();

    ToolResult execute(ToolCall toolCall, ChatContext context);
}
