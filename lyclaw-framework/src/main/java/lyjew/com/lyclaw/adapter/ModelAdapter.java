package lyjew.com.lyclaw.adapter;

import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ModelConfig;
import lyjew.com.lyclaw.model.ModelResponse;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 模型适配器接口，用于统一封装不同LLM提供商（如OpenAI、Claude等）的调用方式。
 * 提供同步/流式对话、Token计数、配置校验以及SSE响应解析等能力。
 * 该接口已废弃，请使用新的适配体系。
 */
@Deprecated
public interface ModelAdapter {

    /**
     * 同步对话：发送聊天请求并阻塞等待模型返回完整响应。
     *
     * @param request 聊天请求，包含消息历史、参数等
     * @return 模型返回的完整响应
     */
    ModelResponse chat(ChatRequest request);

    /**
     * 流式对话：以 SSE（Server‑Sent Events）方式逐步返回模型生成的文本。
     *
     * @param request 聊天请求
     * @return 逐块返回文本的响应式流
     */
    Flux<String> chatStream(ChatRequest request);

    /**
     * 计算给定文本的Token数量，用于计费或上下文窗口控制。
     *
     * @param text 待计数的文本
     * @return Token数量
     */
    int countTokens(String text);

    /**
     * 校验当前适配器的配置是否有效，例如API Key是否可用。
     *
     * @return 配置有效返回 true，否则 false
     */
    boolean validate();

    /**
     * 返回当前适配器所属的提供商名称。
     *
     * @return 提供商名称字符串
     */
    String getProvider();

    /**
     * 判断当前适配器是否已完成配置。
     *
     * @return 已配置返回 true
     */
    boolean isConfigured();

    /**
     * 使用给定的模型配置初始化适配器。
     *
     * @param config 模型配置信息
     */
    void configure(ModelConfig config);

    /**
     * 返回当前使用的模型名称。
     *
     * @return 模型名称
     */
    String getModel();

    /**
     * 返回当前适配器连接的API基础URL。
     *
     * @return 基础URL
     */
    String getBaseUrl();

    /**
     * 从原始SSE响应文本中提取工具调用请求。
     * 默认返回空列表，子类可按需覆写。
     *
     * @param rawSSE 原始SSE文本
     * @return 工具调用请求列表
     */
    default List<ModelResponse.ToolCallRequest> extractSseToolCalls(String rawSSE) {
        return List.of();
    }

    /**
     * 从原始SSE响应文本中提取纯文本内容。
     * 默认返回空字符串，子类可按需覆写。
     *
     * @param rawSSE 原始SSE文本
     * @return 提取到的纯文本
     */
    default String extractSsePlainText(String rawSSE) {
        return "";
    }

    /**
     * 从原始SSE响应文本中提取Token用量信息。
     * 默认返回零值占位字符串，子类可按需覆写。
     *
     * @param rawSSE 原始SSE文本
     * @return 格式为 "prompt=N completion=N total=N" 的用量字符串
     */
    default String extractSseTokenUsage(String rawSSE) {
        return "prompt=0 completion=0 total=0";
    }
}
