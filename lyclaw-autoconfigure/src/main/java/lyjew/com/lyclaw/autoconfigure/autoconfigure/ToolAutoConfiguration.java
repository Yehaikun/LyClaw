package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import lyjew.com.lyclaw.autoconfigure.facade.ConditionFilter;
import lyjew.com.lyclaw.autoconfigure.processor.ToolAnnotationProcessor;
import lyjew.com.lyclaw.tool.ToolRegistry;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
}
