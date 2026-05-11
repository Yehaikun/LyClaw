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

/**
 * 全窗口上下文构建器。
 *
 * 将系统消息、记忆内容和会话历史消息组装成完整的上下文消息列表。
 * 系统消息包含所有可用工具的定义，记忆内容以特殊 XML 标签包裹后注入，
 * 使 LLM 能够在一次请求中获得完整的上下文。
 */
@Component
public class FullWindowContextBuilder implements ContextBuilder {

    /**
     * 构建完整的上下文消息列表。
     * 消息顺序：系统消息 -> 记忆（可选） -> 会话历史消息。
     *
     * @param session         当前会话
     * @param memory          记忆内容（可为 null 或禁用）
     * @param toolDefinitions 工具定义列表
     * @return 组装后的消息列表
     */
    @Override
    public List<Message> buildContext(Session session, MemoryContent memory,
                                      List<ToolDefinition> toolDefinitions) {
        // 预分配容量：会话消息数 + 系统消息 + 可选的记忆消息
        List<Message> messages = new ArrayList<>(session.getMessages().size() + 2);

        // 构建并添加系统提示消息
        Message systemMessage = buildSystemMessage(toolDefinitions);
        messages.add(systemMessage);

        // 如果记忆已启用且有内容，以 <memory> 标签包裹后添加
        if (memory != null && memory.isEnabled() && memory.getContent() != null) {
            String wrapped = "<memory>\n" + memory.getContent() + "\n</memory>";
            messages.add(Message.builder().role("user").content(wrapped).build());
        }

        // 追加所有会话历史消息
        messages.addAll(session.getMessages());
        return messages;
    }

    /**
     * 判断是否支持该请求的上下文构建。当前对所有请求返回 true。
     *
     * @param request 聊天请求
     * @return 始终返回 true
     */
    @Override
    public boolean supports(ChatRequest request) {
        return true;
    }

    /**
     * 构建系统消息，包含可用工具列表的描述。
     *
     * @param toolDefinitions 工具定义列表
     * @return 角色为 system 的消息
     */
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
