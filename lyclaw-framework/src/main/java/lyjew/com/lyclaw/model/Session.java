package lyjew.com.lyclaw.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话模型，表示一次完整的 AI 对话会话。
 *
 * 每个会话通过 sessionId 唯一标识，维护完整的消息历史列表（messages）。
 * 不带任何持久化语义，纯运行时内存对象。
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
    /** 会话默认使用的模型名称 */
    private String model;

    /** 会话中的消息历史列表 */
    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    /**
     * 向会话追加一条消息。
     */
    private static final int MAX_MESSAGES = 500;

    public void addMessage(Message message) {
        if (this.messages == null) {
            this.messages = new ArrayList<>();
        }
        this.messages.add(message);
        // 防止长会话导致 OOM
        if (this.messages.size() > MAX_MESSAGES) {
            this.messages = new ArrayList<>(this.messages.subList(this.messages.size() - MAX_MESSAGES, this.messages.size()));
        }
    }
}
