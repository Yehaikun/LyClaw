package lyjew.com.lyclaw.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 模型适配器声明注解，用于将一个类标记为 LyClaw 框架的 AI 模型适配器提供者（Adapter Provider）。
 *
 * <p>在 LyClaw 框架的多模型接入架构中，"适配器"（Adapter）是负责与特定 AI 服务提供商
 * 进行通信的组件，它将框架内部的统一请求格式转换为 Provider 的原生 API 协议格式，
 * 并将 Provider 的响应解析为框架内部统一的数据模型。被 {@code @Adapter} 注解标记的类
 * 通过 {@link org.springframework.stereotype.Component} 元注解自动被 Spring 容器扫描
 * 并注册为 Bean，同时由框架的适配器注册中心提取元数据，实现 Provider 的自动发现和注册。
 *
 * <p>核心属性说明：
 * <ul>
 *   <li><b>provider</b>：Provider 的唯一标识符字符串，全局不可重复。用于在框架配置中引用
 *       该 Provider（如 "deepseek"、"openai"、"groq"），也作为 ChatModelRegistry 中
 *       查找模型实例的键值</li>
 *   <li><b>description</b>：适配器的描述信息，用于运维面板和 Actuator 端点中展示
 *       Provider 的详细信息，帮助运维人员理解该适配器的用途和适用的服务提供商</li>
 * </ul>
 *
 * <p>对于遵循 OpenAI 兼容协议的 Provider，通常无需手动编写适配器类并使用此注解，
 * 只需在 application.yml 中配置 lyclaw.chat.models.xxx.* 即可，框架会自动创建
 * OpenAiProtocolChatModel 实例。此注解主要用于需要自定义协议适配逻辑的非标准 Provider。
 *
 * @see lyjew.com.lyclaw.adapter.OpenAiProtocolChatModel
 * @see lyjew.com.lyclaw.annotation.chat.ChatModel
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@Documented
public @interface Adapter {

    /**
     * Provider 的全局唯一标识符字符串，用于在框架中区分不同的 AI 服务提供商。
     *
     * <p>该标识符将作为 ChatModelRegistry 中的键值，同时也是配置文件中引用该 Provider
     * 的名称。建议使用简短、有意义的英文标识，如 "deepseek"、"openai"、"groq"。
     * 空字符串表示未指定 Provider 标识，框架会使用类名或其他默认策略推断。
     *
     * @return Provider 唯一标识符字符串，默认为空字符串
     */
    String provider() default "";

    /**
     * 适配器的功能描述文本，用于在运维面板、Actuator 端点和日志中展示 Provider 的详细信息。
     *
     * <p>建议包含该适配器适用的 AI 服务提供商、支持的模型系列、特殊限制或注意事项等
     * 信息，帮助运维和开发人员快速了解该适配器的用途和适用范围。
     *
     * @return 适配器描述字符串，默认为空字符串
     */
    String description() default "";
}
