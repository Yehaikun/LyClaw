package lyjew.com.lyclaw.chat;

import lyjew.com.lyclaw.annotation.chat.ChatModel.ModelProtocol;

/**
 * ChatModel 元数据记录，对应 {@code @ChatModel} 注解的数据载体。
 */
public record ChatModelMetadata(
        String provider,
        String displayName,
        String description,
        ModelProtocol protocol,
        ModelCapabilities capabilities,
        String defaultModel,
        String defaultBaseUrl,
        String version,
        int priority) {

    public static ChatModelMetadata fromAnnotation(String provider, String displayName,
            String description, ModelProtocol protocol, ModelCapabilities caps,
            String defaultModel, String defaultBaseUrl, String version, int priority) {
        return new ChatModelMetadata(provider, displayName, description,
                protocol, caps, defaultModel, defaultBaseUrl, version, priority);
    }
}
