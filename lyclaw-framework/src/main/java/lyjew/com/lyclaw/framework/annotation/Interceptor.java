package lyjew.com.lyclaw.framework.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an interceptor.
 * Interceptors are auto-discovered as Spring Beans.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@Documented
public @interface Interceptor {

    /**
     * Interceptor name.
     */
    String name() default "";

    /**
     * This interceptor must execute AFTER the listed interceptors.
     */
    Class<?>[] after() default {};

    /**
     * This interceptor must execute BEFORE the listed interceptors.
     */
    Class<?>[] before() default {};
}
