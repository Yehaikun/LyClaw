package lyjew.com.lyclaw.annotation.chat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式降级链，标注在 ChatModel 上自动生成 FallbackChatModel 装饰器。
 *
 * <p>降级链按顺序尝试，格式为 "provider:model"。
 * 触发降级的异常类型可配置，默认包含 ModelException 和 TimeoutException。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Fallback {

    /** 降级链：按顺序尝试，格式 "provider:model" */
    String[] chain();

    /** 触发降级的异常类型全限定名 */
    String[] on() default {
        "lyjew.com.lyclaw.exception.ModelException",
        "java.util.concurrent.TimeoutException"
    };
}
