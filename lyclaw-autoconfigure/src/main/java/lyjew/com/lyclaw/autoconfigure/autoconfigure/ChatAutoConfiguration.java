package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import lyjew.com.lyclaw.chat.*;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Adapter 层自动配置——注册 ChatModel 注册表和路由。
 *
 * <p>当配置了 lyclaw.chat.models.* 时激活。
 * @ConditionalOnMissingBean 确保使用者自定义实现优先于框架默认。</p>
 */
@AutoConfiguration
public class ChatAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConfigurationProperties(prefix = "lyclaw.chat")
    public ChatProperties chatProperties() {
        return new ChatProperties();
    }

    /**
     * 注册 ChatModelRegistry 聊天模型注册表 Bean，作为所有聊天模型的中央注册中心。
     *
     * <p>ChatModelRegistry 是 LyClaw 聊天模型架构的核心组件，负责维护 provider 名称到
     * ChatModel 实例的多对多映射关系。所有通过注解（@ChatModel）或配置（lyclaw.chat.models.*）
     * 发现的聊天模型实例最终都会被注册到这个注册表中。后续的路由选择器（如 FirstAvailableRouter）
     * 和调用门面（ChatFacade）都依赖此注册表来查找和选择目标模型。</p>
     *
     * <p>使用 {@code @ConditionalOnMissingBean} 允许用户通过自定义 ChatModelRegistry 实现
     * 来扩展或替换默认的注册表行为，例如添加缓存层、监控指标收集、动态路由等功能。</p>
     *
     * @return DefaultChatModelRegistry 实例，使用 HashMap 存储 provider 到模型列表的映射
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatModelRegistry chatModelRegistry() {
        return new DefaultChatModelRegistry();
    }

    /**
     * 注册 FirstAvailableRouter 首选可用路由 Bean，作为模型选择的默认路由策略。
     *
     * <p>FirstAvailableRouter 采用"首选可用"策略：按配置中的优先级顺序遍历 ChatModelRegistry
     * 中注册的所有模型，返回第一个通过健康检查（health check）的可用模型。这种策略简单可靠，
     * 适合大多数场景——配置多个模型作为备选，主模型不可用时自动切换到备用模型。</p>
     *
     * <p>如果需要更复杂的路由策略（如加权轮询、基于延迟的选择、基于成本的优化等），
     * 可以通过实现 ModelRouter 接口并使用 @ModelRouter 注解来注册自定义路由器，
     * 此时框架将优先使用用户自定义的路由策略而非此默认路由器。</p>
     *
     * @param registry ChatModelRegistry 实例，由 Spring 容器自动注入，提供模型查询能力
     * @return FirstAvailableRouter 实例，实现了首选可用的模型选择算法
     */
    @Bean
    @ConditionalOnMissingBean
    public FirstAvailableRouter firstAvailableRouter(ChatModelRegistry registry) {
        return new FirstAvailableRouter(registry);
    }

    /**
     * 注册 ChatFacade 聊天门面 Bean，作为聊天模型调用的统一入口。
     *
     * <p>ChatFacade 是 LyClaw 对外暴露的聊天调用门面，封装了模型选择（通过 ModelRouter）、
     * 请求构造、响应处理、健康检查等核心流程。上层业务代码（如 Pipeline 阶段、Controller
     * 层）通过 ChatFacade 与 LLM 交互，无需关心底层的模型注册、路由选择、协议适配等细节。</p>
     *
     * <p>DefaultChatFacade 实现将请求参数转发给选定的 ChatModel 实例，并在调用前后进行
     * 日志记录和性能统计。如果所有模型都不可用（健康检查全部失败），会抛出明确的异常信息
     * 帮助运维人员快速定位问题。</p>
     *
     * @param registry ChatModelRegistry 实例，提供模型查询和健康检查能力
     * @param router FirstAvailableRouter 实例（或其他自定义 ModelRouter），负责从注册表中选择目标模型
     * @return DefaultChatFacade 实例，封装了模型调用和健康检查的统一逻辑
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatFacade chatFacade(ChatModelRegistry registry, FirstAvailableRouter router) {
        return new DefaultChatFacade(registry, router);
    }
}
