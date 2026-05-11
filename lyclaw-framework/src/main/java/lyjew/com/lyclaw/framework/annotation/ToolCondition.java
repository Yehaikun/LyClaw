package lyjew.com.lyclaw.framework.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares conditions for tool registration.
 * This is a meta-annotation for {@link Tool} and is NOT a standalone Spring Bean.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolCondition {

    /**
     * Required configuration keys (e.g. "lyclaw.tools.brave.api-key").
     */
    String[] requiresConfig() default {};

    /**
     * Required Spring profiles.
     */
    String[] requiresProfile() default {};

    /**
     * Required classes that must be present on the classpath.
     */
    Class<?>[] requiresClass() default {};
}
