package lyjew.com.lyclaw.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a pipeline stage with ordering constraints.
 * Pipeline stages are auto-discovered as Spring Beans.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@Documented
public @interface PipelineStage {

    /**
     * Stage name.
     */
    String name() default "";

    /**
     * This stage must execute AFTER the listed stages.
     */
    Class<?>[] after() default {};

    /**
     * This stage must execute BEFORE the listed stages.
     */
    Class<?>[] before() default {};

    /**
     * Stage group (e.g. PREPROCESSING, CORE, POSTPROCESSING).
     */
    String group() default "";
}
