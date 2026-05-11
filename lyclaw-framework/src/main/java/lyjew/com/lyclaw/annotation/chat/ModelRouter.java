package lyjew.com.lyclaw.annotation.chat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明该类是一个模型路由策略。
 *
 * <p>标注了此注解的类自动注册到 ModelRouterRegistry。
 * 路由策略负责根据请求内容/复杂度决定使用哪个模型。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ModelRouter {

    /** 路由策略名称 */
    String name();

    /** 策略描述 */
    String description() default "";

    /** 是否默认路由（只能有一个 Router 声明 defaultRouter=true） */
    boolean defaultRouter() default false;

    /** 路由延迟估计（毫秒），用于日志和性能监控 */
    long estimatedLatencyMs() default 1;
}
