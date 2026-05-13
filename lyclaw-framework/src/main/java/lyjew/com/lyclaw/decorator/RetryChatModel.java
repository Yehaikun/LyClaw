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
 * 重试装饰器（Retry Decorator），为 ChatModel 的流式调用提供自动重试能力。
 *
 * <p>本装饰器实现了基于 Reactor 响应式流的透明重试机制，当被装饰的 ChatModel 在
 * 流式调用过程中抛出异常时，自动按照预先配置的策略进行延迟重试。与传统的阻塞式重试
 * 不同，本装饰器在响应式流层面工作，通过 {@code Flux.onErrorResume} 操作符捕获异常，
 * 使用 {@code Mono.delay} 实现异步延迟等待，然后通过递归调用 resume 到重试后的流，
 * 确保整个重试过程完全非阻塞且线程高效。
 *
 * <p>支持三种退避策略（Backoff Strategy），可通过 {@link RetryPolicy.BackoffStrategy} 配置：
 * <ul>
 *   <li><b>FIXED（固定延迟）</b>：每次重试的延迟时间固定为 baseDelayMs，不随重试次数变化。
 *       适用于恢复时间可预测的临时故障场景</li>
 *   <li><b>EXPONENTIAL（指数退避）</b>：延迟时间按 2^(attempt-1) 指数增长，即第 1 次重试
 *       延迟 baseDelayMs，第 2 次延迟 2*baseDelayMs，第 3 次延迟 4*baseDelayMs，以此类推。
 *       适用于需要给下游服务充分恢复时间的场景，是推荐的默认策略</li>
 *   <li><b>LINEAR（线性增长）</b>：延迟时间按 attempt * baseDelayMs 线性增长。相比指数退避
 *       增长更缓慢，适用于对恢复时间有明确预期的场景</li>
 * </ul>
 *
 * <p>抖动（Jitter）机制：为了避免"惊群效应"（多个客户端同时重试导致下游服务压力突增），
 * 每种退避策略都会叠加随机抖动。抖动幅度由 jitter 参数控制（默认 0.3），在 [delay*(1-jitter),
 * delay*(1+jitter)] 区间内随机选取一个值作为最终延迟时间。使用 {@code ThreadLocalRandom}
 * 生成抖动值以保证多线程下的高性能随机数生成。
 *
 * <p>重试次数控制：maxAttempts 参数指定包括初次调用在内的最大尝试次数。例如
 * maxAttempts=3 意味着最多执行 1 次原始调用 + 2 次重试。超过最大尝试次数后，异常
 * 将向上传播而不继续重试，通过 error 日志记录最终失败信息。
 *
 * @see lyjew.com.lyclaw.annotation.chat.RetryPolicy
 */
public class RetryChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(RetryChatModel.class);

    private final ChatModel delegate;
    private final int maxAttempts;
    private final long baseDelayMs;
    private final RetryPolicy.BackoffStrategy backoff;
    private final double jitter;

    /**
     * 从 {@link RetryPolicy} 注解中提取配置参数构造重试装饰器。
     *
     * <p>通过 RetryPolicy 注解提取 maxAttempts（最大尝试次数）、baseDelayMs（基础延迟毫秒）、
     * backoff（退避策略类型）和 jitter（抖动因子）等参数。这是与注解声明联动的主要构造方式，
     * 由框架的 ChatModelPostProcessor 在构建装饰器链时使用。
     *
     * @param delegate 被装饰的底层 ChatModel 实例
     * @param policy   重试策略注解，提供重试次数、退避策略等配置
     */
    public RetryChatModel(ChatModel delegate, RetryPolicy policy) {
        this.delegate = delegate;
        this.maxAttempts = policy.maxAttempts();
        this.baseDelayMs = policy.baseDelayMs();
        this.backoff = policy.backoff();
        this.jitter = policy.jitter();
    }

    /**
     * 使用编程方式指定的参数构造重试装饰器，适用于无需注解的编程式配置场景。
     *
     * <p>与注解驱动构造器相比，此构造器允许在运行时动态指定所有重试参数，适合用于
     * 配置文件驱动的动态配置、测试场景或需要 A/B 测试不同策略参数的场景。
     *
     * @param delegate    被装饰的底层 ChatModel 实例
     * @param maxAttempts 最大尝试次数（包含初次调用），需 >= 1
     * @param baseDelayMs 基础延迟时间（毫秒），作为各种退避策略的基准值
     * @param backoff     退避策略类型（FIXED/EXPONENTIAL/LINEAR）
     * @param jitter      抖动因子，取值范围 [0, 1]，0 表示无抖动
     */
    public RetryChatModel(ChatModel delegate, int maxAttempts, long baseDelayMs,
                           RetryPolicy.BackoffStrategy backoff, double jitter) {
        this.delegate = delegate;
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.backoff = backoff;
        this.jitter = jitter;
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
     * 带重试逻辑的流式调用入口。
     *
     * <p>将 ChatRequest 包装为 Mono 后通过 flatMapMany 展开为 Flux 并启动递归重试流程。
     * 使用 Mono.just 确保每次订阅时都基于相同的请求对象，attempt 从 1 开始计数
     * （1 表示初次调用，2 表示第一次重试，以此类推）。
     *
     * @param request 聊天请求对象
     * @return 带重试逻辑的响应流
     */
    @Override
    public Flux<ModelResponse> stream(ChatRequest request) {
        return Mono.just(request)
                .flatMapMany(req -> streamWithRetry(req, 1));
    }

    /**
     * 递归执行流式调用并在失败时触发延迟重试。
     *
     * <p>该方法通过 Reactor 的 onErrorResume 实现递归重试：当流式调用出错时，
     * 首先检查当前尝试次数是否小于最大允许次数。如果还可以重试，则计算延迟时间、
     * 记录 warn 日志，通过 Mono.delay 异步等待指定毫秒后递归调用自身（attempt+1）。
     * 当尝试次数达到上限时，记录 error 日志并将异常向上传播，终止重试循环。
     *
     * <p>递归调用通过 Reactor 的 thenMany 操作符实现，将延迟 Mono 完成后衔接
     * 下一次重试的 Flux，整个链保持非阻塞异步执行。
     *
     * @param request 聊天请求对象
     * @param attempt 当前尝试次数（从 1 开始）
     * @return 当前尝试的响应流，失败时可能递归到下一次重试或传播错误
     */
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

    /**
     * 根据配置的退避策略和当前尝试次数计算重试延迟时间（含随机抖动）。
     *
     * <p>延迟计算公式因策略而异：
     * <ul>
     *   <li>FIXED: delay = baseDelayMs</li>
     *   <li>LINEAR: delay = baseDelayMs * attempt</li>
     *   <li>EXPONENTIAL: delay = baseDelayMs * 2^(attempt-1)</li>
     * </ul>
     * 计算基础延迟后，叠加抖动：在 [delay*(1-jitter), delay*(1+jitter)] 区间内
     * 使用 ThreadLocalRandom 随机取值。最终延迟不小于 0 毫秒。
     *
     * @param attempt 当前尝试次数（从 1 开始）
     * @return 含随机抖动的延迟时间（毫秒）
     */
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
