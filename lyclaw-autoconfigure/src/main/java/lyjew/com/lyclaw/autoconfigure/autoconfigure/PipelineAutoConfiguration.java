package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import lyjew.com.lyclaw.autoconfigure.facade.ExtensionFacade;
import lyjew.com.lyclaw.autoconfigure.processor.PipelineStageProcessor;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.pipeline.PipelineProperties;
import lyjew.com.lyclaw.infra.metrics.MetricsCollector;
import lyjew.com.lyclaw.pipeline.stage.ContextBuildStage;
import lyjew.com.lyclaw.pipeline.stage.MetricsStage;
import lyjew.com.lyclaw.pipeline.stage.RespondStage;
import lyjew.com.lyclaw.pipeline.stage.SecurityCheckStage;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.filter.ContentFilter;
import lyjew.com.lyclaw.security.SecurityManager;
import lyjew.com.lyclaw.tool.ToolRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 管道阶段自动配置类，负责将管道阶段发现处理器和扩展门面注册为 Spring Bean。
 *
 * <p>该配置类是 LyClaw Pipeline 处理管道的启动入口，通过 Spring Boot 的
 * {@code @AutoConfiguration} 自动装配机制注册两个核心组件：</p>
 * <ul>
 *   <li>{@link PipelineStageProcessor}：
 *       作为 BeanPostProcessor，在应用启动时自动扫描所有实现了
 *       {@link lyjew.com.lyclaw.pipeline.ReactivePipelineStage} 接口的 Bean，
 *       解析 {@code @PipelineStage} 注解中的 name、after、before 排序约束，
 *       并通过拓扑排序算法确定阶段的执行顺序。</li>
 *   <li>四个内置 PipelineStage（ContextBuild、SecurityCheck、Respond、Metrics），
 *       通过 {@code @ConditionalOnMissingBean} 允许使用者覆盖。</li>
 * </ul>
 */
@AutoConfiguration
public class PipelineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConfigurationProperties(prefix = "lyclaw.pipeline")
    public PipelineProperties pipelineProperties() {
        return new PipelineProperties();
    }

    @Bean
    @ConditionalOnMissingBean
    public PipelineStageProcessor pipelineStageProcessor() {
        return new PipelineStageProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    public ExtensionFacade extensionFacade() {
        return new ExtensionFacade();
    }

    // ══════════════════════════════════════════════════════════════
    //  内置 Pipeline Stage 注册——默认开箱即用
    //  使用者可通过声明同名 @Bean 覆盖任何阶段
    // ══════════════════════════════════════════════════════════════

    @Bean
    @ConditionalOnMissingBean
    public ContextBuildStage contextBuildStage(
            ObjectProvider<MetricsCollector> metricsCollector) {
        return new ContextBuildStage(metricsCollector.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityCheckStage securityCheckStage(
            ObjectProvider<SecurityManager> securityManager,
            ObjectProvider<ContentFilter> contentFilter,
            ObjectProvider<MetricsCollector> metricsCollector) {
        return new SecurityCheckStage(
                securityManager.getIfAvailable(),
                contentFilter.getIfAvailable(),
                metricsCollector.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public RespondStage respondStage(
            ObjectProvider<ChatFacade> chatFacade,
            ToolRegistry toolRegistry,
            ObjectProvider<ReActEngine> reActEngine) {
        return new RespondStage(
                chatFacade.getIfAvailable(),
                toolRegistry,
                reActEngine.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public MetricsStage metricsStage(
            ObjectProvider<MetricsCollector> metricsCollector) {
        return new MetricsStage(metricsCollector.getIfAvailable());
    }
}
