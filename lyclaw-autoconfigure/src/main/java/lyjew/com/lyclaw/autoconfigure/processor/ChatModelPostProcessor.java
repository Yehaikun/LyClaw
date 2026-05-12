package lyjew.com.lyclaw.autoconfigure.processor;

import lyjew.com.lyclaw.annotation.chat.CircuitBreaker;
import lyjew.com.lyclaw.annotation.chat.Fallback;
import lyjew.com.lyclaw.annotation.chat.ModelCapability;
import lyjew.com.lyclaw.annotation.chat.RetryPolicy;
import lyjew.com.lyclaw.base.exception.LyClawException;
import lyjew.com.lyclaw.chat.ChatModel;
import lyjew.com.lyclaw.chat.ChatModelMetadata;
import lyjew.com.lyclaw.chat.ChatModelRegistry;
import lyjew.com.lyclaw.chat.ModelCapabilities;
import lyjew.com.lyclaw.enums.ErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

/**
 * 模型适配器注册处理器，扫描 {@code @ChatModel} 注解的 Bean 并自动注册到 ChatModelRegistry。
 *
 * <p>执行顺序：LOWEST_PRECEDENCE - 200，在所有基础设施就绪后。
 * 处理流程：提取 Provider 名称→提取协议类型→提取能力声明→校验接口实现→注册到 Registry。
 * 同时检测 @RetryPolicy/@Fallback/@CircuitBreaker 注解，自动生成装饰器包装链。</p>
 */
public class ChatModelPostProcessor implements BeanPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ChatModelPostProcessor.class);

    private final ChatModelRegistry registry;

    public ChatModelPostProcessor(ChatModelRegistry registry) {
        this.registry = registry;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 200;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        lyjew.com.lyclaw.annotation.chat.ChatModel annotation =
                bean.getClass().getAnnotation(lyjew.com.lyclaw.annotation.chat.ChatModel.class);
        if (annotation == null) return bean;

        if (!(bean instanceof ChatModel chatModel)) {
            throw new LyClawException(
                    ErrorCode.ADAPTER_NOT_FOUND.code(), ErrorCode.ADAPTER_NOT_FOUND.httpStatus(),
                    "类 " + bean.getClass().getName() + " 标注了 @ChatModel 但未实现 ChatModel 接口");
        }

        String provider = annotation.provider();
        if (provider.isEmpty()) {
            provider = Character.toLowerCase(bean.getClass().getSimpleName().charAt(0))
                    + bean.getClass().getSimpleName().substring(1);
        }

        // 提取能力声明
        ModelCapabilities caps = extractCapabilities(bean.getClass());

        // 校验非 CUSTOM 协议的 baseUrl 和 model 非空
        if (annotation.protocol() != lyjew.com.lyclaw.annotation.chat.ChatModel.ModelProtocol.CUSTOM) {
            if (annotation.defaultBaseUrl().isEmpty()) {
                log.warn("ChatModel {} (provider={}) 未设置 defaultBaseUrl，将以配置中的 baseUrl 为准",
                        beanName, provider);
            }
            if (annotation.defaultModel().isEmpty() && chatModel.model() == null) {
                log.warn("ChatModel {} (provider={}) 未设置 defaultModel，将以配置中的 model 为准",
                        beanName, provider);
            }
        }

        // 构建元数据
        ChatModelMetadata metadata = ChatModelMetadata.fromAnnotation(
                provider, annotation.displayName(), annotation.description(),
                annotation.protocol(), caps, annotation.defaultModel(),
                annotation.defaultBaseUrl(), annotation.version(), annotation.priority());

        // 注册（自动装饰器包装在后续版本中实现）
        if (annotation.autoRegister()) {
            String modelName = chatModel.model() != null ? chatModel.model() : annotation.defaultModel();
            if (modelName == null || modelName.isEmpty()) {
                modelName = provider + "-default";
            }
            registry.register(provider, modelName, applyDecorators(chatModel, bean.getClass()), metadata);
        }

        log.info("注册 ChatModel: provider={}, model={}, protocol={}, capabilities={}",
                provider, chatModel.model(), annotation.protocol(), caps);

        return bean;
    }

    /**
     * 检测并应用装饰器包装链：CircuitBreaker → Retry → Fallback → 原始 ChatModel。
     */
    private ChatModel applyDecorators(ChatModel original, Class<?> clazz) {
        ChatModel wrapped = original;

        // 检测 @Fallback
        if (clazz.isAnnotationPresent(Fallback.class)) {
            Fallback fb = clazz.getAnnotation(Fallback.class);
            log.info("检测到 @Fallback 注解，chain={}", java.util.Arrays.toString(fb.chain()));
            wrapped = new lyjew.com.lyclaw.decorator.FallbackChatModel(wrapped, fb, registry);
        }

        // 检测 @RetryPolicy
        if (clazz.isAnnotationPresent(RetryPolicy.class)) {
            RetryPolicy rp = clazz.getAnnotation(RetryPolicy.class);
            log.info("检测到 @RetryPolicy 注解，maxAttempts={}, backoff={}",
                    rp.maxAttempts(), rp.backoff());
            wrapped = new lyjew.com.lyclaw.decorator.RetryChatModel(wrapped, rp);
        }

        // 检测 @CircuitBreaker（最外层）
        if (clazz.isAnnotationPresent(CircuitBreaker.class)) {
            CircuitBreaker cb = clazz.getAnnotation(CircuitBreaker.class);
            log.info("检测到 @CircuitBreaker 注解，failureThreshold={}, halfOpenAfter={}s",
                    cb.failureThreshold(), cb.halfOpenAfterSeconds());
            wrapped = new lyjew.com.lyclaw.decorator.CircuitBreakerChatModel(wrapped, cb);
        }

        return wrapped;
    }

    private ModelCapabilities extractCapabilities(Class<?> clazz) {
        ModelCapability ann = clazz.getAnnotation(ModelCapability.class);
        if (ann != null) {
            return ModelCapabilities.builder()
                    .streaming(ann.streaming())
                    .toolCalling(ann.toolCalling())
                    .toolCallStreaming(ann.toolCallStreaming())
                    .thinking(ann.thinking())
                    .vision(ann.vision())
                    .promptCaching(ann.promptCaching())
                    .maxInputTokens(ann.maxInputTokens())
                    .maxOutputTokens(ann.maxOutputTokens())
                    .build();
        }
        return ModelCapabilities.openAiDefaults();
    }
}
