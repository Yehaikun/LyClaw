package lyjew.com.lyclaw.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Activates the LyClaw framework.
 * This is a marker annotation (similar to {@code @EnableScheduling}) and is NOT a Spring Bean.
 *
 * <p>Usage: place on a Spring {@code @Configuration} class or the main application class.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EnableLyClaw {
}
