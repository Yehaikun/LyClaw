package lyjew.com.lyclaw.template;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.enums.ErrorCode;
import lyjew.com.lyclaw.exception.ModelException;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ModelConfig;
import lyjew.com.lyclaw.model.ModelResponse;
import reactor.core.publisher.Flux;

/**
 * 模型适配器抽象基类，提供 LLM API 调用的模板方法骨架。
 *
 * <p>封装了同步/流式调用的通用流程：配置校验 → 请求构建 → 发送 → 响应解析 → 后处理。
 * 子类只需实现具体的请求构建、响应解析、HTTP 通信等抽象方法即可接入新的 LLM 提供商。</p>
 *
 * <p>调用链：chat() → {checkConfigured → buildRequest → sendRequest → parseResponse → toUnifiedResponse}
 * 流式链：chatStream() → {checkConfigured → buildStreamRequest → sendStreamRequest}</p>
 */
@Slf4j
public abstract class AbstractModelAdapter implements ModelAdapter {

    /** API 密钥 */
    protected String apiKey;
    /** API 基础 URL */
    protected String baseUrl;
    /** 模型名称 */
    protected String model;
    /** 适配器是否已完成配置 */
    protected boolean configured = false;

    /**
     * 检查适配器是否已完成配置且 API Key 有效。
     *
     * @return 已配置且 API Key 非空时返回 true
     */
    @Override
    public boolean isConfigured() {
        return configured && apiKey != null && !apiKey.isEmpty();
    }

    /**
     * 使用 ModelConfig 初始化适配器，验证 provider 匹配性后注入配置参数。
     *
     * @param config 模型配置对象
     * @throws LyClawException 当 config 为 null 或 provider 不匹配时抛出
     */
    @Override
    public void configure(ModelConfig config) {
        if (config == null) {
            throw ErrorCode.ADAPTER_NOT_CONFIGURED.exception("ModelConfig 为 null");
        }
        if (!getProvider().equals(config.getProvider())) {
            throw ErrorCode.ADAPTER_NOT_CONFIGURED.exception(
                    "Provider 不匹配：期望 " + getProvider() + "，实际 " + config.getProvider());
        }
        this.apiKey = config.getApiKey();
        // baseUrl 和 model 优先使用配置值，未配置时使用子类提供的默认值
        this.baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : getDefaultBaseUrl();
        this.model = config.getModel() != null && !config.getModel().isEmpty()
                ? config.getModel() : getDefaultModel();
        this.configured = true;
        log.info("[{}] 适配器已配置: model={}, baseUrl={}", getProvider(), this.model, this.baseUrl);
    }

    /** @return 当前使用的模型名称 */
    @Override
    public String getModel() { return model; }

    /** @return 当前使用的 API 基础 URL */
    @Override
    public String getBaseUrl() { return baseUrl; }

    /**
     * 同步调用 LLM，走完整请求-响应流水线。
     *
     * @param request 聊天请求
     * @return 统一格式的模型响应；若请求本身是流式的，返回 null（应改用 chatStream）
     */
    @Override
    public ModelResponse chat(ChatRequest request) {
        checkConfigured();
        try {
            beforeCall(request);
            // 根据流式标记选择请求构建方式
            Object apiRequest = request.isStream() ? buildStreamRequest(request) : buildRequest(request);
            if (request.isStream()) {
                return null;
            }
            String rawResponse = sendRequest(apiRequest);
            Object apiResponse = parseResponse(rawResponse);
            ModelResponse unifiedResponse = toUnifiedResponse(apiResponse);
            afterCall(unifiedResponse);
            return unifiedResponse;
        } catch (ModelException e) {
            throw e;
        } catch (Exception e) {
            handleError(e);
            throw e;
        }
    }

