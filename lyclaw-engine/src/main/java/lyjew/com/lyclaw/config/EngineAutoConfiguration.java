package lyjew.com.lyclaw.config;


import lyjew.com.lyclaw.pipeline.impl.*;
import lyjew.com.lyclaw.tool.impl.DefaultToolRegistry;
import lyjew.com.lyclaw.tool.impl.ToolCallLoop;
import lyjew.com.lyclaw.provider.ModelProvider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 引擎自动装配类 —— Spring Boot 启动时自动配置 engine 层所有 Bean。
 *
 * <p>通过 @ComponentScan 扫描 lyjew.com.lyclaw 包下所有 @Component 类，
 * 同时通过 @Bean 方法显式声明需要特殊配置的 Bean。</p>
 *
 * <p><b>启用方式</b>：在 spring.factories 中配置即可：
 * <pre>
 * org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
 *   lyjew.com.lyclaw.config.EngineAutoConfiguration
 * </pre>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
@Configuration
@ComponentScan(basePackages = "lyjew.com.lyclaw")
public class EngineAutoConfiguration {

    private final EngineProperties properties;

    public EngineAutoConfiguration(EngineProperties properties) {
        this.properties = properties;
    }

    /**
     * ToolCallLoop —— 工具调用循环模板方法。
     * 传入的 ModelProvider 由 DefaultModelProvider 兜底。
     */
    @Bean
    @ConditionalOnMissingBean
    public ToolCallLoop toolCallLoop(ModelProvider modelProvider,
                                     DefaultToolRegistry toolRegistry) {
        return new ToolCallLoop(modelProvider, toolRegistry, null);
    }

    /**
     * PipelineBuilder —— 管道构建器。
     * PipelineBuilder 只有无参构造器，通过 addStage() 链式添加。
     */
    @Bean
    @ConditionalOnMissingBean
    public PipelineBuilder pipelineBuilder(ContextBuildStage ctxStage,
                                           InterceptorStage interceptorStage,
                                           ToolCallLoopStage toolStage,
                                           MetricsStage metricsStage,
                                           ResponseBuildStage respStage) {
        PipelineBuilder builder = new PipelineBuilder();
        builder.addStage(ctxStage);
        builder.addStage(interceptorStage);
        builder.addStage(toolStage);
        builder.addStage(metricsStage);
        builder.addStage(respStage);
        return builder;
    }

    // EngineSelector 已通过 @Component + @PostConstruct 自动注册，此处不再 @Bean
}