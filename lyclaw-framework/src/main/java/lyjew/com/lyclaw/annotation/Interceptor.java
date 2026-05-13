package lyjew.com.lyclaw.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 拦截器（Interceptor）声明注解，用于将一个类标记为 LyClaw 框架请求处理链路中的横切关注点拦截器，
 * 并通过 after/before 属性声明拦截器之间的执行顺序约束。
 *
 * <p>在 LyClaw 框架的请求处理架构中，拦截器链（Interceptor Chain）是一种面向切面编程
 * （AOP）机制的轻量级实现，类似于 Web 框架中的 Filter 或 Spring 的 HandlerInterceptor。
 * 每个拦截器可以在 AI 对话请求进入核心处理逻辑之前（before）和响应返回之后（after）
 * 执行自定义的横切逻辑，如内容安全审核、Token 使用量检查、请求日志记录、敏感信息过滤、
 * 响应格式化等。被 {@code @Interceptor} 注解标记的类通过
 * {@link org.springframework.stereotype.Component} 元注解自动被 Spring 容器发现并注册，
 * 框架的 InterceptorChain 根据 after/before 约束自动构建有序的拦截器链。
 *
 * <p>拦截器排序机制：与 PipelineStage 类似，框架使用拓扑排序算法处理所有拦截器的
 * after/before 声明，确保拦截器按正确的顺序执行。例如，内容审核拦截器应声明
 * {@code before = {ModelCallInterceptor.class}} 以确保审核在模型调用之前完成，
 * 日志拦截器应声明 {@code after = {AuditInterceptor.class}} 以确保审计信息
 * 先于日志记录生成。
 *
 * <p>核心属性说明：
 * <ul>
 *   <li><b>name</b>：拦截器的名称，用于在日志、追踪和监控中标识该拦截器</li>
 *   <li><b>after</b>：声明该拦截器必须在指定的拦截器类执行完毕之后才能执行。
 *       每个元素应是标注了 {@code @Interceptor} 的类的 Class 对象</li>
 *   <li><b>before</b>：声明该拦截器必须在指定的拦截器类开始执行之前完成。
 *       与 after 互为反向声明</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * @Interceptor(name = "contentFilter", before = {ModelCallInterceptor.class})
 * public class ContentFilterInterceptor implements InterceptorHandler {
 *     // 拦截器实现
 * }
 * }</pre>
 *
 * @see lyjew.com.lyclaw.interceptor.InterceptorChain
 * @see lyjew.com.lyclaw.annotation.PipelineStage
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@Documented
public @interface Interceptor {

    /**
     * 拦截器的名称，用于在日志、追踪信息和监控面板中标识该拦截器。
     *
     * <p>如果为空字符串，框架使用类的简单名称（SimpleName）作为默认的拦截器名称。
     *
     * @return 拦截器名称字符串，默认为空字符串（使用类名推断）
     */
    String name() default "";

    /**
     * 声明当前拦截器必须在指定的拦截器类执行完毕之后才能开始执行。
     *
     * <p>此属性定义了拦截器链中的"后序依赖"关系。数组中每个元素必须是标注了
     * {@code @Interceptor} 的类的 Class 对象。如果形成循环依赖，框架在启动阶段
     * 会抛出异常。
     *
     * @return 必须在当前拦截器之前执行的拦截器类数组，默认为空数组
     */
    Class<?>[] after() default {};

    /**
     * 声明当前拦截器必须在指定的拦截器类开始执行之前完成。
     *
     * <p>此属性与 after 互为反向声明。建议优先使用 after 声明依赖关系以保持
     * 统一的声明风格。
     *
     * @return 必须在当前拦截器之后才能执行的拦截器类数组，默认为空数组
     */
    Class<?>[] before() default {};
}
