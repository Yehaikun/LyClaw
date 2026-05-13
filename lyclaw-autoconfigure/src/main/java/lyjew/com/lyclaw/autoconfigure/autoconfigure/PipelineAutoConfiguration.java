package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import lyjew.com.lyclaw.autoconfigure.facade.ExtensionFacade;
import lyjew.com.lyclaw.autoconfigure.processor.PipelineStageProcessor;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 管道阶段自动配置类，负责将管道阶段发现处理器和扩展门面注册为 Spring Bean。
 *
 * <p>该配置类是 LyClaw Pipeline 处理管道的启动入口，通过 Spring Boot 的
 * {@code @AutoConfiguration} 自动装配机制注册两个核心组件：</p>
 * <ul>
 *   <li>{@link lyjew.com.lyclaw.autoconfigure.processor.PipelineStageProcessor}：
 *       作为 BeanPostProcessor，在应用启动时自动扫描所有实现了
 *       {@link lyjew.com.lyclaw.pipeline.ReactivePipelineStage} 接口的 Bean，
 *       解析 {@code @PipelineStage} 注解中的 name、after、before 排序约束，
 *       并通过拓扑排序算法确定阶段的执行顺序。</li>
 *   <li>{@link lyjew.com.lyclaw.autoconfigure.facade.ExtensionFacade}：
 *       扩展门面组件，提供可配置的过滤器链机制，允许在扩展注册过程中根据条件
 *       过滤掉不符合要求的管道阶段候选者，支持 fail-fast 和条件启用等策略。</li>
 * </ul>
 *
 * <p><b>条件装配策略：</b>两个 Bean 都使用 {@code @ConditionalOnMissingBean} 注解，
 * 允许使用者通过声明自定义实现来覆盖框架默认行为。例如，可以自定义 PipelineStageProcessor
 * 来添加额外的阶段排序逻辑，或自定义 ExtensionFacade 来引入业务特定的过滤规则。</p>
 *
 * <p><b>管道执行模型：</b>LyClaw 的管道采用响应式处理模型，每个阶段负责处理消息流的
 * 一个特定环节（如上下文注入、LLM 调用、工具执行、消息持久化等），阶段之间通过响应式
 * 流式连接，形成一个完整的请求-响应处理链路。</p>
 */
@AutoConfiguration
public class PipelineAutoConfiguration {

    /**
     * 注册 PipelineStageProcessor 管道阶段发现处理器 Bean，自动扫描实现了
     * ReactivePipelineStage 接口的管道阶段组件。
     *
     * <p>该处理器作为 BeanPostProcessor，在应用启动时扫描所有 Spring Bean，自动发现
     * 实现了 {@link lyjew.com.lyclaw.pipeline.ReactivePipelineStage} 接口的实例，
     * 并解析 {@code @PipelineStage} 注解中的 name、after、before 排序约束信息。
     * 同时实现了 SmartInitializingSingleton 接口，在所有单例 Bean 初始化完成后
     * 输出管道的启动摘要日志。使用 {@code @ConditionalOnMissingBean} 允许用户
     * 通过声明自定义的 PipelineStageProcessor Bean 来扩展或替换发现逻辑。</p>
     *
     * @return PipelineStageProcessor 实例，负责管道阶段的自动发现和排序
     */
    @Bean
    @ConditionalOnMissingBean
    public PipelineStageProcessor pipelineStageProcessor() {
        return new PipelineStageProcessor();
    }

    /**
     * 注册 ExtensionFacade 扩展门面 Bean，提供可配置的扩展过滤和编排管道。
     *
     * <p>ExtensionFacade 是 LyClaw 扩展系统的编排中枢，提供过滤器链机制对发现的扩展
     * 候选者（工具、管道阶段、拦截器等）进行统一的过滤和分类处理。使用者可以通过
     * {@code filteringEnabled(boolean)} 控制是否启用过滤，通过 {@code failFast(boolean)}
     * 控制异常处理策略，通过 {@code addFilter(Predicate)} 注册自定义过滤条件。
     * 使用 {@code @ConditionalOnMissingBean} 允许用户替换默认的门面实现。</p>
     *
     * @return ExtensionFacade 实例，默认为空过滤器链，过滤启用，快速失败禁用
     */
    @Bean
    @ConditionalOnMissingBean
    public ExtensionFacade extensionFacade() {
        return new ExtensionFacade();
    }
}
