package lyjew.com.lyclaw.decorator;

import lyjew.com.lyclaw.annotation.chat.Fallback;
import lyjew.com.lyclaw.chat.ChatModel;
import lyjew.com.lyclaw.chat.ChatModelRegistry;
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
    private final ChatModelRegistry registry;

    public FallbackChatModel(ChatModel delegate, Fallback policy, ChatModelRegistry registry) {
        this.delegate = delegate;
        this.chain = policy.chain();
        this.registry = registry;
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
                        log.warn("{} 调用失败，触发降级链: {}，错误: {}",
                                provider(), String.join(" -> ", chain),
                                error.getMessage(), error);
                        return tryFallbackChain(request);
                    }
                    return Flux.error(error);
                });
    }

    private Flux<ModelResponse> tryFallbackChain(ChatRequest request) {
        return tryNextInChain(request, 0);
    }

    private Flux<ModelResponse> tryNextInChain(ChatRequest request, int index) {
        if (index >= chain.length) {
            log.error("降级链中所有模型均不可用: {}", String.join(",", chain));
            return Flux.error(new IllegalStateException(
                    "所有降级模型均不可用，chain=" + String.join(",", chain)));
        }
        String entry = chain[index].trim();
        int colon = entry.indexOf(':');
        if (colon <= 0) {
            log.warn("降级链条目格式无效 (expected provider:model): {}", entry);
            return tryNextInChain(request, index + 1);
        }
        String provider = entry.substring(0, colon);
        String modelName = entry.substring(colon + 1);
        ChatModel fallbackModel = registry.resolve(provider, modelName);
        if (fallbackModel == null) {
            log.warn("降级模型未找到: {}:{}，尝试下一个", provider, modelName);
            return tryNextInChain(request, index + 1);
        }
        log.info("降级到 {}:{}", provider, modelName);
        return fallbackModel.stream(request)
                .onErrorResume(err -> {
                    log.warn("降级模型 {}:{} 调用失败: {}", provider, modelName, err.getMessage());
                    return tryNextInChain(request, index + 1);
                });
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
