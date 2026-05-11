package lyjew.com.lyclaw.context;

import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.ToolDefinition;

import java.util.List;

public interface ContextBuilder {

    List<Message> buildContext(Session session, MemoryContent memory,
                               List<ToolDefinition> toolDefinitions);

    boolean supports(ChatRequest request);
}
