package lyjew.com.lyclaw.context.impl;

import lyjew.com.lyclaw.context.ContextBuilder;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 全量窗口上下文构建策略 —— 把所有消息塞进去，不做截断。兜底策略。
 *
 * <p>当没有其他 ContextBuilder（如 SlidingWindowContextBuilder、SummaryContextBuilder）
 * 匹配当前请求时，FullWindowContextBuilder 作为最后的兜底策略总是返回 true。</p>
 *
 * <p><b>构建顺序</b>：
 * <ol>
 *   <li>注入 System Prompt（包含系统角色设定、当前可用工具描述）</li>
 *   <li>注入长期记忆（如果 {@link MemoryContent#isEnabled()} 为 true，
 *       将内容用 {@code <memory>} 标签包裹后插入）</li>
 *   <li>追加会话中的所有历史消息</li>
 *   <li>注入当前请求的消息</li>
 * </ol>
 * </p>
 *
 * <p><b>适用场景</b>：会话轮次较少、上下文窗口充足的场景。
 * 对于长对话，消息总长度可能超出模型上下文窗口限制，建议配合滑动窗口策略使用。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ContextBuilder
 */
@Component
public class FullWindowContextBuilder implements ContextBuilder {

    /**
     * 构建全量窗口的模型输入消息列表。
     *
     * @param session          当前会话（含消息历史），不可为 null
     * @param memory           长期记忆内容
     * @param toolDefinitions  当前可用的工具定义列表
     * @return 按序排列的消息列表（System → 记忆 → 历史 → 当前请求）
     */
    @Override
    public List<Message> buildContext(Session session, MemoryContent memory,
                                      List<ToolDefinition> toolDefinitions) {
        // 使用 ArrayList 以便按序插入，初始容量预设为会话消息数 + 2（System + 用户请求）
        List<Message> messages = new ArrayList<>(session.getMessages().size() + 2);

        // 步骤1：构建包含工具描述的 System Prompt
        Message systemMessage = buildSystemMessage(toolDefinitions);
        messages.add(systemMessage);

        // 步骤2：如果记忆内容可用且启用，注入长期记忆
        if (memory != null && memory.isEnabled() && memory.getContent() != null) {
            Message memoryMessage = buildMemoryMessage(memory);
            messages.add(memoryMessage);
        }

        // 步骤3：追加会话中的所有历史消息
        messages.addAll(session.getMessages());

        // 步骤4：注入当前请求的消息
        // 注意：当前请求的消息由上层调用方负责追加到 ChatRequest 中，
        // 这个方法是给 ContextBuildStage 用的，它会先更新 Session 再调用。
        // 所以这里 session.getMessages() 已经包含了当前请求消息。

        return messages;
    }

    /**
     * 始终返回 true，作为兜底策略。
     * 当没有其他 ContextBuilder 匹配时，使用全量窗口策略。
     *
     * @param request 用户发起的对话请求
     * @return 始终返回 true
     */
    @Override
    public boolean supports(ChatRequest request) {
        return true;
    }

    /**
     * 构建包含工具描述的 System Prompt 消息。
     *
     * @param toolDefinitions 当前可用的工具定义列表
     * @return System 类型的 Message
     */
    private Message buildSystemMessage(List<ToolDefinition> toolDefinitions) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个智能 AI 助手，可以调用以下工具来完成任务：\n");
        for (ToolDefinition def : toolDefinitions) {
            sb.append("- ").append(def.getName())
                    .append(": ").append(def.getDescription()).append("\n");
        }
        return Message.builder().role("system").content(sb.toString()).build();
    }

    /**
     * 构建包含长期记忆的消息。
     * 使用 {@code <memory>} 标签包裹，让模型知道这是长期记忆而不是当前对话。
     *
     * <p><b>角色选择说明</b>：记忆以 "user" 角色注入而非 "system"，因为
     * DeepSeekOpenAIAdapter.buildMessages() 会过滤掉 role=system 的消息
     * （用 ChatRequest.systemPrompt 替代），导致记忆无法传递给模型。</p>
     *
     * @param memory 长期记忆内容
     * @return User 类型的 Message（记忆以 user 角色注入）
     */
    private Message buildMemoryMessage(MemoryContent memory) {
        String wrapped = "<memory>\n" + memory.getContent() + "\n</memory>";
        return Message.builder().role("user").content(wrapped).build();
    }
}