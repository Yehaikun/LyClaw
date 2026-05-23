package lyjew.com.lyclaw.context;

import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.ToolDefinition;

import java.util.List;

/**
 * 上下文构建器接口，负责根据会话、记忆和工具信息构建发送给 AI 模型的消息列表。
 *
 * 不同的 AI 模型对消息结构的格式要求不同（如 Anthropic 的 Messages API 与
 * OpenAI 的 Chat Completions API），因此可以通过实现此接口来适配不同模型。
 * 构建器通过会话历史消息、持久化记忆内容和可用工具定义，组装出最终发给模型
 * 的完整上下文消息列表。{@link #supports(ChatRequest)} 用于判断当前构建器
 * 是否适用于给定的请求。
 */
public interface ContextBuilder {

    /**
     * 根据会话和工具定义构建上下文消息列表。
     * TODO: 记忆系统重新设计后恢复 MemoryContent 参数
     *
     * @param session          当前会话，包含历史消息
     * @param toolDefinitions  可用工具定义列表，用于告知模型可调用的工具
     * @return 发送给 AI 模型的消息列表
     */
    List<Message> buildContext(Session session,
                               List<ToolDefinition> toolDefinitions);

    /**
     * 判断当前上下文构建器是否支持处理给定的请求。
     * 通常根据请求中指定的模型名称或提供商类型来判断。
     *
     * @param request 聊天请求
     * @return true 表示支持此请求，false 表示应尝试其他构建器
     */
    boolean supports(ChatRequest request);
}
