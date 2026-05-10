package lyjew.com.lyclaw.adapter;

import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ModelConfig;
import lyjew.com.lyclaw.model.ModelResponse;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 模型适配器策略接口
 *
 * 定义所有厂商适配器必须遵守的统一调用协议。
 * 上层业务（Agent调度模块）只依赖这个接口，不感知底层调用的是哪个厂商。
 *
 * 设计模式：策略模式（Strategy Pattern）
 * 每个厂商的适配器都是这个接口的一种策略实现
 */
public interface ModelAdapter {

    // ========== 核心调用方法 ==========

    /**
     * 同步对话——发送消息并等待完整回复
     *
     * @param request 统一请求体（由上层构建，包含消息历史、工具列表、系统提示等）
     * @return 统一的模型响应（文本内容 + 工具调用 + Token用量等）
     * @throws lyjew.com.lyclaw.exception.ModelException 调用失败时抛出
     */
    ModelResponse chat(ChatRequest request);


    /**
     * 流式对话——以 SSE 流的方式逐步返回 token
     *
     * @param request 统一请求体
     * @return 响应式的字符串流，每个元素是一个 token 片段
     * @throws lyjew.com.lyclaw.exception.ModelException 调用失败时抛出
     */
    Flux<String> chatStream(ChatRequest request);

    // ========== 工具方法 ==========

    /**
     * 估算文本的 Token 数量
     * 优先使用厂商 API 计数，不支持时使用本地估算算法
     *
     * @param text 要估算的文本
     * @return Token 数量的估算值
     */
    int countTokens(String text);

    /**
     * 验证 API Key 是否有效
     * 实现方式：向厂商 API 发送一个最小 Token 的请求（如 1-token completion）
     *
     * @return true 表示 Key 有效且网络可达
     */
    boolean validate();

    // ========== 元信息方法 ==========

    /**
     * 获取厂商标识名
     * 这个值也是 ModelAdapterFactory 中注册的 key
     *
     * @return 厂商名，如 "minimax"、"deepseek-openai"、"deepseek-anthropic"
     */
    String getProvider();

    /**
     * 当前适配器是否已完成配置
     * 只有配置完成（apiKey、baseUrl 等已设置）后才能调用 chat/chatStream
     *
     * @return true 表示已配置好，可以调用
     */
    boolean isConfigured();

    /**
     * 注入模型配置
     * 从 ModelConfig（存储层读取的配置）中提取 apiKey、baseUrl、model 等参数
     *
     * @param config 存储在 configs/ 目录下的模型配置
     */
    void configure(ModelConfig config);

    /**
     * 获取当前使用的模型名称
     *
     * @return 模型名，如 "MiniMax-M2.7"、"deepseek-v4-pro"
     */
    String getModel();

    /**
     * 获取当前使用的 API Base URL
     *
     * @return API 端点地址
     */
    String getBaseUrl();

    // ========== SSE 数据解析方法（default 实现，适配器可选覆写） ==========

    /**
     * 从 SSE 数据流中提取工具调用请求。
     * 不同厂商 SSE 格式不同，各适配器应实现自己的解析逻辑。
     *
     * @param rawSSE 一轮完整的 SSE 输出（含 data: 前缀和 [DONE] 标记）
     * @return 工具调用请求列表，无工具调用时返回空列表
     */
    default List<ModelResponse.ToolCallRequest> extractSseToolCalls(String rawSSE) {
        return List.of();
    }

    /**
     * 从 SSE 数据流中提取纯文本（拼接 delta.content）。
     *
     * @param rawSSE 原始 SSE 输出
     * @return 纯文本内容，无文本返回空串
     */
    default String extractSsePlainText(String rawSSE) {
        return "";
    }

    /**
     * 从 SSE 数据流中提取 Token 用量。
     * 从最后一个含有 "usage" 字段的 SSE chunk 中提取。
     *
     * @param rawSSE 原始 SSE 输出
     * @return 格式如 "prompt=6 completion=32 total=38"，无信息时返回 "prompt=0 completion=0 total=0"
     */
    default String extractSseTokenUsage(String rawSSE) {
        return "prompt=0 completion=0 total=0";
    }
}