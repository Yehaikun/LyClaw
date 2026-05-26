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
 * 熔断装饰器（Circuit Breaker Decorator），为 ChatModel 提供故障隔离和自动恢复能力。
 *
 * <p>本装饰器实现了经典的断路器模式（Circuit Breaker Pattern），用于防止级联故障和
 * 保护底层 AI 服务免受持续的压力。当底层 ChatModel 连续调用失败达到预设阈值时，断路器
 * 自动"跳闸"进入 OPEN 状态，直接拒绝所有后续请求而不实际发送网络调用，从而快速失败
 * 并给下游服务恢复时间。经过一段冷却时间后，断路器进入 HALF_OPEN 半开状态进行探测性
 * 请求，如果探测成功则恢复为 CLOSED 正常状态，如果探测失败则重新进入 OPEN 状态。
 *
 * <p>状态机详细说明：
 * <ul>
 *   <li><b>CLOSED（闭合/正常）</b>：所有请求正常通过，转发给被装饰的 ChatModel 处理。
 *       每次调用失败时累加失败计数器，当失败次数达到 failureThreshold 阈值时，
 *       状态自动切换为 OPEN</li>
 *   <li><b>OPEN（断开/熔断）</b>：所有请求被直接拒绝，返回包含 "CircuitBreaker 已熔断"
 *       错误信息的 Flux.error，不再实际调用底层模型。断路器保持 OPEN 状态直到经过
 *       halfOpenAfterMs 毫秒的冷却时间</li>
 *   <li><b>HALF_OPEN（半开/探测）</b>：冷却时间结束后自动进入此状态，允许有限数量
 *       （由 halfOpenMaxRequests 控制）的探测请求通过。如果任一探测请求成功，断路器
 *       重置失败计数器并恢复为 CLOSED 状态；如果所有探测请求均失败，断路器重新进入
 *       OPEN 状态，开始新一轮冷却周期</li>
 * </ul>
 *
 * <p>线程安全设计：使用 {@link java.util.concurrent.atomic.AtomicReference} 管理状态
 * 字符串，{@link java.util.concurrent.atomic.AtomicInteger} 管理失败计数和半开尝试计数，
 * {@link java.util.concurrent.atomic.AtomicLong} 记录断路器打开的时间戳，确保在高并发
 * 场景下的状态一致性。状态转换使用 CAS（Compare-And-Swap）操作，在 OPEN→HALF_OPEN
 * 转换时通过 compareAndSet 保证只有一个线程能成功触发转换。
 *
 * <p>配置参数来源于 {@link lyjew.com.lyclaw.annotation.chat.CircuitBreaker} 注解：
 * failureThreshold（连续失败阈值）、halfOpenAfterSeconds（冷却时间，秒）、
 * halfOpenMaxRequests（半开状态下允许的最大探测请求数）。
 *
 * @see lyjew.com.lyclaw.annotation.chat.CircuitBreaker
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

    /**
     * 构造熔断装饰器，从 {@link CircuitBreaker} 注解中提取配置参数。
     *
     * <p>halfOpenAfterSeconds 会被转换为毫秒（乘以 1000），其他参数直接使用注解中的值。
     * delegate 是被装饰的底层 ChatModel 实例，所有成功的请求最终都由它处理。
     *
     * @param delegate 被装饰的底层 ChatModel 实例
     * @param config   熔断器配置注解，提供失败阈值、冷却时间和半开探测数等参数
     */
    public CircuitBreakerChatModel(ChatModel delegate, CircuitBreaker config) {
        this.delegate = delegate;
        this.failureThreshold = config.failureThreshold();
        this.halfOpenAfterMs = config.halfOpenAfterSeconds() * 1000L;
        this.halfOpenMaxRequests = config.halfOpenMaxRequests();
    }

    /**
     * 使用编程方式指定参数构造熔断装饰器，适用于非 Spring 编程式配置场景。
     *
     * @param delegate           被装饰的底层 ChatModel 实例
     * @param failureThreshold   连续失败次数阈值，达到后触发熔断
     * @param halfOpenAfterMs    熔断后等待毫秒数，之后进入半开探测状态
     */
    public CircuitBreakerChatModel(ChatModel delegate, int failureThreshold, long halfOpenAfterMs) {
        this.delegate = delegate;
        this.failureThreshold = failureThreshold;
        this.halfOpenAfterMs = halfOpenAfterMs;
        this.halfOpenMaxRequests = 3;
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

    /**
     * 根据当前熔断状态处理流式请求：正常时直接转发，半开时探测转发，熔断时直接拒绝。
     *
     * <p>该方法首先调用 {@link #checkAndTransitionState()} 获取当前状态并可能触发
     * OPEN→HALF_OPEN 的状态转换，然后使用 Java 的 switch 表达式根据状态执行不同逻辑：
     * CLOSED 状态下正常转发并监听成功/失败事件（成功重置计数器，失败累加并在达到阈值
     * 时切换为 OPEN）；HALF_OPEN 状态下转发探测请求并监听结果（成功恢复为 CLOSED，
     * 失败计数并在达到半开上限时重新切换为 OPEN）；OPEN 状态下直接返回错误 Flux。
     *
     * @param request 聊天请求对象
     * @return CLOSED/HALF_OPEN 时返回模型的响应流，OPEN 时返回错误流
     */
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
