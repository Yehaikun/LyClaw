package lyjew.com.lyclaw.framework.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method parameter as a tool input parameter.
 * This is parameter metadata and is NOT a Spring Bean marker.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Param {

    /**
     * Parameter name as it appears in the JSON Schema.
     */
    String name() default "";

    /**
     * Parameter description for LLM consumption.
     */
    String description() default "";

    /**
     * Whether this parameter is required.
     */
    boolean required() default true;

    /**
     * Default value if the LLM does not provide this parameter.
     */
    String defaultValue() default "";
}
