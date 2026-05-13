package lyjew.com.lyclaw.autoconfigure.processor;

import lyjew.com.lyclaw.annotation.chat.CircuitBreaker;
import lyjew.com.lyclaw.annotation.chat.Fallback;
import lyjew.com.lyclaw.annotation.chat.RetryPolicy;
import lyjew.com.lyclaw.chat.*;
import lyjew.com.lyclaw.decorator.CircuitBreakerChatModel;
import lyjew.com.lyclaw.decorator.FallbackChatModel;
import lyjew.com.lyclaw.decorator.RetryChatModel;
import lyjew.com.lyclaw.model.ModelConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.Ordered;

import java.util.Map;

/**
 * OpenAI 协议自动配置器——"配置即 Provider"的核心实现。
 *
 * <p>启动时读取 lyclaw.chat.models.* 配置，对每个 provider=openai-protocol 的条目
 * 自动创建 OpenAiProtocolChatModel 实例并注册到 ChatModelRegistry。
 * 这就是"不需要写 Java 代码就能新增 AI 模型"的设计——改 YAML 即可。</p>
 *
 * <p>注解声明的 @ChatModel Bean 优先级高于配置创建的 Bean——如果已存在同名的 ChatModel，
 * 则跳过配置创建（防止覆盖用户的自定义实现）。</p>
 */
