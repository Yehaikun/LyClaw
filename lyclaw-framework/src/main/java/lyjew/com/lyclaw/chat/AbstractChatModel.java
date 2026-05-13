package lyjew.com.lyclaw.chat;

import lyjew.com.lyclaw.exception.ModelException;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ModelResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;

/**
 * ChatModel 模板方法模式基类，为所有 AI 大模型接入提供了流式调用的标准骨架实现。
 *
 * <p>作为整个框架的模型层抽象根基，本类采用经典的 GoF 模板方法（Template Method）设计模式，
 * 将一次完整的 AI 模型流式调用流程固化为不可变的算法骨架，同时将 Provider 特定的实现细节
 * 推迟到子类中。模板方法 {@link #stream(ChatRequest)} 定义了标准的请求处理流水线：首先调用
 * {@link #validateRequest(ChatRequest)} 进行请求合法性校验（默认检查消息列表非空，子类可覆写
 * 添加 Provider 特定校验）；然后调用 {@link #buildNativeRequest(ChatRequest)} 将框架统一请求
 * 转换为 Provider 原生格式；接着通过 {@link #sendNativeRequest(Object)} 发起 HTTP 流式请求
 * 获取响应数据流；对数据流中的每个数据块调用 {@link #parseChunk(String)} 将原始文本解析为
 * 统一的 {@link ModelResponse} 对象；最后由 {@link #handleError(Throwable)} 统一处理异常
 * 并终止响应流，确保上游调用者获得一致的错误处理体验。
 *
 * <p>子类必须实现的五个核心抽象方法：
 * <ul>
 *   <li>{@link #buildNativeRequest(ChatRequest)} — 将框架内部的统一请求模型转换为 Provider
 *       原生 API 格式的请求体，是协议适配的关键步骤</li>
 *   <li>{@link #sendNativeRequest(Object)} — 向 Provider 端点发送 HTTP 请求并返回原始
 *       响应数据流（每个元素为一行未解析的原始文本）</li>
 *   <li>{@link #parseChunk(String)} — 将原始数据块解析为框架统一的 ModelResponse 对象，
 *       处理流式/非流式两种响应格式</li>
 *   <li>{@link #getDefaultBaseUrl()} — 返回 Provider 默认的 API 端点地址，当构造参数
 *       未指定 baseUrl 时使用此默认值</li>
 *   <li>{@link #getDefaultModel()} — 返回 Provider 默认使用的模型名称，当构造参数
 *       未指定 model 时使用此默认值</li>
 * </ul>
 *
 * <p>可选的钩子方法（子类可按需覆写以注入自定义行为）：
 * <ul>
 *   <li>{@link #getDefaultApiKey()} — 返回默认 API 密钥，可从系统属性或环境变量获取</li>
 *   <li>{@link #validateRequest(ChatRequest)} — 请求级别的自定义校验逻辑</li>
 *   <li>{@link #handleError(Throwable)} — 自定义的流异常处理策略</li>
 * </ul>
 *
 * <p>构造器采用防御式编程：baseUrl、apiKey、model 三个字段在传入 null 值时均自动回退
 * 到各自的 getDefault*() 方法获取默认值，确保对象始终处于可用状态。
 *
 * @see OpenAiProtocolChatModel
 * @see lyjew.com.lyclaw.model.ChatRequest
 * @see lyjew.com.lyclaw.model.ModelResponse
 */
public abstract class AbstractChatModel implements ChatModel {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final String baseUrl;
    protected final String apiKey;
    protected final String model;

    /**
     * 构造 ChatModel 实例，所有参数支持 null 值自动回退到默认值。
     *
     * <p>当 baseUrl、apiKey 或 model 参数为 null 时，构造器会自动调用对应的
     * getDefault*() 方法获取默认值，确保实例始终处于可工作状态。这种设计允许子类
     * 在构造时仅传入需要覆盖的参数，其余参数使用 Provider 预定义的默认值，简化了
     * 子类的构造逻辑和配置复杂度。
     *
     * @param baseUrl API 端点的基础 URL 地址，null 时使用子类定义的默认端点
     * @param apiKey  用于 API 认证的密钥字符串，null 时使用子类定义的默认密钥
     * @param model   默认使用的模型名称，null 时使用子类定义的默认模型
     */
    protected AbstractChatModel(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl != null ? baseUrl : getDefaultBaseUrl();
        this.apiKey = apiKey != null ? apiKey : getDefaultApiKey();
        this.model = model != null ? model : getDefaultModel();
    }

    /**
     * 模板方法：执行完整的 AI 模型流式调用流程，返回响应式的 {@link ModelResponse} 流。
     *
     * <p>该方法是模板方法模式的核心，封装了以下不可变的调用骨架：
     * <ol>
     *   <li>调用 {@link #validateRequest(ChatRequest)} 进行请求校验</li>
     *   <li>调用 {@link #buildNativeRequest(ChatRequest)} 构建原生请求</li>
     *   <li>调用 {@link #sendNativeRequest(Object)} 发送 HTTP 流式请求</li>
     *   <li>对每个原始数据块调用 {@link #parseChunk(String)} 解析为 ModelResponse</li>
     *   <li>流完成时记录 debug 日志，流异常时通过 {@link #handleError(Throwable)} 处理</li>
     * </ol>
     * 使用 {@code Flux.defer()} 确保每次订阅都重新执行完整流程，避免冷发布问题。
     *
     * @param request 聊天请求对象，包含消息历史、工具定义、采样参数等
     * @return 响应式的 ModelResponse 流，每个元素为一个解析后的响应块
     */
    @Override
    public Flux<ModelResponse> stream(ChatRequest request) {
        return Flux.defer(() -> {
            validateRequest(request);
            Object nativeRequest = buildNativeRequest(request);
            return sendNativeRequest(nativeRequest)
                    .map(this::parseChunk)
                    .doOnComplete(() -> log.debug("{} stream completed", provider()))
                    .doOnError(this::handleError);
        });
    }