    /**
     * 流式调用 LLM，返回 SSE 事件流。
     *
     * @param request 聊天请求
     * @return 流式响应的 Flux 序列，每个元素为一条 SSE 数据
     */
    @Override
    public Flux<String> chatStream(ChatRequest request) {
        checkConfigured();
        try {
            beforeCall(request);
            Object apiRequest = buildStreamRequest(request);
            return sendStreamRequest(apiRequest)
                    .doOnError(error -> {
                        log.error("[{}] 流式请求失败", getProvider(), error);
                        handleError(error);
                    });
        } catch (ModelException e) {
            return Flux.error(e);
        } catch (Exception e) {
            handleError(e);
            return Flux.error(e);
        }
    }

    /**
     * 构建同步 API 请求对象（子类必须实现）。
     *
     * @param request 统一聊天请求
     * @return 特定提供商格式的请求对象
     */
    protected abstract Object buildRequest(ChatRequest request);

    /**
     * 构建流式 API 请求对象，默认同同步请求（子类可覆写以添加 stream=true 参数）。
     *
     * @param request 统一聊天请求
     * @return 特定提供商格式的流式请求对象
     */
    protected Object buildStreamRequest(ChatRequest request) { return buildRequest(request); }

    /**
     * 解析 API 返回的原始 JSON 字符串（子类必须实现）。
     *
     * @param rawResponse API 原始响应字符串
     * @return 特定提供商格式的响应对象
     */
    protected abstract Object parseResponse(String rawResponse);

    /**
     * 将提供商特有响应转换为统一的 ModelResponse（子类必须实现）。
     *
     * @param apiResponse 特定提供商格式的响应对象
     * @return 统一格式的模型响应
     */
    protected abstract ModelResponse toUnifiedResponse(Object apiResponse);

    /**
     * 调用前钩子：校验请求参数有效性。
     *
     * @param request 聊天请求
     */
    protected void beforeCall(ChatRequest request) {
        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            throw ErrorCode.MODEL_INVALID_REQUEST.exception("消息列表不能为空");
        }
    }

    /**
     * 调用后钩子：记录 Token 消耗日志。
     *
     * @param response 模型响应
     */
    protected void afterCall(ModelResponse response) {
        if (response != null && response.getUsage() != null) {
            log.info("[{}] Token用量: prompt={}, completion={}, total={}", getProvider(),
                    response.getUsage().getPromptTokens(), response.getUsage().getCompletionTokens(),
                    response.getUsage().getTotalTokens());
        }
    }

    /**
     * 统一错误处理：ModelException 直接抛出，其他异常包装为 MODEL_API_ERROR。
     *
     * @param error 捕获的异常
     */
    protected void handleError(Throwable error) {
        if (error instanceof ModelException) {
            throw (ModelException) error;
        }
        throw ModelException.of(ErrorCode.MODEL_API_ERROR, error);
    }

    /**
     * 发送同步 HTTP 请求并返回原始响应字符串（子类必须实现）。
     *
     * @param apiRequest 特定提供商格式的请求对象
     * @return API 原始响应字符串
     */
    protected abstract String sendRequest(Object apiRequest);

    /**
     * 发送流式 HTTP 请求并返回 SSE 事件流（子类必须实现）。
     *
     * @param apiRequest 特定提供商格式的流式请求对象
     * @return SSE 事件流
     */
    protected abstract Flux<String> sendStreamRequest(Object apiRequest);

    /**
     * 获取该提供商的默认 API 基础 URL（子类必须实现）。
     *
     * @return 默认基础 URL
     */
    protected abstract String getDefaultBaseUrl();

    /**
     * 获取该提供商的默认模型名称（子类必须实现）。
     *
     * @return 默认模型名称
     */
    protected abstract String getDefaultModel();

    /** 前置校验：若适配器未配置则抛出异常 */
    private void checkConfigured() {
        if (!isConfigured()) {
            throw ErrorCode.ADAPTER_NOT_CONFIGURED.exception("适配器 [" + getProvider() + "] 尚未配置");
        }
    }
}
