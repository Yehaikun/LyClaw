package lyjew.com.lyclaw.chat;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 默认 ChatModelRegistry 实现。
 *
 * <p>使用 ConcurrentHashMap 维护 provider → (model → ChatModel) 的二级映射，
 * 线程安全，适合启动时注册、运行时仅读取的场景。
 */
public class DefaultChatModelRegistry implements ChatModelRegistry {

    /** provider → (modelName → ChatModel) */
    private final Map<String, Map<String, ChatModel>> registry = new ConcurrentHashMap<>();
    /** (provider, modelName) → metadata */
    private final Map<String, ChatModelMetadata> metadataMap = new ConcurrentHashMap<>();

    @Override
    public void register(String provider, String modelName, ChatModel chatModel, ChatModelMetadata metadata) {
        registry.computeIfAbsent(provider, k -> new ConcurrentHashMap<>())
                .put(modelName, chatModel);
        metadataMap.put(provider + ":" + modelName, metadata);
    }

    @Override
    public ChatModel resolve(String provider, String modelName) {
        Map<String, ChatModel> models = registry.get(provider);
        if (models == null) return null;
        return models.get(modelName);
    }

    @Override
    public ChatModel resolve(RoutingDecision decision) {
        if (decision == null) return null;
        ChatModel model = resolve(decision.provider(), decision.model());
        if (model != null) return model;
        // 回退：尝试该 Provider 下的第一个模型
        List<ChatModel> list = listByProvider(decision.provider());
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public List<ChatModel> listByProvider(String provider) {
        Map<String, ChatModel> models = registry.get(provider);
        if (models == null) return Collections.emptyList();
        return List.copyOf(models.values());
    }

    @Override
    public Map<String, List<ChatModel>> getAll() {
        Map<String, List<ChatModel>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, ChatModel>> entry : registry.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue().values()));
        }
        return result;
    }

    @Override
    public boolean hasModel(String provider, String modelName) {
        Map<String, ChatModel> models = registry.get(provider);
        return models != null && models.containsKey(modelName);
    }

    @Override
    public ChatModelMetadata getMetadata(String provider, String modelName) {
        return metadataMap.get(provider + ":" + modelName);
    }

    @Override
    public List<String> getModelNames(String provider) {
        Map<String, ChatModel> models = registry.get(provider);
        if (models == null) return Collections.emptyList();
        return models.keySet().stream().sorted().collect(Collectors.toList());
    }
}
