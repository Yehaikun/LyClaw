package lyjew.com.lyclaw.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 激活 LyClaw 框架的开关注解，类似于 Spring 的 {@code @EnableScheduling} 或
 * {@code @EnableAsync}，用于在 Spring Boot 应用中显式启用 LyClaw 框架的全部功能。
 *
 * <p>本注解是一个纯标记注解（Marker Annotation），不包含任何属性，也不是 Spring Bean
 * 标记（不带有 {@link org.springframework.stereotype.Component} 元注解）。将其放置在
 * Spring 配置类或主启动类上后，LyClaw 框架的自动配置模块检测到该注解的存在，触发
 * 一系列的自动配置行为，包括但不限于：注册 ChatModelRegistry 和默认路由策略、
 * 扫描并注册所有 {@code @Tool} 注解的工具类、初始化记忆存储后端、
 * 启动流水线阶段调度器、激活链路追踪等。
 *
 * <p>使用方式：将本注解放置在任意的 Spring {@code @Configuration} 类上，或者直接放在
 * Spring Boot 主应用类（标注了 {@code @SpringBootApplication} 的类）上。框架通过
 * {@code @Import} 机制导入 LyClaw 的自动配置类，在 Spring 容器刷新阶段完成所有
 * 框架组件的初始化。
 *
 * <p>使用示例：
 * <pre>{@code
 * @SpringBootApplication
 * @EnableLyClaw
 * public class MyApplication {
 *     public static void main(String[] args) {
 *         SpringApplication.run(MyApplication.class, args);
 *     }
 * }
 * }</pre>
 *
 * <p>如果不添加此注解，LyClaw 框架的自动配置将不会生效，框架组件不会被初始化。
 * 这是有意设计的"选择性激活"模式，允许应用在依赖了 lyclaw-framework 模块但不希望
 * 启用 AI 功能的环境中（如纯数据处理微服务）正常运行。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EnableLyClaw {
}
