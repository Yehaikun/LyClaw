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
 * 模型适配器模板基类
 *
 * 使用模板方法模式固化对话调用的核心流程骨架，
 * 子类只需实现厂商特定的构建请求、解析响应、转换统一格式三个方法。
 *
 * 设计模式：模板方法模式（Template Method Pattern）
 * - 模板方法：chat()、chatStream()——固化调用流程
 * - 抽象方法：buildRequest()、parseResponse()、toUnifiedResponse()——子类实现
 * - 钩子方法：beforeCall()、afterCall()、handleError()——子类可选重写
 */
@Slf4j
public abstract class AbstractModelAdapter implements ModelAdapter {


    // ========== 配置状态 ==========

    /** API Key */
    protected String apiKey;

    /** API Base URL */
    protected String baseUrl;

    /** 默认模型名 */
    protected String model;

    /** 是否已完成配置 */
    protected boolean configured = false;

    // ========== 实现 ModelAdapter 的元信息方法 ==========

    @Override
    public boolean isConfigured() {
        return configured && apiKey != null && !apiKey.isEmpty();
    }

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
        this.baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : getDefaultBaseUrl();
        this.model = config.getModel() != null && !config.getModel().isEmpty()
                ? config.getModel() : getDefaultModel();
        this.configured = true;
        log.info("[{}] 适配器已配置: model={}, baseUrl={}", getProvider(), this.model, this.baseUrl);
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public String getBaseUrl() {
        return baseUrl;
    }

    // ========== 模板方法：同步对话 ==========

    /**
     * 同步对话的模板方法——固化调用流程骨架
     *
     * 流程：
     * 1. beforeCall()    — 预处理钩子
     * 2. buildRequest()  — 构建厂商特定请求体
     * 3. sendRequest()   — 发送 HTTP 请求
     * 4. parseResponse() — 解析厂商原始响应
     * 5. toUnifiedResponse() — 转换为统一响应
     * 6. afterCall()     — 后处理钩子
     * 7. handleError()   — 出错时调用
     */
    @Override
    public ModelResponse chat(ChatRequest request) {
        checkConfigured();

        try {
            // 步骤1：预处理钩子（默认空实现，子类可重写）
            beforeCall(request);//子类可选实现

            // 步骤2：构建厂商特定的 API 请求体
            Object apiRequest = buildRequest(request); //钩子方法，子类必须实现，否则无法进行
            logRequest(apiRequest);

            // 步骤3：发送 HTTP 请求，获取原始响应字符串，
            String rawResponse = sendRequest(apiRequest);//子类必须实现这个发生请求的方法
            log.debug("[{}] 原始响应: {} 字符", getProvider(),
                    rawResponse != null ? rawResponse.length() : 0);

            // ★ 增加：打印完整原始 JSON（需要时改为 INFO 或 DEBUG 级别查看）
            if (log.isDebugEnabled()) {
                String displayJson = rawResponse != null ? rawResponse : "null";
                // 如果太长就截断显示前 2000 字符
                if (displayJson.length() > 2000) {
                    displayJson = displayJson.substring(0, 2000) + "...(截断)";
                }
                log.debug("[{}] 原始响应JSON:\n{}", getProvider(), displayJson);
            }

            // 步骤4：解析原始响应为厂商特定的响应对象
            Object apiResponse = parseResponse(rawResponse); // 子类必须实现

            // 步骤5：转换为统一的 ModelResponse
            ModelResponse unifiedResponse = toUnifiedResponse(apiResponse); // 子类必须实现

            // 步骤6：后处理钩子（默认空实现，子类可重写）
            afterCall(unifiedResponse); // 子类可选实现

            return unifiedResponse;

        } catch (ModelException e) {
            // 已经是 ModelException，直接抛出
            throw e;
        } catch (Exception e) {
            // 其他异常转成 ModelException
            handleError(e);
            throw e; // handleError 可能已经抛出了异常，这里防止编译错误
        }
    }

