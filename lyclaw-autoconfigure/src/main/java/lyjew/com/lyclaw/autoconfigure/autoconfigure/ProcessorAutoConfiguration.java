package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import lyjew.com.lyclaw.autoconfigure.processor.*;
import lyjew.com.lyclaw.chat.*;
import lyjew.com.lyclaw.storage.*;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
     * 注册存储后端发现处理器 Bean，自动扫描 @StorageBackend 注解的存储后端实现。
     *
     * <p>该处理器在 Bean 初始化阶段执行，自动发现所有标注了 @StorageBackend 注解的
     * Spring Bean，解析注解中的层归属（SessionStore、EntityStore、MemoryStore）和
     * 能力声明（VectorStore、GraphStore、FullTextStore），校验接口实现后注册到
     * StorageBackendRegistry 中。依赖 StorageBackendRegistry 先就绪后才加载。</p>
     *
     * @param registry 存储后端注册表，由 StorageAutoConfiguration 提前创建
     * @return StorageBackendPostProcessor 实例，执行顺序为 LOWEST_PRECEDENCE - 100
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(StorageBackendRegistry.class)
    public StorageBackendPostProcessor storageBackendPostProcessor(StorageBackendRegistry registry) {
        return new StorageBackendPostProcessor(registry);
    }

    /**
     * 注册写策略发现处理器 Bean，自动扫描 @WritePolicy 注解的持久化策略实现。
     *
     * <p>该处理器在 Bean 初始化阶段执行，自动发现标注了 @WritePolicy 注解的 Bean，
     * 校验是否实现了 MemoryPersistence 接口，然后将策略按名称注册到
     * DefaultMemoryWriteManager 中。如果注解声明为默认策略（defaultPolicy=true），
     * 还会自动设置对应 MemoryLayer 的默认策略。依赖 DefaultMemoryWriteManager 就绪后才加载。</p>
     *
     * @param writeManager 记忆写管理器，由 StorageAutoConfiguration 提前创建
     * @return WritePolicyPostProcessor 实例，执行顺序为 LOWEST_PRECEDENCE - 90
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DefaultMemoryWriteManager.class)
    public WritePolicyPostProcessor writePolicyPostProcessor(DefaultMemoryWriteManager writeManager) {
        return new WritePolicyPostProcessor(writeManager);
    }

    /**
     * 注册记忆系统自动配置器 Bean，将所有分散注册的存储组件编织成完整的记忆系统。
     *
     * <p>该配置器在所有 Bean 初始化完成后（通过 InitializingBean 接口的
     * afterPropertiesSet() 回调）执行，自动完成以下任务：确定 MemoryStore 层的默认后端、
     * 检测向量搜索和全文搜索能力、配置检索路径架构、初始化 L1（内存缓存）+ L2（持久化）
     * 双层存储架构。依赖 StorageBackendRegistry 和 StorageProperties 都已就绪后才加载。</p>
     *
     * @param registry 存储后端注册表，提供所有已注册后端的查询能力
     * @param properties 存储配置属性，提供层到后端的映射配置
     * @return MemorySystemAutoConfigurator 实例，执行顺序为 LOWEST_PRECEDENCE - 50
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({StorageBackendRegistry.class, StorageProperties.class})
    public MemorySystemAutoConfigurator memorySystemAutoConfigurator(
            StorageBackendRegistry registry, StorageProperties properties) {
        return new MemorySystemAutoConfigurator(registry, properties);
    }

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
}
