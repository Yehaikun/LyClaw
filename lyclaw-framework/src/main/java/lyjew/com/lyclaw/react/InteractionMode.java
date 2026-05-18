package lyjew.com.lyclaw.react;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 交互模式声明注解，标记一个类为 LyClaw 支持的 LLM 交互模式实现。
 *
 * <p>交互模式定义了 LLM 在一次任务中如何与工具、环境进行多轮交互的策略。
 * 标注此注解的类会被自动配置（如 {@code ReActAutoConfiguration}）显式注册为 Bean，
 * 而非通过 Spring 组件扫描自动发现（因为交互模式实现通常需要特定的构造器注入）。
 *
 * <p>使用示例：
 * <pre>{@code
 * @InteractionMode(name = "react", description = "Reasoning-Acting loop", isDefault = true)
 * public class DefaultReActEngine implements ReActEngine { ... }
 * }</pre>
 *
 * @see ReActEngine
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface InteractionMode {

    /** 交互模式名称，如 "react"、"cot"、"tree-of-thought" */
    String name();

    /** 模式描述 */
    String description() default "";

    /** 是否为默认交互模式，框架在未指定模式时使用此实现 */
    boolean isDefault() default false;
}
