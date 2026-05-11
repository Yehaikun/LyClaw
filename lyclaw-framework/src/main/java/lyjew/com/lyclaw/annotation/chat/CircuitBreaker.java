package lyjew.com.lyclaw.annotation.chat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式熔断配置，标注在 ChatModel 上自动生成 CircuitBreakerChatModel 装饰器。
 *
 * <p>装饰器包装顺序：CircuitBreaker → Retry → Fallback → 原始 ChatModel。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CircuitBreaker {

    /** 失败阈值（连续失败 N 次后熔断） */
    int failureThreshold() default 5;

    /** 半开状态等待时间（秒） */
    long halfOpenAfterSeconds() default 30;

    /** 半开状态允许的最大请求数 */
    int halfOpenMaxRequests() default 3;
}
