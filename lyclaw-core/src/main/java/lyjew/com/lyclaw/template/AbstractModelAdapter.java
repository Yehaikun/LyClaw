package lyjew.com.lyclaw.template;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.enums.ErrorCode;
import lyjew.com.lyclaw.exception.ModelException;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ModelConfig;
import lyjew.com.lyclaw.model.ModelResponse;
import reactor.core.publisher.Flux;

@Slf4j
public abstract class AbstractModelAdapter implements ModelAdapter {

    protected String apiKey;
    protected String baseUrl;
    protected String model;
    protected boolean configured = false;

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
    public String getModel() { return model; }

    @Override
    public String getBaseUrl() { return baseUrl; }

    @Override
    public ModelResponse chat(ChatRequest request) {
        checkConfigured();
        try {
            beforeCall(request);
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

    protected abstract Object buildRequest(ChatRequest request);

    protected Object buildStreamRequest(ChatRequest request) { return buildRequest(request); }

    protected abstract Object parseResponse(String rawResponse);

    protected abstract ModelResponse toUnifiedResponse(Object apiResponse);

    protected void beforeCall(ChatRequest request) {
        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            throw ErrorCode.MODEL_INVALID_REQUEST.exception("消息列表不能为空");
        }
    }

    protected void afterCall(ModelResponse response) {
        if (response != null && response.getUsage() != null) {
            log.info("[{}] Token用量: prompt={}, completion={}, total={}", getProvider(),
                    response.getUsage().getPromptTokens(), response.getUsage().getCompletionTokens(),
                    response.getUsage().getTotalTokens());
        }
    }

    protected void handleError(Throwable error) {
        if (error instanceof ModelException) {
            throw (ModelException) error;
        }
        throw ModelException.of(ErrorCode.MODEL_API_ERROR, error);
    }

    protected abstract String sendRequest(Object apiRequest);

    protected abstract Flux<String> sendStreamRequest(Object apiRequest);

    protected abstract String getDefaultBaseUrl();

    protected abstract String getDefaultModel();

    private void checkConfigured() {
        if (!isConfigured()) {
            throw ErrorCode.ADAPTER_NOT_CONFIGURED.exception("适配器 [" + getProvider() + "] 尚未配置");
        }
    }
}
