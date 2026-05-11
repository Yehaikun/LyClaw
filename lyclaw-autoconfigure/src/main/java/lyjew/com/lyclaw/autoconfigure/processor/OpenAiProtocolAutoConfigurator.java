package lyjew.com.lyclaw.autoconfigure.processor;

import lyjew.com.lyclaw.chat.*;
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

    public OpenAiProtocolAutoConfigurator(ChatModelRegistry registry, ChatProperties chatProperties) {
        this.registry = registry;
        this.chatProperties = chatProperties;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 180;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

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

        // 构建 ModelConfig
        ModelConfig modelConfig = new ModelConfig();
        modelConfig.setBaseUrl(baseUrl);
        modelConfig.setApiKey(apiKey);
        modelConfig.setModel(model);

        // 创建 OpenAiProtocolChatModel
        lyjew.com.lyclaw.adapter.OpenAiProtocolChatModel chatModel =
                new lyjew.com.lyclaw.adapter.OpenAiProtocolChatModel(configKey, modelConfig);

        // 注册
        ModelCapabilities caps = ModelCapabilities.openAiDefaults();
        ChatModelMetadata metadata = ChatModelMetadata.fromAnnotation(
                configKey, configKey, "配置自动创建: " + baseUrl,
                lyjew.com.lyclaw.annotation.chat.ChatModel.ModelProtocol.OPENAI,
                caps, model, baseUrl, "1.0.0", 0);

        registry.register(configKey, model, chatModel, metadata);
        log.info("自动创建 OpenAI 协议 ChatModel: provider={}, model={}, baseUrl={}",
                configKey, model, baseUrl);
    }
}
