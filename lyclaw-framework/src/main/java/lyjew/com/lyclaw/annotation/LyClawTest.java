package lyjew.com.lyclaw.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test environment annotation (Phase 5 skeleton).
 * This is NOT a Spring Bean marker.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LyClawTest {

    /**
     * Whether to enable network access during tests.
     */
    boolean enableNetwork() default false;
}
