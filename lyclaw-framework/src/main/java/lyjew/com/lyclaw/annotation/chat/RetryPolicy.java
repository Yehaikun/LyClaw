package lyjew.com.lyclaw.annotation.chat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式重试策略，标注在 ChatModel 上自动生成 RetryChatModel 装饰器。
 *
 * <p>框架在 ChatModelPostProcessor 中检测到此注解时，
 * 自动将原始适配器包装为 RetryChatModel。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RetryPolicy {

    /** 最大重试次数 */
    int maxAttempts() default 3;

    /** 基础延迟（毫秒） */
    long baseDelayMs() default 1000;

    /** 退避策略 */
    BackoffStrategy backoff() default BackoffStrategy.EXPONENTIAL;

    /** 抖动因子 [0.0, 1.0] */
    double jitter() default 0.1;

    /** 触发重试的 HTTP 状态码 */
    HttpStatusHint[] retryOn() default {
        HttpStatusHint.TOO_MANY_REQUESTS,
        HttpStatusHint.SERVICE_UNAVAILABLE,
        HttpStatusHint.GATEWAY_TIMEOUT
    };

    enum BackoffStrategy { FIXED, EXPONENTIAL, LINEAR }

    enum HttpStatusHint {
        TOO_MANY_REQUESTS(429),
        SERVICE_UNAVAILABLE(503),
        GATEWAY_TIMEOUT(504),
        BAD_GATEWAY(502);

        private final int code;
        HttpStatusHint(int code) { this.code = code; }
        public int getCode() { return code; }
    }
}
