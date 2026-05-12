package lyjew.com.lyclaw.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an AI Agent (Phase 4, pre-defined now).
 * Agents are auto-discovered as Spring Beans.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@Documented
public @interface Agent {

    /**
     * Agent name.
     */
    String name() default "";

    /**
     * Agent description.
     */
    String description() default "";

    /**
     * Agent version.
     */
    String version() default "1.0.0";
}
