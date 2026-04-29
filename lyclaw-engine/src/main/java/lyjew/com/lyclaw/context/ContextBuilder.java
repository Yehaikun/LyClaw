package lyjew.com.lyclaw.context;

import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ChatRequest;

import java.util.List;

/**
 * 上下文构建策略接口 —— 将原始请求、会话历史、长期记忆转换为模型可理解的消息列表。
 *
 * <p>大语言模型的输入是一个 {@code List<Message>}，包含 system 消息、历史对话消息
 * 和当前用户请求。ContextBuilder 负责组装这些消息，并决定记忆的注入方式。</p>
 *
 * <p><b>设计动机</b>：不同场景需要不同的上下文构建策略——
 * <ul>
 *   <li>全量窗口：把所有历史消息塞进去（简单但浪费 Token）</li>
 *   <li>滑动窗口：只保留最近的 N 轮对话（省 Token，但可能丢失上下文）</li>
 *   <li>摘要窗口：历史超出窗口时，用模型生成摘要替代（省 Token，保留关键信息）</li>
 * </ul>
 * 通过策略模式，ContextBuildStage 遍历所有 ContextBuilder 实现，
 * 调用 {@link #supports(ChatRequest)} 选择第一个匹配的策略。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see FullWindowContextBuilder
 */
public interface ContextBuilder {

    /**
     * 执行上下文构建，返回模型输入消息列表。
     *
     * <p>实现方需要保证：
     * <ol>
     *   <li>System Prompt 放在第一条（角色设定、工具描述）</li>
     *   <li>长期记忆在 System Prompt 之后注入（使用 {@code <memory>} 标签包裹）</li>
     *   <li>历史会话消息按时间顺序排列</li>
     *   <li>当前请求消息放在最后</li>
     *   <li>如果消息总长度超过模型上下文窗口，需要截断或摘要</li>
     * </ol>
     * </p>
     *
     * @param session      当前会话（含消息历史），不可为 null
     * @param memory       长期记忆内容，如果没有记忆则为 {@code MemoryContent} 空实例
     * @param toolDefinitions 当前可用的工具定义列表，模型据此了解可以调用哪些工具
     * @return 构建好的消息列表（不可变，非 null）。空列表表示构建失败
     */
    List<Message> buildContext(Session session, MemoryContent memory,
                               List<ToolDefinition> toolDefinitions);

    /**
     * 判断当前策略是否适用于这个请求。
     * 例如：SlidingWindowContextBuilder 在消息数超过阈值时返回 true，
     * FullWindowContextBuilder 在所有情况下都返回 true（作为兜底）。
     *
     * @param request 用户发起的对话请求
     * @return true 表示适用
     */
    boolean supports(ChatRequest request);
}