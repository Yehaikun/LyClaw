package lyjew.com.lyclaw.orchestration.context;

import lyjew.com.lyclaw.context.ContextBuilder;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class FullWindowContextBuilder implements ContextBuilder {

    @Override
    public List<Message> buildContext(Session session, MemoryContent memory,
                                      List<ToolDefinition> toolDefinitions) {
        List<Message> messages = new ArrayList<>(session.getMessages().size() + 2);

        Message systemMessage = buildSystemMessage(toolDefinitions);
        messages.add(systemMessage);

        if (memory != null && memory.isEnabled() && memory.getContent() != null) {
            String wrapped = "<memory>\n" + memory.getContent() + "\n</memory>";
            messages.add(Message.builder().role("user").content(wrapped).build());
        }

        messages.addAll(session.getMessages());
        return messages;
    }

    @Override
    public boolean supports(ChatRequest request) {
        return true;
    }

    private Message buildSystemMessage(List<ToolDefinition> toolDefinitions) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an intelligent AI assistant. You can call the following tools to complete tasks:\n");
        for (ToolDefinition def : toolDefinitions) {
            sb.append("- ").append(def.getName())
                    .append(": ").append(def.getDescription()).append("\n");
        }
        return Message.builder().role("system").content(sb.toString()).build();
    }
}
