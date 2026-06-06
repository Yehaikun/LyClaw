package lyjew.com.lyclaw.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话模型，表示一次完整的 AI 对话会话。
 *
 * <p>每个会话通过 sessionId 唯一标识，维护完整的消息历史列表（messages）。
 * 带有生命周期状态管理和扩展元数据能力。</p>
 *
 * <p>兼容旧版序列化：新增字段都有 {@code @JsonInclude(NON_NULL)} 或 {@code @Builder.Default}
 * 默认值，旧版 JSON 反序列化不受影响。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Session {
    /** 会话唯一标识 */
    private String sessionId;
    /** 会话名称，用于 UI 显示 */
    private String name;
    /** 关联的 Agent ID */
    private String agentId;
    /** 会话默认使用的模型名称 */
    private String model;

    /** 会话生命周期状态 */
    @Builder.Default
    private SessionStatus status = SessionStatus.ACTIVE;

    /** 用户自定义标签 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Builder.Default
    private Map<String, String> tags = new HashMap<>();

    /** 用户身份标识 */
    private String userId;

    /** 创建时间戳（毫秒） */
    @Builder.Default
    private long createdAt = System.currentTimeMillis();

    /** 最后更新时间戳（毫秒） */
    @Builder.Default
    private long updatedAt = System.currentTimeMillis();

    /** 最后活跃时间戳（毫秒） */
    @Builder.Default
    private long lastActiveAt = System.currentTimeMillis();

    /** 会话中消息的总数 */
    @Builder.Default
    private int messageCount = 0;

    /** 会话的估计 Token 总数（近似） */
    @Builder.Default
    private int estimatedTokenCount = 0;

    /** 扩展元数据（JSON 字符串，用于存储额外的自定义数据） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String metadataJson;

    /** 会话中的消息历史列表（仅供兼容旧版，新代码优先使用 MessageStore） */
    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    private static final int MAX_MESSAGES = 500;

    /**
     * 向会话追加一条消息（仅保留在内存列表中，持久化需通过 MessageStore）。
     */
    public void addMessage(Message message) {
        if (this.messages == null) {
            this.messages = new ArrayList<>();
        }
        this.messages.add(message);
        this.messageCount = this.messages.size();
        this.lastActiveAt = System.currentTimeMillis();
        // 防止长会话导致 OOM
        if (this.messages.size() > MAX_MESSAGES) {
            this.messages = new ArrayList<>(this.messages.subList(this.messages.size() - MAX_MESSAGES, this.messages.size()));
        }
    }
}
