package lyjew.com.lyclaw.tracing;

import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * 链路追踪自动配置类，在 Spring 容器初始化时启用 Reactor 上下文的自动传播。
 *
 * <p>响应式编程中线程切换会导致 ThreadLocal（如 MDC）丢失上下文。
 * 通过静态初始化块调用 {@code Hooks.enableAutomaticContextPropagation()}，
 * 确保 traceId、spanId 等追踪信息在 Reactor 操作符链中自动传递。
 */
@Configuration
public class TraceAutoConfiguration {

    // 在类加载时启用 Reactor 的自动上下文传播，确保 MDC 追踪信息跨线程传递
    static {
        Hooks.enableAutomaticContextPropagation();
    }
}
