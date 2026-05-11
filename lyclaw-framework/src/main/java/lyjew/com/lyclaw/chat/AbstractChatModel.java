package lyjew.com.lyclaw.chat;

import lyjew.com.lyclaw.exception.ModelException;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ModelResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;

/**
 * ChatModel 模板基类，提供流式调用的骨架实现。
 *
 * <p>子类只需实现 5 个抽象方法：buildNativeRequest()、sendNativeRequest()、
 * parseChunk()、getDefaultBaseUrl()、getDefaultModel()。
 * 模板方法 stream() 组装完整的调用链：校验→构建原生请求→发送→逐块解析→错误处理。
 *
 * <p>可选的钩子方法：validateRequest()、handleError()、getDefaultApiKey()。
 */
public abstract class AbstractChatModel implements ChatModel {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final String baseUrl;
    protected final String apiKey;
    protected final String model;

    /**
     * @param baseUrl API 端点 URL
     * @param apiKey  API 密钥
     * @param model   模型名称
     */
    protected AbstractChatModel(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl != null ? baseUrl : getDefaultBaseUrl();
        this.apiKey = apiKey != null ? apiKey : getDefaultApiKey();
        this.model = model != null ? model : getDefaultModel();
    }

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

    /** 构建 Provider 原生请求对象 */
    protected abstract Object buildNativeRequest(ChatRequest request);

    /** 发送请求，返回原始响应流（每行一个 chunk） */
    protected abstract Flux<String> sendNativeRequest(Object nativeRequest);

    /** 将原始 chunk 解析为统一 ModelResponse */
    protected abstract ModelResponse parseChunk(String rawChunk);

    /** 获取默认 API 端点 URL */
    protected abstract String getDefaultBaseUrl();

    /** 获取默认模型名称 */
    protected abstract String getDefaultModel();

    // ===== 可选的钩子方法 =====

    /** 获取默认 API 密钥（子类可覆写从系统属性/环境变量获取） */
    protected String getDefaultApiKey() {
        return null;
    }

    /** 请求校验，默认检查 messages 是否非空 */
    protected void validateRequest(ChatRequest request) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw ModelException.of(
                    lyjew.com.lyclaw.enums.ErrorCode.MODEL_INVALID_REQUEST,
                    "消息列表为空，Provider=" + provider());
        }
    }

    /** 流异常处理，默认包装为 ModelException 并终止流 */
    protected void handleError(Throwable error) {
        if (error instanceof ModelException) {
            throw (ModelException) error;
        }
        throw ModelException.of(
                lyjew.com.lyclaw.enums.ErrorCode.MODEL_API_ERROR,
                new RuntimeException(provider() + " API 调用异常: " + error.getMessage(), error));
    }
}