public class OpenAiProtocolAutoConfigurator implements InitializingBean, ApplicationContextAware, Ordered {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProtocolAutoConfigurator.class);

    private ApplicationContext applicationContext;
    private final ChatModelRegistry registry;
    private final ChatProperties chatProperties;

    /**
     * 构造 OpenAI 协议自动配置器，注入 ChatModelRegistry 和 ChatProperties 依赖。
     *
     * <p>ChatModelRegistry 用于注册配置驱动的模型实例；ChatProperties 提供所有
     * {@code lyclaw.chat.models.*} 下的模型配置数据，包括 provider 类型、baseUrl、
     * apiKey、model 名称等。通过构造器注入确保两个依赖在配置器激活前都已就绪。</p>
     *
     * @param registry ChatModel 注册表实例，由 ChatAutoConfiguration 创建并注入
     * @param chatProperties 聊天配置属性实例，由 ChatAutoConfiguration 创建并绑定配置
     */
    public OpenAiProtocolAutoConfigurator(ChatModelRegistry registry, ChatProperties chatProperties) {
        this.registry = registry;
        this.chatProperties = chatProperties;
    }

    /**
     * 返回此 InitializingBean 的执行顺序值，数值越小优先级越高。
     *
     * <p>返回 {@code Ordered.LOWEST_PRECEDENCE - 180}，在 ChatModelPostProcessor
     * （-200，处理注解声明的模型）之后、ModelRouterPostProcessor（-190）之前执行。
     * 这个执行顺序设计确保了：注解声明的 @ChatModel Bean 已经处理完毕（注解优先于配置），
     * 配置驱动的模型注册在注解模型之后进行（避免覆盖用户实现），模型路由器在所有模型
     * 注册完毕后统一处理。</p>
     *
     * @return {@link Ordered#LOWEST_PRECEDENCE} - 180
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 180;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * InitializingBean 回调方法，在所有 Bean 属性设置完成后由 Spring 容器调用，
     * 负责根据配置文件动态创建和注册聊天模型实例，实现"配置即 Provider"的核心逻辑。
     *
     * <p><b>处理流程：</b></p>
     * <ol>
     *   <li><b>旧版模式检查：</b>如果 {@code lyclaw.chat.legacy=true}，说明使用者
     *       使用的是旧版单一模型配置模式，跳过自动配置（旧版模式使用 llm.* 配置项，
     *       由其他配置类处理）。</li>
     *   <li><b>模型配置遍历：</b>遍历 {@code lyclaw.chat.models.*} 下所有配置条目，
     *       每个条目的 key 作为 Provider 名称，value 中的 provider 字段决定模型类型。
     *       如果某条目不指定 provider 类型，记录 DEBUG 日志并跳过。</li>
     *   <li><b>Provider 类型分发：</b>根据 provider 字段值进行分发处理——
     *       "openai-protocol" 或 "openai" 调用 {@link #createOpenAiProtocolModel(String, ChatProperties.ModelProperties)}
     *       创建兼容 OpenAI 协议的模型；"anthropic"、"ollama"、"gemini" 等类型当前仅
     *       输出提示日志，需要对应的 SDK 在 classpath 中才能激活；未知类型输出警告日志。</li>
     * </ol>
     *
     * <p><b>注解优先原则：</b>{@link #createOpenAiProtocolModel} 在执行时会首先检查
     * Registry 中是否已存在同名 Provider（由 @ChatModel 注解 Bean 注册），若已存在
     * 则跳过配置创建，确保注解声明的模型始终优先于配置驱动的模型。</p>
     *
     * <p><b>弹性装饰器：</b>每个配置创建的模型会自动应用弹性装饰器链——
     * 通过 {@link #wrapWithResilience(ChatModel)} 检测类上的 @RetryPolicy、
     * @Fallback、@CircuitBreaker 注解，并自动生成对应的装饰器包装链。对于没有显式
     * 声明重试策略的普通模型，自动应用默认重试策略（最大3次，指数退避，1秒基准间隔）。</p>
     *
     * @throws Exception 当模型创建或注册过程中发生不可恢复的错误时抛出
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        if (chatProperties.isLegacy()) {
            log.info("Chat 旧版模式 (lyclaw.chat.legacy=true)，跳过 OpenAI 协议自动配置");
            return;
        }

        Map<String, ChatProperties.ModelProperties> models = chatProperties.getModels();
        if (models == null || models.isEmpty()) {
            log.info("未配置 lyclaw.chat.models.*，跳过 OpenAI 协议自动配置");
            return;
        }

        for (Map.Entry<String, ChatProperties.ModelProperties> entry : models.entrySet()) {
            String configKey = entry.getKey();
            ChatProperties.ModelProperties props = entry.getValue();

            String providerType = props.getProvider();
            if (providerType == null) {
                log.debug("配置 lyclaw.chat.models.{} 未指定 provider 类型，跳过", configKey);
                continue;
            }

            switch (providerType.toLowerCase()) {
                case "openai-protocol":
                case "openai":
                    createOpenAiProtocolModel(configKey, props);
                    break;
                case "anthropic":
                    log.info("Anthropic Provider {} 需 classpath 中有 Anthropic SDK 才能激活", configKey);
                    break;
                case "ollama":
                    log.info("Ollama Provider {} 需 classpath 中有 Ollama 客户端才能激活", configKey);
                    break;
                case "gemini":
                    log.info("Gemini Provider {} 需 classpath 中有 Gemini SDK 才能激活", configKey);
                    break;
                default:
                    log.warn("未知 Provider 类型: {} (配置键: {})", providerType, configKey);
            }
        }
    }

    private void createOpenAiProtocolModel(String configKey, ChatProperties.ModelProperties props) {
        // 检查是否已有同名注解 Bean（注解优先）
        if (registry.listByProvider(configKey) != null && !registry.listByProvider(configKey).isEmpty()) {
            log.info("Provider {} 已有注解声明的 ChatModel，跳过配置创建", configKey);
            return;
        }

        String baseUrl = props.getBaseUrl();
        String apiKey = props.getApiKey();
        String model = props.getModel();
        if (model == null) model = configKey + "-default";
        if (baseUrl == null) baseUrl = "https://api." + configKey + ".com";

        ModelConfig modelConfig = new ModelConfig();
        modelConfig.setBaseUrl(baseUrl);
        modelConfig.setApiKey(apiKey);
        modelConfig.setModel(model);

        // 对于 deepseek 使用带弹性注解的 DeepSeekChatModel
        ChatModel chatModel;
        if ("deepseek".equalsIgnoreCase(configKey)) {
            chatModel = new lyjew.com.lyclaw.adapter.DeepSeekChatModel(modelConfig);
            log.info("使用 DeepSeekChatModel (带 @RetryPolicy/@Fallback/@CircuitBreaker)");
        } else {
            chatModel = new lyjew.com.lyclaw.adapter.OpenAiProtocolChatModel(configKey, modelConfig);
        }

        // 应用弹性装饰器
        chatModel = wrapWithResilience(chatModel);

        ModelCapabilities caps = ModelCapabilities.openAiDefaults();
        ChatModelMetadata metadata = ChatModelMetadata.fromAnnotation(
                configKey, configKey, "配置自动创建: " + baseUrl,
                lyjew.com.lyclaw.annotation.chat.ChatModel.ModelProtocol.OPENAI,
                caps, model, baseUrl, "1.0.0", 0);

        registry.register(configKey, model, chatModel, metadata);
        log.info("自动创建 OpenAI 协议 ChatModel: provider={}, model={}, baseUrl={}",
                configKey, model, baseUrl);
    }

    private ChatModel wrapWithResilience(ChatModel raw) {
        ChatModel wrapped = raw;
        Class<?> clazz = raw.getClass();

        // 检测类上的弹性注解（DeepSeekChatModel 有这些）
        if (clazz.isAnnotationPresent(Fallback.class)) {
            Fallback fb = clazz.getAnnotation(Fallback.class);
            log.info("自动配置: 对 {} 应用 @Fallback chain={}", clazz.getSimpleName(),
                    java.util.Arrays.toString(fb.chain()));
            wrapped = new FallbackChatModel(wrapped, fb, registry);
        }

        if (clazz.isAnnotationPresent(RetryPolicy.class)) {
            RetryPolicy rp = clazz.getAnnotation(RetryPolicy.class);
            log.info("自动配置: 对 {} 应用 @RetryPolicy maxAttempts={}", clazz.getSimpleName(),
                    rp.maxAttempts());
            wrapped = new RetryChatModel(wrapped, rp);
        }

        if (clazz.isAnnotationPresent(CircuitBreaker.class)) {
            CircuitBreaker cb = clazz.getAnnotation(CircuitBreaker.class);
            log.info("自动配置: 对 {} 应用 @CircuitBreaker failureThreshold={}", clazz.getSimpleName(),
                    cb.failureThreshold());
            wrapped = new CircuitBreakerChatModel(wrapped, cb);
        }

        // 对于没有 @RetryPolicy 的普通模型，应用默认重试策略
        if (!clazz.isAnnotationPresent(RetryPolicy.class)) {
            log.info("自动配置: 对 {} 应用默认重试策略 (3次/指数退避/1s基准)", clazz.getSimpleName());
            wrapped = new RetryChatModel(wrapped, 3, 1000,
                    RetryPolicy.BackoffStrategy.EXPONENTIAL, 0.1);
        }

        return wrapped;
    }
}
