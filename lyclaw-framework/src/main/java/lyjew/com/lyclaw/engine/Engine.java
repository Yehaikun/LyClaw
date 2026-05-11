package lyjew.com.lyclaw.engine;

import lyjew.com.lyclaw.model.ChatRequest;
import reactor.core.publisher.Flux;

/**
 * LLM 引擎接口，定义 AI 模型调用的核心契约。
 *
 * <p>Engine 是 LyClaw 框架的模型抽象层，每个 LLM 提供商（如 OpenAI、DeepSeek、
 * 本地模型等）通过实现该接口来接入框架。框架通过 {@link EngineSelector} 根据
 * 请求特征（如目标模型名称）进行自动引擎选择。</p>
 *
 * <p>引擎执行采用响应式编程模型，通过 {@link Flux} 流式返回结果，
 * 支持逐 token 输出，提升用户体验。实现类需标注 {@code @Component}
 * 以被 Spring 容器自动发现。</p>
 *
 * <p>核心契约包括：</p>
 * <ul>
 *   <li>{@link #getName()} - 引擎唯一名称</li>
 *   <li>{@link #supports(ChatRequest)} - 是否支持处理该请求</li>
 *   <li>{@link #execute(ChatRequest)} - 流式执行请求并返回结果</li>
 *   <li>{@link #getMetadata()} - 引擎元数据</li>
 * </ul>
 */
public interface Engine {

    /**
     * 获取引擎的唯一名称，用于日志记录和引擎注册时的标识。
     *
     * @return 引擎名称，如 "openai"、"deepseek"、"local-llm"
     */
    String getName();

    /**
     * 判断该引擎是否支持处理指定的聊天请求。
     * 用于 {@link EngineSelector} 在多个引擎中选择合适的一个。
     *
     * @param request 聊天请求对象，包含目标模型名称、消息历史、生成参数等
     * @return true 表示本引擎可以处理该请求
     */
    boolean supports(ChatRequest request);

    /**
     * 执行聊天请求，以响应式流逐 token 返回生成文本。
     *
     * <p>每个流元素通常为一个 token 或文本片段，调用方通过订阅该 Flux 流
     * 即可逐块获取模型输出，实现流式响应效果。</p>
     *
     * @param request 聊天请求对象，包含模型名称、对话消息列表和生成参数
     * @return 包含逐 token 生成结果的 Flux 流
     */
    Flux<String> execute(ChatRequest request);

    /**
     * 获取该引擎的元数据信息，包括名称、版本、支持的模型及能力标签。
     *
     * @return 包含引擎名称、版本、支持模型列表和能力标签的元数据对象
     */
    EngineMetadata getMetadata();
}
