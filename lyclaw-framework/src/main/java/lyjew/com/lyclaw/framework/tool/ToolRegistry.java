package lyjew.com.lyclaw.framework.tool;

import lyjew.com.lyclaw.framework.model.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;

import java.util.List;

public interface ToolRegistry {

    void register(Tool tool);

    Tool get(String name);

    List<ToolDefinition> getAllDefinitions();

    ToolResult execute(ToolCall toolCall, ChatContext context);
}
