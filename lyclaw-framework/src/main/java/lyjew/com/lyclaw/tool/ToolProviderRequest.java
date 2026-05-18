package lyjew.com.lyclaw.tool;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import lyjew.com.lyclaw.model.ChatRequest;

/**
 * 工具提供请求 — 封装了触发工具提供的调用上下文信息。
 *
 * <p>传递给 {@link ToolProvider#provideTools(ToolProviderRequest)} 的参数，
 * 包含当前 ChatRequest 和可扩展的上下文属性。
 */
public class ToolProviderRequest {

    private final ChatRequest chatRequest;
    private final Map<String, Object> attributes;

    public ToolProviderRequest(ChatRequest chatRequest) {
        this(chatRequest, Collections.emptyMap());
    }

    public ToolProviderRequest(ChatRequest chatRequest, Map<String, Object> attributes) {
        this.chatRequest = chatRequest;
        this.attributes = attributes != null ? new HashMap<>(attributes) : new HashMap<>();
    }

    /** 当前聊天请求，包含消息历史、模型选择等 */
    public ChatRequest getChatRequest() {
        return chatRequest;
    }

    /** 扩展属性（如用户权限、沙箱等级等） */
    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }
}
