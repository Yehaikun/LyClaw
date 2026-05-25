package lyjew.com.lyclaw.autoconfigure.processor;

import lyjew.com.lyclaw.annotation.chat.CircuitBreaker;
import lyjew.com.lyclaw.annotation.chat.Fallback;
import lyjew.com.lyclaw.annotation.chat.ModelCapability;
import lyjew.com.lyclaw.annotation.chat.RetryPolicy;
import lyjew.com.lyclaw.exception.LyClawException;
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

    /**
     * 构造 ChatModel 后处理器，注入 ChatModelRegistry 依赖。
     *
     * <p>ChatModelRegistry 是所有聊天模型的中央注册表，负责维护 provider 名称到
     * ChatModel 实例的映射关系。本处理器在发现带有 @ChatModel 注解的 Bean 后，
     * 将解析出的模型元数据连同模型实例一起注册到该注册表中，供后续路由和调用使用。</p>
     *
     * @param registry ChatModel 注册表实例，由 Spring 容器通过构造器注入提供，
     *                 确保所有模型注册到一个统一的注册中心
     */
    public ChatModelPostProcessor(ChatModelRegistry registry) {
        this.registry = registry;
    }

    /**
     * 返回此 BeanPostProcessor 的执行顺序值，数值越小优先级越高。
     *
     * <p>本处理器返回 {@code Ordered.LOWEST_PRECEDENCE - 200}，意味着它在所有
     * 基础设施 Bean（如 ToolRegistry、ChatModelRegistry 等）初始化完成之后才执行。
     * 这个顺序设计确保了：</p>
     * <ul>
     *   <li>ChatModelRegistry 已经由自动配置创建并注入到 Spring 容器中</li>
     *   <li>其他更高优先级的后处理器（如存储后端注册器）已经完成扫描</li>
     *   <li>本处理器扫描 @ChatModel 注解时依赖的注册表实例已经完全就绪</li>
     * </ul>
     *
     * @return {@link Ordered#LOWEST_PRECEDENCE} - 200，即 {@code Integer.MAX_VALUE - 200}
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 200;
    }

    /**
     * Spring Bean 后处理器核心方法，在 Bean 初始化完成后被容器调用，负责发现和注册聊天模型。
     *
     * <p><b>处理流程详解：</b></p>
     * <ol>
     *   <li><b>注解检测：</b>通过 {@code bean.getClass().getAnnotation(ChatModel.class)} 检查当前
     *       Bean 是否标注了 {@code @ChatModel} 注解，若未标注则直接返回原 Bean，不做任何处理。</li>
     *   <li><b>接口校验：</b>强制要求标注了 @ChatModel 的 Bean 必须实现 {@link ChatModel} 接口，
     *       如果未实现则抛出 {@link LyClawException} 异常（错误码 ADAPTER_NOT_FOUND），防止
     *       配置错误导致后续运行时异常。</li>
     *   <li><b>Provider 名称提取：</b>从注解的 {@code provider()} 属性获取 Provider 标识，
     *       如果未显式指定则自动将类名首字母小写作为默认 Provider 名称，减少开发者的配置负担。</li>
     *   <li><b>能力声明提取：</b>调用 {@link #extractCapabilities(Class)} 从类上的
     *       {@code @ModelCapability} 注解中提取模型能力声明（流式输出、工具调用、思考能力、
     *       视觉能力、提示缓存、最大输入/输出 token 数等），未声明时回退为 OpenAI 默认能力集。</li>
     *   <li><b>协议校验：</b>对于非 CUSTOM 协议类型的模型，检查 {@code defaultBaseUrl} 和
     *       {@code defaultModel} 是否已配置，未配置时记录警告日志但不会阻止注册，因为可能在
     *       外部配置文件中提供这些值。</li>
     *   <li><b>元数据构建：</b>将提取的所有信息组合成 {@link ChatModelMetadata} 对象，
     *       作为模型的标准化元数据描述。</li>
     *   <li><b>装饰器包装与注册：</b>如果注解的 {@code autoRegister} 为 true（默认开启），
     *       调用 {@link #applyDecorators(ChatModel, Class)} 对原始 ChatModel 进行装饰器链包装
     *       （按 Fallback → RetryPolicy → CircuitBreaker 的层次顺序），然后将包装后的模型连同
     *       元数据一起注册到 ChatModelRegistry。如果未指定 model 名称，自动生成
     *       "{provider}-default" 作为默认名称。</li>
     * </ol>
     *
     * @param bean Spring 容器中已初始化的 Bean 实例，可能带有 @ChatModel 注解
     * @param beanName Bean 在 Spring 容器中的注册名称，用于日志记录和问题定位
     * @return 始终返回原始 bean 实例（不修改 bean 对象引用，注册操作仅在 Registry 内部完成）
     * @throws BeansException 当 Bean 标注了 @ChatModel 但未实现 ChatModel 接口时抛出异常
     */
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

        // 注册到 ChatModelRegistry，同时调用 applyDecorators() 应用装饰器包装链。
        // 装饰器链按 CircuitBreaker→Retry→Fallback 的顺序逐层包裹原始 ChatModel，
        // 为模型调用提供断路器熔断、重试策略和回退处理的弹性能力。
        // 如果 autoRegister 为 false，则跳过注册，允许开发者手动控制注册时机。
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
        log.info("===============应用装饰器包装链================");
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
        log.info("===============================");
        log.info("");
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
