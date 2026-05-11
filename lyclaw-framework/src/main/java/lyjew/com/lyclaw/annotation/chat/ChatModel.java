package lyjew.com.lyclaw.annotation.chat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一个 AI 模型适配器。
 *
 * <p>替代旧的 {@code ModelAdapter} 和 {@code Engine} 两套体系。
 * 标注了此注解的类会被 {@code ChatModelPostProcessor} 自动发现并注册到 {@code ChatModelRegistry}。</p>
 *
 * <p>对于 OpenAI 兼容协议（protocol=OPENAI）：无需写适配器类——
 * 只需在 application.yml 中配置 lyclaw.chat.models.xxx.* 即可，框架自动创建适配器实例。
 * 这是"配置即 Provider"的核心设计——85%+ Provider 零代码覆盖。</p>
 *
 * <p>使用示例：
 * <pre>{@code
 * @ChatModel(provider = "anthropic", protocol = ModelProtocol.ANTHROPIC, defaultModel = "claude-sonnet-4-5")
 * public class AnthropicChatModel extends AbstractChatModel { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ChatModel {

    /** Provider 名称，全局唯一。用于配置引用：lyclaw.chat.models.deepseek.provider=deepseek */
    String provider();

    /** Provider 显示名称，用于 UI 模型列表 */
    String displayName() default "";

    /** Provider 描述 */
    String description() default "";

    /** 原生协议类型，框架据此提供默认的请求构建/响应解析行为 */
    ModelProtocol protocol() default ModelProtocol.OPENAI;

    /** 默认模型名称 */
    String defaultModel() default "";

    /** 默认 API 端点 URL */
    String defaultBaseUrl() default "";

    /** 语义化版本号 */
    String version() default "1.0.0";

    /** 优先级，值越大越优先被选为默认 Provider */
    int priority() default 0;

    /** 是否自动注册到 ChatModelRegistry */
    boolean autoRegister() default true;

    /** API 协议枚举 */
    enum ModelProtocol {
        /** OpenAI Chat Completions API（及所有兼容服务如 DeepSeek、Groq 等） */
        OPENAI,
        /** Anthropic Messages API */
        ANTHROPIC,
        /** Ollama API */
        OLLAMA,
        /** Google Gemini API */
        GEMINI,
        /** 完全自定义协议 */
        CUSTOM
    }
}