    // ===== 子类需实现的 5 个抽象方法 =====

    /**
     * 将框架内部的统一聊天请求对象构建为 Provider 原生协议格式的请求体。
     *
     * <p>这是协议适配的核心步骤。不同 AI Provider（OpenAI、DeepSeek、Groq 等）虽然
     * 大多遵循 OpenAI 兼容协议，但在请求体的细节上可能存在差异。子类需在此方法中完成
     * 消息格式转换、工具定义序列化、采样参数映射、思考模式配置注入等所有协议适配工作。
     *
     * @param request 框架内部的统一聊天请求对象
     * @return Provider 原生协议格式的请求体对象（通常为 Map 或自定义 DTO）
     */
    protected abstract Object buildNativeRequest(ChatRequest request);

    /**
     * 向 Provider API 端点发送 HTTP 请求并返回原始响应数据流。
     *
     * <p>该方法负责实际的网络通信，使用 HTTP Client（如 WebClient、OkHttp 等）发起
     * 流式 POST 请求。返回的 Flux 中每个元素代表一个未解析的原始数据块（通常为一行
     * SSE 格式的字符串）。子类需在此方法中处理 HTTP 错误状态码、设置超时时间、
     * 配置认证头等网络层面的关注点。
     *
     * @param nativeRequest 由 {@link #buildNativeRequest(ChatRequest)} 构建的原生请求体
     * @return 原始响应数据流，每个元素为一行的文本数据（如 SSE 数据行）
     */
    protected abstract Flux<String> sendNativeRequest(Object nativeRequest);

    /**
     * 将 Provider 返回的原始数据块解析为框架统一的 {@link ModelResponse} 对象。
     *
     * <p>不同 Provider 的响应格式可能存在差异（如字段命名、嵌套结构、特殊标记等），
     * 子类需在此方法中完成 JSON 解析、字段提取、数据转换等操作，产出统一的
     * ModelResponse 实例。返回的 ModelResponse 由模板方法 stream() 自动流式发射给上游调用者。
     *
     * @param rawChunk Provider 返回的原始数据块字符串（如 "data: {...}"）
     * @return 统一格式的 ModelResponse 对象
     */
    protected abstract ModelResponse parseChunk(String rawChunk);

    /**
     * 返回此 Provider 的默认 API 端点基础 URL，当构造参数未指定 baseUrl 时使用。
     *
     * <p>每个 Provider 子类应覆写此方法返回其官方 API 端点地址，例如 OpenAI 为
     * "https://api.openai.com"，DeepSeek 为 "https://api.deepseek.com"。
     *
     * @return Provider 的默认 API 端点基础 URL
     */
    protected abstract String getDefaultBaseUrl();

    /**
     * 返回此 Provider 的默认模型名称，当构造参数未指定 model 时使用。
     *
     * <p>每个 Provider 子类应覆写此方法返回其推荐的默认模型名称，例如 OpenAI 为
     * "gpt-4o"，DeepSeek 为 "deepseek-v4-flash"。默认模型的选取应综合考虑性能、
     * 成本和可用性。
     *
     * @return Provider 的默认模型名称
     */
    protected abstract String getDefaultModel();

    // ===== 可选的钩子方法 =====

    /**
     * 返回此 Provider 的默认 API 密钥，当构造参数未指定 apiKey 时使用。
     *
     * <p>默认实现返回 null，子类可覆写此方法从系统属性（如 "OPENAI_API_KEY"）、
     * 环境变量或配置中心动态获取密钥，实现密钥与代码的分离管理。
     *
     * @return 默认 API 密钥字符串，null 表示无默认密钥
     */
    protected String getDefaultApiKey() {
        return null;
    }

    /**
     * 对聊天请求进行前置校验，默认实现检查消息列表是否非空。
     *
     * <p>子类可覆写此方法添加 Provider 特定的校验逻辑，如检查 token 数量是否
     * 超出上下文窗口、验证工具定义格式是否合规、检查模型名称是否有效等。
     * 校验失败应抛出 {@link ModelException} 以触发框架的统一错误处理。
     *
     * @param request 待校验的聊天请求对象
     * @throws ModelException 当请求不满足校验条件时
     */
    protected void validateRequest(ChatRequest request) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw ModelException.of(
                    lyjew.com.lyclaw.enums.ErrorCode.MODEL_INVALID_REQUEST,
                    "消息列表为空，Provider=" + provider());
        }
    }

    /**
     * 处理流式调用过程中产生的异常，默认实现将 {@link ModelException} 直接抛出，
     * 将其他异常包装为 ModelException。
     *
     * <p>子类可覆写此方法实现自定义的异常处理策略，如按异常类型分类记录日志、
     * 触发告警、执行降级逻辑等。此方法在 Reactor 流的 doOnError 回调中调用，
     * 抛出异常会终止当前流。
     *
     * @param error 流处理过程中捕获的异常对象
     * @throws ModelException 包装后的模型异常或原始 ModelException
     */
    protected void handleError(Throwable error) {
        if (error instanceof ModelException) {
            throw (ModelException) error;
        }
        throw ModelException.of(
                lyjew.com.lyclaw.enums.ErrorCode.MODEL_API_ERROR,
                new RuntimeException(provider() + " API 调用异常: " + error.getMessage(), error));
    }
}
