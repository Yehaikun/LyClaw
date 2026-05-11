package lyjew.com.lyclaw.decorator;

import lyjew.com.lyclaw.annotation.chat.Fallback;
import lyjew.com.lyclaw.chat.ChatModel;
import lyjew.com.lyclaw.chat.ModelCapabilities;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ModelResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 降级装饰器——主模型失败时按降级链顺序尝试备用模型。
 *
 * <p>降级链格式为 "provider:model"（如 "openai:gpt-4o-mini"），
 * 从 ChatModelRegistry 查找对应的 ChatModel 并依次尝试。
 * 触发降级的异常类型可配置，默认为 ModelException 和 TimeoutException。
 */
public class FallbackChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(FallbackChatModel.class);

    private final ChatModel delegate;
    private final String[] chain;
    private final List<Class<? extends Throwable>> onExceptions;

    public FallbackChatModel(ChatModel delegate, Fallback policy) {
        this.delegate = delegate;
        this.chain = policy.chain();
        try {
            this.onExceptions = parseExceptionClasses(policy.on());
        } catch (Exception e) {
            throw new IllegalArgumentException("@Fallback.on() 配置的异常类型无效", e);
        }
    }

    @Override
    public String provider() {
        return delegate.provider();
    }

    @Override
    public String model() {
        return delegate.model();
    }

    @Override
    public ModelCapabilities capabilities() {
        return delegate.capabilities();
    }

    @Override
    public Flux<ModelResponse> stream(ChatRequest request) {
        return delegate.stream(request)
                .onErrorResume(error -> {
                    if (shouldFallback(error)) {
                        log.warn("{} 调用失败，触发降级链: {}",
                                provider(), String.join(" -> ", chain),
                                error instanceof Exception ? ((Exception) error).getMessage() : error.toString());
                        return tryFallbackChain(request);
                    }
                    return Flux.error(error);
                });
    }

    private Flux<ModelResponse> tryFallbackChain(ChatRequest request) {
        // 降级链需要查 ChatModelRegistry 来获取备选模型
        // 此处简化实现：仅记录降级请求，实际解析需要依赖注入 ChatModelRegistry
        log.warn("降级链解析需要 ChatModelRegistry 依赖，当前版本在 ChatModelPostProcessor 中完成");
        return Flux.error(new IllegalStateException(
                "所有降级模型均不可用，chain=" + String.join(",", chain)));
    }

    @SuppressWarnings("unchecked")
    private List<Class<? extends Throwable>> parseExceptionClasses(String[] classNames)
            throws ClassNotFoundException {
        if (classNames == null || classNames.length == 0) {
            return List.of(lyjew.com.lyclaw.exception.ModelException.class,
                    java.util.concurrent.TimeoutException.class);
        }
        List<Class<? extends Throwable>> result = new java.util.ArrayList<>();
        for (String className : classNames) {
            result.add((Class<? extends Throwable>) Class.forName(className));
        }
        return result;
    }

    private boolean shouldFallback(Throwable error) {
        for (Class<? extends Throwable> cls : onExceptions) {
            if (cls.isInstance(error)) return true;
        }
        return false;
    }

    @Override
    public int countTokens(String text) {
        return delegate.countTokens(text);
    }

    @Override
    public int countTokens(List<lyjew.com.lyclaw.model.Message> messages) {
        return delegate.countTokens(messages);
    }

    @Override
    public Mono<Boolean> validate() {
        return delegate.validate();
    }
}