    // ========== 模板方法：流式对话 ==========

    @Override
    public Flux<String> chatStream(ChatRequest request) {
        checkConfigured();

        try {
            beforeCall(request);

            Object apiRequest = buildStreamRequest(request); // 子类必须实现
            logRequest(apiRequest);

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

    // ========== 抽象方法——子类必须实现 ==========

    /**
     * 构建厂商特定的 API 请求体
     *
     * @param request 统一请求体
     * @return 厂商特定的请求对象（AnthropicRequest / OpenAIRequest）
     */
    protected abstract Object buildRequest(ChatRequest request);

    /**
     * 构建流式请求体（默认和普通请求一样，部分厂商需要额外参数）
     */
    protected Object buildStreamRequest(ChatRequest request) {
        return buildRequest(request);
    }

    /**
     * 解析厂商原始响应字符串
     *
     * @param rawResponse 厂商返回的原始 JSON 字符串
     * @return 厂商特定的响应对象
     */
    protected abstract Object parseResponse(String rawResponse);

    /**
     * 将厂商响应转换为统一的 ModelResponse
     *
     * @param apiResponse 厂商特定的响应对象
     * @return 统一的 ModelResponse
     */
    protected abstract ModelResponse toUnifiedResponse(Object apiResponse);

    // ========== 钩子方法——子类可选重写 ==========

    /**
     * 调用前的预处理钩子
     * 默认空实现，子类可重写做参数校验、日志记录等
     */
    protected void beforeCall(ChatRequest request) {
        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            throw ErrorCode.MODEL_INVALID_REQUEST.exception("消息列表不能为空");
        }
    }

    /**
     * 调用后的后处理钩子
     * 默认空实现，子类可重写做 Token 记录、限流状态更新等
     */
    protected void afterCall(ModelResponse response) {
        if (response != null && response.getUsage() != null) {
            log.info("[{}] Token用量: prompt={}, completion={}, total={}",
                    getProvider(),
                    response.getUsage().getPromptTokens(),
                    response.getUsage().getCompletionTokens(),
                    response.getUsage().getTotalTokens());
        }
    }

    /**
     * 错误处理钩子
     * 默认根据 HTTP 状态码转换为对应的 ModelException
     */
    protected void handleError(Throwable error) {
        if (error instanceof ModelException) {
            throw (ModelException) error;
        }
        throw ModelException.of(ErrorCode.MODEL_API_ERROR, error);
    }

    /**
     * 发送 HTTP 请求（同步）
     * 子类需要实现具体的 HTTP 调用逻辑
     */
    protected abstract String sendRequest(Object apiRequest);

    /**
     * 发送 HTTP 请求（流式）
     * 子类需要实现具体的 SSE 流调用逻辑
     */
    protected abstract Flux<String> sendStreamRequest(Object apiRequest);

    // ========== 子类需要提供的方法 ==========

    /**
     * 获取默认的 API Base URL
     */
    protected abstract String getDefaultBaseUrl();

    /**
     * 获取默认的模型名称
     */
    protected abstract String getDefaultModel();

    // ========== 私有辅助方法 ==========

    /** 检查是否已完成配置 */
    private void checkConfigured() {
        if (!isConfigured()) {
            throw ErrorCode.ADAPTER_NOT_CONFIGURED.exception(
                    "适配器 [" + getProvider() + "] 尚未配置");
        }
    }

    /** 记录请求信息（脱敏） */
    private void logRequest(Object apiRequest) {
        if (log.isDebugEnabled()) {
            String requestBody = apiRequest != null ? apiRequest.toString() : "null";
            // 脱敏：隐藏 API Key
            requestBody = requestBody.replaceAll("Bearer [^\"]+", "Bearer ***");
            log.debug("[{}] 请求体: {}", getProvider(), requestBody);
        }
    }
}