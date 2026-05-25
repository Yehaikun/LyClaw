package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import lyjew.com.lyclaw.autoconfigure.facade.ConditionFilter;
import lyjew.com.lyclaw.autoconfigure.processor.ToolAnnotationProcessor;
import lyjew.com.lyclaw.tool.ToolProperties;
import lyjew.com.lyclaw.tool.ToolRegistry;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 工具相关自动配置，负责注册 {@link ToolAnnotationProcessor} 和 {@link ConditionFilter}。
 *
 * <p>不再创建匿名 {@link ToolRegistry} 回退 bean，
 * 统一由 action 模块的 {@code DefaultToolRegistry} 提供唯一注册表。</p>
 */
@AutoConfiguration
@ConditionalOnClass(ToolRegistry.class)
public class ToolAutoConfiguration {

    /**
     * 注册 ToolAnnotationProcessor 工具注解处理器 Bean，自动发现 @Tool 注解的工具类。
     *
     * <p>该处理器作为 BeanPostProcessor，在应用启动时扫描所有 Spring Bean，自动发现
     * 标注了 {@code @Tool} 注解的类。支持三种工具注册模式：类级 + 方法级 @Tool 注解模式、
     * 类级 @Tool 注解 + Tool 接口实现模式、以及旧版无注解的 Tool 接口兼容模式。
     * 通过反射解析方法参数上的 @Param 注解构建 JSON Schema 格式的参数定义，
     * 并将工具适配器注册到 ToolRegistry。使用 {@code @ConditionalOnMissingBean}
     * 允许替换，使用 {@code @ConditionalOnBean(ToolRegistry.class)} 确保依赖就绪。</p>
     *
     * @param registry ToolRegistry 实例，由 action 模块的 DefaultToolRegistry 提供，
     *                 所有发现的工具最终注册到此注册表中
     * @return ToolAnnotationProcessor 实例，负责发现和注册 @Tool 注解的 Spring Bean
     */
    @Bean
    @ConditionalOnMissingBean
    @ConfigurationProperties(prefix = "lyclaw.tool")
    public ToolProperties toolProperties() {
        return new ToolProperties();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ToolRegistry.class)
    public ToolAnnotationProcessor toolAnnotationProcessor(ToolRegistry registry) {
        return new ToolAnnotationProcessor(registry);
    }

    /**
     * 注册条件过滤器，根据环境配置决定工具是否可用。
     *
     * @param env Spring环境对象
     * @return 条件过滤器实例
     */
    @Bean
    public ConditionFilter conditionFilter(Environment env) {
        return new ConditionFilter(env);
    }

    /**
     * DefaultToolRegistry 的条件配置。
     * 仅当 lyclaw-action 模块在 classpath 上时生效。
     * 使用反射避免与 lyclaw-action 的编译期循环依赖。
     */
    @org.springframework.context.annotation.Configuration
    @ConditionalOnClass(name = "lyjew.com.lyclaw.action.impl.DefaultToolRegistry")
    static class ToolRegistryConfiguration {
        @Bean
        @ConditionalOnMissingBean(ToolRegistry.class)
        public ToolRegistry toolRegistry() {
            try {
                Class<?> clazz = Class.forName("lyjew.com.lyclaw.action.impl.DefaultToolRegistry");
                return (ToolRegistry) clazz.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failed to create DefaultToolRegistry", e);
            }
        }
    }
}
