package lyjew.com.lyclaw.decorator;

import lyjew.com.lyclaw.annotation.chat.RetryPolicy;
import lyjew.com.lyclaw.chat.ChatModel;
import lyjew.com.lyclaw.chat.ModelCapabilities;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ModelResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 重试装饰器——封装 ChatModel 的流式调用，失败时按配置的重试策略自动重试。
 *
 * <p>支持 FIXED、EXPONENTIAL、LINEAR 三种退避策略，以及可配置的抖动因子。
 * 重试仅在 sendNativeRequest 出现异常时触发，业务逻辑层错误不重试。
 */
public class RetryChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(RetryChatModel.class);

    private final ChatModel delegate;
    private final int maxAttempts;
    private final long baseDelayMs;
    private final RetryPolicy.BackoffStrategy backoff;
    private final double jitter;

    public RetryChatModel(ChatModel delegate, RetryPolicy policy) {
        this.delegate = delegate;
        this.maxAttempts = policy.maxAttempts();
        this.baseDelayMs = policy.baseDelayMs();
        this.backoff = policy.backoff();
        this.jitter = policy.jitter();
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
        return Mono.just(request)
                .flatMapMany(req -> streamWithRetry(req, 1));
    }

    private Flux<ModelResponse> streamWithRetry(ChatRequest request, int attempt) {
        return delegate.stream(request)
                .onErrorResume(error -> {
                    if (attempt < maxAttempts) {
                        long delay = computeDelay(attempt);
                        log.warn("{} 流式调用失败 (第 {} 次重试)，{}ms 后重试: {}",
                                provider(), attempt, delay, error.getMessage());
                        return Mono.delay(Duration.ofMillis(delay))
                                .thenMany(streamWithRetry(request, attempt + 1));
                    }
                    log.error("{} 流式调用重试 {} 次后仍失败", provider(), maxAttempts, error);
                    return Flux.error(error);
                });
    }

    private long computeDelay(int attempt) {
        long delay;
        switch (backoff) {
            case FIXED:
                delay = baseDelayMs;
                break;
            case LINEAR:
                delay = baseDelayMs * attempt;
                break;
            case EXPONENTIAL:
            default:
                delay = baseDelayMs * (1L << (attempt - 1));
                break;
        }
        // 抖动：[delay * (1-jitter), delay * (1+jitter)]
        double jitterRange = delay * jitter;
        double jitteredDelay = delay + ThreadLocalRandom.current().nextDouble(-jitterRange, jitterRange);
        return Math.max(0, (long) jitteredDelay);
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
