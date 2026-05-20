package lyjew.com.lyclaw.adapter;

import lyjew.com.lyclaw.annotation.chat.ChatModel;
import lyjew.com.lyclaw.annotation.chat.CircuitBreaker;
import lyjew.com.lyclaw.annotation.chat.ModelCapability;
import lyjew.com.lyclaw.annotation.chat.RetryPolicy;
import lyjew.com.lyclaw.chat.ModelCapabilities;
import lyjew.com.lyclaw.model.ModelConfig;
import org.springframework.context.annotation.Bean;

/**
 * DeepSeek ChatModel 适配器，基于 OpenAI 兼容协议接入 DeepSeek V4 系列大语言模型。
 *
 * <p>DeepSeek 是由深度求索公司开发的大语言模型系列，以其卓越的推理能力、长上下文支持
 * （高达 128K tokens）和极具竞争力的性价比在 AI 领域广受关注。本适配器继承自
 * {@link OpenAiProtocolChatModel}，复用其完整的 OpenAI 协议通信能力和 SSE 解析逻辑，
 * 仅需覆写默认端点 URL 和模型名称即可完成 DeepSeek 的接入，充分体现了框架"配置即
 * Provider"的设计理念。默认使用的模型为 deepseek-v4-flash，该模型在保持高推理质量
 * 的同时提供了更快的响应速度和更低的调用成本。
 *
 * <p>本类通过 Java 注解声明的方式配置了完整的弹性策略，实现了声明式韧性编程模式：
 * <ul>
 *   <li>{@code @ChatModel} — 声明 Provider 元数据（名称、协议类型、默认端点、优先级等），
 *       供框架的模型注册中心自动发现和注册</li>
 *   <li>{@code @ModelCapability} — 声明模型的能力矩阵，包括是否支持流式输出、工具调用、
 *       工具调用流式传输、思考模式（思维链）、视觉识别、提示词缓存，以及最大输入/输出
 *       Token 数，框架据此在运行时进行能力校验和功能开关控制</li>
 *   <li>{@code @RetryPolicy(maxAttempts=3, backoff=EXPONENTIAL)} — 配置指数退避的自动
 *       重试策略，最多重试 3 次，在遇到 429（请求过多）、503（服务不可用）、504（网关
 *       超时）等临时性错误时自动触发</li>
 *   <li>{@code @CircuitBreaker(failureThreshold=5, halfOpenAfterSeconds=30)} — 配置
 *       熔断保护，连续 5 次失败后自动熔断，30 秒后进入半开状态进行探测性恢复</li>
 * </ul>
 * 框架的 ChatModelPostProcessor（Bean 后置处理器）在 Spring 容器启动时扫描这些注解，
 * 自动构建装饰器链：CircuitBreakerChatModel → RetryChatModel →
 * DeepSeekChatModel，形成层层保护的弹性调用链路。
 *
 * <p>特别说明：DeepSeek 的思考模式（Thinking/思维链）目前仅在非流式调用时可用，
 * 即 {@code @ModelCapability} 中 toolCallStreaming 设置为 false。这意味着当启用
 * 工具调用的流式模式时，思考模式将自动降级为关闭状态，框架会根据 ModelCapabilities
 * 声明自动处理此兼容性问题。
 *
 * <p>Phase 2 推理内容处理：DeepSeek API 在流式 SSE 块的 delta 中返回
 * {@code "reasoning_content"} 字段。父类 {@link OpenAiProtocolChatModel#parseChunk(String)}
 * 已将该字段提取到 {@code ModelResponse.thinking} 中，下游的 {@code DefaultReActEngine}
 * 负责将其转换为 {@code event: thinking} SSE 事件发送给前端展示。
 * 调用方可通过 {@link lyjew.com.lyclaw.react.SseEventBuilder#thinkingEvent(String)}
 * 构建标准的 thinking 事件字符串。
 *
 * @see OpenAiProtocolChatModel
 * @see lyjew.com.lyclaw.chat.AbstractChatModel
 */
@ChatModel(
    provider = "deepseek",
    displayName = "DeepSeek",
    description = "DeepSeek V4 系列模型，支持思考模式、工具调用",
    protocol = ChatModel.ModelProtocol.OPENAI,
    defaultModel = "deepseek-v4-flash",
    defaultBaseUrl = "https://api.deepseek.com",
    priority = 100
)
@ModelCapability(
    streaming = true,
    toolCalling = true,
    toolCallStreaming = false,
    thinking = true,
    vision = false,
    promptCaching = false,
    maxInputTokens = 128000,
    maxOutputTokens = 8192
)
@RetryPolicy(
    maxAttempts = 3,
    backoff = RetryPolicy.BackoffStrategy.EXPONENTIAL,
    retryOn = {
        RetryPolicy.HttpStatusHint.TOO_MANY_REQUESTS,
        RetryPolicy.HttpStatusHint.SERVICE_UNAVAILABLE,
        RetryPolicy.HttpStatusHint.GATEWAY_TIMEOUT
    }
)
@CircuitBreaker(failureThreshold = 5, halfOpenAfterSeconds = 30)
public class DeepSeekChatModel extends OpenAiProtocolChatModel {

    /**
     * 使用 {@link ModelConfig} 配置对象创建 DeepSeek ChatModel 实例。
     *
     * <p>通过 ModelConfig 统一配置对象传入 baseUrl、apiKey 和 model 参数，Provider 标识
     * 固定为 "deepseek"。如果 config 中的字段为 null，将自动回退到 DeepSeek 的默认值
     * （端点 https://api.deepseek.com，模型 deepseek-v4-flash）。
     *
     * @param config 模型配置对象，包含 API 端点地址、认证密钥和模型名称
     */
    public DeepSeekChatModel(ModelConfig config) {
        super("deepseek", config);
    }

    /**
     * 带完整参数和自定义能力声明的构造函数，提供最大程度的配置灵活性。
     *
     * <p>适用于需要精确控制所有连接参数和模型能力矩阵的场景。capabilities 参数定义了
     * 该模型实例支持的功能特性（流式、工具调用、思考模式等），如果为 null 则自动使用
     * OpenAI 默认能力集。此构造方式绕过了注解声明的 ModelCapabilities，适合编程式
     * 动态创建模型实例的场景。
     *
     * @param baseUrl      DeepSeek API 端点的基础 URL
     * @param apiKey       DeepSeek API 认证密钥
     * @param model        模型名称，如 "deepseek-v4-flash" 或 "deepseek-v4"
     * @param capabilities 自定义的能力声明对象，null 时使用 OpenAI 默认能力集
     */
    public DeepSeekChatModel(
            String baseUrl, String apiKey, String model, ModelCapabilities capabilities) {
        super("deepseek", baseUrl, apiKey, model, capabilities);
    }

    @Override
    protected String getDefaultBaseUrl() {
        return "https://api.deepseek.com";
    }

    @Override
    protected String getDefaultModel() {
        return "deepseek-v4-flash";
    }
}
