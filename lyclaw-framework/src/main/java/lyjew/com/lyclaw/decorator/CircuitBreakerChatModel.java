package lyjew.com.lyclaw.decorator;

import lyjew.com.lyclaw.annotation.chat.CircuitBreaker;
import lyjew.com.lyclaw.chat.ChatModel;
import lyjew.com.lyclaw.chat.ModelCapabilities;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ModelResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 熔断装饰器——连续失败 N 次后熔断（拒绝请求），等待一段时间后半开探测。
 *
 * <p>状态机：CLOSED（正常通过→失败累积）→ OPEN（拒绝所有请求→等待 halfOpenAfter）→
 * HALF_OPEN（允许有限个请求通过→判断是否恢复或重新熔断）。
 *
 * <p>线程安全，使用 AtomicReference 管理状态。
 */
public class CircuitBreakerChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerChatModel.class);

    private static final String STATE_CLOSED = "CLOSED";
    private static final String STATE_OPEN = "OPEN";
    private static final String STATE_HALF_OPEN = "HALF_OPEN";

    private final ChatModel delegate;
    private final int failureThreshold;
    private final long halfOpenAfterMs;
    private final int halfOpenMaxRequests;

    private final AtomicReference<String> state = new AtomicReference<>(STATE_CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong openedAt = new AtomicLong(0);
    private final AtomicInteger halfOpenAttempts = new AtomicInteger(0);

    public CircuitBreakerChatModel(ChatModel delegate, CircuitBreaker config) {
        this.delegate = delegate;
        this.failureThreshold = config.failureThreshold();
        this.halfOpenAfterMs = config.halfOpenAfterSeconds() * 1000L;
        this.halfOpenMaxRequests = config.halfOpenMaxRequests();
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
        String currentState = checkAndTransitionState();
        return switch (currentState) {
            case STATE_CLOSED ->
                delegate.stream(request)
                        .doOnNext(chunk -> failureCount.set(0))
                        .doOnError(error -> {
                            int failures = failureCount.incrementAndGet();
                            log.warn("{} 调用失败 ({}/{})", provider(), failures, failureThreshold);
                            if (failures >= failureThreshold) {
                                state.set(STATE_OPEN);
                                openedAt.set(System.currentTimeMillis());
                                log.warn("{} 熔断器打开: 连续 {} 次失败", provider(), failures);
                            }
                        });
            case STATE_HALF_OPEN -> {
                log.info("{} 半开状态探测 ({} of {})",
                        provider(), halfOpenAttempts.get() + 1, halfOpenMaxRequests);
                yield delegate.stream(request)
                        .doOnNext(chunk -> {
                            // 半开状态下的成功——恢复
                            state.set(STATE_CLOSED);
                            failureCount.set(0);
                            halfOpenAttempts.set(0);
                            log.info("{} 熔断器恢复: 半开探测成功", provider());
                        })
                        .doOnError(error -> {
                            int halfAttempts = halfOpenAttempts.incrementAndGet();
                            if (halfAttempts >= halfOpenMaxRequests) {
                                state.set(STATE_OPEN);
                                openedAt.set(System.currentTimeMillis());
                                halfOpenAttempts.set(0);
                                log.warn("{} 熔断器重新打开: 半开探测全部失败", provider());
                            }
                        });
            }
            default ->
                Flux.error(new IllegalStateException(
                        "CircuitBreaker " + provider() + " 已熔断，拒绝请求"));
        };
    }

    /**
     * 检查并执行状态转换。
     * OPEN 状态超过 halfOpenAfterMs → HALF_OPEN。
     */
    private String checkAndTransitionState() {
        String current = state.get();
        if (STATE_OPEN.equals(current)) {
            long elapsed = System.currentTimeMillis() - openedAt.get();
            if (elapsed >= halfOpenAfterMs) {
                state.compareAndSet(STATE_OPEN, STATE_HALF_OPEN);
                halfOpenAttempts.set(0);
                log.info("{} 熔断器进入半开状态 (打开 {}ms 后)", provider(), elapsed);
                return STATE_HALF_OPEN;
            }
        }
        return state.get();
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
