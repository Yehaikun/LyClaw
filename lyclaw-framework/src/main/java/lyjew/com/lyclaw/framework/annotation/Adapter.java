package lyjew.com.lyclaw.framework.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a model adapter provider.
 * Adapters are auto-discovered as Spring Beans.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@Documented
public @interface Adapter {

    /**
     * Provider identifier (e.g. "deepseek", "openai").
     */
    String provider() default "";

    /**
     * Adapter description.
     */
    String description() default "";
}
