package lyjew.com.lyclaw.annotation.skill;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a Skill.
 * Skills are auto-discovered as Spring Beans.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@Documented
public @interface Skill {

    /**
     * Skill name.
     */
    String name() default "";

    /**
     * Skill description.
     */
    String description() default "";
}
