package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import lyjew.com.lyclaw.autoconfigure.processor.InterceptorProcessor;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 拦截器自动配置类，负责将拦截器发现处理器注册为 Spring Bean。
 *
 * <p>该配置类是 LyClaw 框架的拦截器机制入口，通过 Spring Boot 的
 * {@code @AutoConfiguration} 机制自动装配（无需使用者手动 import）。它注册
 * {@link lyjew.com.lyclaw.autoconfigure.processor.InterceptorProcessor} 作为
 * {@link org.springframework.beans.factory.config.BeanPostProcessor}，该处理器在
 * 应用启动时自动扫描所有实现了 {@link lyjew.com.lyclaw.interceptor.Interceptor}
 * 接口的 Spring Bean，并根据 {@code @Interceptor} 注解的排序约束（after/before 属性）
 * 组织拦截器链的执行顺序。</p>
 *
 * <p><b>条件装配策略：</b>使用 {@code @ConditionalOnMissingBean} 注解确保只有在
 * 容器中不存在同类型的用户自定义 Bean 时才创建框架默认实例，遵循 Spring Boot 的
 * "约定优于配置，配置覆盖约定"原则。使用者可以通过声明自己的 InterceptorProcessor Bean
 * 来完全接管拦截器发现逻辑。</p>
 *
 * <p><b>拦截器执行模型：</b>拦截器链在 LyClaw Pipeline 的执行过程中被自动应用，
 * 每个管道阶段（如消息预处理、LLM 调用、工具调用等）在执行前和执行后都会遍历
 * 拦截器链，实现横切关注点的统一处理（如日志记录、权限校验、性能监控、数据脱敏等）。
 * 拦截器的执行顺序由 {@code getOrder()} 方法的返回值确定，数值越小优先级越高，
 * 越先执行。</p>
 */
@AutoConfiguration
public class InterceptorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public InterceptorProcessor interceptorProcessor() {
        return new InterceptorProcessor();
    }
}
