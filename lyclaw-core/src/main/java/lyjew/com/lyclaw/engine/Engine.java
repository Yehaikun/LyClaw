package lyjew.com.lyclaw.engine;

import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.model.ChatRequest;
import reactor.core.publisher.Flux;

/**
 * 引擎顶层接口 — AI 对话处理的统一入口。
 *
 * <p>每个 Engine 实现代表一种独立的对话处理策略。EngineSelector 遍历所有
 * 注册的 Engine，调用 {@link #supports(ChatRequest)} 选择第一个匹配的引擎，
 * 然后通过 {@link #execute(ChatRequest)} 执行对话。</p>
 *
 * <p><b>设计动机</b>：不同的对话场景需要不同的处理逻辑——
 * 普通对话走 Pipeline 管道、推理任务走 Chain-of-Thought、
 * RAG 查询走检索增强流程。通过策略模式将这些逻辑解耦到独立的 Engine 实现中，
 * 新增场景只需新建类实现 Engine 接口 + {@code @Component} 自动注册。</p>
 *
 * <p><b>调用链路</b>：
 * <ol>
 *   <li>Controller 构建 ChatRequest</li>
 *   <li>EngineSelector.select(request) 返回匹配的 Engine</li>
 *   <li>Engine.execute(request) 执行对话</li>
 *   <li>返回 Flux&lt;String&gt; 流式结果给上层消费</li>
 * </ol>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see EngineSelector
 * @see EngineMetadata
 */
public interface Engine {

    /**
     * 返回引擎的唯一标识名称，如 "default"、"reasoning"、"planning"。
     * 用于日志、监控和 EngineSelector 的调试输出。
     *
     * @return 引擎名称（非 null）
     */
    String getName();

    /**
     * 判断当前引擎是否支持处理这个请求。
     * EngineSelector 会遍历所有注册的 Engine，返回第一个 supports() 返回 true 的引擎。
     * 这就使得引擎选择逻辑完全由引擎自己决定，而不是由一个中心化的 if-else 判断。
     *
     * <p>实现示例：一个 ReasoningEngine 可以检查请求中是否包含 "reason"、"think" 关键词，
     * DefaultEngine 则始终返回 true 作为兜底。</p>
     *
     * @param request 用户发起的对话请求
     * @return true 表示当前引擎可以处理该请求
     */
    boolean supports(ChatRequest request);

    /**
     * 执行对话，返回流式响应。
     * 使用 Flux 而不是 List，是因为模型调用本身是流式的（逐 token 返回），
     * 引擎应该保持这种流式特性，让上层可以实时消费。
     *
     * <p>实现方必须保证：
     * <ul>
     *   <li>不阻塞调用线程</li>
     *   <li>内部错误通过 Flux.error() 传播</li>
     *   <li>执行完毕自动 complete</li>
     * </ul>
     * </p>
     *
     * @param request 用户发起的对话请求，包含消息列表、会话 ID、配置参数
     * @return Flux 流式响应，每个元素是一个文本块
     */
    Flux<String> execute(ChatRequest request);

    /**
     * 获取引擎的元信息，包括名称、版本、描述、支持的能力列表。
     * 用于运维监控面板展示和管理界面选择引擎。
     *
     * @return 引擎元信息（不可变，非 null）
     */
    EngineMetadata getMetadata();
}