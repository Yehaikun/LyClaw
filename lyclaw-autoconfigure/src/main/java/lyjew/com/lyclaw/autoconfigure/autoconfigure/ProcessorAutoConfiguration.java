package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import lyjew.com.lyclaw.autoconfigure.processor.ChatModelPostProcessor;
import lyjew.com.lyclaw.autoconfigure.processor.InteractionModeProcessor;
import lyjew.com.lyclaw.autoconfigure.processor.ModelRouterPostProcessor;
import lyjew.com.lyclaw.autoconfigure.processor.OpenAiProtocolAutoConfigurator;
import lyjew.com.lyclaw.chat.ChatModelRegistry;
import lyjew.com.lyclaw.chat.ChatProperties;
import lyjew.com.lyclaw.react.ReActEngine;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 注解处理器自动配置——注册所有 BeanPostProcessor 和自动配置器。
 *
 * <p>所有处理器使用 @ConditionalOnMissingBean 允许覆盖，
 * 部分处理器使用 @ConditionalOnBean 确保依赖就绪后才加载。
 */
@AutoConfiguration
public class ProcessorAutoConfiguration {

    /**
     * 注册 ChatModel 发现处理器 Bean，自动扫描 @ChatModel 注解的聊天模型实现。
     *
     * <p>该处理器在 Bean 初始化阶段执行，自动发现所有标注了 @ChatModel 注解的 Bean，
     * 校验是否实现了 ChatModel 接口，提取 Provider 名称、协议类型、能力声明等元数据，
     * 并自动检测 @RetryPolicy、@Fallback、@CircuitBreaker 弹性注解生成装饰器包装链。
     * 依赖 ChatModelRegistry 就绪后才加载。</p>
     *
     * @param registry ChatModel 注册表，由 ChatAutoConfiguration 提前创建
     * @return ChatModelPostProcessor 实例，执行顺序为 LOWEST_PRECEDENCE - 200
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ChatModelRegistry.class)
    public ChatModelPostProcessor chatModelPostProcessor(ChatModelRegistry registry) {
        return new ChatModelPostProcessor(registry);
    }

    /**
     * 注册模型路由策略发现处理器 Bean，自动扫描 @ModelRouter 注解的自定义路由实现。
     *
     * <p>该处理器在 Bean 初始化阶段执行，自动发现标注了 @ModelRouter 注解的 Bean，
     * 校验是否实现了 ModelRouter 接口，然后按名称注册路由策略。如果注解声明为默认路由
     * （defaultRouter=true），则将其设为全局默认路由。如果没有任何路由 Bean 注册，
     * 框架回退使用 ChatAutoConfiguration 中创建的 FirstAvailableRouter。</p>
     *
     * @param registry ChatModel 注册表，路由策略需要从中查询可用模型列表
     * @return ModelRouterPostProcessor 实例，执行顺序为 LOWEST_PRECEDENCE - 190
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ChatModelRegistry.class)
    public ModelRouterPostProcessor modelRouterPostProcessor(ChatModelRegistry registry) {
        return new ModelRouterPostProcessor(registry);
    }

    /**
     * 注册 OpenAI 协议自动配置器 Bean，实现"配置即 Provider"的零代码模型接入。
     *
     * <p>该配置器在所有 Bean 初始化完成后执行，读取 {@code lyclaw.chat.models.*}
     * 配置项，对每个 provider 类型为 openai-protocol 或 openai 的配置条目，自动创建
     * OpenAiProtocolChatModel 实例并注册到 ChatModelRegistry。这就是 LyClaw 框架
     * "不需要写 Java 代码就能新增 AI 模型"的核心实现——通过修改 YAML 配置文件即可
     * 接入兼容 OpenAI 协议的大语言模型服务。依赖 ChatModelRegistry 和 ChatProperties
     * 都已就绪后才加载。</p>
     *
     * @param registry ChatModel 注册表，用于注册配置驱动的模型实例
     * @param properties 聊天配置属性，包含所有 lyclaw.chat.models.* 下的模型配置
     * @return OpenAiProtocolAutoConfigurator 实例，执行顺序为 LOWEST_PRECEDENCE - 180
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ChatModelRegistry.class, ChatProperties.class})
    public OpenAiProtocolAutoConfigurator openAiProtocolAutoConfigurator(
            ChatModelRegistry registry, ChatProperties properties) {
        return new OpenAiProtocolAutoConfigurator(registry, properties);
    }

    /**
     * 注册 InteractionModeProcessor 交互模式发现处理器 Bean，自动扫描
     * {@code @InteractionMode} 注解的 ReActEngine 实现。
     *
     * <p>该处理器在 Bean 初始化阶段执行，自动发现所有标注了 @InteractionMode 注解的 Bean，
     * 校验是否实现了 ReActEngine 接口，提取 name、description、isDefault 属性后构建
     * 交互模式注册表。同时实现了 SmartInitializingSingleton 接口，在所有单例初始化完成后
     * 输出启动摘要日志。使用 {@code @ConditionalOnClass} 仅在 ReActEngine 类可用时激活。</p>
     *
     * @return InteractionModeProcessor 实例，负责交互模式的自动发现和索引
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(ReActEngine.class)
    public InteractionModeProcessor interactionModeProcessor() {
        return new InteractionModeProcessor();
    }
}
