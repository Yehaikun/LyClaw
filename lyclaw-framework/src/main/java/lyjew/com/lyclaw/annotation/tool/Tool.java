package lyjew.com.lyclaw.annotation.tool;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class or method as a LyClaw Tool.
 * Tool classes annotated with {@code @Tool} are auto-discovered as Spring Beans.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Component
@Documented
public @interface Tool {

    /**
     * Tool unique name.
     * Required — an empty value will cause an error at startup.
     */
    String name() default "";

    /**
     * Tool description for LLM consumption.
     */
    String description() default "";

    /**
     * Grouping label for this tool.
     */
    String group() default "";

    /**
     * Whether this tool is side-effect free (read-only).
     */
    boolean readonly() default false;

    /**
     * Tool version.
     */
    String version() default "1.0.0";

    /**
     * Execution timeout in milliseconds.
     */
    long timeout() default 30000;
}
