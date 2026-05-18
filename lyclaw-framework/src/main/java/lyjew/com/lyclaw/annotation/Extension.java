package lyjew.com.lyclaw.annotation;

import java.lang.annotation.*;

/**
 * 键值对扩展配置，用于 {@link Agent#extensions()}。
 *
 * <p>通过 key-value 对为 Agent 注入框架级配置，不需修改 @Agent 注解定义即可扩展。
 * 运行时由 {@code AgentConfigResolver} 按优先级合并多个配置源。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
@Documented
public @interface Extension {
    String key();
    String value();
}
