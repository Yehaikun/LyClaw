package lyjew.com.lyclaw.autoconfigure.config;

import lyjew.com.lyclaw.chat.*;
import lyjew.com.lyclaw.config.AgentConfig;
import lyjew.com.lyclaw.enums.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 ChatModelRegistry 的默认聊天模型提供者实现。
 *
 * <p>根据 AgentConfig 中指定的 provider + model 从注册表中查找匹配的 ChatModel，
 * 支持回退到默认模型。
 */
public class DefaultChatModelProvider implements ChatModelProvider {

    private final ChatModelRegistry registry;
    private final ChatProperties chatProperties;

    public DefaultChatModelProvider(ChatModelRegistry registry, ChatProperties chatProperties) {
        this.registry = registry;
        this.chatProperties = chatProperties;
    }

    @Override
    public ChatModel resolve(AgentConfig config) {
        String provider = config.getProvider();
        String model = config.getModel();

        // 1. 精确匹配: provider + model
        if (provider != null && model != null) {
            ChatModel found = registry.resolve(provider, model);
            if (found != null) return found;
        }

        // 2. 按 provider 匹配
        if (provider != null) {
            List<ChatModel> byProvider = registry.listByProvider(provider);
            if (byProvider != null && !byProvider.isEmpty()) return byProvider.get(0);
        }

        // 3. 回退到默认
        if (chatProperties != null) {
            String defaultProvider = chatProperties.getDefaultProvider();
            String defaultModel = chatProperties.getDefaultModel();
            if (defaultProvider != null && defaultModel != null) {
                ChatModel fallback = registry.resolve(defaultProvider, defaultModel);
                if (fallback != null) return fallback;
            }
            if (defaultProvider != null) {
                List<ChatModel> byProvider = registry.listByProvider(defaultProvider);
                if (byProvider != null && !byProvider.isEmpty()) return byProvider.get(0);
            }
        }

        // 4. 取注册表中任意一个
        Map<String, List<ChatModel>> all = registry.getAll();
        if (all != null && !all.isEmpty()) {
            for (List<ChatModel> models : all.values()) {
                if (models != null && !models.isEmpty()) return models.get(0);
            }
        }

        throw ErrorCode.MODEL_CONFIG_NOT_FOUND
                .exception("无法找到可用的 ChatModel: provider=" + provider + ", model=" + model);
    }

    @Override
    public List<String> supportedModels() {
        List<String> names = new ArrayList<>();
        Map<String, List<ChatModel>> all = registry.getAll();
        if (all != null) {
            for (String provider : all.keySet()) {
                names.addAll(registry.getModelNames(provider));
            }
        }
        return names;
    }
}
